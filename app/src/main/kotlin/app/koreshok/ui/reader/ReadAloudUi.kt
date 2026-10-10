package app.koreshok.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.koreshok.audio.NeuralVoice
import app.koreshok.audio.NeuralVoices
import app.koreshok.audio.ReadAloud
import app.koreshok.audio.ReadAloudState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** The sentence being read, tinted so the eye can follow the voice. */
fun speakingColor(theme: ReaderTheme): Color = theme.accent.copy(alpha = 0.2f)

private val Speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Player controls over the page while the book is read aloud. */
@Composable
fun ReadAloudBar(
    state: ReadAloudState,
    prefs: ReaderPrefs,
    theme: ReaderTheme,
    onToggle: () -> Unit,
    onSkip: (Boolean) -> Unit,
    onSpeed: (Float) -> Unit,
    onSleep: (Int?) -> Unit,
    onSleepChapter: () -> Unit,
    onVoices: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = theme.background,
        contentColor = theme.text,
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onSkip(false) }) { Icon(Icons.Default.SkipPrevious, "Предыдущая фраза") }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = theme.accent, strokeWidth = 2.5.dp)
                    } else {
                        IconButton(onClick = onToggle) {
                            Icon(
                                if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (state.playing) "Пауза" else "Читать",
                                tint = theme.accent,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }
                IconButton(onClick = { onSkip(true) }) { Icon(Icons.Default.SkipNext, "Следующая фраза") }
                TextButton(onClick = {
                    val next = Speeds.firstOrNull { it > prefs.speechRate + 0.01f } ?: Speeds.first()
                    onSpeed(next)
                }) {
                    Text("${"%.2f".format(Locale.US, prefs.speechRate).trimEnd('0').trimEnd('.')}×", color = theme.text, fontWeight = FontWeight.Medium)
                }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.Bedtime, "Таймер сна", tint = if (state.sleepAt != null || state.sleepAtChapterEnd) theme.accent else theme.text)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(15, 30, 45, 60).forEach { minutes ->
                            DropdownMenuItem(text = { Text("Через $minutes мин") }, onClick = { onSleep(minutes); menu = false })
                        }
                        DropdownMenuItem(text = { Text("В конце главы") }, onClick = { onSleepChapter(); menu = false })
                        if (state.sleepAt != null || state.sleepAtChapterEnd) {
                            DropdownMenuItem(text = { Text("Выключить таймер") }, onClick = { onSleep(null); menu = false })
                        }
                    }
                }
                IconButton(onClick = onVoices) { Icon(Icons.Default.RecordVoiceOver, "Голос") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onStop) { Icon(Icons.Default.Close, "Закончить чтение вслух") }
            }
            val sleep = when {
                state.sleepAtChapterEnd -> "Остановлюсь в конце главы"
                state.sleepAt != null -> "Остановлюсь в " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(state.sleepAt))
                else -> null
            }
            sleep?.let { Text(it, color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, bottom = 4.dp)) }
        }
    }
}

/** Choosing the voice, speed and pitch; neural voices download right here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(
    readAloud: ReadAloud,
    prefs: ReaderPrefs,
    language: String?,
    update: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    val installed by readAloud.voices.installed.collectAsStateWithLifecycle()
    val progress by readAloud.voices.progress.collectAsStateWithLifecycle()
    val downloadError by readAloud.voices.error.collectAsStateWithLifecycle()
    val systemVoices by readAloud.systemVoices.collectAsStateWithLifecycle()
    val state by readAloud.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { readAloud.loadSystemVoices(language) }

    fun choose(id: String) {
        update { it.copy(voice = id) }
        readAloud.restart()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Голос", style = MaterialTheme.typography.titleLarge)
            (downloadError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            SectionTitle("Живые голоса · без интернета")
            Text(
                "Нейросетевые голоса Piper. Скачиваются один раз и работают офлайн.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NeuralVoices.forEach { voice ->
                NeuralVoiceRow(
                    voice = voice,
                    selected = prefs.voice == voice.id,
                    installed = voice.key in installed,
                    progress = progress[voice.key],
                    onChoose = { choose(voice.id) },
                    onDownload = { readAloud.voices.download(voice) },
                    onCancel = { readAloud.voices.cancel(voice) },
                    onDelete = {
                        if (prefs.voice == voice.id) update { it.copy(voice = "") }
                        readAloud.voices.delete(voice)
                    },
                    onPreview = { readAloud.preview(voice.id) },
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle("Голоса телефона")
            VoiceRow(
                title = "Как в настройках телефона",
                subtitle = "голос по умолчанию",
                selected = prefs.voice.isBlank() || prefs.voice == "system",
                onChoose = { choose("") },
                onPreview = { readAloud.preview("") },
            )
            val list = systemVoices
            if (list == null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Ищу голоса на телефоне…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                list.forEach { voice ->
                    VoiceRow(
                        title = voice.label,
                        subtitle = if (voice.needsNetwork) "нужен интернет" else "без интернета",
                        selected = prefs.voice == voice.id,
                        onChoose = { choose(voice.id) },
                        onPreview = { readAloud.preview(voice.id) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle("Скорость · ${"%.2f".format(Locale.US, prefs.speechRate).trimEnd('0').trimEnd('.')}×")
            Slider(
                value = prefs.speechRate,
                onValueChange = { v -> update { it.copy(speechRate = (v * 20).roundToInt() / 20f) } },
                onValueChangeFinished = { readAloud.restart() },
                valueRange = 0.5f..2.5f,
            )
            if (!prefs.voice.startsWith("piper:")) {
                SectionTitle("Высота голоса · ${"%.1f".format(Locale.US, prefs.speechPitch)}")
                Slider(
                    value = prefs.speechPitch,
                    onValueChange = { v -> update { it.copy(speechPitch = (v * 10).roundToInt() / 10f) } },
                    onValueChangeFinished = { readAloud.restart() },
                    valueRange = 0.5f..2f,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun NeuralVoiceRow(
    voice: NeuralVoice,
    selected: Boolean,
    installed: Boolean,
    progress: Float?,
    onChoose: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = installed, onClick = onChoose)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onChoose, enabled = installed)
        Column(Modifier.weight(1f)) {
            Text(voice.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                (if (voice.male) "мужской" else "женский") + when {
                    progress != null -> " · скачиваю ${(progress * 100).roundToInt()}%"
                    installed -> " · скачан"
                    else -> " · ${voice.sizeMb} МБ"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            progress != null -> {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Отменить") }
            }
            installed -> {
                IconButton(onClick = onPreview) { Icon(Icons.Default.VolumeUp, "Послушать") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить голос") }
            }
            else -> OutlinedButton(onClick = onDownload) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Скачать")
            }
        }
    }
}

@Composable
private fun VoiceRow(title: String, subtitle: String, selected: Boolean, onChoose: () -> Unit, onPreview: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onChoose).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onChoose)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onPreview) { Icon(Icons.Default.VolumeUp, "Послушать") }
    }
}
