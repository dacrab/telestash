package com.telestash.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.AppViewModel
import com.telestash.AppViewModel.Companion.MIN_API_HASH_LENGTH

/** Telegram api_id values are numeric IDs; a plausible one is at least this many digits. */
private const val MIN_API_ID_LENGTH = 6

/**
 * First-run welcome: what TeleStash is, then a card to connect the user's own
 * Telegram app (api_id / api_hash from my.telegram.org).
 */
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        BrandHeader()
        Spacer(Modifier.height(24.dp))
        FeatureRow(Icons.Filled.Lock, "Private", "Encrypted at rest in Saved Messages")
        Spacer(Modifier.height(8.dp))
        FeatureRow(Icons.Filled.AllInclusive, "Unlimited", "Free, no quotas on your own Telegram")
        Spacer(Modifier.height(8.dp))
        FeatureRow(Icons.Filled.Send, "Automatic", "Backs up new photos as they arrive")
        Spacer(Modifier.height(24.dp))
        CredentialsCard(vm)
        Spacer(Modifier.height(16.dp))
    }
}

/** The logo, name and one-line pitch shown above the form. */
@Composable
private fun BrandHeader() {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(72.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("TeleStash", style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Unlimited photo storage in your Telegram",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** Form to paste the Telegram API credentials and connect the account. */
@Composable
private fun CredentialsCard(vm: AppViewModel) {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var hashVisible by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val authError by vm.authError.collectAsStateWithLifecycle()

    val idValid = apiId.length >= MIN_API_ID_LENGTH
    val hashValid = apiHash.trim().length >= MIN_API_HASH_LENGTH

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Connect your Telegram app", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "TeleStash needs its own free API credentials. They never leave your device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                buildAnnotatedString {
                    append("Get them at ")
                    withLink(LinkAnnotation.Url("https://my.telegram.org")) {
                        append("my.telegram.org")
                    }
                    append(" → API development tools → Create an app.")
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
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiHash,
                onValueChange = { apiHash = it.trim() },
                label = { Text("api_hash") },
                visualTransformation =
                    if (hashVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { hashVisible = !hashVisible }) {
                        Icon(
                            if (hashVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (hashVisible) "Hide api_hash" else "Show api_hash",
                        )
                    }
                },
                isError = apiHash.isNotEmpty() && !hashValid,
                supportingText = {
                    if (apiHash.isNotEmpty() && !hashValid) {
                        Text("Looks too short - copy the full api_hash")
                    }
                },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            if (authError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    authError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    saving = true
                    vm.saveCredentials(apiId, apiHash)
                },
                enabled = idValid && hashValid && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting…")
                } else {
                    Text("Save and connect")
                }
            }
            TextButton(onClick = {
                apiId = ""
                apiHash = ""
            }) { Text("Start over") }
        }
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
