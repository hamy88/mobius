# 小莫助理移动端

Kotlin Multiplatform + Compose Multiplatform 版小莫助理移动客户端。工程放在
`mobius/extension/momo-mobile/`，同时也是一个 Mobius extension 项目，便于在
Mobius 里看到、进入和继续开发。

## 基本介绍

小莫助理移动端 App 是 Mobius AI Agent 操作系统的随身入口。它把 Web 端的小莫主助理、
分身 Session、Agent 实时进度、语音输入、附件上传、语音播报和消息提醒收进移动端，
让用户不必一直守在电脑前，也能随时查看 Agent 状态、补充指令、创建分身并接收关键进展。
它不是一个独立的聊天机器人应用，而是连接到既有 Mobius 服务器的移动客户端；同时，小莫
也可以像团队成员一样加入项目群聊，在被点名后基于群聊上下文和授权工具承接任务、同步
进度，让用户继续专注其它工作。

## 核心特点

### 随身 Agent 工作台

小莫移动端直接连接 Mobius 服务器，让用户在手机上向 Agent 发送文字、语音和附件，
随时派发任务、补充指令并查看执行状态；Agent 的回复、历史消息、实时文本和工具调用
进度会同步回流到移动端。

### 小莫群聊

小莫不只是私聊助手，也可以像团队成员一样加入项目群、任务频道或临时讨论组。在被授权
的上下文和工具范围内，用户可以直接在群聊里点名小莫，让它整理结论、创建任务、排查
问题或跟进进度；小莫则把执行过程和需要确认的问题同步回群里，让 AI 协作自然发生在
团队原本的对话流中。

当前代码侧已经具备会话、分身、实时进度和提醒等基础能力；完整的真人团队群聊入口仍
需要后续和 Mobius Web、项目频道或外部 IM 做进一步打通。

### 进度提醒与语音播报

当其它 Session 或分身小莫完成任务、失败中断或产生关键进展时，进度会同步回主小莫会话，
用户可在移动端看到汇总提醒，并在后台收到消息通知；同时，客户端支持按住说话转文字和
自动播报小莫回复，可选择朗读整条消息或只朗读关键结论，让用户在移动场景中及时掌握
Agent 进展。

### 移动优先的交互

界面采用微信式对话布局：顶部显示当前小莫/分身名称，底部输入栏支持附件、文字输入、
语音输入和发送/停止按钮。消息内容支持 Markdown、代码块、表格、引用、复制和单条重播。
设置页支持深浅色切换、主题色、服务器地址、消息推送、自动播报、小莫预设和退出登录。

## 使用说明

### 1. 登录

1. 打开 App 后先确认“服务器地址”。可以填写自建 Mobius 服务地址，也可以点击“填入默认”
   使用推荐地址。
2. 输入 Mobius 用户名。
3. 如果服务器要求密码，点击“下一步”后输入密码；如果服务器是免密模式，直接点击“登录”。
4. 登录成功后，App 会保存当前服务器地址和 JWT。下次打开时会自动尝试恢复登录状态；
   如果 token 过期或服务器地址变化，需要重新登录。

注意：服务器地址必须以 `http://` 或 `https://` 开头。登录失败时优先检查服务器地址、
用户名/密码和当前 Mobius 服务是否可访问。

### 2. 消息聊天

#### 小莫对话

登录后默认进入“我的主小莫”。主小莫适合处理日常入口型任务，例如：

- 查询当前项目、Issue、Session 状态；
- 让小莫帮忙创建任务单、拆分需求、整理计划；
- 让小莫把一个任务派给分身或提醒用户确认；
- 上传图片、PDF、文档等附件，让小莫结合附件继续分析。

发送消息时可以直接输入文字并点击发送。Agent 正在处理时，输入栏会出现停止按钮，可以
中断当前活跃 Session。右上角菜单可进入“分身列表”“清空对话”“设置”和“关于”。

