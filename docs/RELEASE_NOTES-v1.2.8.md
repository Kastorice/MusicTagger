# 音签 MusicTagger v1.2.8

功能更新：**编辑页左右滑动切 Tab、MusicBrainz 数据源、设置字号微调**。

## 下载

- `MusicTagger-v1.2.8.apk`（R8 压缩 + 资源裁剪，约 **1.5 MB**）
- 校验：`SHA256.txt` 与 APK 一同发布（SHA-256：`DC314015C2328C6AE0CBB808F8D977F6A8E815017A0162E5CA7D425F3D1C16C4`）
- 系统要求：Android 8.0（API 26）及以上；动态取色需 Android 12+

## 更新内容

- **左右滑动切换 Tab**：编辑标签页支持在「歌曲信息 / 歌词 / 文件信息」之间左右滑动切换，
  仍可点击分段控件切换；滑动不会影响上下滚动与文本编辑。
- **新增 MusicBrainz 数据源**：
  - 使用官方 `ws/2/recording` 搜索录音，填充标题、歌手、专辑、时长；
  - 封面从 **Cover Art Archive** 获取（专辑无封面时返回空，不报错）；
  - MusicBrainz 不提供歌词，匹配面板会明确显示「该来源没有歌词」；
  - 遵循官方 API 规范：自定义 User-Agent、约 1 次/秒限流，并对 429/503 自动重试两次。
  - 设置页数据源下拉与在线匹配面板均新增 MusicBrainz。
- **设置字号微调**：设置页文字整体放大约 5%，行高与卡片高度同步适配，其余页面字号不变。

## 验证

- release APK 在 API 36 模拟器安装：左右滑动可切换三个 Tab；MusicBrainz 搜索成功返回结果；
  设置页下拉包含三个数据源，字号增大后布局正常。
- 新增 MusicBrainz 单元测试，`:core` 共 **159** 个测试全部通过。

## 已知限制

- MusicBrainz 无歌词，且公共 API 限流约 1 次/秒；频繁搜索可能短暂返回限流提示。
- release 目前使用 debug 签名，便于直接安装。

## 开源

Apache License 2.0。项目地址：<https://github.com/Kastorice/MusicTagger>
