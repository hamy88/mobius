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
}
