package com.telestash.ui.auth

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.AppViewModel
import com.telestash.AuthUi
import kotlinx.coroutines.delay

private const val MIN_PHONE_DIGITS = 8
private const val AUTO_SUBMIT_MIN_DIGITS = 6
private const val RESEND_COOLDOWN_SECONDS = 60L

/** One second between countdown ticks. */
private const val RESEND_TICK_MS = 1_000L

/** Telegram login steps with a branded header and a visible way to go back. */
@Composable
fun AuthScreen(
    vm: AppViewModel,
    state: AuthUi,
) {
    val busy by vm.authBusyPublic.collectAsStateWithLifecycle()
    val error by vm.authError.collectAsStateWithLifecycle()
    // Phone is hoisted so "change number" returns to a filled field.
    var phone by remember { mutableStateOf("") }
    val cleanPhone = phone.trim().filterIndexed { i, ch -> ch.isDigit() || (ch == '+' && i == 0) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state !is AuthUi.Phone) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = { vm.backToAuthStep() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        } else {
            Spacer(Modifier.height(48.dp))
        }

        Text("TeleStash", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Unlimited photo storage in your Telegram",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        when (state) {
            is AuthUi.Phone -> PhoneStep(vm, busy, error, cleanPhone) { phone = it }
            is AuthUi.Code -> CodeStep(vm, busy, state.hint, error, cleanPhone)
            is AuthUi.Password -> PasswordStep(vm, busy, state.hint, error)
            is AuthUi.Registration -> RegistrationStep(vm, busy, error)
            // Handled by the parent screen, which never routes them here.
            AuthUi.Loading, AuthUi.NeedCredentials, AuthUi.Ready -> Unit
        }
    }
}

/** Inline error text under the active step's fields, if any. */
@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PhoneStep(
    vm: AppViewModel,
    busy: Boolean,
    error: String?,
    phone: String,
    onPhoneChanged: (String) -> Unit,
) {
    val cleaned = phone.filterIndexed { i, ch -> ch.isDigit() || (ch == '+' && i == 0) }
    OutlinedTextField(
        value = phone,
        onValueChange = { onPhoneChanged(it.trim().filterIndexed { i, ch -> ch.isDigit() || (ch == '+' && i == 0) }) },
        label = { Text("Phone number") },
        placeholder = { Text("+15551234567") },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done,
            ),
        keyboardActions =
            KeyboardActions(
                onDone = {
                    if (cleaned.length >= MIN_PHONE_DIGITS && !busy) vm.submitPhone(cleaned)
                },
            ),
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(error)
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.submitPhone(cleaned) },
        enabled = cleaned.length >= MIN_PHONE_DIGITS && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Sending…")
        } else {
            Text("Continue")
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "A code arrives in your Telegram app or by SMS.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CodeStep(
    vm: AppViewModel,
    busy: Boolean,
    hint: String,
    error: String?,
    phone: String,
) {
    var code by remember { mutableStateOf("") }
    val digits = code.filter { ch -> ch.isDigit() }
    var resendIn by remember { mutableLongStateOf(0L) }

    // Tick the "resend" countdown down once a second.
    LaunchedEffect(resendIn) {
        while (resendIn > 0) {
            delay(RESEND_TICK_MS)
            resendIn--
        }
    }

    Box(Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { vm.backToAuthStep() },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Text("Change number")
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "Code sent to ${if (phone.isBlank()) "your device" else phone}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { new ->
            code = new.filter { ch -> ch.isDigit() }
            // Auto-submit only when a full code lands at once (paste),
            // not while the user is still typing shorter codes.
            if (!busy && new.length >= AUTO_SUBMIT_MIN_DIGITS && code.length >= AUTO_SUBMIT_MIN_DIGITS) {
                vm.submitCode(code)
            }
        },
        label = { Text("Login code") },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { if (digits.isNotBlank() && !busy) vm.submitCode(digits) }),
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(error)
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.submitCode(digits) },
        enabled = digits.isNotBlank() && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Checking…")
        } else {
            Text("Verify")
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(
        enabled = resendIn == 0L && !busy,
        onClick = {
            vm.submitPhone(phone)
            resendIn = RESEND_COOLDOWN_SECONDS
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (resendIn > 0) "Resend code in ${resendIn}s" else "Didn't get it? Resend code",
        )
    }
}

@Composable
private fun PasswordStep(
    vm: AppViewModel,
    busy: Boolean,
    hint: String,
    error: String?,
) {
    var pw by remember { mutableStateOf("") }
    if (hint.isNotBlank()) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
    OutlinedTextField(
        value = pw,
        onValueChange = { pw = it },
        label = { Text("Cloud password") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { if (pw.isNotBlank() && !busy) vm.submitPassword(pw) }),
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(error)
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.submitPassword(pw) },
        enabled = pw.isNotBlank() && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Unlocking…")
        } else {
            Text("Unlock")
        }
    }
}

@Composable
private fun RegistrationStep(
    vm: AppViewModel,
    busy: Boolean,
    error: String?,
) {
    var name by remember { mutableStateOf("") }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Your name") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(error)
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.submitRegistration(name) },
        enabled = name.isNotBlank() && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Creating…")
        } else {
            Text("Create account")
        }
    }
}
