package com.mobius.momo.data

const val SERVER_BASE_URL_PREFERENCE = "server_base_url"
const val RECOMMENDED_MOBIUS_BASE_URL = "https://cloud-17.agent-matrix.com"
const val SECURE_PREF_SELECTED_VOICE = "tts_selected_voice"
const val SECURE_PREF_TTS_PLAYBACK_MODE = "tts_playback_mode"
// 「自动播报」开关持久化(默认开). "1"=开, "0"=关. 不存则视为默认开.
const val SECURE_PREF_TTS_ENABLED = "tts_enabled"
const val GROUP_RELAY_OVERLAY_KEY = "group_relay_overlay"

// ===== 聚合推送（JPush）相关偏好 =====
// 「消息推送」总开关持久化（默认开）。值为 "true"/"false"。
const val SECURE_PREF_PUSH_ENABLED = "push_enabled"
// 最近一次成功上报给后端的 RegistrationID（用于判断是否需要重新上报）。
const val SECURE_PREF_PUSH_TOKEN = "push_registration_id"
// 当前 RegistrationID 是否已成功注册到后端（"true"/"false"）。
const val SECURE_PREF_PUSH_TOKEN_REGISTERED = "push_token_registered"
// 上报设备令牌时携带的渠道名，后端据此区分推送通道。
const val PUSH_PLATFORM_JPUSH = "jpush"
const val PUSH_PLATFORM_HUAWEI = "huawei"
// 华为令牌(JPush 华为插件接不住, 由 MomoHmsPushService 直接捕获上报)单独持久化。
const val SECURE_PREF_PUSH_HMS_TOKEN = "push_hms_token"
const val SECURE_PREF_PUSH_HMS_REGISTERED = "push_hms_registered"

// 本项目作为 Mobius 扩展的 name（见 extension.json）；客户端的设备令牌注册/推送走 Mobius 的
// /api/ext 转发到本扩展 handler（backend/extension_backend_handler.js）。
const val MOBIUS_EXTENSION_NAME = "momo-mobile"

// ===== 四个 tab（聊天/通讯录/项目/我）列表的磁盘快照缓存 =====
// 值为 JSON 字符串（登录用户 id 绑定，切换账号自动失效）。用于冷启动秒开：
// 先渲染上次快照，同时后台拉最新数据覆盖（stale-while-revalidate）。
const val TAB_CACHE_CONVERSATIONS = "tab_cache_conversations"
// 项目页精简模式开关("1"=精简, 默认经典)。
const val PROJECTS_LITE_MODE_KEY = "projects_lite_mode"
// 精简模式聚合 session 列表的磁盘快照(秒开缓存)。
const val TAB_CACHE_LITE_SESSIONS = "tab_cache_lite_sessions"
const val TAB_CACHE_CONTACTS = "tab_cache_contacts"
const val TAB_CACHE_PROJECTS = "tab_cache_projects"
const val TAB_CACHE_CLONES = "tab_cache_clones"

enum class TtsPlaybackMode {
    All,
    Selected,
    ;

    companion object {
        fun fromName(name: String?): TtsPlaybackMode =
            entries.firstOrNull { it.name == name } ?: Selected
    }
}

fun normalizeMobiusBaseUrl(value: String): String {
    val normalized = value.trim().trimEnd('/')
    if (normalized.isBlank()) return ""
    require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
        "服务器地址必须以 http:// 或 https:// 开头"
    }
    return normalized
}

fun resolveMobiusBaseUrl(buildDefault: String, savedValue: String?): String =
    normalizeMobiusBaseUrl(savedValue?.takeIf { it.isNotBlank() } ?: buildDefault)

expect fun platformBuildBaseUrl(): String

// 返回实际安装的应用版本号(如 "0.1.4")，供设置页等显示，避免硬编码不同步。
expect fun platformAppVersion(): String

// 底部 tabbar 额外 bottom padding:
// iOS: .ignoresSafeArea() 导致 navigationBarsPadding 返回 0(home indicator 遮挡), 需兜底 24dp.
// Android: navigationBarsPadding 自动适配系统导航栏, 无需额外 padding.
expect fun platformBottomTabPaddingDp(): Float
