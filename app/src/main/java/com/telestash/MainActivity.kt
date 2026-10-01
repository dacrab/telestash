package com.telestash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.ui.TeleStashApp
import com.telestash.ui.theme.TeleStashTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val vm: AppViewModel by viewModels()
        setContent {
            TeleStashTheme {
                val authState = vm.auth.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { vm.start() }
                TeleStashApp(vm, authState)
            }
        }
    }
}
