package com.telestash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.telestash.AppViewModel
import com.telestash.AppViewModel.Companion.MIN_API_HASH_LENGTH

/** First-run screen: user pastes their own Telegram api_id / api_hash (my.telegram.org). */
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    val idValid = apiId.length >= 1
    val hashValid = apiHash.trim().length >= MIN_API_HASH_LENGTH

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .imePadding()
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
                    "TeleStash needs its own free API credentials:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    buildAnnotatedString {
                        append("1. Open ")
                        withLink(LinkAnnotation.Url("https://my.telegram.org")) {
                            append("my.telegram.org")
                        }
                        append(" and log in with your phone\n")
                        append("2. Open API development tools and create an app\n")
                        append("3. Copy the api_id and api_hash below")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiId,
                    onValueChange = { apiId = it.filter { ch -> ch.isDigit() } },
                    label = { Text("api_id") },
                    isError = apiId.isNotEmpty() && !idValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiHash,
                    onValueChange = { apiHash = it.trim() },
                    label = { Text("api_hash") },
                    isError = apiHash.isNotEmpty() && !hashValid,
                    supportingText = {
                        if (apiHash.isNotEmpty() && !hashValid) Text("Looks too short - copy the full api_hash")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.saveCredentials(apiId, apiHash) },
                    enabled = idValid && hashValid,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save and connect") }
            }
        }
    }
}
