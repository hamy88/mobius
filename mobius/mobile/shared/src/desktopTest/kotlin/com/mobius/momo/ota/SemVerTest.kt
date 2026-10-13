package com.mobius.momo.ota

import com.mobius.momo.data.SemVer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemVerTest {

    @Test
    fun parse_basic_semver() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("1.2.3"))
    }

    @Test
    fun parse_with_v_prefix() {
        assertEquals(SemVer(0, 4, 0), SemVer.parse("v0.4.0"))
    }

    @Test
    fun parse_with_prerelease() {
        assertEquals(SemVer(0, 4, 0, "rc.1"), SemVer.parse("0.4.0-rc.1"))
    }

    @Test
    fun parse_returns_null_on_garbage() {
        assertNull(SemVer.parse("not-a-version"))
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse(null))
    }

    @Test
    fun release_greater_than_prerelease() {
        val v100 = SemVer(1, 0, 0)
        val v100rc = SemVer(1, 0, 0, "rc.1")
        assertTrue(v100 > v100rc, "正式版应 > prerelease")
    }

    @Test
    fun numeric_prerelease_segment_compares_as_int() {
        val rc1 = SemVer(1, 0, 0, "rc.1")
        val rc2 = SemVer(1, 0, 0, "rc.2")
        assertTrue(rc2 > rc1)
    }

    @Test
    fun string_prerelease_segment_compares_lexically() {
        val alpha = SemVer(1, 0, 0, "alpha")
        val beta = SemVer(1, 0, 0, "beta")
        assertTrue(beta > alpha)
    }

    @Test
    fun minor_increment_beats_patch() {
        val v010 = SemVer(0, 1, 0)
        val v009 = SemVer(0, 0, 9)
        assertTrue(v010 > v009)
    }

    // ===== 4 段版本号兼容 (0.4.5 / 0.4.5.1 形态) =====

    @Test
    fun parse_4_part_truncates_to_3() {
        // 第 4 段忽略，不参与 major/minor/patch 构造。
        assertEquals(SemVer(0, 4, 5), SemVer.parse("0.4.5.1"))
    }

    @Test
    fun parse_5_part_returns_null() {
        // 5 段及以上视为非法(防止拼接错误拼出 X.Y.Z.W.N 的版本号)。
        assertNull(SemVer.parse("0.4.5.1.2"))
    }

    @Test
    fun parse_4_part_compares_against_3_part() {
        val v451 = SemVer.parse("0.4.5.1")
        val v44 = SemVer.parse("0.4.4")
        assertTrue(v451!! > v44!!, "0.4.5.1 应 > 0.4.4")
    }

    @Test
    fun parse_4_part_equals_3_part_when_only_extra_part() {
        val v451 = SemVer.parse("0.4.5.1")
        val v45 = SemVer.parse("0.4.5")
        assertEquals(0, v451!!.compareTo(v45!!), "0.4.5.1 应等于 0.4.5(第4段被截断)")
    }
}
