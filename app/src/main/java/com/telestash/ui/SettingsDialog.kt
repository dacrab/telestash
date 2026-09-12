package com.telestash.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.AppViewModel

@Composable
fun SettingsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val autoBackup by vm.autoBackup.collectAsStateWithLifecycle(initialValue = false)
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle(initialValue = true)
    val chargingOnly by vm.chargingOnly.collectAsStateWithLifecycle(initialValue = false)

    var backup by remember { mutableStateOf(autoBackup) }
    var wifi by remember { mutableStateOf(wifiOnly) }
    var charging by remember { mutableStateOf(chargingOnly) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Auto-backup", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Periodically upload new device photos & videos to Telegram",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable auto-backup", Modifier.weight(1f))
                    Switch(checked = backup, onCheckedChange = { backup = it })
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Wi-Fi only", Modifier.weight(1f))
                    Checkbox(enabled = backup, checked = wifi, onCheckedChange = { wifi = it })
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Only when charging", Modifier.weight(1f))
                    Checkbox(enabled = backup, checked = charging, onCheckedChange = { charging = it })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Runs every ~6 hours in the background via WorkManager.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.setAutoBackup(backup, wifi, charging)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = backup,
                    onClick = { vm.backupNow() }
                ) { Text("Back up now") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
