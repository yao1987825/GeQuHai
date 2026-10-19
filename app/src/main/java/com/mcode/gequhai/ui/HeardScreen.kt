package com.mcode.gequhai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mcode.gequhai.data.Prefs
import com.mcode.gequhai.data.SongMeta
import com.mcode.gequhai.player.PlaybackController
import kotlinx.coroutines.launch

/**
 * "收听"页: 我播放过的歌曲 (按最近在前), DataStore 持久化.
 *
 * 操作:
 *  - 整行点击 -> 重播
 *  - ❤️     -> 收藏/取消收藏 (与收听列表解耦)
 *  - ❌     -> 从收听历史中移除
 *  - 顶部 全部/仅❤️ 切换过滤
 */
@Composable
fun HeardScreen() {
    val context = LocalContext.current
    val items by Prefs.listened(context).collectAsState(initial = emptyList())
    val favorites by Prefs.favorites(context).collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }

    val filtered = if (onlyFavorites) items.filter { it.mp3Id in favorites } else items

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("收听历史", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "只要播放过一首歌, 就会一直留在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (items.isNotEmpty()) {
                IconButton(onClick = {
                    scope.launch { Prefs.clearListened(context) }
                }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "清空", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // 筛选: 全部 / 仅 ❤️
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !onlyFavorites,
                onClick = { onlyFavorites = false },
                label = { Text("全部 ${items.size}") },
            )
            FilterChip(
                selected = onlyFavorites,
                onClick = { onlyFavorites = true },
                label = { Text("仅 ❤️ ${favorites.size}") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        when {
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MusicNote, contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "还没有收听记录, 去搜索页播首歌吧",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "已过滤: 你 ${items.size} 首里没有任何 ❤️ 标记",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.mp3Id }) { song ->
                    val isFav = song.mp3Id in favorites
                    HeardRow(
                        song = song,
                        isFavorite = isFav,
                        onClick = { PlaybackController.play(song) },
                        onToggleFavorite = {
                            scope.launch { Prefs.toggleFavorite(context, song.mp3Id) }
                        },
                        onRemove = { scope.launch { Prefs.removeListened(context, song.mp3Id) } },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HeardRow(
    song: SongMeta,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(
                if (isFavorite) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
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
                    Icons.Default.MusicNote, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.author.ifBlank { "未知歌手" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // ❤️ 切换
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(40.dp)) {
            Icon(
                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                tint = if (isFavorite)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ❌ 从历史移除
        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "从收听历史移除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
