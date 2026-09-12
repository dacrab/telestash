package com.telestash.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.AppViewModel
import com.telestash.AuthUi
import com.telestash.GalleryUi
import com.telestash.ui.auth.AuthScreen
import com.telestash.ui.gallery.GalleryCell
import com.telestash.ui.gallery.PhotoViewerDialog
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeleStashApp(vm: AppViewModel, authState: androidx.compose.runtime.State<AuthUi>) {
    val gallery by vm.gallery.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var viewer by remember { mutableStateOf<com.telestash.model.StashItem?>(null) }
    val scope = rememberCoroutineScope()

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
                        IconButton(onClick = { vm.logout() }) {
                            Icon(Icons.Filled.Logout, contentDescription = "Log out")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (authState.value is AuthUi.Ready) {
                val pickerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri -> uri?.let(vm::upload) }
                ExtendedFloatingActionButton(
                    onClick = {
                        pickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Upload") }
                )
            }
        }
    ) { padding ->
        when (val s = authState.value) {
            is AuthUi.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            is AuthUi.Phone, is AuthUi.Code, is AuthUi.Password, is AuthUi.Registration ->
                AuthScreen(vm, s)

            is AuthUi.Ready -> {
                LaunchedEffect(vm) { vm.refresh() }
                when (val g = gallery) {
                    is GalleryUi.Loading -> Box(
                        Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    is GalleryUi.Ready -> {
                        if (g.items.isEmpty()) {
                            Box(
                                Modifier.fillMaxSize().padding(padding),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No photos yet.\nTap Upload to stash your first photo in Telegram.",
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.fillMaxSize().padding(padding),
                                contentPadding = PaddingValues(2.dp)
                            ) {
                                items(g.items, key = { it.messageId }) { item ->
                                    GalleryCell(vm, item) { viewer = item }
                                }
                                if (g.loadingMore) {
                                    item {
                                        Box(
                                            Modifier.fillMaxWidth().height(48.dp),
                                            contentAlignment = Alignment.Center
                                        ) { CircularProgressIndicator() }
                                    }
                                }
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
                    }
                }
            }
        }
    }
}
