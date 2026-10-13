package com.mobius.momo.data

/**
 * 0.4.5.3 登录页「记住密码」复选框的存储.
 *
 * 行为:
 * - 复选框勾选 + 登录成功 → [saveRememberedCredential] 把 username + password 落 SecureStorage, 按 baseUrl 分桶.
 * - 下次进入登录页, 选中已记住的 server → [loadRememberedCredential] 拉到凭据, UI 预填, 用户手动点登录(不做自动登录).
 * - 注销 / 主动取消勾选再登录一次 → [clearRememberedCredential] 清掉对应桶.
 *
 * 安全性:
 * - key 后缀是 SHA-256(baseUrl) 的 64 位 hex 串, 避免 raw URL 出现在存储 key 里(防"看 key 列表推测内部地址").
 * - password 原文仍由底层 SecureStorage 保护(Android EncryptedSharedPreferences + Keystore / iOS Keychain /
 *   desktop OS 凭据存储); 本类只负责分桶.
 * - 与 30 天 token 机制解耦: token 过期后, 已记住的密码仍可预填, 用户重新登录即可.
 */
class CredentialStore(private val storage: SecureStorage) {

    /**
     * 保存某条服务器的登录凭据. baseUrl / username / password 任一为空时整次调用为 no-op
     * (避免误把"用户清空"当作"用户主动取消"——后者走 [clearRememberedCredential]).
     */
    fun saveRememberedCredential(baseUrl: String, username: String, password: String) {
        if (baseUrl.isBlank() || username.isBlank() || password.isBlank()) return
        val key = Sha256.hex(baseUrl)
        storage.savePreference(keyPrefix(key) + USERNAME_KEY_SUFFIX, username)
        storage.savePreference(keyPrefix(key) + PASSWORD_KEY_SUFFIX, password)
        rememberIndex(baseUrl, add = true)
    }

    /**
     * 加载某条服务器的记住凭据. 返回 (username, password); 任何一边缺失/空都视为未记住, 返回 null.
     */
    fun loadRememberedCredential(baseUrl: String): Pair<String, String>? {
        if (baseUrl.isBlank()) return null
        val key = Sha256.hex(baseUrl)
        val username = storage.getPreference(keyPrefix(key) + USERNAME_KEY_SUFFIX)
            ?.takeIf { it.isNotBlank() } ?: return null
        val password = storage.getPreference(keyPrefix(key) + PASSWORD_KEY_SUFFIX)
            ?.takeIf { it.isNotBlank() } ?: return null
        return username to password
    }

    /**
     * 清除某条服务器的记住凭据. 即使原本就没有凭据, 调用也是幂等.
     */
    fun clearRememberedCredential(baseUrl: String) {
        if (baseUrl.isBlank()) return
        val key = Sha256.hex(baseUrl)
        // 显式清空而非删除 key: SecureStorage 接口未提供 delete, 用空串覆盖语义一致
        // (loadRememberedCredential 把空值视为"未记住").
        storage.savePreference(keyPrefix(key) + USERNAME_KEY_SUFFIX, "")
        storage.savePreference(keyPrefix(key) + PASSWORD_KEY_SUFFIX, "")
        rememberIndex(baseUrl, add = false)
    }

    /**
     * 列出所有已保存凭据的 baseUrl. 顺序按 SecureStorage 写入顺序(不保证稳定),
     * 仅供迁移 / 调试使用(主流程按 serverEntries 渲染, 不依赖此函数).
     */
    fun listRememberedBaseUrls(): List<String> {
        return storage.getPreference(REMEMBER_INDEX_KEY)
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    private fun rememberIndex(baseUrl: String, add: Boolean) {
        val current = listRememberedBaseUrls().toMutableList()
        val changed = if (add) {
            if (current.contains(baseUrl)) false else { current.add(baseUrl); true }
        } else {
            current.remove(baseUrl)
        }
        if (changed) {
            storage.savePreference(REMEMBER_INDEX_KEY, current.joinToString("\n"))
        }
    }

    private companion object {
        const val REMEMBER_INDEX_KEY = "auth/remembered/index"
        const val KEY_PREFIX = "auth/remembered/"
        const val USERNAME_KEY_SUFFIX = "/username"
        const val PASSWORD_KEY_SUFFIX = "/password"
        fun keyPrefix(hash: String): String = KEY_PREFIX + hash
    }
}
