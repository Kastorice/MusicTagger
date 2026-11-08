package com.musictag.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musictag.app.R
import com.musictag.app.data.model.MatchInfo
import com.musictag.app.data.model.SearchUiState
import com.musictag.app.ui.components.ArtworkCard
import com.musictag.app.ui.components.NetworkCover
import com.musictag.core.net.Lrc
import com.musictag.core.net.MusicSourceId
import com.musictag.core.net.RemoteSong
import com.musictag.app.util.Formats

/**
 * Modal sheet for online lyric/cover matching. Results come from NetEase Cloud
 * Music or QQ Music; selecting a hit resolves lyrics and artwork, which can then
 * be applied to the editor draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineMatchSheet(
    state: SearchUiState,
    previewCover: suspend (ByteArray, Int) -> Bitmap?,
    onDismiss: () -> Unit,
    onKeywordChange: (String) -> Unit,
    onSourceChange: (MusicSourceId) -> Unit,
    onSearch: () -> Unit,
    onSelect: (RemoteSong) -> Unit,
    onBackToResults: () -> Unit,
    onApply: (applyMetadata: Boolean, applyLyrics: Boolean, applyCover: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_cloud_download),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "在线匹配",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
            Text(
                text = "从网易云音乐 / QQ 音乐搜索歌词与封面，应用后保存即可嵌入文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                MusicSourceId.entries.forEachIndexed { index, sourceId ->
                    SegmentedButton(
                        selected = state.sourceId == sourceId,
                        onClick = { onSourceChange(sourceId) },
                        shape = SegmentedButtonDefaults.itemShape(index, MusicSourceId.entries.size),
                    ) { Text(sourceId.displayName) }
                }
            }
            Spacer(Modifier.height(12.dp))

            val focusManager = LocalFocusManager.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.keyword,
                    onValueChange = onKeywordChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("歌曲名 / 歌手") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        onSearch()
                    }),
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onSearch()
                    },
                    enabled = !state.searching,
                    shape = MaterialTheme.shapes.medium,
                ) { Text("搜索") }
            }
            if (state.error != null && state.selectedSong == null) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            val selected = state.selectedSong
            if (selected == null) {
                ResultsList(
                    state = state,
                    onSelect = onSelect,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                MatchPreview(
                    state = state,
                    previewCover = previewCover,
                    onBackToResults = onBackToResults,
                    onApply = onApply,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ResultsList(
    state: SearchUiState,
    onSelect: (RemoteSong) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        when {
            state.searching -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "正在搜索 ${state.sourceId.displayName}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.results.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(R.drawable.ic_auto_awesome),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "输入歌曲名或歌手开始搜索",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxHeight(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items = state.results, key = { it.source.name + it.id }) { song ->
                    ResultRow(song = song, onClick = { onSelect(song) })
                }
            }
        }
    }
}

@Composable
private fun ResultRow(song: RemoteSong, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetworkCover(url = song.coverUrl, modifier = Modifier.size(46.dp), corner = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = Formats.duration(song.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MatchPreview(
    state: SearchUiState,
    previewCover: suspend (ByteArray, Int) -> Bitmap?,
    onBackToResults: () -> Unit,
    onApply: (Boolean, Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val match: MatchInfo? = state.match
    var applyMetadata by remember(match) { mutableStateOf(true) }
    var applyLyrics by remember(match) { mutableStateOf(true) }
    var applyCover by remember(match) { mutableStateOf(true) }

    val coverBitmap by produceState<Bitmap?>(initialValue = null, key1 = match?.cover?.bytes) {
        value = match?.cover?.bytes?.let { previewCover(it, 320) }
    }

    Column(modifier = modifier.fillMaxHeight()) {
        if (state.loadingMatch) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("正在获取歌词与封面…", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (match != null) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.Top) {
                        if (coverBitmap != null) {
                            Image(
                                bitmap = coverBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(112.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            )
                        } else {
                            ArtworkCard(
                                bitmap = null,
                                modifier = Modifier.size(112.dp),
                                corner = 18.dp,
                                placeholderIcon = R.drawable.ic_album,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = match.song.title,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = match.song.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = match.song.source.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                if (match.error != null) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = match.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }

                item {
                    Text("歌词预览", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp, max = 220.dp),
                    ) {
                        val plain = if (match.hasLyrics) {
                            Lrc.plainText(match.lyrics?.lrc).lineSequence()
                                .filter { it.isNotBlank() }
                                .take(12)
                                .joinToString("\n")
                        } else {
                            ""
                        }
                        Text(
                            text = if (plain.isBlank()) {
                                "未找到歌词，可尝试切换数据源或换一个搜索结果。"
                            } else {
                                "$plain\n…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (plain.isBlank()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                item {
                    ApplyOption(
                        title = "填充歌曲信息",
                        subtitle = "${match.song.title} · ${match.song.artists}",
                        checked = applyMetadata,
                        enabled = true,
                        onCheckedChange = { applyMetadata = it },
                    )
                    ApplyOption(
                        title = "应用歌词",
                        subtitle = if (match.hasLyrics) "将写入 LRC 歌词标签" else "该来源没有歌词",
                        checked = applyLyrics && match.hasLyrics,
                        enabled = match.hasLyrics,
                        onCheckedChange = { applyLyrics = it },
                    )
                    ApplyOption(
                        title = "应用专辑封面",
                        subtitle = if (match.hasCover) "将替换当前封面" else "该来源没有封面",
                        checked = applyCover && match.hasCover,
                        enabled = match.hasCover,
                        onCheckedChange = { applyCover = it },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBackToResults) { Text("返回结果") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        onApply(
                            applyMetadata,
                            applyLyrics && match.hasLyrics,
                            applyCover && match.hasCover,
                        )
                    },
                ) { Text("应用到编辑内容") }
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("未找到匹配结果", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ApplyOption(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
