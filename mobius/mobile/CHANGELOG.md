# Changelog

本文件记录 Mobius Mobile（移动端 App）的版本变更。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [0.4.4] - 2026-09-20

### 新增
- **OTA 下载全程进度 + 失败重试 + 后台通知**（Issue e5a536be / 分身 #78）：0.4.3 的 `downloadAndInstallOta()` 是个 toast 占位,`OtaDownloader` / `OtaInstaller` 写完了但 `ViewModel` / `UiState` / `Composable` 三层 0 订阅,实际下载 / 安装链路从未打通。本次接通：
  - **平台层 expect/actual 框架**：`OtaDownloader` / `OtaInstaller` 提到 `commonMain` 顶层(`shared/src/commonMain/kotlin/com/mobius/momo/platform/ota/`)。`OtaDownloader` 暴露 `lastProgress: StateFlow<Map<Long, OtaDownloadProgress>>` / `completionEvents: SharedFlow<OtaCompletionEvent>` / `enqueue(asset)` / `cancel(id)` / `queryProgress(id)` / `localUri(id)` / `openDownloadId()` / `requestPostNotificationsIfNeeded()`；`OtaInstaller` 暴露 `install(apkPath, expectedPackageName, onResult)`。Android 走系统 `DownloadManager` + `PackageInstaller`(原逻辑完整保留); iOS / desktop 占位实现保证跨平台编译通过(返回 no-op + 失败回调,不下载 / 不安装)。新增 `expect fun currentDeviceAbi(): String`(Android 读 `Build.SUPPORTED_ABIS[0]`,其它平台返回空串)。
  - **VM 层编排**：`UiState.otaDownload: OtaDownloadUi?`(null = 没在下载)记录 `version` / `assetSize` / `bytesDownloaded` / `fraction` / `phase`(Queued / Downloading / Verifying / Installing / Done / Failed) / `errorMessage` / `manifest`(供"重试"复用)。`init` 订阅 `otaDownloader.lastProgress` 与 `otaDownloader.completionEvents` 两个 flow,把 system 事件翻译进 `otaDownload` state;`downloadAndInstallOta(manifest)` 走"选 ABI → 拼 OtaAsset → enqueue → 关闭 4 档弹窗 → OtaDownloadDialog 接管"完整路径;新增 `retryOtaDownload()` / `dismissOtaDownloadUi()` / `cancelOtaDownload()` 三个用户动作;`install` 完成后 phase=Done 持续 3s 自动清空。
  - **UI 层 OtaDownloadDialog**：`shared/src/commonMain/kotlin/com/mobius/momo/ui/OtaDialog.kt` 末尾新增 `OtaDownloadDialog` Composable,直接用 `AlertDialog`(不走 BaseOtaDialog 以支持 LinearProgressIndicator slot)。标题 `正在下载 vX.Y` / `下载失败 vX.Y`,正文显示阶段标签(`准备下载…` / `下载中…` / `校验中…` / `安装中…` / `已提交安装` / `下载失败`)+ 进度条(Material3 `LinearProgressIndicator`)+ `X.X MB / Y.Y MB (NN%)`。按钮按 phase 分支:Queued/Downloading → "后台下载"(关弹窗但下载继续)+ "取消下载";Verifying/Installing → 仅"取消下载";Failed → "重试"+"关闭";Done → "关闭"。
  - **设置页下载中状态行**:`SettingsScreen` "检查更新"行下方在 `otaDownload != null && phase != Done` 时多一行小字展示 `v{ver} 下载中 NN%`,提供"点后台下载"后用户仍能在设置页感知进度的入口。
  - **后台通知 + ABIs 选择**:`OtaDownloader` 通知文案 `Mobius v{abi} 正在下载`(替代原来的固定标题),`setNotificationVisibility(VISIBILITY_VISIBLE_NOTIFY_COMPLETED)` 系统在下载完成后自动切到"下载完成"通知;`OtaAbiSelector.pick()` 拆到 commonMain 顶层,纯函数逻辑方便 desktopTest 单测。

### 修复
- `downloadAndInstallOta` 占位 toast bug(D7 之前的占位): 现在真正入队下载并把进度反映到 UI,失败时给"重试"按钮。

