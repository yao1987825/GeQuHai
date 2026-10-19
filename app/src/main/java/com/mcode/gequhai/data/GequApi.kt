package com.mcode.gequhai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * m.gequba.com 抓取 API
 *
 * 三步:
 *   search(keyword)        -> [SearchItem]
 *   resolve(item)          -> SongMeta (含 mp3_url)
 *   download(song, file)   -> File (流式下载)
 *
 * 链路完全用 OkHttp + Jsoup, 不依赖 JS 引擎.
 * 对应 chrome-devtools-mcp 实测结论: appData.mp3_url 字段直接是完整 mp3 URL,
 * 无需走 /api/geturl 签名 + AES 解密链路.
 */
class GequApi {

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
                // Accept-Encoding 让 OkHttp 自动加 + 自动解压 (不要再手动 header 避免重复)
                .header("Sec-Fetch-Dest",  "document")
                .header("Sec-Fetch-Mode",  "navigate")
                .header("Sec-Fetch-Site",  "same-origin")
                .header("Sec-Fetch-User",  "?1")
                .header("Upgrade-Insecure-Requests", "1")
                // Referer 按 host 自动匹配: 搜索页和播放页都用主域
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

    /** 解析播放页: 从 window.appData JSON.parse('...') 抓 mp3_url 等 */
    suspend fun resolve(item: SearchItem): SongMeta = withContext(Dispatchers.IO) {
        val html = get(item.detailUrl)
        val regex = Regex("""window\.appData\s*=\s*JSON\.parse\(\s*'([\s\S]*?)'\s*\)""")
        val raw = regex.find(html)?.groupValues?.get(1)
            ?: error("appData not found at ${item.detailUrl}")
        // HTML 里这一行是 window.appData = JSON.parse('\u0022web_code\u0022:...')
        // \u0022 是 JS 字符串里的 " 转义, 必须先反转义为字面字符,
        // 否则 JSONObject 看到 { 后紧跟 \, 会报 "Expected property name or '}'"
        val jsonStr = unescapeUnicode(raw)
        val obj = JSONObject(jsonStr)
        val mp3Url = obj.optString("mp3_url")
        if (mp3Url.isBlank()) error("mp3_url empty (web_code=${obj.optString("web_code")})")
        SongMeta(
            mp3Id     = obj.optString("mp3_id", item.hash),
            playId    = obj.optString("play_id"),
            title     = obj.optString("mp3_title", item.title),
            author    = obj.optString("mp3_author", item.artist),
            cover     = obj.optString("mp3_cover"),
            duration  = obj.optString("mp3_duration"),
            mp3Type   = obj.optInt("mp3_type"),
            mp3Url    = mp3Url,
        )
    }

    // ---------------- 内部 ----------------

    private fun get(url: String): String {
        // header 全部由 OkHttp 拦截器统一补. 这里只放 URL.
        var lastErr: Throwable? = null
        for (attempt in 1..3) {
            val req = Request.Builder().url(url).build()
            try {
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) return r.body?.string() ?: error("empty body")
                    // 403/503 退避重试 (反爬偶发)
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
     * 支持:
     *   \u0022 -> "
     *   \u7231 -> "爱"
     *   \\     -> \     (转义的反斜杠)
     *   \"     -> "     (转义的引号)
     *   \n / \t / \r     原样保留
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