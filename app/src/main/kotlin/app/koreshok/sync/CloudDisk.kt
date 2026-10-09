package app.koreshok.sync

import java.io.File
import java.io.IOException
import java.io.InputStream

/** A book file kept in the cloud. */
data class CloudBook(val name: String, val size: Long, val id: String)

/**
 * A place in the cloud for Корешок: one small file with reading state, and a folder of books.
 * Яндекс Диск and other WebDAV servers, or Google Диск.
 */
interface CloudDisk {
    /** The sync file's bytes, or null when there is none yet. */
    suspend fun readState(): ByteArray?
    suspend fun writeState(bytes: ByteArray)

    suspend fun books(): List<CloudBook>
    suspend fun upload(name: String, size: Long, open: () -> InputStream, onProgress: (Float) -> Unit)
    suspend fun download(book: CloudBook, target: File, onProgress: (Float) -> Unit)

    companion object {
        const val STATE_FILE = "koreshok-sync.json"
        const val FOLDER = "Корешок"

        fun httpError(code: Int) = IOException(
            when (code) {
                401, 403 -> "Нет доступа: проверьте логин и пароль"
                404, 409 -> "Нет такой папки на сервере"
                507 -> "На диске закончилось место"
                else -> "Сервер ответил $code"
            },
        )
    }
}

/** Copies with progress; shared by both disks. */
internal fun copyWithProgress(input: InputStream, output: java.io.OutputStream, total: Long, onProgress: (Float) -> Unit) {
    val buffer = ByteArray(64 * 1024)
    var done = 0L
    var lastReported = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        output.write(buffer, 0, n)
        done += n
        if (total > 0 && done - lastReported > total / 50) {
            lastReported = done
            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
        }
    }
    onProgress(1f)
}
