package com.telestash

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.telestash.td.TgNativeLoader

/** Share of the heap the bitmap cache may use before entries are evicted. */
private const val IMAGE_CACHE_HEAP_FRACTION = 0.25

/**
 * Application entry point; preloads TDLib's native library up front (see
 * [TgNativeLoader]) and owns the process-wide Coil [ImageLoader].
 */
class TeleStashApplication :
    Application(),
    SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // Force TDLib's class init and native library load on the main thread,
        // once, before any background thread can race Client.create() into a
        // deadlock on the native loader lock.
        TgNativeLoader.preload()
    }

    // Coil's disk cache would only duplicate files this app already stores: every
    // source is a downloaded media file or TDLib minithumbnail bytes.
    override fun newImageLoader(context: Context): ImageLoader {
        val memoryCache =
            MemoryCache
                .Builder()
                // Grid cells decode to 512px, so a quarter of the heap outlasts
                // several screenfuls of scrolling.
                .maxSizePercent(context, IMAGE_CACHE_HEAP_FRACTION)
                .build()

        return ImageLoader
            .Builder(context)
            .memoryCache(memoryCache)
            .diskCache(null)
            .crossfade(true)
            .build()
    }
}
