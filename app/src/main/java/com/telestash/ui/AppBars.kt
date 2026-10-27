package com.telestash.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.telestash.AppViewModel
import com.telestash.GalleryUi

/** Corner radius of the tab indicator pill. */
private val TabPillShape = RoundedCornerShape(50)

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
    val onNewAlbum: () -> Unit,
)

/** Library/Albums navigation bar with the animated selected pill. */
@Composable
fun BottomNav(
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
    icon: ImageVector,
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

/** Picks the top bar that matches the current navigation state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBars(
    selectedTab: Int,
    gallery: GalleryUi,
    albumName: String?,
    inAlbum: Boolean,
    actions: TopBarActions,
) {
    val selectedCount = gallery.selected?.size ?: 0
    when {
        gallery.selected != null ->
            SelectionTopBar(
                count = selectedCount,
                onCancel = actions.onCancelSelection,
                onSelectAll = actions.onSelectAll,
                onAddToAlbum = actions.onAddToAlbum,
                onDownload = actions.onDownloadSelected,
                onDelete = actions.onDeleteSelected,
            )

        selectedTab == AppViewModel.TAB_SETTINGS -> SimpleTopBar("Settings")

        inAlbum -> BackTopBar(albumName ?: "Album", actions.onBack, actions.onSettings)

        selectedTab == AppViewModel.TAB_ALBUMS -> SimpleTopBar("Albums", actions.onSettings, actions.onNewAlbum)

        else -> SimpleTopBar("Library", actions.onSettings)
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
