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
adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
powershell -NoProfile -ExecutionPolicy Bypass -File tools\e2e\prepare-media.ps1

# 4) 截图
powershell -NoProfile -ExecutionPolicy Bypass -File tools\emulator.ps1 -Action screenshot -OutFile screenshots\current.png
```
