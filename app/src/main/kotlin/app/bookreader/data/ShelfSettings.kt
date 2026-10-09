package app.bookreader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.bookreader.core.library.GroupBy
import app.bookreader.core.library.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ShelfPrefs(
    val sort: SortOrder = SortOrder.AUTHOR,
    val descending: Boolean = false,
    val groupBy: GroupBy = GroupBy.NONE,
)

private val Context.dataStore by preferencesDataStore("shelf")

class ShelfSettings(private val context: Context) {
    private val sortKey = stringPreferencesKey("sort")
    private val descendingKey = booleanPreferencesKey("descending")
    private val groupKey = stringPreferencesKey("group")

    val prefs: Flow<ShelfPrefs> = context.dataStore.data.map { p ->
        ShelfPrefs(
            sort = p[sortKey]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.AUTHOR,
            descending = p[descendingKey] ?: false,
            groupBy = p[groupKey]?.let { runCatching { GroupBy.valueOf(it) }.getOrNull() } ?: GroupBy.NONE,
        )
    }

    suspend fun setSort(sort: SortOrder) = context.dataStore.edit { it[sortKey] = sort.name }
    suspend fun setDescending(value: Boolean) = context.dataStore.edit { it[descendingKey] = value }
    suspend fun setGroupBy(groupBy: GroupBy) = context.dataStore.edit { it[groupKey] = groupBy.name }
}
