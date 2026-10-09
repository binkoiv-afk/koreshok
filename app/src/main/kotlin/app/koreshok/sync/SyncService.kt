package app.koreshok.sync

import android.content.Context
import app.koreshok.core.sync.SyncBook
import app.koreshok.core.sync.SyncMerge
import app.koreshok.core.sync.SyncNote
import app.koreshok.core.sync.SyncSnapshot
import app.koreshok.data.AnnotationEntity
import app.koreshok.data.AnnotationKind
import app.koreshok.data.AppDatabase
import app.koreshok.data.BookEntity
import app.koreshok.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Done(val at: Long, val changed: Int) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/**
 * Keeps where you stopped, bookmarks and highlights the same on every device. All devices
 * share one JSON file in a WebDAV folder; each sync merges it with this device's state.
 */
/** A running upload or download of books. */
data class Transfer(val upload: Boolean, val title: String, val done: Int, val total: Int, val progress: Float)

/** What lies in the cloud folder, compared with this device's shelf. */
data class CloudShelf(
    val loading: Boolean = false,
    val books: List<CloudBook> = emptyList(),
    /** Cloud books missing on this device. */
    val missing: List<CloudBook> = emptyList(),
    /** Shelf books not yet in the cloud. */
    val notUploaded: Int = 0,
    val transfer: Transfer? = null,
    val error: String? = null,
)

