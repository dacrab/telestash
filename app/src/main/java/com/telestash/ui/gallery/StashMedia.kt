package com.telestash.ui.gallery

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.telestash.model.StashItem
import com.telestash.td.MediaPipeline
import com.telestash.td.TgClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import java.io.File

/** 16:9 is the dominant aspect of phone camera video (grid thumbnails only). */
private const val VIDEO_ASPECT_RATIO = 16f / 9f

/** Grid cells are ~110dp; decoding a full 4000px photo to fill one is pure waste. */
private const val GRID_CELL_DECODE_SIZE = 512

/** How a gallery file resolved: instant minithumbnail, full file, or honest fallback. */
private sealed interface Resolved {
    data object Loading : Resolved

    data class Ready(
        val path: String,
    ) : Resolved

    data class Mini(
        val bytes: ByteArray,
    ) : Resolved

    data object Failed : Resolved

    /** Video with no downloadable thumbnail: badge tile instead of a spinner. */
    data object VideoBadge : Resolved
}

/**
 * Resolves a gallery file to something displayable, cheapest source first:
 * already-sent bytes, a local file, the thumbnail, then the full file.
 * [thumbOnly] keeps grid cells and offscreen viewer pages off that last step.
 */
@Composable
private fun rememberResolved(
    item: StashItem,
    thumbOnly: Boolean,
    retryTick: Int = 0,
): Resolved {
    var resolved by
        remember(item.fileId, item.thumbFileId, item.localPath, item.miniJpeg, thumbOnly) {
            mutableStateOf<Resolved>(Resolved.Loading)
        }
    LaunchedEffect(
        item.fileId,
        item.thumbFileId,
        item.localPath,
        item.miniJpeg,
        thumbOnly,
        retryTick,
    ) {
        resolved = resolveStashItem(item, thumbOnly)
    }
    return resolved
}

private suspend fun resolveStashItem(
    item: StashItem,
    thumbOnly: Boolean,
): Resolved =
    // Called from the composition's (main) dispatcher, so keep the file stats and
    // the tail of MediaPipeline.resolve off the UI thread.
    withContext(Dispatchers.IO) {
        val mini = item.miniJpeg
        // Already-downloaded bytes beat anything TDLib would fetch again.
        val local = item.localPath?.takeIf { (!thumbOnly || !item.isVideo) && File(it).exists() }
        if (local != null) return@withContext Resolved.Ready(local)

        val client = TgClient.peek()
        val thumb =
            if (thumbOnly && item.thumbFileId != 0) {
                MediaPipeline.resolve(client, item.thumbFileId, fullFile = false)
            } else {
                null
            }
        if (thumb != null) return@withContext Resolved.Ready(thumb)
        if (thumbOnly && item.isVideo) {
            // Don't pull a whole video just to draw a grid cell.
            return@withContext if (mini != null) Resolved.Mini(mini) else Resolved.VideoBadge
        }

        val full = MediaPipeline.resolve(client, item.fileId, fullFile = true)
        when {
            full != null -> Resolved.Ready(full)
            thumbOnly && mini != null -> Resolved.Mini(mini)
            else -> Resolved.Failed
        }
    }

/** Renders a photo: instant minithumbnail first, full file with crossfade next. */
@Composable
fun StashImage(
    item: StashItem,
    contentScale: ContentScale = ContentScale.Crop,
    zoomable: Boolean = false,
    thumbOnly: Boolean = false,
    allowRetry: Boolean = true,
    contentDescription: String? = item.fileName,
) {
    val context = LocalContext.current
    var retryTick by
        remember(item.fileId, item.thumbFileId, item.localPath, item.miniJpeg, thumbOnly) {
            mutableIntStateOf(0)
        }
    val resolved = rememberResolved(item, thumbOnly, retryTick)
    var decodeFailed by remember(item.fileId, thumbOnly, resolved) { mutableStateOf(false) }

    when (resolved) {
        is Resolved.Mini ->
            AsyncImage(
                model = resolved.bytes,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        is Resolved.Ready ->
            if (decodeFailed) {
                PreviewErrorTile(
                    onRetry = if (allowRetry) ({ retryTick++ }) else null,
                    contentDescription = if (allowRetry) "Preview unavailable, tap to retry" else null,
                )
            } else {
                val imageModifier =
                    if (zoomable) {
                        Modifier.fillMaxSize().zoomable(rememberZoomState())
                    } else {
                        Modifier.fillMaxSize()
                    }
                val request =
                    remember(resolved.path, thumbOnly) {
                        val builder =
                            ImageRequest
                                .Builder(context)
                                .data(File(resolved.path))
                                // Grid cells appear by the dozen during a fling; animating
                                // each one in is wasted frame time. Keep it for full-size.
                                .crossfade(!thumbOnly)
                        if (thumbOnly) builder.size(GRID_CELL_DECODE_SIZE, GRID_CELL_DECODE_SIZE)
                        builder.build()
                    }
                AsyncImage(
                    model = request,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = imageModifier,
                    onError = { decodeFailed = true },
                )
            }
        is Resolved.Loading ->
            if (item.miniJpeg != null) {
                AsyncImage(
                    model = item.miniJpeg,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
        is Resolved.Failed ->
            PreviewErrorTile(
                onRetry = if (allowRetry) ({ retryTick++ }) else null,
                contentDescription = if (allowRetry) "Preview unavailable, tap to retry" else null,
            )
        is Resolved.VideoBadge -> VideoBadgeTile(contentDescription)
    }
}

/** Centered broken-image tile; tapping retries the download in place. */
@Composable
private fun PreviewErrorTile(
    onRetry: (() -> Unit)?,
    contentDescription: String? = "Preview unavailable, tap to retry",
) {
    val retryModifier = if (onRetry != null) Modifier.clickable(onClick = onRetry) else Modifier
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(retryModifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.BrokenImage,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** Dark tile with a play glyph for videos that have no thumbnail (yet). */
@Composable
private fun VideoBadgeTile(contentDescription: String?) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(32.dp),
        )
    }
}

/** Plays a gallery video, or a cheap thumbnail tile when [thumbOnly]. */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    item: StashItem,
    active: Boolean,
    thumbOnly: Boolean = false,
) {
    val context = LocalContext.current
    var retryTick by remember(item.fileId) { mutableIntStateOf(0) }
    val resolved = rememberResolved(item, thumbOnly, retryTick)

    if (thumbOnly) {
        // Neighbor pages: only a cheap thumbnail tile is needed; downloading the
        // whole video and spinning up a decoder for a page the user may never
        // watch just makes swiping stall behind a full TDLib download.
        StashImage(item, contentScale = ContentScale.Fit, thumbOnly = true)
        return
    }

    val readyPath = (resolved as? Resolved.Ready)?.path
    if (readyPath != null) {
        val exo =
            remember(readyPath) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(File(readyPath).toURI().toString()))
                    prepare()
                    playWhenReady = active
                }
            }
        // Swiping away pauses (no ghost audio from offscreen pages); back resumes.
        LaunchedEffect(active) { exo.playWhenReady = active }
        DisposableEffect(exo) { onDispose { exo.release() } }
        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    player = exo
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    useController = true
                    // FIT shows portrait video correctly instead of forcing 16:9.
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    } else if (resolved is Resolved.Failed) {
        PreviewErrorTile(onRetry = { retryTick++ })
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(VIDEO_ASPECT_RATIO),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}
