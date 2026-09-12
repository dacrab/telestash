package com.telestash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.ui.TeleStashApp
import com.telestash.ui.theme.TeleStashTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm: AppViewModel by viewModels()
        setContent {
            TeleStashTheme {
                val state = vm.auth.collectAsStateWithLifecycle()
                TeleStashApp(vm, state)
            }
        }
    }
}
