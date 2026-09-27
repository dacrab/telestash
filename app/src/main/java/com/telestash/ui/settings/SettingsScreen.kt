package com.telestash.ui.settings

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.AppViewModel
import com.telestash.GalleryHealth
import com.telestash.backup.BackupGate
import com.telestash.device.DeviceFolder
import com.telestash.device.DeviceMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backup preferences, folder selection, maintenance, and account actions. */
@Suppress("LongMethod")
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
    val remoteDedupe by vm.remoteDedupeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val instant by vm.instantUpload.collectAsStateWithLifecycle(initialValue = false)
    val savedFolders by vm.backupFolders.collectAsStateWithLifecycle(initialValue = emptySet())
    val gallery by vm.gallery.collectAsStateWithLifecycle()
    val uploadProgress by vm.uploadProgress.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val deviceFolders = remember { mutableStateListOf<DeviceFolder>() }
    var hasMediaPermission by remember { mutableStateOf(hasGalleryAccess(context)) }
    var hasPartialMediaPermission by remember { mutableStateOf(hasPartialGalleryAccess(context)) }
    var hasNotificationPermission by remember { mutableStateOf(hasNotificationAccess(context)) }
    var notificationPermissionDenied by remember { mutableStateOf(false) }
    var foldersLoading by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }

    fun refreshPermissionState() {
        hasMediaPermission = hasGalleryAccess(context)
        hasPartialMediaPermission = hasPartialGalleryAccess(context)
        hasNotificationPermission = hasNotificationAccess(context)
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionState()
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasNotificationPermission = granted
            notificationPermissionDenied = !granted
        }
    val appSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionState()
        }

    LaunchedEffect(hasMediaPermission, hasPartialMediaPermission) {
        if (!hasMediaPermission) return@LaunchedEffect
        foldersLoading = true
        try {
            val folders = withContext(Dispatchers.IO) { DeviceMedia.folders(context) }
            deviceFolders.clear()
            deviceFolders.addAll(folders)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            deviceFolders.clear()
        } finally {
            foldersLoading = false
        }
    }

    val waitReason =
        remember(wifiOnly, chargingOnly, gallery.newMediaCount, uploadProgress) {
            BackupGate.waitReason(context, wifiOnly, chargingOnly)
        }
    val needsNotificationPermission =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission
    val requestMediaPermission = { permissionLauncher.launch(neededPermissionsFor()) }
    val requestNotificationPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(POST_NOTIFICATIONS)
        }
    }
    val openAppSettings = {
        appSettingsLauncher.launch(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier =
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(),
            verticalArrangement =
                androidx.compose.foundation.layout.Arrangement
                    .spacedBy(8.dp),
        ) {
            item {
                BackupStatusCard(
                    autoBackup = autoBackup,
                    hasMediaPermission = hasMediaPermission,
                    hasPartialMediaPermission = hasPartialMediaPermission,
                    uploadProgress = uploadProgress,
                    pendingCount = gallery.newMediaCount,
                    waitReason = waitReason,
                    onBackupNow = { vm.backupNow() },
                )
            }

            item { SectionHeader("Backup") }

            backupSection(
                vm = vm,
                autoBackup = autoBackup,
                wifiOnly = wifiOnly,
                chargingOnly = chargingOnly,
                instant = instant,
                needsNotificationPermission = needsNotificationPermission,
                notificationPermissionDenied = notificationPermissionDenied,
                onRequestNotificationPermission = requestNotificationPermission,
                onOpenAppSettings = openAppSettings,
            )

            foldersSection(
                vm = vm,
                autoBackup = autoBackup,
                hasMediaPermission = hasMediaPermission,
                hasPartialMediaPermission = hasPartialMediaPermission,
                foldersLoading = foldersLoading,
                savedFolders = savedFolders,
                deviceFolders = deviceFolders,
                onRequestPermission = requestMediaPermission,
                onLaunchSettings = openAppSettings,
            )

            item { SectionHeader("Photos") }
            item {
                SettingSwitch(
                    label = "Skip duplicate media",
                    description = "Skips items with the same exact SHA-256 content and uploads only one copy.",
                    checked = dedupe,
                    onCheckedChange = { vs -> vm.setDedupe(vs) },
                )
            }
            item {
                TextButton(onClick = { advancedOpen = !advancedOpen }) {
                    Text(if (advancedOpen) "Hide advanced tools" else "Advanced tools")
                }
            }
            if (advancedOpen) {
                item {
                    SettingSwitch(
                        label = "Check Saved Messages",
                        description =
                            "Compares automatic main-library uploads with existing Saved Messages media; " +
                                "candidate files may be downloaded once to verify an exact match; " +
                                "album copies remain independent.",
                        checked = remoteDedupe,
                        enabled = dedupe,
                        onCheckedChange = { vs -> vm.setRemoteDedupe(vs) },
                    )
                }
                item { SectionHeader("Advanced maintenance") }
                item { CleanupSection(vm) }
            }

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
                                "Media is sent directly to your Telegram Saved Messages.",
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
}

private fun LazyListScope.backupSection(
    vm: AppViewModel,
    autoBackup: Boolean,
    wifiOnly: Boolean,
    chargingOnly: Boolean,
    instant: Boolean,
    needsNotificationPermission: Boolean,
    notificationPermissionDenied: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    item {
        SettingSwitch(
            label = "Back up my device",
            description = "Uploads the photos and videos in the chosen folders, then keeps new ones coming.",
            checked = autoBackup,
            onCheckedChange = { on ->
                if (on && needsNotificationPermission) onRequestNotificationPermission()
                vm.setAutoBackup(on, wifiOnly, chargingOnly)
            },
        )
    }
    if (autoBackup && needsNotificationPermission) {
        item {
            NotificationPermissionCard(
                denied = notificationPermissionDenied,
                onRequestPermission = onRequestNotificationPermission,
                onOpenAppSettings = onOpenAppSettings,
            )
        }
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
}

private fun LazyListScope.foldersSection(
    vm: AppViewModel,
    autoBackup: Boolean,
    hasMediaPermission: Boolean,
    hasPartialMediaPermission: Boolean,
    foldersLoading: Boolean,
    savedFolders: Set<String>,
    deviceFolders: List<DeviceFolder>,
    onRequestPermission: () -> Unit,
    onLaunchSettings: () -> Unit,
) {
    val allFolders = deviceFolders.mapTo(mutableSetOf()) { it.name }
    val allSelected = savedFolders.isEmpty() || (allFolders.isNotEmpty() && savedFolders.containsAll(allFolders))
    item { SectionHeader("Folders to back up") }
    if (hasPartialMediaPermission) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Photo access is partial. TeleStash can back up the items you selected.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Open app settings to choose more photos and videos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onLaunchSettings) { Text("Open app settings") }
                }
            }
        }
    }
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
                    OutlinedButton(onClick = onRequestPermission) { Text("Allow photo access") }
                    TextButton(onClick = onLaunchSettings) { Text("Open app settings") }
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
                checked = allSelected,
                onCheckedChange = { checked ->
                    vm.setBackupFolders(if (checked) emptySet() else allFolders)
                },
            )
        }
        items(deviceFolders, key = { it.name }) { folder ->
            FolderRow(
                label = folder.name,
                sub = folder.countLabel(),
                enabled = autoBackup,
                checked = allSelected || folder.name in savedFolders,
                onCheckedChange = { checked ->
                    val updated =
                        if (allSelected) {
                            if (checked) allFolders else allFolders - folder.name
                        } else if (checked) {
                            savedFolders + folder.name
                        } else if (savedFolders.size > 1) {
                            savedFolders - folder.name
                        } else {
                            savedFolders
                        }
                    vm.setBackupFolders(updated)
                },
            )
        }
    }
}

