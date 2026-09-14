package com.telestash.ui.gallery

import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import com.telestash.model.StashItem
import com.telestash.td.TgClient
import com.telestash.util.Format
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import java.io.File

// 16:9 is the dominant aspect of phone camera video (grid thumbnails only).
private const val VIDEO_ASPECT_WIDTH = 16f
private const val VIDEO_ASPECT_HEIGHT = 9f
private const val VIDEO_ASPECT_RATIO = VIDEO_ASPECT_WIDTH / VIDEO_ASPECT_HEIGHT

/** Resolves the local file for a Telegram-stored item, downloading it if needed. */
@Composable
private fun rememberLocalPath(item: StashItem): String? {
    var path by remember(item.fileId) { mutableStateOf(item.localPath) }
    LaunchedEffect(item.fileId) {
        val p = path
        if (p == null || !File(p).exists()) {
            runCatching { path = TgClient.peek()?.download(item.fileId) }
        }
    }
    return path
}

/** Renders a Telegram-stored photo. Loads from local TDLib cache, else downloads. */
@Composable
fun StashImage(
    item: StashItem,
    contentScale: ContentScale = ContentScale.Crop,
    zoomable: Boolean = false,
) {
    val resolved = rememberLocalPath(item)

    if (resolved != null && File(resolved).exists()) {
        val imageModifier =
            if (zoomable) {
                Modifier
                    .fillMaxSize()
                    .zoomable(rememberZoomState())
            } else {
                Modifier.fillMaxSize()
            }
        AsyncImage(
            model = File(resolved),
            contentDescription = item.fileName,
            contentScale = contentScale,
            modifier = imageModifier,
        )
    } else {
        // Static skeleton while the file resolves; no per-cell spinner noise.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(
    item: StashItem,
    fill: Boolean = false,
) {
    val context = LocalContext.current
    val resolved = rememberLocalPath(item)

    if (resolved != null && File(resolved).exists()) {
        val exo =
            remember(resolved) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(File(resolved).toURI().toString()))
                    prepare()
                    playWhenReady = true
                }
            }
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
            modifier =
                if (fill) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(VIDEO_ASPECT_RATIO)
                },
        )
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
        StashImage(item)
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
    onDelete: () -> Unit,
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
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                ) { page ->
                    val item = items[page]
                    Box(Modifier.fillMaxSize()) {
                        if (item.isVideo) {
                            VideoPlayer(item, fill = true)
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
