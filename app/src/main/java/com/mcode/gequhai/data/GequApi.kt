package com.mcode.gequhai.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 *     2) 若 appData.mp3_url 缺失 (服务器端 HTML 静态版不带此字段, 是 plugin.js
 *        运行时通过 /api/geturl + iLingKu_Decode 注入的) 则 fallback 到 WebView
 *        加载播放页, 等 JS 跑完后从 window.appData.mp3_url 读取
 */
class GequApi(context: Context) {

    private val appContext: Context = context.applicationContext

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        // 补全浏览器指纹, 防 m.gequba.com WAF 返 403
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent",      UA)
                .header("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Sec-Fetch-Dest",  "document")
                .header("Sec-Fetch-Mode",  "navigate")
                .header("Sec-Fetch-Site",  "same-origin")
                .header("Sec-Fetch-User",  "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Referer", if (chain.request().url.host.endsWith("gequba.com")) BASE + "/" else chain.request().url.toString())
                .build()
            chain.proceed(req)
        }
        .build()

    /** 搜索: 第 1 页到第 endPage 页 (含) */
    suspend fun search(
        keyword: String,
        startPage: Int = 1,
        endPage: Int = 3,
        onPage: suspend (page: Int, items: List<SearchItem>) -> Unit = { _, _ -> }
    ): List<SearchItem> = withContext(Dispatchers.IO) {
        val all = mutableListOf<SearchItem>()
        for (p in startPage..endPage) {
            val url = if (p == 1)
                "$BASE/s/${URLEncoder.encode(keyword, "UTF-8")}"
            else
                "$BASE/s/${URLEncoder.encode(keyword, "UTF-8")}/$p"
            val html = get(url)
            val items = parseSearchHtml(html)
            onPage(p, items)
            all += items
        }
        all.distinctBy { it.hash }
    }

    /**
     * 解析播放页, 拿到完整 SongMeta (含 mp3_url).
     *
     * 流程:
     *   1) OkHttp 抓 HTML, 解析 window.appData JSON.parse('\u0022...') -> 静态字段
     *   2) 若 mp3_url 字段为空 (服务端 HTML 静态版本不带此字段, plugin.js 才会注入)
     *      则用 WebView 加载播放页, 等 JS 跑完后从 window.appData.mp3_url 读取
     */
    suspend fun resolve(item: SearchItem): SongMeta = withContext(Dispatchers.IO) {
        val html = get(item.detailUrl)
        val regex = Regex("""window\.appData\s*=\s*JSON\.parse\(\s*'([\s\S]*?)'\s*\)""")
        val raw = regex.find(html)?.groupValues?.get(1)
            ?: error("appData not found at ${item.detailUrl}")
        // HTML 里这一行是 window.appData = JSON.parse('\u0022web_code\u0022:...')
        // \u0022 是 JS 字符串里的 " 转义, 必须先反转义为字面字符
        val jsonStr = unescapeUnicode(raw)
        val obj = JSONObject(jsonStr)
        val staticUrl = obj.optString("mp3_url")
        val base = SongMeta(
            mp3Id     = obj.optString("mp3_id", item.hash),
            playId    = obj.optString("play_id"),
            title     = obj.optString("mp3_title", item.title),
            author    = obj.optString("mp3_author", item.artist),
            cover     = obj.optString("mp3_cover"),
            duration  = obj.optString("mp3_duration"),
            mp3Type   = obj.optInt("mp3_type"),
            mp3Url    = staticUrl,
        )
        if (staticUrl.isNotBlank()) return@withContext base

        // Fallback: WebView 加载, 等 plugin.js 跑完拿到 mp3_url
        val realUrl = withContext(Dispatchers.Main) { fetchMp3UrlViaWebView(item.detailUrl) }
        base.copy(mp3Url = realUrl)
    }

    // ---------------- 内部 ----------------

    private fun get(url: String): String {
        var lastErr: Throwable? = null
        for (attempt in 1..3) {
            val req = Request.Builder().url(url).build()
            try {
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) return r.body?.string() ?: error("empty body")
                    if (r.code == 403 || r.code in 500..599) {
                        lastErr = error("HTTP ${r.code} $url (attempt $attempt/3)")
                        Thread.sleep(1000L * attempt)
                        return@use
                    }
                    error("HTTP ${r.code} $url")
                }
            } catch (e: Throwable) {
                lastErr = e
                Thread.sleep(500L * attempt)
            }
        }
        throw lastErr ?: error("get failed: $url")
    }

    /**
     * 用隐藏 WebView 加载播放页, 等 plugin.js + crypto-js 全部执行完后
     * 读取 window.appData.mp3_url / mp3_url_a.
     *
     * 注意: WebView 必须在主线程创建与操作, 这里 suspendCancellableCoroutine 配合 Dispatchers.Main.
     */
    private suspend fun fetchMp3UrlViaWebView(url: String): String =
        suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            val wv = WebView(appContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = UA
            // 隐藏: 0x0 像素 + invisible
            wv.setBackgroundColor(0x00000000)

            // postDelayed 返回 Boolean (表示是否成功 enqueue), 不是 Runnable.
            // 保留 Runnable 引用以便 removeCallbacks / invokeOnCancellation 使用.
            val timeoutRunnable = Runnable {
                if (cont.isActive) {
                    wv.destroy()
                    cont.resumeWithException(IllegalStateException("WebView timeout ($url)"))
                }
            }
            mainHandler.postDelayed(timeoutRunnable, 10_000L)

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedURL: String) {
                    // plugin.js 通常在 DOMContentLoaded 后立刻调 /api/geturl,
                    // onPageFinished 后基本就绪. 再等 600ms 让 mp3_url 落地.
                    mainHandler.postDelayed({
                        view.evaluateJavascript(
                            "(function(){try{return JSON.stringify(" +
                                "{u:window.appData&&window.appData.mp3_url," +
                                " a:window.appData&&window.appData.mp3_url_a," +
                                " t:window.appData&&window.appData.mp3_type," +
                                " ut:window.appData&&window.appData.ut," +
                                " w:window.appData&&window.appData.is_white_url});" +
                                "}catch(e){return 'ERR:'+e.message;}})()"
                        ) { raw ->
                                mainHandler.removeCallbacks(timeoutRunnable)
                                val safe = raw?.trim('"')?.replace("\\\"", "\"") ?: ""
                                try {
                                    val obj = JSONObject(safe)
                                    val u = obj.optString("u").takeIf { it.isNotBlank() && it != "null" && it != "ERR:" }
                                        ?: obj.optString("a").takeIf { it.isNotBlank() && it != "null" }
                                    wv.destroy()
                                    if (u.isNullOrBlank()) {
                                        cont.resumeWithException(IllegalStateException("mp3_url still empty after WebView load"))
                                    } else {
                                        cont.resume(u)
                                    }
                                } catch (e: Throwable) {
                                    wv.destroy()
                                    cont.resumeWithException(e)
                                }
                            }
                    }, 600L)
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

    /**
     * 把字符串里的 \uXXXX 反转义成真实字符 (BMP 范围).
     * 处理 gequba.com 播放页 HTML 里 JSON.parse('\u0022...') 这种转义后的 JSON 串.
     */
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
                            'f'  -> out.append(12.toChar())  // form feed
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
        const val UA   = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    }
}