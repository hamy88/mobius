package com.mobius.momo.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 登录页「服务器地址列表」的持久化 key(经 SecureStorage.savePreference 落各平台原生 KV:
// Android EncryptedSharedPreferences / iOS NSUserDefaults / desktop 内存实现)。
const val SERVER_LIST_PREFERENCE = "server_address_list"

/** 一条已保存的服务器地址。label 可空(用户重命名的备注名, 如 "公司测试机")。 */
@Serializable
data class ServerEntry(
    val url: String,
    val label: String = "",
    val lastUsedAt: Long = 0L,
)

/**
 * 服务器地址列表仓库: 登录成功后自动 addOrTouch 当前地址, 登录页展示最近使用倒序列表。
 *
 * 刻意不做 expect/actual——直接复用 [SecureStorage] 的平台 KV(savePreference/getPreference),
 * 三平台(Android/iOS/desktop)行为一致且零额外样板。JSON 序列化整个列表, 单 key 存取。
 */
class ServerAddressRepository(private val storage: SecureStorage) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ServerEntry.serializer())

    /** 按 lastUsedAt 倒序(最近使用在前)。 */
    fun getAll(): List<ServerEntry> =
        runCatching {
            storage.getPreference(SERVER_LIST_PREFERENCE)
                ?.takeIf { it.isNotBlank() }
                ?.let { json.decodeFromString(serializer, it) }
        }.getOrNull()
            ?.filter { it.url.isNotBlank() }
            ?.sortedByDescending { it.lastUsedAt }
            ?: emptyList()

    private fun saveAll(entries: List<ServerEntry>) {
        runCatching { storage.savePreference(SERVER_LIST_PREFERENCE, json.encodeToString(entries)) }
    }

    /**
     * 登录成功后调用: url 已存在则刷新 lastUsedAt(保留既有 label), 否则追加。
     * 返回最新列表(已按最近使用排序)。
     */
    fun addOrTouch(url: String, label: String? = null, nowMillis: Long = nowEpochMillis()): List<ServerEntry> {
        val normalized = url.trim().trimEnd('/')
        if (normalized.isBlank()) return getAll()
        val existing = getAll().toMutableList()
        val index = existing.indexOfFirst { it.url == normalized }
        val entry = when {
            index >= 0 -> existing[index].copy(
                label = label ?: existing[index].label,
                lastUsedAt = nowMillis,
            )
            else -> ServerEntry(url = normalized, label = label.orEmpty(), lastUsedAt = nowMillis)
        }
        if (index >= 0) existing[index] = entry else existing.add(entry)
        val sorted = existing.sortedByDescending { it.lastUsedAt }
        saveAll(sorted)
        return sorted
    }

    fun remove(url: String) {
        saveAll(getAll().filterNot { it.url == url.trim().trimEnd('/') })
    }

    /** 重命名 label(传 null/空清空备注)。url 不匹配时为空操作。 */
    fun rename(url: String, label: String?) {
        val target = url.trim().trimEnd('/')
        val entries = getAll().toMutableList()
        val index = entries.indexOfFirst { it.url == target }
        if (index < 0) return
        entries[index] = entries[index].copy(label = label.orEmpty())
        saveAll(entries)
    }
}
