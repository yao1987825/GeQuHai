package com.mcode.gequhai.data

import android.util.Log

/**
 * 一行歌词
 */
data class LrcEntry(
    val start: Long,   // ms
    val end: Long,     // ms, 给 UI 做高亮切换
    val text: String,
)

/**
 * LRC 解析器. 兼容:
 *   [00:00.00]爱错 - 王力宏 (Leehom Wang)
 *   [00:04.90][00:08.10]同一时刻多次出现         (一行多时间标签, 复制同一段)
 *   [ar:王力宏]   [ti:爱错]   [al:专辑]   (元信息跳过)
 *   [00:04.90]词：王力宏/崔惟楷
 *   HTML 实体 &#58; / &#10; / &apos; / &quot; / <br />
 *
 * m.gequba.com 的格式: [mm:ss.xx]text  (毫秒 2 位)
 */
object LrcParser {

    private const val TAG = "LrcParser"

    // [ar:xxx] [ti:xxx] [al:xxx] [by:xxx] [offset:xxx] 等元信息
    private val META_LINE = Regex("""^\s*\[(ti|ar|al|by|offset|length|re|ve)[:\s].*]\s*$""", RegexOption.IGNORE_CASE)

    // 时间标签: [mm:ss.xx] 或 [mm:ss.xxx] 或 [mm:ss:xx]  (m.gequba 用 .xx, 标准是 .xxx)
    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})[.:](\d{1,3})]""")

    fun parse(raw: String?): List<LrcEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        // 还原 HTML 实体 + <br>
        val text = raw
            .replace("&#58;", ":")
            .replace("&#10;", "\n")
            .replace("&#13;", "\r")
            .replace("&#46;", ".")
            .replace("&#32;", " ")
            .replace("&#45;", "-")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("<br />", "\n", ignoreCase = true)
            .replace("<br/>", "\n", ignoreCase = true)
            .replace("<br>", "\n", ignoreCase = true)

        val out = mutableListOf<LrcEntry>()
        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (META_LINE.matches(trimmed)) continue

            val stamps = STAMP.findAll(trimmed).toList()
            if (stamps.isEmpty()) continue

            // 去掉所有时间标签, 剩下的就是文本 (可能还有 metadata 比如 (Leehom Wang))
            val lyric = STAMP.replace(trimmed, "").trim()
            if (lyric.isEmpty()) continue

            for (m in stamps) {
                val mm = m.groupValues[1].toLongOrNull() ?: continue
                val ss = m.groupValues[2].toLongOrNull() ?: continue
                val msRaw = m.groupValues[3].toLongOrNull() ?: continue
                val ms = when {
                    msRaw < 10  -> msRaw * 100   // x
                    msRaw < 100 -> msRaw * 10    // xx
                    else        -> msRaw          // xxx
                }
                val start = mm * 60_000 + ss * 1_000 + ms
                out.add(LrcEntry(start = start, end = 0, text = lyric))
            }
        }

        if (out.isEmpty()) return emptyList()

        out.sortBy { it.start }

        // 填 end: 当前行 end = 下一行 start; 末行 end = start + 5s
        for (i in out.indices) {
            val nxt = if (i + 1 < out.size) out[i + 1].start else out[i].start + 5_000
            out[i] = out[i].copy(end = nxt)
        }

        Log.d(TAG, "parsed ${out.size} LRC entries")
        return out
    }
}