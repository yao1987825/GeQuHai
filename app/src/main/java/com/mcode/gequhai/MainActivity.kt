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

class MainActivity : ComponentActivity() {

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果忽略: 没拿到也能播, 只是没有通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        PlaybackController.bind(this)

        setContent {
            GeQuHaiTheme {
                GeQuHaiApp()
            }
        }
    }
}