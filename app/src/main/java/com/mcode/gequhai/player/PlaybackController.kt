package com.mcode.gequhai.player

import android.content.ComponentName
import android.content.Context
import android.os.Build
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
 * - 当前曲 / 状态 / 进度通过 StateFlow 暴露给 Compose
 */
object PlaybackController {

    private var controller: MediaController? = null

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
        }, MoreExecutors.directExecutor())
    }

    fun play(song: SongMeta) {
        val c = controller ?: return
        val item = MediaItem.Builder()
            .setMediaId(song.mp3Id)
            .setUri(song.mp3Url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.author)
                    .setArtworkUri(song.cover.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) })
                    .build()
            )
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
        _current.value = song
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

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }
        override fun onPlaybackStateChanged(playbackState: Int) {
            controller?.let { c ->
                _durationMs.value = c.duration.coerceAtLeast(0)
            }
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