package com.mobius.momo.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 登录代理设置的单测：验证 ProxyConfig/ProxyProtocol 序列化、旧数据兼容，
 * 以及 OkHttp builder 的代理映射（不真正启动代理服务，只检查 engine 配置）。
 */
class ProxyConfigTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `proxy off yields direct connection`() {
        val client = OkHttpClient.Builder()
            .apply { configureMobiusProxy(null) }
            .build()
        assertNull(client.proxy)
        assertEquals(Authenticator.NONE, client.proxyAuthenticator)
    }

    @Test
    fun `HTTP proxy on maps to OkHttp HTTP proxy`() {
        val client = OkHttpClient.Builder()
            .apply { configureMobiusProxy(ProxyConfig(protocol = ProxyProtocol.HTTP, host = "127.0.0.1", port = 8080)) }
            .build()
        val proxy = client.proxy
        assertEquals(Proxy.Type.HTTP, proxy?.type())
        val address = proxy?.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(8080, address.port)
    }

    @Test
    fun `SOCKS5 proxy on maps to OkHttp SOCKS proxy`() {
        val client = OkHttpClient.Builder()
            .apply { configureMobiusProxy(ProxyConfig(protocol = ProxyProtocol.SOCKS5, host = "10.0.0.2", port = 1080)) }
            .build()
        val proxy = client.proxy
        assertEquals(Proxy.Type.SOCKS, proxy?.type())
        val address = proxy?.address() as InetSocketAddress
        assertEquals("10.0.0.2", address.hostString)
        assertEquals(1080, address.port)
    }

    @Test
    fun `proxy with auth installs proxy authenticator`() {
        val client = OkHttpClient.Builder()
            .apply {
                configureMobiusProxy(
                    ProxyConfig(
                        protocol = ProxyProtocol.HTTP,
                        host = "proxy.local",
                        port = 3128,
                        username = "user",
                        password = "secret",
                    ),
                )
            }
            .build()
        assertNotEquals(Authenticator.NONE, client.proxyAuthenticator)
    }

    @Test
    fun `invalid proxy config falls back to direct connection`() {
        val client = OkHttpClient.Builder()
            .apply {
                configureMobiusProxy(ProxyConfig(protocol = ProxyProtocol.HTTP, host = "", port = 1080))
            }
            .build()
        assertNull(client.proxy)
        assertEquals(Authenticator.NONE, client.proxyAuthenticator)
    }

    @Test
    fun `ProxyConfig and ProxyProtocol round trip through JSON`() {
        val original = ProxyConfig(
            protocol = ProxyProtocol.SOCKS5,
            host = "192.168.1.1",
            port = 1080,
            username = "momo",
            password = "p@ss",
        )
        val restored = json.decodeFromString<ProxyConfig>(json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `old ServerEntry JSON without proxy fields remains backward compatible`() {
        val raw = """[{"url":"https://cloud-17.agent-matrix.com","label":"测试机","lastUsedAt":123}]"""
        val entries = json.decodeFromString<List<ServerEntry>>(raw)
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertFalse(entry.proxyEnabled)
        assertEquals(ProxyProtocol.HTTP, entry.proxyProtocol)
        assertNull(entry.toProxyConfigOrNull())
    }

    @Test
    fun `enabled ServerEntry converts to usable ProxyConfig`() {
        val entry = ServerEntry(
            url = "https://example.com",
            proxyEnabled = true,
            proxyProtocol = ProxyProtocol.SOCKS5,
            proxyHost = "127.0.0.1",
            proxyPort = 1080,
            proxyUsername = "user",
            proxyPassword = "secret",
        )
        val proxy = entry.toProxyConfigOrNull()
        assertTrue(proxy?.isUsable == true)
        assertEquals(ProxyProtocol.SOCKS5, proxy.protocol)
        assertEquals("user", proxy.username)
        assertEquals("secret", proxy.password)
    }
}
