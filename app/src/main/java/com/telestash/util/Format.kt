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

    private fun atLocalTime(epochSec: Int) = Instant.ofEpochSecond(epochSec.toLong()).atZone(ZoneId.systemDefault())

    /** "Sep 14, 2026" */
    fun date(epochSec: Int): String =
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()).format(atLocalTime(epochSec))

    /** "September 2026" month bucket label for timeline headers. */
    fun monthYear(epochSec: Int): String =
        DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(atLocalTime(epochSec))

    /** "3.4 MB" style byte size. */
    fun size(bytes: Long): String {
        val locale = Locale.getDefault()
        return when {
            bytes < BYTES_PER_KB -> "$bytes B"
            bytes < BYTES_PER_MB -> "%.1f KB".format(locale, bytes / BYTES_PER_KB.toDouble())
            bytes < BYTES_PER_GB -> "%.1f MB".format(locale, bytes / BYTES_PER_MB.toDouble())
            else -> "%.1f GB".format(locale, bytes / BYTES_PER_GB.toDouble())
        }
    }
}
