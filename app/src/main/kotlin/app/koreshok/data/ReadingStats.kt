package app.koreshok.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * One day of reading. [chars] counts text read forward, which gives the reading speed; pages
 * are book pages of ~1800 characters, so they mean the same in any font size or scroll mode.
 */
data class DayStats(val date: LocalDate, val seconds: Long = 0, val chars: Long = 0) {
    val pages: Int get() = (chars / CHARS_PER_PAGE).toInt()
}

const val CHARS_PER_PAGE = 1800L

data class StatsSummary(
    val days: Map<LocalDate, DayStats>,
) {
    val today: DayStats get() = days[LocalDate.now()] ?: DayStats(LocalDate.now())
    val totalSeconds: Long get() = days.values.sumOf { it.seconds }
    val totalPages: Int get() = (days.values.sumOf { it.chars } / CHARS_PER_PAGE).toInt()

    /** Days in a row with some reading, ending today or yesterday. */
    val streak: Int
        get() {
            var day = LocalDate.now()
            if ((days[day]?.seconds ?: 0) < MIN_DAY_SECONDS) day = day.minusDays(1)
            var count = 0
            while ((days[day]?.seconds ?: 0) >= MIN_DAY_SECONDS) {
                count++
                day = day.minusDays(1)
            }
            return count
        }

    fun lastWeek(): List<DayStats> = (6 downTo 0).map { back ->
        val date = LocalDate.now().minusDays(back.toLong())
        days[date] ?: DayStats(date)
    }

    /** Characters per minute over the last month, or null until there is enough to tell. */
    val charsPerMinute: Int?
        get() {
            val since = LocalDate.now().minusDays(30)
            val recent = days.values.filter { it.date > since }
            val seconds = recent.sumOf { it.seconds }
            val chars = recent.sumOf { it.chars }
            return if (seconds >= 600 && chars > 0) (chars * 60 / seconds).toInt() else null
        }

    private companion object {
        const val MIN_DAY_SECONDS = 60
    }
}

private val Context.statsStore by preferencesDataStore("stats")

class ReadingStats(private val context: Context) {

    val summary: Flow<StatsSummary> = context.statsStore.data.map { p ->
        StatsSummary(
            p.asMap().mapNotNull { (key, value) ->
                val date = key.name.removePrefix("d:").takeIf { key.name.startsWith("d:") }
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                val parts = (value as? String)?.split(',') ?: return@mapNotNull null
                date to DayStats(date, parts.getOrNull(0)?.toLongOrNull() ?: 0, parts.getOrNull(2)?.toLongOrNull() ?: 0)
            }.toMap(),
        )
    }

    suspend fun add(seconds: Long = 0, chars: Long = 0) {
        val key = stringPreferencesKey("d:${LocalDate.now()}")
        context.statsStore.edit { p ->
            val parts = p[key]?.split(',')
            val s = (parts?.getOrNull(0)?.toLongOrNull() ?: 0) + seconds
            val ch = (parts?.getOrNull(2)?.toLongOrNull() ?: 0) + chars
            p[key] = "$s,0,$ch"
        }
    }
}
