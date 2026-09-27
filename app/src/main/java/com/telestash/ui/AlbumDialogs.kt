package com.telestash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.telestash.AppViewModel
import com.telestash.data.AlbumEntity

private const val MAX_ALBUM_NAME = 64

@Composable
fun NewAlbumDialog(
    vm: AppViewModel,
    albums: List<AlbumEntity>,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val duplicate = albums.any { it.name.equals(trimmed, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New album") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_ALBUM_NAME) },
                    label = { Text("Album name") },
                    isError = trimmed.isNotBlank() && duplicate,
                    supportingText = {
                        if (trimmed.isNotBlank() && duplicate) {
                            Text("An album with this name already exists.")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Use albums to group related media together.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotBlank() && !duplicate,
                onClick = {
                    vm.createAlbum(trimmed)
                    onDismiss()
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AddToAlbumDialog(
    vm: AppViewModel,
    albums: List<AlbumEntity>,
    selectedCount: Int,
    sourceAlbumId: Long,
    onDismiss: () -> Unit,
) {
    var newAlbumName by remember(sourceAlbumId) { mutableStateOf("") }
    var chosenExisting by remember(sourceAlbumId) { mutableStateOf<Long?>(null) }
    val trimmedName = newAlbumName.trim()
    val duplicateName = trimmedName.isNotBlank() && albums.any { it.name.equals(trimmedName, ignoreCase = true) }
    val countText = if (selectedCount == 1) "1 item" else "$selectedCount items"
    val sourceText = sourceDescription(albums, sourceAlbumId)
    val destinationText = destinationDescription(albums, chosenExisting, trimmedName)
    val canMove = canMove(selectedCount, chosenExisting, sourceAlbumId, trimmedName, duplicateName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move $countText to an album") },
        text = {
            Column {
                Text(
                    "Move $countText $sourceText. The originals will be removed from the current view.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                AlbumChoices(albums, sourceAlbumId, chosenExisting) { chosenExisting = it }
                Spacer(Modifier.height(12.dp))
                NewAlbumNameField(
                    value = newAlbumName,
                    duplicateName = duplicateName,
                    destinationText = destinationText,
                    onValueChange = {
                        newAlbumName = it.take(MAX_ALBUM_NAME)
                        if (newAlbumName.isNotBlank()) chosenExisting = null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canMove,
                onClick = {
                    vm.addSelectedToAlbum(
                        albumTopicId = chosenExisting ?: 0L,
                        newAlbumName = trimmedName.takeIf { it.isNotBlank() },
                    )
                    onDismiss()
                },
            ) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AlbumChoices(
    albums: List<AlbumEntity>,
    sourceAlbumId: Long,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    if (albums.isEmpty()) {
        Text("No albums yet. Create one below.")
        return
    }
    LazyColumn(Modifier.heightIn(max = 300.dp)) {
        items(albums, key = { it.topicId }) { album ->
            val current = album.topicId == sourceAlbumId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selectedId == album.topicId,
                            onClick = { onSelect(album.topicId) },
                            enabled = !current,
                            role = Role.RadioButton,
                        ).padding(vertical = 8.dp),
            ) {
                Text(
                    if (current) "${album.name} (current album)" else album.name,
                    Modifier.weight(1f),
                )
                RadioButton(
                    selected = selectedId == album.topicId,
                    onClick = null,
                    enabled = !current,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun NewAlbumNameField(
    value: String,
    duplicateName: Boolean,
    destinationText: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("New album name") },
        isError = duplicateName,
        supportingText = {
            Text(if (duplicateName) "An album with this name already exists." else destinationText)
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun sourceDescription(
    albums: List<AlbumEntity>,
    sourceAlbumId: Long,
): String {
    val sourceName = albums.firstOrNull { it.topicId == sourceAlbumId }?.name
    return when {
        sourceName != null -> "from \"$sourceName\""
        sourceAlbumId != 0L -> "from this album"
        else -> "from your main library"
    }
}

private fun destinationDescription(
    albums: List<AlbumEntity>,
    selectedId: Long?,
    newName: String,
): String {
    val name =
        selectedId?.let { id -> albums.firstOrNull { it.topicId == id }?.name }
            ?: newName.takeIf { it.isNotBlank() }
    return name?.let { "Destination: $it" } ?: "Choose a destination album."
}

private fun canMove(
    selectedCount: Int,
    selectedId: Long?,
    sourceAlbumId: Long,
    newName: String,
    duplicateName: Boolean,
): Boolean =
    selectedCount > 0 &&
        (
            selectedId?.let { it != sourceAlbumId } == true ||
                (newName.isNotBlank() && !duplicateName)
        )
