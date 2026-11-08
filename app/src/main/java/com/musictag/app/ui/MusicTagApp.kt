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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.musictag.app.AppContainer
import com.musictag.app.ui.editor.EditorScreen
import com.musictag.app.ui.editor.EditorViewModel
import com.musictag.app.ui.library.LibraryEvent
import com.musictag.app.ui.library.LibraryScreen
import com.musictag.app.ui.library.LibraryViewModel
import com.musictag.app.ui.settings.SettingsScreen

/**
 * App shell: bottom navigation between the library and settings, plus a
 * full-screen editor overlay for the selected track.
 */
@Composable
fun MusicTagApp(
    container: AppContainer,
    pendingUri: Uri?,
    onPendingUriConsumed: () -> Unit,
) {
    val context = LocalContext.current
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

    LaunchedEffect(Unit) {
        container.pruneEditCache()
    }

    fun closeEditor() {
        val closingUri = editorUri
        editorViewModel.close()
        editorUri = null
        closingUri?.let(libraryViewModel::onTrackOpened)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (editorUri == null) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(com.musictag.app.R.drawable.ic_music_note),
                                    contentDescription = null,
                                )
                            },
                            label = { Text("曲库") },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text("设置") },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (editorUri == null && tab == 0) {
                    ExtendedFloatingActionButton(
                        onClick = { filePicker.launch(arrayOf("audio/*")) },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("添加音乐") },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding()),
            ) {
                Crossfade(targetState = tab, label = "tab") { currentTab ->
                    when (currentTab) {
                        0 -> LibraryScreen(
                            state = libraryState,
                            onAddClick = { filePicker.launch(arrayOf("audio/*")) },
                            onAddAllFiles = { filePicker.launch(arrayOf("*/*")) },
                            onRefresh = libraryViewModel::refresh,
                            onOpen = { uri -> tab = 0; editorUri = uri },
                            onRemove = libraryViewModel::remove,
                            thumbnail = { uri -> libraryViewModel.thumbnail(Uri.parse(uri)) },
                        )

                        else -> SettingsScreen(
                            settings = settings,
                            onDynamicColorChange = container.settingsStore::setDynamicColor,
                            onSourceChange = container.settingsStore::setPreferredSource,
                            onNeteaseApiBaseChange = { base ->
                                container.settingsStore.setNeteaseApiBase(base)
                            },
                        )
                    }
                }
            }
        }

        // Full-screen editor drawn above the scaffold so the bottom bar is covered.
        AnimatedVisibility(
            visible = editorUri != null,
            enter = slideInHorizontally(initialOffsetX = { it / 4 }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it / 4 }) + fadeOut(),
        ) {
            val uri = editorUri
            if (uri != null) {
                EditorScreen(
                    uri = uri,
                    viewModel = editorViewModel,
                    onClose = ::closeEditor,
                    onSaved = { item -> libraryViewModel.onTrackOpened(item.uri) },
                )
            }
        }
    }
}
