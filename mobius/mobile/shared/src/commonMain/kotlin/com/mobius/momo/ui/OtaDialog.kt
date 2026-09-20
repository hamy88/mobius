package com.mobius.momo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobius.momo.data.ChangelogItem
import com.mobius.momo.viewmodel.ThresholdEvaluator
import kotlinx.serialization.Serializable

/**
 * §10.5.3.2 中文模板 + §10.5.3.3 英文模板的文案生成器（纯函数，单测可覆盖）。
 *
 * 所有文案长度遵守 §10.5.3.1 限制：
 * - 标题 ≤ 24 中文字符 / 48 英文字符
 * - 正文 ≤ 160 中文字符
 * - 按钮 ≤ 4 中文字符 / ≤ 2 个英文单词
 *
 * 重大原因（reason ≠ `other`）时附"为什么需要更新"说明框，默认折叠（advisory）/ 默认展开
 * （strong_advisory / hard_block）；hard_block 说明框不可关闭。
 */
object OtaDialogCopy {

    /** 渲染文案所需的全部上下文。 */
    data class Context(
        val localVersion: String,
        val remoteVersion: String,
        val minSupportedVersion: String? = null,
        val reasonDisplay: String? = null,
        val advisoryId: String? = null,
        val hardBlockBypassable: Boolean = true,
        /** Normal 档首条摘要文本（≤60 字）。为空时 normal() 兜底"新功能与体验改进"。 */
        val releaseHighlight: String? = null,
        /** 完整 changelog 条目列表（用于"查看完整更新说明"链接）。空 → 不渲染链接。 */
        val changelogItems: List<ChangelogItem> = emptyList(),
        /** 点击"查看完整更新说明"时触发；UI 层打开全屏 modal 渲染 [changelogItems]。 */
        val onViewFullNotes: (() -> Unit)? = null,
    )

    /** 单条文案渲染结果。 */
    @Serializable
    data class Copy(
        val title: String,
        val body: String,
        val infoBoxLines: List<String> = emptyList(),
        val infoBoxExpandedByDefault: Boolean = false,
        val infoBoxDismissible: Boolean = true,
        val primaryLabel: String,
        val secondaryLabel: String? = null,
        val tertiaryLabel: String? = null,
        // 0.4.3 新增: "查看完整更新说明" 链接开关 + 标签文案
        val showFullNotesLink: Boolean = false,
        val fullNotesLinkLabel: String = "查看完整更新说明",
    )

    /** 主入口：按档位 + bypassable 渲染对应文案。 */
    fun render(level: ThresholdEvaluator.Level, ctx: Context): Copy = when (level) {
        ThresholdEvaluator.Level.Normal -> normal(ctx)
        ThresholdEvaluator.Level.Advisory -> advisory(ctx)
        ThresholdEvaluator.Level.StrongAdvisory -> strongAdvisory(ctx)
        ThresholdEvaluator.Level.HardBlock -> if (ctx.hardBlockBypassable) hardBlockBypassable(ctx) else hardBlockMandatory(ctx)
        ThresholdEvaluator.Level.NoUpdate,
        ThresholdEvaluator.Level.Invalid -> error("NoUpdate / Invalid 不应弹窗")
    }

    // ===== §10.5.3.2 中文模板 =====
    private fun normal(ctx: Context): Copy = Copy(
        title = "新版本可用",
        body = "v${ctx.remoteVersion} 已发布（v${ctx.localVersion} → v${ctx.remoteVersion}）。" +
            (ctx.releaseHighlight?.take(60)?.takeIf { it.isNotBlank() } ?: "新功能与体验改进。"),
        infoBoxLines = emptyList(),
        infoBoxExpandedByDefault = false,
        infoBoxDismissible = true,
        primaryLabel = "立即更新",
        secondaryLabel = "稍后",
        tertiaryLabel = "忽略此版本",
        // 0.4.3: 有 changelog + 有回调才渲染"查看完整更新说明"链接；UI 层打开 modal 渲染完整内容
        showFullNotesLink = ctx.changelogItems.isNotEmpty() && ctx.onViewFullNotes != null,
    )

    private fun advisory(ctx: Context): Copy = Copy(
        title = "建议尽快更新",
        body = "v${ctx.remoteVersion} 修复了重要问题，建议尽快升级。最低支持版本为 v${ctx.minSupportedVersion ?: "?"}。",
        infoBoxLines = buildList {
            ctx.reasonDisplay?.let { add("原因：$it") }
            // 详情占位：实际填充由 UI 层从 RELEASE_NOTES.md 拉首条；本期先空行
        },
        infoBoxExpandedByDefault = false,
        infoBoxDismissible = true,
        primaryLabel = "立即更新",
        secondaryLabel = "稍后",
        tertiaryLabel = null,
    )

