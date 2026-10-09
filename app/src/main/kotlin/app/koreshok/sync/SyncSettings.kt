package app.koreshok.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class DiskKind { WEBDAV, GOOGLE }

/** Where Корешок keeps reading state and books: a WebDAV folder (Яндекс Диск, Nextcloud, a NAS) or Google Диск. */
data class SyncAccount(val url: String, val login: String, val password: String, val kind: DiskKind = DiskKind.WEBDAV) {
    val isComplete: Boolean
        get() = when (kind) {
            DiskKind.GOOGLE -> login.isNotBlank()
            DiskKind.WEBDAV -> url.isNotBlank() && login.isNotBlank() && password.isNotBlank()
        }

    companion object {
        const val YANDEX = "https://webdav.yandex.ru/"
    }
}

data class SyncPrefs(val account: SyncAccount?, val lastSync: Long?)

private val Context.syncStore by preferencesDataStore("sync")

class SyncSettings(private val context: Context) {
    private val urlKey = stringPreferencesKey("url")
    private val loginKey = stringPreferencesKey("login")
    private val passwordKey = stringPreferencesKey("password")
    private val lastKey = longPreferencesKey("last_sync")
    private val kindKey = stringPreferencesKey("kind")

    val prefs: Flow<SyncPrefs> = context.syncStore.data.map { p ->
        val kind = p[kindKey]?.let { runCatching { DiskKind.valueOf(it) }.getOrNull() } ?: DiskKind.WEBDAV
        val account = SyncAccount(p[urlKey].orEmpty(), p[loginKey].orEmpty(), p[passwordKey].orEmpty(), kind)
        SyncPrefs(account.takeIf { it.isComplete }, p[lastKey])
    }

    suspend fun save(account: SyncAccount) = context.syncStore.edit {
        it[urlKey] = account.url
        it[loginKey] = account.login
        it[passwordKey] = account.password
        it[kindKey] = account.kind.name
    }

    suspend fun clear() = context.syncStore.edit { it.clear() }

    suspend fun setLastSync(time: Long) = context.syncStore.edit { it[lastKey] = time }
}
