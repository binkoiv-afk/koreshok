package app.koreshok.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BookEntity::class, FolderEntity::class, AnnotationEntity::class], version = 2)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun folders(): FolderDao
    abstract fun annotations(): AnnotationDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "library.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `annotations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookUri` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `chapter` INTEGER NOT NULL,
                        `block` INTEGER NOT NULL,
                        `start` INTEGER NOT NULL,
                        `end` INTEGER NOT NULL,
                        `color` INTEGER NOT NULL,
                        `note` TEXT,
                        `text` TEXT NOT NULL,
                        `chapterTitle` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_annotations_bookUri` ON `annotations` (`bookUri`)")
            }
        }
    }
}
