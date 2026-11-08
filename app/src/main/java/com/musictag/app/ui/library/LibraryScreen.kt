package com.musictag.app.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musictag.app.R
import com.musictag.app.data.model.LibraryUiState
import com.musictag.app.data.model.TrackItem
import com.musictag.app.ui.components.ArtworkCard
import com.musictag.app.ui.components.EmptyState
import com.musictag.app.ui.components.FormatBadge
import com.musictag.app.ui.components.MiuixOverflowMenu
import com.musictag.app.ui.components.MusicTagTextField
import com.musictag.app.util.Formats
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Search

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    contentPadding: PaddingValues,
    onAddClick: () -> Unit,
    onAddAllFiles: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    thumbnail: suspend (String) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(state.items, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            state.items
        } else {
            state.items.filter { item ->
                listOfNotNull(item.title, item.artist, item.album, item.displayName)
                    .any { it.contains(keyword, ignoreCase = true) }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        if (state.refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (state.items.isEmpty() && !state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = R.drawable.ic_music_note,
                    title = "还没有音乐",
                    description = "从本地选择 mp3 / flac / ogg / opus / m4a / aac 音频文件，\n即可编辑标题、歌手、专辑、歌词与封面。",
                    actionLabel = "选择音频文件",
                    onAction = onAddClick,
                    secondaryActionLabel = "从「所有文件」导入",
                    onSecondaryAction = onAddAllFiles,
                )
            }
        } else if (state.loading && state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                top.yukonga.miuix.kmp.basic.CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "summary") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "本地音乐",
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "共 ${state.items.size} 首",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                }
                item(key = "search") {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "搜索标题、歌手或专辑",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        cornerRadius = 14.dp,
                        leadingIcon = {
                            Icon(MiuixIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        textStyle = MiuixTheme.textStyles.body2,
                    )
                }
                if (filtered.isEmpty()) {
                    item(key = "empty-filter") {
                        Text(
                            text = "没有匹配「$query」的歌曲",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                } else {
                    items(items = filtered, key = { it.uri }) { item ->
                        TrackRow(
                            item = item,
                            thumbnail = thumbnail,
                            onClick = { onOpen(item.uri) },
                            onRemove = { onRemove(item.uri) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    item: TrackItem,
    thumbnail: suspend (String) -> Bitmap?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = thumbnail(item.uri)
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        cornerRadius = 16.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkCard(bitmap = bitmap, modifier = Modifier.size(56.dp), corner = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title?.takeIf { it.isNotBlank() } ?: item.displayName.substringBeforeLast('.'),
                    style = MiuixTheme.textStyles.title4,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.secondaryText,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FormatBadge(item.formatName)
                    Text(
                        text = Formats.duration(item.durationMs),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = Formats.fileSize(item.sizeBytes),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(MiuixIcons.More, contentDescription = "更多操作")
                }
                MiuixOverflowMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    items = listOf(
                        "编辑标签" to onClick,
                        "从列表移除" to onRemove,
                    ),
                )
            }
        }
    }
}
