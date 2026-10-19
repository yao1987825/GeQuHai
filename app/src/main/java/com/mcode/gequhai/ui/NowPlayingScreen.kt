package com.mcode.gequhai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mcode.gequhai.data.Downloader
import com.mcode.gequhai.player.PlaybackController
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onDownloaded: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val song by PlaybackController.current.collectAsState()
    val isPlaying by PlaybackController.isPlaying.collectAsState()
    val progressMs by PlaybackController.progressMs.collectAsState()
    val durationMs by PlaybackController.durationMs.collectAsState()
    val queue by PlaybackController.queue.collectAsState()
    val queueIndex by PlaybackController.queueIndex.collectAsState()
    val scope = rememberCoroutineScope()

    // 用户拖动进度条时, 暂停从 Player 拉进度, 避免覆盖用户的拖动值
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // 每秒刷新进度 (用户没在拖动时)
    LaunchedEffect(Unit) {
        while (true) {
            if (!isDragging) PlaybackController.pollProgress()
            delay(500)
        }
    }

    if (song == null) {
        EmptyNowPlaying()
        return
    }
    val s = song!!
    val duration = durationMs.coerceAtLeast(1L)

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            if (s.cover.isNotBlank()) {
                AsyncImage(
                    model = s.cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(96.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            s.title,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            s.author,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (queue.isNotEmpty() && queueIndex >= 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "队列 ${queueIndex + 1} / ${queue.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(8.dp))

        // 进度条: onValueChange 只更新本地 dragValue; onValueChangeFinished 才 seekTo
        Slider(
            value = if (isDragging) dragValue else progressMs.toFloat(),
            onValueChange = {
                isDragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                PlaybackController.seekTo(dragValue.toLong())
                isDragging = false
            },
            valueRange = 0f..duration.toFloat(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(if (isDragging) dragValue.toLong() else progressMs), style = MaterialTheme.typography.bodySmall)
            Text(formatMs(durationMs), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))
        // 控制按钮: 上一首 | 播放/暂停 | 下一首 + 下载
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            FilledTonalIconButton(
                onClick = { PlaybackController.playPrev() },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(32.dp))
            }
            FilledIconButton(
                onClick = { PlaybackController.pauseOrResume() },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(40.dp),
                    tint = Color.White
                )
            }
            FilledTonalIconButton(
                onClick = { PlaybackController.playNext() },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "下一首", modifier = Modifier.size(32.dp))
            }
            FilledTonalIconButton(
                onClick = {
                    val appCtx = context.applicationContext
                    scope.launch {
                        try {
                            val f = Downloader.download(context = appCtx, song = s)
                            onDownloaded(f.absolutePath)
                        } catch (_: Throwable) {}
                    }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = "下载")
            }
        }
    }
}

@Composable
private fun EmptyNowPlaying() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text("先去搜索页选一首歌播放", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = (ms / 1000).toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}