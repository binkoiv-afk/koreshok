package app.koreshok.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Text ready to play: synthesized samples for neural voices, the text itself for system ones. */
sealed interface Prepared {
    data class Text(val text: String) : Prepared
    class Samples(val samples: FloatArray, val sampleRate: Int) : Prepared
}

/** One way of turning text into sound. [prepare] may run ahead of [play] for the next sentence. */
interface SpeechEngine {
    suspend fun prepare(text: String, rate: Float): Prepared
    /** Returns once the text has been spoken; cancelling stops the sound. */
    suspend fun play(prepared: Prepared, rate: Float, pitch: Float)
    fun release()
}

val SpeechAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/** A voice of the phone's own speech engines (Google, Samsung, RHVoice and so on). */
data class SystemVoice(val id: String, val engine: String, val engineLabel: String, val voice: String, val label: String, val needsNetwork: Boolean)

class SystemEngine private constructor(private val tts: TextToSpeech) : SpeechEngine {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val counter = AtomicInteger()

    init {
        tts.setAudioAttributes(SpeechAttributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) {
                waiting.remove(utteranceId)?.complete(Unit)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                waiting.remove(utteranceId)?.complete(Unit)
            }
            override fun onError(utteranceId: String, errorCode: Int) {
                waiting.remove(utteranceId)?.complete(Unit)
            }
            override fun onStop(utteranceId: String, interrupted: Boolean) {
                waiting.remove(utteranceId)?.complete(Unit)
            }
        })
    }

    fun useVoice(name: String?, language: String?) {
        val voice = name?.let { wanted -> runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == wanted } }
        if (voice != null) {
            tts.voice = voice
        } else {
            tts.language = Locale.forLanguageTag(language?.takeIf { it.isNotBlank() } ?: "ru")
        }
    }

    override suspend fun prepare(text: String, rate: Float): Prepared = Prepared.Text(text)

    override suspend fun play(prepared: Prepared, rate: Float, pitch: Float) {
        val text = (prepared as Prepared.Text).text
        val id = "u${counter.incrementAndGet()}"
        val done = CompletableDeferred<Unit>()
        waiting[id] = done
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        try {
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) return
            done.await()
        } finally {
            if (!done.isCompleted) {
                waiting.remove(id)
                tts.stop()
            }
        }
    }

    override fun release() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    companion object {
        /** Starts the engine [packageName] (or the phone's default); null if it does not start. */
        suspend fun create(context: Context, packageName: String?): SystemEngine? {
            var engine: TextToSpeech? = null
            val ok = suspendCancellableCoroutine { continuation ->
                val listener = TextToSpeech.OnInitListener { status -> if (continuation.isActive) continuation.resume(status == TextToSpeech.SUCCESS) }
                engine = if (packageName.isNullOrBlank()) {
                    TextToSpeech(context.applicationContext, listener)
                } else {
                    TextToSpeech(context.applicationContext, listener, packageName)
                }
            }
            val tts = engine ?: return null
            if (!ok) {
                tts.shutdown()
                return null
            }
            return SystemEngine(tts)
        }

        /** Every installed engine's voices for [language], best quality first. */
        suspend fun voices(context: Context, language: String): List<SystemVoice> {
            val probe = create(context, null) ?: return emptyList()
            val engines = probe.tts.engines.toList()
            probe.release()
            val result = mutableListOf<SystemVoice>()
            for (info in engines) {
                val engine = create(context, info.name) ?: continue
                val voices: Set<Voice> = runCatching { engine.tts.voices }.getOrNull().orEmpty()
                voices
                    .filter { it.locale.language == language && "notInstalled" !in it.features.orEmpty() }
                    .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
                    .forEachIndexed { index, voice ->
                        result += SystemVoice(
                            id = "system:${info.name}:${voice.name}",
                            engine = info.name,
                            engineLabel = info.label,
                            voice = voice.name,
                            label = "${info.label} · голос ${index + 1}",
                            needsNetwork = voice.isNetworkConnectionRequired,
                        )
                    }
                engine.release()
            }
            return result
        }
    }
}

/** A downloaded Piper voice, synthesized on the phone by sherpa-onnx and played through AudioTrack. */
class NeuralEngine private constructor(private val tts: OfflineTts) : SpeechEngine {
    private var track: AudioTrack? = null

    override suspend fun prepare(text: String, rate: Float): Prepared = withContext(Dispatchers.Default) {
        val audio = tts.generate(text, 0, rate)
        Prepared.Samples(audio.samples, audio.sampleRate)
    }

    override suspend fun play(prepared: Prepared, rate: Float, pitch: Float) {
        val audio = prepared as Prepared.Samples
        val track = trackFor(audio.sampleRate)
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        try {
            withContext(Dispatchers.IO) {
                var offset = 0
                while (offset < audio.samples.size) {
                    ensureActive()
                    val count = minOf(CHUNK, audio.samples.size - offset)
                    val written = track.write(audio.samples, offset, count, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching {
                track.pause()
                track.flush()
            }
            throw e
        }
    }

    private fun trackFor(sampleRate: Int): AudioTrack {
        track?.takeIf { it.sampleRate == sampleRate }?.let { return it }
        track?.release()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(SpeechAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track = it }
    }

    override fun release() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        runCatching { tts.release() }
    }

    companion object {
        private const val CHUNK = 4096

        suspend fun create(files: VoiceFiles): NeuralEngine = withContext(Dispatchers.Default) {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = files.model.absolutePath,
                        tokens = files.tokens.absolutePath,
                        dataDir = files.dataDir.absolutePath,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )
            NeuralEngine(OfflineTts(null, config))
        }
    }
}
