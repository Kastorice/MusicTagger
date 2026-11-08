package com.musictag.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musictag.app.R
import com.musictag.app.data.model.MatchInfo
import com.musictag.app.data.model.SearchUiState
import com.musictag.app.ui.components.ArtworkCard
import com.musictag.app.ui.components.MiuixPrimaryButton
import com.musictag.app.ui.components.MusicTagTextField
import com.musictag.app.ui.components.NetworkCover
import com.musictag.app.util.Formats
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import com.musictag.app.ui.components.MiuixSegmentedControl
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import com.musictag.core.net.Lrc
import com.musictag.core.net.MusicSourceId
import com.musictag.core.net.RemoteSong

/**
 * MIUI style bottom sheet for online lyric / cover matching.
 */
@Composable
fun OnlineMatchSheet(
    show: Boolean,
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
    OverlayBottomSheet(
        show = show,
        title = "在线匹配",
        endAction = {
            IconButton(onClick = onDismiss) {
                Icon(MiuixIcons.Close, contentDescription = "关闭")
            }
        },
        onDismissRequest = onDismiss,
    ) {
        var keywordDraft by remember(show) { mutableStateOf(state.keyword) }
        LaunchedEffect(show) {
            if (show) keywordDraft = state.keyword
        }
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MusicTagTextField(
                    value = keywordDraft,
                    onValueChange = { keywordDraft = it },
                    label = "歌曲名 / 歌手",
                    modifier = Modifier.weight(1f),
                    labelAsPlaceholder = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onKeywordChange(keywordDraft)
                        onSearch()
                    },
                    enabled = !state.searching,
                    cornerRadius = 14.dp,
                    minHeight = 46.dp,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("搜索", style = MiuixTheme.textStyles.button) }
            }
            Spacer(Modifier.height(10.dp))
            MiuixSegmentedControl(
                options = MusicSourceId.entries.map { it.displayName },
                selectedIndex = MusicSourceId.entries.indexOf(state.sourceId).coerceAtLeast(0),
                onSelected = { index ->
                    onKeywordChange(keywordDraft)
                    onSourceChange(MusicSourceId.entries[index])
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            if (state.error != null && state.selectedSong == null) {
                Text(
                    text = state.error,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            val selected = state.selectedSong
            if (selected == null) {
                ResultsArea(state = state, onSelect = onSelect)
            } else {
                MatchArea(
                    state = state,
                    previewCover = previewCover,
                    onBackToResults = onBackToResults,
                    onApply = onApply,
                )
            }
        }
    }
}

@Composable
private fun ResultsArea(
    state: SearchUiState,
    onSelect: (RemoteSong) -> Unit,
) {
    when {
        state.searching -> Box(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(10.dp))
                Text(
                    "正在搜索 ${state.sourceId.displayName}…",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        state.results.isEmpty() -> Box(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "输入歌曲名或歌手开始搜索",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items = state.results, key = { it.source.name + it.id }) { song ->
                ResultRow(song = song, onClick = { onSelect(song) })
            }
        }
    }
}

@Composable
private fun ResultRow(song: RemoteSong, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetworkCover(url = song.coverUrl, modifier = Modifier.size(44.dp), corner = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MiuixTheme.textStyles.body1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.subtitle,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = Formats.duration(song.durationMs),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun MatchArea(
    state: SearchUiState,
    previewCover: suspend (ByteArray, Int) -> Bitmap?,
    onBackToResults: () -> Unit,
    onApply: (Boolean, Boolean, Boolean) -> Unit,
) {
    val match: MatchInfo? = state.match
    var applyMetadata by remember(match?.song?.id) { mutableStateOf(true) }
    var applyLyrics by remember(match?.song?.id) { mutableStateOf(true) }
    var applyCover by remember(match?.song?.id) { mutableStateOf(true) }

    val coverBitmap by produceState<Bitmap?>(initialValue = null, key1 = match?.cover?.bytes) {
        value = match?.cover?.bytes?.let { previewCover(it, 320) }
    }

    if (state.loadingMatch) {
        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "正在获取歌词与封面…",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        return
    }

    if (match == null) {
        Text(
            text = "未找到匹配结果",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(vertical = 20.dp),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                if (coverBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = coverBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MiuixTheme.colorScheme.secondaryContainer),
                    )
                } else {
                    ArtworkCard(
                        bitmap = null,
                        modifier = Modifier.size(96.dp),
                        corner = 16.dp,
                        placeholderIcon = R.drawable.ic_album,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = match.song.title,
                        style = MiuixTheme.textStyles.title4,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = match.song.subtitle,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = match.song.source.displayName,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (match.error != null) {
                Text(
                    text = match.error,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text("歌词预览", style = MiuixTheme.textStyles.subtitle)
            Spacer(Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 150.dp),
                shape = RoundedCornerShape(12.dp),
                color = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                val plain = if (match.hasLyrics) {
                    Lrc.plainText(match.lyrics?.lrc).lineSequence()
                        .filter { it.isNotBlank() }
                        .take(8)
                        .joinToString("\n")
                } else {
                    ""
                }
                Text(
                    text = if (plain.isBlank()) "未找到歌词，可尝试切换数据源或换一个搜索结果。" else "$plain\n…",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        item {
            CheckboxPreference(
                title = "填充歌曲信息",
                summary = "${match.song.title} · ${match.song.artists}",
                checked = applyMetadata,
                onCheckedChange = { applyMetadata = it },
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
            )
            CheckboxPreference(
                title = "应用歌词",
                summary = if (match.hasLyrics) "将写入 LRC 歌词标签" else "该来源没有歌词",
                checked = applyLyrics && match.hasLyrics,
                onCheckedChange = { applyLyrics = it },
                enabled = match.hasLyrics,
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
            )
            CheckboxPreference(
                title = "应用专辑封面",
                summary = if (match.hasCover) "将替换当前封面" else "该来源没有封面",
                checked = applyCover && match.hasCover,
                onCheckedChange = { applyCover = it },
                enabled = match.hasCover,
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(text = "返回结果", onClick = onBackToResults, minHeight = 40.dp)
        Spacer(Modifier.weight(1f))
        MiuixPrimaryButton(
            text = "应用到编辑内容",
            onClick = {
                onApply(
                    applyMetadata,
                    applyLyrics && match.hasLyrics,
                    applyCover && match.hasCover,
                )
            },
        )
    }
}
