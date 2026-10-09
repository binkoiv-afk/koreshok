package app.koreshok.ui.catalog

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.koreshok.core.opds.OpdsDownload
import app.koreshok.core.opds.OpdsEntry
import app.koreshok.ui.components.BookCover
import app.koreshok.ui.theme.BookTitleStyle

/**
 * A book in a catalog or in search results: cover, title, authors, a short blurb and one
 * button per format. The best format gets the big button, the rest sit next to it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogBook(
    entry: OpdsEntry,
    downloads: Map<String, DownloadState>,
    onDownload: (OpdsEntry, OpdsDownload) -> Unit,
    onRead: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        BookCover(
            title = entry.title,
            author = entry.authors.firstOrNull(),
            image = entry.coverUrl,
            compact = true,
            elevation = 3.dp,
            modifier = Modifier.size(width = 72.dp, height = 108.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).animateContentSize()) {
            Text(entry.title, style = BookTitleStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (entry.authors.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    entry.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 40 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                entry.downloads.forEachIndexed { index, file ->
                    FormatButton(file, downloads[file.url], primary = index == 0, onDownload = { onDownload(entry, file) }, onRead = onRead)
                }
            }
        }
    }
}

@Composable
private fun FormatButton(
    file: OpdsDownload,
    state: DownloadState?,
    primary: Boolean,
    onDownload: () -> Unit,
    onRead: (String) -> Unit,
) {
    when (state) {
        is DownloadState.Running -> Row(
            Modifier.height(40.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(progress = { state.progress }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("${file.label} ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        is DownloadState.Done -> FilledTonalButton(onClick = { state.bookUri?.let(onRead) }, enabled = state.bookUri != null) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Читать")
        }
        is DownloadState.Failed -> TextButton(onClick = onDownload) {
            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("${file.label}: ещё раз")
        }
        null -> if (primary) {
            FilledTonalButton(onClick = onDownload) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(file.label)
            }
        } else {
            TextButton(onClick = onDownload) { Text(file.label) }
        }
    }
}
