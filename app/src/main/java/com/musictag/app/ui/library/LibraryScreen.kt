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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musictag.app.R
import com.musictag.app.data.model.LibraryUiState
import com.musictag.app.data.model.TrackItem
import com.musictag.app.ui.components.ArtworkCard
import com.musictag.app.ui.components.EmptyState
import com.musictag.app.ui.components.FormatBadge
import com.musictag.app.ui.components.GradientHero
import com.musictag.app.util.Formats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
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

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("音签", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "本地音乐标签编辑器",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            actions = {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新列表")
                }
                var addMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { addMenuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = addMenuExpanded,
                        onDismissRequest = { addMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("从所有文件导入") },
                            onClick = {
                                addMenuExpanded = false
                                onAddAllFiles()
                            },
                        )
                    }
                }
            },
        )
        if (state.refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (state.items.isEmpty() && !state.loading) {
            EmptyState(
                icon = R.drawable.ic_music_note,
                title = "还没有音乐",
                description = "从本地选择 .mp3 / .flac / .ogg / .opus / .m4a / .aac 音频文件，\n即可编辑标题、歌手、专辑、歌词与封面。",
                actionLabel = "选择音频文件",
                onAction = onAddClick,
                secondaryActionLabel = "从「所有文件」中导入",
                onSecondaryAction = onAddAllFiles,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "hero") {
                    GradientHero(
                        title = "收藏 ${state.items.size} 首歌曲",
                        subtitle = "标签与封面将直接写入本地文件 · 不上传任何音频",
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索标题、歌手或专辑") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = CircleShape,
                    )
                }
                if (filtered.isEmpty()) {
                    item(key = "empty-filter") {
                        Text(
                            text = "没有匹配「$query」的歌曲",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkCard(bitmap = bitmap, modifier = Modifier.size(58.dp), corner = 14.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title?.takeIf { it.isNotBlank() } ?: item.displayName.substringBeforeLast('.'),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FormatBadge(item.formatName)
                    Text(
                        text = Formats.duration(item.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = Formats.fileSize(item.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑标签") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("从列表移除") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}
