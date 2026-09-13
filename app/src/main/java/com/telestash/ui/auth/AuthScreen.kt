package com.telestash.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.AppViewModel
import com.telestash.AuthUi

@Composable
fun AuthScreen(
    vm: AppViewModel,
    state: AuthUi,
) {
    val busy by vm.authBusyPublic.collectAsStateWithLifecycle()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
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
            is AuthUi.Phone -> PhoneStep(vm, busy)
            is AuthUi.Code -> CodeStep(vm, busy, state.hint)
            is AuthUi.Password -> PasswordStep(vm, busy, state.hint)
            is AuthUi.Registration -> RegistrationStep(vm, busy)
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun PhoneStep(
    vm: AppViewModel,
    busy: Boolean,
) {
    var phone by remember { mutableStateOf("") }
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text("Phone number") },
        placeholder = { Text("+15551234567") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.submitPhone(phone) },
        enabled = phone.isNotBlank() && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "Sending..." else "Continue") }
}

@Composable
private fun CodeStep(
    vm: AppViewModel,
    busy: Boolean,
    hint: String,
) {
    var code by remember { mutableStateOf("") }
    Text(hint, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Login code") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.submitCode(code) },
        enabled = code.isNotBlank() && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "Checking..." else "Verify") }
}

@Composable
private fun PasswordStep(
    vm: AppViewModel,
    busy: Boolean,
    hint: String,
) {
    var pw by remember { mutableStateOf("") }
    if (hint.isNotBlank()) Text("Hint: $hint", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = pw,
        onValueChange = { pw = it },
        label = { Text("Cloud password") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.submitPassword(pw) },
        enabled = pw.isNotBlank() && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Unlock") }
}

@Composable
private fun RegistrationStep(
    vm: AppViewModel,
    busy: Boolean,
) {
    var name by remember { mutableStateOf("") }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Your name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.submitRegistration(name) },
        enabled = name.isNotBlank() && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Create account") }
}
