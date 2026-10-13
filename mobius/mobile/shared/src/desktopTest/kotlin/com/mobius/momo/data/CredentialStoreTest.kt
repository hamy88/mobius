package com.mobius.momo.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.4.5.3 登录页「记住密码」单测: 覆盖 [CredentialStore] 的分桶 / 清除 / 拒绝空值 / 哈希稳定性.
 *
 * 用 in-memory FakeSecureStorage 隔离平台实现, 保证测试在 desktop JVM 上稳定跑过.
 */
class CredentialStoreTest {

    private class FakeSecureStorage : SecureStorage {
        val map = mutableMapOf<String, String>()
        override fun saveToken(token: String) {}
        override fun getToken(): String? = null
        override fun savePreference(key: String, value: String) { map[key] = value }
        override fun getPreference(key: String): String? = map[key]
        override fun clear() { map.clear() }
    }

    @Test
    fun `save and load roundtrip returns same username and password`() {
        val store = CredentialStore(FakeSecureStorage())
        store.saveRememberedCredential("https://example.com", "alice", "secret123")
        val loaded = store.loadRememberedCredential("https://example.com")
        assertNotNull(loaded)
        assertEquals("alice", loaded.first)
        assertEquals("secret123", loaded.second)
    }

    @Test
    fun `different baseUrls hit different buckets without cross-contamination`() {
        val store = CredentialStore(FakeSecureStorage())
        store.saveRememberedCredential("https://a.example.com", "user1", "pass1")
        store.saveRememberedCredential("https://b.example.com", "user2", "pass2")
        val a = store.loadRememberedCredential("https://a.example.com")
        val b = store.loadRememberedCredential("https://b.example.com")
        assertNotNull(a); assertNotNull(b)
        assertEquals("user1" to "pass1", a)
        assertEquals("user2" to "pass2", b)
        // 验证存储 key 不含 raw URL 字符
        val keysWithRawUrl = store.let { FakeSecureStorage() }.let { _ ->
            val s2 = FakeSecureStorage()
            val localStore = CredentialStore(s2)
            localStore.saveRememberedCredential("https://a.example.com", "u", "p")
            s2.map.keys.any { it.contains("a.example.com") }
        }
        assertTrue(!keysWithRawUrl, "raw URL 字符不应出现在存储 key 中")
    }

    @Test
    fun `clearRememberedCredential makes subsequent load return null and removes from index`() {
        val store = CredentialStore(FakeSecureStorage())
        store.saveRememberedCredential("https://c.example.com", "user", "pass")
        assertNotNull(store.loadRememberedCredential("https://c.example.com"))
        store.clearRememberedCredential("https://c.example.com")
        assertNull(store.loadRememberedCredential("https://c.example.com"))
        assertTrue(!store.listRememberedBaseUrls().contains("https://c.example.com"))
    }

    @Test
    fun `save same baseUrl twice overwrites and index stays deduplicated`() {
        val store = CredentialStore(FakeSecureStorage())
        store.saveRememberedCredential("https://d.example.com", "u1", "p1")
        store.saveRememberedCredential("https://d.example.com", "u2", "p2")
        val urls = store.listRememberedBaseUrls()
        assertEquals(1, urls.size, "同一 baseUrl 多次 save 后 index 应只有一条")
        assertEquals("https://d.example.com", urls.single())
        val loaded = store.loadRememberedCredential("https://d.example.com")
        assertNotNull(loaded)
        assertEquals("u2", loaded.first)
        assertEquals("p2", loaded.second)
    }

    @Test
    fun `empty username password or baseUrl is rejected and never persisted`() {
        val store = CredentialStore(FakeSecureStorage())
        store.saveRememberedCredential("https://e.example.com", "", "pass")
        store.saveRememberedCredential("https://f.example.com", "user", "")
        store.saveRememberedCredential("", "user", "pass")
        assertNull(store.loadRememberedCredential("https://e.example.com"))
        assertNull(store.loadRememberedCredential("https://f.example.com"))
        assertNull(store.loadRememberedCredential(""))
        assertTrue(store.listRememberedBaseUrls().isEmpty(), "空值调用不应进入 index")
    }
}