@Composable
private fun BackupStatusCard(
    autoBackup: Boolean,
    hasMediaPermission: Boolean,
    hasPartialMediaPermission: Boolean,
    uploadProgress: com.telestash.UploadProgress?,
    pendingCount: Int,
    waitReason: String?,
    onBackupNow: () -> Unit,
) {
    val (title, subtitle, color) =
        when {
            uploadProgress != null -> {
                val total = uploadProgress.total.coerceAtLeast(0)
                val done = uploadProgress.done.coerceIn(0, total)
                Triple(
                    "Uploading $done of $total",
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
            !hasMediaPermission ->
                Triple(
                    "Photo access needed",
                    "Allow photo and video access to back up your device.",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            hasPartialMediaPermission ->
                Triple(
                    "Partial photo access",
                    "Only the photos and videos you selected can be backed up.",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            waitReason != null ->
                Triple(
                    "Backup paused · $waitReason",
                    "${itemCount(pendingCount)} still waiting.",
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
            pendingCount > 0 ->
                Triple(
                    "${itemCount(pendingCount)} waiting to back up",
                    "Started automatically on the schedule.",
                    MaterialTheme.colorScheme.primary,
                )
            else ->
                Triple(
                    "No items currently waiting",
                    "Run a backup to check your device again.",
                    MaterialTheme.colorScheme.primary,
                )
        }

    val actionLabel = if (waitReason != null) "Back up anyway" else "Back up now"
    Card(
        colors =
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
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
                    color = color,
                    modifier = Modifier.semantics { heading() },
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
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun NotificationPermissionCard(
    denied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (denied) {
                    "Notifications are off. Backups can still run, but progress may be less visible."
                } else {
                    "Allow notifications to see backup progress."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = onRequestPermission) { Text("Allow notifications") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onOpenAppSettings) { Text("Open app settings") }
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
        modifier =
            Modifier
                .padding(top = 12.dp, bottom = 2.dp)
                .semantics { heading() },
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
                .toggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    enabled = enabled,
                    role = Role.Switch,
                ).padding(vertical = 8.dp),
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
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {},
        )
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
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    enabled = enabled,
                    role = Role.Checkbox,
                ),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(
            enabled = enabled,
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun CleanupSection(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf<GalleryHealth?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }

    val summary = health
    val summaryText =
        when {
            cleaning -> "Removing exact duplicates…"
            scanError != null -> "Scan failed: ${scanError.orEmpty()}"
            summary == null -> "Finds exact SHA-256 duplicate files and removes extras from Telegram."
            summary.removableCount == 0 -> "Gallery is clean: no exact SHA-256 duplicates found."
            else ->
                "Found ${duplicateCount(summary.removableCount)} in " +
                    "${groupCount(summary.duplicateGroups.size)}. The oldest copy of each is kept."
        }
    Text(
        summaryText,
        style = MaterialTheme.typography.bodySmall,
        color = if (scanError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (scanning) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(
                onClick = {
                    scanning = true
                    cleaning = false
                    health = null
                    scanError = null
                    scope.launch {
                        try {
                            health = vm.scanGalleryHealth()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            scanError = e.message ?: "unknown error"
                        } finally {
                            scanning = false
                        }
                    }
                },
                enabled = !scanning && !cleaning,
            ) { Text(if (health == null && !cleaning) "Scan now" else "Scan again") }
            if (!cleaning && (health?.removableCount ?: 0) > 0) {
                TextButton(onClick = { confirmDelete = true }) { Text("Remove") }
            }
        }
    }
    if (confirmDelete) {
        val target = health
        val removalCount = target?.removableCount ?: 0
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(if (removalCount == 1) "Remove 1 item?" else "Remove $removalCount items?")
            },
            text = {
                Text(
                    "This permanently deletes them from your Telegram cloud. " +
                        "The oldest copy of each exact SHA-256 duplicate is kept. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        if (target != null) {
                            health = null
                            cleaning = true
                            scope.launch {
                                try {
                                    vm.cleanGallery(target)
                                } finally {
                                    cleaning = false
                                }
                            }
                        }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"

private fun duplicateCount(count: Int): String =
    if (count == 1) "1 exact SHA-256 duplicate" else "$count exact SHA-256 duplicates"

private fun groupCount(count: Int): String = if (count == 1) "1 group" else "$count groups"

private fun hasGalleryAccess(context: Context): Boolean =
    (hasImageAccess(context) || hasVideoAccess(context) || hasSelectedMediaAccess(context)) &&
        (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        )

private fun hasImageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

private fun hasVideoAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

private fun hasSelectedMediaAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        ContextCompat.checkSelfPermission(context, READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

private fun hasPartialGalleryAccess(context: Context): Boolean =
    hasGalleryAccess(context) && !(hasImageAccess(context) && hasVideoAccess(context))

private fun hasNotificationAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun neededPermissionsFor(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
    } else {
        arrayOf(READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE)
    }
