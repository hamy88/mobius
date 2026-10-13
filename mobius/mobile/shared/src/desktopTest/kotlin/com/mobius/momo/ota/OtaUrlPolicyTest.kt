package com.mobius.momo.ota

import com.mobius.momo.platform.ota.OtaUrlPolicy
import java.net.InetAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §6 红线 — OTA URL 协议白名单的纯函数测试（v0.4.5.1 放宽私网 HTTP）。
 *
 * 分两层：
 * 1. [OtaUrlPolicy.isPrivateIpv4] / [OtaUrlPolicy.isPrivateIpv6] 字节数组判定（commonMain 纯函数）——
 *    覆盖 RFC1918 私网段 + IPv6 link-local/ULA + 公网 false case。
 * 2. URL 协议白名单的端到端判定：仿照 androidMain OtaDownloader.assertHttpsUrl 的拼接逻辑，
 *    用 java.net.URI 解析 + java.net.InetAddress.getByName 做 DNS（均为 JVM 标准库，desktop 端可跑），
 *    验证 https 任意通过、http + 私网通过、http + 公网 reject、非法 URL reject。
 *
 * 0.4.5 之前 assertHttpsUrl 直接 require https://，所有 http:// 一律 reject；
 * 0.4.5.1 放宽到私网段（10/8、172.16/12、192.168/16、127/8、::1、fe80::/10、fc00::/7）+ localhost
 * 允许 http://——便于用户自部署本服务器 OTA 通道（AndroidManifest cleartextTrafficPermitted=true
 * 已就绪，仅 App 代码层补一个 allowlist）。
 */
class OtaUrlPolicyTest {

    // ---------- 纯函数：IPv4 私网段 ----------

    @Test
    fun `private ipv4 ranges are accepted`() {
        // 10/8
        assertTrue(OtaUrlPolicy.isPrivateIpv4(ipv4(10, 0, 0, 1)))
        assertTrue(OtaUrlPolicy.isPrivateIpv4(ipv4(10, 255, 255, 254)))
        // 172.16/12
        assertTrue(OtaUrlPolicy.isPrivateIpv4(ipv4(172, 16, 0, 1)))
        assertTrue(OtaUrlPolicy.isPrivateIpv4(ipv4(172, 31, 255, 254)))
        // 192.168/16
        assertTrue(OtaUrlPolicy.isPrivateIpv4(ipv4(192, 168, 1, 1)))
        assertTrue(OtaUrlPolicy.isPrivateIpv4(ipv4(192, 168, 255, 254)))
        // 127/8 loopback
        assertTrue(OtaUrlPolicy.isPrivateIpv4(ipv4(127, 0, 0, 1)))
        assertTrue(OtaUrlPolicy.isPrivateIpv4(ipv4(127, 255, 255, 254)))
        // 169.254/16 link-local
        assertTrue(OtaUrlPolicy.isPrivateIpv4(ipv4(169, 254, 1, 1)))
        // 0/8
        assertTrue(OtaUrlPolicy.isPrivateIpv4(ipv4(0, 0, 0, 0)))
    }

