package com.telestash.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.AppViewModel
import com.telestash.AuthUi
import com.telestash.GalleryUi
import com.telestash.data.AlbumEntity
import com.telestash.model.StashItem
import com.telestash.ui.auth.AuthScreen
import com.telestash.ui.gallery.GalleryCell
import com.telestash.ui.gallery.PhotoViewerDialog
import com.telestash.util.Format
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** How many placeholder cells to show during first sync. */
private const val SKELETON_CELLS = 24

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeleStashApp(
    vm: AppViewModel,
    authState: androidx.compose.runtime.State<AuthUi>,
) {
    val gallery by vm.gallery.collectAsStateWithLifecycle()
    val uploadProgress by vm.uploadProgress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var viewer by remember { mutableStateOf<StashItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showNewAlbum by remember { mutableStateOf(false) }
    var showAddToAlbum by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val openTopicId = gallery.openTopicId

    LaunchedEffect(vm) {
        vm.toasts.collect { snackbar.showSnackbar(it) }
    }

    val selecting = gallery.selected != null
    val inAlbum = openTopicId != 0L

    // System back: leave selection/album instead of exiting the app.
    BackHandler(enabled = selecting || inAlbum) {
        if (selecting) {
            vm.exitSelectionMode()
        } else {
            vm.closeTopic()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopBars(
                selecting = selecting,
                selectedCount = gallery.selected?.size ?: 0,
                inAlbum = inAlbum,
                albumName = gallery.albums.find { it.topicId == openTopicId }?.name,
                isReady = authState.value is AuthUi.Ready,
                onCancelSelection = { vm.exitSelectionMode() },
                onSelectAll = { vm.selectAll() },
                onAddToAlbum = { showAddToAlbum = true },
                onDownloadSelected = { vm.downloadSelected() },
                onDeleteSelected = { confirmDelete = gallery.selected?.size ?: 0 },
                onBack = { vm.closeTopic() },
                onNewAlbum = { showNewAlbum = true },
                onSettings = { showSettings = true },
                onLogout = { confirmLogout = true },
            )
        },
        floatingActionButton = {
            if (authState.value is AuthUi.Ready && !selecting) {
                UploadFab(vm, inAlbum, openTopicId)
            }
        },
    ) { padding ->
        when (val s = authState.value) {
            AuthUi.NeedCredentials -> OnboardingScreen(vm)

            AuthUi.Loading ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

            is AuthUi.Phone, is AuthUi.Code, is AuthUi.Password, is AuthUi.Registration ->
                AuthScreen(vm, s)

            AuthUi.Ready ->
                GalleryScreen(
                    vm,
                    gallery,
                    uploadProgress,
                    openTopicId,
                    padding,
                    onItemClick = { viewer = it },
                    onOpenAlbum = { vm.openTopic(it) },
                )
        }
    }

    viewer?.let { v ->
        val index = gallery.items.indexOfFirst { it.messageId == v.messageId }
        PhotoViewerDialog(
            items = gallery.items,
            initialIndex = if (index >= 0) index else 0,
            onDownload = { item -> vm.download(item) },
            onDelete = { confirmDelete = 1 },
            onDismiss = { viewer = null },
        )
    }

    AppDialogs(
        vm = vm,
        albums = gallery.albums,
        confirmDelete = confirmDelete,
        onDeleteConfirmed = {
            val v = viewer
            if (v != null) {
                vm.delete(v)
                viewer = null
            } else {
                vm.deleteSelected()
            }
            confirmDelete = null
        },
        onDismissDelete = { confirmDelete = null },
        confirmLogout = confirmLogout,
        onLogoutConfirmed = {
            vm.logout()
            confirmLogout = false
        },
        onDismissLogout = { confirmLogout = false },
        showSettings = showSettings,
        onDismissSettings = { showSettings = false },
        showNewAlbum = showNewAlbum,
        onDismissNewAlbum = { showNewAlbum = false },
        showAddToAlbum = showAddToAlbum,
        onDismissAddToAlbum = { showAddToAlbum = false },
    )
}

@Composable
private fun UploadFab(
    vm: AppViewModel,
    inAlbum: Boolean,
    openTopicId: Long,
) {
    val picker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(AppViewModel.PICK_LIMIT),
        ) { uris ->
            if (uris.isNotEmpty()) vm.upload(uris, openTopicId)
        }
    ExtendedFloatingActionButton(
        onClick = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        },
        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
        text = { Text(if (inAlbum) "Add to album" else "Upload") },
    )
}

