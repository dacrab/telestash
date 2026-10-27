package com.telestash

import com.telestash.data.ItemDao
import com.telestash.data.SettingsRepository
import com.telestash.td.TgClient

/**
 * Keeps local data tied to one Telegram account: a different or first-seen user
 * clears the gallery, remote index and sync watermarks. Shared by the UI and the
 * background worker; the upload ledger survives so files aren't re-sent.
 */
object AccountGuard {
    /**
     * Records [TgClient]'s user as owner, clearing data from another/unclaimed account.
     *
     * @return the account id and whether a reset happened.
     */
    suspend fun ensureOwnedBy(
        client: TgClient,
        settings: SettingsRepository,
        dao: ItemDao,
    ): Pair<Long, Boolean> {
        val userId = client.currentUserId()
        val previousUserId = settings.telegramUserId()
        val reset =
            (previousUserId != 0L && previousUserId != userId) ||
                (previousUserId == 0L && hasUnclaimedData(dao))
        if (reset) {
            dao.clearAllAccountData()
            settings.clearSyncWatermarks()
        }
        settings.setTelegramUserId(userId)
        return userId to reset
    }

    /** EXISTS queries only: loading the ledger's columns to test emptiness is not free. */
    private suspend fun hasUnclaimedData(dao: ItemDao): Boolean =
        dao.hasGalleryItems() || dao.hasRemoteMedia() || dao.hasUploads()
}
