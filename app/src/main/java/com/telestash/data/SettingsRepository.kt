package com.telestash.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settings: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * App settings backed by DataStore. Telegram api credentials are provided by the user
 * on first launch (or baked in at build time for personal use).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val API_ID = intPreferencesKey("tg_api_id")
        val API_HASH = stringPreferencesKey("tg_api_hash")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup_enabled")
        val BACKUP_ON_WIFI = booleanPreferencesKey("backup_wifi_only")
        val BACKUP_WHEN_CHARGING = booleanPreferencesKey("backup_charging_only")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val BACKUP_FOLDERS = stringPreferencesKey("backup_folders")
        val DEDUPE = booleanPreferencesKey("dedupe_enabled")
    }

    val credentials: Flow<Pair<Int, String>> = context.settings.data.map { p ->
        (p[Keys.API_ID] ?: 0) to (p[Keys.API_HASH] ?: "")
    }

    suspend fun setCredentials(apiId: Int, apiHash: String) {
        context.settings.edit { it[Keys.API_ID] = apiId; it[Keys.API_HASH] = apiHash }
    }

    suspend fun credentialsOnce(): Pair<Int, String> = credentials.first()

    val autoBackup: Flow<Boolean> = context.settings.data.map { it[Keys.AUTO_BACKUP] ?: false }
    val wifiOnly: Flow<Boolean> = context.settings.data.map { it[Keys.BACKUP_ON_WIFI] ?: true }
    val chargingOnly: Flow<Boolean> = context.settings.data.map { it[Keys.BACKUP_WHEN_CHARGING] ?: false }

    /** Comma-separated MediaStore bucket names to back up; empty = all folders. */
    val backupFolders: Flow<String> = context.settings.data.map { it[Keys.BACKUP_FOLDERS] ?: "" }

    suspend fun setBackupFolders(folders: Set<String>) {
        context.settings.edit { it[Keys.BACKUP_FOLDERS] = folders.joinToString(",") }
    }

    val dedupeEnabled: Flow<Boolean> = context.settings.data.map { it[Keys.DEDUPE] ?: true }

    suspend fun setDedupeEnabled(enabled: Boolean) {
        context.settings.edit { it[Keys.DEDUPE] = enabled }
    }

    suspend fun setAutoBackup(enabled: Boolean) {
        context.settings.edit { it[Keys.AUTO_BACKUP] = enabled }
    }

    suspend fun setWifiOnly(wifi: Boolean) {
        context.settings.edit { it[Keys.BACKUP_ON_WIFI] = wifi }
    }

    suspend fun setChargingOnly(charging: Boolean) {
        context.settings.edit { it[Keys.BACKUP_WHEN_CHARGING] = charging }
    }

    val onboarded: Flow<Boolean> = context.settings.data.map { it[Keys.ONBOARDED] ?: false }

    suspend fun setOnboarded() {
        context.settings.edit { it[Keys.ONBOARDED] = true }
    }
}