@Composable
private fun AppDialogs(
    vm: AppViewModel,
    albums: List<AlbumEntity>,
    confirmDelete: Int?,
    onDeleteConfirmed: () -> Unit,
    onDismissDelete: () -> Unit,
    confirmLogout: Boolean,
    onLogoutConfirmed: () -> Unit,
    onDismissLogout: () -> Unit,
    showSettings: Boolean,
    onDismissSettings: () -> Unit,
    showNewAlbum: Boolean,
    onDismissNewAlbum: () -> Unit,
    showAddToAlbum: Boolean,
    onDismissAddToAlbum: () -> Unit,
) {
    confirmDelete?.let { count ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Delete ${if (count > 1) "$count items" else "this item"}?") },
            text = { Text("This permanently removes it from your Telegram cloud. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = onDeleteConfirmed) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismissDelete) { Text("Cancel") } },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = onDismissLogout,
            title = { Text("Log out?") },
            text = { Text("You'll need to sign in again with your phone number.") },
            confirmButton = { TextButton(onClick = onLogoutConfirmed) { Text("Log out") } },
            dismissButton = { TextButton(onClick = onDismissLogout) { Text("Cancel") } },
        )
    }

    if (showSettings) SettingsDialog(vm, onDismiss = onDismissSettings)
    if (showNewAlbum) NewAlbumDialog(vm, onDismiss = onDismissNewAlbum)
    if (showAddToAlbum) AddToAlbumDialog(vm, albums, onDismiss = onDismissAddToAlbum)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBars(
    selecting: Boolean,
    selectedCount: Int,
    inAlbum: Boolean,
    albumName: String?,
    isReady: Boolean,
    onCancelSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onAddToAlbum: () -> Unit,
    onDownloadSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onBack: () -> Unit,
    onNewAlbum: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    when {
        selecting ->
            SelectionTopBar(
                count = selectedCount,
                onCancel = onCancelSelection,
                onSelectAll = onSelectAll,
                onAddToAlbum = onAddToAlbum,
                onDownload = onDownloadSelected,
                onDelete = onDeleteSelected,
            )

        inAlbum -> AlbumTopBar(albumName, onBack)

        else -> MainTopBar(isReady, onNewAlbum, onSettings, onLogout)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    isReady: Boolean,
    onNewAlbum: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    TopAppBar(
        title = { Text("TeleStash") },
        actions = {
            if (isReady) {
                IconButton(onClick = onNewAlbum) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "New album")
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
                IconButton(onClick = onLogout) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Log out")
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumTopBar(
    albumName: String?,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = { Text(albumName ?: "Album") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onAddToAlbum: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
        },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, contentDescription = "Select all") }
            IconButton(onClick = onAddToAlbum) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "Move to album")
            }
            IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, contentDescription = "Download") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryScreen(
    vm: AppViewModel,
    gallery: GalleryUi,
    uploadProgress: Pair<Int, Int>?,
    openTopicId: Long,
    padding: PaddingValues,
    onItemClick: (StashItem) -> Unit,
    onOpenAlbum: (Long) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val selecting = gallery.selected != null

    // endless scroll trigger (main gallery only)
    if (openTopicId == 0L) {
        EndlessScrollTrigger(gridState, gallery.items.size) { vm.loadMore() }
    }

    // First sync: skeleton grid so the app doesn't look empty-dead.
    if (gallery.items.isEmpty() && gallery.albums.isEmpty() && gallery.syncing) {
        SkeletonGrid(padding)
        return
    }

    if (gallery.items.isEmpty() && gallery.albums.isEmpty() && !gallery.syncing) {
        EmptyGallery(padding)
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (openTopicId == 0L && gallery.albums.isNotEmpty() && !selecting) {
                AlbumsStrip(gallery.albums, onOpen = onOpenAlbum)
            }
            if (gallery.newMediaCount > 0 && !selecting) {
                AnimatedVisibility(visible = true, enter = expandVertically(), exit = shrinkVertically()) {
                    NewMediaBanner(
                        count = gallery.newMediaCount,
                        onUpload = { vm.uploadPending() },
                        onDismiss = { vm.dismissNewMedia() },
                    )
                }
            }
            AnimatedVisibility(
                visible = uploadProgress != null,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                UploadProgressRow(uploadProgress)
            }
            if (gallery.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())

            MediaGrid(
                gallery = gallery,
                gridState = gridState,
                selecting = selecting,
                onToggleSelect = vm::toggleSelect,
                onEnterSelection = vm::enterSelection,
                onItemClick = onItemClick,
            )
        }
    }
}

/** Placeholder grid shown during the very first cloud sync. */
@Composable
private fun SkeletonGrid(padding: PaddingValues) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = PaddingValues(2.dp),
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
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

/** Slim "Uploading 4/12" row with a spinner. */
@Composable
private fun UploadProgressRow(progress: Pair<Int, Int>?) {
    if (progress == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Uploading ${progress.first + 1}/${progress.second}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        CircularProgressIndicator(
            Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
    }
}

/** Fires [onLoadMore] when the grid nears its loaded end (endless scroll). */
@Composable
private fun EndlessScrollTrigger(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    itemCount: Int,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(gridState, itemCount) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - AppViewModel.SCROLL_PRELOAD_THRESHOLD && info.totalItemsCount > 0
        }.collect { should -> if (should) onLoadMore() }
    }
}

