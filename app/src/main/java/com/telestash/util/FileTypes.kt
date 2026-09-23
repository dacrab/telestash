package com.telestash.util

/** File-name/-type helpers shared by backup and message mapping. */
object FileTypes {
    private val VIDEO_EXTENSIONS =
        setOf("mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v")

    fun isVideoName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    /** Best-guess MIME for an image file name (MediaStore insert needs one). */
    fun imageMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
    }
}
