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

    // Rebuilt when the locale or zone changes; ofPattern() is not cheap per cell.
    @Volatile private var cache: FormatCache? = null

    private class FormatCache(
        val locale: Locale,
        val zone: ZoneId,
    ) {
        val date = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
        val month = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    }

    private fun cached(): FormatCache {
        val locale = Locale.getDefault()
        val zone = ZoneId.systemDefault()
        val hit = cache
        if (hit != null && hit.locale == locale && hit.zone == zone) return hit
        return FormatCache(locale, zone).also { cache = it }
    }

    /** "Sep 14, 2026" */
    fun date(epochSec: Int): String = cached().let { it.date.format(atLocalTime(epochSec, it.zone)) }

    /** "September 2026" month bucket label for timeline headers. */
    fun monthYear(epochSec: Int): String = cached().let { it.month.format(atLocalTime(epochSec, it.zone)) }

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

    private fun atLocalTime(
        epochSec: Int,
        zone: ZoneId,
    ) = Instant.ofEpochSecond(epochSec.toLong()).atZone(zone)
}
