package app.koreshok.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Line spacing and margin presets: named steps read better than raw numbers on a slider. */
private val Spacings = listOf(1.2f to "Плотно", 1.45f to "Обычно", 1.7f to "Свободно", 2.0f to "Широко")
private val Margins = listOf(10f to "Узкие", 22f to "Обычные", 36f to "Широкие")

/**
 * Typography settings. Every row is built from weighted segments, so nothing runs off the
 * edge even with the large system font this sheet inherits.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsPanel(prefs: ReaderPrefs, update: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Оформление", style = MaterialTheme.typography.titleLarge)

        Section("Листание") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to "Страницы", true to "Прокрутка").forEachIndexed { index, (scroll, label) ->
                    SegmentedButton(
                        selected = prefs.scroll == scroll,
                        onClick = { update { it.copy(scroll = scroll) } },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        icon = {
                            Icon(
                                if (scroll) Icons.Default.SwapVert else Icons.AutoMirrored.Filled.MenuBook,
                                null,
                                Modifier.size(18.dp),
                            )
                        },
                    ) { Text(label, maxLines = 1) }
                }
            }
        }

        if (!prefs.scroll) {
            Section("Перелистывание") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PageAnimation.entries.forEachIndexed { index, animation ->
                        SegmentedButton(
                            selected = prefs.pageAnimation == animation,
                            onClick = { update { it.copy(pageAnimation = animation) } },
                            shape = SegmentedButtonDefaults.itemShape(index, PageAnimation.entries.size),
                            icon = {},
                        ) { Text(animation.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }

        Section("Тема") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    val selected = theme == prefs.theme
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(theme.background)
                            .border(
                                if (selected) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary)
                                else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                MaterialTheme.shapes.medium,
                            )
                            .clickable { update { it.copy(theme = theme) } }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Аа", color = theme.text, fontFamily = FontFamily.Serif, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text(theme.label, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (prefs.theme == ReaderTheme.CUSTOM) {
                Spacer(Modifier.height(10.dp))
                Swatches("Фон", BackgroundSwatches, prefs.customBackground) { c -> update { it.copy(customBackground = c) } }
                Spacer(Modifier.height(6.dp))
                Swatches("Текст", TextSwatches, prefs.customText) { c -> update { it.copy(customText = c) } }
            }
        }

        Section("Шрифт") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.entries.filter { it != ReaderFont.CUSTOM }.forEach { font ->
                    FilterChip(
                        selected = prefs.font == font,
                        onClick = { update { it.copy(font = font) } },
                        label = { Text(font.label, maxLines = 1, fontFamily = font.family, fontSize = 15.sp) },
                    )
                }
                if (prefs.customFont.isNotBlank()) {
                    FilterChip(
                        selected = prefs.font == ReaderFont.CUSTOM,
                        onClick = { update { it.copy(font = ReaderFont.CUSTOM) } },
                        label = {
                            Text(
                                java.io.File(prefs.customFont).nameWithoutExtension,
                                maxLines = 1,
                                fontFamily = prefs.copy(font = ReaderFont.CUSTOM).fontFamily(),
                                fontSize = 15.sp,
                            )
                        },
                    )
                }
                AddFontChip { path -> update { it.copy(font = ReaderFont.CUSTOM, customFont = path) } }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedIconButton(onClick = { update { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(12f)) } }) {
                    Text("A", fontSize = 14.sp, fontFamily = FontFamily.Serif)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${prefs.fontSize.roundToInt()}", style = MaterialTheme.typography.headlineSmall)
                    Text("размер", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedIconButton(onClick = { update { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(36f)) } }) {
                    Text("A", fontSize = 22.sp, fontFamily = FontFamily.Serif)
                }
            }
        }

        Section("Интервал") {
            Presets(Spacings, prefs.lineHeight) { value -> update { it.copy(lineHeight = value) } }
            FineSlider(prefs.lineHeight, 1.0f..2.4f, steps = 13, text = "×%.1f".format(prefs.lineHeight)) { value ->
                update { it.copy(lineHeight = value) }
            }
        }
        Section("Поля") {
            Presets(Margins, prefs.margin) { value -> update { it.copy(margin = value) } }
            FineSlider(prefs.margin, 0f..56f, steps = 13, text = "${prefs.margin.roundToInt()}") { value ->
                update { it.copy(margin = value) }
            }
        }

        Section("Яркость") {
            val system = prefs.brightness < 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BrightnessLow, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = if (system) 0.5f else prefs.brightness,
                    onValueChange = { value -> update { it.copy(brightness = value.coerceAtLeast(0.01f)) } },
                    enabled = !system,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Icon(Icons.Default.BrightnessHigh, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SwitchRow("Как в системе", "яркость телефона, без своей", system) { v ->
                update { it.copy(brightness = if (v) -1f else 0.5f) }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SwitchRow("По ширине", "ровный правый край", prefs.justify) { v -> update { it.copy(justify = v) } }
                SwitchRow("Переносы", "по слогам, как в книге", prefs.hyphenate) { v -> update { it.copy(hyphenate = v) } }
                SwitchRow("Красная строка", "отступ в начале абзаца", prefs.indent) { v -> update { it.copy(indent = v) } }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SwitchRow("Не гасить экран", "пока открыта книга", prefs.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
                SwitchRow("Кнопки громкости", "листают страницы", prefs.volumeKeys) { v -> update { it.copy(volumeKeys = v) } }
                SwitchRow("Часы и заряд", "внизу страницы", prefs.showClock) { v -> update { it.copy(showClock = v) } }
                SwitchRow("Две страницы", "в альбомной ориентации, как разворот", prefs.twoPages) { v -> update { it.copy(twoPages = v) } }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Presets(options: List<Pair<Float, String>>, current: Float, onPick: (Float) -> Unit) {
    // The nearest preset counts as selected, so values saved by older versions still show.
    val nearest = options.minByOrNull { abs(it.first - current) }?.first
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == nearest,
                onClick = { onPick(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
        }
    }
}

private val BackgroundSwatches = listOf(
    0xFFFFFFFF, 0xFFF5F5F0, 0xFFFBF3E4, 0xFFF3E9D2, 0xFFE8F0E3, 0xFFE3ECF2, 0xFFEDE4F0,
    0xFF2B2B2B, 0xFF1F2A1C, 0xFF1B2330, 0xFF2A1F1A, 0xFF000000,
)
private val TextSwatches = listOf(
    0xFF000000, 0xFF1F1B16, 0xFF3B2F22, 0xFF1F2A1C, 0xFF1B2A3A,
    0xFFE6E1D6, 0xFFCFC7BA, 0xFFB8D4B0, 0xFFF0D9A8, 0xFFFFFFFF,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(label: String, colors: List<Long>, current: Long, onPick: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            colors.forEach { value ->
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(value))
                        .border(
                            if (value == current) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary)
                            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            CircleShape,
                        )
                        .clickable { onPick(value) },
                )
            }
        }
    }
}

/** "+ Свой шрифт": picks a TTF or OTF file and keeps a copy in the app. */
@Composable
private fun AddFontChip(onAdded: (String) -> Unit) {
    // Screenshot tests have no activity to pick files with.
    if (LocalActivityResultRegistryOwner.current == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) { copyFont(context, uri) }
            if (path != null) onAdded(path)
        }
    }
    AssistChip(
        onClick = { pick.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/octet-stream")) },
        label = { Text("Свой шрифт…", fontSize = 15.sp) },
        leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
    )
}

private fun copyFont(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
        ?.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_") ?: "font.ttf"
    val dir = java.io.File(context.filesDir, "fonts").apply { mkdirs() }
    val target = java.io.File(dir, name)
    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
    // A file Android cannot read as a font is not kept.
    if (runCatching { android.graphics.Typeface.createFromFile(target) }.isFailure) {
        target.delete()
        return null
    }
    target.absolutePath
}.getOrNull()

/** Exact value between the presets, for those who want 1.6 and not 1.45 or 1.7. */
@Composable
private fun FineSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, text: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.weight(1f),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
