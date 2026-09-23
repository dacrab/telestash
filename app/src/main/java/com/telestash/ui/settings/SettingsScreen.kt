package com.telestash.ui.settings

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.AppViewModel
import com.telestash.GalleryHealth
import com.telestash.backup.BackupGate
import com.telestash.device.DeviceFolder
import com.telestash.device.DeviceMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full Settings screen (a tab of the main scaffold, not a dialog): backup
 * toggles with descriptions, folder picks, library maintenance and account.
 */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    padding: PaddingValues,
    onLogout: () -> Unit,
) {
    val autoBackup by vm.autoBackup.collectAsStateWithLifecycle(initialValue = false)
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle(initialValue = true)
    val chargingOnly by vm.chargingOnly.collectAsStateWithLifecycle(initialValue = false)
    val dedupe by vm.dedupeEnabled.collectAsStateWithLifecycle(initialValue = true)
    val instant by vm.instantUpload.collectAsStateWithLifecycle(initialValue = false)
    val savedFolders by vm.backupFolders.collectAsStateWithLifecycle(initialValue = emptySet())
    val gallery by vm.gallery.collectAsStateWithLifecycle()
    val uploadProgress by vm.uploadProgress.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val deviceFolders = remember { mutableStateListOf<DeviceFolder>() }
    var hasMediaPermission by remember { mutableStateOf(hasGalleryAccess(context)) }
    var foldersLoading by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            hasMediaPermission = grants.values.any { it }
        }
    val appSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            hasMediaPermission = hasGalleryAccess(context)
        }

    LaunchedEffect(hasMediaPermission) {
        if (!hasMediaPermission) return@LaunchedEffect
        foldersLoading = true
        runCatching {
            withContext(Dispatchers.IO) { DeviceMedia.folders(context) }
        }.onSuccess { folders ->
            deviceFolders.clear()
            deviceFolders.addAll(folders)
        }
        foldersLoading = false
    }

    val waitReason =
        remember(wifiOnly, chargingOnly, gallery.newMediaCount, uploadProgress) {
            BackupGate.waitReason(context, wifiOnly, chargingOnly)
        }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
        verticalArrangement =
            androidx.compose.foundation.layout.Arrangement
                .spacedBy(8.dp),
    ) {
        item {
            BackupStatusCard(
                autoBackup = autoBackup,
                uploadProgress = uploadProgress,
                pendingCount = gallery.newMediaCount,
                waitReason = waitReason,
                onBackupNow = { vm.backupNow() },
            )
        }

        item { SectionHeader("Backup") }

        backupSection(vm, autoBackup, wifiOnly, chargingOnly, instant)

        foldersSection(
            vm = vm,
            autoBackup = autoBackup,
            hasMediaPermission = hasMediaPermission,
            foldersLoading = foldersLoading,
            savedFolders = savedFolders,
            deviceFolders = deviceFolders,
            onLaunchSettings = {
                appSettingsLauncher.launch(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
        )

        item { SectionHeader("Photos") }
        item {
            SettingSwitch(
                label = "Skip duplicate photos",
                description = "Recognizes identical photos you may have re-saved and uploads only one copy.",
                checked = dedupe,
                onCheckedChange = { vs -> vm.setDedupe(vs) },
            )
        }

        item { SectionHeader("Library maintenance") }
        item { CleanupSection(vm) }

        item { SectionHeader("Account") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Telegram connected", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Signed in to your Telegram account. Photos are stored privately in Saved Messages.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onLogout) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Log out")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** The backup toggles plus their immediate "back up now" shortcut. */
private fun LazyListScope.backupSection(
    vm: AppViewModel,
    autoBackup: Boolean,
    wifiOnly: Boolean,
    chargingOnly: Boolean,
    instant: Boolean,
) {
    item {
        SettingSwitch(
            label = "Back up my device",
            description = "Uploads the photos and videos in the chosen folders, then keeps new ones coming.",
            checked = autoBackup,
            onCheckedChange = { on -> vm.setAutoBackup(on, wifiOnly, chargingOnly) },
        )
    }
    item {
        SettingSwitch(
            label = "Wi-Fi only",
            description = "Saves your mobile data by uploading over unmetered connections.",
            checked = wifiOnly,
            enabled = autoBackup,
            onCheckedChange = { on -> vm.setAutoBackup(autoBackup, on, chargingOnly) },
        )
    }
    item {
        SettingSwitch(
            label = "Only while charging",
            description = "Backs up when plugged in — keeps the battery healthy on big uploads.",
            checked = chargingOnly,
            enabled = autoBackup,
            onCheckedChange = { on -> vm.setAutoBackup(autoBackup, wifiOnly, on) },
        )
    }
    item {
        SettingSwitch(
            label = "Upload new media instantly",
            description = "Starts the moment a new photo or video is detected, instead of on the schedule.",
            checked = instant,
            enabled = autoBackup,
            onCheckedChange = { vs -> vm.setInstantUpload(vs) },
        )
    }
    item {
        Button(
            onClick = { vm.backupNow() },
            enabled = autoBackup,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back up now") }
    }
}

/** The permission hint + folder picker list. */
private fun LazyListScope.foldersSection(
    vm: AppViewModel,
    autoBackup: Boolean,
    hasMediaPermission: Boolean,
    foldersLoading: Boolean,
    savedFolders: Set<String>,
    deviceFolders: List<DeviceFolder>,
    onLaunchSettings: () -> Unit,
) {
    item { SectionHeader("Folders to back up") }
    if (!autoBackup) {
        item {
            HintText("Turn on backup above to choose folders.")
        }
    } else if (!hasMediaPermission) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "TeleStash needs access to your photos and videos to back them up.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onLaunchSettings) { Text("Grant access") }
                }
            }
        }
    } else if (foldersLoading) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Scanning folders…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        item {
            FolderRow(
                label = "All folders",
                sub = "Everything on this device",
                enabled = autoBackup,
                checked = savedFolders.isEmpty(),
                onCheckedChange = { checked ->
                    val next = if (checked) emptySet() else deviceFolders.map { it.name }.toSet()
                    vm.setBackupFolders(next)
                },
            )
        }
        items(deviceFolders, key = { it.name }) { folder ->
            FolderRow(
                label = folder.name,
                sub = folder.countLabel(),
                enabled = autoBackup && savedFolders.isNotEmpty(),
                checked = folder.name in savedFolders,
                onCheckedChange = { checked ->
                    val updated = if (checked) savedFolders + folder.name else savedFolders - folder.name
                    vm.setBackupFolders(updated)
                },
            )
        }
    }
}

