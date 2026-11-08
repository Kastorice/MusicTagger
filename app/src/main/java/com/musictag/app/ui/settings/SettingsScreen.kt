package com.musictag.app.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.musictag.app.BuildConfig
import com.musictag.app.R
import com.musictag.app.data.model.AppSettings
import com.musictag.app.ui.components.InfoRow
import com.musictag.app.ui.components.SectionCard
import com.musictag.core.net.MusicSourceId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onDynamicColorChange: (Boolean) -> Unit,
    onSourceChange: (MusicSourceId) -> Unit,
    onNeteaseApiBaseChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var apiBaseDraft by remember(settings.neteaseApiBase) { mutableStateOf(settings.neteaseApiBase) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("设置", style = MaterialTheme.typography.headlineSmall) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SectionCard(title = "外观", icon = R.drawable.ic_palette) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Material You 动态配色", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (dynamicColorSupported) {
                                    "使用壁纸颜色生成主题（Android 12+）"
                                } else {
                                    "当前系统版本不支持，将使用品牌配色"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.dynamicColor && dynamicColorSupported,
                            onCheckedChange = onDynamicColorChange,
                            enabled = dynamicColorSupported,
                        )
                    }
                }
            }
            item {
                SectionCard(title = "在线数据源", icon = R.drawable.ic_cloud_download) {
                    Text(
                        text = "默认使用以下平台搜索歌词与封面（非官方接口，仅供个人学习使用）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        MusicSourceId.entries.forEachIndexed { index, sourceId ->
                            SegmentedButton(
                                selected = settings.preferredSource == sourceId,
                                onClick = { onSourceChange(sourceId) },
                                shape = SegmentedButtonDefaults.itemShape(index, MusicSourceId.entries.size),
                            ) {
                                Text(sourceId.displayName)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("网易云音乐 API 地址（可选）", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = apiBaseDraft,
                        onValueChange = { apiBaseDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如 http://192.168.1.10:3000") },
                        supportingText = {
                            Text("直接访问网易云可能失败时，可填写自建 NeteaseCloudMusicApi 地址；留空使用默认接口。")
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onNeteaseApiBaseChange(apiBaseDraft) }) {
                        Text("保存数据源设置")
                    }
                }
            }
            item {
                SectionCard(title = "支持的音频格式", icon = R.drawable.ic_file_music) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormatChip("MP3 / AAC")
                        FormatChip("FLAC")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormatChip("OGG Vorbis")
                        FormatChip("OGG Opus")
                        FormatChip("M4A / MP4")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "不支持的文件会在导入时自动跳过；写入失败时会提示另存为副本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionCard(title = "关于音签", icon = R.drawable.ic_info) {
                    InfoRow("版本", BuildConfig.VERSION_NAME)
                    InfoRow("处理方式", "全部在本机完成")
                    InfoRow("歌词来源", "网易云音乐 / QQ 音乐")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "音签只修改你选择的音频文件的标签信息，不会上传或收集任何音频内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatChip(text: String) {
    Row(
        modifier = Modifier
            .padding(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.width(0.dp))
    }
}
