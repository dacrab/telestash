package com.telestash.ui.gallery

import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.telestash.model.StashItem
import com.telestash.td.MediaPipeline
import com.telestash.td.TgClient
import com.telestash.util.Format
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import java.io.File

// 16:9 is the dominant aspect of phone camera video (grid thumbnails only).
private const val VIDEO_ASPECT_WIDTH = 16f
private const val VIDEO_ASPECT_HEIGHT = 9f
private const val VIDEO_ASPECT_RATIO = VIDEO_ASPECT_WIDTH / VIDEO_ASPECT_HEIGHT

/** Grid cells are ~110dp; decoding a full 4000px photo to fill one is pure waste. */
private const val GRID_CELL_DECODE_SIZE = 512

/** Resolution state of a gallery file: instant mini, full file, or honest fallback. */
private sealed interface Resolved {
    data object Loading : Resolved

    data class Ready(
        val path: String,
    ) : Resolved

    data object Failed : Resolved

    /** Video with no downloadable thumbnail: badge tile instead of a spinner. */
    data object VideoBadge : Resolved
}

/**
 * Resolves the local file for an item through the gated [MediaPipeline].
 *
 * Grid cells pass [thumbOnly] so videos never pull whole files just to fill a
 * 110dp cell: they get their tiny TDLib thumbnail (or a badge tile), while
 * photos prefer the cached full file, then the thumbnail, then the full file.
 */
@Composable
private fun rememberLocalPath(
    item: StashItem,
    thumbOnly: Boolean,
    retryTick: Int = 0,
): Resolved {
    var resolved by remember(item.fileId, item.localPath, thumbOnly) {
        mutableStateOf<Resolved>(Resolved.Loading)
    }
    LaunchedEffect(item.fileId, item.localPath, thumbOnly, retryTick) {
        resolved = Resolved.Loading
        val local = item.localPath?.takeIf { File(it).exists() }
        if (local != null) {
            resolved = Resolved.Ready(local)
            return@LaunchedEffect
        }
        val client = TgClient.peek()
        if (thumbOnly && item.isVideo) {
            // Never pull whole videos for a grid cell.
            val thumb = MediaPipeline.resolve(client, item.thumbFileId)
            resolved = if (thumb != null) Resolved.Ready(thumb) else Resolved.VideoBadge
            return@LaunchedEffect
        }
        val target = if (thumbOnly && item.thumbFileId != 0) item.thumbFileId else item.fileId
        val path = MediaPipeline.resolve(client, target)
        if (path != null) {
            resolved = Resolved.Ready(path)
        } else if (thumbOnly && !item.isVideo && target != item.fileId) {
            // Thumbnail fetch failed; the full file may still work.
            val full = MediaPipeline.resolve(client, item.fileId)
            resolved = if (full != null) Resolved.Ready(full) else Resolved.Failed
        } else {
            resolved = Resolved.Failed
        }
    }
    return resolved
}

