package com.telestash.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeleStashApp(vm: AppViewModel, authState: androidx.compose.runtime.State<AuthUi>) {
    val gallery by vm.gallery.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var viewer by remember { mutableStateOf<StashItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showNewAlbum by remember { mutableStateOf(false) }
    var showAddToAlbum by remember { mutableStateOf(false) }
    var inAlbum by remember { mutableStateOf<Long?>(null) } // open album topic or null

    LaunchedEffect(vm) {
        vm.toasts.collectLatest { snackbar.showSnackbar(it) }
    }

    val selecting = gallery.selected != null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                SelectionTopBar(
                    count = gallery.selected?.size ?: 0,
                    onCancel = { vm.exitSelectionMode() },
                    onSelectAll = { vm.selectAll() },
                    onAddToAlbum = { showAddToAlbum = true },
                    onDownload = { vm.downloadSelected() },
                    onDelete = { vm.deleteSelected() },
                )
            } else if (inAlbum != null) {
                val album = gallery.albums.find { it.topicId == inAlbum }
                TopAppBar(
                    title = { Text(album?.name ?: "Album") },
                    navigationIcon = {
                        IconButton(onClick = { inAlbum = null; vm.closeTopic() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("TeleStash") },
                    actions = {
                        if (authState.value is AuthUi.Ready) {
                            IconButton(onClick = { showNewAlbum = true }) {
                                Icon(Icons.Filled.CreateNewFolder, contentDescription = "New album")
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                            IconButton(onClick = { vm.logout() }) {
                                Icon(Icons.Filled.Logout, contentDescription = "Log out")
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (authState.value is AuthUi.Ready && !selecting) {
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickMultipleVisualMedia(20)
                ) { uris ->
                    if (uris.isNotEmpty()) vm.upload(uris, inAlbum ?: 0L)
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(if (inAlbum != null) "Add to album" else "Upload") }
                )
            }
        }
    ) { padding ->
        when (val s = authState.value) {
            AuthUi.NeedCredentials -> OnboardingScreen(vm)

            AuthUi.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            is AuthUi.Phone, is AuthUi.Code, is AuthUi.Password, is AuthUi.Registration ->
                AuthScreen(vm, s)

            AuthUi.Ready -> GalleryScreen(
                vm, gallery, inAlbum, padding,
                onItemClick = { viewer = it },
                onOpenAlbum = { id -> inAlbum = id; vm.openTopic(id) },
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
            onDismiss = { viewer = null }
        )
    }

    if (showSettings) SettingsDialog(vm, onDismiss = { showSettings = false })
    if (showNewAlbum) NewAlbumDialog(vm, onDismiss = { showNewAlbum = false })
    if (showAddToAlbum) AddToAlbumDialog(vm, gallery.albums, onDismiss = { showAddToAlbum = false })
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
            IconButton(onClick = onAddToAlbum) { Icon(Icons.Filled.CreateNewFolder, contentDescription = "Add to album") }
            IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, contentDescription = "Download") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryScreen(
    vm: AppViewModel,
    gallery: GalleryUi,
    inAlbum: Long?,
    padding: PaddingValues,
    onItemClick: (StashItem) -> Unit,
    onOpenAlbum: (Long) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val selecting = gallery.selected != null

    // endless scroll trigger (main gallery only)
    if (inAlbum == null) {
        LaunchedEffect(gridState, gallery.items.size) {
            snapshotFlow {
                val info = gridState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= info.totalItemsCount - 12 && info.totalItemsCount > 0
            }.collect { should -> if (should) vm.loadMore() }
        }
    }

    // ---- album list view ----
    if (inAlbum == null && gallery.albums.isNotEmpty() && !selecting) {
        // show albums strip at top of the grid (row of chips), items grouped by month
    }

    if (gallery.items.isEmpty() && gallery.albums.isEmpty() && !gallery.syncing) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(
                "No photos yet.\nUpload or enable auto-backup in Settings.",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {
            // albums strip
            if (inAlbum == null && gallery.albums.isNotEmpty() && !selecting) {
                AlbumsStrip(gallery.albums, onOpen = onOpenAlbum)
            }
            if (gallery.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                val grouped = groupByMonth(gallery.items)
                grouped.forEach { (monthLabel, monthItems) ->
                    item(key = "header_$monthLabel") {
                        Text(
                            monthLabel,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    items(monthItems, key = { it.messageId }) { item ->
                        val selected = gallery.selected?.contains(item.messageId) == true
                        GalleryCell(
                            vm = vm,
                            item = item,
                            selected = selected,
                            selecting = selecting,
                            onClick = {
                                if (selecting) vm.toggleSelect(item.messageId)
                                else onItemClick(item)
                            },
                            onLongClick = { vm.enterSelection(item.messageId) },
                        )
                    }
                }
                if (gallery.loadingMore) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().height(48.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                    }
                }
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
            cal.timeInMillis = item.date * 1000L
            fmt.format(cal.time)
        }
        .map { (k, v) -> k to v }
}

@Composable
private fun AlbumsStrip(albums: List<AlbumEntity>, onOpen: (Long) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(albums.size) { i ->
            val a = albums[i]
            FilterChip(
                selected = false,
                onClick = { onOpen(a.topicId) },
                label = { Text(a.name) },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp)) }
            )
        }
    }
}
