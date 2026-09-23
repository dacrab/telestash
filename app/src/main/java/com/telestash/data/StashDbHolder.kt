package com.telestash.data

import android.content.Context
import androidx.room.Room

/** Process-wide Room holder; the Worker and the UI share one database. */
object StashDbHolder {
    private lateinit var app: Context

    val db: StashDb by lazy {
        Room
            .databaseBuilder(
                app,
                StashDb::class.java,
                "stash.db",
            )
            // Cache-only DB: re-syncing from Telegram repopulates rows, so a rare
            // schema bump only loses deviceUri/phash and may re-offer seen media once.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    fun db(context: Context): StashDb {
        app = context.applicationContext
        return db
    }
}
