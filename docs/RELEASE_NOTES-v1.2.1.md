# 音签 MusicTagger v1.2.1

细节修复版本：**中性 Tab 栏 + 白色搜索 FAB**。

## 下载

- `MusicTagger-v1.2.1.apk`（R8 压缩 + 资源裁剪，约 **1.5 MB**）
- 校验：`SHA256.txt` 与 APK 一同发布（SHA-256：`64793B4A1C9E8B8EBE18B29B9D196BED7ED61B36F8F73417E9FDFAE70FC8E1AF`）
- 系统要求：Android 8.0（API 26）及以上；动态取色需 Android 12+

## 更新内容

- **Tab 栏去除强调色**：分段控件改为灰色轨道 + 白色选中滑块，选中文字为常规深色，
  不再使用主色填充；滑块在切换 Tab 时带 **220ms 位移动画**（`animateDpAsState`）。
  设置页数据源、在线匹配面板的数据源切换同步采用该中性样式。
- **搜索 FAB**：歌词 Tab 下保存 FAB 左侧的圆形搜索按钮改为**白底黑色搜索图标**，
  并使用与保存 FAB 相同的 `shadow(14dp)` + `Surface(shadowElevation = 12dp)` 阴影。
- 其他布局与功能不变。

## 验证

- debug / release 均构建通过；release APK 在 API 36 模拟器安装，
  三个 Tab、搜索 FAB、在线匹配面板显示正常，无崩溃。
- 154 个 `:core` 单元测试保持通过。

## 已知限制

- Spotify 源仍未接入：官方 Web API 需 OAuth 且无歌词接口，匿名 token 接口被 403 拒绝。
- 在线接口为非官方公开接口，可能随服务端调整失效。
- release 目前使用 debug 签名，便于直接安装。

## 开源

Apache License 2.0。项目地址：<https://github.com/Kastorice/MusicTagger>