    private fun strongAdvisory(ctx: Context): Copy = Copy(
        title = "⚠️ 必须更新（强烈建议）",
        body = "v${ctx.remoteVersion} 涉及关键变更，未升级可能影响核心功能。最低支持版本为 v${ctx.minSupportedVersion ?: "?"}。",
        infoBoxLines = buildList {
            ctx.reasonDisplay?.let { add("原因：$it") }
            ctx.advisoryId?.let { add("公告号：$it") }
        },
        infoBoxExpandedByDefault = true,
        infoBoxDismissible = true,
        primaryLabel = "立即更新",
        secondaryLabel = "稍后",
        tertiaryLabel = null,
    )

    private fun hardBlockBypassable(ctx: Context): Copy = Copy(
        title = "🚨 检测到严重安全风险",
        body = "v${ctx.remoteVersion} 修复了已被在野利用的安全漏洞（${ctx.advisoryId ?: "?"}）。" +
            "强烈建议立即升级。继续使用将面临已知风险。",
        infoBoxLines = buildList {
            ctx.advisoryId?.let { add("漏洞编号：$it") }
            ctx.reasonDisplay?.let { add("影响：$it") }
            add("缓解建议：升级至 v${ctx.minSupportedVersion ?: "?"} 或更高版本")
        },
        infoBoxExpandedByDefault = true,
        infoBoxDismissible = false, // hard_block 说明框不可关闭
        primaryLabel = "立即更新",
        secondaryLabel = "继续使用（受限）",
        tertiaryLabel = null,
    )

    private fun hardBlockMandatory(ctx: Context): Copy = Copy(
        title = "🚨 检测到严重安全风险（必须更新）",
        body = "v${ctx.remoteVersion} 修复了灾难性安全漏洞。继续使用将无法连接服务。请立即升级。",
        infoBoxLines = buildList {
            ctx.advisoryId?.let { add("漏洞编号：$it") }
            add("影响：连接服务失败 / 数据损坏 / 等")
            add("缓解建议：升级至 v${ctx.minSupportedVersion ?: "?"} 或更高版本")
        },
        infoBoxExpandedByDefault = true,
        infoBoxDismissible = false,
        primaryLabel = "立即更新",
        secondaryLabel = null,  // 无其他按钮
        tertiaryLabel = null,
    )
}

/**
 * OTA 4 档弹窗 Compose 渲染层（v1.1）。
 *
 * 文件结构：
 * - 4 个 @Composable 函数对应 §10.5.2 四档（normal / advisory / strong_advisory / hard_block）
 * - hard_block 内部根据 bypassable 切两套（bypassable=true 一组 / bypassable=false 单按钮）
 * - 文案生成由 [OtaDialogCopy.render] 负责，纯函数化便于单测 snapshot
 *
 * MomoTypography / MomoTheme 是 MomoApp.kt 的私有 object，本文件复用一致的字号 / 颜色
 * 以保持视觉风格统一（dark/light 走外部传入的 [OtaColors]）。
 */
data class OtaColors(
    val background: androidx.compose.ui.graphics.Color,
    val onBackground: androidx.compose.ui.graphics.Color,
    val onBackgroundMuted: androidx.compose.ui.graphics.Color,
    val accent: androidx.compose.ui.graphics.Color,
    val danger: androidx.compose.ui.graphics.Color,
    val divider: androidx.compose.ui.graphics.Color,
)

/** 通用：normal 档（三按钮）。 */
@Composable
fun OtaNormalDialog(
    copy: OtaDialogCopy.Copy,
    colors: OtaColors,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
    onViewFullNotes: (() -> Unit)? = null,
) {
    BaseOtaDialog(
        copy = copy,
        colors = colors,
        onDismiss = onDismiss,
        onViewFullNotes = onViewFullNotes,
        buttons = {
            TextButton(onClick = onIgnore) {
                Text(copy.tertiaryLabel ?: "忽略此版本", color = colors.onBackgroundMuted)
            }
            TextButton(onClick = onLater) {
                Text(copy.secondaryLabel ?: "稍后", color = colors.onBackgroundMuted)
            }
            TextButton(onClick = onUpdate) {
                Text(copy.primaryLabel, color = colors.accent, fontWeight = FontWeight.Bold)
            }
        },
    )
}

