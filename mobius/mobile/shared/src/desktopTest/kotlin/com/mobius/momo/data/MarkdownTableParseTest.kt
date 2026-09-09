package com.mobius.momo.data

import com.mobius.momo.ui.ensureBlankLineBeforeGfmTable
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 诊断并回归：聊天页 markdown 表格能否被正确解析为 TABLE 节点。
 *
 * 渲染链 = mikepenz `Markdown`，默认 flavour 经反编译确认是 `GFMFlavourDescriptor`(含 TABLE 解析)。
 * 这里用同一 flavour 直接验证「解析侧」行为, 并钉死 `ensureBlankLineBeforeGfmTable` 的修复。
 *
 * 关键结论: org.jetbrains.markdown 的 GFM 表格**要求表格块前有一空行**; LLM 输出常省略该空行,
 * 于是表格被当成普通段落 → 渲染成一堆带竖线的纯文本(用户反馈"表格未正确解析")。
 * 修复=渲染前经 ensureBlankLineBeforeGfmTable 补空行。
 */
class MarkdownTableParseTest {
    private val parser = MarkdownParser(GFMFlavourDescriptor())

    private fun countTables(text: String): Int {
        val root = parser.buildMarkdownTreeFromString(text)
        var n = 0
        fun walk(node: ASTNode) {
            if (node.type == GFMElementTypes.TABLE) n++
            node.children.forEach(::walk)
        }
        walk(root)
        return n
    }

    @Test
    fun valid_table_with_blank_line_before_parses() {
        val md = "说明：\n\n| 列1 | 列2 |\n|---|---|\n| a | b |"
        assertEquals(1, countTables(md), "合法表格(前面有空行)应解析为 1 个 TABLE 节点")
    }

    @Test
    fun table_directly_after_text_without_blank_line_does_not_parse_by_default() {
        // 表格紧贴上一段文字、无空行 → GFM 不识别为表格(回归钉死该限制)。
        val md = "说明：\n| 列1 | 列2 |\n|---|---|\n| a | b |"
        assertEquals(0, countTables(md), "无空行时表格不应被识别(这是要修的根因)")
    }

    @Test
    fun ensure_blank_line_fix_makes_no_blank_line_table_parse() {
        // 修复后: 同样的无空行表格, 经 ensureBlankLineBeforeGfmTable 补空行 → 解析为 TABLE。
        val md = "说明：\n| 列1 | 列2 |\n|---|---|\n| a | b |"
        val fixed = ensureBlankLineBeforeGfmTable(md)
        assertEquals(1, countTables(fixed), "补空行后无空行表格应解析为 TABLE")
    }

    @Test
    fun ensure_blank_line_is_idempotent_and_keeps_already_valid() {
        // 已有空行的合法表格不应被改动(幂等), 仍解析为 TABLE。
        val md = "说明：\n\n| 列1 | 列2 |\n|---|---|\n| a | b |"
        val fixed = ensureBlankLineBeforeGfmTable(md)
        assertEquals(md, fixed, "已合规的内容应原样返回(幂等)")
        assertEquals(1, countTables(fixed))
    }

    @Test
    fun malformed_no_separator_row_still_not_a_table() {
        // 截图内容: 只有一行像表头, 没有 |---|---| 分隔行 → 补空行也无济于事(本就不是表格)。
        val md = "| 1次提交 | 2条修改 | 1次未处理 |\n| 2e5d0d6 |\n| 1 |\n| git@github.com |"
        assertEquals(0, countTables(md))
        assertEquals(0, countTables(ensureBlankLineBeforeGfmTable(md)))
    }

    // ===== 列表是否有同类"块前需空行"问题? =====
    private fun countLists(text: String): Int {
        val root = parser.buildMarkdownTreeFromString(text)
        var n = 0
        fun walk(node: ASTNode) {
            val t = node.type
            if (t == MarkdownElementTypes.UNORDERED_LIST || t == MarkdownElementTypes.ORDERED_LIST) n++
            node.children.forEach(::walk)
        }
        walk(root)
        return n
    }

    @Test
    fun bullet_list_with_blank_line_before_parses() {
        val md = "说明：\n\n- 一\n- 二\n- 三"
        assertEquals(1, countLists(md), "无序列表(前有空行)应解析为 LIST")
    }

    @Test
    fun bullet_list_directly_after_text_no_blank_line() {
        // 无序列表紧贴文字: CommonMark 允许列表打断段落 → 应仍解析为 LIST(与表格不同)。
        val md = "说明：\n- 一\n- 二\n- 三"
        assertEquals(1, countLists(md), "无序列表紧贴文字(无空行)也应解析为 LIST")
    }

    @Test
    fun ordered_list_starting_with_1_after_text_parses() {
        // 以 1. 开头的有序列表可打断段落。
        val md = "说明：\n1. 一\n2. 二"
        assertEquals(1, countLists(md), "以 1. 开头的有序列表紧贴文字应解析为 LIST")
    }

    @Test
    fun ordered_list_starting_with_non_1_after_text_also_parses() {
        // 实测: org.jetbrains.markdown 比 CommonMark 宽松, 即使以非 1(如 2.) 开头、紧贴文字、无空行,
        // 有序列表仍被识别为 LIST → 列表没有表格那种"块前需空行"的问题。
        val md = "说明：\n2. 一\n3. 二"
        assertEquals(1, countLists(md), "非 1 开头的有序列表紧贴文字也应解析为 LIST")
    }

    @Test
    fun ordered_list_starting_with_non_1_with_blank_line_parses() {
        // 但前面补空行后, 非 1 开头的有序列表也能解析(印证空行的作用)。
        val md = "说明：\n\n2. 一\n3. 二"
        assertEquals(1, countLists(md), "非 1 开头的有序列表前面有空行时应解析为 LIST")
    }
}
