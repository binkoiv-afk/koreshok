package app.bookreader

import android.app.Application
import app.bookreader.data.AppDatabase
import app.bookreader.data.LibraryRepository
import app.bookreader.data.ShelfSettings

class BookReaderApp : Application() {
    val database by lazy { AppDatabase.create(this) }
    val library by lazy { LibraryRepository(this, database) }
    val shelfSettings by lazy { ShelfSettings(this) }
}
