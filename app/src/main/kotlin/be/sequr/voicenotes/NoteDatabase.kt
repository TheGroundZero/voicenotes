// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.Executors

@Database(entities = [Note::class, Transcript::class, Label::class, NoteLabel::class], version = 5)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile private var instance: NoteDatabase? = null

        fun get(context: Context): NoteDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, NoteDatabase::class.java, "notes.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { instance = it }
        }

        /** v5 adds labels. The SQL is the one Room exports in schemas/5.json. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `labels` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_labels_name` ON `labels` (`name`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_labels` (`noteId` INTEGER NOT NULL, `labelId` INTEGER NOT NULL, PRIMARY KEY(`noteId`, `labelId`), " +
                        "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`labelId`) REFERENCES `labels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_labels_labelId` ON `note_labels` (`labelId`)")
            }
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
