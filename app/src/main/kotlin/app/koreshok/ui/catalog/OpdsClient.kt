package app.koreshok.ui.catalog

import app.koreshok.core.format.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** Minimal HTTP for catalogs. Follows redirects across http/https, which HttpURLConnection will not. */
object OpdsClient {
    private const val USER_AGENT = "Koreshok/1.0 (Android; OPDS)"

    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                connection.disconnect()
                current = URL(URL(current), location).toString()
            } else {
                if (code !in 200..299) {
                    connection.disconnect()
                    throw IOException("Сервер ответил $code")
                }
                return connection
            }
        }
        throw IOException("Слишком много перенаправлений")
    }

    suspend fun fetch(url: String): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            val bytes = connection.inputStream.use { it.readAtMost(16 * 1024 * 1024) } ?: throw IOException("Ответ слишком большой")
            bytes to connection.url.toString()
        } finally {
            connection.disconnect()
        }
    }

    /** Saves a book into [dir], named after the server's file name or [fallbackName]. */
    suspend fun download(url: String, dir: File, fallbackName: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val connection = open(url)
            try {
                val name = fileName(connection.getHeaderField("Content-Disposition")) ?: fallbackName
                val target = uniqueFile(dir, sanitize(name))
                val total = connection.contentLengthLong.takeIf { it > 0 }
                val partial = File(dir, target.name + ".part")
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total != null) onProgress(done.toFloat() / total)
                        }
                    }
                }
                if (!partial.renameTo(target)) throw IOException("Не удалось сохранить файл")
                target
            } finally {
                connection.disconnect()
            }
        }

    private fun fileName(disposition: String?): String? {
        if (disposition == null) return null
        Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return URLDecoder.decode(it.groupValues[1].trim('"', ' '), "UTF-8")
        }
        return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)?.trim()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(150).ifBlank { "book" }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var n = 2
        val stem = name.substringBefore('.')
        val ext = name.substringAfter('.', "")
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext")
            n++
        }
        return candidate
    }
}
