package com.mcode.gequhai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mcode.gequhai.data.GequApi
import com.mcode.gequhai.data.SearchItem
import com.mcode.gequhai.data.SongMeta
import com.mcode.gequhai.player.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    api: GequApi,
    onPlaying: (SongMeta) -> Unit,
) {
    var keyword by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it; error = null },
            placeholder = { Text("搜索歌名 / 歌手") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(
                    enabled = keyword.isNotBlank() && !loading,
                    onClick = {
                        loading = true
                        results = emptyList()
                        error = null
                        scope.launch {
                            try {
                                val r = withContext(Dispatchers.IO) {
                                    api.search(keyword.trim(), startPage = 1, endPage = 2)
                                }
                                results = r
                            } catch (e: Throwable) {
                                error = e.message ?: "搜索失败"
                            } finally {
                                loading = false
                            }
                        }
                    }
                ) { Text("搜索") }
            }
        )

        Spacer(Modifier.height(8.dp))

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }

        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.hash }) { item ->
                SearchRow(
                    item = item,
                    isResolving = resolving == item.hash,
                    onPlay = {
                        resolving = item.hash
                        scope.launch {
                            try {
                                val meta = withContext(Dispatchers.IO) { api.resolve(item) }
                                PlaybackController.play(meta)
                                onPlaying(meta)
                            } catch (e: Throwable) {
                                error = e.message ?: "解析失败"
                            } finally {
                                resolving = null
                            }
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SearchRow(
    item: SearchItem,
    isResolving: Boolean,
    onPlay: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.artist.ifBlank { item.hash.take(8) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        FilledTonalButton(
            onClick = onPlay,
            enabled = !isResolving,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            if (isResolving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Text("播放")
            }
        }
    }
}