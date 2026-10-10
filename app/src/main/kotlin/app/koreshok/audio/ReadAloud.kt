package app.koreshok.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat
import app.koreshok.core.document.Document
import app.koreshok.core.document.Position
import app.koreshok.core.document.Speech
import app.koreshok.core.document.Utterance
import app.koreshok.data.LibraryRepository
import app.koreshok.ui.reader.ReaderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class ReadAloudState(
    val bookUri: String? = null,
    val title: String = "",
    val playing: Boolean = false,
    /** Preparing the voice: the first sentence takes a moment with neural voices. */
    val loading: Boolean = false,
    val current: Utterance? = null,
    /** When the sleep timer pauses reading, in wall-clock millis. */
    val sleepAt: Long? = null,
    val sleepAtChapterEnd: Boolean = false,
    val error: String? = null,
) {
    val active: Boolean get() = bookUri != null
}

/**
 * The audiobook mode: reads the open book aloud sentence by sentence, keeps the reading
 * position, and survives the screen going off through [ReadAloudService].
 */
class ReadAloud(
    private val context: Context,
    private val library: LibraryRepository,
    private val settings: ReaderSettings,
    val voices: VoiceStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var sleepJob: Job? = null
    private var engine: SpeechEngine? = null
    private var engineId: String? = null
    private var document: Document? = null
    private var language: String? = null
    private var focus: AudioFocusRequest? = null

    private val _state = MutableStateFlow(ReadAloudState())
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private val _systemVoices = MutableStateFlow<List<SystemVoice>?>(null)
    val systemVoices: StateFlow<List<SystemVoice>?> = _systemVoices.asStateFlow()

    fun start(uri: String, title: String, document: Document, language: String?, from: Position) {
        this.document = document
        this.language = language
        _state.value = ReadAloudState(bookUri = uri, title = title)
        ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java))
        playFrom(from)
    }

    fun toggle() = if (_state.value.playing) pause() else resume()

    fun pause() {
        job?.cancel()
        _state.update { it.copy(playing = false, loading = false) }
        abandonFocus()
    }

    fun resume() {
        if (!_state.value.active) return
        playFrom(_state.value.current?.position ?: Position.START)
    }

    /** Applies changed voice, speed or pitch at once, from the current sentence. */
    fun restart() {
        if (_state.value.playing) resume()
    }

    fun stop() {
        job?.cancel()
        sleepJob?.cancel()
        releaseEngine()
        abandonFocus()
        document = null
        _state.value = ReadAloudState()
    }

    /** One sentence forward or back. */
    fun skip(forward: Boolean) {
        val doc = document ?: return
        val current = _state.value.current ?: return
        val locale = locale()
        val target = if (forward) {
            Speech.utterances(doc, Position(current.chapter, current.block, current.end), locale).firstOrNull()
        } else {
            val from = Position(current.chapter, (current.block - 3).coerceAtLeast(0), 0)
            Speech.utterances(doc, from, locale).takeWhile { it.position < current.position }.lastOrNull()
                ?: Speech.utterances(doc, Position(current.chapter, 0, 0), locale).firstOrNull()
        } ?: return
        _state.update { it.copy(current = target) }
        if (_state.value.playing) playFrom(target.position)
    }

    /** Pauses after [minutes]; null switches the timer off. */
    fun sleepIn(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) {
            _state.update { it.copy(sleepAt = null, sleepAtChapterEnd = false) }
            return
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepAt = at, sleepAtChapterEnd = false) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            _state.update { it.copy(sleepAt = null) }
        }
    }

    fun sleepAtChapterEnd() {
        sleepJob?.cancel()
        _state.update { it.copy(sleepAt = null, sleepAtChapterEnd = true) }
    }

    /** Says a short phrase in [voiceId], so voices can be compared before choosing. */
    fun preview(voiceId: String) {
        val wasPlaying = _state.value.playing
        job?.cancel()
        job = scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val engine = try {
                engineFor(voiceId)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, playing = false, error = "Голос не запустился: ${e.message ?: "ошибка"}") }
                return@launch
            }
            _state.update { it.copy(loading = false, playing = false) }
            val prefs = settings.prefs.first()
            engine.play(engine.prepare(SAMPLE, prefs.speechRate), prefs.speechRate, prefs.speechPitch)
            if (wasPlaying) resume()
        }
    }

    fun loadSystemVoices(language: String?) {
        if (_systemVoices.value != null) return
        scope.launch {
            _systemVoices.value = runCatching { SystemEngine.voices(context, language?.take(2)?.lowercase() ?: "ru") }.getOrDefault(emptyList())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun playFrom(position: Position) {
        job?.cancel()
        val doc = document ?: return
        job = scope.launch {
            _state.update { it.copy(playing = true, loading = true, error = null) }
            val prefs = settings.prefs.first()
            val engine = try {
                engineFor(prefs.voice)
            } catch (e: Exception) {
                _state.update { it.copy(playing = false, loading = false, error = "Голос не запустился: ${e.message ?: "ошибка"}") }
                return@launch
            }
            requestFocus()
            val chapter = position.chapter
            coroutineScope {
                // The next sentence is synthesized while the current one plays, so there are no gaps.
                val queue = produce(capacity = 1) {
                    for (utterance in Speech.utterances(doc, position, locale())) {
                        send(utterance to engine.prepare(utterance.spoken, prefs.speechRate))
                    }
                }
                for ((utterance, prepared) in queue) {
                    if (_state.value.sleepAtChapterEnd && utterance.chapter != chapter) {
                        _state.update { it.copy(current = utterance, sleepAtChapterEnd = false) }
                        queue.cancel()
                        pause()
                        return@coroutineScope
                    }
                    _state.update { it.copy(current = utterance, loading = false) }
                    save(utterance)
                    engine.play(prepared, prefs.speechRate, prefs.speechPitch)
                }
                // The end of the book.
                _state.update { it.copy(playing = false, loading = false) }
                abandonFocus()
            }
        }
    }

    private suspend fun save(utterance: Utterance) {
        val uri = _state.value.bookUri ?: return
        val doc = document ?: return
        runCatching { library.saveProgress(uri, utterance.position.serialize(), doc.progressOf(utterance.position)) }
    }

    private suspend fun engineFor(voiceId: String): SpeechEngine {
        val wanted = voiceId.ifBlank { "system" }
        engine?.let { current ->
            if (engineId == wanted) {
                (current as? SystemEngine)?.useVoice(systemVoiceName(wanted), language)
                return current
            }
        }
        releaseEngine()
        val created: SpeechEngine = when {
            wanted.startsWith("piper:") -> {
                val files = voices.files(wanted.removePrefix("piper:")) ?: throw IllegalStateException("голос ещё не скачан")
                NeuralEngine.create(files)
            }
            else -> {
                val packageName = wanted.removePrefix("system").removePrefix(":").substringBefore(':').ifBlank { null }
                val system = SystemEngine.create(context, packageName) ?: throw IllegalStateException("на телефоне нет синтеза речи")
                system.useVoice(systemVoiceName(wanted), language)
                system
            }
        }
        engine = created
        engineId = wanted
        return created
    }

    private fun systemVoiceName(id: String): String? = id.split(':', limit = 3).getOrNull(2)

    private fun releaseEngine() {
        engine?.release()
        engine = null
        engineId = null
    }

    private fun locale(): Locale = Locale.forLanguageTag(language?.takeIf { it.isNotBlank() } ?: "ru")

    private fun requestFocus() {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(SpeechAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    scope.launch { pause() }
                }
            }
            .build()
        focus = request
        audio.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val request = focus ?: return
        context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
        focus = null
    }

    private companion object {
        const val SAMPLE = "Здравствуйте! Этим голосом я буду читать вам книги."
    }
}
