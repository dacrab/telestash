package com.telestash.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.telestash.AppViewModel
import com.telestash.GalleryUi
import com.telestash.UploadProgress
import com.telestash.backup.BackupGate
import com.telestash.model.StashItem
import com.telestash.ui.itemCount

/** Placeholder cells shown during the very first cloud sync. */
private const val SKELETON_CELLS = 24

/** How close to the loaded end the grid gets before paging in more. */
private const val SCROLL_PRELOAD_THRESHOLD = 12

/** The photo/video grid: filter tabs, month-separated rows, selection and the backup banner. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    vm: AppViewModel,
    gallery: GalleryUi,
    photos: LazyPagingItems<TimelineRow>,
    selectedTab: Int,
    openTopicId: Long,
    padding: PaddingValues,
    onItemClick: (StashItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val selecting = gallery.selected != null
    // Collected here, not in the shell, so upload ticks don't recompose the whole app.
    val uploadProgress by vm.uploadProgress.collectAsStateWithLifecycle()
    val mediaFilter by vm.mediaFilter.collectAsStateWithLifecycle()
    val inAlbum = selectedTab == AppViewModel.TAB_ALBUMS && openTopicId != 0L
    // The filter only makes sense for the main library, so it resets on navigation.
    LaunchedEffect(selectedTab, openTopicId) {
        if (selectedTab != AppViewModel.TAB_PHOTOS || openTopicId != 0L) {
            vm.setMediaFilter(AppViewModel.FILTER_ALL)
        }
    }

    if (gallery.hasMore) {
        EndlessScrollTrigger(gridState, photos.itemCount) { vm.loadMore() }
    }

    // Nothing to show yet: either a first-load skeleton, or an explanation.
    if (photos.itemCount == 0) {
        if (gallery.syncing || gallery.loadingMore) {
            LoadingGrid(vm, padding, uploadProgress)
        } else {
            GalleryStatePanel(
                vm = vm,
                padding = padding,
                isError = gallery.syncError,
                inAlbum = inAlbum,
                mediaFilter = mediaFilter,
                uploadProgress = uploadProgress,
                onRetry = vm::refresh,
                onOpenSettings = { vm.selectTab(AppViewModel.TAB_SETTINGS) },
            )
        }
        return
    }

    GalleryBody(
        vm = vm,
        gallery = gallery,
        photos = photos,
        uploadProgress = uploadProgress,
        selectedTab = selectedTab,
        inAlbum = inAlbum,
        gridState = gridState,
        selecting = selecting,
        padding = padding,
        onItemClick = onItemClick,
    )
}

/** Skeleton placeholders behind the filter chips, during the first cloud sync. */
@Composable
private fun LoadingGrid(
    vm: AppViewModel,
    padding: PaddingValues,
    uploadProgress: UploadProgress?,
) = Column(
    Modifier
        .fillMaxSize()
        .padding(padding),
) {
    MediaFilterRow(vm)
    UploadProgressRow(uploadProgress)
    SkeletonGrid(PaddingValues(0.dp), Modifier.weight(1f))
}

@Composable
private fun GalleryBody(
    vm: AppViewModel,
    gallery: GalleryUi,
    photos: LazyPagingItems<TimelineRow>,
    uploadProgress: UploadProgress?,
    selectedTab: Int,
    inAlbum: Boolean,
    gridState: LazyGridState,
    selecting: Boolean,
    padding: PaddingValues,
    onItemClick: (StashItem) -> Unit,
) {
    val inPhotos = selectedTab == AppViewModel.TAB_PHOTOS && !inAlbum

    PullToRefreshBox(
        isRefreshing = gallery.syncing,
        onRefresh = vm::refresh,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (gallery.syncError && !gallery.syncing) {
                SyncErrorBanner(inAlbum = inAlbum, onRetry = vm::refresh)
            }
            if (inPhotos && !selecting) {
                BackupStatusBanner(vm, gallery, uploadProgress)
            } else if (inAlbum && !selecting) {
                UploadProgressRow(uploadProgress)
            }
            if (!selecting) MediaFilterRow(vm)

            MediaGrid(
                photos = photos,
                loadingMore = gallery.loadingMore,
                hasMore = gallery.hasMore,
                gridState = gridState,
                selecting = selecting,
                selected = gallery.selected,
                onToggleSelect = vm::toggleSelect,
                onEnterSelection = vm::enterSelection,
                onItemClick = onItemClick,
            )
        }
    }
}

