package com.mobius.momo.data

/**
 * GitHub Release body markdown → 用户可读 [ChangelogItem] 列表。
 *
 * 设计意图（§D7 + §5.1 release body 模板）：
 * - 输入：GitHub Releases API 的 `body` 字段（markdown 字符串）。
 * - 输出：UI 层 / 设置页"关于 → 更新日志"可直接渲染的扁平列表。
 *
 * 解析规则（与 §7 模板对齐，兼容中英文）：
 * - `**Breaking:**` / `**Breaking Change:**` / `**破坏性变更:**` / `**破坏性变化:**` → [ChangelogType.Breaking]
 * - `**Fix:**` / `**Bug Fix:**` / `**Bugfix:**` / `**Fixes:**` / `**修复:**` / `**Bug 修复:**` → [ChangelogType.Fix]
 * - 其他 `**...:**` 开头 → [ChangelogType.Feature]（默认）
 * - 列表项自动去掉前导 `- ` / `* ` 标记
 * - 合并相邻相同 [ChangelogType] 的条目（多行 Feature 常见）
 *
 * 边界处理：
 * - 输入 null / 空白 → 返回空列表（不抛错）
 * - 标签缺失（如整个 release body 都是纯文本）→ 全部视为 [ChangelogType.Feature]
 * - HTML 标签不剥离（§5.1 模板不使用 HTML）
 */
object ChangelogParser {

    private val BREAKING_KEYS: List<String> = listOf(
        "breaking",
        "breaking change",
        "breaking changes",
        "破坏性变更",
        "破坏性变化",
    )

    private val FIX_KEYS: List<String> = listOf(
        "fix",
        "fixes",
        "bug fix",
        "bugfix",
        "修复",
        "bug 修复",
        "bug修复",
    )

    /**
     * 主入口。
     *
     * @param body GitHub Release body markdown；null / 空白返回空列表
     * @return 扁平 changelog 项；按 release body 出现顺序排列，同 type 相邻合并
     */
    fun parse(body: String?): List<ChangelogItem> {
        val raw = body?.takeIf { it.isNotBlank() } ?: return emptyList()

        val result = mutableListOf<ChangelogItem>()
        var currentType: ChangelogType = ChangelogType.Feature

        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // 识别 markdown 二级标题 `## Xxx` —— release body 段头，作为分类边界
            if (trimmed.startsWith("## ")) {
                val headerText = trimmed.removePrefix("## ").trim()
                currentType = classify(headerText.lowercase())
                continue
            }

            // 识别粗体标签 `**Xxx:**` —— release body 单行标签
            val labelMatch = LABEL_REGEX.find(trimmed)
            if (labelMatch != null) {
                val label = labelMatch.groupValues[1].trim().lowercase()
                val rest = labelMatch.groupValues[2].trim()
                currentType = classify(label)
                if (rest.isNotEmpty()) {
                    appendOrMerge(result, currentType, stripBullet(rest))
                }
                continue
            }

            // 一级标题 `#` / 三级以上 `###` —— 跳过（不入 changelog）
            if (trimmed.startsWith("#")) continue

            // 普通列表项 / 段落：去掉前导 `- ` / `* `
            val text = stripBullet(trimmed)
            if (text.isEmpty()) continue
            appendOrMerge(result, currentType, text)
        }

        return result
    }

    private val LABEL_REGEX = Regex("""^\*\*([^*]+?):\*\*\s*(.*)$""")

    private fun stripBullet(line: String): String {
        val s = line.trim()
        return when {
            s.startsWith("- ") -> s.removePrefix("- ")
            s.startsWith("* ") -> s.removePrefix("* ")
            s.startsWith("• ") -> s.removePrefix("• ")
            else -> s
        }.trim()

    }

    private fun classify(label: String): ChangelogType {
        val normalized = label.lowercase()
        return when {
            BREAKING_KEYS.any { normalized.contains(it) } -> ChangelogType.Breaking
            FIX_KEYS.any { normalized.contains(it) } -> ChangelogType.Fix
            else -> ChangelogType.Feature
        }
    }

    private fun appendOrMerge(target: MutableList<ChangelogItem>, type: ChangelogType, text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        val last = target.lastOrNull()
        if (last != null && last.type == type) {
            val merged = if (last.text.endsWith("\n") || last.text.isEmpty()) {
                last.text + cleaned
            } else {
                last.text + "\n" + cleaned
            }
            target[target.size - 1] = ChangelogItem(type, merged)
        } else {
            target.add(ChangelogItem(type, cleaned))
        }
    }
}