#### 同事对话

这里的“同事”指同一 Mobius 工作区里的其它 Agent Session，主要表现为分身小莫。进入
“分身列表”后，点击任意分身即可切换到它的对话页，继续查看它的执行状态或补充指令。

分身会话与主小莫共享同一个小莫任务单上下文，但每个分身都有独立 Session、模型和任务
描述。适合让不同分身分别做代码实现、资料检索、测试验证、文案整理等专项工作。

当前版本中，带附件的消息会优先转给主小莫处理；纯文本消息可以直接发给当前分身 Session。

#### 群聊

当前版本没有单独的“新建群聊房间”界面。移动端的群聊雏形是“同一任务单下的主小莫 +
多个分身小莫”：

- 主小莫承担协调和总入口；
- 分身小莫承担专项任务；
- 用户可以在分身列表中切换不同 Agent 的对话；
- 每个 Agent 的完成、失败、运行中状态会在列表中展示。

如果要模拟一个项目群聊，可以围绕同一个任务连续创建多个分身，例如“分身小莫 #1 - 需求
梳理”“分身小莫 #2 - 前端实现”“分身小莫 #3 - 测试验收”。这样手机端就能像查看团队
成员一样查看各个 Agent 的工作进度。

### 3. 创建群聊或协作会话

当前推荐做法是创建一组分身来组成协作会话：

1. 在主小莫页面点击右上角菜单。
2. 进入“分身列表”。
3. 点击底部“+ 开分身”。
4. 填写分身名称，例如“分身小莫 #1 - 查资料”。
5. 填写任务描述。任务描述应尽量具体，包括目标、背景、输出格式和验收标准。
6. 选择模型。
7. 点击“+ 创建并启动”。

创建成功后，App 会在当前小莫任务单下创建独立 Session，并把任务描述作为第一条消息
发送给该分身。用户可以继续回到主小莫，也可以留在分身对话里观察执行过程。

### 4. 创建分身

分身适合以下场景：

- 把一个大任务拆成多个并行子任务；
- 让不同模型分别给出方案或校审结果；
- 让一个分身长期跟进某个项目方向；
- 在主小莫忙于协调时，把执行型任务交给独立 Agent。

创建分身时，建议任务描述采用“背景 + 目标 + 限制 + 交付物”的结构。例如：

```text
请检查 momo-mobile 当前 README 的使用说明是否完整。
重点关注登录、主小莫对话、分身创建、语音播报和消息提醒。
不要修改代码，只输出问题清单和建议改法。
```

### 5. 通用设置

设置页从主界面右上角菜单进入。

#### 语音播报

“自动播报”打开后，小莫产生新回复时会自动朗读。可配置：

- 播报范围：“全部”会朗读整条消息；“只朗读关键结论”只朗读小莫标记出的关键语音内容；
- 音色：“系统默认”使用设备系统 TTS；如果服务器提供豆包音色列表，也可以选择具体音色；
- 播放控制：播报时底部会出现控制条，可跳到正在播报的消息、暂停播报或关闭自动播报。

语音输入在聊天页底部切换。点击麦克风图标进入语音模式，按住“按住说话”开始录音，松手
后自动识别并发送；上滑松手会取消本次语音输入。

#### 消息提醒

“消息推送”打开后，App 会在后台收到小莫新回复时发出本地提醒。Android 端首次开启时
会申请通知权限，并尝试启动前台保活服务、引导用户关闭电池优化限制。若系统拒绝通知
权限，App 会提示“通知权限未开启，暂无法后台提醒”。

提醒只在 App 不在前台时触发；如果用户正停留在对话页面，消息会直接显示在当前界面。
iOS 当前通知实现仍是占位，后续需要接入系统通知权限和 APNs/本地通知策略。

#### 外观与账号