/** 通用：advisory 档（两按钮，无"忽略此版本"）。 */
@Composable
fun OtaAdvisoryDialog(
    copy: OtaDialogCopy.Copy,
    colors: OtaColors,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onDismiss: () -> Unit,
    onViewFullNotes: (() -> Unit)? = null,
) {
    BaseOtaDialog(
        copy = copy,
        colors = colors,
        onDismiss = onDismiss,
        onViewFullNotes = onViewFullNotes,
        buttons = {
            TextButton(onClick = onLater) {
                Text(copy.secondaryLabel ?: "稍后", color = colors.onBackgroundMuted)
            }
            TextButton(onClick = onUpdate) {
                Text(copy.primaryLabel, color = colors.accent, fontWeight = FontWeight.Bold)
            }
        },
    )
}

/** 通用：strong_advisory 档（两按钮 + 设置页红点由业务侧另开）。 */
@Composable
fun OtaStrongAdvisoryDialog(
    copy: OtaDialogCopy.Copy,
    colors: OtaColors,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onDismiss: () -> Unit,
    onViewFullNotes: (() -> Unit)? = null,
) {
    OtaAdvisoryDialog(copy, colors, onUpdate, onLater, onDismiss, onViewFullNotes)
}

/** 通用：hard_block 档（按 bypassable 切两套）。 */
@Composable
fun OtaHardBlockDialog(
    copy: OtaDialogCopy.Copy,
    colors: OtaColors,
    bypassable: Boolean,
    onUpdate: () -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    onViewFullNotes: (() -> Unit)? = null,
) {
    if (bypassable) {
        BaseOtaDialog(
            copy = copy,
            colors = colors,
            onDismiss = onDismiss,
            onViewFullNotes = onViewFullNotes,
            buttons = {
                TextButton(onClick = onContinue) {
                    Text(copy.secondaryLabel ?: "继续使用（受限）", color = colors.danger)
                }
                TextButton(onClick = onUpdate) {
                    Text(copy.primaryLabel, color = colors.accent, fontWeight = FontWeight.Bold)
                }
            },
        )
    } else {
        // bypassable=false：仅 [立即更新] 一个按钮
        BaseOtaDialog(
            copy = copy,
            colors = colors,
            onDismiss = onDismiss,
            onViewFullNotes = onViewFullNotes,
            buttons = {
                TextButton(onClick = onUpdate) {
                    Text(copy.primaryLabel, color = colors.accent, fontWeight = FontWeight.Bold)
                }
            },
        )
    }
}

// ===== 内部：统一弹窗骨架 =====

@Composable
private fun BaseOtaDialog(
    copy: OtaDialogCopy.Copy,
    colors: OtaColors,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit,
    onViewFullNotes: (() -> Unit)? = null,
) {
    var infoExpanded by rememberSaveable(copy.title) {
        mutableStateOf(copy.infoBoxExpandedByDefault)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        titleContentColor = colors.onBackground,
        textContentColor = colors.onBackgroundMuted,
        title = {
            Text(
                copy.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    copy.body,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = colors.onBackgroundMuted,
                )
                if (copy.infoBoxLines.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    InfoBox(
                        lines = copy.infoBoxLines,
                        expanded = infoExpanded,
                        onToggle = { if (copy.infoBoxDismissible) infoExpanded = !infoExpanded },
                        colors = colors,
                    )
                }
                // 0.4.3: "查看完整更新说明"链接 — 仅当 showFullNotesLink=true 且回调非空时渲染
                if (copy.showFullNotesLink && onViewFullNotes != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onViewFullNotes,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            copy.fullNotesLinkLabel,
                            color = colors.accent,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        },
        confirmButton = buttons,
    )
}

@Composable
private fun InfoBox(
    lines: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    colors: OtaColors,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = colors.onBackground.copy(alpha = 0.05f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (expanded) "▼ 为什么需要更新" else "▶ 为什么需要更新",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onBackground,
        )
        if (expanded) {
            HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
            lines.forEach { line ->
                Text("• $line", fontSize = 13.sp, color = colors.onBackgroundMuted)
            }
        }
    }
    // remember 调用占位：onToggle 由调用方捕获；保留以备 hard_block 不可关闭场景下的可访问性
    remember(lines, expanded) { Unit }
    remember(onToggle) { Unit }
}
