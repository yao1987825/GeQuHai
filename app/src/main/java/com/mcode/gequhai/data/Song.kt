package com.mcode.gequhai.data

/**
 * 搜索结果条目 (来自 m.gequba.com 搜索页 HTML)
 */
data class SearchItem(
    val hash: String,
    val title: String,
    val artist: String,
    val detailUrl: String,
)

/**
 * 单首歌的完整元数据 + 播放 URL (来自播放页 window.appData)
 */
data class SongMeta(
    val mp3Id: String,
    val playId: String,
    val title: String,
    val author: String,
    val cover: String,
    val duration: String,
    val mp3Type: Int,
    val mp3Url: String,
    val source: String = "m.gequba.com",
)

/**
 * 下载任务条目 (UI 上展示)
 */
data class DownloadItem(
    val id: String,
    val title: String,
    val artist: String,
    val cover: String,
    val sourceUrl: String,
    val savedPath: String? = null,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val state: DownloadState = DownloadState.Queued,
)

enum class DownloadState { Queued, Downloading, Done, Failed }