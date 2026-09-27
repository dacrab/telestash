package com.telestash.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.telestash.AppViewModel
import com.telestash.AuthUi
import com.telestash.GalleryUi
import com.telestash.UploadProgress
import com.telestash.backup.BackupGate
import com.telestash.data.AlbumEntity
import com.telestash.data.TopicStats
import com.telestash.model.StashItem
import com.telestash.ui.albums.AlbumsScreen
import com.telestash.ui.auth.AuthScreen
import com.telestash.ui.gallery.GalleryCell
import com.telestash.ui.gallery.PhotoViewerDialog
import com.telestash.ui.gallery.TimelineRow
import com.telestash.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/** How many placeholder cells to show during first sync. */
private const val SKELETON_CELLS = 24

/** Corner radius of the tab indicator pill. */
private val TabPillShape = RoundedCornerShape(50)

/** Scale used to render a fraction as a whole-number percentage. */
private const val PERCENT = 100

private sealed interface PendingDelete {
    val count: Int

    data class Viewer(
        val item: StashItem,
    ) : PendingDelete {
        override val count: Int = 1
    }

    data class Selection(
        override val count: Int,
    ) : PendingDelete
}

@Suppress("CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeleStashApp(
    vm: AppViewModel,
    authState: androidx.compose.runtime.State<AuthUi>,
) {
    val gallery by vm.gallery.collectAsStateWithLifecycle()
    val uploadProgress by vm.uploadProgress.collectAsStateWithLifecycle()
    val selectedTab by vm.selectedTab.collectAsStateWithLifecycle()
    val albumCounts by vm.albumCounts.collectAsStateWithLifecycle(initialValue = null)
    val photos = vm.galleryPaging.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    var viewer by remember { mutableStateOf<StashItem?>(null) }
    var showNewAlbum by remember { mutableStateOf(false) }
    var showAddToAlbum by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val openTopicId = gallery.openTopicId
    val isReady = authState.value is AuthUi.Ready

    LaunchedEffect(vm) {
        vm.toasts.collect { snackbar.showSnackbar(it) }
    }

    val selecting = gallery.selected != null
    val inAlbum = selectedTab == AppViewModel.TAB_ALBUMS && openTopicId != 0L
    val baseFabVisible = isReady && !selecting && selectedTab != AppViewModel.TAB_SETTINGS
    val showFab = baseFabVisible && (selectedTab != AppViewModel.TAB_ALBUMS || inAlbum)

    // System back: leave selection/album/settings before exiting the app.
    BackHandler(enabled = isReady && (selecting || inAlbum || selectedTab == AppViewModel.TAB_SETTINGS)) {
        when {
            selecting -> vm.exitSelectionMode()
            inAlbum -> vm.closeTopic()
            else -> vm.selectTab(AppViewModel.TAB_PHOTOS)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (isReady) {
                TopBars(
                    selectedTab = selectedTab,
                    selecting = selecting,
                    selectedCount = gallery.selected?.size ?: 0,
                    inAlbum = inAlbum,
                    albumName = gallery.albums.find { it.topicId == openTopicId }?.name,
                    onCancelSelection = { vm.exitSelectionMode() },
                    onSelectAll = { vm.selectAll(snapshotPhotos(photos)) },
                    onAddToAlbum = { showAddToAlbum = true },
                    onDownloadSelected = vm::downloadSelected,
                    onDeleteSelected = {
                        pendingDelete = PendingDelete.Selection(gallery.selected?.size ?: 0)
                    },
                    onBack = { vm.closeTopic() },
                    onSettings = { vm.selectTab(AppViewModel.TAB_SETTINGS) },
                    onNewAlbum = { showNewAlbum = true },
                )
            }
        },
        bottomBar = {
            if (isReady && !selecting && selectedTab != AppViewModel.TAB_SETTINGS) {
                BottomNav(
                    selected = selectedTab,
                    onSelect = vm::selectTab,
                )
            }
        },
        floatingActionButton = {
            if (showFab) {
                AppFab(
                    vm = vm,
                    selectedTab = selectedTab,
                    inAlbum = inAlbum,
                    openTopicId = openTopicId,
                    onNewAlbum = { showNewAlbum = true },
                )
            }
        },
    ) { padding ->
        val content = authState.value
        if (content is AuthUi.Ready) {
            TabContent(
                vm = vm,
                gallery = gallery,
                photos = photos,
                uploadProgress = uploadProgress,
                selectedTab = selectedTab,
                openTopicId = openTopicId,
                albumCounts = albumCounts,
                padding = padding,
                onOpenViewer = { viewer = it },
                onNewAlbum = { showNewAlbum = true },
                onLogout = { confirmLogout = true },
            )
        } else {
            AuthGate(vm, content, padding)
        }
    }

    ViewerDialogs(
        photos = photos,
        viewer = viewer,
        updatedViewer = { viewer = it },
        pendingDelete = pendingDelete,
        updatedPendingDelete = { pendingDelete = it },
        selectedCount = gallery.selected?.size ?: 0,
        selectionActive = gallery.selected != null,
        openTopicId = openTopicId,
        confirmLogout = confirmLogout,
        showNewAlbum = showNewAlbum,
        showAddToAlbum = showAddToAlbum,
        albums = gallery.albums,
        vm = vm,
        updatedLogout = { confirmLogout = it },
        updatedNewAlbum = { showNewAlbum = it },
        updatedAddToAlbum = { showAddToAlbum = it },
    )
}

