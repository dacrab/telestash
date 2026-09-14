package com.telestash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.telestash.AppViewModel
import com.telestash.data.AlbumEntity

private const val MAX_ALBUM_NAME = 64

/** Dialog: create a new album (Saved Messages topic). */
@Composable
fun NewAlbumDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New album") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_ALBUM_NAME) },
                label = { Text("Album name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    vm.createAlbum(name.trim())
                    onDismiss()
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Dialog: pick an existing album or create a new one for the selected items. */
@Composable
fun AddToAlbumDialog(
    vm: AppViewModel,
    albums: List<AlbumEntity>,
    onDismiss: () -> Unit,
) {
    var newAlbumName by remember { mutableStateOf("") }
    var chosenExisting by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to album") },
        text = {
            Column {
                if (albums.isEmpty()) {
                    Text("No albums yet. Create one below.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(albums, key = { it.topicId }) { album ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(album.name, Modifier.weight(1f))
                                RadioButton(
                                    selected = chosenExisting == album.topicId,
                                    onClick = { chosenExisting = album.topicId },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newAlbumName,
                    onValueChange = {
                        newAlbumName = it.take(MAX_ALBUM_NAME)
                        if (newAlbumName.isNotBlank()) chosenExisting = null
                    },
                    label = { Text("New album name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosenExisting != null || newAlbumName.isNotBlank(),
                onClick = {
                    vm.addSelectedToAlbum(
                        albumTopicId = chosenExisting ?: 0L,
                        newAlbumName = newAlbumName.takeIf { it.isNotBlank() },
                    )
                    onDismiss()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
