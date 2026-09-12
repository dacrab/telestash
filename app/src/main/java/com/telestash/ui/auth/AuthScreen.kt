package com.telestash.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.telestash.AppViewModel
import com.telestash.AuthUi

@Composable
fun AuthScreen(vm: AppViewModel, state: AuthUi) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("TeleStash", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Unlimited photo storage in your Telegram",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        when (state) {
            is AuthUi.Phone -> PhoneStep(vm)
            is AuthUi.Code -> CodeStep(vm, state.hint)
            is AuthUi.Password -> PasswordStep(vm, state.hint)
            is AuthUi.Registration -> RegistrationStep(vm)
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun PhoneStep(vm: AppViewModel) {
    var phone by remember { mutableStateOf("") }
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text("Phone number") },
        placeholder = { Text("+15551234567") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.submitPhone(phone) },
        enabled = phone.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Continue") }
}

@Composable
private fun CodeStep(vm: AppViewModel, hint: String) {
    var code by remember { mutableStateOf("") }
    Text(hint, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Login code") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.submitCode(code) },
        enabled = code.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Verify") }
}

@Composable
private fun PasswordStep(vm: AppViewModel, hint: String) {
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
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.submitPassword(pw) },
        enabled = pw.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Unlock") }
}

@Composable
private fun RegistrationStep(vm: AppViewModel) {
    var name by remember { mutableStateOf("") }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Your name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.submitRegistration(name) },
        enabled = name.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Create account") }
}
