package app.koreshok.ui.discover

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.koreshok.core.discover.Discover
import app.koreshok.data.FavoriteAuthor
import app.koreshok.data.TastePrefs

/** The questionnaire in a sheet: genres first, then authors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasteQuizSheet(
    prefs: TastePrefs,
    shelf: ShelfTaste,
    onSave: (Set<String>, List<FavoriteAuthor>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        TasteQuiz(prefs, shelf, onSave = { genres, authors, follow ->
            onSave(genres, authors, follow)
            onDismiss()
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasteQuiz(
    prefs: TastePrefs,
    shelf: ShelfTaste,
    onSave: (Set<String>, List<FavoriteAuthor>, Boolean) -> Unit,
    startStep: Int = 0,
) {
    var step by rememberSaveable { mutableStateOf(startStep) }
    // First time round, start from what the shelf suggests.
    val genres = remember { mutableStateListOf<String>().apply { addAll(if (prefs.asked) prefs.genres else prefs.genres + shelf.genres) } }
    val authors = remember { mutableStateListOf<FavoriteAuthor>().apply { addAll(prefs.authors) } }
    var follow by rememberSaveable { mutableStateOf(prefs.followNew) }
    var typed by rememberSaveable { mutableStateOf("") }

    fun addAuthor(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty() && authors.none { it.name.equals(clean, ignoreCase = true) }) authors.add(FavoriteAuthor(clean))
    }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        LinearProgressIndicator(
            progress = { (step + 1) / 2f },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        Spacer(Modifier.height(18.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (step == 0) {
                Text("Что вам нравится читать?", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Отметьте несколько жанров. По ним Корешок соберёт подборку из популярного и новинок.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Discover.genres.forEach { genre ->
                        val selected = genre.key in genres
                        FilterChip(
                            selected = selected,
                            onClick = { if (selected) genres.remove(genre.key) else genres.add(genre.key) },
                            label = { Text(genre.label) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                            } else {
                                null
                            },
                            shape = CircleShape,
                        )
                    }
                }
            } else {
                Text("Любимые авторы", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Корешок будет следить за их новыми книгами. Например, выйдет новый Пелевин, и он появится в «Обзоре».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text("Имя и фамилия автора") },
                    singleLine = true,
                    shape = CircleShape,
                    trailingIcon = {
                        IconButton(onClick = { addAuthor(typed); typed = "" }, enabled = typed.isNotBlank()) {
                            Icon(Icons.Default.Add, "Добавить")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addAuthor(typed); typed = "" }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (authors.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        authors.forEach { author ->
                            InputChip(
                                selected = true,
                                onClick = { authors.remove(author) },
                                label = { Text(author.name) },
                                trailingIcon = { Icon(Icons.Default.Close, "Убрать", Modifier.size(16.dp)) },
                                shape = CircleShape,
                            )
                        }
                    }
                }
                val suggestions = shelf.authors.filter { name -> authors.none { it.name == name } }
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("С вашей полки", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { name ->
                            FilterChip(
                                selected = false,
                                onClick = { addAuthor(name) },
                                label = { Text(name) },
                                leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(FilterChipDefaults.IconSize)) },
                                shape = CircleShape,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Следить за новинками", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Проверять при запуске, не вышло ли у этих авторов что-то новое",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = follow, onCheckedChange = { follow = it })
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step == 1) TextButton(onClick = { step = 0 }) { Text("Назад") }
            Spacer(Modifier.weight(1f))
            if (step == 0) {
                Button(onClick = { step = 1 }) { Text(if (genres.isEmpty()) "Пропустить" else "Дальше") }
            } else {
                Button(onClick = {
                    addAuthor(typed)
                    onSave(genres.toSet(), authors.toList(), follow)
                }) { Text("Готово") }
            }
        }
    }
}
