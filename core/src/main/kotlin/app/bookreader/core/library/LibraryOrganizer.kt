package app.bookreader.core.library

import java.text.Collator
import java.util.Locale

/** The fields of a library book that sorting and grouping look at. */
interface ShelfItem {
    val title: String
    val authorSortName: String
    val series: String?
    val seriesIndex: Float?
    val genres: List<String>
    val language: String?
    val year: Int?
    val format: String
    val addedAt: Long
    val lastOpenedAt: Long?
    val progress: Float
}

enum class SortOrder(val label: String) {
    TITLE("Название"),
    AUTHOR("Автор"),
    SERIES("Серия"),
    YEAR("Год"),
    ADDED("Дата добавления"),
    LAST_OPENED("Последние открытые"),
    PROGRESS("Прогресс"),
}

enum class GroupBy(val label: String) {
    NONE("Без группировки"),
    AUTHOR("Авторы"),
    SERIES("Серии"),
    GENRE("Жанры"),
    LANGUAGE("Языки"),
    FORMAT("Форматы"),
    STATUS("Статус чтения"),
}

enum class ReadingStatus(val label: String) {
    NEW("Не начатые"),
    READING("Читаю"),
    FINISHED("Прочитанные");

    companion object {
        fun of(progress: Float): ReadingStatus = when {
            progress <= 0f -> NEW
            progress >= 0.99f -> FINISHED
            else -> READING
        }
    }
}

data class ShelfGroup<T : ShelfItem>(val title: String, val items: List<T>)

object LibraryOrganizer {

    const val NO_VALUE = "Не указано"

    /** Russian collation: "ё" sorts with "е" and case is ignored. */
    private val collator: Collator = Collator.getInstance(Locale("ru")).apply { strength = Collator.SECONDARY }

    fun <T : ShelfItem> sort(items: List<T>, order: SortOrder, descending: Boolean = false): List<T> {
        val byTitle = compareBy(collator) { item: T -> item.title }
        val comparator: Comparator<T> = when (order) {
            SortOrder.TITLE -> byTitle
            SortOrder.AUTHOR -> compareBy(collator) { item: T -> item.authorSortName }
                .then(seriesComparator())
                .then(byTitle)
            SortOrder.SERIES -> seriesComparator<T>().then(byTitle)
            SortOrder.YEAR -> compareBy<T, Int?>(nullsLast()) { it.year }.then(byTitle)
            SortOrder.ADDED -> compareBy<T> { it.addedAt }.then(byTitle)
            SortOrder.LAST_OPENED -> compareBy<T> { it.lastOpenedAt ?: Long.MIN_VALUE }.then(byTitle)
            SortOrder.PROGRESS -> compareBy<T> { it.progress }.then(byTitle)
        }
        return items.sortedWith(if (descending) comparator.reversed() else comparator)
    }

    /** Books inside a series always go by their number, so "Дюна 2" follows "Дюна 1" whatever the sort. */
    private fun <T : ShelfItem> seriesComparator(): Comparator<T> =
        compareBy<T, String?>(nullsLast(collator)) { it.series }
            .thenBy(nullsLast()) { it.seriesIndex }

    /**
     * Groups books for the shelf. A book with several genres appears under each of them.
     * Inside a group items keep the order they came in, so call [sort] first.
     */
    fun <T : ShelfItem> group(
        items: List<T>,
        groupBy: GroupBy,
        genreName: (String) -> String = { it },
    ): List<ShelfGroup<T>> {
        if (groupBy == GroupBy.NONE) return listOf(ShelfGroup("", items))
        val groups = linkedMapOf<String, MutableList<T>>()
        for (item in items) {
            for (key in keys(item, groupBy, genreName)) {
                groups.getOrPut(key) { mutableListOf() } += item
            }
        }
        val ordered = if (groupBy == GroupBy.STATUS) {
            ReadingStatus.entries.mapNotNull { status -> groups[status.label]?.let { status.label to it } }
        } else {
            groups.entries
                .sortedWith(compareBy<Map.Entry<String, MutableList<T>>> { it.key == NO_VALUE }
                    .thenBy(collator) { it.key })
                .map { it.key to it.value }
        }
        return ordered.map { (title, books) ->
            ShelfGroup(title, if (groupBy == GroupBy.SERIES) books.sortedWith(seriesComparator()) else books)
        }
    }

    private fun keys(item: ShelfItem, groupBy: GroupBy, genreName: (String) -> String): List<String> =
        when (groupBy) {
            GroupBy.NONE -> listOf("")
            GroupBy.AUTHOR -> listOf(item.authorSortName.ifBlank { NO_VALUE })
            GroupBy.SERIES -> listOf(item.series?.takeIf { it.isNotBlank() } ?: NO_VALUE)
            GroupBy.GENRE -> item.genres.map(genreName).distinct().ifEmpty { listOf(NO_VALUE) }
            GroupBy.LANGUAGE -> listOf(item.language?.takeIf { it.isNotBlank() }?.lowercase() ?: NO_VALUE)
            GroupBy.FORMAT -> listOf(item.format)
            GroupBy.STATUS -> listOf(ReadingStatus.of(item.progress).label)
        }

    /** Case- and ё-insensitive search over title, author and series. */
    fun <T : ShelfItem> filter(items: List<T>, query: String): List<T> {
        val needle = normalize(query)
        if (needle.isEmpty()) return items
        return items.filter { item ->
            listOfNotNull(item.title, item.authorSortName, item.series).any { normalize(it).contains(needle) }
        }
    }

    private fun normalize(text: String): String = text.trim().lowercase().replace('ё', 'е')
}
