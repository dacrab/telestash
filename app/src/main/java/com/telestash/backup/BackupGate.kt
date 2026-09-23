package com.telestash.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.telestash.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Applies the user's Wi-Fi/charging preferences to implicit auto-uploads (the
 * new-media watcher and toggling instant upload on). WorkManager enforces the
 * same constraints for the periodic worker natively; explicit user actions
 * (the banner button, manual picks) always bypass the gate.
 */
object BackupGate {
    suspend fun isBlocked(
        context: Context,
        settings: SettingsRepository,
    ): String? {
        if (settings.wifiOnly.first() && !isUnmetered(context)) return "Waiting for Wi-Fi"
        if (settings.chargingOnly.first() && !isCharging(context)) return "Waiting for a charger"
        return null
    }

    /**
     * Synchronous version for the gallery banner: reads the current connectivity
     * and charging state from the already-collected prefs, no DataStore wait.
     */
    fun waitReason(
        context: Context,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
    ): String? {
        if (wifiOnly && !isUnmetered(context)) return "Waiting for Wi-Fi"
        if (chargingOnly && !isCharging(context)) return "Waiting for a charger"
        return null
    }

    private fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun isCharging(context: Context): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        return bm.isCharging
    }
}
