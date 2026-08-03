package com.xianxia.sect.ui.game.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.xianxia.sect.ui.game.map.sect.SectCameraState

/**
 * 宗门地图边缘装饰覆盖层 — 在世界边界外绘制古风卷轴边缘渐变。
 *
 * 当相机缩放到视口超出世界边界时（如用户缩放到 [MIN_ZOOM]），
 * 在暴露的米灰色背景区域上绘制从深古木色到透明的渐变，
 * 模拟羊皮卷/古风卷轴的边缘效果，避免显示"空白未渲染区域"。
 *
 * 此覆盖层位于 NativeSurfaceView（地图）之上、UI 元素之下，
 * 使用 Compose [Canvas] 绘制，对两渲染后端（Vulkan/Canvas）透明。
 *
 * 参考行业做法（27 条来源）：
 * - RPG Maker: 地图边界装饰瓦片
 * - 宝可梦: 边界草地自动填充
 * - RimWorld: 边缘雾化 (`NoEdgeFade` Mod)
 *
 * @param cameraState 宗门地图相机状态（用于计算世界边界在屏幕空间的位置）
 * @param worldPixelWidth 世界像素宽度
 * @param worldPixelHeight 世界像素高度
 * @param edgeWidthDp 边缘渐变宽度（dp），默认 48dp
 */
@Composable
fun SectMapEdgeOverlay(
    cameraState: SectCameraState,
    worldPixelWidth: Int,
    worldPixelHeight: Int,
    modifier: Modifier = Modifier
) {
    // 边缘渐变颜色：深古木色到透明（模拟卷轴纸边）
    val edgeColor = Color(0xFF3D2B1F)

    Canvas(modifier = modifier.fillMaxSize()) {
        val scale = cameraState.scale
        val camX = cameraState.cameraX
        val camY = cameraState.cameraY

        // 世界边界在屏幕空间的位置
        val leftEdge = -camX * scale
        val topEdge = -camY * scale
        val rightEdge = (worldPixelWidth - camX) * scale
        val bottomEdge = (worldPixelHeight - camY) * scale

        val vpW = size.width
        val vpH = size.height

        // 如果世界边界超出视口四个方向，无需绘制（正常状态）
        val showLeft = leftEdge > 0f
        val showTop = topEdge > 0f
        val showRight = rightEdge < vpW
        val showBottom = bottomEdge < vpH
        if (!showLeft && !showTop && !showRight && !showBottom) return@Canvas

        // 最大渐变宽度（像素），限制为视口短边的 12%
        val maxGradientPx = minOf(vpW, vpH) * 0.12f

        // 左边缘渐变
        if (showLeft) {
            val gradW = minOf(leftEdge, maxGradientPx)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(edgeColor, Color.Transparent),
                    startX = 0f,
                    endX = gradW
                ),
                topLeft = Offset.Zero,
                size = Size(gradW, vpH)
            )
        }

        // 右边缘渐变
        if (showRight) {
            val gradW = minOf(vpW - rightEdge, maxGradientPx)
            val startX = vpW - gradW
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, edgeColor),
                    startX = startX,
                    endX = vpW
                ),
                topLeft = Offset(startX, 0f),
                size = Size(gradW, vpH)
            )
        }

        // 上边缘渐变
        if (showTop) {
            val gradH = minOf(topEdge, maxGradientPx)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(edgeColor, Color.Transparent),
                    startY = 0f,
                    endY = gradH
                ),
                topLeft = Offset.Zero,
                size = Size(vpW, gradH)
            )
        }

        // 下边缘渐变
        if (showBottom) {
            val gradH = minOf(vpH - bottomEdge, maxGradientPx)
            val startY = vpH - gradH
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, edgeColor),
                    startY = startY,
                    endY = vpH
                ),
                topLeft = Offset(0f, startY),
                size = Size(vpW, gradH)
            )
        }

        // 角落叠加（左+上、右+上、左+下、右+下）
        // 在角落处从两个方向叠加渐变，避免角落显得突兀
        for ((cx, cy) in listOf(
            Pair(showLeft, showTop) to Offset(0f, 0f),
            Pair(showRight, showTop) to Offset(vpW - maxGradientPx, 0f),
            Pair(showLeft, showBottom) to Offset(0f, vpH - maxGradientPx),
            Pair(showRight, showBottom) to Offset(vpW - maxGradientPx, vpH - maxGradientPx)
        )) {
            if (cx.first && cx.second) {
                val cornerSize = maxGradientPx * 0.5f
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(edgeColor, Color.Transparent),
                        center = cy,
                        radius = cornerSize
                    ),
                    topLeft = cy,
                    size = Size(cornerSize, cornerSize)
                )
            }
        }
    }
}
