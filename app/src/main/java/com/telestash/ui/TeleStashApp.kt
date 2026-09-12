package com.telestash.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.AppViewModel
import com.telestash.AuthUi
import com.telestash.GalleryUi
import com.telestash.model.StashItem
import com.telestash.ui.auth.AuthScreen
import com.telestash.ui.gallery.GalleryCell
import com.telestash.ui.gallery.PhotoViewerDialog
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeleStashApp(vm: AppViewModel, authState: androidx.compose.runtime.State<AuthUi>) {
    val gallery by vm.gallery.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var viewer by remember { mutableStateOf<StashItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.toasts.collectLatest { snackbar.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("TeleStash") },
                actions = {
                    if (authState.value is AuthUi.Ready) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                        IconButton(onClick = { vm.logout() }) {
                            Icon(Icons.Filled.Logout, contentDescription = "Log out")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (authState.value is AuthUi.Ready) {
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickMultipleVisualMedia(20)
                ) { uris -> if (uris.isNotEmpty()) vm.upload(uris) }
                ExtendedFloatingActionButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                            )
                        )
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Upload") }
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

            AuthUi.Ready -> GalleryScreen(vm, gallery, padding, onItemClick = { viewer = it })
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

    if (showSettings) {
        SettingsDialog(vm, onDismiss = { showSettings = false })
    }
}

@Composable
private fun GalleryScreen(
    vm: AppViewModel,
    gallery: GalleryUi,
    padding: PaddingValues,
    onItemClick: (StashItem) -> Unit,
) {
    val gridState = rememberLazyGridState()

    // endless scroll trigger
    LaunchedEffect(gridState, gallery.items.size) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 12 && info.totalItemsCount > 0
        }.collect { shouldLoad ->
            if (shouldLoad) vm.loadMore()
        }
    }

    if (gallery.items.isEmpty() && !gallery.syncing) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(
                "No photos yet.\nUpload or enable auto-backup in Settings.",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(gallery.items, key = { it.messageId }) { item ->
                    GalleryCell(vm, item, onClick = { onItemClick(item) })
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
            if (gallery.syncing && gallery.items.isNotEmpty()) {
                LinearProgressIndicator(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                )
            }
        }
    }
}
