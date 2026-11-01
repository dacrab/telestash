package com.telestash.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.telestash.AppViewModel
import com.telestash.GalleryUi

/** Corner radius of the floating navigation pill. */
private val NavPillShape = RoundedCornerShape(28.dp)

/** Callbacks the top bars invoke, grouped so the bar API stays readable. */
@Stable
class TopBarActions(
    val onCancelSelection: () -> Unit,
    val onSelectAll: () -> Unit,
    val onAddToAlbum: () -> Unit,
    val onDownloadSelected: () -> Unit,
    val onDeleteSelected: () -> Unit,
    val onBack: () -> Unit,
    val onSettings: () -> Unit,
)

/** Floating expressive navigation pill with icon-only inactive destinations. */
@Composable
fun BottomNav(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            shape = NavPillShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ShortNavigationBar(
                containerColor = Color.Transparent,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                NavItem(AppViewModel.TAB_PHOTOS, "Library", Icons.Filled.PhotoLibrary, selected, onSelect)
                NavItem(AppViewModel.TAB_ALBUMS, "Albums", Icons.Filled.PhotoAlbum, selected, onSelect)
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: Int,
    label: String,
    icon: ImageVector,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    ShortNavigationBarItem(
        selected = selected == tab,
        onClick = { onSelect(tab) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
    )
}

/** Picks the top bar that matches the current navigation state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBars(
    selectedTab: Int,
    gallery: GalleryUi,
    albumName: String?,
    inAlbum: Boolean,
    actions: TopBarActions,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    when {
        gallery.selected != null ->
            SelectionTopBar(
                count = gallery.selected.size,
                onCancel = actions.onCancelSelection,
                onSelectAll = actions.onSelectAll,
            )

        selectedTab == AppViewModel.TAB_SETTINGS ->
            TopAppBar(
                title = {
                    Text("Settings", Modifier.semantics { heading() })
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
            )

        inAlbum ->
            MediumFlexibleTopAppBar(
                title = {
                    Text(albumName ?: "Album", Modifier.semantics { heading() })
                },
                subtitle = { Text("Album") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to albums")
                    }
                },
                actions = { SettingsButton(actions.onSettings) },
                scrollBehavior = scrollBehavior,
            )

        selectedTab == AppViewModel.TAB_ALBUMS ->
            LargeFlexibleTopAppBar(
                title = {
                    Text("Albums", Modifier.semantics { heading() })
                },
                subtitle = { Text(albumSubtitle(gallery)) },
                actions = { SettingsButton(actions.onSettings) },
                scrollBehavior = scrollBehavior,
            )

        else ->
            LargeFlexibleTopAppBar(
                title = {
                    Text("Library", Modifier.semantics { heading() })
                },
                subtitle = { Text(librarySubtitle(gallery)) },
                actions = { SettingsButton(actions.onSettings) },
                scrollBehavior = scrollBehavior,
            )
    }
}

/** A tonal circular settings affordance instead of a bare icon. */
@Composable
private fun SettingsButton(onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick) {
        Icon(Icons.Filled.Settings, contentDescription = "Settings")
    }
}

private fun librarySubtitle(gallery: GalleryUi): String =
    when {
        gallery.syncing -> "Syncing with Telegram…"
        gallery.syncError -> "Offline · showing saved items"
        else -> "Your Telegram Saved Messages"
    }

private fun albumSubtitle(gallery: GalleryUi): String {
    val count = gallery.albums.size
    return when {
        count == 0 -> "Group related media"
        count == 1 -> "1 album"
        else -> "$count albums"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                "$count selected",
                modifier = Modifier.semantics { heading() },
            )
        },
        navigationIcon = {
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel selection") }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = "Select all loaded")
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    )
}

/**
 * Expressive replacement for the old selection overflow menu: the bulk actions
 * live in a vibrant floating pill right above the gesture bar.
 */
@Composable
fun SelectionToolbar(
    count: Int,
    onAddToAlbum: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            leadingContent = {
                Text(
                    itemCount(count),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            },
        ) {
            IconButton(onClick = onAddToAlbum) {
                Icon(Icons.Filled.PhotoAlbum, contentDescription = "Move to album")
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = "Save to device")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}
