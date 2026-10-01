package com.telestash

import com.telestash.data.ItemDao
import com.telestash.data.SettingsRepository
import com.telestash.td.TgClient

/**
 * Guards the local database against a Telegram account switch. The gallery, the
 * remote index and the sync watermarks all belong to one account, so a
 * different (or first-seen) user clears them rather than showing one person's
 * photos to another.
 *
 * Shared by the UI session and the background worker, which can both be the
 * first to notice an account change. The upload ledger survives a reset: those
 * files are already in the cloud and must not be uploaded again.
 */
object AccountGuard {
    /**
     * Records [TgClient]'s user as the owner of the local data, clearing data
     * that belonged to a different or unclaimed account.
     *
     * @return the account id, and whether a reset happened.
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

    /** True when the database holds data that no account has claimed yet. */
    private suspend fun hasUnclaimedData(dao: ItemDao): Boolean =
        dao.hasGalleryItems() ||
            dao.hasRemoteMedia() ||
            dao.allContentHashes().isNotEmpty() ||
            dao.allUploadedHashes().isNotEmpty()
}
