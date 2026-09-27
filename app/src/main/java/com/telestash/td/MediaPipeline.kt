package com.telestash.td

import android.util.Log
import kotlinx.coroutines.CancellationException
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

/** Shared, concurrency-gated file resolver for gallery media. */
object MediaPipeline {
    /** Max parallel TDLib downloads for previews; full files are huge. */
    private const val MAX_PARALLEL_DOWNLOADS = 4

    private const val THUMBNAIL_TIMEOUT_MS = 60_000L
    private const val FULL_DOWNLOAD_TIMEOUT_MS = 30L * 60 * 1000

    private val downloadGate = Semaphore(MAX_PARALLEL_DOWNLOADS)

    /**
     * Downloads for the same file and mode that arrive while one is in flight join it
     * instead of stacking a second TDLib download (and a second flood-wait).
     */
    private data class DownloadKey(
        val fileId: Int,
        val fullFile: Boolean,
    )

    private val inFlight = ConcurrentHashMap<DownloadKey, Deferred<String?>>()

    private val resolveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Downloads [fileId] (no-op when 0) and returns the local path, or null
     * when the download failed or the file isn't actually on disk. Cancellation
     * still propagates so callers can stop their own work.
     */
    suspend fun resolve(
        client: TgClient?,
        fileId: Int,
        fullFile: Boolean,
    ): String? {
        if (client == null || fileId == 0) return null
        val key = DownloadKey(fileId, fullFile)
        val deferred =
            inFlight.computeIfAbsent(key) { request ->
                resolveScope.async {
                    try {
                        downloadGate.withPermit { client.download(request.fileId) }
                    } finally {
                        inFlight.remove(key)
                    }
                }
            }
        val path =
            try {
                withTimeoutOrNull(if (fullFile) FULL_DOWNLOAD_TIMEOUT_MS else THUMBNAIL_TIMEOUT_MS) { deferred.await() }
                    ?: run {
                        // Abandon the stalled download so it can't hold a permit that
                        // every grid cell is now blocked behind.
                        Log.w("MediaPipeline", "download($fileId) timed out; abandoning")
                        deferred.cancel()
                        null
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MediaPipeline", "download($fileId) failed: ${e.message}")
                null
            }
        if (path == null || !File(path).exists()) {
            Log.w("MediaPipeline", "download($fileId) missing on disk")
            return null
        }
        return path
    }
}
