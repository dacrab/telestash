package com.telestash.model

import androidx.compose.runtime.Immutable
import com.telestash.util.FileTypes
import org.drinkless.tdlib.TdApi

/**
 * A Saved Messages message parsed once, carrying both the gallery item the UI
 * renders and the cloud identity the dedupe index needs. Splitting the two
 * keeps [StashItem] free of fields only one feature reads.
 */
internal class ParsedMessage(
    val item: StashItem,
    val remoteUniqueId: String?,
    val remoteFileId: String?,
    /** TDLib file kind, which decides how a remote file is re-resolved. */
    val remoteType: String,
)

/**
 * A media item shown in the gallery, extracted from a Saved Messages message.
 * [Immutable] so grid cells stay skippable; [miniJpeg] is never mutated after
 * it is parsed out of TDLib.
 */
@Immutable
data class StashItem(
    val messageId: Long,
    val chatId: Long,
    val date: Int,
    val fileId: Int,
    val fileName: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
    val localPath: String?,
    /** MediaStore uri of the device original, if this item is an auto-backup of it. */
    val deviceUri: String? = null,
    /** SHA-256 hex of the device original; set only for device backups. */
    val contentHash: String? = null,
    /** TDLib thumbnail file id for cheap grid previews; 0 = none. */
    val thumbFileId: Int = 0,
    /** TDLib minithumbnail JPEG bytes for an instant placeholder; null if none. */
    val miniJpeg: ByteArray? = null,
) {
    companion object {
        /** Null when the message holds no photo or video worth showing. */
        fun fromMessage(message: TdApi.Message): StashItem? = parse(message)?.item
    }
}

/**
 * Parses a message into gallery data, or null if it isn't gallery media. The
 * stash is a photo/video gallery, so anything else (a text note, a PDF) is
 * skipped rather than rendered as a broken cell.
 */
internal fun parse(message: TdApi.Message): ParsedMessage? =
    when (val content = message.content) {
        is TdApi.MessageDocument -> {
            val document = content.document
            val name = document.fileName.ifBlank { "file" }
            if (!isGalleryMime(document.mimeType, name)) return null
            ParsedMessage(
                item =
                    item(
                        message = message,
                        file = document.document,
                        name = name,
                        isVideo = document.mimeType.startsWith("video/") || name.isVideoName(),
                        thumbFileId = document.thumbnail?.file?.id ?: 0,
                        miniJpeg = document.minithumbnail?.data,
                    ),
                remoteUniqueId = document.document.remote?.uniqueId,
                remoteFileId = document.document.remote?.id,
                remoteType = DOCUMENT,
            )
        }
        is TdApi.MessagePhoto -> {
            val sizes = content.photo.sizes
            if (sizes.isEmpty()) return null
            val biggest = sizes.maxBy { it.width * it.height }
            ParsedMessage(
                item =
                    item(
                        message = message,
                        file = biggest.photo,
                        name = "photo_${message.id}.jpg",
                        isVideo = false,
                        // The smallest existing size is already a cheap grid preview.
                        thumbFileId = sizes.minBy { it.width * it.height }.photo.id,
                        miniJpeg = content.photo.minithumbnail?.data,
                    ),
                remoteUniqueId = biggest.photo.remote?.uniqueId,
                remoteFileId = biggest.photo.remote?.id,
                remoteType = PHOTO,
            )
        }
        is TdApi.MessageVideo -> {
            val video = content.video
            ParsedMessage(
                item =
                    item(
                        message = message,
                        file = video.video,
                        name = video.fileName.ifBlank { "video_${message.id}.mp4" },
                        isVideo = true,
                        thumbFileId = video.thumbnail?.file?.id ?: 0,
                        miniJpeg = video.minithumbnail?.data,
                    ),
                remoteUniqueId = video.video.remote?.uniqueId,
                remoteFileId = video.video.remote?.id,
                remoteType = VIDEO,
            )
        }
        else -> null
    }

private const val PHOTO = "photo"
private const val VIDEO = "video"
private const val DOCUMENT = "document"

private fun item(
    message: TdApi.Message,
    file: TdApi.File,
    name: String,
    isVideo: Boolean,
    thumbFileId: Int = 0,
    miniJpeg: ByteArray? = null,
) = StashItem(
    messageId = message.id,
    chatId = message.chatId,
    date = message.date,
    fileId = file.id,
    fileName = name,
    sizeBytes = file.size.takeIf { it > 0L } ?: file.expectedSize,
    isVideo = isVideo,
    localPath = file.local?.path?.takeIf { file.local?.isDownloadingCompleted == true && it.isNotBlank() },
    thumbFileId = thumbFileId,
    miniJpeg = miniJpeg,
)

/** Accept image/video MIME types and fall back to the filename when blank. */
private fun isGalleryMime(
    mime: String,
    name: String,
): Boolean =
    when {
        mime.startsWith("image/") || mime.startsWith("video/") -> true
        mime.isNotBlank() -> false
        // Telegram leaves the MIME blank for some uploads; trust the extension.
        else -> name.isVideoName() || name.isStillImageName()
    }

private val IMAGE_EXTENSIONS =
    setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif")

private fun String.isVideoName(): Boolean = extension() in FileTypes.videoExtensions

private fun String.isStillImageName(): Boolean = extension() in IMAGE_EXTENSIONS

private fun String.extension(): String = substringAfterLast('.', "").lowercase()
