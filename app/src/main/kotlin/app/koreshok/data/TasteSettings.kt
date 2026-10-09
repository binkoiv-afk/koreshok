package app.koreshok.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** An author the reader likes; [url] is their page in Флибуста once found. */
data class FavoriteAuthor(val name: String, val url: String? = null)

/** What the taste questionnaire collected. */
data class TastePrefs(
    /** Keys of Discover.genres. */
    val genres: Set<String> = emptySet(),
    val authors: List<FavoriteAuthor> = emptyList(),
    /** Look for new books by [authors]. */
    val followNew: Boolean = true,
    /** The questionnaire was filled in or put away, so stop offering it. */
    val asked: Boolean = false,
    /** New books already shown, so only the rest count as news. */
    val seen: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = genres.isEmpty() && authors.isEmpty()
}

private val Context.tasteStore by preferencesDataStore("taste")

class TasteSettings(private val context: Context) {
    private val genresKey = stringSetPreferencesKey("genres")
    private val authorsKey = stringSetPreferencesKey("authors")
    private val followKey = booleanPreferencesKey("follow_new")
    private val askedKey = booleanPreferencesKey("asked")
    private val seenKey = stringSetPreferencesKey("seen")

    val prefs: Flow<TastePrefs> = context.tasteStore.data.map { p ->
        TastePrefs(
            genres = p[genresKey].orEmpty(),
            authors = p[authorsKey].orEmpty().map(::decode).sortedBy { it.name },
            followNew = p[followKey] ?: true,
            asked = p[askedKey] ?: false,
            seen = p[seenKey].orEmpty(),
        )
    }

    suspend fun save(genres: Set<String>, authors: List<FavoriteAuthor>, followNew: Boolean) = context.tasteStore.edit {
        it[genresKey] = genres
        it[authorsKey] = authors.map(::encode).toSet()
        it[followKey] = followNew
        it[askedKey] = true
    }

    suspend fun dismiss() = context.tasteStore.edit { it[askedKey] = true }

    /** Remembers where an author lives in Флибуста, so the search happens once. */
    suspend fun resolve(author: FavoriteAuthor, url: String) = context.tasteStore.edit { p ->
        val authors = p[authorsKey].orEmpty().map(::decode)
        p[authorsKey] = authors.map { if (it.name == author.name) it.copy(url = url) else it }.map(::encode).toSet()
    }

    suspend fun markSeen(ids: Collection<String>) = context.tasteStore.edit { p ->
        // Old ids are of no use; keep the set small.
        p[seenKey] = (p[seenKey].orEmpty() + ids).toList().takeLast(500).toSet()
    }

    private fun encode(author: FavoriteAuthor) = author.name + "\t" + author.url.orEmpty()

    private fun decode(raw: String): FavoriteAuthor {
        val name = raw.substringBefore('\t')
        return FavoriteAuthor(name, raw.substringAfter('\t', "").ifBlank { null })
    }
}
