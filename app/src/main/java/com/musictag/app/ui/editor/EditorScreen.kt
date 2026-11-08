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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musictag.app.R
import com.musictag.app.data.model.TrackItem
import com.musictag.app.ui.components.ArtworkCard
import com.musictag.app.ui.components.ErrorState
import com.musictag.app.ui.components.InfoRow
import com.musictag.app.ui.components.LoadingState
import com.musictag.app.ui.components.MusicTagTextField
import com.musictag.app.ui.components.SectionCard
import com.musictag.app.util.Formats

/**
 * Full screen tag editor. Owns its snackbar and the "save as" document picker so
 * the rest of the app only needs to know when the editor finished.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("编辑标签", style = MaterialTheme.typography.titleLarge)
                            if (state.hasChanges) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(8.dp),
                                ) {}
                            }
                        }
                        state.track?.let { track ->
                            Text(
                                text = track.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(
                            onClick = viewModel::save,
                            enabled = state.hasChanges && state.track != null,
                        ) { Text("保存", style = MaterialTheme.typography.labelLarge) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (state.hasChanges && state.track != null && !state.saving) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "有未保存的修改",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = viewModel::save) { Text("保存到文件") }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.loading -> LoadingState(
                message = "正在读取音频标签…",
                modifier = Modifier.padding(padding),
            )

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
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "artwork") {
                        Column {
                            Row(verticalAlignment = Alignment.Top) {
                                ArtworkCard(
                                    bitmap = artworkBitmap,
                                    modifier = Modifier.size(124.dp),
                                    corner = 22.dp,
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = state.fields.title.ifBlank {
                                            track.displayName.substringBeforeLast('.')
                                        },
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = state.fields.artist.ifBlank { "未知歌手" } +
                                            state.fields.album.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = "封面将嵌入音频文件",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_image),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("本地图片")
                                }
                                FilledTonalButton(
                                    onClick = viewModel::openSearch,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_cloud_download),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("在线匹配")
                                }
                                if (state.artwork != null) {
                                    TextButton(onClick = viewModel::removeArtwork) { Text("移除") }
                                }
                            }
                        }
                    }

                    item(key = "tags") {
                        SectionCard(title = "歌曲信息", icon = R.drawable.ic_tag) {
                            MusicTagTextField(
                                value = state.fields.title,
                                onValueChange = { viewModel.updateField(EditorField.TITLE, it) },
                                label = "标题",
                                placeholder = "例如：晴天",
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MusicTagTextField(
                                    value = state.fields.artist,
                                    onValueChange = { viewModel.updateField(EditorField.ARTIST, it) },
                                    label = "歌手",
                                    placeholder = "周杰伦",
                                    modifier = Modifier.weight(1f),
                                )
                                MusicTagTextField(
                                    value = state.fields.album,
                                    onValueChange = { viewModel.updateField(EditorField.ALBUM, it) },
                                    label = "专辑",
                                    placeholder = "叶惠美",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MusicTagTextField(
                                    value = state.fields.year,
                                    onValueChange = { viewModel.updateField(EditorField.YEAR, it) },
                                    label = "年份",
                                    placeholder = "2003",
                                    modifier = Modifier.weight(1f),
                                )
                                MusicTagTextField(
                                    value = state.fields.trackNumber,
                                    onValueChange = { viewModel.updateField(EditorField.TRACK_NUMBER, it) },
                                    label = "音轨",
                                    placeholder = "3/12",
                                    modifier = Modifier.weight(1f),
                                )
                                MusicTagTextField(
                                    value = state.fields.discNumber,
                                    onValueChange = { viewModel.updateField(EditorField.DISC_NUMBER, it) },
                                    label = "碟号",
                                    placeholder = "1",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MusicTagTextField(
                                    value = state.fields.genre,
                                    onValueChange = { viewModel.updateField(EditorField.GENRE, it) },
                                    label = "流派",
                                    placeholder = "Pop",
                                    modifier = Modifier.weight(1f),
                                )
                                MusicTagTextField(
                                    value = state.fields.albumArtist,
                                    onValueChange = { viewModel.updateField(EditorField.ALBUM_ARTIST, it) },
                                    label = "专辑歌手",
                                    placeholder = "可选",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            MusicTagTextField(
                                value = state.fields.comment,
                                onValueChange = { viewModel.updateField(EditorField.COMMENT, it) },
                                label = "备注",
                                placeholder = "可选",
                            )
                        }
                    }

                    item(key = "lyrics") {
                        SectionCard(
                            title = "歌词",
                            icon = R.drawable.ic_lyrics,
                            trailing = {
                                TextButton(onClick = viewModel::openSearch) {
                                    Icon(
                                        painterResource(R.drawable.ic_auto_awesome),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("在线匹配")
                                }
                            },
                        ) {
                            MusicTagTextField(
                                value = state.fields.lyrics,
                                onValueChange = { viewModel.updateField(EditorField.LYRICS, it) },
                                label = "歌词内容",
                                singleLine = false,
                                minLines = 7,
                                maxLines = 16,
                                placeholder = "支持 LRC 时间轴歌词，可直接粘贴，或点击右上角在线匹配",
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "已输入 ${state.fields.lyrics.length} 个字符 · LRC 时间轴会原样写入标签",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item(key = "fileinfo") {
                        SectionCard(title = "文件信息", icon = R.drawable.ic_file_music) {
                            InfoRow("格式", track.formatName ?: "未知")
                            InfoRow("时长", Formats.duration(track.durationMs))
                            InfoRow("大小", Formats.fileSize(track.sizeBytes))
                            InfoRow("类型", track.mimeType ?: "未知")
                            InfoRow("文件名", track.displayName)
                        }
                    }

                    item(key = "hint") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "所有修改只写入你选择的本地文件",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
            title = { Text("放弃未保存的修改？") },
            text = { Text("关闭后本次编辑的内容不会写入音频文件。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onClose()
                }) { Text("放弃修改") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }

    if (state.searchVisible) {
        OnlineMatchSheet(
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
