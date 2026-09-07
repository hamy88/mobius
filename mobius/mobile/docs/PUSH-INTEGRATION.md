# 极光推送（JPush）聚合推送集成

> Issue: 推送功能集成 — 添加极米 push 的聚合推送。按「聚合推送」语义实现为**极光推送 JPush**
> （JPush 的核心卖点即聚合华为 / 小米 / OPPO / vivo / 魅族 等厂商通道）。「极米 push」按口误/笔误处理。

## 它解决了什么

原先 App 的「消息推送」只靠**本地通知 + 前台 keepalive 服务**（`NotificationGateway` + `MomoKeepaliveService`）：
App 必须常驻后台进程保活 SSE，才能在状态栏弹通知。**一旦系统把进程彻底杀掉，SSE 断开，消息送不到。**

本次新增的**远程聚合推送**补上这一环：

- App 启动 → JPush SDK 注册，拿到设备唯一 **RegistrationID**；
- 登录后把 RegistrationID 上报后端（经 Mobius `POST /api/ext` → 本项目扩展 handler 持久化）；
- 需要推送时，扩展 handler 调 JPush 服务端 REST 把消息下发——即便 App 被杀，也能经 JPush（及其聚合的厂商通道）推到设备状态栏。

本地 keepalive 与远程 JPush **互补**：前台/后台保活时走 SSE 实时通；进程被杀时由 JPush 兜底。

## 架构

```
commonMain   PushProvider(接口) + createPushProvider()(expect)
             MobiusApi.registerDeviceToken / unregisterDeviceToken  (POST /api/ext → 扩展 handler)
             AppConfig: SECURE_PREF_PUSH_* / PUSH_PLATFORM_JPUSH / MOBIUS_EXTENSION_NAME
             MomoAppViewModel: init→pushProvider.init; 登录/自动登录→registerPushTokenIfNeeded;
                               togglePush(持久化+注册/注销); logout→注销设备令牌
androidMain  AndroidJPushProvider(object): JPushInterface.init/getRegistrationID/stop/resumePush
             MomoJPushReceiver: onMessage(透传→本地通知) / onNotifyMessageOpened(拉起 App) / onRegister(缓存 rid)
desktopMain  NoOp（desktop 无聚合推送）
iosMain      NoOp（后续接 APNs）
androidApp   Manifest: JPUSH_APPKEY/CHANNEL meta-data + MomoJPushReceiver + 修正 SDK 缺失的 exported
             build.gradle.kts: manifestPlaceholders(JPUSH_APPKEY/CHANNEL/PKGNAME)
             proguard-rules.pro: JPush keep
backend      extension_backend_handler.js（经 Mobius /api/ext 调用）：
               register_device / unregister_device  持久化到 ext_data_dir/devices.json
               list_devices（调试） / notify_user  查令牌→调 JPush REST(https://api.jpush.cn/v3/push)
```

## 启用步骤（拿到 AppKey 后）

