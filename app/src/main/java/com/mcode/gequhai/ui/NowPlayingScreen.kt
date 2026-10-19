package com.mcode.gequhai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lyrics
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mcode.gequhai.data.Downloader
import com.mcode.gequhai.data.LrcParser
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
    val queue by PlaybackController.queue.collectAsState()
    val queueIndex by PlaybackController.queueIndex.collectAsState()
    val lrc by PlaybackController.lrc.collectAsState()
    val currentLrcIndex by PlaybackController.currentLrcIndex.collectAsState()
    val scope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }

    // 切歌时把 lrc 喂给 controller
    LaunchedEffect(song?.mp3Id) {
        val s = song
        if (s != null) {
            val entries = s.lrc?.let { LrcParser.parse(it) } ?: emptyList()
            PlaybackController.setLrc(entries)
            if (entries.isNotEmpty()) showLyrics = true
        } else {
            PlaybackController.setLrc(emptyList())
        }
    }

    // 每秒刷新进度
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
        Spacer(Modifier.height(20.dp))

        // cover 区域: cover 或 歌词, 点击切换
        if (showLyrics && lrc.isNotEmpty()) {
            LyricsView(
                entries = lrc,
                currentIndex = currentLrcIndex,
                progressMs = progressMs,
                onSeekTo = { ms -> PlaybackController.seekTo(ms) },
                onSwitchToCover = { showLyrics = false },
            )
        } else {
            Box(
                Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .clickable {
                        if (lrc.isNotEmpty()) showLyrics = true
                    },
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
                // 歌词提示角标
                if (lrc.isNotEmpty()) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(
                                Color.Black.copy(alpha = 0.6f),
                                RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Lyrics,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("点按看词", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

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
            Spacer(Modifier.height(2.dp))
            Text(
                "队列 ${queueIndex + 1} / ${queue.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(8.dp))

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

        Spacer(Modifier.height(12.dp))
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

/**
 * 滚动歌词视图.
 * - 居中高亮当前行, 上方/下方稍小稍暗
 * - 跟随播放进度自动滚动, 但只滚到"当前行 ± 0.5 屏"内, 不强制对齐顶部
 * - 点击任意行 → seekTo 该行
 */
@Composable
private fun LyricsView(
    entries: List<com.mcode.gequhai.data.LrcEntry>,
    currentIndex: Int,
    progressMs: Long,
    onSeekTo: (Long) -> Unit,
    onSwitchToCover: () -> Unit,
) {
    val listState = rememberLazyListState()
    // 自动滚动: 当前行尽量落在屏幕中间偏上
    LaunchedEffect(currentIndex) {
        if (currentIndex < 0) return@LaunchedEffect
        // 第一行刚出现时别弹跳
        if (currentIndex < 3) return@LaunchedEffect
        listState.animateScrollToItem(
            index = (currentIndex - 3).coerceAtLeast(0),
            scrollOffset = 0
        )
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Lyrics,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("歌词", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSwitchToCover) { Text("封面", fontSize = 12.sp) }
        }
        Box(
            Modifier
                .height(280.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp)
            ) {
                itemsIndexed(entries, key = { _, e -> "${e.start}-${e.text.hashCode()}" }) { idx, e ->
                    val isCurrent = idx == currentIndex
                    val isPast = currentIndex >= 0 && idx < currentIndex
                    Text(
                        text = e.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeekTo(e.start) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        textAlign = TextAlign.Center,
                        fontSize = if (isCurrent) 17.sp else 14.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            isCurrent -> MaterialTheme.colorScheme.primary
                            isPast    -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            else      -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
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