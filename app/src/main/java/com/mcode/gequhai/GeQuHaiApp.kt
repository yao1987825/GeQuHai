package com.mcode.gequhai

import android.app.Application
import com.mcode.gequhai.player.PlaybackController

class GeQuHaiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: GeQuHaiApp
            private set
    }
}