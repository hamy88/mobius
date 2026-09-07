# Changelog

本文件记录 Mobius Mobile（移动端 App）的版本变更。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [0.1.7] - 2026-07-28

首个 GitHub Release。

### 新增
- **项目列表顶部 tabbar**：全部 / 活跃（最近 7 天内有活动）/ 收藏（星标）/ 扩展（`kind=extension`），分段药丸样式；选中态提升到 `UiState` 跨导航持久。
- **项目列表下拉刷新**：Material3 `PullToRefreshBox`，刷新转圈设最短展示 800ms，避免一闪而过。
- **进入扩展应用**：项目 → 扩展 tab 点开扩展，即在 App 内用 WebView 打开其 web 前端（`/extension/<name>/`），自动注入 `cc-token` 鉴权（Android `onPageFinished` 注入 + reload；iOS `WKUserScript` documentStart 注入）。
- **APK 按 ABI 分包**：`arm64-v8a` / `armeabi-v7a` 独立包，供 Mobius 主体「下载移动端 App」分发。
- 极光推送（JPush）聚合推送 + 华为 HMS 厂商通道（App 被杀也能收）。

### 修复
- **聊天 markdown 表格未解析**：`org.jetbrains.markdown` 的 GFM 解析要求表格块前有空行，LLM 输出常省略 → 渲染前 `ensureBlankLineBeforeGfmTable` 补空行（幂等）。列表无此问题。
- **从扩展应用返回停在「扩展」tab**：`selectedTab` 提升到 `UiState` 跨导航持久，`openExtension` 时设为「扩展」。

### 变更
- GitHub Actions `build-all-formats` 改为仅手动触发（`workflow_dispatch`），避免在 main 上长期失败时每次 push 产出失败 run。

### 已知限制
- iOS 端 in-app WebView 代码已写，待 Mac 编译验证（`NSURLRequest` 桥接参数名一处可能需调整）。
- Release 包为 debug 签名（未配置 release keystore）；覆盖安装若签名不一致需先卸载旧版。