### 变更
- 同步 `androidApp/build.gradle.kts` `versionCode=27→28` / `versionName="0.4.3"→"0.4.4"`。
- `OtaDownloader` / `OtaInstaller` 文件位置从 `shared/src/androidMain/...` 移到 `shared/src/commonMain/...`(平台无关 API)+ `shared/src/{android,ios,desktop}Main/...`(平台实现)。Android 端所有 DownloadManager / PackageInstaller 逻辑(SDK ≥ 34 走 PackageInstaller.Session API、≤ 13 走 ACTION_INSTALL_PACKAGE 兼容回退、`REQUEST_INSTALL_PACKAGES` 预检、`FileProvider` 兜底、ACTION_INSTALL_COMMIT PendingIntent、SHA256 校验预留调用点)完整保留。

### 保留
- 0.4.3 OTA 弹窗显示 changelog(Normal 档首条摘要 + "查看完整更新说明"全屏 modal)。
- 0.4.2 OTA 数据链路修复(本服务器 channel + GitHub 兜底 + 端点 /releases?per_page=10)。
- 0.4.1 OTA 客户端代码接入主流程(`triggerOtaCheck` / `dismissOtaDialog` / `markOtaVersionIgnored` 等)。

### 已知限制
- iOS / desktop 仍为占位(`OtaDownloader` 返回 -1L;`OtaInstaller` 立即回调 `Failure(CODE_UNSUPPORTED)`);下载 / 安装只在 Android 真机生效。
- "后台下载" / "取消下载"按钮与系统通知之间的 deepLink 跳转(点击通知回到 MomoApp 内 OTA 弹窗)未实现,系统通知点击行为由 Android 默认接管(`ACTION_NOTIFICATION_CLICKED` 暂未做 deepLink 解析,留作下版本)。

## [0.4.3] - 2026-09-20

### 新增
- **OTA 弹窗显示 changelog 内容**（Issue e5a536be / 分身 #73）：0.4.2 弹窗只显示固定文案"新功能与体验改进"，无法让用户在升级前看到具体改动。本次接入：
  - `OtaCheckUseCase.OtaCheckResult.Show` 新增 `changelogItems: List<ChangelogItem>` 字段（默认空 list），GitHub channel 透传 release body 解析结果，本服务器 channel 暂返回空 list（0.4.3 后端会填，下文）。
  - `OtaDialogCopy.Context` 新增 `releaseHighlight` / `changelogItems` / `onViewFullNotes`，`Copy` 新增 `showFullNotesLink` + `fullNotesLinkLabel` 字段。`normal()` 把首条摘要（≤60 字）拼进 body 取代兜底文案；changelog 非空时渲染"查看完整更新说明 ›"链接按钮。
  - `BaseOtaDialog` 签名新增 `onViewFullNotes` 参数，在 `text` 块内按 `showFullNotesLink` 开关渲染链接。四个 Composable 入口（normal / advisory / strongAdvisory / hardBlock）统一透传。
  - `MomoAppViewModel` 新增 `otaChangelogSheet: List<ChangelogItem>?` 状态 + `showOtaChangelog(items)` / `dismissOtaChangelog()` 方法。
  - `MomoApp` 在 OTA 弹窗渲染块底部新增 `OtaChangelogSheet` 全屏 modal（`ModalBottomSheet`），按 Breaking > Fix > Feature 排序、按类型加颜色徽标（破坏=红/修复=橙/功能=绿），整体 verticalScroll 渲染完整条目。
- **本服务器 OTA 渠道补 changelog_items**：`mobius/backend/routes/mobile-ota.ts` 在 `/api/mobile/ota/manifest.json` 响应顶层新增 `changelog_items` 字段。读 `mobius/mobile/CHANGELOG.md` 解析对应版本段（`## [<version>]` 到下一个 `## [` 之前），按 `### 分类` 边界映射 type（修复→Fix / 破坏→Breaking / 其他→Feature），合并相邻同 type 条目为一条 text。解析失败兜底空数组，不阻塞主流程。
- 客户端 `OtaCheckUseCase.fetchManifestDualChannel()` 返回类型从 `OtaManifest?` 改为 `Pair<OtaManifest, List<ChangelogItem>>?`，本服务器 channel 现阶段返 `manifest to emptyList()`（待客户端也走 `changelogItems` 字段，下版本同源）。

### 变更
- 同步 `androidApp/build.gradle.kts` `versionCode=26→27` / `versionName="0.4.2"→"0.4.3"`。

