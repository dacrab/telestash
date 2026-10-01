package com.telestash.td

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * TDLib boots its native library from a static initializer. Doing that lazily
 * on a background thread can deadlock two threads against the loader lock, so
 * the load is forced up front on the main thread.
 */
object TgNativeLoader {
    private var loaded = false

    @Synchronized
    fun preload() {
        if (loaded) return
        // Any client call runs the static init; verbosity 0 keeps TDLib quiet.
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(0)) }
        loaded = true
    }
}
