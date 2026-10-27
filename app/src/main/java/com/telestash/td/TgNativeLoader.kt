package com.telestash.td

import org.drinkless.tdlib.TdApi

/**
 * TDLib boots its native library from a static initializer. Doing that lazily
 * on a background thread can deadlock two threads against the loader lock, so
 * the load is forced up front on the main thread.
 */
object TgNativeLoader {
    private var loaded = false

    /** Loads TdApi (and thus the native library) once, on the calling thread. */
    @Synchronized
    fun preload() {
        if (loaded) return
        // Touching TdApi runs its static initializer, which loads tdjni.
        TdApi::class.java
        loaded = true
    }
}