/** The screen shown while not signed in yet. */
@Composable
private fun AuthGate(
    vm: AppViewModel,
    content: AuthUi,
    padding: PaddingValues,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        when (content) {
            AuthUi.NeedCredentials -> OnboardingScreen(vm)
            AuthUi.Loading -> BrandedLoading()
            else -> AuthScreen(vm, content)
        }
    }
}

/** Content of the tab the user selected. */
@Composable
private fun TabContent(
    vm: AppViewModel,
    gallery: GalleryUi,
    photos: LazyPagingItems<TimelineRow>,
    uploadProgress: UploadProgress?,
    selectedTab: Int,
    openTopicId: Long,
    albumCounts: List<TopicStats>?,
    padding: PaddingValues,
    onOpenViewer: (StashItem) -> Unit,
    onNewAlbum: () -> Unit,
    onLogout: () -> Unit,
) {
    val inAlbum = selectedTab == AppViewModel.TAB_ALBUMS && openTopicId != 0L
    when (selectedTab) {
        AppViewModel.TAB_SETTINGS ->
            SettingsScreen(vm = vm, padding = padding, onLogout = onLogout)

        AppViewModel.TAB_ALBUMS ->
            if (inAlbum) {
                GalleryScreen(
                    vm,
                    gallery,
                    photos,
                    uploadProgress,
                    selectedTab,
                    openTopicId,
                    padding,
                    onItemClick = onOpenViewer,
                )
            } else {
                AlbumsScreen(
                    albums = gallery.albums,
                    counts = albumCounts.orEmpty().associate { it.topicId to it.count },
                    countsLoaded = albumCounts != null,
                    isLoading = gallery.syncing,
                    padding = padding,
                    onOpen = { vm.openTopic(it) },
                    onNewAlbum = onNewAlbum,
                )
            }

        else ->
            GalleryScreen(
                vm,
                gallery,
                photos,
                uploadProgress,
                selectedTab,
                openTopicId,
                padding,
                onItemClick = onOpenViewer,
            )
    }
}

/** The context-aware FAB that swaps by tab. */
@Composable
private fun AppFab(
    vm: AppViewModel,
    selectedTab: Int,
    inAlbum: Boolean,
    openTopicId: Long,
    onNewAlbum: () -> Unit,
) {
    when {
        selectedTab == AppViewModel.TAB_ALBUMS && inAlbum ->
            UploadFab(vm, inAlbum = true, openTopicId = openTopicId)

        selectedTab == AppViewModel.TAB_ALBUMS ->
            NewAlbumFab(onClick = onNewAlbum)

        else -> UploadFab(vm, inAlbum = false, openTopicId = 0L)
    }
}

