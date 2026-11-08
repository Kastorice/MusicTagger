# 验证报告 · 音签 MusicTagger（MIUI / HyperOS 版）

日期：2026-11-07 · 环境：Windows 10.0.26300、JDK 21.0（`D:\Java\jdk-21`）、
Android SDK API 36 + Platform 37、Gradle 8.14.3、AGP 8.13.0、Kotlin 2.3.20、Compose 1.10.3、
MiuiX 0.9.0、ffmpeg 8.1。
模拟器：`dsh_api36`（google_apis x86_64，Android 16 / API 36，1080×2400 @420dpi，WHPX 加速）。

## 1. 单元测试

命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -Tasks ":core:test",":app:assembleDebug"
```

结果：**BUILD SUCCESSFUL**，`:core:test` 共 **154 个测试、0 失败、0 错误、0 跳过**（15 个测试类）。
升级到 Kotlin 2.3.20 / Compose 1.10.3 / MiuiX 0.9.0 后重新执行，全部通过。

| 测试类 | 数量 | 说明 |
|---|---:|---|
| `tag.mp3.Mp3FormatTest` | 16 | ID3v2 读取、写入、封面、清空、AAC |
| `tag.mp3.Id3v2EdgeCaseTest` | 20 | v2.2/v2.3/v2.4、unsync、扩展头、编码、异常 |
| `tag.flac.FlacFormatTest` | 15 | 块解析、注释/封面、块保留、损坏输入 |
| `tag.ogg.OggFormatReadTest` + `WriteTest` | 8 + 11 | 页/CRC、Vorbis/Opus、封面、大歌词多页、音频页不变 |
| `tag.mp4.Mp4FormatReadTest` + `WriteTest` | 8 + 13 | ilst 读写、moov 前后置、stco 偏移、幂等 |
| `core.net.*`（6 个类） | 58 | 网易云/QQ 搜索·歌词·封面解析、MiniJson、LRC、HTTP 错误映射（录制真实响应 + 注入式传输层，无外网依赖） |
| `tag.TagEditorTest` | 3 | 门面 API、Artwork MIME 嗅探 |
| `tag.FormatIntegrationTest` | 2 | **跨格式门面往返**：同一份中文标签 + 封面在 6 个容器（mp3/flac/ogg/opus/m4a/aac）上写读一致、清空、ffmpeg 解码 |

标签格式测试的交叉验证手段（不依赖被测实现）：外部 `ffprobe` 读取
`format_tags`/`attached_pic` 流；`ffmpeg -i <file> -f null -` 退出码为 0；音视频时长不变；
FLAC 的 STREAMINFO/SEEKTABLE 与音频尾部字节哈希一致；Ogg 用独立 Python 解析器校验
CRC/序列号/音频页字节一致。

## 2. 模拟器端到端验证（MIUI 版重新执行）

安装 `app-debug.apk` 后，全部操作通过真实 MIUI 风格 UI（`adb shell input` + `uiautomator` 定位）完成；
写回结果从设备拉回宿主机，用 **Python mutagen 1.48.1**（独立实现）与 **ffmpeg/ffprobe** 校验。

| 格式 | 用例 | 写前 → 写后 | 独立校验结果 |
|---|---|---|---|
| MP3 | 打开「晴天」→ 在线匹配 QQ 音乐官方结果 → 应用歌词+封面 → 保存 | 34,231 B → 118,438 B | `title=晴天 artist=周杰伦 album=叶惠美 year=2003 track=3`；`cover_count=1`（JPEG 81,439 B）；`lyrics_count=1`（LRC）；`ffmpeg -f null -` 退出码 0；时长 2.038 s |
| MP3（文本编辑 / 保存路径） | MIUI 浮动标签输入框把标题改为「晴天 Miui」→ 触发放弃修改对话框 → 继续编辑 → 保存 | 118,438 B → 118,448 B | mutagen 读取 `title=晴天 Miui`，其余标签与封面不变，`ffmpeg` 解码 0 |
| FLAC | 打开「夜曲」→ 本地图片换封面 → 保存 | 28,433 B → 33,602 B | `title=夜曲 artist=周杰伦 album=十一月的萧邦`；`cover_count=1`（JPEG 4,511 B）；`ffmpeg` 解码 0；ffprobe 显示 `attached_pic` |
| OGG Vorbis | 打开「稻香」→ QQ 音乐匹配 → 应用歌词+封面 → 保存 | 10,668 B → 118,591 B | `title=稻香 artist=周杰伦`；`lyrics_count=1`；`cover_blocks=1`（105,436 B）；`ffmpeg` 解码 0 |
| M4A / AAC | 打开「七里香」→ 标题改「七里香 Remix」→ 保存 | 37,111 B → 37,117 B | `title=七里香 Remix`；原封面保留（2,390 B）；`ffmpeg` 解码 0（证明 `stco` 偏移修正正确） |

MIUI UI 专项验证（均为真机/模拟器截图确认）：

- 大标题顶栏、MIUI 底部导航（曲库 / 设置）、胶囊 FAB、分组卡片与灰色输入框正常渲染。
- 编辑器浮动标签（标题/歌手/专辑/年份/音轨/流派/歌词内容）在字段有值时仍完整显示。
- `OverlayBottomSheet` 在线匹配面板：网易云 / QQ 音乐切换、搜索、结果列表、歌词与封面预览、
  MIUI `CheckboxPreference` 三项应用选项全部可见，`应用到编辑内容` 生效。
- `OverlayDialog` 放弃未保存修改对话框正常弹出与取消。
- `SwitchPreference` 开启 Material You 动态取色后，界面即时切换为壁纸派生配色；关闭恢复 MIUI 蓝。
- 保存成功后 Snackbar 提示、未保存修改底栏、返回/放弃逻辑均正常。

### 迁移中修复的问题

1. MiuiX 0.9.0 需要 `compileSdk 37`（AAR metadata），本机仅装 API 36：安装 SDK Platform 37
   并配置 `android.suppressUnsupportedCompileSdk=37,37.0`，用 `platforms/android-37` 目录映射兼容 AGP 8.13。
2. MiuiX 的 `OverlayBottomSheet` 依赖 `NavigationEventDispatcherOwner`：将 `activity-compose`
   升级到 1.12.0（`ComponentActivity` 实现该 owner）后崩溃修复。
3. MiuiX 弹窗/底部面板必须位于 `Scaffold` 内容内部才能拿到 `LocalDialogStates`，否则静默不显示。
4. `TextField` 的 `useLabelAsPlaceholder=true` 会在有值时隐藏标签：编辑器改为浮动标签模式。
5. 上一版发现并保留的修复：在线封面过大（951 KB → 81 KB 自动缩放重编码）、图片选择器改用
   `ACTION_OPEN_DOCUMENT`、歌词预览截断。

### 本轮 UI 细节打磨（2026-11-08）

- 编辑页：歌曲信息全部改为单列全宽输入框；歌词「在线匹配」改为卡片右下角文本按钮；移除顶栏保存，
  改为右下角胶囊扩展 FAB（有未保存修改时出现，实测写入 `title=晴天R` 成功、`ffmpeg` 解码 0）；
  封面卡片只保留「在线匹配所有歌曲信息」图标 + 文字按钮；点击封面弹出替换对话框
  （在线匹配 / 本地选择 / 移除当前封面）；删除底部「所有修改只写入你选择的本地文件」；
  返回箭头与顶栏垂直居中。
- 设置页：移除多余顶栏标题与「保存数据源设置」按钮（输入停顿 700 ms 自动保存，已用 `run-as`
  校验 SharedPreferences 写入与清空）；分组标题与设置卡片左边缘对齐；使用自绘全圆角、
  蓝色填充的分段控件替代 TabRow（选中态更突出、无白色残角）；辅助说明文字统一为
  body2（与设置项描述同字号）；「关于音签」只保留版本 1.0.0 与作者「松川 & DeepSeek」。
- 曲库页：删除「本地音乐 / 共 N 首」信息行；搜索图标与占位文字增加间距；右上角按钮增大到
  52 dp 并加大间距；「更多」菜单改为 MiuiX 原生 `OverlayListPopup`；右下角 + 图标改为白色。
- 所有改动均在 API 36 模拟器逐项操作并截图验证（`docs/screenshots/01`–`09`），
  154 个单元测试保持全绿。

### 第三轮视觉打磨（2026-11-08，参考 HyperOS / LSPosed / KernelSU）

- 全局字号按参考图压缩：`title1 32→28sp`、`title3 20→18`、`title4 18→16`、`main/body1 17/16→15/14`、
  `body2 14→13`、`footnote1 13→12`、`button 17→14`，列表标题与描述明显更紧凑（统一在
  `MusicTagTheme` 的 `CompactTextStyles` 中覆盖，不逐个页面硬编码）。
- 设置页改为参考图布局：大标题「设置」+ 版本号副标题；分组标题与卡片内文左边缘对齐；
  分组间距 22 dp；保留分组卡片 / 开关 / 分段控件；字号与行距同步缩小。
- 曲库搜索框改为参考图的全圆角胶囊，并改为自绘 `MiuixSearchField` 以精确控制间距：
  46 dp 高、左右内边距 20 dp、图标 20 dp、图标与占位文字间距 14 dp（MiuiX `TextField` 的
  `leadingIcon` 插槽会贴左边缘，已弃用该插槽，改用 `Row + BasicTextField` 自绘）。
- 「更多」菜单在参考 KernelSU 菜单后改为右上角锚定的白色圆角卡片（200 dp 宽、16 dp 圆角、
  8 dp 阴影、距屏幕边缘 12 dp），菜单项 20×14 dp 内边距，不再贴屏幕边；打开时主界面压暗
  （30% 黑色遮罩），点空白处关闭。
- 应用图标重做：白底 + 提供的 `queue_music` SVG 线稿；glyph 按 66 dp 自适应图标安全圆的
  黄金比例（61.8% ≈ 40.8 dp）缩放居中，而不是占满 108 dp 画布；自适应图标不声明 `<monochrome>`，
  避免启动器把图标整体做成主题色（系统「App info」中确认白底黑 glyph；若启动器开启主题图标，
  仍可能按系统主题着色，属系统行为）。
- 验证：模拟器逐项截图 `docs/screenshots/01`–`10`；`:app:assembleDebug` 通过。

### 第四轮交互补充（2026-11-08）

- 曲库卡片在「更多」左侧新增播放图标（`MiuixIcons.Play`，40 dp 触控区），点击通过
  `ACTION_VIEW + FLAG_GRANT_READ_URI_PERMISSION` 调起系统「选择打开方式」选择器；
  模拟器中已确认弹出 YouTube Music / 音签等候选，见 `docs/screenshots/11-open-with.png`。
- 「更多」菜单加入展开/收起动画：`Animatable` 驱动 140 ms 内 alpha 0→1、scale 0.88→1，
  变换原点在右上角；关闭时 120 ms 缩小淡出后再移除 Popup。
- 歌词卡片：去掉「歌词内容」浮动标签（`MusicTagTextField(hideLabel = true)`）；
  「在线匹配」由文字按钮改为搜索图标按钮，并与左侧「已输入 N 个字符」在同一行垂直居中对齐。
- 验证：曲库/编辑页重新截图；`:app:assembleDebug` 通过。

### 第五轮反馈修复（2026-11-08）

**Bug 修复**

1. 修改封面后曲库不更新：`ArtworkRepository` 的缩略图缓存 key 只有 URI，保存后没有失效；
   且列表行 `produceState` 以 URI 为 key，不会重新加载。
   - `TrackRepository.save()` 成功后调用 `ArtworkRepository.invalidateThumbnail(uri)`，
     并给 `TrackItem.modifiedAt` 打时间戳；`refreshItem()` 也会 bump，刷新/保存后列表 key 变化即重新解码。
   - `LibraryViewModel.refresh()` 先清空缩略图缓存。
   - 实测：QQ 音乐应用新封面 → 保存 → 返回曲库立即显示新封面（无需手动刷新）；
     手动推送替换文件后点刷新也能更新（随后恢复测试素材）。
2. 在线匹配输入框光标跳到最后一个字符前：原输入框直接绑定 ViewModel 中的字符串，回写导致
   selection 复位。改为面板内本地草稿 `keywordDraft`，只在点「搜索」或切换数据源时提交；
   实测输入 `123` 得到 `周杰伦 晴天123`，光标保持在末尾。
3. 在线匹配抽屉底部按钮与小白条重叠：给面板内容加 `navigationBarsPadding()`；
   实测「返回结果 / 应用到编辑内容」位于手势条上方。
4. 字号/字重：重新校准 `CompactTextStyles`（标题 30 sp、正文 15、描述 13、footnote 11-12、
   按钮 15），分组标题改为 13 sp 常规字重灰色；卡片标题 16 sp Medium；设置页与参考图更接近。

**建议落实**

- 删除在线匹配面板顶部的说明文案。
- 编辑页「文件信息」扩展为：格式、时长、大小、比特率（MediaMetadataRetriever）、采样率、
  声道（MediaExtractor）、MIME、文件名、SAF 路径（如「内部存储/Music/sunny.mp3」）。
- 编辑页各分组标题移到白色卡片上方（专辑封面/歌曲信息/歌词/文件信息），与设置页一致。
- 曲库搜索框右侧增加清除按钮（有输入时显示）。
- 「保存到文件」FAB 常驻右下角、加大并增加阴影；无修改时 Toast/Snackbar「没有需要保存的修改」。
- 「关于音签」按参考图排版：版本 / 作者两行 + GitHub 行（右侧 chevron，点击打开
  `https://github.com/Kastorice/MusicTagger`）。
