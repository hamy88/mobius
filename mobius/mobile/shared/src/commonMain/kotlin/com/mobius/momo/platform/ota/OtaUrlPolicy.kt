package com.mobius.momo.platform.ota

/**
 * OTA 下载 URL 协议白名单的纯函数判定（无平台依赖，跨 commonMain / androidMain / desktopMain 共用）。
 *
 * §6 红线：拒绝非 HTTPS URL，但允许私网段 HTTP 走本服务器 OTA 通道。
 * - https:// 任意 host → 通过
 * - http:// + host 落在私网段（10/8、172.16/12、192.168/16、127/8、::1、fe80::/10、fc00::/7）→ 通过
 * - 其它 http:// 公网 → reject
 *
 * 把字节数组的私网段判断抽到 commonMain 是为了能直接在 desktopTest 覆盖；DNS 解析仍由调用方
 * （androidMain OtaDownloader）经 java.net.InetAddress.getByName 完成，避免 commonMain 引入
 * JVM-only API。
 */
internal object OtaUrlPolicy {

    /** IPv4 私网段判定。输入长度必须为 4。 */
    fun isPrivateIpv4(addr: ByteArray): Boolean {
        require(addr.size == 4) { "expected 4 bytes, got ${addr.size}" }
        val a = addr[0].toInt() and 0xff
        val b = addr[1].toInt() and 0xff
        return a == 10 || a == 127 || a == 0 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) // link-local
    }

    /** IPv6 私网段判定。输入长度必须为 16。 */
    fun isPrivateIpv6(addr: ByteArray): Boolean {
        require(addr.size == 16) { "expected 16 bytes, got ${addr.size}" }
        val b0 = addr[0].toInt() and 0xff
        val b1 = addr[1].toInt() and 0xff
        // ::1 (loopback) — 前 15 字节全 0，最后一字节为 1
        val isLoopback = (0..14).all { addr[it].toInt() == 0 } && addr[15].toInt() == 1
        // ::/128 全 0 — 0.0.0.0 的 IPv6 映射
        val isAllZero = (0..15).all { addr[it].toInt() == 0 }
        return isAllZero || isLoopback ||
            (b0 == 0xfe && (b1 and 0xc0) == 0x80) || // fe80::/10 link-local
            (b0 and 0xfe) == 0xfc                      // fc00::/7 unique-local
    }
}
