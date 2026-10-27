package com.telestash.util

/** File-name/-type helpers shared by backup and message mapping. */
object FileTypes {
    /** Lower-case extensions treated as video when the MIME type is missing. */
    val videoExtensions =
        setOf("mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v")

    private val IMAGE_MIMES =
        mapOf(
            "png" to "image/png",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "heic" to "image/heic",
            "heif" to "image/heic",
        )
    private val VIDEO_MIMES =
        mapOf(
            "mp4" to "video/mp4",
            "mov" to "video/quicktime",
            "mkv" to "video/x-matroska",
            "avi" to "video/x-msvideo",
            "webm" to "video/webm",
            "3gp" to "video/3gpp",
            "m4v" to "video/x-m4v",
        )

    /** True when the file name's extension is a known video one. */
    fun isVideoName(name: String): Boolean = extensionOf(name) in videoExtensions

    /** Best-guess MIME for an image file name (MediaStore insert needs one). */
    fun imageMime(name: String): String = IMAGE_MIMES[extensionOf(name)] ?: "image/jpeg"

    /** Best-guess MIME for a video file name. */
    fun videoMime(name: String): String = VIDEO_MIMES[extensionOf(name)] ?: "video/mp4"

    /** Characters MediaStore and Telegram file sends reject in display names. */
    private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

    /** Longest file name kept when sanitizing; longer names are truncated. */
    const val MAX_NAME_LENGTH = 120

    /**
     * Makes a display name safe for MediaStore inserts and Telegram sends: strips
     * any path prefix, replaces unsafe characters, and keeps [suffix] (an
     * extension including the dot) intact at the end.
     */
    fun sanitizeName(
        displayName: String,
        suffix: String = "",
    ): String {
        val baseLength = (MAX_NAME_LENGTH - suffix.length).coerceAtLeast(1)
        val base =
            displayName
                .substringAfterLast('/')
                .removeSuffix(suffix)
                .replace(UNSAFE_NAME_CHARS, "_")
                .take(baseLength)
        return (base + suffix).ifEmpty { "media" }
    }

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()
}
