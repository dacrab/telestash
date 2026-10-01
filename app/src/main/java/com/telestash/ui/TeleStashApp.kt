package com.telestash.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.telestash.AppViewModel
import com.telestash.AuthUi
import com.telestash.GalleryUi
import com.telestash.UploadProgress
import com.telestash.data.TopicStats
import com.telestash.model.StashItem
import com.telestash.ui.albums.AlbumsScreen
import com.telestash.ui.auth.AuthScreen
import com.telestash.ui.gallery.GalleryScreen
import com.telestash.ui.gallery.TimelineRow
import com.telestash.ui.gallery.snapshotPhotos
import com.telestash.ui.settings.SettingsScreen

/**
 * App shell: top bars, bottom navigation, the content FAB, and the overlays.
 * Collects the shared state once and hands it to the pieces that need it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeleStashApp(
    vm: AppViewModel,
    authState: State<AuthUi>,
) {
    val gallery by vm.gallery.collectAsStateWithLifecycle()
    val uploadProgress by vm.uploadProgress.collectAsStateWithLifecycle()
    val selectedTab by vm.selectedTab.collectAsStateWithLifecycle()
    val albumCounts by vm.albumCounts.collectAsStateWithLifecycle(initialValue = null)
    val photos = vm.galleryPaging.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    val dialogs = rememberDialogState()
    val auth = authState.value
    val isReady = auth is AuthUi.Ready
    val selecting = gallery.selected != null
    val openTopicId = gallery.openTopicId
    val inAlbum = selectedTab == AppViewModel.TAB_ALBUMS && openTopicId != 0L

    LaunchedEffect(vm) { vm.toasts.collect { snackbar.showSnackbar(it) } }

    val chrome = chrome(isReady, selecting, selectedTab, inAlbum)

    // System back unwinds one layer at a time before leaving the app.
    BackHandler(enabled = chrome.handleBack) {
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
                    gallery = gallery,
                    albumName = gallery.albums.find { it.topicId == openTopicId }?.name,
                    inAlbum = inAlbum,
                    actions = rememberTopBarActions(vm, dialogs, photos),
                )
            }
        },
        bottomBar = {
            if (chrome.showBottomNav) {
                BottomNav(selected = selectedTab, onSelect = vm::selectTab)
            }
        },
        floatingActionButton = {
            if (chrome.showFab) {
                AppFab(
                    vm = vm,
                    selectedTab = selectedTab,
                    inAlbum = inAlbum,
                    openTopicId = openTopicId,
                    onNewAlbum = { dialogs.showNewAlbum = true },
                )
            }
        },
        content = { padding ->
            if (isReady) {
                TabContent(
                    vm = vm,
                    gallery = gallery,
                    photos = photos,
                    uploadProgress = uploadProgress,
                    selectedTab = selectedTab,
                    openTopicId = openTopicId,
                    albumCounts = albumCounts,
                    padding = padding,
                    onOpenViewer = { dialogs.viewer = it },
                    onNewAlbum = { dialogs.showNewAlbum = true },
                    onLogout = { dialogs.confirmLogout = true },
                )
            } else {
                AuthGate(vm, auth, padding)
            }
        },
    )

    DialogHost(
        dialogs = dialogs,
        vm = vm,
        photos = photos,
        selectionActive = selecting,
        selectedCount = gallery.selected?.size ?: 0,
        openTopicId = openTopicId,
        albums = gallery.albums,
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
    when (selectedTab) {
        AppViewModel.TAB_SETTINGS ->
            SettingsScreen(vm = vm, padding = padding, onLogout = onLogout)

        AppViewModel.TAB_ALBUMS ->
            // An open album shows the same timeline, scoped to its topic id.
            if (openTopicId != 0L) {
                GalleryScreen(
                    vm = vm,
                    gallery = gallery,
                    photos = photos,
                    uploadProgress = uploadProgress,
                    selectedTab = selectedTab,
                    openTopicId = openTopicId,
                    padding = padding,
                    onItemClick = onOpenViewer,
                )
            } else {
                AlbumsScreen(
                    albums = gallery.albums,
                    counts = albumCounts.orEmpty().associate { it.topicId to it.count },
                    countsLoaded = albumCounts != null,
                    isLoading = gallery.syncing,
                    padding = padding,
                    onOpen = vm::openTopic,
                    onNewAlbum = onNewAlbum,
                )
            }

        else ->
            GalleryScreen(
                vm = vm,
                gallery = gallery,
                photos = photos,
                uploadProgress = uploadProgress,
                selectedTab = selectedTab,
                openTopicId = 0L,
                padding = padding,
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

/** Which pieces of app chrome are visible, given the current navigation state. */
private class AppChrome(
    val showBottomNav: Boolean,
    val showFab: Boolean,
    val handleBack: Boolean,
)

/**
 * Settings takes over the screen (no tabs, no FAB), multi-select hides the
 * chrome behind the selection bar, and the album list swaps the add FAB for
 * "New album".
 */
private fun chrome(
    isReady: Boolean,
    selecting: Boolean,
    selectedTab: Int,
    inAlbum: Boolean,
): AppChrome {
    val onSettings = selectedTab == AppViewModel.TAB_SETTINGS
    val onAlbumList = selectedTab == AppViewModel.TAB_ALBUMS && !inAlbum
    return AppChrome(
        showBottomNav = isReady && !selecting && !onSettings,
        showFab = isReady && !selecting && !onSettings && !onAlbumList,
        handleBack = isReady && (selecting || inAlbum || onSettings),
    )
}

/**
 * The top bars' callbacks never change identity while the VM and dialog state
 * live, but the delete action must read the current selection, so it reads the
 * gallery through a snapshot state.
 */
@Composable
private fun rememberTopBarActions(
    vm: AppViewModel,
    dialogs: DialogHostState,
    photos: LazyPagingItems<TimelineRow>,
): TopBarActions {
    val galleryState = rememberUpdatedState(vm.gallery.collectAsStateWithLifecycle().value)
    return remember(vm, dialogs) {
        TopBarActions(
            onCancelSelection = vm::exitSelectionMode,
            onSelectAll = { vm.selectAll(snapshotPhotos(photos)) },
            onAddToAlbum = { dialogs.showAddToAlbum = true },
            onDownloadSelected = { vm.downloadSelected() },
            onDeleteSelected = {
                dialogs.pendingDelete = DialogState.DeleteSelection(galleryState.value.selected?.size ?: 0)
            },
            onBack = { vm.closeTopic() },
            onSettings = { vm.selectTab(AppViewModel.TAB_SETTINGS) },
            onNewAlbum = { dialogs.showNewAlbum = true },
        )
    }
}
