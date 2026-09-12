package com.telestash.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.AppViewModel

@Composable
fun SettingsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val autoBackup by vm.autoBackup.collectAsStateWithLifecycle(initialValue = false)
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle(initialValue = true)
    val chargingOnly by vm.chargingOnly.collectAsStateWithLifecycle(initialValue = false)
    val dedupe by vm.dedupeEnabled.collectAsStateWithLifecycle(initialValue = true)
    val folders by vm.backupFolders.collectAsStateWithLifecycle(initialValue = "")

    var backup by remember { mutableStateOf(autoBackup) }
    var wifi by remember { mutableStateOf(wifiOnly) }
    var charging by remember { mutableStateOf(chargingOnly) }
    var dedupeOn by remember { mutableStateOf(dedupe) }
    val chosenFolders = remember { mutableStateListOf<String>() }
    LaunchedEffect(folders) {
        chosenFolders.clear()
        chosenFolders.addAll(folders.split(",").map { it.trim() }.filter { it.isNotBlank() })
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceFolders = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        // list MediaStore bucket names for folder selection
        runCatching {
            val cr = context.contentResolver
            arrayOf(
                android.provider.MediaStore.Images.Media.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL
                ),
                android.provider.MediaStore.Video.Media.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL
                ),
            ).forEach { uri ->
                cr.query(
                    uri,
                    arrayOf(android.provider.MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                    null, null, null
                )?.use { c ->
                    val col = c.getColumnIndexOrThrow(
                        android.provider.MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
                    )
                    while (c.moveToNext()) {
                        c.getString(col)?.let { deviceFolders.add(it) }
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Auto-backup", style = MaterialTheme.typography.titleSmall)
                SettingRow("Enable auto-backup") { Switch(checked = backup, onCheckedChange = { backup = it }) }
                SettingRow("Wi-Fi only") {
                    Checkbox(enabled = backup, checked = wifi, onCheckedChange = { wifi = it })
                }
                SettingRow("Only when charging") {
                    Checkbox(enabled = backup, checked = charging, onCheckedChange = { charging = it })
                }
                SettingRow("Skip duplicate photos") {
                    Switch(checked = dedupeOn, onCheckedChange = { dedupeOn = it })
                }

                if (deviceFolders.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Backup folders (none = all)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(deviceFolders.distinct().sorted()) { folder ->
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(folder, Modifier.weight(1f), maxLines = 1)
                                Checkbox(
                                    enabled = backup,
                                    checked = folder in chosenFolders,
                                    onCheckedChange = { checked ->
                                        if (checked) chosenFolders.add(folder)
                                        else chosenFolders.remove(folder)
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Auto-backup runs every ~6 hours in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.setDedupe(dedupeOn)
                vm.setBackupFolders(chosenFolders.toSet())
                vm.setAutoBackup(backup, wifi, charging)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = backup, onClick = { vm.backupNow() }) { Text("Back up now") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun SettingRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, Modifier.weight(1f))
        trailing()
    }
}
