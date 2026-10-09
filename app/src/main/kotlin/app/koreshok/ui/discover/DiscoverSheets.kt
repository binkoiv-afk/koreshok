package app.koreshok.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.koreshok.core.discover.Discover
import app.koreshok.core.opds.OpdsDownload
import app.koreshok.core.opds.OpdsEntry
import app.koreshok.data.BookEntity
import app.koreshok.ui.catalog.CatalogBook
import app.koreshok.ui.catalog.DownloadState
import app.koreshok.ui.components.BookCover
import java.io.File

/** A suggested book: cover, blurb and download buttons, plus "another one" for random picks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogBookSheet(
    entry: OpdsEntry,
    downloads: Map<String, DownloadState>,
    onDownload: (OpdsEntry, OpdsDownload) -> Unit,
    onRead: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        CatalogBook(entry.copy(title = Discover.cleanTitle(entry.title)), downloads, onDownload, onRead, Modifier.padding(bottom = 24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RandomCatalogSheet(
    pick: RandomPick,
    downloads: Map<String, DownloadState>,
    onDownload: (OpdsEntry, OpdsDownload) -> Unit,
    onRead: (String) -> Unit,
    onAnother: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Может, эту?",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            AnimatedContent(targetState = pick, label = "random") { state ->
                val book = state.book
                when {
                    book != null -> CatalogBook(book.copy(title = Discover.cleanTitle(book.title)), downloads, onDownload, onRead)
                    state.failed -> Text(
                        "Библиотека сейчас не отвечает. Попробуйте ещё раз чуть позже.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                    )
                    else -> Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            OutlinedButton(onClick = onAnother, modifier = Modifier.padding(horizontal = 20.dp), enabled = pick.book != null || pick.failed) {
                Icon(Icons.Default.Casino, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Другую")
            }
        }
    }
}

/** A book from the shelf picked at random, big and calm, with "read" and "another one". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfRandomSheet(book: BookEntity, onRead: () -> Unit, onAnother: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ShelfRandom(book, onRead, onAnother)
    }
}

@Composable
fun ShelfRandom(book: BookEntity, onRead: () -> Unit, onAnother: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("С вашей полки, наугад", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        AnimatedContent(targetState = book, label = "shelf-random") { shown ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BookCover(shown.title, shown.authors, shown.coverPath?.let(::File), Modifier.width(132.dp).aspectRatio(2f / 3f), elevation = 12.dp)
                Spacer(Modifier.height(18.dp))
                Text(shown.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (shown.authors.isNotBlank()) {
                    Text(shown.authors, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center)
                }
                shown.description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onAnother) {
                Icon(Icons.Default.Casino, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Другую")
            }
            Button(onClick = onRead) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Читать")
            }
        }
    }
}

/** Which shelf book to suggest: ones never opened first, then any not finished. */
fun randomShelfBook(books: List<BookEntity>, except: String? = null): BookEntity? {
    val unread = books.filter { it.progress <= 0f && it.uri != except }
    val pool = unread.ifEmpty { books.filter { it.progress < 0.99f && it.uri != except } }
    return pool.randomOrNull()
}
