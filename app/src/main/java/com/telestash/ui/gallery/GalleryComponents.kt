package com.telestash.ui.gallery

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.telestash.AppViewModel
import com.telestash.model.StashItem
import com.telestash.td.TgClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders a Telegram-stored photo. Loads from local TDLib cache, else downloads. */
@Composable
fun StashImage(item: StashItem, contentScale: ContentScale = ContentScale.Crop) {
    var path by remember(item.fileId) { mutableStateOf(item.localPath) }

    LaunchedEffect(item.fileId) {
        if (path == null || !File(path!!).exists()) {
            runCatching {
                path = TgClient.peek()?.downloadPhoto(item.fileId)
            }
        }
    }

    val p = path
    if (p != null && File(p).exists()) {
        AsyncImage(
            model = File(p),
            contentDescription = item.fileName,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            item.minithumb?.let { bytes ->
                val bmp = remember(item.messageId) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                }
                bmp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
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
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
            )
        }
    }
}

@Composable
fun PhotoViewerDialog(
    item: StashItem,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.fileName) },
        text = {
            Column {
                StashImage(item, contentScale = ContentScale.Fit)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${formatDate(item.date)} · ${formatSize(item.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) { Text("Save to device") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
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
