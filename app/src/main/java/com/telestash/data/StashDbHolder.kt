package com.telestash.data

import android.content.Context
import androidx.room.Room

/** Process-wide Room holder; the Worker and the UI share one database. */
object StashDbHolder {
    @Volatile private var db: StashDb? = null

    fun db(context: Context): StashDb =
        db ?: synchronized(this) {
            db ?: Room.databaseBuilder(
                context.applicationContext,
                StashDb::class.java,
                "stash.db",
            )
                // v2 added phash/topicId to a cache-only db: drop & rebuild is fine
                .fallbackToDestructiveMigration()
                .build()
                .also { db = it }
        }
}