- “暗色模式”可在浅色和深色之间切换；
- “外观”可切换主题色；
- “连接”可修改和保存 Mobius 服务器地址；
- “小莫预设”可修改主小莫的人设和模型，保存后会影响下一次主小莫 Session；
- “退出登录”会清除本地 token，并回到登录页。

## 当前范围与边界

第一版覆盖：

- 登录：直接采用 Mobius 账号系统，读取 `/api/auth/config` 判断是否需要密码，
  再调用 `/api/auth/login` 获取 JWT。
- 我的主小莫：HTTP 发送消息，SSE 接收 history、typing、jsonl_entry 和 server_error。
- 分身列表：读取小莫会话，创建分身 Session，并用 `/api/sessions/:id/messages` 启动。
- 设置：暗色模式、消息推送、自动播报、播报音色、服务器地址、账号信息和退出登录。

当前版本已经支持按住说话、服务端语音识别、图片/普通文件附件上传，以及文字/语音
输入模式切换。Android 端已经接入本地消息提醒；iOS 通知、完整文件管理、离线附件缓存、
多账号切换和独立真人群聊仍未完成。

## 目录

```text
momo-mobile/
├── extension.json
├── backend/extension_backend_handler.js
├── frontend/                 # Mobius extension 的说明页
├── shared/                   # KMP shared module
│   └── src/commonMain/kotlin/com/mobius/momo/
│       ├── data/             # Ktor client、SSE、SecureStorage 抽象
│       ├── domain/           # User、Project、Issue、Session、Message
│       ├── ui/               # Compose 主题、页面和组件
│       └── viewmodel/        # StateFlow 状态管理
├── androidApp/               # Android applicationId com.mobius.momo
├── iosApp/                   # iOS Swift 壳入口
└── desktopPreview/           # Linux/macOS/Windows 桌面预览，复用 commonMain
```

## API 与服务器地址

正式应用不再把 `https://mobius.example.com` 作为固定服务端。服务器地址按以下顺序
解析：

1. 用户在“设置 → 服务器”中保存的地址；
2. 构建参数或运行环境中的 `MOMO_BASE_URL`；
3. 空值。此时登录页会引导用户先配置服务器地址。

Android/CI 构建时可以使用：

```bash
./gradlew -PMOMO_BASE_URL=https://mobius.your-domain.example :androidApp:assembleDebug
```

桌面端也可以在运行时使用环境变量 `MOMO_BASE_URL` 或 JVM 参数
`-Dmomo.base.url=https://mobius.your-domain.example`。服务器地址不是密码或 token，
可以存放为 GitHub repository variable；登录密码、JWT 和 API key 不会编译进客户端。

主要接口：

- `POST /api/auth/login`
- `GET /api/auth/me`
- `GET /api/assistant/workspace`
- `POST /api/assistant/messages`
- `GET /api/assistant/sessions`
- `GET /api/sessions/:id/events`
- `POST /api/issues/:issueId/sessions/`
- `POST /api/sessions/:id/messages`

注意：当前 Mobius 账号系统没有 `/api/auth/challenge`。客户端不再做 salt/challenge
兼容，始终以 Mobius `/api/auth/config` 和 `/api/auth/login` 为准；cloud-17 当前返回
`password_required=false`，输入用户名即可登录。

## 安全存储

- Android：`EncryptedSharedPreferences`。
- iOS：当前 Linux 环境无法验证 Keychain cinterop，先通过 `SecureStorage`
  抽象接入 `NSUserDefaults` 可运行实现；替换点是
  `shared/src/iosMain/kotlin/com/mobius/momo/Platform.ios.kt`。

## 构建环境

- Java 17；
- 项目自带 Gradle 8.8 Wrapper；
- Android SDK 35 和 Build Tools；
- Windows EXE/MSI 必须在 Windows 构建；
- macOS DMG 和 iOS Simulator `.app` 必须在 macOS/Xcode 构建；
- iOS 工程生成还需要 XcodeGen。

