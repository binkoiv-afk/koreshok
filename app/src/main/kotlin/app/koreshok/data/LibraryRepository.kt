package app.koreshok.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.koreshok.core.format.EpubMetadataParser
import app.koreshok.core.format.Fb2MetadataParser
import app.koreshok.core.model.BookFormat
import app.koreshok.core.model.BookMetadata
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

    suspend fun rescanAll() {
        for (folder in db.folders().all()) rescan(folder.uri)
    }

    /** Adds new and changed books in a folder and forgets files that are gone. Unchanged files are skipped. */
    suspend fun rescan(folderUri: String) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return@withContext
        val folderName = root.name.orEmpty()
        _scan.value = ScanProgress(folderName, 0, 0)
        try {
            val files = mutableListOf<Pair<DocumentFile, BookFormat>>()
            collect(root, files)
            _scan.value = ScanProgress(folderName, files.size, 0)

            val known = db.books().stamps(folderUri).associateBy { it.uri }
            val seen = HashSet<String>()
            files.forEachIndexed { index, (file, format) ->
                val uri = file.uri.toString()
                seen += uri
                val stamp = known[uri]
                if (stamp == null || stamp.sizeBytes != file.length() || stamp.modifiedAt != file.lastModified()) {
                    runCatching { index(folderUri, file, format) }
                }
                _scan.value = ScanProgress(folderName, files.size, index + 1)
            }
            val gone = known.keys - seen
            if (gone.isNotEmpty()) db.books().delete(gone.toList())
        } finally {
            _scan.value = null
        }
    }

    private fun collect(dir: DocumentFile, out: MutableList<Pair<DocumentFile, BookFormat>>) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                collect(child, out)
            } else {
                val format = child.name?.let(BookFormat::fromFileName) ?: continue
                out += child to format
            }
        }
    }

    private suspend fun index(folderUri: String, file: DocumentFile, format: BookFormat) {
        val uri = file.uri.toString()
        val fileName = file.name.orEmpty()
        val fallbackTitle = format.extensions
            .firstOrNull { fileName.lowercase().endsWith(".$it") }
            ?.let { fileName.dropLast(it.length + 1) }
            ?: fileName
        val meta = readMetadata(file, format, fallbackTitle)
        val previous = db.books().get(uri)
        db.books().upsert(
            BookEntity(
                uri = uri,
                folderUri = folderUri,
                fileName = fileName,
                sizeBytes = file.length(),
                modifiedAt = file.lastModified(),
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
                coverPath = meta.cover?.let { saveCover(uri, it) },
                addedAt = previous?.addedAt ?: System.currentTimeMillis(),
                lastOpenedAt = previous?.lastOpenedAt,
                progress = previous?.progress ?: 0f,
                position = previous?.position,
            ),
        )
    }

    private fun readMetadata(file: DocumentFile, format: BookFormat, fallbackTitle: String): BookMetadata {
        val parser: ((ByteArray, String) -> BookMetadata)? = when (format) {
            BookFormat.EPUB -> EpubMetadataParser::parse
            BookFormat.FB2 -> Fb2MetadataParser::parse
            BookFormat.FB2_ZIP -> Fb2MetadataParser::parseZipped
            // Other formats get their metadata once their rendering engines land.
            else -> null
        }
        if (parser == null || file.length() > MAX_PARSE_BYTES) return BookMetadata(title = fallbackTitle)
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

    private companion object {
        const val MAX_PARSE_BYTES = 100L * 1024 * 1024
    }
}
