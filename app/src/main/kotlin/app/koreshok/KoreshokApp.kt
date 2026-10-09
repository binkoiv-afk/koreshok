package app.koreshok

import android.app.Application
import app.koreshok.data.AppDatabase
import app.koreshok.data.LibraryRepository
import app.koreshok.data.ShelfSettings
import app.koreshok.ui.reader.ReaderSettings

class KoreshokApp : Application() {
    val database by lazy { AppDatabase.create(this) }
    val library by lazy { LibraryRepository(this, database) }
    val shelfSettings by lazy { ShelfSettings(this) }
    val readerSettings by lazy { ReaderSettings(this) }
}
