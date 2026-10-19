package com.mcode.gequhai

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import com.mcode.gequhai.player.PlaybackController
import com.mcode.gequhai.ui.GeQuHaiApp
import com.mcode.gequhai.ui.GeQuHaiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果忽略: 没拿到也能播, 只是没有通知 */ }

    /** 独立作用域, 不阻塞 Activity 生命周期. 用于启动期 WAF cookie 预热. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val gequApi by lazy { com.mcode.gequhai.data.GequApi(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        PlaybackController.bind(this)

        // 启动时预热 WAF: 先访问主页拿 cookie, 首次搜索时就不会被陌生请求拦截
        appScope.launch { runCatching { gequApi.warmup() } }

        setContent {
            GeQuHaiTheme {
                GeQuHaiApp()
            }
        }
    }
}
