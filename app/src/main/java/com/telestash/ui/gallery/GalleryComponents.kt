package com.telestash.ui.gallery

import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MILLIS_PER_SECOND = 1000L

// 16:9 is the dominant aspect of phone camera video.
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
fun VideoPlayer(item: StashItem) {
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
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(VIDEO_ASPECT_RATIO),
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
                        .padding(6.dp)
                        .size(20.dp),
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
                        .padding(6.dp)
                        .size(16.dp),
            )
        }
    }
}

/** Full-screen viewer with zoom and video playback. */
@Composable
fun PhotoViewerDialog(
    item: StashItem,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.95f),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                        )
                    }
                }

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (item.isVideo) {
                        VideoPlayer(item)
                    } else {
                        StashImage(item, contentScale = ContentScale.Fit, zoomable = true)
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${formatDate(item.date)} - ${formatSize(item.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDownload) { Text("Save to device", color = Color.White) }
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun formatDate(epochSec: Int): String =
    SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(epochSec * MILLIS_PER_SECOND))

private const val BYTES_PER_KB = 1024L

private fun formatSize(bytes: Long): String {
    if (bytes < BYTES_PER_KB) return "$bytes B"
    val kb = bytes / BYTES_PER_KB.toDouble()
    if (kb < BYTES_PER_KB) return "%.1f KB".format(kb)
    val mb = kb / BYTES_PER_KB.toDouble()
    if (mb < BYTES_PER_KB) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / BYTES_PER_KB.toDouble())
}
