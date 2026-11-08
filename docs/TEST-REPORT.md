# 验证报告 · 音签 MusicTagger

日期：2026-11-01 · 环境：Windows 10.0.26300、JDK 21.0（`D:\Java\jdk-21`）、
Android SDK API 36、Gradle 8.14.3、AGP 8.13.0、Kotlin 2.2.20、ffmpeg 8.1。
模拟器：`dsh_api36`（google_apis x86_64，Android 16 / API 36，1080×2400 @420dpi，WHPX 加速）。

## 1. 单元测试

命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -Tasks ":core:test",":app:assembleDebug"
```

结果：**BUILD SUCCESSFUL**，`:core:test` 共 **154 个测试、0 失败、0 错误、0 跳过**（15 个测试类）。

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

## 2. 模拟器端到端验证

安装 `app-debug.apk` 后，全部操作通过真实 UI（`adb shell input` + `uiautomator` 定位）完成；
写回结果从设备拉回宿主机，用 **Python mutagen 1.48.1**（独立实现）与 **ffmpeg/ffprobe** 校验。

| 格式 | 用例 | 写前 → 写后 | 独立校验结果 |
|---|---|---|---|
| MP3 | 打开「晴天」（ffmpeg 生成的无封面文件）→ 在线匹配 QQ 音乐官方结果 → 应用歌词+封面 → 保存 | 34,231 B → 118,438 B | `title=晴天 artist=周杰伦 album=叶惠美 year=2003 track=3`；`cover_count=1`（JPEG 81,439 B）；`lyrics_count=1`（LRC，含 `[ti:][ar:][al:]` 与时间轴）；`ffmpeg -f null -` 退出码 0；时长 2.038 s |
| FLAC | 打开「夜曲」→ 本地图片选择器换封面 → 保存 | 28,433 B → 33,602 B | `title=夜曲 artist=周杰伦 album=十一月的萧邦`；`cover_count=1`（JPEG 4,511 B，替换成功）；`ffmpeg` 解码 0；时长 2.0 s；ffprobe 显示 `attached_pic` |
| OGG Vorbis | 打开「稻香」→ 在线匹配 QQ 音乐 → 应用歌词+封面 → 保存 | 10,668 B → 118,591 B | `title=稻香 artist=周杰伦`；`lyrics_count=1`（LRC）；`cover_blocks=1`（METADATA_BLOCK_PICTURE，105,436 B base64）；`ffmpeg` 解码 0；流为 vorbis + mjpeg |
| M4A / AAC | 打开「七里香」→ 修改标题为「七里香 Remix」→ 保存 | 37,111 B → 37,117 B | `title=七里香 Remix artist=周杰伦 album=七里香`；`cover_count=1`（原封面保留）；`ffmpeg` 解码 0（证明 `stco` 偏移修正正确）；时长 2.021 s |

在线源连通性（设备内实测，2026-11-01）：

- 网易云音乐：`POST /api/cloudsearch/pc` 搜索成功；`/api/song/lyric` 返回 LRC；封面 URL 可下载并嵌入。
- QQ 音乐：`smartbox_new.fcg` 搜索成功 → `fcg_play_single_song.fcg` 解析专辑/时长 →
  `fcg_query_lyric_new.fcg` 返回 LRC → `y.gtimg.cn` 封面下载并嵌入。

### 验证中发现并修复的问题

1. 在线封面原始尺寸约 500×500 / 951 KB，直接嵌入会把 34 KB 的 MP3 变成 988 KB。
   修复：应用在线封面时统一经 `ArtworkRepository.normalizeImage` 缩放到 900 px 内并重新编码，
   实测同一封面变为 81 KB。
2. 系统图片选择器在 Android 13+ 会把 `GET_CONTENT image/*` 重定向到照片选择器（无法浏览本地目录）。
   修复：改用 `ACTION_OPEN_DOCUMENT`（`OpenDocument` 契约），可从任意目录选择图片。
3. 歌词预览一次渲染全文，面板溢出。修复：预览截取前 12 行并加省略号。

## 3. 构建产物

- `app/build/outputs/apk/debug/app-debug.apk`（约 11 MB，debug 签名，minSdk 26 / targetSdk 36）
- `dist/MusicTagger-v1.0.0-debug.apk`（交付副本）

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
