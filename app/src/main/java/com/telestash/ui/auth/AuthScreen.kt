package com.telestash.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.AppViewModel
import com.telestash.AuthUi

private const val MIN_PHONE_DIGITS = 8
private const val AUTO_SUBMIT_MIN_DIGITS = 6

/** Auth steps with per-step content; errors show inline under the fields. */
@Composable
fun AuthScreen(
    vm: AppViewModel,
    state: AuthUi,
) {
    val busy by vm.authBusyPublic.collectAsStateWithLifecycle()
    val error by vm.authError.collectAsStateWithLifecycle()
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
        Spacer(Modifier.height(4.dp))
        Text(
            "Unlimited photo storage in your Telegram",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        when (state) {
            is AuthUi.Phone -> PhoneStep(vm, busy, error)
            is AuthUi.Code -> CodeStep(vm, busy, state.hint, error)
            is AuthUi.Password -> PasswordStep(vm, busy, state.hint, error)
            is AuthUi.Registration -> RegistrationStep(vm, busy, error)
            else -> CircularProgressIndicator()
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
        )
    }
}

@Composable
private fun PhoneStep(
    vm: AppViewModel,
    busy: Boolean,
    error: String?,
) {
    var phone by remember { mutableStateOf("") }
    // Allow digits and a leading + only, so Telegram gets a clean number.
    val cleaned = phone.trim().filterIndexed { i, ch -> ch.isDigit() || (ch == '+' && i == 0) }
    OutlinedTextField(
        value = phone,
        onValueChange = {
            phone = it.trim().filterIndexed { i, ch -> ch.isDigit() || (ch == '+' && i == 0) }
        },
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
    ) { Text(if (busy) "Sending..." else "Continue") }
}

@Composable
private fun CodeStep(
    vm: AppViewModel,
    busy: Boolean,
    hint: String,
    error: String?,
) {
    var code by remember { mutableStateOf("") }
    val digits = code.filter { ch -> ch.isDigit() }
    Text(hint, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { new ->
            code = new.filter { ch -> ch.isDigit() }
            // Auto-submit only when a full code lands at once (paste),
            // not while the user is still typing shorter codes.
            val d = code.filter { ch -> ch.isDigit() }
            if (!busy && new.length >= AUTO_SUBMIT_MIN_DIGITS && d.length >= AUTO_SUBMIT_MIN_DIGITS) {
                vm.submitCode(d)
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
    ) { Text(if (busy) "Checking..." else "Verify") }
}

@Composable
private fun PasswordStep(
    vm: AppViewModel,
    busy: Boolean,
    hint: String,
    error: String?,
) {
    var pw by remember { mutableStateOf("") }
    if (hint.isNotBlank()) Text("Hint: $hint", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
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
    ) { Text(if (busy) "Unlocking..." else "Unlock") }
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
    ) { Text("Create account") }
}