/** Branded placeholder instead of a bare spinner while TDLib boots. */
@Composable
private fun BrandedLoading() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text("TeleStash", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("Starting TeleStash…", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Full-screen photo viewer + the confirm dialogs that live above it. */
@Composable
private fun ViewerDialogs(
    photos: LazyPagingItems<TimelineRow>,
    viewer: StashItem?,
    updatedViewer: (StashItem?) -> Unit,
    pendingDelete: PendingDelete?,
    updatedPendingDelete: (PendingDelete?) -> Unit,
    selectedCount: Int,
    selectionActive: Boolean,
    openTopicId: Long,
    confirmLogout: Boolean,
    showNewAlbum: Boolean,
    showAddToAlbum: Boolean,
    albums: List<AlbumEntity>,
    vm: AppViewModel,
    updatedLogout: (Boolean) -> Unit,
    updatedNewAlbum: (Boolean) -> Unit,
    updatedAddToAlbum: (Boolean) -> Unit,
) {
    val viewerItem = viewer
    if (viewerItem != null) {
        val items = snapshotPhotos(photos)
        val index = items.indexOfFirst { it.messageId == viewerItem.messageId }
        PhotoViewerDialog(
            items = items,
            initialIndex = if (index >= 0) index else 0,
            onDownload = { item -> vm.download(item) },
            onDelete = { item ->
                if (!selectionActive && pendingDelete !is PendingDelete.Selection) {
                    updatedPendingDelete(PendingDelete.Viewer(item))
                }
            },
            onDismiss = {
                updatedViewer(null)
                updatedPendingDelete(null)
            },
        )
    }

    pendingDelete?.let { request ->
        val count = request.count
        AlertDialog(
            onDismissRequest = { updatedPendingDelete(null) },
            title = {
                Text(if (count == 1) "Delete this item?" else "Delete $count items?")
            },
            text = {
                Text(
                    if (count == 1) {
                        "This permanently removes it from your Telegram cloud. This can't be undone."
                    } else {
                        "This permanently removes these $count items from your Telegram cloud. This can't be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (request) {
                            is PendingDelete.Viewer -> vm.delete(request.item)
                            is PendingDelete.Selection -> vm.deleteSelected()
                        }
                        updatedViewer(null)
                        updatedPendingDelete(null)
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { updatedPendingDelete(null) }) { Text("Cancel") }
            },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { updatedLogout(false) },
            title = { Text("Log out?") },
            text = { Text("You'll need to sign in again with your phone number.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.logout()
                    updatedLogout(false)
                }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { updatedLogout(false) }) { Text("Cancel") } },
        )
    }

    if (showNewAlbum) NewAlbumDialog(vm, albums, onDismiss = { updatedNewAlbum(false) })
    if (showAddToAlbum) {
        AddToAlbumDialog(
            vm = vm,
            albums = albums,
            selectedCount = selectedCount,
            sourceAlbumId = openTopicId,
            onDismiss = { updatedAddToAlbum(false) },
        )
    }
}

@Composable
private fun NewAlbumFab(onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
        text = { Text("New album") },
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
        text = { Text(if (inAlbum) "Add from device" else "Add media") },
    )
}

/** Two media destinations; Settings lives in the top bar. */
@Composable
private fun BottomNav(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    NavigationBar {
        NavItem(AppViewModel.TAB_PHOTOS, "Library", Icons.Filled.PhotoLibrary, selected, onSelect, Modifier.weight(1f))
        NavItem(AppViewModel.TAB_ALBUMS, "Albums", Icons.Filled.PhotoAlbum, selected, onSelect, Modifier.weight(1f))
    }
}