所有命令都从本目录执行，不依赖全局 Gradle。

### Android

```bash
./gradlew --no-daemon :shared:allTests
./gradlew --no-daemon :androidApp:test
./gradlew --no-daemon :androidApp:assembleDebug
```

Debug APK：

```text
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Release 只有在 `MOMO_ANDROID_KEYSTORE_PATH`、`MOMO_ANDROID_KEYSTORE_PASSWORD`、
`MOMO_ANDROID_KEY_ALIAS` 和 `MOMO_ANDROID_KEY_PASSWORD` 全部存在时才签名。GitHub
Actions 接受 Base64 keystore secret 并在 runner 临时目录解码；仓库不保存 keystore。

### Windows 和 macOS 正式桌面应用

正式入口是 `desktopApp`，直接显示 `MomoApp()`，不会显示 `desktopPreview` 的刘海、
状态栏和 Home indicator。

Windows：

```powershell
.\gradlew.bat --no-daemon :shared:desktopTest :desktopApp:desktopTest
.\gradlew.bat --no-daemon :desktopApp:createDistributable :desktopApp:packageExe :desktopApp:packageMsi
```

macOS：

```bash
./gradlew --no-daemon :desktopApp:createDistributable :desktopApp:packageDmg
```

Compose Desktop 的 macOS 打包工具不接受主版本号为 0，因此应用产品版本仍为
`0.1.0`，DMG package version 使用 `1.0.0`。

桌面端当前语音识别是可见的 mock 流程，TTS 是空实现；文件选择器使用系统
`JFileChooser`。这些限制与安装包构建成功是两个独立概念。

### iOS Simulator

Apple Silicon：

```bash
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
```

Intel：

```bash
./gradlew --no-daemon :shared:linkDebugFrameworkIosX64
```

复制 `MomoShared.framework` 到 `iosApp/Frameworks/`，运行
`xcodegen generate --spec project.yml`，再用 `CODE_SIGNING_ALLOWED=NO`
构建 Simulator `.app`。Simulator `.app` 的 ZIP 不是 IPA。

真机 IPA 需要 Team ID、Distribution `.p12`、`.p12` 密码、provisioning profile
和匹配的 bundle identifier。缺少任一材料时，CI 会明确跳过 IPA。

### GitHub Actions

`.github/workflows/momo-mobile-build.yml` 支持手动触发、PR、`main` push 和
`momo-mobile-v*` tag，生成：

- `momo-android-debug-apk-*`
- `momo-android-release-*`（仅有签名 secrets 时）
- `momo-windows-exe-*`
- `momo-windows-msi-*`
- `momo-macos-dmg-*`
- `momo-ios-simulator-app-*`
- `momo-ios-ipa-*`（仅有 Apple 签名 secrets 时）

每个 artifact 包含 `checksums.txt`。编译成功只说明代码和包结构可生成；发布签名
完成还要求有效证书、私钥和 provisioning profile。

## Desktop Preview

桌面预览用于没有 Android 模拟器或 Xcode 的开发机。安装 Java 17、Xvfb/noVNC
等本地预览工具后，可以启动可交互预览：

```bash
tmux kill-session -t momo_mobile_preview 2>/dev/null || true
tmux new-session -d -s momo_mobile_preview \
  $APP_DIR/mobius/extension/momo-mobile/desktopPreview/run-local-preview.sh
