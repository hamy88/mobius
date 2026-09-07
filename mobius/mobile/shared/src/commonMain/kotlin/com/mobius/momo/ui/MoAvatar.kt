package com.mobius.momo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import kotlin.math.min
import kotlin.math.sin
import momo_mobile.shared.generated.resources.Res
import momo_mobile.shared.generated.resources.mobius_brand_logo
import org.jetbrains.compose.resources.painterResource
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween

private data class ParticleSpec(
    val x: Float,
    val y: Float,
    val size: Float,
    /** 唯一 index, 用于抽签式生成每颗粒子独立的随机漂移参数. */
    val seed: Int,
)

/**
 * 抽签: 用 index 派生出 [0,1) 的确定性伪随机数 (避免依赖 kotlin.random,
 * Compose drawScope 也好用). 同一 index 永远一致 → 重组不抖动.
 */
private fun seedFloat(i: Int, salt: Int): Float {
    val raw = (i.toLong() * 2654435761L + salt * 40503L) and 0xFFFFFFFFL
    val u = raw.toFloat() / 4294967295f
    return if (u < 0f) u + 1f else u
}

/**
 * 一颗粒子的"随机漂移参数": 取两组正交正弦, 频率为无理比 (黄金相关),
 * 永不精确闭合, 视觉上像在圆里随机游走. driftAmp = 总位移幅度 (相对半径).
 */
private data class DriftParams(
    val fx1: Float, val fx2: Float,
    val fy1: Float, val fy2: Float,
    val phx1: Float, val phx2: Float,
    val phy1: Float, val phy2: Float,
    val amp: Float,
)

private fun driftParams(i: Int): DriftParams {
    // 1.0..1.9 / 2.2..3.4 / 1.1..2.1 / 2.7..4.0 — 频率比皆无理数比例, 不互成整数倍.
    return DriftParams(
        fx1 = 1.0f + seedFloat(i, 31) * 0.9f,
        fx2 = 2.2f + seedFloat(i, 37) * 1.2f,
        fy1 = 1.1f + seedFloat(i, 41) * 1.0f,
        fy2 = 2.7f + seedFloat(i, 43) * 1.3f,
        phx1 = seedFloat(i, 47) * (kotlin.math.PI.toFloat() * 2f),
        phx2 = seedFloat(i, 53) * (kotlin.math.PI.toFloat() * 2f),
        phy1 = seedFloat(i, 59) * (kotlin.math.PI.toFloat() * 2f),
        phy2 = seedFloat(i, 61) * (kotlin.math.PI.toFloat() * 2f),
        // 漂移幅度 0.035..0.075 r (避免某些粒子飘太远)
        amp = 0.035f + seedFloat(i, 71) * 0.040f,
    )
}

// 18 个白色粒子: 固定位置 + 大小不一, drift 参数从 index 抽签.
private val PARTICLES = listOf(
    ParticleSpec(0.47f, 0.05f, 0.070f, 0),
    ParticleSpec(0.63f, 0.10f, 0.045f, 1),
    ParticleSpec(0.75f, 0.22f, 0.065f, 2),
    ParticleSpec(0.86f, 0.42f, 0.040f, 3),
    ParticleSpec(0.76f, 0.66f, 0.085f, 4),
    ParticleSpec(0.60f, 0.80f, 0.055f, 5),
    ParticleSpec(0.42f, 0.86f, 0.042f, 6),
    ParticleSpec(0.22f, 0.76f, 0.060f, 7),
    ParticleSpec(0.10f, 0.57f, 0.050f, 8),
    ParticleSpec(0.08f, 0.36f, 0.068f, 9),
    ParticleSpec(0.32f, 0.95f, 0.045f, 15),
    ParticleSpec(0.95f, 0.50f, 0.052f, 16),
    ParticleSpec(0.05f, 0.10f, 0.038f, 17),
    ParticleSpec(0.18f, 0.18f, 0.045f, 10),
    ParticleSpec(0.32f, 0.11f, 0.062f, 11),
    ParticleSpec(0.54f, 0.31f, 0.038f, 12),
    ParticleSpec(0.65f, 0.47f, 0.058f, 13),
    ParticleSpec(0.48f, 0.63f, 0.048f, 14),
)

