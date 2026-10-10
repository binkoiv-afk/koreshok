package app.koreshok.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.koreshok.core.document.DocxDocumentParser
import app.koreshok.core.document.MobiDocumentParser
import androidx.documentfile.provider.DocumentFile
import app.koreshok.core.format.EpubMetadataParser
import app.koreshok.core.format.Fb2MetadataParser
import app.koreshok.core.model.BookFormat
import app.koreshok.core.model.BookMetadata
import app.koreshok.ui.pages.PageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class ScanProgress(val folder: String, val found: Int, val processed: Int)

class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
) {
    val books: Flow<List<BookEntity>> = db.books().observeAll()
    val folders: Flow<List<FolderEntity>> = db.folders().observeAll()

    private val _scan = MutableStateFlow<ScanProgress?>(null)
    val scan: StateFlow<ScanProgress?> = _scan.asStateFlow()

    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    suspend fun addFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = DocumentFile.fromTreeUri(context, treeUri)?.name ?: treeUri.lastPathSegment.orEmpty()
        db.folders().insert(FolderEntity(treeUri.toString(), name, System.currentTimeMillis()))
        rescan(treeUri.toString())
    }

    suspend fun removeFolder(folderUri: String) {
        db.books().deleteFolder(folderUri)
        db.folders().delete(folderUri)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(folderUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    suspend fun book(uri: String): BookEntity? = db.books().get(uri)

    suspend fun saveProgress(uri: String, position: String, progress: Float) =
        db.books().saveProgress(uri, position, progress, System.currentTimeMillis())

    /** Reads a whole book file. Books are opened from the folder in place, never copied. */
    suspend fun readBytes(uri: String): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
            ?: throw java.io.FileNotFoundException(uri)
    }

    /** Streams a book file, for uploading without loading it whole. */
    fun openInput(uri: String): java.io.InputStream =
        context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw java.io.FileNotFoundException(uri)

    fun annotations(bookUri: String): Flow<List<AnnotationEntity>> = db.annotations().observeForBook(bookUri)

    suspend fun saveAnnotation(annotation: AnnotationEntity) {
        db.annotations().upsert(annotation)
    }

    suspend fun deleteAnnotation(id: Long) = db.annotations().delete(id)

    /** Folder for books downloaded from catalogs; visible on a computer under Android/data. */
    val downloadsDir: File
        get() = (context.getExternalFilesDir("books") ?: File(context.filesDir, "books")).apply { mkdirs() }

    /** Adds a downloaded file to the library and returns its entry. */
    suspend fun importDownloaded(file: File): BookEntity? = withContext(Dispatchers.IO) {
        val format = BookFormat.fromFileName(file.name) ?: return@withContext null
        val found = FoundFile(Uri.fromFile(file), file.name, file.length(), file.lastModified(), format)
        runCatching { index(DOWNLOADS, found) }
        db.books().get(found.uri.toString())
    }

    suspend fun rescanAll() {
        for (folder in db.folders().all()) rescan(folder.uri)
    }

    /**
     * Adds new and changed books in a folder and forgets files that are gone. Unchanged files are
     * skipped, and the progress banner only shows when there is actually something to read.
     */
    suspend fun rescan(folderUri: String) = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(folderUri)
        val folderName = DocumentFile.fromTreeUri(context, treeUri)?.name.orEmpty()
        try {
            val files = mutableListOf<FoundFile>()
            collect(treeUri, DocumentsContract.getTreeDocumentId(treeUri), files)

            val known = db.books().stamps(folderUri).associateBy { it.uri }
            val changed = files.filter { file ->
                val stamp = known[file.uri.toString()]
                stamp == null || stamp.sizeBytes != file.size || stamp.modifiedAt != file.modified
            }
            if (changed.isNotEmpty()) _scan.value = ScanProgress(folderName, changed.size, 0)
            changed.forEachIndexed { index, file ->
                runCatching { index(folderUri, file) }
                _scan.value = ScanProgress(folderName, changed.size, index + 1)
            }
            val gone = known.keys - files.map { it.uri.toString() }.toSet()
            if (gone.isNotEmpty()) db.books().delete(gone.toList())
        } finally {
            _scan.value = null
        }
    }

    /** A book file as the folder listing describes it. */
    private data class FoundFile(val uri: Uri, val name: String, val size: Long, val modified: Long, val format: BookFormat)

    /**
     * Lists a folder tree with one query per folder. DocumentFile asks the provider again for
     * every name, size and date, which made a big library take seconds on each launch.
     */
    private fun collect(treeUri: Uri, documentId: String, out: MutableList<FoundFile>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val folders = mutableListOf<String>()
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1).orEmpty()
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders += id
                } else {
                    val format = BookFormat.fromFileName(name) ?: continue
                    out += FoundFile(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        name = name,
                        size = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                        modified = if (cursor.isNull(4)) 0 else cursor.getLong(4),
                        format = format,
                    )
                }
            }
        }
        folders.forEach { collect(treeUri, it, out) }
    }

    private suspend fun index(folderUri: String, file: FoundFile) {
        val format = file.format
        val uri = file.uri.toString()
        val fileName = file.name
        val previous = db.books().get(uri)
        val fallbackTitle = format.extensions
            .firstOrNull { fileName.lowercase().endsWith(".$it") }
            ?.let { fileName.dropLast(it.length + 1) }
            ?: fileName
        val meta = readMetadata(file, format, fallbackTitle).let { meta ->
            // Fixed-page books have no cover inside; their first page serves as one.
            if (meta.cover == null && format in PageSource.formats && previous?.coverPath == null) {
                meta.copy(cover = PageSource.cover(context, uri, format))
            } else {
                meta
            }
        }
        db.books().upsert(
            BookEntity(
                uri = uri,
                folderUri = folderUri,
                fileName = fileName,
                sizeBytes = file.size,
                modifiedAt = file.modified,
                format = format.name,
                title = meta.title,
                authors = meta.authors.joinToString(", ") { it.displayName },
                authorSortName = meta.authors.firstOrNull()?.sortName.orEmpty(),
                series = meta.series,
                seriesIndex = meta.seriesIndex,
                genres = meta.genres,
                language = meta.language,
                year = meta.year,
                publisher = meta.publisher,
                description = meta.description,
                coverPath = meta.cover?.let { saveCover(uri, it) } ?: previous?.coverPath,
                addedAt = previous?.addedAt ?: System.currentTimeMillis(),
                lastOpenedAt = previous?.lastOpenedAt,
                progress = previous?.progress ?: 0f,
                position = previous?.position,
            ),
        )
    }

    private fun readMetadata(file: FoundFile, format: BookFormat, fallbackTitle: String): BookMetadata {
        val parser: ((ByteArray, String) -> BookMetadata)? = when (format) {
            BookFormat.EPUB -> EpubMetadataParser::parse
            BookFormat.FB2 -> Fb2MetadataParser::parse
            BookFormat.FB2_ZIP -> Fb2MetadataParser::parseZipped
            BookFormat.MOBI -> MobiDocumentParser::metadata
            BookFormat.DOCX -> DocxDocumentParser::metadata
            // Other formats get their metadata once their rendering engines land.
            else -> null
        }
        if (parser == null || file.size > MAX_PARSE_BYTES) return BookMetadata(title = fallbackTitle)
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: return BookMetadata(title = fallbackTitle)
        return runCatching { parser(bytes, fallbackTitle) }.getOrElse { BookMetadata(title = fallbackTitle) }
    }

    private fun saveCover(uri: String, bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        val file = File(coversDir, name)
        file.writeBytes(bytes)
        return file.absolutePath
    }

    companion object {
        private const val MAX_PARSE_BYTES = 100L * 1024 * 1024

        /** Pseudo folder for catalog downloads; never rescanned, so its books stay put. */
        const val DOWNLOADS = "koreshok:downloads"
    }
}