1. 在 [极光控制台](https://www.jiguang.cn/) 创建应用，获取 **AppKey**。
2. 在仓库根的 `gradle.properties`（或 CI 环境变量）配置：
   ```properties
   MOMO_JPUSH_APPKEY=你的AppKey
   MOMO_JPUSH_CHANNEL=mobius
   ```
   未配置时 `MOMO_JPUSH_APPKEY` 默认空串——`PushProvider.init` 为空操作，App 仍可正常构建运行，
   只是不会有远程推送（构建**不会**因缺 key 而失败）。
3. （聚合厂商通道——以华为为例，已打通）想让 App **被杀也能收**，需逐家接厂商通道。**控制台**和**客户端**两边都要做：

   **控制台（极光 + 厂商）**：
   - 极光控制台「推送设置 → 厂商通道」填华为 AppId/AppSecret + 包名。
   - **华为 AGC**：`AppGallery Connect → 项目设置 → 常规 → 应用 → 指纹` 添加 APK 签名证书 SHA-256
     （debug keystore：`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`）。
     **不加指纹 HMS 拿不到令牌、JPush 连 RegistrationID 都注册不上**（实测踩坑）。

   **客户端（已集成）**：
   - `cn.jiguang.sdk.plugin:huawei:4.0.5`（JPush↔HMS 桥接，含 `PluginHuaweiPlatformsService`）+ `com.huawei.hms:push`（华为 Maven 仓库 `developer.huawei.com/repo`）。
   - **agconnect 插件**：KMP 项目里不能在 `plugins{}` 块直接 apply（触发 "AGP applied without creating android() Kotlin Target"），
     改为**根 `build.gradle.kts` `apply false` + androidApp 末尾 `pluginManager.apply("com.huawei.agconnect.agcp")`** 延迟到 `kotlin{}` 块之后再 apply。
   - `agconnect-services.json` 放 `androidApp/` 根（agcp 默认从这里读，注入配置供 agconnect-core 运行时用）。
   - Manifest 加 `<meta-data android:name="com.huawei.hms.client.appid" android:value="appid=<APPID>"/>`。
   - JPush 运行时自动探测华为插件；华为设备上 App 被杀也能经 HMS 下发。

   其他厂商（小米/OPPO/vivo/魅族）同理：各自极光插件 + 各自厂商 SDK + 控制台配置。

4. 构建、安装、启动 App，登录后在 logcat 过滤 `JPush` 可见 RegistrationID 注册日志。

## 后端链路（已实现于扩展 handler）

后端不另起独立服务，而是复用 Mobius 扩展机制：客户端的设备令牌请求经 Mobius `POST /api/ext`
转发到本项目扩展 handler `backend/extension_backend_handler.js`（JWT 自动注入可信 `username`）。
handler 按 `ext_main_payload.action` 分发，**已实现**以下动作（调用方均幂等容错，失败由客户端
`runCatching` 兜底，不影响主流程）：

| action | 载荷 | 行为 | 时机 |
|--------|------|------|------|
| `register_device` | `{token, platform}` | 持久化到 `ext_data_dir/devices.json`，按用户去重 | 登录/自动登录成功、开启推送后 |
| `unregister_device` | `{token}` | 解绑该令牌 | 登出、关闭推送前 |
| `list_devices` | — | 查看自己绑定的设备（调试） | 调试 |
| `notify_user` | `{title?, body, deepLink?}` | 查调用者令牌 → 调 JPush REST 下发 | 需要推送时（见下「触发」） |
| `whoami` | — | 探活 | 调试 |

handler 调 JPush 时发的推送体（已由 `backend/extension_backend_handler.test.js` 钉死）：

```jsonc
POST https://api.jpush.cn/v3/push   // Authorization: Basic base64(AppKey:MasterSecret)
{
  "platform": "all",
  "audience": { "registration_id": ["<RegistrationID>", ...] },
  "notification": {
    "alert": "<body>",
    "android": { "title": "<title>", "extras": { "deepLink": "momo://chat/<sessionId>" } },
    "ios":     { "alert": "<body>", "sound": "default", "extras": { "deepLink": "..." } }
  },
  "options": { "time_to_live": 604800 }
}
```

### Master Secret 部署（密钥，绝不进客户端 / 不提交仓库）

AppKey 公开（已在 `gradle.properties`、打进 APK manifest）；**Master Secret 是密钥**，只在 Mobius
服务端用，handler 从以下来源读取（优先 env，回退配置文件）：

```bash
# 方式 A：环境变量（推荐，配进 Mobius 服务的 systemd/.env）
export MOMO_JPUSH_APPKEY=d0889c1cdac3cb59c05a8092
export MOMO_JPUSH_MASTER_SECRET=<你的MasterSecret>

# 方式 B：配置文件（放在 Mobius 服务的 ext_data_dir，即
#   APP_DIR/protected_data/extension/momo-mobile/jpush_config.json，参考 backend/jpush_config.example.json）
{ "appkey": "d0889c1cdac3cb59c05a8092", "master_secret": "<你的MasterSecret>" }
```

`backend/jpush_config.json` 与 `.env*` 已在 `.gitignore`，不会被提交。

### 触发：Mobius 主项目消息管线已接入（2026-07-05）

`notify_user` 只能推给**调用者本人**的设备（安全：防互相打扰）。Mobius 后端已在消息产出点接入钩子，
**只给"不在线（无 SSE 连接）"的目标用户推**（在线则由 SSE 实时送达，不重复打扰）：

| 场景 | 钩子文件 | 触发时机 | 目标用户 |
|------|---------|---------|---------|
| 1v1 assistant 回复 | `backend/routes/sessions.ts`（`POST /:id/messages` 后） | agent 回复 settle（turn complete） | 会话 owner，离线时 |
| 群聊真人消息 | `backend/routes/conversations.ts`（`POST /:id/messages` 后） | 消息入库 | 所有离线的真人成员（不含发送者、不含 agent 成员） |

Mobius 侧实现（均 fire-and-forget + try/catch，推送失败绝不影响消息主流程）：
- `backend/services/user-presence.ts`：按 userId 引用计数追踪 SSE 在线状态；session 事件流 open/close 时 track/release。
- `backend/services/extension-push.ts`：`pushToUser({username, title, body, deepLink})` → `registry.get('momo-mobile')` → `invokeHandler({entry, username, ext_main_payload:{action:'notify_user',...}})`。
- 1v1：订阅 agent thought stream（`getAgentRawThoughtStream` + sentinel），turn-complete entry 触发；从 entry 直接取 assistant 文本做摘要；`deepLink=momo://chat/<sessionId>`。
- 群聊：复用现成 `isMemberOnline(last_seen_at)`（群 SSE 每 1.5s 心跳，<6s 算在线）；`deepLink=momo://group/<id>`。
- 开关：`MOBIUS_EXT_PUSH_ENABLED=0` 整体关闭（默认开）；扩展名 `MOBIUS_EXT_PUSH_EXTENSION_NAME`（默认 `momo-mobile`）。

**注意**：在线判定以"是否有活跃 SSE"为准 —— 若同一用户在网页端开着会话页，移动端不会重复推（标准去重）。
手头联调阶段也可直接 curl 验证全链路：

```bash
TOKEN=<你的JWT>  PORT=<Mobius端口，默认33316>
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"extension_name":"momo-mobile","ext_main_payload":{"action":"notify_user","title":"Mobius","body":"测试推送","deepLink":"momo://chat/test"}}' \
  http://localhost:$PORT/api/ext
```

## 依赖版本

- `cn.jiguang.sdk:jpush:4.0.5` + `cn.jiguang.sdk:jcore:2.7.4`（从 aliyun / mavenCentral 解析，
  无需额外仓库；`developer.jiguang.cn` 的私有仓库可不用）。
- 选 4.0.5 是因这是 mavenCentral/aliyun 上可解析的最新版。JPush 5.x（含更深度的厂商通道客户端）
  仅发布在其私有仓库；本集成用到的 `JPushInterface.init/getRegistrationID`、`JPushMessageReceiver`
  等 API 在 4.x / 5.x 间稳定，后续如需 5.x 仅改 `shared/build.gradle.kts` 版本号即可。

## 测试

- `shared/src/desktopTest/.../MobiusApiDeviceTokenTest.kt`：钉死客户端 `POST /api/ext` 的方法、
  路径、`{extension_name, ext_main_payload:{action,token,platform}}` 体、`Authorization: Bearer` 头与空 token 空操作。
- `backend/extension_backend_handler.test.js`（`node --test backend/`）：覆盖 handler 的 register/unregister/
  list/notify（用本地 capture server 重定向 JPush API，验证 Basic Auth、`registration_id`、`notification`、
  `extras.deepLink` 形状）、whoami、参数校验与错误兜底。
- Android 构建产物 `:androidApp:assembleDebug` / `assembleRelease`(R8) 已验证 manifest 合并（含修正
  JPush SDK 缺失的 `android:exported`）与 SDK 打包。

## 客户端关键文件

- `shared/src/commonMain/kotlin/com/mobius/momo/data/PushProvider.kt`、`AppConfig.kt`、`MobiusApi.kt`（设备令牌走 `/api/ext`）
- `shared/src/androidMain/kotlin/com/mobius/momo/data/PushProvider.android.kt`（`AndroidJPushProvider`）、`MomoJPushReceiver.kt`
- `shared/src/commonMain/kotlin/com/mobius/momo/viewmodel/MomoAppViewModel.kt`（`registerPushTokenIfNeeded` / `unregisterPushToken` / `togglePush` / `logout`）
- `androidApp/src/androidMain/AndroidManifest.xml`、`androidApp/build.gradle.kts`、`androidApp/proguard-rules.pro`
- `backend/extension_backend_handler.js`（后端链路）、`backend/jpush_config.example.json`
