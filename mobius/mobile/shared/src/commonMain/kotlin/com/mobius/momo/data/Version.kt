package com.mobius.momo.data

/**
 * 轻量 semver 比对（MAJOR.MINOR.PATCH + 可选 prerelease）。
 *
 * 不引入三方库，避免为单点比对付出传递依赖代价。语义遵循 semver 2.0.0：
 * - 缺失字段按 0 补齐
 * - prerelease 按字典序逐段比（数字按整数、字符串按字典序），无 prerelease > 有 prerelease
 *
 * 仅用于 OTA 阈值比对，不用于构建产物签名校验。
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: String? = null) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        // 1) 数字段（major → minor → patch）
        val byMajor = major.compareTo(other.major)
        if (byMajor != 0) return byMajor
        val byMinor = minor.compareTo(other.minor)
        if (byMinor != 0) return byMinor
        val byPatch = patch.compareTo(other.patch)
        if (byPatch != 0) return byPatch

        // 2) prerelease：空 > 有；非空时按 "x.y.z" 拆分（数字按整数比，字符串按字典序）
        val mine = preRelease?.takeIf { it.isNotBlank() }
        val theirs = other.preRelease?.takeIf { it.isNotBlank() }
        return when {
            mine == null && theirs == null -> 0
            mine == null -> 1   // 正式版 > prerelease
            theirs == null -> -1
            else -> comparePreRelease(mine, theirs)
        }
    }

    private fun comparePreRelease(a: String, b: String): Int {
        val aIds = a.split('.')
        val bIds = b.split('.')
        val common = minOf(aIds.size, bIds.size)
        for (i in 0 until common) {
            val av = aIds[i]
            val bv = bIds[i]
            val aIsNum = av.toIntOrNull() != null
            val bIsNum = bv.toIntOrNull() != null
            val cmp = when {
                aIsNum && bIsNum -> av.toInt().compareTo(bv.toInt())
                aIsNum -> -1 // 数字 < 非数字（semver 规则）
                bIsNum -> 1
                else -> av.compareTo(bv)
            }
            if (cmp != 0) return cmp
        }
        return aIds.size.compareTo(bIds.size)
    }

    override fun toString(): String =
        if (preRelease.isNullOrBlank()) "$major.$minor.$patch" else "$major.$minor.$patch-$preRelease"

    companion object {
        /** 容错解析：失败回退到 0.0.0，调用方应自行决定是否视为无效。 */
        fun parse(raw: String?): SemVer? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // 兼容 "v" 前缀（GitHub tag 习惯 mobile-v0.4.0）。
            val normalized = text.removePrefix("v").removePrefix("V")
            val mainAndPre = normalized.split("-", limit = 2)
            val parts = mainAndPre[0].split(".")
            if (parts.isEmpty() || parts.size > 3) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            val major = nums.getOrElse(0) { 0 }
            val minor = nums.getOrElse(1) { 0 }
            val patch = nums.getOrElse(2) { 0 }
            val pre = mainAndPre.getOrNull(1)?.takeIf { it.isNotBlank() }
            return SemVer(major, minor, patch, pre)
        }

        /** 严格解析：失败时抛 IllegalArgumentException；用于 §5.5 强校验路径。 */
        fun parseStrict(raw: String?): SemVer =
            parse(raw) ?: throw IllegalArgumentException("invalid semver: $raw")
    }
}
