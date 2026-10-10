package app.koreshok.ui.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.koreshok.core.document.BookSearch
import app.koreshok.core.document.Document
import app.koreshok.core.document.Position
import app.koreshok.core.document.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Search through the open book; [query] lives with the caller so it survives reopening. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    document: Document,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (Position) -> Unit,
    onDismiss: () -> Unit,
) {
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            hits = null
            return@LaunchedEffect
        }
        delay(250)
        hits = withContext(Dispatchers.Default) { BookSearch.search(document, query, LIMIT) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Слово или фраза в книге") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            val found = hits
            Text(
                when {
                    found == null -> "Поиск по всему тексту книги, без учёта регистра и «ё»"
                    found.isEmpty() -> "Ничего не нашлось"
                    found.size >= LIMIT -> "Найдено больше $LIMIT, уточните запрос"
                    else -> "Найдено: ${found.size}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(bottom = 16.dp)) {
            itemsIndexed(hits.orEmpty()) { _, hit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(hit.position) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        hit.chapterTitle ?: "Глава ${hit.position.chapter + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildAnnotatedString {
                            append(hit.snippet.substring(0, hit.matchStart))
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
                                append(hit.snippet.substring(hit.matchStart, hit.matchStart + hit.matchLength))
                            }
                            append(hit.snippet.substring(hit.matchStart + hit.matchLength))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val LIMIT = 500

/** The word's meaning in Викисловарь, which explains Russian and foreign words alike. */
fun openDictionary(context: Context, word: String) {
    open(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://ru.wiktionary.org/wiki/" + Uri.encode(word.lowercase()))))
}

/**
 * A translation in Google Переводчик when it is installed (it opens over the book), otherwise
 * on translate.google.com.
 */
fun openTranslation(context: Context, text: String) {
    val app = Intent(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain")
        .setPackage("com.google.android.apps.translate")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    try {
        context.startActivity(app)
    } catch (e: ActivityNotFoundException) {
        val url = "https://translate.google.com/?sl=auto&tl=ru&op=translate&text=" + Uri.encode(text)
        open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
}
