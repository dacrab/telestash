package com.telestash.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.paging.compose.LazyPagingItems
import com.telestash.AppViewModel
import com.telestash.data.AlbumEntity
import com.telestash.model.StashItem
import com.telestash.ui.gallery.PhotoViewerDialog
import com.telestash.ui.gallery.TimelineRow
import com.telestash.ui.gallery.snapshotPhotos

/** A destructive action awaiting confirmation. */
@Stable
sealed interface DialogState {
    data object None : DialogState

    /** Delete the single item the viewer is showing. */
    data class DeleteItem(
        val item: StashItem,
    ) : DialogState

    /** Delete the current multi-selection. */
    data class DeleteSelection(
        val count: Int,
    ) : DialogState
}

/**
 * Which overlays are showing. Held as one object so the app shell passes a
 * single value to [DialogHost] instead of a dozen flags.
 */
@Stable
class DialogHostState {
    var viewer by mutableStateOf<StashItem?>(null)
    var pendingDelete by mutableStateOf<DialogState>(DialogState.None)
    var confirmLogout by mutableStateOf(false)
    var showNewAlbum by mutableStateOf(false)
    var showAddToAlbum by mutableStateOf(false)

    fun closeAll() {
        viewer = null
        pendingDelete = DialogState.None
    }
}

@Composable
fun rememberDialogState(): DialogHostState = remember { DialogHostState() }

/** The full-screen viewer plus every confirm dialog layered above it. */
@Composable
fun DialogHost(
    dialogs: DialogHostState,
    vm: AppViewModel,
    photos: LazyPagingItems<TimelineRow>,
    selectionActive: Boolean,
    selectedCount: Int,
    openTopicId: Long,
    albums: List<AlbumEntity>,
) {
    val viewerItem = dialogs.viewer
    if (viewerItem != null) {
        val items = snapshotPhotos(photos)
        val index = items.indexOfFirst { it.messageId == viewerItem.messageId }
        PhotoViewerDialog(
            items = items,
            initialIndex = if (index >= 0) index else 0,
            onDownload = vm::download,
            onDelete = { item ->
                if (!selectionActive && dialogs.pendingDelete is DialogState.None) {
                    dialogs.pendingDelete = DialogState.DeleteItem(item)
                }
            },
            onDismiss = dialogs::closeAll,
        )
    }

    when (val request = dialogs.pendingDelete) {
        DialogState.None -> Unit
        is DialogState.DeleteItem ->
            DeleteConfirmDialog(
                count = 1,
                onConfirm = {
                    vm.delete(request.item)
                    dialogs.closeAll()
                },
                onDismiss = { dialogs.pendingDelete = DialogState.None },
            )
        is DialogState.DeleteSelection ->
            DeleteConfirmDialog(
                count = request.count,
                onConfirm = {
                    vm.deleteSelected()
                    dialogs.closeAll()
                },
                onDismiss = { dialogs.pendingDelete = DialogState.None },
            )
    }

    if (dialogs.confirmLogout) {
        AlertDialog(
            onDismissRequest = { dialogs.confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("You'll need to sign in again with your phone number.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.logout()
                    dialogs.confirmLogout = false
                }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { dialogs.confirmLogout = false }) { Text("Cancel") } },
        )
    }

    if (dialogs.showNewAlbum) {
        NewAlbumDialog(vm, albums, onDismiss = { dialogs.showNewAlbum = false })
    }
    if (dialogs.showAddToAlbum) {
        AddToAlbumDialog(
            vm = vm,
            albums = albums,
            selectedCount = selectedCount,
            sourceAlbumId = openTopicId,
            onDismiss = { dialogs.showAddToAlbum = false },
        )
    }
}

@Composable
private fun DeleteConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (count == 1) "Delete this item?" else "Delete $count items?") },
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
        TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
)
