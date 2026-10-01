package com.telestash

import android.app.Application
import com.telestash.td.TgNativeLoader

class TeleStashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Force TDLib's class init and native library load on the main thread,
        // once, before any background thread can race Client.create() into a
        // deadlock on the native loader lock.
        TgNativeLoader.preload()
    }
}