/**
 * One consolidated backup status row above the timeline, Google Photos style:
 * active progress, "waiting for Wi-Fi/charger" pauses, or "N not backed up".
 */
@Composable
private fun BackupStatusBanner(
    vm: AppViewModel,
    gallery: GalleryUi,
    uploadProgress: UploadProgress?,
) {
    val context = LocalContext.current
    val autoBackup by vm.autoBackup.collectAsStateWithLifecycle(initialValue = false)
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle(initialValue = true)
    val chargingOnly by vm.chargingOnly.collectAsStateWithLifecycle(initialValue = false)
    val waitReason =
        remember(wifiOnly, chargingOnly) {
            BackupGate.waitReason(context, wifiOnly, chargingOnly)
        }
    val progress = uploadProgress
    if (progress != null) {
        UploadProgressRow(progress)
        return
    }

    val count = gallery.newMediaCount
    if (!autoBackup) {
        BackupNotice(
            icon = Icons.Filled.PhotoLibrary,
            title = "Backup is off",
            subtitle = "Turn on automatic backup to keep new photos safe.",
            action = "Set up backup",
            onAction = { vm.selectTab(AppViewModel.TAB_SETTINGS) },
        )
        return
    }
    if (count == 0) return

    if (waitReason != null) {
        BackupNotice(
            icon = Icons.Filled.CloudOff,
            title = "Backup paused · $waitReason",
            subtitle = "${itemCount(count)} waiting for the next eligible backup.",
        )
    } else {
        BackupNotice(
            icon = Icons.Filled.CloudUpload,
            title = "${itemCount(count)} waiting to back up",
            subtitle = "The next scheduled backup will handle them.",
        )
    }
}