/**
 * MoAvatar — 字面对齐网页 .momo-orb 的 CSS 多层背景:
 *
 *   background:
 *     radial-gradient(circle at 35% 29%, rgba(255,255,255,.96) 0 5%, transparent 16%),
 *     radial-gradient(circle at 62% 56%, rgba(20,184,166,.64) 0 10%, transparent 38%),
 *     radial-gradient(circle at 35% 70%, rgba(251,113,133,.46) 0 8%, transparent 33%),
 *     radial-gradient(circle at 72% 28%, rgba(250,204,21,.42) 0 7%, transparent 30%),
 *     conic-gradient(from 30deg, #2dd4bf47, #818cf857, #fb71852e, #facc1538, #2dd4bf47),
 *     conic-gradient(from 140deg, rgba(56,189,248,.88), rgba(129,140,248,.82),
 *                    rgba(236,72,153,.58), rgba(45,212,191,.92), rgba(56,189,248,.88));
 *
 *   两层环: 外环 inset 8% border-radius 46%/55% 54%/42% 42%/58% 58%/45% #E0F2FE .34
 *          内环 inset 25% border-radius 58%/43% 42%/57% 54%/41% 46%/59% #FDBA74 .38
 *
 *   18 个白色粒子, 大小不一, 每颗独立随机慢慢漂 (irrational-freq 叠加正弦)
 *   一个白色核心在中心, 脉动
 */
@Composable
fun MoAvatar(
    sizeDp: Dp,
    active: Boolean = false,
    lite: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "moAvatar")
    val corePulse by transition.animateFloat(
        initialValue = 0.88f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(5800, easing = LinearEasing), RepeatMode.Reverse),
        label = "corePulse",
    )
    val particlePhase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart),
        label = "particlePhase",
    )

    Canvas(
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape),
    ) {
        val w = size.width
        val h = size.height
        val radius = min(w, h) / 2f
        val center = Offset(w / 2f, h / 2f)

        // === 1. 底层 conic-gradient (CSS from 140deg, 5 段, 颜色 + 透明度照搬) ===
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color(0xFF38BDF8).copy(alpha = 0.88f), // sky
                    Color(0xFF818CF8).copy(alpha = 0.82f), // indigo
                    Color(0xFFEC4899).copy(alpha = 0.58f), // pink
                    Color(0xFF2DD4BF).copy(alpha = 0.92f), // teal
                    Color(0xFF38BDF8).copy(alpha = 0.88f), // sky 回环
                ),
                center = center,
            ),
            startAngle = 140f,
            sweepAngle = 360f,
            useCenter = true,
            topLeft = Offset.Zero,
            size = Size(w, h),
        )

        // === 2. 底部暗化 (CSS 没明写但实际渲染需要, 微量) ===
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Transparent, Color(0xFF0F172A).copy(alpha = 0.18f)),
                center = Offset(w * 0.5f, h * 0.78f),
                radius = radius * 0.8f,
            ),
            center = center, radius = radius,
        )

        // === 2.5. 第二层 conic-gradient (CSS 字面照搬, from 30deg, 软色调叠加) ===
        // 颜色 + 透明度直接取 #RRGGBBAA 的 AA, 不另算. 整体 α 0.18..0.34,
        // 作为底层上的"tint" — 让主 conic 在某些角度透出更暖/更冷.
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color(0x472DD4BF), // teal    α 0x47 = 0.278
                    Color(0x57818CF8), // indigo  α 0x57 = 0.341
                    Color(0x2EFB7185), // pink    α 0x2E = 0.180
                    Color(0x38FACC15), // yellow  α 0x38 = 0.220
                    Color(0x472DD4BF), // teal 回环
                ),
                center = center,
            ),
            startAngle = 30f,
            sweepAngle = 360f,
            useCenter = true,
            topLeft = Offset.Zero,
            size = Size(w, h),
        )

        // === 3. 4 个色斑 (CSS 字面照搬, 含透明度 stops) ===
        // 高光白: 35%/29%, 0..5% solid white α0.96, 16% transparent
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = 0.96f),
                    (0.05f / 0.16f) to Color.White.copy(alpha = 0.96f),
                    1f to Color.Transparent,
                ),
                center = Offset(w * 0.35f, h * 0.29f),
                radius = radius * 0.16f,
            ),
            center = Offset(w * 0.35f, h * 0.29f),
            radius = radius * 0.16f,
        )
        // teal: 62%/56%, α0.64, 0..10% solid, 38% transparent
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color(0xFF14B8A6).copy(alpha = 0.64f),
                    (0.10f / 0.38f) to Color(0xFF14B8A6).copy(alpha = 0.64f),
                    1f to Color.Transparent,
                ),
                center = Offset(w * 0.62f, h * 0.56f),
                radius = radius * 0.38f,
            ),
            center = Offset(w * 0.62f, h * 0.56f),
            radius = radius * 0.38f,
        )
        // pink: 35%/70%, α0.46, 0..8% solid, 33% transparent
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color(0xFFFB7185).copy(alpha = 0.46f),
                    (0.08f / 0.33f) to Color(0xFFFB7185).copy(alpha = 0.46f),
                    1f to Color.Transparent,
                ),
                center = Offset(w * 0.35f, h * 0.70f),
                radius = radius * 0.33f,
            ),
            center = Offset(w * 0.35f, h * 0.70f),
            radius = radius * 0.33f,
        )
        // yellow: 72%/28%, α0.42, 0..7% solid, 30% transparent
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color(0xFFFACC15).copy(alpha = 0.42f),
                    (0.07f / 0.30f) to Color(0xFFFACC15).copy(alpha = 0.42f),
                    1f to Color.Transparent,
                ),
                center = Offset(w * 0.72f, h * 0.28f),
                radius = radius * 0.30f,
            ),
            center = Offset(w * 0.72f, h * 0.28f),
            radius = radius * 0.30f,
        )

        // === 4. 外环 (CSS: inset 8%, border-radius 46%/55% 54%/42% 42%/58% 58%/45%) ===
        drawOrganicRing(
            boxWidth = w * 0.84f,
            boxHeight = h * 0.84f,
            color = Color(0xFFE0F2FE).copy(alpha = 0.34f),
            strokeWidth = radius * 0.020f,
            topLeft = CornerRadius(w * 0.84f * 0.46f, h * 0.84f * 0.55f),
            topRight = CornerRadius(w * 0.84f * 0.54f, h * 0.84f * 0.42f),
            bottomRight = CornerRadius(w * 0.84f * 0.42f, h * 0.84f * 0.58f),
            bottomLeft = CornerRadius(w * 0.84f * 0.58f, h * 0.84f * 0.45f),
        )

        // === 5. 内环 (CSS: inset 25%, border-radius 58%/43% 42%/57% 54%/41% 46%/59%) ===
        drawOrganicRing(
            boxWidth = w * 0.50f,
            boxHeight = h * 0.50f,
            color = Color(0xFFFDBA74).copy(alpha = 0.38f),
            strokeWidth = radius * 0.020f,
            topLeft = CornerRadius(w * 0.50f * 0.58f, h * 0.50f * 0.43f),
            topRight = CornerRadius(w * 0.50f * 0.42f, h * 0.50f * 0.57f),
            bottomRight = CornerRadius(w * 0.50f * 0.54f, h * 0.50f * 0.41f),
            bottomLeft = CornerRadius(w * 0.50f * 0.46f, h * 0.50f * 0.59f),
        )

        // === 6. 中心核心白圆 (在中心, 脉动) ===
        val coreR = radius * 0.18f * corePulse
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.White,
                    0.6f to Color(0xFFE0F7FA).copy(alpha = 0.95f),
                    1f to Color(0xFFA5F3FC).copy(alpha = 0.0f),
                ),
                center = center,
                radius = coreR,
            ),
            center = center,
            radius = coreR,
        )

        // === 7. 18 个白色粒子 (大小不一, 每颗独立随机慢慢漂) ===
        val tau = kotlin.math.PI.toFloat() * 2f
        PARTICLES.forEach { p ->
            val d = driftParams(p.seed)
            val ampPx = radius * d.amp
            val dx = (sin(particlePhase * tau * d.fx1 + d.phx1) +
                sin(particlePhase * tau * d.fx2 + d.phx2) * 0.55f) * ampPx
            val dy = (sin(particlePhase * tau * d.fy1 + d.phy1 + 1.7f) +
                sin(particlePhase * tau * d.fy2 + d.phy2 + 0.9f) * 0.55f) * ampPx
            val px = w * p.x + dx
            val py = h * p.y + dy
            val pr = radius * p.size
            // glow
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(px, py), radius = pr * 2.5f,
                ),
                center = Offset(px, py), radius = pr * 2.5f,
            )
            // 本体
            drawCircle(Color.White.copy(alpha = 0.85f), pr, Offset(px, py))
        }
    }
}