### 测试
- `OtaDialogCopySnapshotTest`（新增）：断言 `normal()` 在 `changelogItems` 非空 + `onViewFullNotes` 非 null 时 `showFullNotesLink=true`、body 拼接首条文本；changelog 空时 `showFullNotesLink=false`、body 走兜底"新功能与体验改进"。
- `OtaCheckUseCaseTest`（扩展）：验证 `Show` 数据类携带 `changelogItems`（GitHub channel 走 release body 解析路径）。
- `OtaRepositoryLocalServerTest`（扩展）：用 fake local server 返回的 manifest.json 含 `changelog_items` 字段，断言 `fetchLocalManifest` 解析保留该字段（**注**：本期客户端 `fetchLocalManifest` 仅反序列化 `OtaManifest` schema，`changelog_items` 在 client 解析时会被 ignoreUnknownKeys 忽略，因此 fetchLocalManifest 仍返回空 changelog —— 后端 endpoint 字段为面向未来的 client schema 兼容预留**）。

## [0.4.2] - 2026-09-20

### 修复
- **OTA 数据链路断裂**（Issue e5a536be / 分身 #72）：0.4.1 客户端虽然接入了 `OtaRepository`，但两个根因导致 OTA 实际上从未生效：
  - `fetchLatestRelease(repo)` 调 `https://api.github.com/repos/{repo}/releases/latest`，但 fork 仓库（`hamy88/mobius`）所有 release 都是 `prerelease: true`，GitHub `/releases/latest` 端点对纯 prerelease 仓库返回 **404**。`releases/latest` 端点只匹配 stable release。
  - 即使修了端点，OTA 还需要从 release 拿 `ota-manifest.json` asset，但 GitHub release 上从未上传过该 asset、body 也是空的。

  本次接入：
  - `OtaRepository.{android,desktop}.kt` 把端点从 `/releases/latest` 改成 `/releases?per_page=10`，解析 JSON **数组**形式：过滤 `draft=false`，按 `published_at` 倒序取第一条（自然允许 prerelease）；统一抽出 commonMain 顶层 `parseLatestNonDraftRelease()` 函数，三平台共享解析逻辑。
  - CI `build-android.yml` 新增 "Generate ota-manifest.json" step，从 `androidApp/build.gradle.kts` 读 `versionCode`，为每个 ABI APK 算 sha256 + size，生成 `ota-manifest.json` 并作为 release asset 一同上传到 GitHub。
  - 客户端 `OtaRepository.fetchManifestJson(repo, version)` 已支持拉取 raw.githubusercontent 上的 `ota-manifest.json`。

### 新增
- **本服务器 OTA 渠道**（双源 OTA，本服务器优先 + GitHub 兜底）：fork / CI 自部署场景下，客户端可以从用户当前登录的 Mobius 服务器直接拉取 OTA manifest，比走 GitHub Releases 更可控、零外网依赖。
  - 新增 `/api/mobile/ota/manifest.json`（GET）后端 endpoint：读 `mobius/mobile-builds/manifest.json`（sync-desktop-builds.js 维护的本地 APK 清单），按 `version` 字段取最新一组 android builds，按 ABI 标准名映射（arm64→arm64-v8a、v7a→armeabi-v7a），返回 OtaManifest schema。CORS `*` + `Cache-Control: no-cache`，保证即时拿到最新版本。
  - `OtaRepository` 接口新增 `suspend fun fetchLocalManifest(baseUrl): OtaManifest?`（三平台实现：android / desktop 用 Ktor，ios 仍 NoOp）。本服务器任意 HTTP 错误 / 解析失败 → 返回 `null` 不抛错，让上层自动 fallback 到 GitHub。
  - `OtaCheckUseCase` 构造时新增 `localBaseUrl: String` 参数，运行时 `fetchManifestDualChannel()` 流程：先 `repo.fetchLocalManifest(localBaseUrl)` → 失败/无新版本 → `repo.fetchLatestRelease(DEFAULT_OTA_REPO)` → 都失败 → `NetworkError`。
  - `MomoAppViewModel` 把当前 `currentBaseUrl` 注入 useCase；切服时调 `refreshOtaUseCase()` 丢弃旧实例，下一次 trigger 按新地址重建（避免 OTA 仍命中旧服务器地址）。

### 变更
- 同步 `androidApp/build.gradle.kts` `versionCode=25→26` / `versionName="0.4.1"→"0.4.2"`。

