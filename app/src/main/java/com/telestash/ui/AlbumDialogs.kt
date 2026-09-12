package com.telestash.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.telestash.AppViewModel
import com.telestash.data.AlbumEntity

/** Dialog: create a new album (Saved Messages topic). */
@Composable
fun NewAlbumDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New album") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Album name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    vm.createAlbum(name.trim())
                    onDismiss()
                }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Dialog: pick an existing album or create a new one for the selected items. */
@Composable
fun AddToAlbumDialog(
    vm: AppViewModel,
    albums: List<AlbumEntity>,
    onDismiss: () -> Unit,
) {
    var newAlbumName by remember { mutableStateOf<String?>(null) }
    val chosenExisting = remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to album") },
        text = {
            Column {
                if (albums.isEmpty()) {
                    Text("No albums yet. Create one below.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(albums, key = { it.topicId }) { album ->
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(album.name, Modifier.weight(1f))
                                RadioButton(
                                    selected = chosenExisting.value == album.topicId,
                                    onClick = { chosenExisting.value = album.topicId }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newAlbumName ?: "",
                    onValueChange = { newAlbumName = it.ifBlank { null } },
                    label = { Text("...or new album name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosenExisting.value != null || !newAlbumName.isNullOrBlank(),
                onClick = {
                    vm.addSelectedToAlbum(
                        albumTopicId = chosenExisting.value ?: 0L,
                        newAlbumName = newAlbumName?.takeIf { it.isNotBlank() }
                    )
                    onDismiss()
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