class SyncService(
    private val context: Context,
    private val db: AppDatabase,
    val settings: SyncSettings,
    private val library: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    /** What this device and the server agreed on last time, to tell deletions from additions. */
    private val baseFile = File(context.filesDir, "sync-base.json")

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Quietly syncs if an account is set up; called when the shelf opens. */
    fun syncInBackground() {
        scope.launch {
            val account = settings.prefs.first().account ?: return@launch
            runSync(account)
        }
    }

    fun syncNow() {
        scope.launch {
            val account = settings.prefs.first().account
            if (account == null) {
                _status.value = SyncStatus.Failed("Сначала подключите диск")
            } else {
                runSync(account)
            }
        }
    }

    /** Checks the account by doing a real sync, and keeps it only if that works. */
    suspend fun connect(account: SyncAccount): String? {
        val error = runSync(account)
        if (error == null) settings.save(account)
        return error
    }

    suspend fun disconnect() {
        settings.clear()
        withContext(Dispatchers.IO) { baseFile.delete() }
        _status.value = SyncStatus.Idle
    }

    /** Returns an error message, or null on success. */
    private suspend fun runSync(account: SyncAccount): String? = mutex.withLock {
        _status.value = SyncStatus.Running
        try {
            val changed = sync(disk(account))
            val now = System.currentTimeMillis()
            settings.setLastSync(now)
            _status.value = SyncStatus.Done(now, changed)
            null
        } catch (e: Exception) {
            val message = e.message ?: "нет связи"
            _status.value = SyncStatus.Failed(message)
            message
        }
    }

    private fun disk(account: SyncAccount): CloudDisk = when (account.kind) {
        DiskKind.WEBDAV -> WebDavDisk(account)
        DiskKind.GOOGLE -> GoogleDriveDisk { GoogleAuth.token(context) }
    }

    /** After the user agreed on Google's screen: finds out who they are and does a first sync. */
    suspend fun connectGoogle(): String? {
        val email = try {
            GoogleDriveDisk { GoogleAuth.token(context) }.email()
        } catch (e: Exception) {
            return e.message ?: "Google не ответил"
        }
        return connect(SyncAccount("", email.ifBlank { "Google" }, "", DiskKind.GOOGLE))
    }

    private suspend fun sync(dav: CloudDisk): Int {
        val books = db.books().all()
        val notes = db.annotations().all().groupBy { it.bookUri }
        val local = localSnapshot(books, notes)
        val remote = dav.readState()?.let(SyncJson::decode) ?: SyncSnapshot.EMPTY
        val base = withContext(Dispatchers.IO) {
            runCatching { SyncJson.decode(baseFile.readBytes()) }.getOrDefault(SyncSnapshot.EMPTY)
        }
        val merged = SyncMerge.merge(base, local, remote)
        val changed = apply(merged, books, notes)
        val bytes = SyncJson.encode(merged)
        if (merged != remote) dav.writeState(bytes)
        withContext(Dispatchers.IO) { baseFile.writeBytes(bytes) }
        return changed
    }

    private fun localSnapshot(books: List<BookEntity>, notes: Map<String, List<AnnotationEntity>>): SyncSnapshot {
        val synced = books.filter { it.lastOpenedAt != null || notes[it.uri].orEmpty().isNotEmpty() }
        // The same book in two folders: the copy read last speaks for both.
        val byKey = synced.groupBy { key(it) }.mapValues { (_, copies) -> copies.maxBy { it.lastOpenedAt ?: 0 } }
        return SyncSnapshot(
            byKey.mapValues { (key, book) ->
                SyncBook(
                    key = key,
                    title = book.title,
                    position = book.position,
                    progress = book.progress,
                    openedAt = book.lastOpenedAt ?: 0,
                    notes = notes[book.uri].orEmpty().map(::toSync),
                )
            },
        )
    }

    /** Writes what other devices did into this one; returns how many things changed. */
    private suspend fun apply(merged: SyncSnapshot, books: List<BookEntity>, notes: Map<String, List<AnnotationEntity>>): Int {
        var changed = 0
        for (book in books) {
            val remote = merged.books[key(book)] ?: continue
            if (remote.openedAt > (book.lastOpenedAt ?: 0) && remote.position != null) {
                db.books().saveProgress(book.uri, remote.position!!, remote.progress, remote.openedAt)
                changed++
            }
            val mine = notes[book.uri].orEmpty().associateBy { toSync(it).key }
            val wanted = remote.notes.associateBy { it.key }
            for ((noteKey, entity) in mine) {
                val target = wanted[noteKey]
                if (target == null) {
                    db.annotations().delete(entity.id)
                    changed++
                } else if (target != toSync(entity)) {
                    db.annotations().upsert(fromSync(target, book.uri, entity.id))
                    changed++
                }
            }
            for ((noteKey, note) in wanted) {
                if (noteKey !in mine) {
                    db.annotations().upsert(fromSync(note, book.uri, 0))
                    changed++
                }
            }
        }
        return changed
    }

    private fun key(book: BookEntity) = SyncMerge.bookKey(book.title, book.authors)

    private fun toSync(a: AnnotationEntity) = SyncNote(
        kind = a.kind.name, chapter = a.chapter, block = a.block, start = a.start, end = a.end,
        color = a.color, note = a.note, text = a.text, chapterTitle = a.chapterTitle, createdAt = a.createdAt,
    )

    private fun fromSync(n: SyncNote, bookUri: String, id: Long) = AnnotationEntity(
        id = id,
        bookUri = bookUri,
        kind = runCatching { AnnotationKind.valueOf(n.kind) }.getOrDefault(AnnotationKind.BOOKMARK),
        chapter = n.chapter, block = n.block, start = n.start, end = n.end, color = n.color,
        note = n.note, text = n.text, chapterTitle = n.chapterTitle, createdAt = n.createdAt,
    )

    private val _cloud = MutableStateFlow(CloudShelf())
    val cloud: StateFlow<CloudShelf> = _cloud.asStateFlow()

    /** Looks at the cloud folder and compares it with the shelf. */
    fun refreshCloud() {
        scope.launch {
            val account = settings.prefs.first().account ?: return@launch
            _cloud.value = _cloud.value.copy(loading = true, error = null)
            try {
                val books = disk(account).books()
                _cloud.value = compare(books).copy(transfer = _cloud.value.transfer)
            } catch (e: Exception) {
                _cloud.value = _cloud.value.copy(loading = false, error = e.message ?: "нет связи")
            }
        }
    }

    private suspend fun compare(cloud: List<CloudBook>): CloudShelf {
        val shelf = db.books().all()
        val cloudNames = cloud.map { it.name }.toSet()
        val localNames = shelf.map { cloudName(it) }.toSet()
        return CloudShelf(
            books = cloud,
            missing = cloud.filter { it.name !in localNames }.sortedBy { it.name.lowercase() },
            notUploaded = shelf.count { cloudName(it) !in cloudNames },
        )
    }

    /** Puts every shelf book that is not in the cloud yet there. */
    fun uploadShelf() = transfer { disk ->
        val cloudNames = disk.books().map { it.name }.toSet()
        val todo = db.books().all().filter { cloudName(it) !in cloudNames }.distinctBy { cloudName(it) }
        var failed = 0
        todo.forEachIndexed { index, book ->
            progress(true, book.title, index, todo.size, 0f)
            try {
                disk.upload(cloudName(book), book.sizeBytes, { library.openInput(book.uri) }) { progress(true, book.title, index, todo.size, it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                if (failed >= 3 && failed == index + 1) throw e
            }
        }
        if (failed > 0) "Не выгрузилось книг: $failed" else null
    }

    /** Downloads [books] from the cloud onto this device's shelf. */
    fun download(books: List<CloudBook>) = transfer { disk ->
        books.forEachIndexed { index, book ->
            progress(false, book.name.removeSuffix(".zip").substringBeforeLast('.'), index, books.size, 0f)
            val target = File(library.downloadsDir, book.name)
            val partial = File(library.downloadsDir, book.name + ".part")
            disk.download(book, partial) { progress(false, book.name.removeSuffix(".zip").substringBeforeLast('.'), index, books.size, it) }
            partial.renameTo(target)
            library.importDownloaded(target)
        }
        null
    }

    private fun transfer(work: suspend (CloudDisk) -> String?) {
        if (_cloud.value.transfer != null) return
        scope.launch {
            val account = settings.prefs.first().account ?: return@launch
            val disk = disk(account)
            val problem = try {
                work(disk)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "нет связи"
            }
            val books = runCatching { disk.books() }.getOrNull()
            _cloud.value = (books?.let { compare(it) } ?: _cloud.value).copy(transfer = null, error = problem)
        }
    }

    private fun progress(upload: Boolean, title: String, done: Int, total: Int, fraction: Float) {
        _cloud.value = _cloud.value.copy(transfer = Transfer(upload, title, done, total, fraction))
    }

    /** The name a book has in the cloud: its own file name, made safe. */
    private fun cloudName(book: BookEntity) = book.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