```

然后在浏览器打开：

```text
http://127.0.0.1:6088/vnc.html?host=127.0.0.1&port=6088&autoconnect=true&resize=scale
```

也可以走 Mobius 同域反代，适合远程网页验证。启动脚本会生成
`.tmp/momo-mobile-preview/access-token` 并在 tmux 日志里打印完整 URL：

```text
https://mobius.example.com/momo_mobile_preview/vnc.html?host=mobius.example.com&port=443&encrypt=1&path=momo_mobile_preview/websockify&autoconnect=true&resize=scale&preview_token=<access-token>
```

停止预览：

```bash
tmux kill-session -t momo_mobile_preview
```

它直接复用 `shared/src/commonMain` 中的 `MomoApp()`、ViewModel、Ktor/SSE
逻辑，只在 `desktopPreview/src/desktopMain` 提供 JVM 平台 actual 实现。

### Tier 1：设备外观预览

Desktop Preview 使用固定的 430×900dp 设备画布，并模拟：

- 47dp 圆角设备外框和 4dp 黑色边框；
- 47dp 顶部安全区、200×30dp 刘海和每分钟刷新的状态栏；
- 使用 Compose `Path` 绘制的 Wi-Fi、电池图标；
- 34dp 底部安全区和 134×5dp Home indicator。

运行尺寸契约测试与编译检查：

```bash
cd mobius/extension/momo-mobile/desktopPreview
JAVA_HOME="$APP_DIR/.tmp/tools/jdk-deb/usr/lib/jvm/java-17-openjdk-amd64" \
  "$APP_DIR/.tmp/tools/gradle/gradle-8.8/bin/gradle" \
  --no-daemon desktopTest compileKotlinDesktop
```

### Tier 2：GitHub Actions 真模拟器截图

仓库级 workflow 位于：

```text
.github/workflows/momo-mobile-screenshot-verify.yml
```

GitHub 只加载仓库根目录下的 workflows，因此不能把可执行 YAML 放在 extension
内部。该 workflow 可通过 `workflow_dispatch` 手动运行，也会在 PR 修改以下目录时运行：

- `mobius/extension/momo-mobile/shared/**`
- `mobius/extension/momo-mobile/androidApp/**`
- `mobius/extension/momo-mobile/iosApp/**`

Android job 使用 Pixel 6 / API 34 无窗口模拟器，iOS job 使用 iPhone 15 Pro
Simulator。两者都会安装并启动应用、等待界面稳定、生成 PNG，并通过
`actions/upload-artifact@v4` 上传。

iOS 模拟器构建使用 `iosApp/project.yml` 通过 XcodeGen 生成临时
`iosApp.xcodeproj`。模拟器构建关闭代码签名，因此不需要 Apple Developer Team ID
或签名 secret；发布到真机或 App Store 时仍需另行配置 Team、证书和 provisioning
profile。

#### 首次建立基线

仓库不使用 Desktop Preview 或占位图片冒充真实平台基线。第一次运行时，如果
`screenshots/baseline/android.png` 或 `ios.png` 不存在，workflow 会把本次截图上传为
baseline candidate artifact：

```text
android-baseline-candidate-<run-id>
ios-baseline-candidate-<run-id>
```

下载并人工确认两张图片后，将它们分别提交到：

```text
mobius/extension/momo-mobile/screenshots/baseline/android.png
mobius/extension/momo-mobile/screenshots/baseline/ios.png
```

后续运行会用 ImageMagick `compare -metric AE` 统计不同像素。不同像素比例超过
5% 时，对应 job 失败；无论比较结果如何，当次截图都会作为 artifact 上传，便于审查。

## 设计对齐

参考设计稿：

- `tmp/01-login-light.png` / `tmp/02-login-dark.png`
- `tmp/03-home-light.png` / `tmp/04-home-dark.png`
- `tmp/05-list-light.png` / `tmp/06-list-dark.png`
- `tmp/07-settings-light.png` / `tmp/08-settings-dark.png`

实现采用微信式布局：顶部 56dp、输入栏 72dp、头像 36dp、气泡圆角 4/12dp、
品牌色 `#5B6CFF` 只用于主按钮、用户气泡和状态高亮。小莫头像使用与 Web 主站一致的
光场圆环视觉；输入栏文字模式为“附件 + 输入框 + 语音切换 + 发送”，语音模式为
“附件 + 按住说话 + 键盘切换”。
