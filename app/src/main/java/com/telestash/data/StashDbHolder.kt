package com.telestash.data

import android.content.Context
import androidx.room.Room

object StashDbHolder {
    @Volatile private var database: StashDb? = null

    fun db(context: Context): StashDb =
        database ?: synchronized(this) {
            database ?: Room
                .databaseBuilder(
                    context.applicationContext,
                    StashDb::class.java,
                    "stash.db",
                ).addMigrations(
                    MIGRATION_1_5,
                    MIGRATION_2_5,
                    MIGRATION_3_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                ).build()
                .also { database = it }
        }
}
