package com.mcode.gequhai.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * m.gequba.com 抓取 API
 *
 * 三步:
 *   search(keyword)        -> [SearchItem]
 *   resolve(item)          -> SongMeta (含 mp3_url)
 *   download(song, file)   -> File (流式下载)
 *
 * 链路:
 *   搜索  :  OkHttp + Jsoup 解析 HTML
 *   resolve:
 *     1) OkHttp 抓播放页 HTML, 正则 + \uXXXX 反转义解析出 window.appData
 *     2) 若 appData.mp3_url 缺失则 fallback 到 WebView, 等 JS 跑完后从 window.appData.mp3_url 读取
 *
 * 抗 WAF:
 *  - CookieJar 保留 Set-Cookie (m.gequba.com 首次访问会发 __waf 之类的 cookie)
 *  - 首次调用前预热主页拿 cookie
 *  - 403/5xx 指数退避 + 随机抖动, 中途再次预热
 *  - 每个搜索子页独立容错, 失败不影响其他页
 */
class GequApi(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 简单内存 CookieJar, 让 OkHttp 自动处理 Set-Cookie / Cookie. */
    private val cookieStore = HashMap<String, List<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host].orEmpty()
        }
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .cookieJar(cookieJar)
        // 补全浏览器指纹 + 恢复 cookie, 防 m.gequba.com WAF 返 403
        .addInterceptor { chain ->
            val req0 = chain.request()
            val url = req0.url
            val isGequba = url.host.endsWith("gequba.com")
            // 搜索分页时把上一分页当 Referer 更逼真, 否则用 BASE
            val referer = if (isGequba) BASE + "/" else req0.url.toString()
            val req = req0.newBuilder()
                .header("User-Agent",      pickUa())
                .header("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Sec-Fetch-Dest",  "document")
                .header("Sec-Fetch-Mode",  "navigate")
                .header("Sec-Fetch-Site",  if (url.encodedPath == "/") "none" else "same-origin")
                .header("Sec-Fetch-User",  "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Referer",         referer)
                .header("Cache-Control",   "no-cache")
                .header("Pragma",          "no-cache")
                .build()
            chain.proceed(req)
        }
        .build()

    /** 首次请求前的预热访问 (拿 Cookie + 触发 WAF JS challenge). */
    suspend fun warmup() = withContext(Dispatchers.IO) {
        runCatching { fetchOnce(BASE + "/", allowFailure = true) }
    }

    /** 搜索: 第 1 页到第 endPage 页 (含). 任一页失败不影响其他页. */
    suspend fun search(
        keyword: String,
        startPage: Int = 1,
        endPage: Int = 3,
        onPage: suspend (page: Int, items: List<SearchItem>, error: String?) -> Unit = { _, _, _ -> }
    ): List<SearchItem> = withContext(Dispatchers.IO) {
        // 首次调用前预热
        if (cookieStore.isEmpty() || cookieStore.values.all { it.isEmpty() }) {
            runCatching { fetchOnce(BASE + "/", allowFailure = true) }
        }
        val all = mutableListOf<SearchItem>()
        var anySuccess = false
        for (p in startPage..endPage) {
            val url = if (p == 1)
                "$BASE/s/${URLEncoder.encode(keyword, "UTF-8")}"
            else
                "$BASE/s/${URLEncoder.encode(keyword, "UTF-8")}/$p"
            val result = runCatching { get(url) }
            val items = result.fold(
                onSuccess = { html ->
                    anySuccess = true
                    parseSearchHtml(html)
                },
                onFailure = { e ->
                    onPage(p, emptyList(), e.message)
                    emptyList()
                }
            )
            onPage(p, items, result.exceptionOrNull()?.message)
            all += items
            // 页间小随机抖动, 降低同 IP 突发频率
            if (p < endPage) delay(200L + Random.nextLong(250))
        }
        // 整批失败才抛
        if (!anySuccess && all.isEmpty()) {
            throw IllegalStateException("搜索失败: 所有分页都被 WAF 拦截, 请稍后重试")
        }
        all.distinctBy { it.hash }
    }

    /**
     * 解析播放页, 拿到完整 SongMeta (含 mp3_url + 歌词).
     */
    suspend fun resolve(item: SearchItem): SongMeta = withContext(Dispatchers.IO) {
        val html = get(item.detailUrl)
        val regex = Regex("""window\.appData\s*=\s*JSON\.parse\(\s*'([\s\S]*?)'\s*\)""")
        val raw = regex.find(html)?.groupValues?.get(1)
            ?: error("appData not found at ${item.detailUrl}")
        val jsonStr = unescapeUnicode(raw)
        val obj = JSONObject(jsonStr)
        val staticUrl = obj.optString("mp3_url")
        val lrc = extractLrc(html)
        val base = SongMeta(
            mp3Id     = obj.optString("mp3_id", item.hash),
            playId    = obj.optString("play_id"),
            title     = obj.optString("mp3_title", item.title),
            author    = obj.optString("mp3_author", item.artist),
            cover     = obj.optString("mp3_cover"),
            duration  = obj.optString("mp3_duration"),
            mp3Type   = obj.optInt("mp3_type"),
            mp3Url    = staticUrl,
            lrc       = lrc,
        )
        if (staticUrl.isNotBlank()) return@withContext base

        // Fallback: WebView
        val realUrl = withContext(Dispatchers.Main) { fetchMp3UrlViaWebView(item.detailUrl) }
        base.copy(mp3Url = realUrl)
    }

    private fun extractLrc(html: String): String? {
        val m = Regex("""<div[^>]*\bid=["']content-lrc["'][^>]*>([\s\S]*?)</div>""").find(html) ?: return null
        val inner = m.groupValues[1]
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .trim()
        return inner.takeIf { it.isNotBlank() && it.contains('[') }
    }

    // ---------------- 内部 ----------------

    /**
     * 带重试 + 指数退避 + 抖动的 GET.
     *  - 403 / 5xx 重试 4 次, 间隔 1500 → 3000 → 6000 → 12000 ms (含 0-500ms 抖动)
     *  - 第 2 次失败时插一次预热, 让 WAF 重新评估
     *  - 4xx (非 403) 立即抛
     *  - 协程上下文, 用 delay 而不是 Thread.sleep, 不阻塞 dispatcher
     */
    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        var lastErr: Throwable? = null
        for (attempt in 1..4) {
            val req = Request.Builder().url(url).build()
            try {
                val body = http.newCall(req).execute().use { r ->
                    when {
                        r.isSuccessful -> r.body?.string().orEmpty()
                        r.code == 403 || r.code in 500..599 -> {
                            // 不抛, 让外层继续重试
                            null
                        }
                        else -> throw IllegalStateException("HTTP ${r.code} $url")
                    }
                }
                if (body.isNotBlank()) return@withContext body
                lastErr = IllegalStateException("empty body")
            } catch (e: Throwable) {
                if (e is IllegalStateException && e.message?.startsWith("HTTP ") == true) {
                    // 4xx (我们自己 throw 的), 直接抛给上层
                    throw e
                }
                // 其他 IO/异常, 记录后重试
                lastErr = e
            }
            if (attempt < 4) {
                val backoff = (1500L shl (attempt - 1)) + Random.nextLong(500)
                delay(backoff)
                if (attempt == 2) {
                    // 重试中插一次预热, 刷新 WAF 会话
                    runCatching { fetchOnce(BASE + "/", allowFailure = true) }
                }
            }
        }
        throw lastErr ?: IllegalStateException("get failed: $url")
    }

    /** 单次请求, 用于预热. */
    private fun fetchOnce(url: String, allowFailure: Boolean): String? {
        val req = Request.Builder().url(url).build()
        return http.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string()
            else if (allowFailure) null
            else throw IllegalStateException("HTTP ${r.code} $url")
        }
    }

    /**
     * 用隐藏 WebView 加载播放页, 等 plugin.js + crypto-js 全部执行完后
     * 读取 window.appData.mp3_url / mp3_url_a.
     */
    private suspend fun fetchMp3UrlViaWebView(url: String): String =
        suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            val startUptime = android.os.SystemClock.uptimeMillis()
            val deadlineMs = 15_000L
            val pollIntervalMs = 500L
            val wv = WebView(appContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = UA_PRIMARY
            wv.setBackgroundColor(0x00000000)

            val timeoutRunnable = Runnable {
                if (cont.isActive) {
                    wv.destroy()
                    cont.resumeWithException(IllegalStateException("WebView timeout (${deadlineMs}ms) $url"))
                }
            }
            mainHandler.postDelayed(timeoutRunnable, deadlineMs)

            fun poll() {
                if (!cont.isActive) return
                val elapsed = android.os.SystemClock.uptimeMillis() - startUptime
                if (elapsed > deadlineMs) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    wv.destroy()
                    cont.resumeWithException(IllegalStateException("mp3_url still empty after WebView load ($elapsed ms) $url"))
                    return
                }
                wv.evaluateJavascript(
                    "(function(){try{var d=window.appData;if(!d)return '';return d.mp3_url||d.mp3_url_a||''}catch(e){return 'ERR:'+e.message;}})()"
                ) { raw ->
                    if (!cont.isActive) return@evaluateJavascript
                    val url = raw?.trim('"') ?: ""
                    if (url.isNotBlank() && url != "null" && !url.startsWith("ERR:")) {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        wv.destroy()
                        cont.resume(url)
                        return@evaluateJavascript
                    }
                    mainHandler.postDelayed({ poll() }, pollIntervalMs)
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedURL: String) {
                    mainHandler.postDelayed({ poll() }, 300L)
                }
            }
            wv.loadUrl(url)

            cont.invokeOnCancellation {
                mainHandler.removeCallbacks(timeoutRunnable)
                wv.destroy()
            }
        }

    private fun parseSearchHtml(html: String): List<SearchItem> {
        val doc = Jsoup.parse(html)
        val seen = HashSet<String>()
        val out = mutableListOf<SearchItem>()
        for (a in doc.select("a[href*=/songs/]")) {
            val href = a.attr("href")
            val m = Regex("""/songs/([0-9a-f]{32})""").find(href) ?: continue
            val hash = m.groupValues[1]
            if (!seen.add(hash)) continue
            val titleAttr = a.attr("title").ifBlank { a.text() }
            val (t, ar) = if (" - " in titleAttr) {
                val parts = titleAttr.split(" - ", limit = 2)
                parts[0].trim() to parts[1].trim()
            } else titleAttr.trim() to ""
            out.add(
                SearchItem(
                    hash = hash,
                    title = t,
                    artist = ar,
                    detailUrl = href.let { if (it.startsWith("http")) it else BASE + it },
                )
            )
        }
        return out
    }

    private fun unescapeUnicode(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\' && i + 1 < n) {
                when (val nxt = s[i + 1]) {
                    'u' -> {
                        if (i + 5 < n) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        out.append(c).append(nxt); i += 2
                    }
                    '\\', '"', '/', 'b', 'f', 'n', 'r', 't' -> {
                        when (nxt) {
                            '\\' -> out.append('\\')
                            '"'  -> out.append('"')
                            '/'  -> out.append('/')
                            'b'  -> out.append('\b')
                            'f'  -> out.append(12.toChar())
                            'n'  -> out.append('\n')
                            'r'  -> out.append('\r')
                            't'  -> out.append('\t')
                        }
                        i += 2
                    }
                    else -> {
                        out.append(c); i++
                    }
                }
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    companion object {
        const val BASE = "https://m.gequba.com"
        const val UA_PRIMARY = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
        // 备用 UA 池, 403 重试时随机切换降低指纹命中率
        private val UA_POOL = arrayOf(
            UA_PRIMARY,
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; SM-G991U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1",
        )
        private fun pickUa() = UA_POOL[Random.nextInt(UA_POOL.size)]
    }
}
