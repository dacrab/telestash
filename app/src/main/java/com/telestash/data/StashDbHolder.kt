package com.telestash.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration

/**
 * Every migration from a schema that actually shipped (1 = v0.2.0, 2 = v0.3.0).
 * Room resolves the path at open time, so these entries must stay; dropping one
 * makes upgrades from that version crash instead of migrating.
 */
val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(
        MIGRATION_1_9,
        MIGRATION_2_9,
    )

object StashDbHolder {
    @Volatile private var database: StashDb? = null

    /**
     * Schemas that only ever existed in development builds. Upgrading from one
     * of these recreates the local cache instead of crashing; shipped schemas
     * (1, 2) are excluded so they must migrate, never wipe.
     */
    @Suppress("MagicNumber")
    private val DEV_ONLY_SCHEMAS = intArrayOf(3, 4, 5, 6, 7, 8)

    /** Process-wide singleton database, built on first use. */
    fun db(context: Context): StashDb =
        database ?: synchronized(this) {
            database ?: Room
                .databaseBuilder(
                    context.applicationContext,
                    StashDb::class.java,
                    "stash.db",
                ).addMigrations(*ALL_MIGRATIONS)
                .fallbackToDestructiveMigrationFrom(false, *DEV_ONLY_SCHEMAS)
                .build()
                .also { database = it }
        }
}
