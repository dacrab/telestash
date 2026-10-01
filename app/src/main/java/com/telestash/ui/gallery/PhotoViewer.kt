package com.telestash.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.telestash.model.StashItem
import com.telestash.util.Format

/** Full-screen viewer: swipe between items, zoom photos, play videos. */
@Composable
fun PhotoViewerDialog(
    items: List<StashItem>,
    initialIndex: Int,
    onDownload: (StashItem) -> Unit,
    onDelete: (StashItem) -> Unit,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, items.lastIndex)) { items.size }
    // Track the swiped-to item so the action row always acts on what's visible.
    var current by remember { mutableStateOf(items[pagerState.currentPage]) }
    LaunchedEffect(pagerState.currentPage) {
        items.getOrNull(pagerState.currentPage)?.let { current = it }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // System back dismisses the viewer (and stops video playback).
        BackHandler(onBack = onDismiss)
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
                    IconButton(onClick = { onDelete(current) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = Color.White,
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
                    val active = pagerState.currentPage == page
                    Box(Modifier.fillMaxSize()) {
                        if (item.isVideo) {
                            // Only the active page needs the real file; neighbors
                            // resolve a thumbnail so swiping stays cheap.
                            VideoPlayer(item, active = active, thumbOnly = !active)
                        } else {
                            StashImage(
                                item,
                                contentScale = ContentScale.Fit,
                                zoomable = active,
                                thumbOnly = !active,
                            )
                        }
                    }
                }

                Text(
                    "${current.fileName} · ${Format.size(current.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
