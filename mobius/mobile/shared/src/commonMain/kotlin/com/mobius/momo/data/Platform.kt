package com.mobius.momo.data

import io.ktor.client.HttpClient

const val TOKEN_STORAGE_VERSION = 2
const val TOKEN_MAX_AGE_MILLIS = 30L * 24L * 60L * 60L * 1000L

data class StoredTokenMetadata(
    val baseUrl: String,
    val savedAtEpochMillis: Long,
    val storageVersion: Int,
)

interface SecureStorage {
    fun saveToken(token: String)
    fun getToken(): String?
    fun saveTokenMetadata(metadata: StoredTokenMetadata) {}
    fun getTokenMetadata(): StoredTokenMetadata? = null
    fun savePreference(key: String, value: String)
    fun getPreference(key: String): String?
    fun clear()
}

fun storedTokenMetadataFromStrings(
    baseUrl: String?,
    savedAtEpochMillis: String?,
    storageVersion: String?,
): StoredTokenMetadata? {
    val savedBaseUrl = baseUrl?.takeIf { it.isNotBlank() } ?: return null
    val savedAt = savedAtEpochMillis?.toLongOrNull() ?: return null
    val version = storageVersion?.toIntOrNull() ?: return null
    return StoredTokenMetadata(
        baseUrl = savedBaseUrl,
        savedAtEpochMillis = savedAt,
        storageVersion = version,
    )
}

data class PickedFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
)

interface FilePicker {
    fun pickFiles(
        maxFiles: Int,
        onResult: (List<PickedFile>) -> Unit,
        onError: (String) -> Unit,
    )
}

// iOS 本地通知点击暂存的 deepLink 通道(Android/desktop 恒 null)。
expect fun consumePendingNotificationDeepLink(): String?

expect fun createSecureStorage(): SecureStorage

expect fun createFilePicker(): FilePicker

expect fun createMobiusHttpClient(
    onUnauthorized: suspend () -> Unit,
): HttpClient

expect fun nowShortTime(): String

expect fun formatBackendTime(value: String?): String

expect fun nowEpochMillis(): Long

// 当前时刻的 ISO8601 字符串(UTC, 如 2026-07-07T05:32:24.312Z)。
// 用于客户端临时构造的群消息(乐观消息/错误消息)的 created_at——与服务端格式一致,
// 这样 parseBackendTimeMillis 能解析、群消息列表能按时间正确排序与显示。
expect fun nowIsoTime(): String

expect fun parseBackendTimeMillis(value: String?): Long?

// 平台标识(iOS 播报诊断等临时用途)。
expect fun platformIsIOS(): Boolean
