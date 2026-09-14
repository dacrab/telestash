package com.telestash.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human-friendly date/size formatting shared by the grid and the viewer. */
object Format {
    const val EPOCH_MILLIS_PER_SECOND = 1000L

    private const val BYTES_PER_KB = 1024L

    /** "Sep 14, 2026" */
    fun date(epochSec: Int): String =
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            .format(Date(epochSec * EPOCH_MILLIS_PER_SECOND))

    /** "Sep 14, 2026 14:30" */
    fun dateTime(epochSec: Int): String =
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
            .format(Date(epochSec * EPOCH_MILLIS_PER_SECOND))

    /** "3.4 MB" style byte size. */
    fun size(bytes: Long): String {
        if (bytes < BYTES_PER_KB) return "$bytes B"
        val kb = bytes / BYTES_PER_KB.toDouble()
        if (kb < BYTES_PER_KB) return "%.1f KB".format(kb)
        val mb = kb / BYTES_PER_KB.toDouble()
        if (mb < BYTES_PER_KB) return "%.1f MB".format(mb)
        return "%.1f GB".format(mb / BYTES_PER_KB.toDouble())
    }
}
