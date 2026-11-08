package com.musictag.app.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musictag.app.BuildConfig
import com.musictag.app.data.model.AppSettings
import com.musictag.app.ui.components.MiuixSegmentedControl
import com.musictag.app.ui.components.MusicTagTextField
import com.musictag.core.net.MusicSourceId
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onDynamicColorChange: (Boolean) -> Unit,
    onSourceChange: (MusicSourceId) -> Unit,
    onNeteaseApiBaseChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var apiBaseDraft by remember(settings.neteaseApiBase) { mutableStateOf(settings.neteaseApiBase) }
    val sources = MusicSourceId.entries
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // No explicit save button: persist once the user stops editing.
    LaunchedEffect(apiBaseDraft) {
        if (apiBaseDraft.trim() != settings.neteaseApiBase.trim()) {
            delay(700)
            onNeteaseApiBaseChange(apiBaseDraft)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item {
                SettingsGroup(title = "外观") {
                    SwitchPreference(
                        checked = settings.dynamicColor && dynamicColorSupported,
                        onCheckedChange = onDynamicColorChange,
                        title = "Material You 动态配色",
                        summary = if (dynamicColorSupported) {
                            "使用壁纸颜色生成 MIUI 风格主题（Android 12+）"
                        } else {
                            "当前系统版本不支持，将使用 MIUI 默认配色"
                        },
                        enabled = dynamicColorSupported,
                    )
                }
            }

            item {
                SettingsGroup(title = "在线数据源") {
                    BasicComponent(
                        title = "默认搜索平台",
                        summary = "用于歌词与封面的在线匹配（非官方接口）",
                    )
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                        MiuixSegmentedControl(
                            options = sources.map { it.displayName },
                            selectedIndex = sources.indexOf(settings.preferredSource).coerceAtLeast(0),
                            onSelected = { index -> onSourceChange(sources[index]) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        MusicTagTextField(
                            value = apiBaseDraft,
                            onValueChange = { apiBaseDraft = it },
                            label = "网易云音乐 API 地址（可选）",
                            singleLine = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "直连失败时可填写自建 NeteaseCloudMusicApi 地址，例如 http://192.168.1.10:3000；留空使用默认接口。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item {
                SettingsGroup(title = "支持的音频格式") {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FormatChip("MP3")
                            FormatChip("AAC")
                            FormatChip("FLAC")
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FormatChip("OGG Vorbis")
                            FormatChip("OGG Opus")
                            FormatChip("M4A")
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "不支持的文件会在导入时自动跳过；写入失败时会提示另存为副本。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item {
                SettingsGroup(title = "关于音签") {
                    BasicComponent(title = "版本", summary = BuildConfig.VERSION_NAME)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    BasicComponent(title = "作者", summary = "松川 & DeepSeek")
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SmallTitle(
            text = title,
            modifier = Modifier.wrapContentHeight(),
            // Align with the card's inner content (card margin 16 + row inset 16),
            // matching the HyperOS/LSPosed settings layout.
            insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
        )
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
            Column { content() }
        }
    }
}

@Composable
private fun FormatChip(text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSecondaryContainer,
        )
    }
}
