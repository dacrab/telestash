package com.telestash.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settings: DataStore<Preferences> by preferencesDataStore(name = "settings")

private object Keys {
    val API_ID = intPreferencesKey("tg_api_id")
    val API_HASH = stringPreferencesKey("tg_api_hash")
    val AUTO_BACKUP = booleanPreferencesKey("auto_backup_enabled")
    val WIFI_ONLY = booleanPreferencesKey("backup_wifi_only")
    val CHARGING_ONLY = booleanPreferencesKey("backup_charging_only")
    val LEGACY_BACKUP_FOLDERS = stringPreferencesKey("backup_folders")
    val BACKUP_FOLDERS = stringSetPreferencesKey("backup_folders_v2")
    val DEDUPE = booleanPreferencesKey("dedupe_enabled")
    val REMOTE_DEDUPE = booleanPreferencesKey("remote_dedupe_enabled")
    val INSTANT_UPLOAD = booleanPreferencesKey("instant_upload_enabled")
    val SYNC_NEWEST = longPreferencesKey("sync_newest_message_id")
    val SYNC_OLDEST = longPreferencesKey("sync_oldest_message_id")
    val TELEGRAM_USER_ID = longPreferencesKey("telegram_user_id")
}

class SettingsRepository(
    private val context: Context,
) {
    private val credentials: Flow<Pair<Int, String>> =
        context.settings.data.map { p ->
            (p[Keys.API_ID] ?: 0) to (p[Keys.API_HASH] ?: "")
        }

    suspend fun credentialsOnce(): Pair<Int, String> = credentials.first()

    suspend fun setCredentials(
        apiId: Int,
        apiHash: String,
    ) {
        context.settings.edit {
            it[Keys.API_ID] = apiId
            it[Keys.API_HASH] = apiHash
        }
    }

    val autoBackup: Flow<Boolean> = context.settings.data.map { it[Keys.AUTO_BACKUP] ?: false }
    val wifiOnly: Flow<Boolean> = context.settings.data.map { it[Keys.WIFI_ONLY] ?: true }
    val chargingOnly: Flow<Boolean> = context.settings.data.map { it[Keys.CHARGING_ONLY] ?: false }

    val backupFolders: Flow<Set<String>> =
        context.settings.data.map { preferences ->
            preferences[Keys.BACKUP_FOLDERS]?.toSet()
                ?: preferences[Keys.LEGACY_BACKUP_FOLDERS]
                    .orEmpty()
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
        }

    suspend fun setBackupFolders(folders: Set<String>) {
        context.settings.edit { preferences ->
            preferences[Keys.BACKUP_FOLDERS] = folders.toSet()
            preferences.remove(Keys.LEGACY_BACKUP_FOLDERS)
        }
    }

    val dedupeEnabled: Flow<Boolean> = context.settings.data.map { it[Keys.DEDUPE] ?: true }

    /** Upload newly detected media immediately instead of waiting for the periodic pass. */
    val instantUpload: Flow<Boolean> = context.settings.data.map { it[Keys.INSTANT_UPLOAD] ?: false }

    suspend fun setInstantUpload(enabled: Boolean) {
        context.settings.edit { it[Keys.INSTANT_UPLOAD] = enabled }
    }

    suspend fun setDedupeEnabled(enabled: Boolean) {
        context.settings.edit { it[Keys.DEDUPE] = enabled }
    }

    val remoteDedupeEnabled: Flow<Boolean> =
        context.settings.data.map { it[Keys.REMOTE_DEDUPE] ?: false }

    suspend fun setRemoteDedupeEnabled(enabled: Boolean) {
        context.settings.edit { it[Keys.REMOTE_DEDUPE] = enabled }
    }

    /** Newest/oldest message ids captured by the last full gallery sync. */
    suspend fun lastSyncedNewestId(): Long = context.settings.data.first()[Keys.SYNC_NEWEST] ?: 0L

    suspend fun lastSyncedOldestId(): Long = context.settings.data.first()[Keys.SYNC_OLDEST] ?: Long.MAX_VALUE

    suspend fun setSyncWatermark(
        newest: Long,
        oldest: Long,
    ) {
        context.settings.edit {
            it[Keys.SYNC_NEWEST] = newest
            it[Keys.SYNC_OLDEST] = oldest
        }
    }

    suspend fun clearSyncWatermarks() {
        context.settings.edit {
            it.remove(Keys.SYNC_NEWEST)
            it.remove(Keys.SYNC_OLDEST)
        }
    }

    suspend fun telegramUserId(): Long = context.settings.data.first()[Keys.TELEGRAM_USER_ID] ?: 0L

    suspend fun setTelegramUserId(userId: Long) {
        context.settings.edit { it[Keys.TELEGRAM_USER_ID] = userId }
    }

    suspend fun setBackupPreferences(
        enabled: Boolean,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
    ) {
        context.settings.edit { preferences ->
            preferences[Keys.AUTO_BACKUP] = enabled
            preferences[Keys.WIFI_ONLY] = wifiOnly
            preferences[Keys.CHARGING_ONLY] = chargingOnly
        }
    }
}
