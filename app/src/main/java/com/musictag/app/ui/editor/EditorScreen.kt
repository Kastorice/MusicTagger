package com.musictag.app.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musictag.app.R
import com.musictag.app.data.model.TrackItem
import com.musictag.app.ui.components.ArtworkCard
import com.musictag.app.ui.components.ErrorState
import com.musictag.app.ui.components.InfoRow
import com.musictag.app.ui.components.LoadingState
import com.musictag.app.ui.components.MiuixPrimaryButton
import com.musictag.app.ui.components.MiuixSectionCard
import com.musictag.app.ui.components.MusicTagTextField
import com.musictag.app.util.Formats
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
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
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Image as ImageIcon

/**
 * Full screen tag editor, MIUI/HyperOS style. Owns its snackbar, dialogs and the
 * "save as" document picker so the app shell only needs the close/saved callbacks.
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
    var showDiscardDialog by remember { mutableStateOf(false) }
    var pendingSaveAsName by remember { mutableStateOf<String?>(null) }

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
        value = state.artwork?.let { viewModel.previewBitmap(it.bytes, 640) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = "编辑标签",
                subtitle = state.track?.displayName.orEmpty(),
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        text = "保存",
                        onClick = viewModel::save,
                        enabled = state.hasChanges && state.track != null && !state.saving,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        },
        bottomBar = {
            if (state.hasChanges && state.track != null) {
                Surface(color = MiuixTheme.colorScheme.surfaceContainer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (state.saving) "正在写入文件…" else "有未保存的修改",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.weight(1f),
                        )
                        MiuixPrimaryButton(
                            text = if (state.saving) "保存中" else "保存到文件",
                            onClick = viewModel::save,
                            enabled = !state.saving,
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
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
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "artwork") {
                        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ArtworkCard(
                                        bitmap = artworkBitmap,
                                        modifier = Modifier.size(92.dp),
                                        corner = 16.dp,
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = state.fields.title.ifBlank {
                                                track.displayName.substringBeforeLast('.')
                                            },
                                            style = MiuixTheme.textStyles.title3,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = state.fields.artist.ifBlank { "未知歌手" } +
                                                state.fields.album.takeIf { it.isNotBlank() }
                                                    ?.let { " · $it" }.orEmpty(),
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = "封面将嵌入音频文件",
                                            style = MiuixTheme.textStyles.footnote2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Button(
                                        onClick = { imagePicker.launch(arrayOf("image/*")) },
                                        cornerRadius = 12.dp,
                                        minHeight = 38.dp,
                                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        Icon(
                                            MiuixIcons.ImageIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp),
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text("本地图片", style = MiuixTheme.textStyles.button)
                                    }
                                    Button(
                                        onClick = viewModel::openSearch,
                                        cornerRadius = 12.dp,
                                        minHeight = 38.dp,
                                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        colors = ButtonDefaults.buttonColorsPrimary(),
                                    ) {
                                        Icon(
                                            MiuixIcons.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp),
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text("在线匹配", style = MiuixTheme.textStyles.button)
                                    }
                                    if (state.artwork != null) {
                                        TextButton(
                                            text = "移除",
                                            onClick = viewModel::removeArtwork,
                                            minHeight = 38.dp,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "tags") {
                        MiuixSectionCard(title = "歌曲信息") {
                            MusicTagTextField(
                                value = state.fields.title,
                                onValueChange = { viewModel.updateField(EditorField.TITLE, it) },
                                label = "标题",
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MusicTagTextField(
                                    value = state.fields.artist,
                                    onValueChange = { viewModel.updateField(EditorField.ARTIST, it) },
                                    label = "歌手",
                                    modifier = Modifier.weight(1f),
                                )
                                MusicTagTextField(
                                    value = state.fields.album,
                                    onValueChange = { viewModel.updateField(EditorField.ALBUM, it) },
                                    label = "专辑",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MusicTagTextField(
                                    value = state.fields.year,
                                    onValueChange = { viewModel.updateField(EditorField.YEAR, it) },
                                    label = "年份",
                                    modifier = Modifier.weight(1f),
                                )
                                MusicTagTextField(
                                    value = state.fields.trackNumber,
                                    onValueChange = { viewModel.updateField(EditorField.TRACK_NUMBER, it) },
                                    label = "音轨",
                                    modifier = Modifier.weight(1f),
                                )
                                MusicTagTextField(
                                    value = state.fields.discNumber,
                                    onValueChange = { viewModel.updateField(EditorField.DISC_NUMBER, it) },
                                    label = "碟号",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MusicTagTextField(
                                    value = state.fields.genre,
                                    onValueChange = { viewModel.updateField(EditorField.GENRE, it) },
                                    label = "流派",
                                    modifier = Modifier.weight(1f),
                                )
                                MusicTagTextField(
                                    value = state.fields.albumArtist,
                                    onValueChange = { viewModel.updateField(EditorField.ALBUM_ARTIST, it) },
                                    label = "专辑歌手",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            MusicTagTextField(
                                value = state.fields.comment,
                                onValueChange = { viewModel.updateField(EditorField.COMMENT, it) },
                                label = "备注",
                            )
                        }
                    }

                    item(key = "lyrics") {
                        MiuixSectionCard(
                            title = "歌词",
                            trailing = {
                                TextButton(
                                    text = "在线匹配",
                                    onClick = viewModel::openSearch,
                                    minHeight = 32.dp,
                                )
                            },
                        ) {
                            MusicTagTextField(
                                value = state.fields.lyrics,
                                onValueChange = { viewModel.updateField(EditorField.LYRICS, it) },
                                label = "歌词内容",
                                singleLine = false,
                                minLines = 7,
                                maxLines = 16,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "已输入 ${state.fields.lyrics.length} 个字符 · LRC 时间轴会原样写入标签",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }

                    item(key = "fileinfo") {
                        MiuixSectionCard(title = "文件信息") {
                            InfoRow("格式", track.formatName ?: "未知")
                            InfoRow("时长", Formats.duration(track.durationMs))
                            InfoRow("大小", Formats.fileSize(track.sizeBytes))
                            InfoRow("类型", track.mimeType ?: "未知")
                            InfoRow("文件名", track.displayName)
                        }
                    }

                    item(key = "hint") {
                        Text(
                            text = "所有修改只写入你选择的本地文件",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
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

