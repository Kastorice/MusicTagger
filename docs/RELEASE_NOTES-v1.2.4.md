# 音签 MusicTagger v1.2.4

交互打磨版本：**可伸缩保存 FAB、更优雅的 Tab 动画**。

## 下载

- `MusicTagger-v1.2.4.apk`（R8 压缩 + 资源裁剪，约 **1.5 MB**）
- 校验：`SHA256.txt` 与 APK 一同发布（SHA-256：`7CEFBFF367C86187013F6489446FEE3E5ABF8B795B69C9D6C37AD8DBF461E4C4`）
- 系统要求：Android 8.0（API 26）及以上；动态取色需 Android 12+

## 更新内容

- **保存 FAB 可伸缩**：
  - 页面在顶部时展开为「💾 保存到文件」胶囊；向下滚动后自动收缩为圆形软盘图标，
    回到顶部再平滑展开。
  - 使用 `animateContentSize` + `AnimatedVisibility(expand/shrinkHorizontally + fade)` 做伸缩动画，
    260ms 标准缓动；保存图标替换为软盘样式矢量图。
  - 无修改时点击仍提示「没有需要保存的修改」。
- **分段 Tab 动画优化**：滑块位移改为 `spring(dampingRatio=0.82, stiffness=420)` 的弹性滑动，
  选中/未选中文字颜色用 180ms 颜色过渡；仍保持无阴影、无强调色。
- 切换到其它 Tab 时列表平滑回到顶部，保存 FAB 随之展开。

## 验证

- release APK 在 API 36 模拟器安装：编辑页顶部 FAB 展开、滚动后收缩为圆形、回顶展开均正常；
  Tab 切换动画正常；154 个 `:core` 单测保持通过。

## 已知限制

- 位深/编码等由系统 MediaExtractor 提供，个别容器可能返回“未知”。
- Spotify 源未接入（OAuth + 无歌词接口）。
- release 目前使用 debug 签名，便于直接安装。

## 开源

Apache License 2.0。项目地址：<https://github.com/Kastorice/MusicTagger>