@Composable
private fun NavItem(
    tab: Int,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedItem = selected == tab
    val contentColor =
        if (selectedItem) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val interactionSource = remember { MutableInteractionSource() }
    val pill by animateColorAsState(
        if (selectedItem) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "navPill",
    )
    Column(
        modifier =
            modifier
                .selectable(
                    selected = selectedItem,
                    onClick = { onSelect(tab) },
                    role = Role.Tab,
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .height(30.dp)
                    .widthIn(min = 64.dp)
                    .clip(TabPillShape)
                    .background(pill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = contentColor)
        }
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = contentColor)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBars(
    selectedTab: Int,
    selecting: Boolean,
    selectedCount: Int,
    inAlbum: Boolean,
    albumName: String?,
    onCancelSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onAddToAlbum: () -> Unit,
    onDownloadSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onNewAlbum: () -> Unit,
) {
    val albumTitle = albumName ?: "Album"
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

        selectedTab == AppViewModel.TAB_SETTINGS -> SimpleTopBar("Settings")

        selectedTab == AppViewModel.TAB_ALBUMS && inAlbum -> BackTopBar(albumTitle, onBack, onSettings)

        selectedTab == AppViewModel.TAB_ALBUMS -> SimpleTopBar("Albums", onSettings, onNewAlbum)

        else -> SimpleTopBar("Library", onSettings)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleTopBar(
    title: String,
    onSettings: (() -> Unit)? = null,
    onNewAlbum: (() -> Unit)? = null,
) {
    TopAppBar(
        title = {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
            )
        },
        actions = {
            if (onNewAlbum != null) {
                IconButton(onClick = onNewAlbum) {
                    Icon(Icons.Filled.Add, contentDescription = "New album")
                }
            }
            if (onSettings != null) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackTopBar(
    title: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
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
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(
                "$count selected",
                modifier = Modifier.semantics { heading() },
            )
        },
        navigationIcon = {
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = "Select all loaded")
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Move to album") },
                    onClick = {
                        menuOpen = false
                        onAddToAlbum()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Save to device") },
                    onClick = {
                        menuOpen = false
                        onDownload()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        },
    )
}

/** Currently loaded photos of the paged timeline (bounded, not the whole library). */
private fun snapshotPhotos(photos: LazyPagingItems<TimelineRow>): List<StashItem> =
    photos.itemSnapshotList.items.mapNotNull { (it as? TimelineRow.Photo)?.item }

@OptIn(ExperimentalFoundationApi::class)
@Suppress("CyclomaticComplexMethod")
@Composable
private fun GalleryScreen(
    vm: AppViewModel,
    gallery: GalleryUi,
    photos: LazyPagingItems<TimelineRow>,
    uploadProgress: UploadProgress?,
    selectedTab: Int,
    openTopicId: Long,
    padding: PaddingValues,
    onItemClick: (StashItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val selecting = gallery.selected != null
    val mediaFilter by vm.mediaFilter.collectAsStateWithLifecycle()
    val inAlbum = selectedTab == AppViewModel.TAB_ALBUMS && openTopicId != 0L

    LaunchedEffect(selectedTab, openTopicId) {
        if (selectedTab != AppViewModel.TAB_PHOTOS || openTopicId != 0L) {
            vm.setMediaFilter(AppViewModel.FILTER_ALL)
        }
    }

    if (gallery.hasMore) {
        EndlessScrollTrigger(gridState, photos.itemCount) { vm.loadMore() }
    }

    when {
        photos.itemCount == 0 && (gallery.syncing || gallery.loadingMore) -> {
            if (uploadProgress == null) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    MediaFilterRow(vm)
                    SkeletonGrid(PaddingValues(0.dp), Modifier.weight(1f))
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    MediaFilterRow(vm)
                    UploadProgressRow(uploadProgress)
                    SkeletonGrid(PaddingValues(0.dp), Modifier.weight(1f))
                }
            }
        }

        photos.itemCount == 0 && gallery.syncError && !gallery.syncing ->
            GalleryStatePanel(
                vm = vm,
                padding = padding,
                isError = true,
                inAlbum = inAlbum,
                mediaFilter = mediaFilter,
                uploadProgress = uploadProgress,
                onRetry = vm::refresh,
                onOpenSettings = { vm.selectTab(AppViewModel.TAB_SETTINGS) },
            )

        photos.itemCount == 0 && !gallery.syncing ->
            GalleryStatePanel(
                vm = vm,
                padding = padding,
                isError = false,
                inAlbum = inAlbum,
                mediaFilter = mediaFilter,
                uploadProgress = uploadProgress,
                onRetry = vm::refresh,
                onOpenSettings = { vm.selectTab(AppViewModel.TAB_SETTINGS) },
            )

        else ->
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
                gallery = gallery,
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
    val total = progress.total.coerceAtLeast(0)
    val done = progress.done.coerceIn(0, total)
    val fraction = if (total > 0) done.toFloat() / total else 0f
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
                if (total > 0) "${(done * PERCENT / total)}%" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
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

private fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"

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
    gallery: GalleryUi,
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
            if (gallery.loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            } else if (photos.itemCount > 0 && !gallery.hasMore) {
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
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
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
            if (info.totalItemsCount > 0 && last >= info.totalItemsCount - AppViewModel.SCROLL_PRELOAD_THRESHOLD) {
                last
            } else {
                -1
            }
        }.collect { last ->
            if (last >= 0) onLoadMore()
        }
    }
}
