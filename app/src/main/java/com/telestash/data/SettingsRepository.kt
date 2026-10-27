package com.telestash.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settings: DataStore<Preferences> by preferencesDataStore(name = "settings")

private object Keys {
    val API_ID = intPreferencesKey("tg_api_id")
    val API_HASH = stringPreferencesKey("tg_api_hash")
    val AUTO_BACKUP = booleanPreferencesKey("auto_backup_enabled")
    val WIFI_ONLY = booleanPreferencesKey("backup_wifi_only")
    val CHARGING_ONLY = booleanPreferencesKey("backup_charging_only")
    val DEDUPE = booleanPreferencesKey("dedupe_enabled")
    val REMOTE_DEDUPE = booleanPreferencesKey("remote_dedupe_enabled")
    val INSTANT_UPLOAD = booleanPreferencesKey("instant_upload_enabled")
    val SYNC_NEWEST = longPreferencesKey("sync_newest_message_id")
    val SYNC_OLDEST = longPreferencesKey("sync_oldest_message_id")
    val TELEGRAM_USER_ID = longPreferencesKey("telegram_user_id")

    // v0.3.0 stored these as one comma-separated String. DataStore matches keys by
    // name and casts unchecked, so the name had to change; the old value is still
    // read as a fallback because dropping it would mean "back up every folder".
    val LEGACY_BACKUP_FOLDERS = stringPreferencesKey("backup_folders")
    val BACKUP_FOLDERS = stringSetPreferencesKey("backup_folders_v2")
}

/** User preferences and the stored Telegram app credentials. */
class SettingsRepository(
    context: Context,
) {
    private val store = context.settings

    /** Falls back to defaults if the preferences file is corrupt, so first launch still works. */
    private val data: Flow<Preferences> =
        store.data.catch { cause ->
            Log.e("SettingsRepository", "Unreadable preferences; using defaults", cause)
            emit(emptyPreferences())
        }

    /** Reads the stored api_id/api_hash once; unset values come back as 0 and "". */
    suspend fun credentialsOnce(): Pair<Int, String> =
        data.first().let { (it[Keys.API_ID] ?: 0) to (it[Keys.API_HASH] ?: "") }

    suspend fun setCredentials(
        apiId: Int,
        apiHash: String,
    ) {
        store.edit {
            it[Keys.API_ID] = apiId
            it[Keys.API_HASH] = apiHash
        }
    }

    val autoBackup: Flow<Boolean> = data.map { it[Keys.AUTO_BACKUP] ?: false }
    val wifiOnly: Flow<Boolean> = data.map { it[Keys.WIFI_ONLY] ?: true }
    val chargingOnly: Flow<Boolean> = data.map { it[Keys.CHARGING_ONLY] ?: false }

    /** Empty means "every folder", which is the default on a fresh install. */
    val backupFolders: Flow<Set<String>> =
        data.map { preferences ->
            preferences[Keys.BACKUP_FOLDERS]
                ?: preferences[Keys.LEGACY_BACKUP_FOLDERS]
                    .orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toSet()
        }

    suspend fun setBackupFolders(folders: Set<String>) {
        store.edit { preferences ->
            preferences[Keys.BACKUP_FOLDERS] = folders
            // Scrub the old key so its String type is never read again.
            preferences.remove(Keys.LEGACY_BACKUP_FOLDERS)
        }
    }

    val dedupeEnabled: Flow<Boolean> = data.map { it[Keys.DEDUPE] ?: true }

    /** Upload newly detected media immediately instead of waiting for the periodic pass. */
    val instantUpload: Flow<Boolean> = data.map { it[Keys.INSTANT_UPLOAD] ?: false }

    suspend fun setInstantUpload(enabled: Boolean) {
        store.edit { it[Keys.INSTANT_UPLOAD] = enabled }
    }

    suspend fun setDedupeEnabled(enabled: Boolean) {
        store.edit { it[Keys.DEDUPE] = enabled }
    }

    val remoteDedupeEnabled: Flow<Boolean> =
        data.map { it[Keys.REMOTE_DEDUPE] ?: false }

    suspend fun setRemoteDedupeEnabled(enabled: Boolean) {
        store.edit { it[Keys.REMOTE_DEDUPE] = enabled }
    }

    /** Newest/oldest message ids captured by the last full gallery sync. */
    suspend fun lastSyncedNewestId(): Long = data.first()[Keys.SYNC_NEWEST] ?: 0L

    suspend fun lastSyncedOldestId(): Long = data.first()[Keys.SYNC_OLDEST] ?: Long.MAX_VALUE

    suspend fun setSyncWatermark(
        newest: Long,
        oldest: Long,
    ) {
        store.edit {
            it[Keys.SYNC_NEWEST] = newest
            it[Keys.SYNC_OLDEST] = oldest
        }
    }

    suspend fun clearSyncWatermarks() {
        store.edit {
            it.remove(Keys.SYNC_NEWEST)
            it.remove(Keys.SYNC_OLDEST)
        }
    }

    suspend fun telegramUserId(): Long = data.first()[Keys.TELEGRAM_USER_ID] ?: 0L

    suspend fun setTelegramUserId(userId: Long) {
        store.edit { it[Keys.TELEGRAM_USER_ID] = userId }
    }

    /** Writes the three backup toggles in one edit, so they commit together. */
    suspend fun setBackupPreferences(
        enabled: Boolean,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
    ) {
        store.edit { preferences ->
            preferences[Keys.AUTO_BACKUP] = enabled
            preferences[Keys.WIFI_ONLY] = wifiOnly
            preferences[Keys.CHARGING_ONLY] = chargingOnly
        }
    }
}
