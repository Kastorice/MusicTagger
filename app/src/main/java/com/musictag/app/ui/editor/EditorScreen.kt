package com.musictag.app.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musictag.app.data.model.TrackItem
import com.musictag.app.ui.components.ArtworkCard
import com.musictag.app.ui.components.ErrorState
import com.musictag.app.ui.components.InfoRow
import com.musictag.app.ui.components.LoadingState
import com.musictag.app.ui.components.LyricsTextField
import com.musictag.app.ui.components.MiuixExtendedFloatingActionButton
import com.musictag.app.ui.components.MiuixSegmentedControl
import com.musictag.app.ui.components.MusicTagTextField
import com.musictag.app.util.Formats
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Search
import kotlinx.coroutines.launch

/**
 * Full screen tag editor, MIUI/HyperOS style. The cover card stays pinned above
 * a segmented tab switch (歌曲信息 / 歌词 / 文件信息); only the selected tab is
 * composed, which keeps the heavy text fields out of unrelated scroll frames.
 */
@Composable
fun EditorScreen(
    uri: String,
    viewModel: EditorViewModel,
    onClose: () -> Unit,
    onSaved: (TrackItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showCoverDialog by remember { mutableStateOf(false) }
    var pendingSaveAsName by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) viewModel.setArtworkFromLocal(picked)
    }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/*"),
    ) { destination ->
        if (destination != null) viewModel.saveAs(destination) else viewModel.cancelSaveAs()
    }

    LaunchedEffect(uri) { viewModel.open(Uri.parse(uri)) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is EditorEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is EditorEvent.Saved -> onSaved(event.item)
                is EditorEvent.SaveAsCompleted -> {
                    onSaved(event.item)
                    onClose()
                }
                is EditorEvent.RequestSaveAs -> pendingSaveAsName = event.suggestedName
            }
        }
    }

    LaunchedEffect(pendingSaveAsName) {
        val name = pendingSaveAsName
        if (name != null) {
            pendingSaveAsName = null
            createDocument.launch(name)
        }
    }

    val requestClose: () -> Unit = {
        if (state.hasChanges) showDiscardDialog = true else onClose()
    }
    BackHandler(enabled = true) { requestClose() }

    val artworkBitmap by produceState<Bitmap?>(initialValue = null, key1 = state.artwork) {
        value = state.artwork?.let { viewModel.previewBitmap(it.bytes, 512) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = "编辑标签",
                navigationIcon = {
                    IconButton(onClick = requestClose, modifier = Modifier.size(50.dp)) {
                        Icon(
                            MiuixIcons.Back,
                            contentDescription = "返回",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.track != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (selectedTab == 1) {
                        LyricsSearchFab(onClick = viewModel::openSearch)
                    }
                    MiuixExtendedFloatingActionButton(
                        text = if (state.saving) "保存中…" else "保存到文件",
                        onClick = {
                            if (state.hasChanges) {
                                viewModel.save()
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("没有需要保存的修改") }
                            }
                        },
                        enabled = !state.saving,
                        icon = MiuixIcons.Download,
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> LoadingState("正在读取音频标签…", Modifier.padding(padding))
                state.loadError != null -> ErrorState(
                    message = state.loadError.orEmpty(),
                    onRetry = { viewModel.open(Uri.parse(uri)) },
                    modifier = Modifier.padding(padding),
                )
                state.track == null -> LoadingState("正在准备…", Modifier.padding(padding))
                else -> {
                    val track = state.track!!
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "artwork") {
                            EditorSection(title = "专辑封面") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable { showCoverDialog = true },
                                    ) {
                                        ArtworkCard(
                                            bitmap = artworkBitmap,
                                            modifier = Modifier.size(88.dp),
                                            corner = 16.dp,
                                        )
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = state.fields.title.ifBlank {
                                                track.displayName.substringBeforeLast('.')
                                            },
                                            style = MiuixTheme.textStyles.title4,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            text = state.fields.artist.ifBlank { "未知歌手" } +
                                                state.fields.album.takeIf { it.isNotBlank() }
                                                    ?.let { " · $it" }.orEmpty(),
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(5.dp))
                                        Text(
                                            text = "点击专辑封面进行替换",
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = viewModel::openSearch,
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 14.dp,
                                    minHeight = 42.dp,
                                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
                                    colors = ButtonDefaults.buttonColors(),
                                ) {
                                    Icon(
                                        MiuixIcons.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "在线匹配所有歌曲信息",
                                        style = MiuixTheme.textStyles.button,
                                    )
                                }
                            }
                        }

                        item(key = "editor-tabs") {
                            MiuixSegmentedControl(
                                options = listOf("歌曲信息", "歌词", "文件信息"),
                                selectedIndex = selectedTab,
                                onSelected = { selectedTab = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            )
                        }

                        when (selectedTab) {
                            0 -> item(key = "tags") {
                                EditorTabCard {
                                    MusicTagTextField(
                                        value = state.fields.title,
                                        onValueChange = { viewModel.updateField(EditorField.TITLE, it) },
                                        label = "标题",
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    MusicTagTextField(
                                        value = state.fields.artist,
                                        onValueChange = { viewModel.updateField(EditorField.ARTIST, it) },
                                        label = "歌手",
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    MusicTagTextField(
                                        value = state.fields.album,
                                        onValueChange = { viewModel.updateField(EditorField.ALBUM, it) },
                                        label = "专辑",
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    MusicTagTextField(
                                        value = state.fields.year,
                                        onValueChange = { viewModel.updateField(EditorField.YEAR, it) },
                                        label = "年份",
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    MusicTagTextField(
                                        value = state.fields.trackNumber,
                                        onValueChange = { viewModel.updateField(EditorField.TRACK_NUMBER, it) },
                                        label = "音轨",
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    MusicTagTextField(
                                        value = state.fields.discNumber,
                                        onValueChange = { viewModel.updateField(EditorField.DISC_NUMBER, it) },
                                        label = "碟号",
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    MusicTagTextField(
                                        value = state.fields.genre,
                                        onValueChange = { viewModel.updateField(EditorField.GENRE, it) },
                                        label = "流派",
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    MusicTagTextField(
                                        value = state.fields.albumArtist,
                                        onValueChange = { viewModel.updateField(EditorField.ALBUM_ARTIST, it) },
                                        label = "专辑歌手",
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    MusicTagTextField(
                                        value = state.fields.comment,
                                        onValueChange = { viewModel.updateField(EditorField.COMMENT, it) },
                                        label = "备注",
                                    )
                                }
                            }

                            1 -> item(key = "lyrics") {
                                EditorTabCard {
                                    LyricsTextField(
                                        value = state.fields.lyrics,
                                        onValueChange = { viewModel.updateField(EditorField.LYRICS, it) },
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "已输入 ${state.fields.lyrics.length} 个字符",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }

                            else -> item(key = "fileinfo") {
                                EditorTabCard {
                                    InfoRow("格式", track.formatName ?: "未知")
                                    InfoRow("时长", Formats.duration(track.durationMs))
                                    InfoRow("大小", Formats.fileSize(track.sizeBytes))
                                    InfoRow("比特率", track.bitrateKbps?.let { "$it kbps" } ?: "未知")
                                    InfoRow("采样率", track.sampleRateHz?.let { "$it Hz" } ?: "未知")
                                    InfoRow("声道", track.channels?.let { "$it 声道" } ?: "未知")
                                    InfoRow("类型", track.mimeType ?: "未知")
                                    InfoRow("文件名", track.displayName)
                                    InfoRow("文件位置", track.location ?: "未知")
                                }
                            }
                        }
                    }
                }
            }

            OverlayDialog(
                show = showCoverDialog,
                title = "替换专辑封面",
                onDismissRequest = { showCoverDialog = false },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "在线匹配所有歌曲信息",
                        summary = "从网易云 / QQ 音乐获取封面、歌词与歌曲信息",
                        onClick = {
                            showCoverDialog = false
                            viewModel.openSearch()
                        },
                        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 12.dp),
                    )
                    HorizontalDivider()
                    BasicComponent(
                        title = "从本地选择图片",
                        summary = "从文件中选择一张图片作为专辑封面",
                        onClick = {
                            showCoverDialog = false
                            imagePicker.launch(arrayOf("image/*"))
                        },
                        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 12.dp),
                    )
                    if (state.artwork != null) {
                        HorizontalDivider()
                        BasicComponent(
                            title = "移除当前封面",
                            onClick = {
                                showCoverDialog = false
                                viewModel.removeArtwork()
                            },
                            insideMargin = PaddingValues(horizontal = 0.dp, vertical = 12.dp),
                        )
                    }
                }
            }

            OverlayDialog(
                show = showDiscardDialog,
                title = "放弃未保存的修改？",
                summary = "关闭后本次编辑的内容不会写入音频文件。",
                onDismissRequest = { showDiscardDialog = false },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = "继续编辑",
                        onClick = { showDiscardDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            showDiscardDialog = false
                            onClose()
                        },
                        modifier = Modifier.weight(1f),
                        cornerRadius = 14.dp,
                        minHeight = 42.dp,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text("放弃修改", style = MiuixTheme.textStyles.button) }
                }
            }

            OnlineMatchSheet(
                show = state.searchVisible,
                state = state.search,
                previewCover = viewModel::previewBitmap,
                onDismiss = viewModel::closeSearch,
                onKeywordChange = viewModel::setSearchKeyword,
                onSourceChange = viewModel::setSearchSource,
                onSearch = viewModel::search,
                onSelect = viewModel::selectSong,
                onBackToResults = viewModel::clearMatchSelection,
                onApply = viewModel::applyMatch,
            )
        }
    }
}

/** Section label placed above its white card, matching the settings layout. */
@Composable
private fun EditorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SmallTitle(
            text = title,
            insideMargin = PaddingValues(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
            Column(modifier = Modifier.padding(14.dp)) { content() }
        }
    }
}

/** Card used by the tab contents; the tab label already carries the section name. */
@Composable
private fun EditorTabCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

/** Circular search FAB shown next to the save FAB while the lyrics tab is active. */
@Composable
private fun LyricsSearchFab(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(52.dp)
            .shadow(elevation = 14.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MiuixTheme.colorScheme.surface,
        contentColor = MiuixTheme.colorScheme.onSurface,
        shadowElevation = 12.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = MiuixIcons.Search,
                contentDescription = "在线匹配歌词",
                tint = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
