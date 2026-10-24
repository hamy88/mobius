package com.mobius.momo.platform.ota

/**
 * 私网段 IP 判定纯函数（无平台依赖，跨 commonMain / desktopTest 共用）。
 *
 * 0.4.5.6 起 OTA URL 协议白名单放宽为「http:// 与 https:// 均可」，不再区分公网/私网，
 * 因此 androidMain OtaDownloader 已不再调用本文件的判定。此文件保留私网段字节数组判定，
 * 供历史参考与 OtaUrlPolicyTest 覆盖 RFC1918 / IPv6 link-local / ULA 等边界。
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