/** One-line summary of where backup stands, Google Photos style. */
@Composable
private fun BackupStatusCard(
    autoBackup: Boolean,
    uploadProgress: com.telestash.UploadProgress?,
    pendingCount: Int,
    waitReason: String?,
    onBackupNow: () -> Unit,
) {
    val (title, subtitle, color) =
        when {
            uploadProgress != null -> {
                val done = uploadProgress.done + 1
                Triple(
                    "Backing up $done of ${uploadProgress.total}",
                    "Uploading to your Telegram…",
                    MaterialTheme.colorScheme.primary,
                )
            }
            !autoBackup ->
                Triple(
                    "Backup is off",
                    "Your photos stay on your device until you turn it on.",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            waitReason != null ->
                Triple(
                    "Backup paused · $waitReason",
                    "$pendingCount item(s) still waiting.",
                    MaterialTheme.colorScheme.secondary,
                )
            pendingCount > 0 ->
                Triple(
                    "$pendingCount item(s) waiting to back up",
                    "Started automatically on the schedule.",
                    MaterialTheme.colorScheme.primary,
                )
            else ->
                Triple(
                    "Backup complete",
                    "Everything is safely in your Telegram.",
                    MaterialTheme.colorScheme.primary,
                )
        }

    Card(
        colors =
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(
                Icons.Filled.CloudUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (autoBackup) {
                TextButton(onClick = onBackupNow) {
                    Icon(
                        Icons.AutoMirrored.Filled.Login,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Sync")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A settings row: label + optional description, trailing switch. */
@Composable
private fun SettingSwitch(
    label: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Folder row: name + count, trailing checkbox. */
@Composable
private fun FolderRow(
    label: String,
    sub: String,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(enabled = enabled, checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Gallery health: find unloadable + duplicate cloud items, then remove them. */
@Composable
private fun CleanupSection(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf<GalleryHealth?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val summary = health
    val summaryText =
        if (summary == null) {
            "Finds items that can never load plus exact duplicates. Removes them from your Telegram cloud."
        } else if (summary.removableCount == 0) {
            "Gallery is clean: no broken or duplicate items found."
        } else {
            val dupes = summary.removableCount - summary.broken.size
            val groups = summary.duplicateGroups.size
            "Found ${summary.broken.size} unloadable and $dupes duplicate(s) in " +
                "$groups group(s). The oldest copy of each is kept."
        }
    Text(
        summaryText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (scanning) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = {
                scanning = true
                scope.launch {
                    health = runCatching { vm.scanGalleryHealth() }.getOrNull()
                    scanning = false
                }
            }) { Text(if (health == null) "Scan now" else "Scan again") }
            if ((health?.removableCount ?: 0) > 0) {
                TextButton(onClick = { confirmDelete = true }) { Text("Remove") }
            }
        }
    }
    if (confirmDelete) {
        val target = health
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${target?.removableCount ?: 0} items?") },
            text = {
                Text(
                    "This permanently deletes them from your Telegram cloud. " +
                        "The oldest copy of each duplicate is kept. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (target != null) vm.cleanGallery(target)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Any of the gallery-read permissions granted? */
private fun hasGalleryAccess(context: Context): Boolean =
    neededPermissionsFor().any { p ->
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }

/** Gallery-read permissions for this OS version. */
private fun neededPermissionsFor(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
    } else {
        arrayOf(READ_EXTERNAL_STORAGE)
    }
