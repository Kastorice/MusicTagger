package com.musictag.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.musictag.app.AppContainer
import com.musictag.app.ui.components.MiuixOverflowMenu
import com.musictag.app.ui.editor.EditorScreen
import com.musictag.app.ui.editor.EditorViewModel
import com.musictag.app.ui.library.LibraryEvent
import com.musictag.app.ui.library.LibraryScreen
import com.musictag.app.ui.library.LibraryViewModel
import com.musictag.app.ui.settings.SettingsScreen
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings

/**
 * App shell in HyperOS style: large-title top bar for the library, bottom
 * [NavigationBar], a capsule FAB and the full-screen editor overlay.
 */
@Composable
fun MusicTagApp(
    container: AppContainer,
    pendingUri: Uri?,
    onPendingUriConsumed: () -> Unit,
) {
    val libraryViewModel: LibraryViewModel = viewModel(
        factory = remember(container) {
            viewModelFactory {
                initializer { LibraryViewModel(container.trackRepository, container.artworkRepository) }
            }
        },
    )
    val editorViewModel: EditorViewModel = viewModel(
        factory = remember(container) {
            viewModelFactory { initializer { EditorViewModel(container) } }
        },
    )
    val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editorUri by rememberSaveable { mutableStateOf<String?>(null) }
    var topMenuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) libraryViewModel.addTracks(uris, openSingle = true)
    }

    LaunchedEffect(Unit) {
        libraryViewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is LibraryEvent.OpenTrack -> {
                    tab = 0
                    editorUri = event.uri
                }
            }
        }
    }

    LaunchedEffect(pendingUri) {
        val uri = pendingUri ?: return@LaunchedEffect
        onPendingUriConsumed()
        libraryViewModel.addTracks(listOf(uri), openSingle = true)
    }

    LaunchedEffect(Unit) { container.pruneEditCache() }

    fun closeEditor() {
        val closingUri = editorUri
        editorViewModel.close()
        editorUri = null
        closingUri?.let(libraryViewModel::onTrackOpened)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (tab == 0) {
                    TopAppBar(
                        title = "音签",
                        largeTitle = "音乐",
                        subtitle = "共 ${libraryState.items.size} 首歌曲 · 本地标签编辑",
                        navigationIconPadding = 0.dp,
                        actionIconPadding = 0.dp,
                        actions = {
                            IconButton(
                                onClick = libraryViewModel::refresh,
                                modifier = Modifier.size(52.dp),
                            ) {
                                Icon(
                                    MiuixIcons.Refresh,
                                    contentDescription = "刷新",
                                    modifier = Modifier.size(25.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Box {
                                IconButton(
                                    onClick = { topMenuExpanded = true },
                                    modifier = Modifier.size(52.dp),
                                ) {
                                    Icon(
                                        MiuixIcons.More,
                                        contentDescription = "更多",
                                        modifier = Modifier.size(25.dp),
                                    )
                                }
                                MiuixOverflowMenu(
                                    expanded = topMenuExpanded,
                                    onDismissRequest = { topMenuExpanded = false },
                                    items = listOf(
                                        "从所有文件导入" to { filePicker.launch(arrayOf("*/*")) },
                                        "刷新列表" to { libraryViewModel.refresh() },
                                    ),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        },
                    )
                } else {
                    TopAppBar(
                        title = "设置",
                        largeTitle = "设置",
                        subtitle = com.musictag.app.BuildConfig.VERSION_NAME,
                        navigationIconPadding = 0.dp,
                        actionIconPadding = 0.dp,
                    )
                }
            },
            bottomBar = {
                if (editorUri == null) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = MiuixIcons.Music,
                            label = "曲库",
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = MiuixIcons.Settings,
                            label = "设置",
                        )
                    }
                }
            },
            floatingActionButton = {
                if (editorUri == null && tab == 0) {
                    FloatingActionButton(onClick = { filePicker.launch(arrayOf("audio/*")) }) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = "添加音乐",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            },
            floatingActionButtonPosition = FabPosition.End,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Crossfade(targetState = tab, label = "tab") { currentTab ->
                if (currentTab == 0) {
                    LibraryScreen(
                        state = libraryState,
                        contentPadding = padding,
                        onAddClick = { filePicker.launch(arrayOf("audio/*")) },
                        onAddAllFiles = { filePicker.launch(arrayOf("*/*")) },
                        onRefresh = libraryViewModel::refresh,
                        onOpen = { uri ->
                            tab = 0
                            editorUri = uri
                        },
                        onRemove = libraryViewModel::remove,
                        thumbnail = { uri -> libraryViewModel.thumbnail(Uri.parse(uri)) },
                    )
                } else {
                    SettingsScreen(
                        settings = settings,
                        contentPadding = padding,
                        onDynamicColorChange = container.settingsStore::setDynamicColor,
                        onSourceChange = container.settingsStore::setPreferredSource,
                        onNeteaseApiBaseChange = { base ->
                            container.settingsStore.setNeteaseApiBase(base)
                        },
                    )
                }
            }
        }

        if (topMenuExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { topMenuExpanded = false },
            )
        }

        AnimatedVisibility(
            visible = editorUri != null,
            enter = slideInHorizontally(initialOffsetX = { it / 4 }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it / 4 }) + fadeOut(),
        ) {
            val currentUri = editorUri
            if (currentUri != null) {
                EditorScreen(
                    uri = currentUri,
                    viewModel = editorViewModel,
                    onClose = ::closeEditor,
                    onSaved = { item -> libraryViewModel.onTrackOpened(item.uri) },
                )
            }
        }
    }
}
