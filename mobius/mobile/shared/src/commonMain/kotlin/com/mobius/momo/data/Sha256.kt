package com.mobius.momo.data

/**
 * 0.4.5.3 登录页「记住密码」: 把 baseUrl 哈希成定长 hex 串作为 SecureStorage 的 key 后缀,
 * 避免 raw URL(含潜在 token/路径)出现在 key 里. 三个平台 (Android / desktop / iOS) 都跑同一份
 * 纯 Kotlin 实现, 不依赖 java.security / CommonCrypto.
 *
 * 实现按 FIPS 180-4. 输入短 (单个 baseUrl 通常 < 256B), 性能无感.
 * 非通用密码学工具, 仅用于 key 派生(碰撞容忍).
 */
internal object Sha256 {
    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
        0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
        0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    fun hex(input: String): String {
        val out = digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(64)
        for (b in out) {
            val v = b.toInt() and 0xff
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0f])
        }
        return sb.toString()
    }

    private fun digest(data: ByteArray): ByteArray {
        val bitLen = data.size.toLong() * 8L
        val padLen = ((56 - (data.size + 1) % 64) + 64) % 64
        val padded = ByteArray(data.size + 1 + padLen + 8)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 1 - i] = (bitLen ushr (i * 8)).toByte()
        }
        val H = intArrayOf(
            0x6a09e667.toInt(), 0xbb67ae85.toInt(), 0x3c6ef372.toInt(), 0xa54ff53a.toInt(),
            0x510e527f.toInt(), 0x9b05688c.toInt(), 0x1f83d9ab.toInt(), 0x5be0cd19.toInt(),
        )
        val W = IntArray(64)
        val blocks = padded.size / 64
        for (block in 0 until blocks) {
            val blockBase = block * 64
            for (i in 0 until 16) {
                W[i] = ((padded[blockBase + i * 4].toInt() and 0xff) shl 24) or
                    ((padded[blockBase + i * 4 + 1].toInt() and 0xff) shl 16) or
                    ((padded[blockBase + i * 4 + 2].toInt() and 0xff) shl 8) or
                    (padded[blockBase + i * 4 + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                W[i] = s1(W[i - 2]) + W[i - 7] + s0(W[i - 15]) + W[i - 16]
            }
            var a = H[0]; var b = H[1]; var c = H[2]; var d = H[3]
            var e = H[4]; var f = H[5]; var g = H[6]; var h = H[7]
            for (i in 0 until 64) {
                val t1 = h + S1(e) + ch(e, f, g) + K[i] + W[i]
                val t2 = S0(a) + maj(a, b, c)
                h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            H[0] += a; H[1] += b; H[2] += c; H[3] += d
            H[4] += e; H[5] += f; H[6] += g; H[7] += h
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (H[i] ushr 24).toByte()
            out[i * 4 + 1] = (H[i] ushr 16).toByte()
            out[i * 4 + 2] = (H[i] ushr 8).toByte()
            out[i * 4 + 3] = H[i].toByte()
        }
        return out
    }

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))
    private fun ch(x: Int, y: Int, z: Int): Int = (x and y) or (x.inv() and z)
    private fun maj(x: Int, y: Int, z: Int): Int = (x and y) or (x and z) or (y and z)
    private fun s0(x: Int): Int = rotr(x, 7) xor rotr(x, 18) xor (x ushr 3)
    private fun s1(x: Int): Int = rotr(x, 17) xor rotr(x, 19) xor (x ushr 10)
    private fun S0(x: Int): Int = rotr(x, 2) xor rotr(x, 13) xor rotr(x, 22)
    private fun S1(x: Int): Int = rotr(x, 6) xor rotr(x, 11) xor rotr(x, 25)

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
