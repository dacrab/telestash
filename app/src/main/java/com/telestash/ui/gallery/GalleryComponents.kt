package com.telestash.ui.gallery

import android.graphics.BitmapFactory
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.telestash.AppViewModel
import com.telestash.model.StashItem
import com.telestash.td.TgClient
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders a Telegram-stored photo. Loads from local TDLib cache, else downloads. */
@Composable
fun StashImage(
    item: StashItem,
    contentScale: ContentScale = ContentScale.Crop,
    zoomable: Boolean = false,
) {
    var path by remember(item.fileId) { mutableStateOf(item.localPath) }

    LaunchedEffect(item.fileId) {
        val p = path
        if (p == null || !File(p!!).exists()) {
            runCatching { path = TgClient.peek()?.download(item.fileId) }
        }
    }

    val resolved = path
    if (resolved != null && File(resolved).exists()) {
        val imageModifier = if (zoomable) {
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
            modifier = imageModifier
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            item.minithumbPlaceholder()
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
fun VideoPlayer(item: StashItem) {
    val context = LocalContext.current
    var path by remember(item.fileId) { mutableStateOf(item.localPath) }

    LaunchedEffect(item.fileId) {
        val p = path
        if (p == null || !File(p!!).exists()) {
            runCatching { path = TgClient.peek()?.download(item.fileId) }
        }
    }

    val resolved = path
    if (resolved != null && File(resolved).exists()) {
        val exo = remember(resolved) {
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
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = true
                }
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun StashItem.minithumbPlaceholder() {
    // Minithumbnail bytes only exist on live TdApi objects; Room stores paths, so we
    // render a soft placeholder while the full file downloads.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
fun GalleryCell(vm: AppViewModel, item: StashItem, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(1.dp)
            .clickable { onClick() }
    ) {
        StashImage(item)
        if (item.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
            )
        }
        if (item.deviceUri != null) {
            Icon(
                Icons.Filled.CloudDone,
                contentDescription = "Backed up from device",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(16.dp)
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
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.95f),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(Modifier.fillMaxSize()) {
                // top bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    IconButton(onClick = onDismiss) {
                                        Text("✕", color = Color.White)
                    }
                }

                // media
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (item.isVideo) {
                        VideoPlayer(item)
                    } else {
                        StashImage(item, contentScale = ContentScale.Fit, zoomable = true)
                    }
                }

                // bottom bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${formatDate(item.date)} · ${formatSize(item.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDownload) { Text("Save to device", color = Color.White) }
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private fun formatDate(epochSec: Int): String =
    SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(epochSec * 1000L))

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}