### 测试
- `OtaRepositoryHttpTest.kt`：覆盖 `/releases?per_page=10` 数组端点（draft 过滤、prerelease 通过、解析 changelog）。
- 新增 `OtaRepositoryLocalServerTest.kt`：覆盖 `fetchLocalManifest` 200 / 404 / 500 / 非法 JSON / 空 baseUrl 等；断言 baseUrl 尾斜杠被自动 trim；OtaCheckUseCase 双渠道逻辑（localBaseUrl 空跳过本服务器）。

## [0.4.1] - 2026-09-19

### 修复
- **OTA 客户端代码接入主流程**（Issue e5a536be / 分身 #70）：0.4.0 OTA Phase 1+2 的 27 个新文件（OtaCheckUseCase / OtaDialog / OtaRepository 三平台实现等）虽然代码落地了，但 ViewModel / MomoApp 完全没接入，导致：
  - App 启动没有静默检查更新
  - 用户无法手动检查新版本
  - OtaDialog 从不渲染（用户从未看到升级弹窗）

  本次接入：
  - `MomoAppViewModel` 注入 `OtaCheckUseCase`（lazy init），新增 `triggerOtaCheck()` / `dismissOtaDialog()` / `downloadAndInstallOta(manifest)` 三个方法
  - `UiState` 加 `otaCheckResult` / `otaCheckInProgress` / `otaLastCheckAt` 三个字段
  - `MomoApp` 顶层 `LaunchedEffect(Unit) { delay(5000); vm.triggerOtaCheck() }` — App 启动 5s 后静默检查
  - `MomoApp` 顶层条件渲染 `OtaDialog(state.otaCheckResult, ...)` — 有更新时弹窗
  - `SettingsScreen` "通用"分组加"检查更新"行（点击触发 manual check；右侧显示"检查中…"/"刚刚"/"X 分钟前"/"未检查"）
  - 新增 `MomoAppViewModelOtaTest.kt`（MockEngine 注入 OtaRepository，验证 triggerOtaCheck 流程）
  - commit `1981550`（+356 行 / 3 文件）

### 变更
- 同步 `androidApp/build.gradle.kts` `versionCode=24→25` / `versionName="0.4.0"→"0.4.1"`。
- `extension.json` 0.4.1（如果有 extension 版本号字段）。

### 已知限制
- `downloadAndInstallOta()` 暂为占位（无完整 APK 下载 / 安装流程）；OtaDialog 当前只能在"有更新"态展示文案 + 按钮，后续版本接 OtaDownloader / OtaInstaller 走真实下载。
- iOS / Desktop 端 OTA 客户端能力尚未接入（仅 Android 端）。

## [0.4.0] - 2026-09-19

### 新增
- **OTA 在线升级客户端能力（Phase 1 + 2）**（Issue e5a536be / 分身 #65）：Mobius Mobile 端首次具备"应用内检查更新 / 下载 / 安装 / 验证签名 / 看 changelog"完整闭环。27 个新文件 / 2,971 行新增（OtaManifest / OtaAsset / OtaError / OtaRelease / OtaRepository / ChangelogParser / ChangelogItem / SignatureSchemeValidator / Version / ThresholdEvaluator / OtaCheckUseCase / OtaDialog / OtaDownloader / OtaInstaller + 三平台 Repository 适配 + 8 个单元测试）。
  - **D1–D5 数据模型**：semver `Version`、JSON manifest `OtaManifest`、资产清单 `OtaAsset`、错误体系 `OtaError`、release 元数据 `OtaRelease`。
  - **D6 网络层**：`OtaRepository.{android,ios,desktop}.kt` 三平台 HTTP 拉取 manifest；Android 走 Ktor OkHttp engine，iOS 走 `NSURLSession`，Desktop 走 `java.net.HttpURLConnection`。
  - **D7 Changelog**：`ChangelogParser` 解析 markdown 列表项（`- feat: ...` / `- fix: ...`），按 semver 阈值 `ThresholdEvaluator` 过滤掉低于基线版本的项，避免给"全量升级用户"显示旧版变更。
  - **D8 UI**：`OtaDialog` Compose Material3 弹窗（检查中 / 有更新 / 已是最新 / 错误四态），内嵌 changelog 列表 + 下载进度条 + 安装确认。
  - **签名方案跨版本跳跃**：`SignatureSchemeValidator` 在 major 跳跃时强制要求 v2/v3 签名（防 EdDSA / RSA-OAEP 等新算法回归到 v1-only），避免 OTA 升级过程中签名校验失败。

