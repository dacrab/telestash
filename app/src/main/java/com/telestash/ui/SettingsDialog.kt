package com.telestash.ui

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.telestash.device.DeviceFolder
import com.telestash.device.DeviceMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    val autoBackup by vm.autoBackup.collectAsStateWithLifecycle(initialValue = false)
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle(initialValue = true)
    val chargingOnly by vm.chargingOnly.collectAsStateWithLifecycle(initialValue = false)
    val dedupe by vm.dedupeEnabled.collectAsStateWithLifecycle(initialValue = true)
    val instant by vm.instantUpload.collectAsStateWithLifecycle(initialValue = false)
    val savedFolders by vm.backupFolders.collectAsStateWithLifecycle(initialValue = emptySet())

    var backup by remember { mutableStateOf(autoBackup) }
    var wifi by remember { mutableStateOf(wifiOnly) }
    var charging by remember { mutableStateOf(chargingOnly) }
    var dedupeOn by remember { mutableStateOf(dedupe) }
    var instantOn by remember { mutableStateOf(instant) }
    val chosenFolders = remember { mutableStateListOf<String>() }

    // DataStore emits after first composition; sync real values into the local
    // editing state as they arrive, or "Save" would overwrite with placeholders.
    LaunchedEffect(autoBackup, wifiOnly, chargingOnly, dedupe, instant) {
        backup = autoBackup
        wifi = wifiOnly
        charging = chargingOnly
        dedupeOn = dedupe
        instantOn = instant
    }
    LaunchedEffect(savedFolders) {
        chosenFolders.clear()
        chosenFolders.addAll(savedFolders)
    }

    val context = LocalContext.current
    val deviceFolders = remember { mutableStateListOf<DeviceFolder>() }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val neededPermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
        } else {
            arrayOf(READ_EXTERNAL_STORAGE)
        }

    // Auto-detected folders (with media counts), like Google Photos' backup list.
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) { DeviceMedia.folders(context) }
        }.onSuccess { folders ->
            deviceFolders.clear()
            deviceFolders.addAll(folders)
        }
    }
    LaunchedEffect(backup) {
        if (backup) permissionLauncher.launch(neededPermissions)
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
                SettingRow("Upload new media instantly") {
                    Switch(
                        enabled = backup,
                        checked = instantOn,
                        onCheckedChange = { instantOn = it },
                    )
                }
                SettingRow("Skip duplicate photos") {
                    Switch(checked = dedupeOn, onCheckedChange = { dedupeOn = it })
                }

                if (deviceFolders.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Backup folders (none selected = all)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(deviceFolders, key = { it.name }) { folder ->
                            FolderRow(
                                folder = folder,
                                enabled = backup,
                                checked = folder.name in chosenFolders,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        chosenFolders.add(folder.name)
                                    } else {
                                        chosenFolders.remove(folder.name)
                                    }
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Backs up the photos and videos in the chosen folders the first time, " +
                        "then new ones upload automatically" +
                        if (instantOn) " as soon as they're detected." else " every ~6 hours.",
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
                vm.setInstantUpload(instantOn)
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

/** A folder row: name, "N photos · M videos" count, and backup checkbox. */
@Composable
private fun FolderRow(
    folder: DeviceFolder,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(folder.name, maxLines = 1)
            if (folder.totalCount > 0) {
                Text(
                    folder.countLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Checkbox(enabled = enabled, checked = checked, onCheckedChange = onCheckedChange)
    }
}
