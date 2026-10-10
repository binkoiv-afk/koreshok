package app.koreshok.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A neural voice Корешок can download and run on the phone itself (Piper, via sherpa-onnx). */
data class NeuralVoice(
    val key: String,
    val name: String,
    val male: Boolean,
    val sizeMb: Int,
) {
    val id: String get() = "piper:$key"
    val url: String get() = "$BASE/vits-piper-ru_RU-$key-medium.tar.bz2"

    private companion object {
        const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    }
}

val NeuralVoices = listOf(
    NeuralVoice("irina", "Ирина", male = false, sizeMb = 64),
    NeuralVoice("denis", "Денис", male = true, sizeMb = 64),
    NeuralVoice("dmitri", "Дмитрий", male = true, sizeMb = 64),
    NeuralVoice("ruslan", "Руслан", male = true, sizeMb = 64),
)

/** Where a downloaded voice's files are. */
data class VoiceFiles(val model: File, val tokens: File, val dataDir: File)

/** Downloads, unpacks and removes neural voices; [progress] is null when nothing is downloading. */
class VoiceStore(context: Context) {
    private val root = File(context.filesDir, "voices")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val jobs = HashMap<String, Job>()

    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    /** Voice key → download share 0..1. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private fun scan(): Set<String> = NeuralVoices.filter { files(it.key) != null }.map { it.key }.toSet()

    fun files(key: String): VoiceFiles? {
        val dir = File(root, key)
        val model = dir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".onnx") } ?: return null
        val tokens = File(model.parentFile, "tokens.txt").takeIf { it.isFile } ?: return null
        val data = File(model.parentFile, "espeak-ng-data").takeIf { it.isDirectory } ?: return null
        if (!File(dir, DONE).exists()) return null
        return VoiceFiles(model, tokens, data)
    }

    fun download(voice: NeuralVoice) {
        if (jobs[voice.key]?.isActive == true) return
        _error.value = null
        jobs[voice.key] = scope.launch {
            _progress.value = _progress.value + (voice.key to 0f)
            try {
                withContext(Dispatchers.IO) { fetch(voice) }
                _installed.value = scan()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) _error.value = "Не скачался голос «${voice.name}»: ${e.message ?: "нет связи"}"
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { File(root, voice.key).deleteRecursively() }
            } finally {
                _progress.value = _progress.value - voice.key
            }
        }
    }

    fun cancel(voice: NeuralVoice) {
        jobs.remove(voice.key)?.cancel()
    }

    fun delete(voice: NeuralVoice) {
        cancel(voice)
        File(root, voice.key).deleteRecursively()
        _installed.value = scan()
    }

    private suspend fun fetch(voice: NeuralVoice) {
        val dir = File(root, voice.key)
        dir.deleteRecursively()
        dir.mkdirs()
        val archive = File(root, "${voice.key}.tar.bz2.part")
        val request = Request.Builder().url(voice.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("пустой ответ")
            val total = body.contentLength().takeIf { it > 0 } ?: (voice.sizeMb * 1_000_000L)
            body.byteStream().use { input ->
                archive.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastShown = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        if (done - lastShown > 256 * 1024) {
                            lastShown = done
                            // Downloading is 90% of the work, unpacking the rest.
                            val share = (done.toFloat() / total).coerceAtMost(1f) * 0.9f
                            withContext(Dispatchers.Main) { _progress.value = _progress.value + (voice.key to share) }
                        }
                    }
                }
            }
        }
        val base = dir.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val target = File(dir, entry.name)
                // Never write outside the voice folder, whatever the archive says.
                if (!target.canonicalPath.startsWith(base)) continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { tar.copyTo(it) }
                }
            }
        }
        archive.delete()
        File(dir, DONE).writeText("ok")
        withContext(Dispatchers.Main) { _progress.value = _progress.value + (voice.key to 1f) }
    }

    private companion object {
        const val DONE = ".installed"
    }
}
