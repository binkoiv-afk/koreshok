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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Explore
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.app.Activity
import androidx.activity.result.ActivityResult
import app.koreshok.sync.CloudShelfSheet
import app.koreshok.ui.reader.ReaderPrefs
import app.koreshok.ui.reader.ReaderSettingsPanel
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import app.koreshok.sync.SyncCard
import app.koreshok.sync.signInToGoogle
import kotlinx.coroutines.CompletableDeferred
import app.koreshok.sync.SyncSetupSheet
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.koreshok.KoreshokApp
import app.koreshok.data.BookEntity
import app.koreshok.ui.discover.ShelfRandomSheet
import app.koreshok.ui.discover.randomShelfBook
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
    SEARCH("Обзор", Icons.Outlined.Explore, Icons.Filled.Explore),
    LIBRARIES("Библиотеки", Icons.Outlined.AccountBalance, Icons.Filled.AccountBalance),
    MORE("Ещё", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var randomUri by rememberSaveable { mutableStateOf<String?>(null) }
    val app = context.applicationContext as KoreshokApp
    val news by app.discover.news.collectAsStateWithLifecycle()
    // Checks favourite authors for new books once per launch, so the badge can say so.
    LaunchedEffect(Unit) {
        app.discover.refresh()
        // Also runs when coming back from a book, so the other devices see where reading stopped.
        app.sync.syncInBackground()
    }
    val syncPrefs by app.sync.settings.prefs.collectAsStateWithLifecycle(initialValue = null)
    val syncStatus by app.sync.status.collectAsStateWithLifecycle()
    var syncSetup by rememberSaveable { mutableStateOf(false) }
    var cloudOpen by rememberSaveable { mutableStateOf(false) }
    var readingSettings by rememberSaveable { mutableStateOf(false) }
    val cloud by app.sync.cloud.collectAsStateWithLifecycle()
    val googleAnswer = remember { mutableStateOf<CompletableDeferred<ActivityResult>?>(null) }
    val googleScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        googleAnswer.value?.complete(it)
    }
    val scope = rememberCoroutineScope()
    val shelfBooks = remember(state) { (state.reading + state.groups.flatMap { it.items }).distinctBy { it.uri } }

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
                            val count = if (item == HomeTab.SEARCH && tab != HomeTab.SEARCH) news else 0
                            BadgedBox(badge = {
                                when {
                                    count > 0 -> Badge { Text("$count") }
                                    showBadge -> Badge()
                                }
                            }) {
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
                        onRandom = { randomUri = randomShelfBook(shelfBooks)?.uri },
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
                    onReading = { readingSettings = true },
                    sync = {
                        SyncCard(
                            prefs = syncPrefs,
                            status = syncStatus,
                            onSyncNow = app.sync::syncNow,
                            onSetup = { syncSetup = true },
                            onDisconnect = { scope.launch { app.sync.disconnect() } },
                            onCloudBooks = {
                                cloudOpen = true
                                app.sync.refreshCloud()
                            },
                        )
                    },
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
    if (syncSetup) {
        SyncSetupSheet(
            onConnect = app.sync::connect,
            onGoogle = {
                val activity = context as Activity
                signInToGoogle(activity) { request ->
                    val answer = CompletableDeferred<ActivityResult>()
                    googleAnswer.value = answer
                    googleScreen.launch(request)
                    answer.await()
                } ?: app.sync.connectGoogle()
            },
            onDismiss = { syncSetup = false },
        )
    }
    if (readingSettings) {
        val prefs by app.readerSettings.prefs.collectAsStateWithLifecycle(initialValue = ReaderPrefs())
        ModalBottomSheet(onDismissRequest = { readingSettings = false }) {
            ReaderSettingsPanel(prefs) { transform -> scope.launch { app.readerSettings.save(transform(prefs)) } }
        }
    }
    if (cloudOpen) {
        CloudShelfSheet(cloud, onUpload = app.sync::uploadShelf, onDownload = app.sync::download, onDismiss = { cloudOpen = false })
    }
    randomUri?.let { uri ->
        val book = shelfBooks.firstOrNull { it.uri == uri }
        if (book == null) {
            randomUri = null
        } else {
            ShelfRandomSheet(
                book,
                onRead = { randomUri = null; open(book) },
                onAnother = { randomUri = randomShelfBook(shelfBooks, except = uri)?.uri ?: uri },
                onDismiss = { randomUri = null },
            )
        }
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
