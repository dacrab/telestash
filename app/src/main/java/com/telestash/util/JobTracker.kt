package com.telestash.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Tracks fire-and-forget jobs so an owner shutdown can cancel and await them.
 * Shared by the upload paths and the gallery sync coordinator.
 */
internal class JobTracker {
    private val jobs = mutableSetOf<Job>()

    /** Launches [block] on [Dispatchers.IO] in [scope] and tracks it until completion. */
    fun launchIo(
        scope: CoroutineScope,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val job = scope.launch(Dispatchers.IO) { block() }
        synchronized(jobs) { jobs.add(job) }
        job.invokeOnCompletion { synchronized(jobs) { jobs.remove(job) } }
        return job
    }

    /** Cancels every tracked job without waiting for them to finish. */
    fun cancelAll() {
        synchronized(jobs) { jobs.toList().also { jobs.clear() } }.forEach { it.cancel() }
    }

    /** Cancels every tracked job and waits for each to finish. */
    suspend fun cancelAndJoin() {
        val current = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
        current.forEach { it.cancel() }
        current.forEach { it.join() }
    }
}
