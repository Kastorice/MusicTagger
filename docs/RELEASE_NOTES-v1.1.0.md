# 音签 MusicTagger v1.1.0

第二个公开版本：**编辑页 Tabs、MiuiX 细节打磨、体积与滑动性能优化**。

## 下载

- `MusicTagger-v1.1.0.apk`（R8 压缩 + 资源裁剪，约 **1.5 MB**）
- 校验：`SHA256.txt` 与 APK 一同发布
- 系统要求：Android 8.0（API 26）及以上；动态取色需 Android 12+
- 安装：下载 APK 后允许「安装未知来源应用」，或 `adb install MusicTagger-v1.1.0.apk`

## 更新内容

### 编辑页 Tabs

- 「歌曲信息 / 歌词 / 文件信息」改为与设置页一致的分段控件 Tab；
- 专辑封面卡片保留在顶部，切换 Tab 时只组合当前内容，减少长页面与大量输入框的掉帧；
- 歌词 Tab 保留「已输入 N 个字符」与搜索图标，底部按钮不再被手势条/保存 FAB 遮挡。

### MiuiX 细节

- 进一步收紧全局字号与行距（大标题 29sp、卡片标题 16sp、正文 15sp、描述 13sp）；
- 曲库卡片更紧凑（缩略图 52dp、卡片内边距 11dp、间距 8dp），设置页分组间距 18dp；
- 「在线匹配所有歌曲信息」改为中性灰底按钮，避免与 Tab / 保存按钮叠加过多强调色；
- 设置页「关于音签」保持无分割线行式布局，GitHub 链接可点击。

### 性能与体积

- 缩略图解码尺寸 320px → 192px，并使用 RGB_565，解码与内存占用约减半；
- 缩略图 LRU 缓存 24 → 32 项，减少来回滚动时的重复 MediaMetadataRetriever 读取；
- 编辑页仅组合当前 Tab；
- 发布版开启 R8（`isMinifyEnabled` / `isShrinkResources`）、仅保留中英资源，
  APK 从 ~20 MB（debug）降到 **约 1.5 MB**（release）。

### Spotify 源

本次**未接入** Spotify：官方 Web API 必须 OAuth（需开发者 Client ID/Secret）且官方接口不提供歌词；
匿名 web-player token 接口在启用代理后仍被 Spotify 以 `403 URL Blocked` 拒绝，`/api/token` 也明确
返回不允许使用。因此保留网易云 / QQ 两个数据源，避免不可靠的抓取实现。

## 质量

- 154 个 `:core` 单元测试全部通过；
- 发布版 APK 在 API 36 模拟器安装运行，编辑 → 保存 → `mutagen` / `ffmpeg` 独立校验写回成功；
- 截图见仓库 `docs/screenshots/`。

## 已知限制

- 在线接口为非官方公开接口，可能随服务端调整失效；支持自建 NeteaseCloudMusicApi 回退。
- 封面写入采用单一前置封面策略；少数特殊容器（分片 MP4、链式 OGG）不支持写入。
- release 目前使用 debug 签名，便于直接安装；正式分发前应配置发布签名。

## 开源

Apache License 2.0。项目地址：<https://github.com/Kastorice/MusicTagger>
