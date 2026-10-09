package app.koreshok.ui.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.koreshok.data.BookEntity
import app.koreshok.ui.catalog.CatalogScreen
import app.koreshok.ui.catalog.CatalogViewModel
import app.koreshok.ui.library.ArrangeSheet
import app.koreshok.ui.library.BookDetailsSheet
import app.koreshok.ui.library.LibraryViewModel
import app.koreshok.ui.library.ShelfActions
import app.koreshok.ui.library.ShelfContent
import app.koreshok.ui.library.UpdateState
import app.koreshok.ui.library.openBook
import app.koreshok.ui.search.SearchScreen
import app.koreshok.update.Updates

enum class HomeTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    SHELF("Полка", Icons.AutoMirrored.Outlined.LibraryBooks, Icons.AutoMirrored.Filled.LibraryBooks),
    SEARCH("Поиск", Icons.Outlined.Search, Icons.Filled.Search),
    LIBRARIES("Библиотеки", Icons.Outlined.AccountBalance, Icons.Filled.AccountBalance),
    MORE("Ещё", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
}

@Composable
fun HomeScreen(onRead: (String) -> Unit) {
    val context = LocalContext.current
    val library: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    val catalogs: CatalogViewModel = viewModel(factory = CatalogViewModel.Factory)
    val state by library.state.collectAsStateWithLifecycle()
    val update by library.update.collectAsStateWithLifecycle()
    val checkMessage by library.checkMessage.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(HomeTab.SHELF) }
    var arranging by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) library.addFolder(uri)
    }
    val open: (BookEntity) -> Unit = { openBook(context, it, onRead) }

    BackHandler(enabled = tab != HomeTab.SHELF) { tab = HomeTab.SHELF }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                HomeTab.entries.forEach { item ->
                    val selected = item == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = item },
                        icon = {
                            val showBadge = item == HomeTab.MORE && update !is UpdateState.None
                            BadgedBox(badge = { if (showBadge) Badge() }) {
                                Icon(if (selected) item.selectedIcon else item.icon, null)
                            }
                        },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                HomeTab.SHELF -> ShelfContent(
                    state = state,
                    update = update,
                    actions = ShelfActions(
                        onOpen = open,
                        onDetails = { details = it.uri },
                        onAddFolder = { pickFolder.launch(null) },
                        onStatus = library::setStatus,
                        onToggleList = { library.setList(!state.prefs.list) },
                        onArrange = { arranging = true },
                        onUpdate = { library.downloadUpdate(context) },
                    ),
                )
                HomeTab.SEARCH -> SearchScreen(
                    onRead = onRead,
                    onOpenFeed = { url, title ->
                        catalogs.closeAll()
                        catalogs.open(url, title)
                        tab = HomeTab.LIBRARIES
                    },
                )
                HomeTab.LIBRARIES -> CatalogScreen(onRead = onRead, viewModel = catalogs)
                HomeTab.MORE -> MoreContent(
                    folders = state.folders,
                    totalBooks = state.totalBooks,
                    scan = state.scan,
                    build = Updates.currentBuild,
                    update = update,
                    checkMessage = checkMessage,
                    onAddFolder = { pickFolder.launch(null) },
                    onRemoveFolder = library::removeFolder,
                    onRescan = library::rescan,
                    onCheckUpdate = { library.checkForUpdate() },
                    onUpdate = { library.downloadUpdate(context) },
                )
            }
        }
    }

    if (arranging) {
        ArrangeSheet(
            sort = state.prefs.sort,
            descending = state.prefs.descending,
            groupBy = state.prefs.groupBy,
            onSort = library::setSort,
            onGroup = library::setGroupBy,
            onDismiss = { arranging = false },
        )
    }
    details?.let { uri ->
        val book = state.groups.firstNotNullOfOrNull { group -> group.items.firstOrNull { it.uri == uri } }
            ?: state.reading.firstOrNull { it.uri == uri }
        if (book == null) {
            details = null
        } else {
            BookDetailsSheet(book, onRead = { details = null; open(book) }) { details = null }
        }
    }
}
