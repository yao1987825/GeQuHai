package com.mcode.gequhai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mcode.gequhai.data.Downloader
import com.mcode.gequhai.data.SongMeta
import com.mcode.gequhai.player.PlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

    // 每秒刷新进度
    LaunchedEffect(Unit) {
        while (true) {
            PlaybackController.pollProgress()
            delay(500)
        }
    }

    if (song == null) {
        EmptyNowPlaying()
        return
    }
    val s = song!!

    Column(
        Modifier.fillMaxSize().padding(24.dp),
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
        Spacer(Modifier.height(24.dp))
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
        Spacer(Modifier.height(8.dp))
        Text(
            "时长 ${s.duration.ifBlank { formatMs(durationMs) }}   |   音质 type=${s.mp3Type}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))
        Slider(
            value = progressMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
            onValueChange = {
                // 拖动进度 (MediaController.seekTo 需要按需暴露接口)
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(progressMs), style = MaterialTheme.typography.bodySmall)
            Text(formatMs(durationMs), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = { PlaybackController.pauseOrResume() },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.width(24.dp))
            FilledTonalIconButton(
                onClick = {
                    val appCtx = context.applicationContext
                    scope.launch {
                        try {
                            val f = Downloader.download(
                                context = appCtx,
                                song = s,
                            )
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