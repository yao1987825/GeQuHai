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
        val jsonStr = regex.find(html)?.groupValues?.get(1)
            ?: error("appData not found at ${item.detailUrl}")
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
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", BASE)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} $url")
            return r.body?.string() ?: error("empty body")
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

    companion object {
        const val BASE = "https://m.gequba.com"
        const val UA   = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    }
}