/** "N new photos/videos on this device" prompt: upload now or dismiss. */
@Composable
private fun NewMediaBanner(
    count: Int,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Filled.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (count == 1) "1 new item not backed up" else "$count new items not backed up",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUpload) { Text("Back up") }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun EmptyGallery(padding: PaddingValues) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "No photos yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap Upload below, or enable auto-backup in Settings.\n" +
                    "Long-press photos to select, move or download them.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    gallery: GalleryUi,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    selecting: Boolean,
    onToggleSelect: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onItemClick: (StashItem) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        state = gridState,
        // Bottom room so the last row clears the FAB.
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 96.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        val grouped = groupByMonth(gallery.items)
        grouped.forEach { (monthLabel, monthItems) ->
            stickyHeader(key = "header_$monthLabel") {
                Text(
                    monthLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            items(monthItems, key = { it.messageId }) { item ->
                val selected = gallery.selected?.contains(item.messageId) == true
                // Fade+shrink new cells in so syncs feel smooth, not jumpy.
                AnimatedVisibility(
                    visibleState = remember { MutableTransitionState(false).apply { targetState = true } },
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                ) {
                    GalleryCell(
                        item = item,
                        selected = selected,
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
            }
        }
        if (gallery.loadingMore) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }
}

/** Groups items into month buckets with "Month YYYY" labels, newest first. */
private fun groupByMonth(items: List<StashItem>): List<Pair<String, List<StashItem>>> {
    val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val cal = Calendar.getInstance()
    return items
        .groupBy { item ->
            cal.timeInMillis = item.date * Format.EPOCH_MILLIS_PER_SECOND
            fmt.format(cal.time)
        }.map { (k, v) -> k to v }
}

@Composable
private fun AlbumsStrip(
    albums: List<AlbumEntity>,
    onOpen: (Long) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(albums.size) { i ->
            val a = albums[i]
            FilterChip(
                selected = false,
                onClick = { onOpen(a.topicId) },
                label = { Text(a.name) },
                leadingIcon = {
                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp))
                },
            )
        }
    }
}
