package app.koreshok.core.discover

import app.koreshok.core.opds.OpdsEntry
import java.net.URLEncoder

/** A genre the taste questionnaire offers, tied to Флибуста's own genre ids. */
data class TasteGenre(
    val key: String,
    val label: String,
    /** Флибуста genre group (as in /opds/genres/<group>/<id>) and genre id. */
    val group: String,
    val id: Int,
    /** Words that mark a book on the shelf as this genre, to preselect it. */
    val hints: List<String>,
)

/**
 * Where suggestions come from. Флибуста has the richest feeds for this: popular books per
 * genre, the week's new books per genre and each author's books by date added.
 */
object Discover {
    const val ROOT = "http://flibusta.is"

    val genres = listOf(
        TasteGenre("modern", "Современная проза", "Проза", 28, listOf("современная", "проза")),
        TasteGenre("ru_classic", "Русская классика", "Проза", 30, listOf("русская классическая")),
        TasteGenre("world_classic", "Зарубежная классика", "Проза", 197, listOf("зарубежная классическая", "классическая проза")),
        TasteGenre("history_prose", "Исторические романы", "Проза", 27, listOf("историческая проза")),
        TasteGenre("sf", "Научная фантастика", "Фантастика", 12, listOf("научная фантастика", "космическая", "социальная фантастика")),
        TasteGenre("fantasy", "Фэнтези", "Фантастика", 11, listOf("фэнтези")),
        TasteGenre("dystopia", "Антиутопия", "Фантастика", 269, listOf("антиутопия")),
        TasteGenre("postapoc", "Постапокалипсис", "Фантастика", 131, listOf("постапокалипсис")),
        TasteGenre("popadancy", "Попаданцы и ЛитРПГ", "Фантастика", 254, listOf("попаданцы", "литрпг", "реалрпг")),
        TasteGenre("horror", "Ужасы и мистика", "Фантастика", 231, listOf("ужасы", "мистика")),
        TasteGenre("detective", "Детективы", "Детективы и триллеры", 24, listOf("детектив")),
        TasteGenre("thriller", "Триллеры", "Детективы и триллеры", 23, listOf("триллер", "боевик")),
        TasteGenre("adventure", "Приключения", "Приключения", 44, listOf("приключения")),
        TasteGenre("romance", "Любовные романы", "Любовные романы", 32, listOf("любовн")),
        TasteGenre("humor", "Юмор и сатира", "Юмор", 99, listOf("юмор", "сатира")),
        TasteGenre("poetry", "Поэзия", "Поэзия", 53, listOf("поэзия", "стихи")),
        TasteGenre("history", "История", "Наука, Образование", 61, listOf("история")),
        TasteGenre("popsci", "Научпоп", "Наука, Образование", 168, listOf("научно-популярная", "научная литература")),
        TasteGenre("psychology", "Психология", "Наука, Образование", 62, listOf("психология")),
        TasteGenre("philosophy", "Философия", "Наука, Образование", 65, listOf("философия")),
        TasteGenre("memoirs", "Биографии и мемуары", "Документальная литература", 89, listOf("биографии", "мемуары")),
        TasteGenre("business", "Бизнес и финансы", "Деловая литература", 144, listOf("деловая", "финансы", "маркетинг", "экономика")),
    )

    fun genre(key: String): TasteGenre? = genres.firstOrNull { it.key == key }

    /** Genres to tick in the questionnaire straight away, from what is already on the shelf. */
    fun guessGenres(shelfGenres: List<String>): Set<String> {
        val counts = genres.associate { genre ->
            genre.key to shelfGenres.count { name -> genre.hints.any { name.contains(it, ignoreCase = true) } }
        }
        return counts.filterValues { it > 0 }.entries.sortedByDescending { it.value }.take(5).map { it.key }.toSet()
    }

    /** The genre's most read books. */
    fun popularUrl(genre: TasteGenre): String = "$ROOT/opds/genres/${encode(genre.group)}/${genre.id}"

    /** The genre's books added this week. */
    fun newUrl(genre: TasteGenre): String = "$ROOT/opds/new/0/newgenres/${genre.id}"

    fun authorSearchUrl(term: String): String = "$ROOT/opds/search?searchType=authors&searchTerm=${encode(term)}"

    /** An author's books, newest additions first. */
    fun authorLatestUrl(authorUrl: String): String = authorUrl.trimEnd('/') + "/time"

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /**
     * Words to search an author by, best first. The shelf says "Виктор Пелевин", Флибуста files
     * him as "Пелевин Виктор Олегович", and its search wants one word, so try the surname
     * from either end.
     */
    fun authorSearchTerms(name: String): List<String> {
        val words = words(name).filter { it.length > 2 }
        return listOfNotNull(words.lastOrNull(), words.firstOrNull()).distinct()
    }

    private fun words(text: String) = text.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}]+")).filter { it.length > 1 }

    /**
     * Picks the author an author search meant: every word of the name must start a word of the
     * entry, initials included; with several matches, the one with the most books wins.
     */
    fun bestAuthor(name: String, entries: List<OpdsEntry>): OpdsEntry? {
        val wanted = words(name)
        if (wanted.isEmpty()) return null
        return entries
            .filter { !it.isBook && it.navigationUrl != null }
            .filter { entry ->
                val have = words(entry.title)
                wanted.all { want -> have.any { it.startsWith(want) || want.startsWith(it) } }
            }
            .maxByOrNull { bookCount(it.summary) }
    }

    private fun bookCount(summary: String?): Int = summary?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 0

    /**
     * Fresh books from an author's "by date added" feed: published this year or last, written by
     * the author (not an anthology they appear in), and not already on the shelf.
     */
    fun freshBooks(authorName: String, entries: List<OpdsEntry>, thisYear: Int, onShelf: (OpdsEntry) -> Boolean): List<OpdsEntry> {
        val surname = words(authorName)
        return entries
            .filter { it.isBook && (it.year ?: 0) >= thisYear - 1 }
            .filter { it.authors.size <= 2 && it.authors.any { author -> words(author).any { w -> surname.any { s -> w == s } } } }
            .filterNot(onShelf)
            .distinctBy { normalizeTitle(it.title) }
    }

    /** "Омон Ра [litres]" and "Омон Ра" are the same book. */
    fun normalizeTitle(title: String): String =
        title.replace(Regex("\\[[^]]*]|\\([^)]*\\)"), "").lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Флибуста writes "Пелевин Виктор Олегович"; a cover caption wants "Виктор Пелевин". */
    fun displayAuthor(name: String): String {
        val words = name.trim().split(Regex("\\s+"))
        return if (words.size == 3 && words.all { it.firstOrNull()?.isUpperCase() == true }) "${words[1]} ${words[0]}" else name.trim()
    }

    /** Flibusta marks variants in brackets; readers don't need to see that. */
    fun cleanTitle(title: String): String = title.replace(Regex("\\s*\\[[^]]*]"), "").trim().ifEmpty { title }
}
