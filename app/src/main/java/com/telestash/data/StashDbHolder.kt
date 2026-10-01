package com.telestash.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration

/**
 * Every migration between the oldest schema that ever shipped and the current
 * one. Room finds the path at open time, so this list must stay contiguous:
 * dropping an entry makes upgrades from that version crash.
 */
val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(
        MIGRATION_1_8,
        MIGRATION_2_8,
    )

object StashDbHolder {
    @Volatile private var database: StashDb? = null

    fun db(context: Context): StashDb =
        database ?: synchronized(this) {
            database ?: Room
                .databaseBuilder(
                    context.applicationContext,
                    StashDb::class.java,
                    "stash.db",
                ).addMigrations(*ALL_MIGRATIONS)
                .build()
                .also { database = it }
        }
}
