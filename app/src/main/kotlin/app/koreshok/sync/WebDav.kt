package app.koreshok.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Just enough WebDAV for one file: read it, write it. */
class WebDav(private val account: SyncAccount) {

    private fun fileUrl(name: String) = URL(account.url.trimEnd('/') + "/" + name)

    private fun open(name: String, method: String): HttpURLConnection =
        (fileUrl(name).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            val token = Base64.getEncoder().encodeToString("${account.login}:${account.password}".toByteArray())
            setRequestProperty("Authorization", "Basic $token")
            setRequestProperty("User-Agent", "Koreshok/1.0 (Android)")
        }

    /** The file's bytes, or null when it is not there yet. */
    suspend fun get(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val connection = open(name, "GET")
        try {
            when (val code = connection.responseCode) {
                in 200..299 -> connection.inputStream.use { it.readBytes() }
                404 -> null
                else -> throw failure(code)
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun put(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val connection = open(name, "PUT")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code !in 200..299) throw failure(code)
        } finally {
            connection.disconnect()
        }
    }

    private fun failure(code: Int) = IOException(
        when (code) {
            401, 403 -> "Неверный логин или пароль"
            404, 409 -> "Нет такой папки на сервере"
            507 -> "На диске закончилось место"
            else -> "Сервер ответил $code"
        },
    )
}
