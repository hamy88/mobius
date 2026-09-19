package com.mobius.momo.data

import kotlinx.serialization.Serializable

/**
 * CHANGELOG 单条目的分类（§D7 + §5.1 release body markdown 解析结果）。
 *
 * - [Feature] ：新功能 / 体验改进（`**Feature:**` / `**Added:**` / `**Highlights:**` 等）
 * - [Fix]      ：缺陷修复（`**Fix:**` / `**Bug Fix:**` / `**修复:**`）
 * - [Breaking] ：破坏性变更（`**Breaking:**` / `**破坏性变更:**`）
 *
 * 类型用于 UI 层分组渲染（弹窗 + 设置页 → 关于 → 更新日志）。
 * 排序在 [ChangelogParser.parse] 内固定为 Breaking > Fix > Feature（UI 层可二次排序）。
 */
@Serializable
enum class ChangelogType { Feature, Fix, Breaking }

/**
 * CHANGELOG 单条目。
 *
 * [text] 在多行合并后含换行（ChangelogParser 合并相邻同 type 行），UI 层用 [String.lines] 拆分。
 */
@Serializable
data class ChangelogItem(
    val type: ChangelogType = ChangelogType.Feature,
    val text: String = "",
)