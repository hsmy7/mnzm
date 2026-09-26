package com.xianxia.sect.ui.game.dialogs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.sqrt

/**
 * 寻访奖励框的**描边扫光**（Q30「持续旋转的流光 / 描边扫光」，D-6 走 Compose 层）。
 *
 * ## 为什么不是 `Brush.sweepGradient` 转角度
 * 本仓 Compose 版本的 `Brush.sweepGradient` 只有两个重载（`vararg colorStops: Pair<Float, Color>`
 * 与 `colors: List<Color>`，均无 `start/end` 角度入参——编译期实测），拿不到「旋转角度」；
 * 而 `linearGradient(colors, start, end)` 可以逐帧挪动高光带位置。Q30 的两个措辞里取
 * 「描边扫光」这一支：一条高光沿框的对角反复扫过，首尾都在框外，循环看不到断点。
 * 旋转那一支要落地得改成 `colorStops` 逐帧采样建表，每帧多一次列表分配——登记给流光精修批。
 *
 * ## 相位读在绘制阶段
 * [Animatable] 的值只在 `drawWithContent` 内读，于是每帧**重绘而不重组**
 * （本仓动画既有范式一律 Animatable，`InfiniteTransition` 全仓零命中）。
 *
 * ## 低端降级
 * 调用方传 `animated = false`（`GpuTier.LOW`）：不起协程、不做渐变，静态同色描边一档，
 * 帧开销归零——D-6 要求的降级路径。
 */
@Composable
fun Modifier.gachaShimmerBorder(
    color: Color,
    animated: Boolean,
    cornerRadius: Dp = CORNER_DP.dp,
    strokeWidth: Dp = STROKE_DP.dp,
): Modifier {
    val phase = remember { Animatable(0f) }
    if (animated) {
        LaunchedEffect(phase) {
            while (isActive) {
                phase.animateTo(1f, tween(CYCLE_MS, easing = LinearEasing))
                phase.snapTo(0f)
            }
        }
    }
    return drawWithContent {
        drawContent()
        val stroke = strokeWidth.toPx()
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        val rectSize = Size(size.width - stroke, size.height - stroke)
        val radius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
        val style = Stroke(width = stroke, cap = StrokeCap.Round)
        if (!animated) {
            drawRoundRect(color = color, topLeft = topLeft, size = rectSize, cornerRadius = radius, style = style)
        } else {
            val band = BAND_FRACTION * diagonalOf(rectSize.width, rectSize.height)
            val head = phase.value * (diagonalOf(rectSize.width, rectSize.height) + band * 2f) - band
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(colorDim(color), SHIMMER_CORE, colorDim(color)),
                    start = Offset(head, head),
                    end = Offset(head + band, head + band),
                ),
                topLeft = topLeft,
                size = rectSize,
                cornerRadius = radius,
                style = style,
            )
        }
    }
}

/** 框面对角线长度：高光带的行程按它算，横竖屏与不同窗宽都自动适配 */
private fun diagonalOf(width: Float, height: Float): Float = sqrt(width * width + height * height)

/** 扫光带两侧渐隐用的同色低透明度版 */
private fun colorDim(color: Color): Color = color.copy(alpha = DIM_ALPHA)

/** 框内半透明底色（Q30：底色与描边同色，压到能容住白字读数） */
fun gachaCellFillColor(color: Color): Color = color.copy(alpha = FILL_ALPHA)

/** 圆角形状与流光描边共用同一个半径口径 */
internal val gachaCellShape = RoundedCornerShape(CORNER_DP.dp)

private const val CORNER_DP = 6
private const val STROKE_DP = 2

/** 一趟扫完的时长 */
private const val CYCLE_MS = 1600

/** 高光带宽占对角跨度的比例 */
private const val BAND_FRACTION = 0.35f

private const val DIM_ALPHA = 0.35f
private const val FILL_ALPHA = 0.18f

/** 扫过时的最亮点（近白的同色系高光，不用纯白以免盖住品阶色本意） */
private val SHIMMER_CORE = Color(0xFFFFF6D5)
