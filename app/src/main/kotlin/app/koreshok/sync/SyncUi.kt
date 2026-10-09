package app.koreshok.sync

import android.text.format.DateUtils
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The card on the Ещё tab: whether sync is on, when it last ran, and a button to run it. */
@Composable
fun SyncCard(prefs: SyncPrefs?, status: SyncStatus, onSyncNow: () -> Unit, onSetup: () -> Unit, onDisconnect: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudSync, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Синхронизация", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(4.dp))
            val account = prefs?.account
            if (account == null) {
                Text(
                    "Место чтения, закладки и цитаты будут одинаковыми на телефоне и планшете. Нужен Яндекс Диск или любой WebDAV.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onSetup) { Text("Подключить диск") }
            } else {
                Text(
                    "${account.login} · ${serverName(account.url)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    statusLine(status, prefs.lastSync),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = onSyncNow, enabled = status !is SyncStatus.Running) {
                        if (status is SyncStatus.Running) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Сейчас")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDisconnect) { Text("Отключить") }
                }
            }
        }
    }
}

private fun serverName(url: String) = when {
    url.startsWith(SyncAccount.YANDEX.trimEnd('/')) -> "Яндекс Диск"
    else -> url.removePrefix("https://").removePrefix("http://").substringBefore('/')
}

private fun statusLine(status: SyncStatus, last: Long?): String = when (status) {
    SyncStatus.Running -> "Синхронизирую…"
    is SyncStatus.Failed -> "Не вышло: ${status.message}"
    is SyncStatus.Done -> if (status.changed > 0) "Готово, обновлено: ${status.changed}" else "Всё совпадает"
    SyncStatus.Idle -> last?.let { "Последний раз ${DateUtils.getRelativeTimeSpanString(it)}".lowercase().replaceFirstChar { c -> c.uppercase() } }
        ?: "Ещё не синхронизировалось"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSetupSheet(onConnect: suspend (SyncAccount) -> String?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SyncSetup(onConnect, onDone = onDismiss)
    }
}

@Composable
fun SyncSetup(onConnect: suspend (SyncAccount) -> String?, onDone: () -> Unit) {
    var yandex by rememberSaveable { mutableStateOf(true) }
    var url by rememberSaveable { mutableStateOf("") }
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Подключить диск", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = yandex, onClick = { yandex = true }, label = { Text("Яндекс Диск") }, shape = CircleShape)
            FilterChip(selected = !yandex, onClick = { yandex = false }, label = { Text("Другой WebDAV") }, shape = CircleShape)
        }
        if (yandex) {
            Text(
                "Нужен пароль приложения, а не обычный: id.yandex.ru → Безопасность → Пароли приложений → «Файлы». Корешок положит в корень диска один маленький файл koreshok-sync.json.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Адрес папки WebDAV") },
                placeholder = { Text("https://cloud.example.ru/remote.php/dav/files/имя/") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = login,
            onValueChange = { login = it },
            label = { Text("Логин") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (yandex) "Пароль приложения" else "Пароль") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    val account = SyncAccount(if (yandex) SyncAccount.YANDEX else url.trim(), login.trim(), password)
                    val problem = onConnect(account)
                    busy = false
                    if (problem == null) onDone() else error = problem
                }
            },
            enabled = !busy && login.isNotBlank() && password.isNotBlank() && (yandex || url.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (busy) "Проверяю…" else "Подключить")
        }
    }
}
