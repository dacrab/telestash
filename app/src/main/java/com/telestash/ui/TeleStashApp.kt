package com.telestash.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoAlbum
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
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
import com.telestash.util.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How many placeholder cells to show during first sync. */
private const val SKELETON_CELLS = 24

/** Corner radius of the tab indicator pill. */
private val TabPillShape = RoundedCornerShape(50)

/** Scale used to render a fraction as a whole-number percentage. */
private const val PERCENT = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeleStashApp(
    vm: AppViewModel,
    authState: androidx.compose.runtime.State<AuthUi>,
) {
    val gallery by vm.gallery.collectAsStateWithLifecycle()
    val uploadProgress by vm.uploadProgress.collectAsStateWithLifecycle()
    val selectedTab by vm.selectedTab.collectAsStateWithLifecycle()
    val albumCounts by vm.albumCounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val photos = vm.galleryPaging.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    var viewer by remember { mutableStateOf<StashItem?>(null) }
    var showNewAlbum by remember { mutableStateOf(false) }
    var showAddToAlbum by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    // The viewer item the delete dialog refers to (the swiped-to photo).
    var deleteTarget by remember { mutableStateOf<StashItem?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val openTopicId = gallery.openTopicId
    val isReady = authState.value is AuthUi.Ready

    LaunchedEffect(vm) {
        vm.toasts.collect { snackbar.showSnackbar(it) }
    }

    val selecting = gallery.selected != null
    val inAlbum = openTopicId != 0L

    // System back: leave selection/album/settings before exiting the app.
    BackHandler(enabled = selecting || inAlbum || selectedTab == AppViewModel.TAB_SETTINGS) {
        when {
            selecting -> vm.exitSelectionMode()
            inAlbum -> vm.closeTopic()
            else -> vm.selectTab(AppViewModel.TAB_PHOTOS)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopBars(
                selectedTab = selectedTab,
                selecting = selecting,
                selectedCount = gallery.selected?.size ?: 0,
                inAlbum = inAlbum,
                albumName = gallery.albums.find { it.topicId == openTopicId }?.name,
                onCancelSelection = { vm.exitSelectionMode() },
                onSelectAll = { vm.selectAll(snapshotPhotos(photos)) },
                onAddToAlbum = { showAddToAlbum = true },
                onDownloadSelected = { vm.downloadSelected(snapshotPhotos(photos)) },
                onDeleteSelected = { confirmDelete = gallery.selected?.size ?: 0 },
                onBack = { vm.closeTopic() },
            )
        },
        bottomBar = {
            if (isReady && !selecting) {
                BottomNav(
                    selected = selectedTab,
                    onSelect = vm::selectTab,
                )
            }
        },
        floatingActionButton = {
            if (isReady && !selecting) {
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
        deleteTarget = deleteTarget,
        updatedDeleteTarget = { deleteTarget = it },
        confirmDelete = confirmDelete,
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
    albumCounts: List<TopicStats>,
    padding: PaddingValues,
    onOpenViewer: (StashItem) -> Unit,
    onNewAlbum: () -> Unit,
    onLogout: () -> Unit,
) {
    val inAlbum = openTopicId != 0L
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
                    counts = albumCounts.associate { it.topicId to it.count },
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("TeleStash", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator()
    }
}

/** Full-screen photo viewer + the confirm dialogs that live above it. */
@Composable
private fun ViewerDialogs(
    photos: LazyPagingItems<TimelineRow>,
    viewer: StashItem?,
    updatedViewer: (StashItem?) -> Unit,
    deleteTarget: StashItem?,
    updatedDeleteTarget: (StashItem?) -> Unit,
    confirmDelete: Int?,
    confirmLogout: Boolean,
    showNewAlbum: Boolean,
    showAddToAlbum: Boolean,
    albums: List<AlbumEntity>,
    vm: AppViewModel,
    updatedLogout: (Boolean) -> Unit,
    updatedNewAlbum: (Boolean) -> Unit,
    updatedAddToAlbum: (Boolean) -> Unit,
) {
    var dialogDelete by remember { mutableStateOf(confirmDelete) }
    LaunchedEffect(confirmDelete) { dialogDelete = confirmDelete }

    val viewerItem = viewer
    if (viewerItem != null) {
        val items = snapshotPhotos(photos)
        val index = items.indexOfFirst { it.messageId == viewerItem.messageId }
        PhotoViewerDialog(
            items = items,
            initialIndex = if (index >= 0) index else 0,
            onDownload = { item -> vm.download(item) },
            onDelete = { item ->
                updatedDeleteTarget(item)
                dialogDelete = 1
            },
            onDismiss = { updatedViewer(null) },
        )
    }

    confirmDelete?.let { count ->
        AlertDialog(
            onDismissRequest = {
                updatedDeleteTarget(null)
                dialogDelete = null
            },
            title = { Text("Delete ${if (count > 1) "$count items" else "this item"}?") },
            text = { Text("This permanently removes it from your Telegram cloud. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = deleteTarget
                    if (target != null) {
                        vm.delete(target)
                        updatedViewer(null)
                    } else {
                        vm.deleteSelected(snapshotPhotos(photos))
                    }
                    updatedDeleteTarget(null)
                    dialogDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    updatedDeleteTarget(null)
                    dialogDelete = null
                }) { Text("Cancel") }
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
    if (showAddToAlbum) AddToAlbumDialog(vm, albums, onDismiss = { updatedAddToAlbum(false) })
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
        text = { Text(if (inAlbum) "Add to album" else "Upload") },
    )
}

/** Three-destination bottom bar: the app's primary navigation. */
@Composable
private fun BottomNav(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    NavigationBar {
        NavItem(AppViewModel.TAB_PHOTOS, "Photos", Icons.Filled.PhotoLibrary, selected, onSelect, Modifier.weight(1f))
        NavItem(AppViewModel.TAB_ALBUMS, "Albums", Icons.Filled.PhotoAlbum, selected, onSelect, Modifier.weight(1f))
        NavItem(AppViewModel.TAB_SETTINGS, "Settings", Icons.Filled.Settings, selected, onSelect, Modifier.weight(1f))
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
                .fillMaxHeight()
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

        selectedTab == AppViewModel.TAB_ALBUMS && inAlbum -> BackTopBar(albumTitle, onBack)

        selectedTab == AppViewModel.TAB_ALBUMS -> SimpleTopBar("Albums")

        else -> SimpleTopBar("TeleStash")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleTopBar(title: String) {
    TopAppBar(title = { Text(title) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackTopBar(
    title: String,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = { Text(title) },
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
                Icon(Icons.Filled.PhotoAlbum, contentDescription = "Move to album")
            }
            IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, contentDescription = "Download") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        },
    )
}

/** Currently loaded photos of the paged timeline (bounded, not the whole library). */
private fun snapshotPhotos(photos: LazyPagingItems<TimelineRow>): List<StashItem> =
    photos.itemSnapshotList.items.mapNotNull { (it as? TimelineRow.Photo)?.item }

@OptIn(ExperimentalFoundationApi::class)
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

    if (openTopicId == 0L) {
        EndlessScrollTrigger(gridState, photos.itemCount) { vm.loadMore() }
    }

    when {
        // First sync: skeleton grid so the app doesn't look empty-dead.
        photos.itemCount == 0 && gallery.syncing -> SkeletonGrid(padding)

        // Sync failed and there's nothing to show: give a retry, not a dead end.
        photos.itemCount == 0 && gallery.syncError && !gallery.syncing ->
            SyncErrorPanel(padding) { vm.refresh() }

        photos.itemCount == 0 && !gallery.syncing ->
            EmptyGallery(padding) {
                vm.refresh()
            }

        else ->
            GalleryBody(
                vm = vm,
                gallery = gallery,
                photos = photos,
                uploadProgress = uploadProgress,
                selectedTab = selectedTab,
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
    gridState: LazyGridState,
    selecting: Boolean,
    padding: PaddingValues,
    onItemClick: (StashItem) -> Unit,
) {
    val inPhotos =
        selectedTab == AppViewModel.TAB_PHOTOS && gallery.openTopicId == 0L

    PullToRefreshBox(
        isRefreshing = gallery.syncing,
        onRefresh = vm::refresh,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (inPhotos && !selecting) {
                BackupStatusBanner(vm, gallery, uploadProgress)
            }
            if (gallery.loadingMore) LinearProgressIndicator(Modifier.fillMaxWidth())

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
    // Recomputed when either preference flips; network changes land on next pass.
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
    if (count == 0) return

    if (autoBackup && waitReason != null) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "$waitReason · $count item(s) waiting",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        return
    }

    if (!autoBackup) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (count == 1) "1 new item not backed up" else "$count new items not backed up",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.uploadPending() }) { Text("Back up") }
                IconButton(onClick = { vm.dismissNewMedia() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }
            }
        }
    }
}

/** Slim "Uploading 4/12" row with a real progress bar. */
@Composable
private fun UploadProgressRow(progress: UploadProgress?) {
    if (progress == null) return
    val done = progress.done + 1
    val total = progress.total
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Uploading $done of $total …",
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
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
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

/** Retry surface for a failed gallery load, instead of a silently empty grid. */
@Composable
private fun SyncErrorPanel(
    padding: PaddingValues,
    onRetry: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Couldn't load your library",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Check your connection and try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** Empty library: a gentle nudge to back up from the device or turn on backup. */
@Composable
private fun EmptyGallery(
    padding: PaddingValues,
    onRetry: () -> Unit,
) {
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
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Your photos live here",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Back up your camera roll, or check back once media arrives.\n" +
                    "Pull down to refresh.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onRetry) { Text("Refresh") }
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
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    is TimelineRow.Photo -> {
                        val item = row.item
                        val isSelected = selected?.contains(item.messageId) == true
                        GalleryCell(
                            item = item,
                            selected = isSelected,
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
            }
        }
        DateScrubber(photos, gridState)
    }
}

/**
 * Google Photos style date scrubber: drag the edge rail to jump through the
 * timeline, with a month bubble. Only shown for libraries big enough to need it.
 */
@Composable
private fun DateScrubber(
    photos: LazyPagingItems<TimelineRow>,
    gridState: LazyGridState,
) {
    if (photos.itemCount < DATE_SCRUBBER_MIN_ITEMS) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var railHeight by remember { mutableFloatStateOf(1f) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    fun labelAt(f: Float): String {
        val index = (f * (photos.itemCount - 1)).toInt().coerceIn(0, photos.itemCount - 1)
        return when (val row = photos.peek(index)) {
            is TimelineRow.Month -> row.label
            is TimelineRow.Photo -> Format.monthYear(row.item.date)
            null -> ""
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        if (dragging) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
                modifier = Modifier.padding(end = 32.dp),
            ) {
                Text(
                    labelAt(fraction),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxHeight()
                .width(28.dp)
                .onSizeChanged { railHeight = it.height.toFloat().coerceAtLeast(1f) }
                .draggable(
                    state =
                        rememberDraggableState { delta ->
                            val next = (fraction + delta / railHeight).coerceIn(0f, 1f)
                            fraction = next
                            val index = (next * (photos.itemCount - 1)).toInt()
                            // A fast drag enqueues scrolls faster than they settle;
                            // only the newest jump should ever land.
                            scrollJob?.cancel()
                            scrollJob = scope.launch { gridState.scrollToItem(index) }
                        },
                    orientation = Orientation.Vertical,
                    onDragStarted = { dragging = true },
                    onDragStopped = { dragging = false },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(vertical = 48.dp)
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        MaterialTheme.shapes.small,
                    ),
            )
        }
    }
}

private const val DATE_SCRUBBER_MIN_ITEMS = 100

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
