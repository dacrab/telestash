package com.telestash.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Process-wide Room holder; the Worker and the UI share one database. */
object StashDbHolder {
    @Volatile private var db: StashDb? = null

    fun db(context: Context): StashDb =
        db ?: synchronized(this) {
            db ?: Room.databaseBuilder(
                context.applicationContext,
                StashDb::class.java,
                "stash.db"
            ).build().also { db = it }
        }
}