/** Renders a photo: instant minithumbnail first, full file with crossfade next. */
@Composable
fun StashImage(
    item: StashItem,
    contentScale: ContentScale = ContentScale.Crop,
    zoomable: Boolean = false,
    thumbOnly: Boolean = false,
) {
    val context = LocalContext.current
    var retryTick by remember(item.fileId, thumbOnly) { mutableIntStateOf(0) }
    val resolved = rememberLocalPath(item, thumbOnly, retryTick)
    var decodeFailed by remember(item.fileId, thumbOnly, resolved) { mutableStateOf(false) }

    when (resolved) {
        is Resolved.Ready ->
            if (decodeFailed) {
                PreviewErrorTile(onRetry = { retryTick++ })
            } else {
                val imageModifier =
                    if (zoomable) {
                        Modifier
                            .fillMaxSize()
                            .zoomable(rememberZoomState())
                    } else {
                        Modifier.fillMaxSize()
                    }
                val request =
                    remember(resolved.path, thumbOnly) {
                        val builder =
                            ImageRequest
                                .Builder(context)
                                .data(File(resolved.path))
                                .crossfade(true)
                        if (thumbOnly) {
                            builder.size(GRID_CELL_DECODE_SIZE, GRID_CELL_DECODE_SIZE)
                        } else {
                            builder
                        }
                        builder.build()
                    }
                AsyncImage(
                    model = request,
                    contentDescription = item.fileName,
                    contentScale = contentScale,
                    modifier = imageModifier,
                    onError = { decodeFailed = true },
                )
            }
        is Resolved.Loading -> {
            val mini = item.miniJpeg
            if (mini != null) {
                // Bytes Telegram already sent: zero-network instant placeholder.
                AsyncImage(
                    model = mini,
                    contentDescription = item.fileName,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        is Resolved.Failed -> PreviewErrorTile(onRetry = { retryTick++ })
        is Resolved.VideoBadge -> VideoBadgeTile()
    }
}

/** Centered broken-image tile; tapping retries the download in place. */
@Composable
private fun PreviewErrorTile(onRetry: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onRetry),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.BrokenImage,
            contentDescription = "Preview unavailable, tap to retry",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** Dark tile with a play glyph for videos that have no thumbnail (yet). */
@Composable
private fun VideoBadgeTile() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "Video",
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(32.dp),
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(
    item: StashItem,
    active: Boolean,
    thumbOnly: Boolean = false,
) {
    val context = LocalContext.current
    var retryTick by remember(item.fileId) { mutableIntStateOf(0) }
    val resolved = rememberLocalPath(item, thumbOnly = thumbOnly, retryTick = retryTick)

    if (thumbOnly) {
        // Neighbor pages: only a cheap thumbnail tile is needed; downloading the
        // whole video and spinning up a decoder for a page the user may never
        // watch just makes swiping stall behind a full TDLib download.
        StashImage(item, contentScale = ContentScale.Fit, thumbOnly = true)
        return
    }

    val readyPath = (resolved as? Resolved.Ready)?.path
    if (readyPath != null && File(readyPath).exists()) {
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
        DisposableEffect(exo) {
            onDispose { exo.release() }
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryCell(
    item: StashItem,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .aspectRatio(1f)
            .padding(1.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ).then(
                if (selected) {
                    Modifier.background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        StashImage(item, thumbOnly = true)
        if (item.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(2.dp)
                        .size(18.dp),
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(22.dp),
            )
        }
        if (item.deviceUri != null) {
            Icon(
                Icons.Filled.CloudDone,
                contentDescription = "Backed up from device",
                tint = Color.White.copy(alpha = 0.9f),
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(2.dp)
                        .size(14.dp),
            )
        }
    }
}

/** Full-screen viewer: swipe between items, zoom photos, play videos. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerDialog(
    items: List<StashItem>,
    initialIndex: Int,
    onDownload: (StashItem) -> Unit,
    onDelete: (StashItem) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = rememberPagerState(initialPage = initialIndex) { items.size }
        var current by remember { mutableStateOf(items.getOrElse(initialIndex) { items.first() }) }

        // System back dismisses the viewer (and stops video playback).
        androidx.activity.compose.BackHandler(onBack = onDismiss)

        // Track the swiped-to item for the action row.
        LaunchedEffect(pagerState.currentPage) {
            items.getOrNull(pagerState.currentPage)?.let { current = it }
        }

        Surface(
            color = Color.Black.copy(alpha = 0.95f),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close",
                            tint = Color.White,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            Format.date(current.date),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                        )
                        if (items.size > 1) {
                            Text(
                                "${pagerState.currentPage + 1} / ${items.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                    IconButton(onClick = { onDownload(current) }) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = "Save to device",
                            tint = Color.White,
                        )
                    }
                    // Delete the swiped-to item, not the one the viewer opened with.
                    IconButton(onClick = { onDelete(current) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    // Neighbors resolve while visible so swiping never stalls.
                    beyondViewportPageCount = 1,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                ) { page ->
                    val item = items[page]
                    Box(Modifier.fillMaxSize()) {
                        if (item.isVideo) {
                            val active = pagerState.currentPage == page
                            // Only the active page needs the real file; neighbors
                            // resolve a thumbnail so swiping stays cheap.
                            VideoPlayer(item, active = active, thumbOnly = !active)
                        } else {
                            StashImage(
                                item,
                                contentScale = ContentScale.Fit,
                                zoomable = true,
                            )
                        }
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${current.fileName} · ${Format.size(current.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
