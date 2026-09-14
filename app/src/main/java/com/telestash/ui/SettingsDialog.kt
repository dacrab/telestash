package com.telestash.ui

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.OutlinedButton
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
import androidx.core.content.ContextCompat
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
    var hasMediaPermission by remember { mutableStateOf(hasGalleryAccess(context)) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            hasMediaPermission = grants.values.any { it }
        }
    val appSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            hasMediaPermission = hasGalleryAccess(context)
        }

    // Auto-detected folders (with media counts), like Google Photos' backup list.
    LaunchedEffect(hasMediaPermission) {
        if (!hasMediaPermission) return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) { DeviceMedia.folders(context) }
        }.onSuccess { folders ->
            deviceFolders.clear()
            deviceFolders.addAll(folders)
        }
    }
    LaunchedEffect(backup) {
        if (backup && !hasMediaPermission) permissionLauncher.launch(neededPermissionsFor())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Auto-backup", style = MaterialTheme.typography.titleSmall)
                BackupToggles(
                    backup = backup,
                    wifi = wifi,
                    charging = charging,
                    instantOn = instantOn,
                    dedupeOn = dedupeOn,
                    onBackup = { backup = it },
                    onWifi = { wifi = it },
                    onCharging = { charging = it },
                    onInstant = { instantOn = it },
                    onDedupe = { dedupeOn = it },
                )

                if (backup && !hasMediaPermission) {
                    PermissionCard(onGrant = {
                        // After one denial the system only offers "Settings";
                        // take the user straight there.
                        appSettingsLauncher.launch(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    })
                }

                if (backup && hasMediaPermission && deviceFolders.isNotEmpty()) {
                    FolderList(
                        folders = deviceFolders,
                        enabled = backup,
                        chosen = chosenFolders,
                        onToggle = { folder, checked ->
                            if (checked) chosenFolders.add(folder) else chosenFolders.remove(folder)
                        },
                    )
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

/** Scrollable auto-detected folder list with checkboxes. */
@Composable
private fun FolderList(
    folders: List<DeviceFolder>,
    enabled: Boolean,
    chosen: List<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Text("Backup folders (none selected = all)", style = MaterialTheme.typography.titleSmall)
    LazyColumn(Modifier.heightIn(max = 200.dp)) {
        items(folders, key = { it.name }) { folder ->
            FolderRow(
                folder = folder,
                enabled = enabled,
                checked = folder.name in chosen,
                onCheckedChange = { checked -> onToggle(folder.name, checked) },
            )
        }
    }
}

/** The five auto-backup toggles. */
@Composable
private fun BackupToggles(
    backup: Boolean,
    wifi: Boolean,
    charging: Boolean,
    instantOn: Boolean,
    dedupeOn: Boolean,
    onBackup: (Boolean) -> Unit,
    onWifi: (Boolean) -> Unit,
    onCharging: (Boolean) -> Unit,
    onInstant: (Boolean) -> Unit,
    onDedupe: (Boolean) -> Unit,
) {
    SettingRow("Enable auto-backup") { Switch(checked = backup, onCheckedChange = onBackup) }
    SettingRow("Wi-Fi only") {
        Checkbox(enabled = backup, checked = wifi, onCheckedChange = onWifi)
    }
    SettingRow("Only when charging") {
        Checkbox(enabled = backup, checked = charging, onCheckedChange = onCharging)
    }
    SettingRow("Upload new media instantly") {
        Switch(enabled = backup, checked = instantOn, onCheckedChange = onInstant)
    }
    SettingRow("Skip duplicate photos") {
        Switch(checked = dedupeOn, onCheckedChange = onDedupe)
    }
}

/** "Grant access" card shown when backup is on but media permission is missing. */
@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text(
        "TeleStash needs access to your photos and videos to back them up.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("Grant access") }
}

/** Any of the gallery-read permissions granted? */
private fun hasGalleryAccess(context: android.content.Context): Boolean =
    neededPermissionsFor().any { p ->
        ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/** Gallery-read permissions for this OS version. */
private fun neededPermissionsFor(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
    } else {
        arrayOf(READ_EXTERNAL_STORAGE)
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
