package com.mobius.momo.ui

/**
 * GFM 表格解析(org.jetbrains.markdown / mikepenz 默认 GFMFlavourDescriptor)要求表格块前有一空行;
 * 否则紧贴上一段文字的表格会被当成普通段落, 渲染成一堆带竖线的纯文本(用户反馈"表格未正确解析")。
 * LLM 输出常省略该空行, 这里在渲染前补上。
 *
 * 仅在"当前行像表头(含 |)且下一行是 GFM 表格分隔行(|---|---|)"、且上一行非空时插入一空行, 幂等。
 */
internal fun ensureBlankLineBeforeGfmTable(content: String): String {
    if ('|' !in content) return content
    val lines = content.split("\n")
    val out = ArrayList<String>(lines.size + 4)
    for (i in lines.indices) {
        val isHeader = i + 1 < lines.size &&
            '|' in lines[i] &&
            isGfmTableSeparator(lines[i + 1])
        val prevNotBlank = out.isNotEmpty() && out.last().isNotBlank()
        if (isHeader && prevNotBlank) out.add("")
        out.add(lines[i])
    }
    return if (out.size == lines.size) content else out.joinToString("\n")
}

// GFM 表格分隔行: 仅含 | : - 与空白, 且至少含一个 - 和一个 |。匹配 |---|---|、| :-- | --: |、---|--- 等。
private fun isGfmTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty() || '-' !in t || '|' !in t) return false
    return t.all { it == '|' || it == ':' || it == '-' || it.isWhitespace() }
}
