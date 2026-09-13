package com.telestash.ui

import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.AppViewModel

@Composable
fun SettingsDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    val autoBackup by vm.autoBackup.collectAsStateWithLifecycle(initialValue = false)
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle(initialValue = true)
    val chargingOnly by vm.chargingOnly.collectAsStateWithLifecycle(initialValue = false)
    val dedupe by vm.dedupeEnabled.collectAsStateWithLifecycle(initialValue = true)
    val savedFolders by vm.backupFolders.collectAsStateWithLifecycle(initialValue = emptySet())

    var backup by remember { mutableStateOf(autoBackup) }
    var wifi by remember { mutableStateOf(wifiOnly) }
    var charging by remember { mutableStateOf(chargingOnly) }
    var dedupeOn by remember { mutableStateOf(dedupe) }
    val chosenFolders = remember { mutableStateListOf<String>() }
    LaunchedEffect(savedFolders) {
        chosenFolders.clear()
        chosenFolders.addAll(savedFolders)
    }

    val context = LocalContext.current
    val deviceFolders = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        runCatching {
            val cr = context.contentResolver
            listOf(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            ).forEach { uri ->
                cr
                    .query(
                        uri,
                        arrayOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { c ->
                        val col = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                        while (c.moveToNext()) c.getString(col)?.let { deviceFolders.add(it) }
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
                    Text("Backup folders (none = all)", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(deviceFolders.distinct().sorted()) { folder ->
                            SettingRow(folder) {
                                Checkbox(
                                    enabled = backup,
                                    checked = folder in chosenFolders,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            chosenFolders.add(folder)
                                        } else {
                                            chosenFolders.remove(folder)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Auto-backup runs every ~6 hours in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        },
    )
}

@Composable
private fun SettingRow(
    label: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, Modifier.weight(1f), maxLines = 1)
        trailing()
    }
}
