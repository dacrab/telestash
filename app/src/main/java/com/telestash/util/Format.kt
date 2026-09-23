package com.telestash.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Human-friendly date/size formatting shared by the grid and the viewer. */
object Format {
    private const val BYTES_PER_KB = 1024L
    private const val BYTES_PER_MB = BYTES_PER_KB * 1024
    private const val BYTES_PER_GB = BYTES_PER_MB * 1024

    // Immutable and thread-safe, so grid threads can share them without
    // per-call formatter allocations.
    private val datePattern = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    private val monthYearPattern = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    private fun atLocalTime(epochSec: Int) = Instant.ofEpochSecond(epochSec.toLong()).atZone(ZoneId.systemDefault())

    /** "Sep 14, 2026" */
    fun date(epochSec: Int): String = datePattern.format(atLocalTime(epochSec))

    /** "September 2026" month bucket label for timeline headers. */
    fun monthYear(epochSec: Int): String = monthYearPattern.format(atLocalTime(epochSec))

    /** "3.4 MB" style byte size. */
    fun size(bytes: Long): String =
        when {
            bytes < BYTES_PER_KB -> "$bytes B"
            bytes < BYTES_PER_MB -> "%.1f KB".format(bytes / BYTES_PER_KB.toDouble())
            bytes < BYTES_PER_GB -> "%.1f MB".format(bytes / BYTES_PER_MB.toDouble())
            else -> "%.1f GB".format(bytes / BYTES_PER_GB.toDouble())
        }
}