    @Test
    fun `public ipv4 ranges are rejected`() {
        // 172.15 / 172.32 是公网边界
        assertFalse(OtaUrlPolicy.isPrivateIpv4(ipv4(172, 15, 255, 255)))
        assertFalse(OtaUrlPolicy.isPrivateIpv4(ipv4(172, 32, 0, 0)))
        // 11.x / 8.8.8.8 / 1.1.1.1
        assertFalse(OtaUrlPolicy.isPrivateIpv4(ipv4(11, 0, 0, 1)))
        assertFalse(OtaUrlPolicy.isPrivateIpv4(ipv4(8, 8, 8, 8)))
        assertFalse(OtaUrlPolicy.isPrivateIpv4(ipv4(1, 1, 1, 1)))
        // 169.255 / 170.0 是公网边界
        assertFalse(OtaUrlPolicy.isPrivateIpv4(ipv4(169, 255, 0, 0)))
        assertFalse(OtaUrlPolicy.isPrivateIpv4(ipv4(170, 0, 0, 1)))
    }

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): ByteArray =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    // ---------- 纯函数：IPv6 私网段 ----------

    @Test
    fun `private ipv6 ranges are accepted`() {
        // ::/128 全 0 — 含 ::1 loopback 与 0.0.0.0 映射
        assertTrue(OtaUrlPolicy.isPrivateIpv6(ByteArray(16)))
        // ::1 loopback
        val loopback = ByteArray(16).also { it[15] = 1 }
        assertTrue(OtaUrlPolicy.isPrivateIpv6(loopback))
        // fe80::/10 link-local
        val fe80 = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte() }
        assertTrue(OtaUrlPolicy.isPrivateIpv6(fe80))
        val fe90 = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x90.toByte() }
        assertTrue(OtaUrlPolicy.isPrivateIpv6(fe90))
        val febf = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0xbf.toByte() }
        assertTrue(OtaUrlPolicy.isPrivateIpv6(febf))
        // fc00::/7 unique-local
        val fc = ByteArray(16).also { it[0] = 0xfc.toByte() }
        assertTrue(OtaUrlPolicy.isPrivateIpv6(fc))
        val fd = ByteArray(16).also { it[0] = 0xfd.toByte() }
        assertTrue(OtaUrlPolicy.isPrivateIpv6(fd))
    }

    @Test
    fun `public ipv6 ranges are rejected`() {
        // 2001:4860:4860::8888 — Google 公网 DNS
        val google = byteArrayOf(
            0x20.toByte(), 0x01.toByte(), 0x48.toByte(), 0x60.toByte(),
            0x48.toByte(), 0x60.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x88.toByte(), 0x88.toByte(),
        )
        assertFalse(OtaUrlPolicy.isPrivateIpv6(google))
        // fec0::/10 站点本地已弃用 → 视为公网
        val fec0 = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0xc0.toByte() }
        assertFalse(OtaUrlPolicy.isPrivateIpv6(fec0))
        // ff00::/8 multicast
        val ff = ByteArray(16).also { it[0] = 0xff.toByte() }
        assertFalse(OtaUrlPolicy.isPrivateIpv6(ff))
    }

    // ---------- 端到端：URL 协议白名单 ----------

    /**
     * 与 androidMain OtaDownloader.assertHttpsUrl 行为一致：
     * - https:// 任意 host 通过
     * - http:// + host 私网通过
     * - http:// + host 公网 reject
     * - 解析失败 reject
     */
    private fun assertHttpsUrl(url: String) {
        if (url.startsWith("https://")) return
        val host = runCatching { URI(url).host }.getOrNull()
        val allowed = host != null && url.startsWith("http://") && isPrivateNetworkHost(host)
        require(allowed) { "OTA 下载仅支持 HTTPS（或私网 HTTP）：$url (host=$host)" }
    }

    private fun isPrivateNetworkHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        val addr = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return false
        return when (addr.size) {
            4 -> OtaUrlPolicy.isPrivateIpv4(addr)
            16 -> OtaUrlPolicy.isPrivateIpv6(addr)
            else -> false
        }
    }

    @Test
    fun `https URLs are always accepted regardless of host`() {
        // 公网域名（GitHub Releases 真实地址）
        assertHttpsUrl("https://github.com/hamy88/mobius/releases/download/mobile-v0.4.5/foo.apk")
        // 私网 IP 走 https 也通过（不强制仅 https 私网）
        assertHttpsUrl("https://192.168.1.10:8080/mobius-mobile-0.4.5.1.apk")
        // localhost https
        assertHttpsUrl("https://localhost/mobius-mobile-0.4.5.1.apk")
    }

    @Test
    fun `http private IPv4 URLs are accepted`() {
        // 10/8
        assertHttpsUrl("http://10.0.0.1:8080/mobius-mobile-0.4.5.1.apk")
        // 172.16/12 边界
        assertHttpsUrl("http://172.16.0.1/apk")
        assertHttpsUrl("http://172.31.255.254/apk")
        // 192.168/16
        assertHttpsUrl("http://192.168.1.100:9000/path/mobius-mobile-0.4.5.1.apk")
        // 127/8
        assertHttpsUrl("http://127.0.0.1:8080/apk")
        // 169.254 link-local
        assertHttpsUrl("http://169.254.169.254/latest/apk")
    }

    @Test
    fun `http localhost and loopback IPv6 URLs are accepted`() {
        // localhost 字面量
        assertHttpsUrl("http://localhost:8080/apk")
        assertHttpsUrl("http://LOCALHOST/apk") // 大小写不敏感
        // ::1 loopback
        assertHttpsUrl("http://[::1]:8080/apk")
        // fe80:: link-local（java URI 会把 host 解析为带 [ ] 的形式）
        assertHttpsUrl("http://[fe80::1]/apk")
    }

    @Test
    fun `http public IPv4 URLs are rejected`() {
        // 8.8.8.8 公网 DNS
        assertFailsWith<IllegalArgumentException> { assertHttpsUrl("http://8.8.8.8/apk") }
        // 1.1.1.1 Cloudflare
        assertFailsWith<IllegalArgumentException> { assertHttpsUrl("http://1.1.1.1/apk") }
        // 172.15/172.32 公网边界
        assertFailsWith<IllegalArgumentException> { assertHttpsUrl("http://172.15.255.255/apk") }
        assertFailsWith<IllegalArgumentException> { assertHttpsUrl("http://172.32.0.0/apk") }
    }

    @Test
    fun `http public IPv6 and domain URLs are rejected`() {
        // 2001:4860:4860::8888 — Google DNS
        assertFailsWith<IllegalArgumentException> { assertHttpsUrl("http://[2001:4860:4860::8888]/apk") }
        // 公网域名（InetAddress.getByName 在 desktopTest 容器里能否解析取决于 DNS；用不存在的 .invalid TLD 保证抛 UnknownHostException → false）
        assertFailsWith<IllegalArgumentException> { assertHttpsUrl("http://example.invalid/apk") }
    }

    @Test
    fun `invalid URLs are rejected`() {
        // 非 URL 字符串
        assertFailsWith<IllegalArgumentException> { assertHttpsUrl("not-a-url") }
        // 不带 scheme
        assertFailsWith<IllegalArgumentException> { assertHttpsUrl("192.168.1.1/apk") }
        // ftp 协议（不支持的 scheme）
        assertFailsWith<IllegalArgumentException> { assertHttpsUrl("ftp://192.168.1.1/apk") }
    }

    @Test
    fun `rejection message contains url and host for debugging`() {
        val ex = assertFailsWith<IllegalArgumentException> { assertHttpsUrl("http://8.8.8.8/apk") }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("http://8.8.8.8/apk"), "error should include URL: $msg")
        assertTrue(msg.contains("host="), "error should include host= label: $msg")
        assertTrue(msg.contains("8.8.8.8"), "error should include host value: $msg")
    }
}
