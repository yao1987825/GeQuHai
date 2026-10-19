package com.mcode.gequhai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mcode.gequhai.data.SongMeta
import com.mcode.gequhai.player.PlaybackController

@Composable
fun QueueScreen(
    onPlaying: () -> Unit = {},
) {
    val queue by PlaybackController.queue.collectAsState()
    val currentIndex by PlaybackController.queueIndex.collectAsState()
    val isPlaying by PlaybackController.isPlaying.collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "播放队列",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${queue.size} 首",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "队列为空, 去搜索页选首歌播放",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(queue, key = { _, it -> it.mp3Id }) { idx, song ->
                    QueueRow(
                        song = song,
                        index = idx,
                        isCurrent = idx == currentIndex,
                        isPlaying = idx == currentIndex && isPlaying,
                        onClick = {
                            PlaybackController.playAt(idx)
                            onPlaying()
                        },
                        onRemove = { PlaybackController.removeFromQueue(idx) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    song: SongMeta,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val bg = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
             else MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (song.cover.isNotBlank()) {
                AsyncImage(model = song.cover, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.author.ifBlank { "" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 播放中/暂停图标
        if (isCurrent) {
            Icon(
                if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = if (isPlaying) "播放中" else "已暂停",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
        }
        // 删除按钮
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "从队列移除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}