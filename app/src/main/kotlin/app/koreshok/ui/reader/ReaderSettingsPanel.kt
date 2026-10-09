package app.koreshok.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
@OptIn(ExperimentalMaterial3Api::class)
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

        Section("Тема") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        }

        Section("Шрифт") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ReaderFont.entries.forEachIndexed { index, font ->
                    SegmentedButton(
                        selected = prefs.font == font,
                        onClick = { update { it.copy(font = font) } },
                        shape = SegmentedButtonDefaults.itemShape(index, ReaderFont.entries.size),
                        icon = {},
                    ) {
                        Text(
                            font.label,
                            maxLines = 1,
                            fontFamily = when (font) {
                                ReaderFont.SERIF -> FontFamily.Serif
                                ReaderFont.SANS -> FontFamily.SansSerif
                                ReaderFont.MONO -> FontFamily.Monospace
                            },
                        )
                    }
                }
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
        }
        Section("Поля") {
            Presets(Margins, prefs.margin) { value -> update { it.copy(margin = value) } }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SwitchRow("По ширине", "ровный правый край", prefs.justify) { v -> update { it.copy(justify = v) } }
                SwitchRow("Переносы", "по слогам, как в книге", prefs.hyphenate) { v -> update { it.copy(hyphenate = v) } }
                SwitchRow("Красная строка", "отступ в начале абзаца", prefs.indent) { v -> update { it.copy(indent = v) } }
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
