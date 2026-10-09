package app.koreshok

import android.app.Application
import app.koreshok.data.AppDatabase
import app.koreshok.data.LibraryRepository
import app.koreshok.data.ShelfSettings
import app.koreshok.data.TasteSettings
import app.koreshok.sync.SyncService
import app.koreshok.sync.SyncSettings
import app.koreshok.ui.catalog.CatalogService
import app.koreshok.ui.discover.DiscoverService
import app.koreshok.ui.reader.ReaderSettings

class KoreshokApp : Application() {
    val database by lazy { AppDatabase.create(this) }
    val library by lazy { LibraryRepository(this, database) }
    val shelfSettings by lazy { ShelfSettings(this) }
    val readerSettings by lazy { ReaderSettings(this) }
    val catalogs by lazy { CatalogService(library) }
    val taste by lazy { TasteSettings(this) }
    val sync by lazy { SyncService(this, database, SyncSettings(this), library) }
    val discover by lazy { DiscoverService(catalogs, taste, library) }
}