@Composable
private fun BackupNotice(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (action != null) {
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

/** Slim "Uploading 4/12" row with a real progress bar. */
@Composable
private fun UploadProgressRow(progress: UploadProgress?) {
    if (progress == null) return
    val done = progress.safeDone
    val total = progress.safeTotal
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Uploading $done of $total"
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Uploading $done of $total",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                progress.percentLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Placeholder grid shown during the very first cloud sync. */
@Composable
private fun SkeletonGrid(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = PaddingValues(2.dp),
        modifier =
            modifier
                .padding(padding)
                .semantics {
                    contentDescription = "Loading library"
                    liveRegion = LiveRegionMode.Polite
                },
    ) {
        items(SKELETON_CELLS) {
            Box(
                Modifier
                    .fillMaxSize()
                    .aspectRatio(1f)
                    .padding(1.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

@Composable
private fun GalleryStatePanel(
    vm: AppViewModel,
    padding: PaddingValues,
    isError: Boolean,
    inAlbum: Boolean,
    mediaFilter: Int,
    uploadProgress: UploadProgress?,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val (title, message) = galleryStateCopy(isError, inAlbum, mediaFilter)
    val icon = if (isError) Icons.Filled.CloudOff else Icons.Filled.PhotoLibrary
    val iconTint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        UploadProgressRow(uploadProgress)
        MediaFilterRow(vm)
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(if (isError) 48.dp else 52.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier =
                Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry) { Text(if (isError) "Retry" else "Refresh") }
        if (!isError && !inAlbum && mediaFilter == AppViewModel.FILTER_ALL) {
            TextButton(onClick = onOpenSettings) { Text("Set up backup") }
        }
    }
}

private fun galleryStateCopy(
    isError: Boolean,
    inAlbum: Boolean,
    mediaFilter: Int,
): Pair<String, String> =
    when {
        isError && inAlbum -> "Couldn't load this album" to "Check your connection and try again."
        isError -> "Couldn't load your library" to "Check your connection and try again."
        inAlbum && mediaFilter == AppViewModel.FILTER_PHOTOS ->
            "No photos in this album" to "Try another filter or move items into this album."
        inAlbum && mediaFilter == AppViewModel.FILTER_VIDEOS ->
            "No videos in this album" to "Try another filter or move items into this album."
        inAlbum -> "This album is empty" to "Move photos and videos here from your library."
        mediaFilter == AppViewModel.FILTER_PHOTOS ->
            "No photos found" to "Try another filter or pull down to refresh."
        mediaFilter == AppViewModel.FILTER_VIDEOS ->
            "No videos found" to "Try another filter or pull down to refresh."
        else ->
            "Your media lives here" to
                "Back up your camera roll, or check back once media arrives. Pull down to refresh."
    }

@Composable
private fun SyncErrorBanner(
    inAlbum: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (inAlbum) {
                    "Showing saved items. Couldn't refresh this album."
                } else {
                    "Showing saved items. Couldn't refresh your library."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** All / Photos / Videos filter chips, Google Photos style. */
@Composable
private fun MediaFilterRow(vm: AppViewModel) {
    val filter by vm.mediaFilter.collectAsStateWithLifecycle()
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            FilterChip(
                selected = filter == AppViewModel.FILTER_ALL,
                onClick = { vm.setMediaFilter(AppViewModel.FILTER_ALL) },
                label = { Text("All") },
            )
        }
        item {
            FilterChip(
                selected = filter == AppViewModel.FILTER_PHOTOS,
                onClick = { vm.setMediaFilter(AppViewModel.FILTER_PHOTOS) },
                label = { Text("Photos") },
            )
        }
        item {
            FilterChip(
                selected = filter == AppViewModel.FILTER_VIDEOS,
                onClick = { vm.setMediaFilter(AppViewModel.FILTER_VIDEOS) },
                label = { Text("Videos") },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    photos: LazyPagingItems<TimelineRow>,
    loadingMore: Boolean,
    hasMore: Boolean,
    gridState: LazyGridState,
    selecting: Boolean,
    selected: Set<Long>?,
    onToggleSelect: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onItemClick: (StashItem) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            state = gridState,
            // Bottom room so the last row clears the FAB.
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = photos.itemCount,
                key =
                    photos.itemKey { row ->
                        when (row) {
                            is TimelineRow.Month -> "header_${row.label}"
                            is TimelineRow.Photo -> "photo_${row.item.messageId}"
                        }
                    },
                span = { index ->
                    if (photos.peek(index) is TimelineRow.Month) {
                        GridItemSpan(maxLineSpan)
                    } else {
                        GridItemSpan(1)
                    }
                },
            ) { index ->
                when (val row = photos[index]) {
                    is TimelineRow.Month ->
                        Text(
                            row.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .semantics { heading() },
                        )
                    is TimelineRow.Photo -> {
                        val item = row.item
                        val isSelected = selected?.contains(item.messageId) == true
                        GalleryCell(
                            item = item,
                            selected = isSelected,
                            selectionMode = selecting,
                            onClick = {
                                if (selecting) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onToggleSelect(item.messageId)
                                } else {
                                    onItemClick(item)
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onEnterSelection(item.messageId)
                            },
                        )
                    }
                    null ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .aspectRatio(1f)
                                .padding(1.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                }
            }
            if (loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            } else if (photos.itemCount > 0 && !hasMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "End of library",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Fires [onLoadMore] when the grid nears its loaded end (endless scroll). */
@Composable
private fun EndlessScrollTrigger(
    gridState: LazyGridState,
    itemCount: Int,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(gridState, itemCount) {
        // Emit a changing value (last visible index), not a boolean: while the
        // predicate sits at the same "near end = true" the flow must still fire
        // again after each page lands, or deep scrolling stalls forever.
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (info.totalItemsCount > 0 && last >= info.totalItemsCount - SCROLL_PRELOAD_THRESHOLD) {
                last
            } else {
                -1
            }
        }.collect { last ->
            if (last >= 0) onLoadMore()
        }
    }
}

/** Currently loaded photos of the paged timeline (bounded, not the whole library). */
fun snapshotPhotos(photos: LazyPagingItems<TimelineRow>): List<StashItem> =
    photos.itemSnapshotList.items.mapNotNull { (it as? TimelineRow.Photo)?.item }
