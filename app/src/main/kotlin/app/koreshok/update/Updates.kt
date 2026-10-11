package app.koreshok.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.koreshok.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(val build: Int, val apkUrl: String, val notes: String)

/**
 * Updates come from the GitHub releases CI publishes for every build of main.
 * Release tags are "build-<N>" and N is the APK's versionCode.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/binkoiv-afk/koreshok/releases/latest"

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    private const val LATEST_PAGE = "https://github.com/binkoiv-afk/koreshok/releases/latest"
    private const val DOWNLOAD = "https://github.com/binkoiv-afk/koreshok/releases/download"

    /**
     * The latest release when it is newer than this app, otherwise null. Throws when neither
     * GitHub's API nor its release page answers, so "no connection" is never shown as "up to date".
     * The message says why, in words, so a blocked or flaky network can be told apart.
     */
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        var failure: Throwable? = null
        var build: Triple<Int, String, String>? = null
        for (attempt in 0 until 2) {
            if (attempt > 0) delay(2_000)
            for (source in listOf(::latestFromApi, ::latestFromPage)) {
                build = runCatching { source() }.onFailure { failure = it }.getOrNull()
                if (build != null) break
            }
            if (build != null) break
        }
        val latest = build ?: throw IOException(reason(failure))
        if (latest.first <= currentBuild) null else AppUpdate(latest.first, latest.second, latest.third)
    }

    private fun reason(e: Throwable?): String = when (e) {
        is java.net.UnknownHostException -> "телефон не находит github.com: нет интернета или сайт заблокирован"
        is java.net.SocketTimeoutException -> "github.com не ответил вовремя"
        is javax.net.ssl.SSLException -> "не удалось защищённое соединение с GitHub"
        is java.net.ConnectException -> "github.com не принимает соединение"
        null -> "GitHub ответил, но без номера сборки"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Build number, APK link and notes from the API; null when it refuses (it limits requests per network). */
    private fun latestFromApi(): Triple<Int, String, String>? {
        val connection = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (connection.responseCode != 200) throw IOException("GitHub ответил кодом ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val build = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?: return null
            return Triple(build, apk.getString("browser_download_url"), json.optString("body"))
        } finally {
            connection.disconnect()
        }
    }

    /** The release page redirects to ".../tag/build-N", which is all that is needed. */
    private fun latestFromPage(): Triple<Int, String, String>? {
        val connection = (URL(LATEST_PAGE).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "HEAD"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val code = connection.responseCode
            val location = connection.getHeaderField("Location") ?: throw IOException("GitHub ответил кодом $code")
            val tag = location.substringAfterLast("/tag/", "").takeIf { it.startsWith("build-") } ?: return null
            val build = tag.removePrefix("build-").toIntOrNull() ?: return null
            return Triple(build, "$DOWNLOAD/$tag/koreshok.apk", "")
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(context: Context, update: AppUpdate, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "koreshok-${update.build}.apk")
            val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                val total = connection.contentLengthLong.takeIf { it > 0 }
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
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
            } finally {
                connection.disconnect()
            }
            target
        }

    /**
     * Hands the APK to the system installer. Returns false when the user first has to allow
     * installs from this app; the settings screen for that is opened instead.
     */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
