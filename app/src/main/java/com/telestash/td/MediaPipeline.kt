package com.telestash.td

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared, concurrency-gated file resolver. Grid cells used to fire one
 * synchronous full-file TDLib download each on first composition, pushing TDLib
 * into flood-waits and leaving gray skeleton cells; all preview traffic now
 * funnels here, downloads capped and every result validated first.
 */
object MediaPipeline {
    /** Max parallel TDLib downloads for previews; full files are huge. */
    private const val MAX_PARALLEL_DOWNLOADS = 4

    /** Give up on a stalled download rather than squatting on a permit forever. */
    private const val DOWNLOAD_TIMEOUT_MS = 60_000L

    private val downloadGate = Semaphore(MAX_PARALLEL_DOWNLOADS)

    /**
     * Downloads for the same [fileId] that arrive while one is in flight join it
     * instead of stacking a second TDLib download (and a second flood-wait).
     */
    private val inFlight = ConcurrentHashMap<Int, Deferred<String?>>()

    private val resolveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Downloads [fileId] (no-op when 0) and returns the local path, or null
     * when the download failed or the file isn't actually on disk. Never
     * throws: callers render fallback UI instead.
     */
    suspend fun resolve(
        client: TgClient?,
        fileId: Int,
    ): String? {
        if (client == null || fileId == 0) return null
        val deferred =
            inFlight.computeIfAbsent(fileId) { id ->
                resolveScope.async {
                    try {
                        downloadGate.withPermit { client.download(id) }
                    } finally {
                        inFlight.remove(id)
                    }
                }
            }
        val result =
            runCatching {
                withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { deferred.await() } ?: run {
                    // Abandon the stalled download so it can't hold a permit that
                    // every grid cell is now blocked behind.
                    Log.w("MediaPipeline", "download($fileId) timed out; abandoning")
                    deferred.cancel()
                    null
                }
            }
        val error = result.exceptionOrNull()
        if (error != null) Log.w("MediaPipeline", "download($fileId) failed: ${error.message}")
        val path = result.getOrNull()
        if (path == null || !File(path).exists()) {
            Log.w("MediaPipeline", "download($fileId) missing on disk")
            return null
        }
        return path
    }
}
