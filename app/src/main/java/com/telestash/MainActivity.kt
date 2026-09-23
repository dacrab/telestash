package com.telestash

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestash.ui.TeleStashApp
import com.telestash.ui.theme.TeleStashTheme
import org.drinkless.tdlib.Client

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Forcing TDLib class init + native library load on the main thread exactly
        // once, so background threads can't race Client.create() and deadlock.
        runCatching {
            Client.execute(
                org.drinkless.tdlib.TdApi
                    .SetLogVerbosityLevel(0),
            )
        }
        // The background backup pass runs as a foreground service; its progress
        // notification needs this permission on 13+ (already granted → no-op).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
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
