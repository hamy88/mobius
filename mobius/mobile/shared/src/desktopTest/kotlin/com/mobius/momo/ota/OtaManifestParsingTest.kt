package com.mobius.momo.ota

import com.mobius.momo.data.OtaManifest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 验证 D1 数据契约：
 * - §5.3 顶层字段全部可序列化往返
 * - §5.5 强校验字段缺失检测（编程式，解析层不抛错由调用方判）
 * - §10.5.1 五字段组合（min_supported_version + 4 配套字段）
 */
class OtaManifestParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun `manifest round-trips with full §10_5_1 fields`() {
        val raw = """
            {
              "version": "0.4.1",
              "version_code": 25,
              "channel": "stable",
              "released_at": "2026-09-19T20:00:00+08:00",
              "min_supported_version": "0.3.0",
              "min_supported_version_level": "hard_block",
              "min_supported_version_reason": "security_cve",
              "min_supported_version_advisory_id": "CVE-2026-12345",
              "hard_block_bypassable": true,
              "android": {
                "min_sdk": 26,
                "target_sdk": 34,
                "signature_scheme": ["v2", "v3"],
                "package_name": "com.mobius.momo",
                "request_install_packages_declared": true,
                "post_notifications_required": true
              },
              "delta_enabled": false,
              "release_notes_url": "https://github.com/hamy88/mobius/releases/tag/mobile-v0.4.1",
              "builds": [
                {
                  "platform": "android",
                  "abi": "arm64-v8a",
                  "version": "0.4.1",
                  "version_code": 25,
                  "url": "https://github.com/.../mobius-mobile-0.4.1-android-arm64-v8a.apk",
                  "size": 5024928,
                  "sha256": "aa08e109deadbeef"
                }
              ]
            }
        """.trimIndent()

        val m = json.decodeFromString(OtaManifest.serializer(), raw)
        assertEquals("0.4.1", m.version)
        assertEquals(25, m.versionCode)
        assertEquals("0.3.0", m.minSupportedVersion)
        assertEquals("hard_block", m.minSupportedVersionLevel)
        assertEquals("security_cve", m.minSupportedVersionReason)
        assertEquals("CVE-2026-12345", m.minSupportedVersionAdvisoryId)
        assertEquals(true, m.hardBlockBypassable)
        assertEquals(34, m.android.targetSdk)
        assertEquals(listOf("v2", "v3"), m.android.signatureScheme)
        assertEquals("com.mobius.momo", m.android.packageName)
        assertEquals(1, m.builds.size)
        assertEquals("arm64-v8a", m.builds[0].abi)
        assertEquals(5024928L, m.builds[0].size)
    }

    @Test
    fun `missing optional fields use defensive defaults`() {
        val raw = """{ "version": "0.4.0", "version_code": 24 }"""
        val m = json.decodeFromString(OtaManifest.serializer(), raw)
        assertEquals("0.4.0", m.version)
        assertEquals("stable", m.channel)
        assertNullOrEmpty(m.minSupportedVersion)
        assertNullOrEmpty(m.minSupportedVersionLevel)
        assertEquals(true, m.hardBlockBypassable, "默认 bypassable=true")
        assertEquals(false, m.deltaEnabled)
        assertTrue(m.builds.isEmpty())
        assertEquals(0, m.android.minSdk)
    }

    @Test
    fun `patch_from map parses for §4 bsdiff 增量`() {
        val raw = """
            {
              "version": "0.4.0",
              "version_code": 24,
              "builds": [
                {
                  "platform": "android",
                  "abi": "arm64-v8a",
                  "version": "0.4.0",
                  "version_code": 24,
                  "url": "https://.../mobius-mobile-0.4.0-android-arm64-v8a.apk",
                  "size": 5024928,
                  "sha256": "aa08",
                  "patch_from": {
                    "0.3.0": {
                      "url": "https://.../mobius-mobile-0.3.0-to-0.4.0-android-arm64-v8a.patch",
                      "size": 800000,
                      "sha256": "bb09"
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val m = json.decodeFromString(OtaManifest.serializer(), raw)
        val build = m.builds.first()
        assertNotNull(build.patchFrom["0.3.0"])
        assertEquals(800000L, build.patchFrom["0.3.0"]!!.size)
        assertEquals("bb09", build.patchFrom["0.3.0"]!!.sha256)
    }

    private fun assertNullOrEmpty(value: String?) {
        if (value != null) {
            assertTrue(value.isEmpty(), "expected null or empty, got $value")
        }
    }
}
