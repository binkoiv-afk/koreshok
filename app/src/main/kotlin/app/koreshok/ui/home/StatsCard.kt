package app.koreshok.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.koreshok.data.CHARS_PER_PAGE
import app.koreshok.data.StatsSummary
import java.time.format.TextStyle
import java.util.Locale

/** Reading time and pages: today, the last week as bars, the streak and the totals. */
@Composable
fun StatsCard(stats: StatsSummary) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Статистика чтения", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Figure(duration(stats.today.seconds), "сегодня", Modifier.weight(1f))
                Figure("${stats.today.pages}", "стр. сегодня", Modifier.weight(1f))
                Figure("${stats.streak}", daysWord(stats.streak) + " подряд", Modifier.weight(1f))
            }
            val week = stats.lastWeek()
            val longest = week.maxOf { it.seconds }.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                week.forEach { day ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        val share = day.seconds.toFloat() / longest
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight((share * 0.78f).coerceAtLeast(0.03f))
                                .background(
                                    if (day.seconds > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    MaterialTheme.shapes.extraSmall,
                                ),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("ru")),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            val speed = stats.charsPerMinute
            Text(
                "Всего ${duration(stats.totalSeconds)} и ${stats.totalPages} стр." +
                    (speed?.let { " Скорость: ~${it * 60 / CHARS_PER_PAGE} стр. в час." } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Figure(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun duration(seconds: Long): String {
    val minutes = seconds / 60
    return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
}

private fun daysWord(n: Int): String {
    val last = n % 10
    val lastTwo = n % 100
    return when {
        lastTwo in 11..14 -> "дней"
        last == 1 -> "день"
        last in 2..4 -> "дня"
        else -> "дней"
    }
}
