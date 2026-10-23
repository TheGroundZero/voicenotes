// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.Executors

@Database(entities = [Note::class, Transcript::class], version = 4)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile private var instance: NoteDatabase? = null

        fun get(context: Context): NoteDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, NoteDatabase::class.java, "notes.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }

        /** v4 remembers which notes have lost their recording (automatic or manual removal). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `audioDeleted` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 stores which microphone recorded each note. Existing notes are "unknown": it was not recorded. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `micSource` TEXT NOT NULL DEFAULT 'unknown'")
            }
        }
    }
}

/**
 * One thread for all database writes that must happen in order (insert before status updates,
 * and startup recovery before anything the service inserts).
 */
object Io {
    val executor = Executors.newSingleThreadExecutor()
}
