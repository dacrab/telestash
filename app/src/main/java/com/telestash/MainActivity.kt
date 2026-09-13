package com.telestash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.ui.TeleStashApp
import com.telestash.ui.theme.TeleStashTheme
import org.drinkless.tdlib.Client

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force TDLib class init + native library load on the main thread exactly
        // once, before background threads can race Client.create() and deadlock
        // the class-initialization lock against the library load.
        runCatching {
            Client.execute(
                org.drinkless.tdlib.TdApi
                    .SetLogVerbosityLevel(0),
            )
        }
        val vm: AppViewModel by viewModels()
        setContent {
            TeleStashTheme {
                val state = vm.auth.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { vm.start() }
                TeleStashApp(vm, state)
            }
        }
    }
}
