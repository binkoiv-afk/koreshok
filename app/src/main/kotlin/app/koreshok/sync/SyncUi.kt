package app.koreshok.sync

import android.text.format.DateUtils
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncCard(
    prefs: SyncPrefs?,
    status: SyncStatus,
    onSyncNow: () -> Unit,
    onSetup: () -> Unit,
    onDisconnect: () -> Unit,
    onCloudBooks: () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudSync, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Облако", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(4.dp))
            val account = prefs?.account
            if (account == null) {
                Text(
                    "Место чтения, закладки и цитаты одинаковые на всех устройствах, а книги можно выгрузить на диск и скачать на другом телефоне. Google Диск, Яндекс Диск или любой WebDAV.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onSetup) { Text("Подключить диск") }
            } else {
                Text(
                    "${account.login} · ${serverName(account)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    statusLine(status, prefs.lastSync),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(verticalArrangement = Arrangement.Center) {
                    FilledTonalButton(onClick = onCloudBooks) {
                        Icon(Icons.AutoMirrored.Filled.LibraryBooks, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Книги в облаке")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onSyncNow, enabled = status !is SyncStatus.Running) {
                        if (status is SyncStatus.Running) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Сверить")
                    }
                    TextButton(onClick = onDisconnect) { Text("Отключить") }
                }
            }
        }
    }
}

private fun serverName(account: SyncAccount) = when {
    account.kind == DiskKind.GOOGLE -> "Google Диск"
    account.url.startsWith(SyncAccount.YANDEX.trimEnd('/')) -> "Яндекс Диск"
    else -> account.url.removePrefix("https://").removePrefix("http://").substringBefore('/')
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
fun SyncSetupSheet(
    onConnect: suspend (SyncAccount) -> String?,
    onGoogle: suspend () -> String?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SyncSetup(onConnect, onGoogle, onDone = onDismiss)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncSetup(onConnect: suspend (SyncAccount) -> String?, onGoogle: suspend () -> String?, onDone: () -> Unit) {
    // 0: Google, 1: Яндекс, 2: other WebDAV
    var choice by rememberSaveable { mutableStateOf(0) }
    val yandex = choice == 1
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Google Диск", "Яндекс Диск", "Другой WebDAV").forEachIndexed { index, label ->
                FilterChip(selected = choice == index, onClick = { choice = index; error = null }, label = { Text(label) }, shape = CircleShape)
            }
        }
        if (choice == 0) {
            Text(
                "Войдите своим аккаунтом Google. Корешок заведёт на диске папку «Корешок» и будет видеть только её, остальные ваши файлы ему недоступны.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val problem = onGoogle()
                        busy = false
                        if (problem == null) onDone() else error = problem
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "Подключаю…" else "Войти через Google")
            }
        } else {
        if (yandex) {
            Text(
                "Нужен пароль приложения, а не обычный: id.yandex.ru → Безопасность → Пароли приложений → «Файлы». Корешок положит в корень диска маленький файл koreshok-sync.json, а книги — в папку «Корешок».",
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
}

/** The books in the cloud folder: upload the shelf there, or bring books down to this device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudShelfSheet(
    cloud: CloudShelf,
    onUpload: () -> Unit,
    onDownload: (List<CloudBook>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CloudShelfContent(cloud, onUpload, onDownload)
    }
}

@Composable
fun CloudShelfContent(cloud: CloudShelf, onUpload: () -> Unit, onDownload: (List<CloudBook>) -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Книги в облаке", style = MaterialTheme.typography.headlineSmall)
            Text(
                when {
                    cloud.loading -> "Смотрю, что лежит на диске…"
                    else -> "На диске ${cloud.books.size} · нет на этом устройстве ${cloud.missing.size}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        cloud.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
        val transfer = cloud.transfer
        if (transfer != null) {
            item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "${if (transfer.upload) "Выгружаю" else "Скачиваю"} ${transfer.done + 1} из ${transfer.total}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(transfer.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (transfer.done + transfer.progress) / transfer.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        } else {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Выгрузить полку", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (cloud.notUploaded > 0) "Ещё не в облаке: ${cloud.notUploaded}" else "Все книги уже в облаке",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = onUpload, enabled = cloud.notUploaded > 0 && !cloud.loading) { Text("Выгрузить") }
                    }
                }
            }
        }
        if (cloud.missing.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Нет на этом устройстве", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onDownload(cloud.missing) }, enabled = transfer == null) { Text("Скачать все") }
                }
            }
            items(cloud.missing.size) { index ->
                val book = cloud.missing[index]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(bookTitle(book.name), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(
                            "${bookFormat(book.name)} · ${sizeLabel(book.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.IconButton(onClick = { onDownload(listOf(book)) }, enabled = transfer == null) {
                        Icon(Icons.Default.Download, "Скачать")
                    }
                }
            }
        }
    }
}

/** "Омон Ра.fb2.zip" → "Омон Ра" and "FB2". */
private fun bookTitle(name: String) = name.removeSuffix(".zip").substringBeforeLast('.')

private fun bookFormat(name: String) = name.removeSuffix(".zip").substringAfterLast('.', "").uppercase()

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f МБ".format(bytes / 1_000_000.0)
    else -> "${(bytes / 1000).coerceAtLeast(1)} КБ"
}
