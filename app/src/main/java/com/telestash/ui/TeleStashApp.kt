package com.telestash.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
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
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val MILLIS_PER_SECOND = 1000L
private const val GALLERY_GRID_COLUMNS = 3
private val GRID_SPACING = 2.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeleStashApp(
    vm: AppViewModel,
    authState: androidx.compose.runtime.State<AuthUi>,
) {
    val gallery by vm.gallery.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var viewer by remember { mutableStateOf<StashItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showNewAlbum by remember { mutableStateOf(false) }
    var showAddToAlbum by remember { mutableStateOf(false) }
    val openTopicId = gallery.openTopicId

    LaunchedEffect(vm) {
        vm.toasts.collectLatest { snackbar.showSnackbar(it) }
    }

    val selecting = gallery.selected != null
    val inAlbum = openTopicId != 0L

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
                onDeleteSelected = { vm.deleteSelected() },
                onBack = { vm.closeTopic() },
                onNewAlbum = { showNewAlbum = true },
                onSettings = { showSettings = true },
                onLogout = { vm.logout() },
            )
        },
        floatingActionButton = {
            if (authState.value is AuthUi.Ready && !selecting) {
                val picker =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.PickMultipleVisualMedia(AppViewModel.PICK_LIMIT),
                    ) { uris ->
                        if (uris.isNotEmpty()) vm.upload(uris, openTopicId)
                    }
                ExtendedFloatingActionButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                        )
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(if (inAlbum) "Add to album" else "Upload") },
                )
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
                    openTopicId,
                    padding,
                    onItemClick = { viewer = it },
                    onOpenAlbum = { vm.openTopic(it) },
                )
        }
    }

    viewer?.let { v ->
        PhotoViewerDialog(
            item = v,
            onDownload = { vm.download(v) },
            onDelete = {
                vm.delete(v)
                viewer = null
            },
            onDismiss = { viewer = null },
        )
    }

    if (showSettings) SettingsDialog(vm, onDismiss = { showSettings = false })
    if (showNewAlbum) NewAlbumDialog(vm, onDismiss = { showNewAlbum = false })
    if (showAddToAlbum) AddToAlbumDialog(vm, gallery.albums, onDismiss = { showAddToAlbum = false })
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
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "Add to album")
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
    openTopicId: Long,
    padding: PaddingValues,
    onItemClick: (StashItem) -> Unit,
    onOpenAlbum: (Long) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val selecting = gallery.selected != null

    // endless scroll trigger (main gallery only)
    if (openTopicId == 0L) {
        LaunchedEffect(gridState, gallery.items.size) {
            snapshotFlow {
                val info = gridState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= info.totalItemsCount - AppViewModel.SCROLL_PRELOAD_THRESHOLD && info.totalItemsCount > 0
            }.collect { should -> if (should) vm.loadMore() }
        }
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

@Composable
private fun EmptyGallery(padding: PaddingValues) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "No photos yet.\nUpload or enable auto-backup in Settings.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    LazyVerticalGrid(
        columns = GridCells.Fixed(GALLERY_GRID_COLUMNS),
        state = gridState,
        contentPadding = PaddingValues(GRID_SPACING),
        modifier = Modifier.fillMaxSize(),
    ) {
        val grouped = groupByMonth(gallery.items)
        grouped.forEach { (monthLabel, monthItems) ->
            item(key = "header_$monthLabel") {
                Text(
                    monthLabel,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            items(monthItems, key = { it.messageId }) { item ->
                GalleryCell(
                    item = item,
                    selected = gallery.selected?.contains(item.messageId) == true,
                    onClick = {
                        if (selecting) onToggleSelect(item.messageId) else onItemClick(item)
                    },
                    onLongClick = { onEnterSelection(item.messageId) },
                )
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
            cal.timeInMillis = item.date * MILLIS_PER_SECOND
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