- 应用图标 glyph 下移，使三条横杠在圆形安全区内视觉居中。
- 验证：截图更新为 `docs/screenshots/01`–`13`；`:app:assembleDebug` 通过，154 个单测保持全绿。

### 第六轮搜索与细节调整（2026-11-08）

- 曲库搜索改为参考 KernelSU 搜索页：搜索框（带内嵌清除按钮）+ 右侧「取消」，
  输入实时过滤；**无结果时列表区域完全留空**（删除原来的「没有匹配…」占位文字）。
  截图：`docs/screenshots/14-search.png`、`15-search-empty.png`。
- 设置页「关于音签」去掉所有分割线，改为与「文件信息」一致的行式布局
  （BasicComponent：16 sp Medium 标题 + 13 sp 描述，行内边距 16×10 dp），GitHub 行保留右侧 chevron。
  截图：`docs/screenshots/13-about.png`。
- 「保存到文件」胶囊 FAB 增加真实绘制阴影：`Modifier.shadow(14 dp)` + `Surface(shadowElevation = 12 dp)`，
  常驻右下角。截图：`docs/screenshots/02-editor.png`。
- 验证：`:app:assembleDebug` 通过；模拟器逐项截图确认。

## 3. 构建产物

- `app/build/outputs/apk/debug/app-debug.apk`（约 19.4 MB，debug 签名，minSdk 26 / targetSdk 36）
- `dist/MusicTagger-v1.0.0-debug.apk`（交付副本，SHA-256 见 `dist/SHA256.txt`）

## 4. 复现步骤

```powershell
# 1) 安装依赖 / 构建 + 跑全部测试
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -Tasks ":core:test",":app:assembleDebug"

# 2) 启动模拟器
powershell -NoProfile -ExecutionPolicy Bypass -File tools\emulator.ps1 -Action start

# 3) 安装并生成中文测试音频
adb -s emulator-5558 install -r app\build\outputs\apk\debug\app-debug.apk
powershell -NoProfile -ExecutionPolicy Bypass -File tools\e2e\prepare-media.ps1

# 4) 截图
powershell -NoProfile -ExecutionPolicy Bypass -File tools\emulator.ps1 -Action screenshot -OutFile screenshots\current.png
```
