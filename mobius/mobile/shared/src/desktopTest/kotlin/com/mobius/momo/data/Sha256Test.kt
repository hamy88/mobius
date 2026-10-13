package com.mobius.momo.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 0.4.5.3: 校验 [Sha256.hex] 与 NIST 公开测试向量一致, 防回归.
 */
class Sha256Test {

    @Test
    fun `empty string hashes to known SHA-256`() {
        // FIPS 180-4 Appendix B.1
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(""),
        )
    }

    @Test
    fun `abc hashes to known SHA-256`() {
        // FIPS 180-4 Appendix B.1
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc"),
        )
    }

    @Test
    fun `typical baseUrl hashes deterministically`() {
        val a = Sha256.hex("https://example.com:33316")
        val b = Sha256.hex("https://example.com:33316")
        assertEquals(a, b)
        assertEquals(64, a.length, "SHA-256 hex 应为 64 字符")
    }

    @Test
    fun `different baseUrls hash to different values`() {
        val a = Sha256.hex("https://a.example.com")
        val b = Sha256.hex("https://b.example.com")
        assertEquals(false, a == b)
    }
}
