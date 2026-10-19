package com.mcode.gequhai.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.mcode.gequhai.data.SongMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放控制单例.
 *
 * - 底层用 Media3 MediaController 接到 PlaybackService (MediaSessionService)
 * - 维护播放队列 [queue] + 当前索引 [queueIndex]
 * - 通过 StateFlow 暴露给 Compose
 */
object PlaybackController {

    private var controller: MediaController? = null

    // 队列 + 当前索引
    private val _queue = MutableStateFlow<List<SongMeta>>(emptyList())
    val queue: StateFlow<List<SongMeta>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(-1)
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    // 当前歌曲 / 播放状态
    private val _current = MutableStateFlow<SongMeta?>(null)
    val current: StateFlow<SongMeta?> = _current.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _progressMs = MutableStateFlow(0L)
    val progressMs: StateFlow<Long> = _progressMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    fun bind(context: Context) {
        if (controller != null) return
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, PlaybackService::class.java)
        )
        val future = MediaController.Builder(context.applicationContext, token).buildAsync()
        future.addListener({
            controller = future.get()
            controller?.addListener(playerListener)
            // 如果服务侧已经有队列, 同步到 UI
            syncFromController()
        }, MoreExecutors.directExecutor())
    }

    private fun syncFromController() {
        val c = controller ?: return
        val items = (0 until c.mediaItemCount).map { i ->
            val md = c.getMediaItemAt(i).mediaMetadata
            SongMeta(
                mp3Id    = c.getMediaItemAt(i).mediaId,
                playId   = "",
                title    = md.title?.toString() ?: "",
                author   = md.artist?.toString() ?: "",
                cover    = md.artworkUri?.toString() ?: "",
                duration = "",
                mp3Type  = 0,
                mp3Url   = c.getMediaItemAt(i).localConfiguration?.uri?.toString() ?: "",
            )
        }
        _queue.value = items
        _queueIndex.value = if (c.currentMediaItemIndex >= 0) c.currentMediaItemIndex else -1
    }

    /**
     * 播放一组歌曲, 从 [startIndex] 开始.
     * 替换整个队列.
     */
    fun playQueue(songs: List<SongMeta>, startIndex: Int = 0) {
        val c = controller ?: return
        if (songs.isEmpty() || startIndex !in songs.indices) return
        val items = songs.map { it.toMediaItem() }
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        c.prepare()
        c.play()
        _queue.value = songs
        _queueIndex.value = startIndex
        _current.value = songs[startIndex]
    }

    /** 单曲播放 (并入队) */
    fun play(song: SongMeta) {
        val c = controller ?: return
        // 如果当前队列包含这首歌, 跳到它
        val existingIdx = _queue.value.indexOfFirst { it.mp3Id == song.mp3Id }
        if (existingIdx >= 0) {
            c.seekToDefaultPosition(existingIdx)
            c.play()
            _queueIndex.value = existingIdx
            _current.value = _queue.value[existingIdx]
        } else {
            // 加到队列末尾
            c.addMediaItem(song.toMediaItem())
            val newIdx = _queue.value.size
            _queue.value = _queue.value + song
            _queueIndex.value = newIdx
            c.seekToDefaultPosition(newIdx)
            c.play()
            _current.value = song
        }
    }

    fun playNext() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
        } else {
            c.seekTo(0, 0)  // 单曲 / 末尾回到头
        }
    }

    fun playPrev() {
        val c = controller ?: return
        // 距当前 < 3s 跳上一首, 否则回到本曲开头
        if (c.currentPosition > 3000) {
            c.seekTo(0, 0)
        } else if (c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
        } else {
            c.seekTo(0, 0)
        }
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        _progressMs.value = ms
    }

    fun pauseOrResume() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun stop() {
        controller?.stop()
        controller?.clearMediaItems()
        _current.value = null
        _isPlaying.value = false
        _progressMs.value = 0
        _durationMs.value = 0
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index !in _queue.value.indices) return
        // 修正 ExoPlayer 的索引
        val playingIdx = c.currentMediaItemIndex
        c.removeMediaItem(index)
        val newList = _queue.value.toMutableList().also { it.removeAt(index) }
        _queue.value = newList
        _queueIndex.value = if (newList.isEmpty()) -1 else playingIdx.coerceAtMost(newList.lastIndex)
    }

    fun playAt(index: Int) {
        val c = controller ?: return
        if (index !in _queue.value.indices) return
        c.seekToDefaultPosition(index)
        c.play()
        _queueIndex.value = index
        _current.value = _queue.value[index]
    }

    /**
     * 把单首追加到队列末尾 (不改变当前播放).
     * 主要给"边搜边加载队列"用.
     */
    fun appendToQueue(song: SongMeta) {
        val c = controller ?: return
        // 防重复
        if (_queue.value.any { it.mp3Id == song.mp3Id }) return
        c.addMediaItem(song.toMediaItem())
        _queue.value = _queue.value + song
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            _queueIndex.value = c.currentMediaItemIndex
            _current.value = _queue.value.getOrNull(c.currentMediaItemIndex)
            _progressMs.value = 0
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            controller?.let { _durationMs.value = it.duration.coerceAtLeast(0) }
        }
    }

    /** 由 UI 层在 onCreate 协程里每秒拉一次 */
    fun pollProgress() {
        controller?.let {
            _progressMs.value = it.currentPosition.coerceAtLeast(0)
            _durationMs.value = it.duration.coerceAtLeast(0)
        }
    }
}

// 把 SongMeta 转成 MediaItem
private fun SongMeta.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mp3Id)
    .setUri(mp3Url)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(author)
            .setArtworkUri(cover.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            .build()
    )
    .build()