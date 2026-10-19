package com.mcode.gequhai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcode.gequhai.data.DownloadItem
import com.mcode.gequhai.data.DownloadState
import com.mcode.gequhai.data.Downloader

@Composable
fun DownloadsScreen() {
    val items by Downloader.items.collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("下载管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无下载任务, 去播放页点下载按钮吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(items, key = { it.id }) { it ->
                    DownloadRow(it)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.artist + " · " + stateText(item.state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (item.state == DownloadState.Downloading && item.totalBytes > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { item.downloadedBytes.toFloat() / item.totalBytes.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item.savedPath?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        when (item.state) {
            DownloadState.Done       -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            DownloadState.Failed     -> Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            DownloadState.Downloading -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            else -> {}
        }
    }
}

private fun stateText(state: DownloadState): String = when (state) {
    DownloadState.Queued      -> "等待中"
    DownloadState.Downloading -> {
        val total = ""
        "下载中"
    }
    DownloadState.Done        -> "已完成"
    DownloadState.Failed      -> "失败"
}