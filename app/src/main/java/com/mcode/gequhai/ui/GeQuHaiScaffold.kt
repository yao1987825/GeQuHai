package com.mcode.gequhai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.mcode.gequhai.data.GequApi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeQuHaiApp() {
    val context = LocalContext.current
    val api = remember { GequApi(context.applicationContext) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        TabItem("搜索",   Icons.Default.Search),
        TabItem("正在播放", Icons.Default.MusicNote),
        TabItem("队列",   Icons.Default.QueueMusic),
        TabItem("下载",   Icons.Default.Download),
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(tabs[tab].label) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                )
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                0 -> SearchScreen(api = api) { /* 跳到正在播放 */ tab = 1 }
                1 -> NowPlayingScreen()
                2 -> QueueScreen(onPlaying = { tab = 1 })
                3 -> DownloadsScreen()
            }
        }
    }
}

private data class TabItem(val label: String, val icon: ImageVector)