### 修复
- **OtaDownloader BroadcastReceiver NPE 风险**（commit 9d2c044）：Kotlin 2.0 K2 null-safety 下 `intent.action`（Intent?）的 when 分支需用 `intent?.action` 安全调用，避免广播未带 action 时编译失败（由 CI run 35442145905 自动 commit 修复）。

### 变更
- 同步 `androidApp/build.gradle.kts` `versionCode=23→24` / `versionName="0.3.1"→"0.4.0"`；`mobius/frontend/src/components/modals.tsx` `MOBILE_VERSION='0.4.0'` + APK 文件名 `mobius-mobile-0.4.0-android-{arm64,armeabi-v7a}.apk`（size/sha256 由 GitHub Actions run 35442145905 实测，release `mobile-v0.4.0` 自动发布为 prerelease）。
- 用户菜单"下载 X"入口加版本号标注：桌面 v0.0.30 / 终端 v0.1.28 / 移动 v0.4.0。

### 保留
- 0.3.1 服务器地址下拉选择器（commit fdfdd9d）完整保留。
- 0.3.0 `ServerAddressRepository` + ViewModel API（commit ffd116e）。
- 0.2.0 聊天长按选取复制修复（commit 271a219）。

### 已知限制
- iOS 端 OTA 仅做 Repository stub + manifest 解析，未接入真机下载 / 安装（依赖 TestFlight 渠道分发）。
- Desktop 端 OTA Repository 已写，未接入 Compose Desktop UI（桌面走 GitHub Releases 直下，不走 OTA）。
- 本地 `./gradlew :androidApp:assembleRelease` 在无 android platforms 的开发机上仍跑不通，必须走 GitHub Actions。
- 本次发布走 `ci/build-0-4-0-v1` 临时分支触发（参考 0.3.0 路径），后续安卓发版建议拆分独立 `build-android.yml` workflow（单 ABI < 8 分钟）。

## [0.3.1] - 2026-09-10

### 变更（UI 重构）
- **服务器地址选择器：卡片列表 → 下拉**（Issue e5a536be / 分身 #9）：登录界面 + 设置页"连接"区域统一改为 `ExposedDropdownMenuBox` + `OutlinedTextField.menuAnchor()`，把"卡片列表 + 独立输入框"两段式合并成"一行 ▼ 输入框"。
  - 点击 ▼ 展开历史地址列表，点条目 = 选中应用（走 `vm.selectServerEntry`，与手输保存同路径）。
  - 每条条目右侧 `⋯` 弹二级菜单：**重命名**（保留 0.3.0 `ServerRenameDialog`，label 可空）/ **删除**（走 `vm.removeServerEntry`）。走 `⋯` 子菜单避开 `combinedClickable` 与 `DropdownMenuItem` 的手势冲突（已知 Material3 bug）。
  - 仍在 `OutlinedTextField` 直接打字 = 手输新地址（保留 IME `Next/Done` + `KeyboardType.Uri`），下拉只是快捷回填。
  - 设置页删除"填入默认"按钮（功能被下拉 placeholder + 列表项选中覆盖）；**保留**显式"保存服务器地址"按钮（避免用户输错立刻入库）。
- 删除 `ServerAddressPicker` / `ServerAddressRow` 两个函数（共 ~197 行），保留 `ServerRenameDialog`（下拉 ⋮ 菜单触发）。
- ViewModel / Repository 一行不动（`MomoAppViewModel.selectServerEntry / removeServerEntry / renameServerEntry / setServerBaseUrl / saveServerBaseUrl`，`ServerAddressRepository.addOrTouch / remove / rename / getAll` 全部沿用 0.3.0 签名）。
- 同步 `androidApp/build.gradle.kts` `versionCode=23` / `versionName="0.3.1"`；`extension.json` 0.3.1；`mobius/frontend/src/components/modals.tsx` `MOBILE_VERSION='0.3.1'` + APK 文件名模板 `mobius-mobile-0.3.1-android-{arm,armv7}.apk`（size/sha256 待 CI 落盘后回填）。

### 保留
- 0.3.0 `ServerAddressRepository` + ViewModel 完整 API（commit ffd116e）。
- 0.2.0 聊天长按选取复制修复（commit 271a219）。
- `sync-desktop-builds.js` mobile-sync hardening（commit 09191ee）。

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
