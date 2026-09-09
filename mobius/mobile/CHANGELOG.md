# Changelog

本文件记录 Mobius Mobile（移动端 App）的版本变更。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [0.3.0] - 2026-09-09

### 新增
- **登录界面服务器地址列表选择器**（Issue e5a536be / 分身 #6）：多服务器用户切换免重输。
  - 新增 `ServerAddressRepository`（`shared/.../data/ServerAddressRepository.kt`）：基于 `SecureStorage.savePreference/getPreference` 持久化（Android=EncryptedSharedPreferences、iOS=NSUserDefaults、desktop=java.util.prefs），刻意不引入新 expect/actual —— 三平台零样板复用同一 KV 通道；JSON 单 key 存整个列表，按 `lastUsedAt` 倒序。
  - **登录成功后自动保存**当前服务器地址（`MomoAppViewModel.loginWith` 调 `addOrTouch`），无手动"+ 保存"按钮；再次登录同一地址仅刷新时间戳并置顶。
  - 登录页列表非空时在服务器地址输入框上方显示 `ServerAddressPicker` 卡片列表：**点击行=选中应用**（走与手输保存相同的 `applyServerBaseUrlInput` 路径，含切换服务器清 token/重建 API）、**左滑=删除**（`SwipeToDismissBox`，与聊天列表删除同范式）、**长按=重命名**（`AlertDialog` 备注名 label，可空；设置后列表主显 label 副显 URL）。当前地址行高亮并标「当前」。
  - 列表为空时保持原有纯输入框 UX 不变（首次使用零打扰）。
  - 登录页整体改为 `verticalScroll + imePadding`：列表较长或小屏时账号/密码/登录按钮不被挤出屏幕。
- 版本号：`extension.json` 0.3.0；`androidApp/build.gradle.kts` `versionCode=22` / `versionName="0.3.0"`。

### 保留
- 0.2.0 聊天长按选取复制修复（commit 271a219）不受影响；服务器地址列表行的 `combinedClickable` 复用同一 API 形态。

## [0.2.0] - 2026-09-09

### 修复
- **聊天内容无法长按选取复制**（Issue 96dd6585）：1v1 私聊与群聊气泡此前用 `pointerInput { detectTapGestures(onTap, onLongPress) }`，长按手势被外层 Box 消费，加上消息文本/ Markdown 未包 `SelectionContainer`，原生选区工具栏弹不出，长按只能触发手动「复制」按钮。
  修复方案（最小 diff）：
  - `MomoApp.kt:MessageRow` / `GroupMessageRow` 将外层 `detectTapGestures` 替换为 `Modifier.combinedClickable(interactionSource, indication = null, onClick, onLongClick)`，`onLongClick` 与 `SelectionContainer` 的选区手势并存，长按文本优先弹原生选区工具栏（选取/全选/复制），长按空白边距仍保留「复制/播放」按钮兜底。
  - 消息正文（用户文本 / 助手 `MarkdownMessageBody`）统一包入 `SelectionContainer { ... }`，1v1 与群聊两条路径都已覆盖。
- 同步把 `androidApp/build.gradle.kts` 的 `versionCode=21` / `versionName="0.2.0"`，覆盖安装时系统可识别。

### 已知限制
- 本地环境无 Android SDK platforms（仅 build-tools）+ gradle 8.8 分发下载慢，`./gradlew :androidApp:assembleRelease` 未能跑完。0.2.0 双 ABI APK 由 GitHub Actions `build-all-formats`（手动触发）补打。

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
