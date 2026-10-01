package com.telestash.util

/** File-name/-type helpers shared by backup and message mapping. */
object FileTypes {
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

    fun isVideoName(name: String): Boolean = extensionOf(name) in videoExtensions

    /** Best-guess MIME for an image file name (MediaStore insert needs one). */
    fun imageMime(name: String): String = IMAGE_MIMES[extensionOf(name)] ?: "image/jpeg"

    fun videoMime(name: String): String = VIDEO_MIMES[extensionOf(name)] ?: "video/mp4"

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()
}
