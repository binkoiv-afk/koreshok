package app.koreshok.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.koreshok.R
import app.koreshok.data.FolderEntity
import app.koreshok.data.ScanProgress
import app.koreshok.ui.library.UpdateBanner
import app.koreshok.ui.library.UpdateState
import app.koreshok.ui.library.booksCount
import app.koreshok.ui.theme.Brand

/** Folders, updates and the app itself. */
@Composable
fun MoreContent(
    folders: List<FolderEntity>,
    totalBooks: Int,
    scan: ScanProgress?,
    build: Int,
    update: UpdateState,
    checkMessage: String?,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRescan: () -> Unit,
    onCheckUpdate: () -> Unit,
    onUpdate: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ещё", style = MaterialTheme.typography.headlineMedium)
        UpdateBanner(update, onUpdate)

        Card {
            Text("Папки с книгами", style = MaterialTheme.typography.titleLarge)
            Text(
                if (totalBooks > 0) "На полке ${booksCount(totalBooks)}" else "Корешок ищет книги во всех вложенных папках",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            folders.forEach { folder ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(folder.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { onRemoveFolder(folder.uri) }) { Text("Убрать") }
                }
            }
            if (scan != null) {
                Text(
                    "Ищу книги: ${scan.processed} из ${scan.found}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAddFolder) {
                    Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить папку")
                }
                TextButton(onClick = onRescan, enabled = folders.isNotEmpty() && scan == null) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Обновить")
                }
            }
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The adaptive icon's foreground has a safe-zone margin, so it is drawn larger and clipped.
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Brand.Green),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(84.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Корешок", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Сборка $build · без рекламы и слежки",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    checkMessage ?: "Новые сборки приходят прямо в приложение",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCheckUpdate) {
                    Icon(Icons.Default.SystemUpdate, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Проверить")
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}
