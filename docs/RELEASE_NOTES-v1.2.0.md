# 音签 MusicTagger v1.2.0

第三个公开版本：**歌词编辑器重做 + 悬浮搜索按钮**。

## 下载

- `MusicTagger-v1.2.0.apk`（R8 压缩 + 资源裁剪，约 **1.5 MB**）
- 校验：`SHA256.txt` 与 APK 一同发布（SHA-256：`0DB5B748B06880C86B3E3FD995563D4BEB2260638CE6A9A993FA76DFAC4859F6`）
- 系统要求：Android 8.0（API 26）及以上；动态取色需 Android 12+

## 更新内容

### 歌词 Tab

- 歌词输入框改为**自动增高**：不再固定行数/高度，文本行越多输入框越高，滚动页面即可查看。
- 输入框使用**等宽字体**（Monospace），像日志 / 代码框一样对齐。
- 对 `[...]` 内的内容（LRC 时间轴、`[ti:]`、`[ar:]` 等元数据）进行**强调色高亮**。
- 输入框获得焦点时显示 1dp 强调色描边；未聚焦时为中性圆角背景。

### 悬浮搜索按钮

- 切换到「歌词」Tab 时，右下角保存 FAB 左侧出现一个**圆形搜索 FAB**，点击直接打开在线匹配；
  其他 Tab 不显示，避免拥挤。
- 「在线匹配所有歌曲信息」仍保持中性灰底按钮（不在页面里堆叠过多强调色）。

## 质量

- 154 个 `:core` 单元测试通过。
- release APK 已在 API 36 模拟器安装，歌词 Tab、悬浮搜索按钮、保存流程正常。
- 截图见仓库 `docs/screenshots/`。

## 已知限制

- Spotify 源仍未接入：官方 Web API 需 OAuth 且无歌词接口，匿名 token 接口被 403 拒绝。
- 在线接口为非官方公开接口，可能随服务端调整失效；支持自建 NeteaseCloudMusicApi。
- release 目前使用 debug 签名，便于直接安装。

## 开源

Apache License 2.0。项目地址：<https://github.com/Kastorice/MusicTagger>