/**
 * 用 Path 画一个带"四角各异" border-radius 的描边矩形 (复刻 CSS
 * `border-radius: TL% TR% BR% BL% / TL% TR% BR% BL%`). 在 DrawScope 内调用,
 * 居中放置, boxWidth/boxHeight 即 rect 尺寸.
 */
private fun DrawScope.drawOrganicRing(
    boxWidth: Float,
    boxHeight: Float,
    color: Color,
    strokeWidth: Float,
    topLeft: CornerRadius,
    topRight: CornerRadius,
    bottomRight: CornerRadius,
    bottomLeft: CornerRadius,
) {
    val left = (size.width - boxWidth) / 2f
    val top = (size.height - boxHeight) / 2f
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(left, top, left + boxWidth, top + boxHeight),
                topLeft = topLeft,
                topRight = topRight,
                bottomRight = bottomRight,
                bottomLeft = bottomLeft,
            )
        )
    }
    drawPath(path, color = color, style = Stroke(width = strokeWidth))
}

@Composable
fun MoAvatarImage(sizeDp: Dp, modifier: Modifier = Modifier) {
    MoAvatar(sizeDp = sizeDp, modifier = modifier)
}

@Composable
fun MobiusBrandLogo(sizeDp: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.mobius_brand_logo),
        contentDescription = "Mobius",
        modifier = modifier.size(sizeDp),
        contentScale = ContentScale.Fit,
    )
}
