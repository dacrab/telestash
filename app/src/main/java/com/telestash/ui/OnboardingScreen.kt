package com.telestash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.telestash.AppViewModel

/** First-run screen: user pastes their own Telegram api_id / api_hash (my.telegram.org). */
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TeleStash", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Unlimited photo storage in your Telegram",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Connect your Telegram app", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Create free API credentials at my.telegram.org (API development tools), " +
                        "then paste them here. Stored only on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiId,
                    onValueChange = { apiId = it.filter { ch -> ch.isDigit() } },
                    label = { Text("api_id") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiHash,
                    onValueChange = { apiHash = it },
                    label = { Text("api_hash") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.saveCredentials(apiId, apiHash) },
                    enabled = apiId.isNotBlank() && apiHash.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save and connect") }
            }
        }
    }
}
