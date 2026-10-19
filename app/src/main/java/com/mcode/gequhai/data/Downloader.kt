package com.mcode.gequhai.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 单例下载管理器
 *
 * - 进度更新通过 [items] StateFlow 暴露给 UI
 * - 文件落地: app 专属外部目录 MusicDir (不需要运行时权限, 卸载即清)
 *   如果以后想保存到公共 Music/ 目录, 切到 MediaStore 即可
 */
object Downloader {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private fun targetFile(context: Context, song: SongMeta): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir.resolve("music").apply { mkdirs() }
        val safe = (song.title + "-" + song.author)
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .take(120)
        val ext = when {
            song.mp3Url.endsWith(".mp3") -> "mp3"
            song.mp3Url.endsWith(".m4a") -> "m4a"
            song.mp3Url.endsWith(".aac") -> "aac"
            song.mp3Url.endsWith(".flac") -> "flac"
            else -> "m4a"
        }
        return File(dir, "$safe.$ext")
    }

    suspend fun download(context: Context, song: SongMeta): File = withContext(Dispatchers.IO) {
        val item = DownloadItem(
            id = song.mp3Id,
            title = song.title,
            artist = song.author,
            cover = song.cover,
            sourceUrl = song.mp3Url,
            state = DownloadState.Downloading,
        )
        upsert(item)

        val dest = targetFile(context, song)
        val tmp  = File(dest.parentFile, dest.name + ".part")

        val req = Request.Builder()
            .url(song.mp3Url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .header("Referer", GequApi.BASE)
            .build()

        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val total = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val body = resp.body ?: throw IOException("empty body")
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(8 * 1024)
                        var read: Int
                        var sum = 0L
                        while (input.read(buf).also { read = it } > 0) {
                            if (currentJob?.isCancelled == true) throw IOException("cancelled")
                            output.write(buf, 0, read)
                            sum += read
                            update(item.id) { it.copy(downloadedBytes = sum, totalBytes = total) }
                        }
                    }
                }
            }
            if (tmp.exists() && (dest.exists() || dest.length() == 0L)) {
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            }
            update(item.id) {
                it.copy(
                    savedPath = dest.absolutePath,
                    state = DownloadState.Done,
                    downloadedBytes = it.totalBytes.takeIf { v -> v > 0 } ?: dest.length(),
                )
            }
            dest
        } catch (e: Exception) {
            tmp.delete()
            update(item.id) { it.copy(state = DownloadState.Failed) }
            throw e
        }
    }

    private fun upsert(item: DownloadItem) {
        _items.update { cur ->
            val idx = cur.indexOfFirst { it.id == item.id }
            if (idx >= 0) cur.toMutableList().also { it[idx] = item }
            else cur + item
        }
    }

    private fun update(id: String, block: (DownloadItem) -> DownloadItem) {
        _items.update { cur ->
            cur.map { if (it.id == id) block(it) else it }
        }
    }

    /** 协程取消 token, 仅用于示意, 实际完整协程取消由调用方 Job 控制 */
    @Volatile var currentJob: kotlinx.coroutines.Job? = null
}