package com.xianxia.sect.ui.game.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.util.GridSystem
import com.xianxia.sect.ui.game.map.sect.SectCameraState
import com.xianxia.sect.ui.game.sect.GoldFingerState
import com.xianxia.sect.ui.theme.GameColors

/** 金手指图标基准尺寸（世界像素）— 固定 48px，屏幕显示随相机缩放（激活图标 = 48 × scale）。 */
private const val GOLDEN_FINGER_ICON_SIZE_PX = 48

/**
 * 金手指图标 — 建筑预览框右下角显示，
 * 提示玩家可长按进入批量建造模式。
 */
@Composable
internal fun GoldFingerIcon(
    goldenFingerBmp: ImageBitmap,
    gridX: Int,
    gridY: Int,
    cameraState: SectCameraState,
    tileSize: Int
) {
    val density = LocalDensity.current.density
    val sx = cameraState.worldToScreenX(
        (gridX * tileSize).toFloat()) / density
    val sy = cameraState.worldToScreenY(
        (gridY * tileSize).toFloat()) / density
    val iconDp = (GOLDEN_FINGER_ICON_SIZE_PX / density).dp

    Box(
        modifier = Modifier
            .offset(x = sx.dp, y = sy.dp)
            .size(iconDp)
    ) {
        Image(
            bitmap = goldenFingerBmp,
            contentDescription = "金手指",
            modifier = Modifier.size(iconDp)
        )
    }
}

/**
 * 金手指框选覆盖层 — 在批量建造选区上绘制
 * 绿色/红色方块、边框和金手指图标。
 */
@Composable
internal fun GoldFingerSelectionOverlay(
    goldFingerState: GoldFingerState,
    cameraState: SectCameraState,
    tileSize: Int,
    goldenFingerBmp: ImageBitmap?
) {
    val density = LocalDensity.current.density
    val g = goldFingerState
    val geo = goldFingerSelectionGeometry(g, cameraState, tileSize)

    Box(
        modifier = Modifier
            .offset(x = (geo.selLeft / density).dp, y = (geo.selTop / density).dp)
            .size(width = (geo.selW / density).dp, height = (geo.selH / density).dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawGoldFingerSelection(
                g = g,
                geo = geo,
                goldenFingerBmp = goldenFingerBmp
            )
        }
    }
}

/** 金手指框选绘制几何（GoldFingerSelectionOverlay 拆分）：像素坐标 + 格尺寸 */
private data class GoldFingerSelectionGeometry(
    val selLeft: Float,
    val selTop: Float,
    val selW: Float,
    val selH: Float,
    val gMinX: Int,
    val gMinY: Int,
    val gMaxX: Int,
    val gMaxY: Int,
    val bW: Int,
    val bH: Int,
    val cellW: Float,
    val cellH: Float,
    val ts: Int,
    val scale: Float,
    val canAfford: Boolean
)

/** 金手指选区几何计算（GoldFingerSelectionOverlay 拆分） */
private fun goldFingerSelectionGeometry(
    g: GoldFingerState,
    cameraState: SectCameraState,
    tileSize: Int
): GoldFingerSelectionGeometry {
    val gMinX = minOf(g.startGridX, g.endGridX)
    val gMaxX = maxOf(g.startGridX, g.endGridX)
    val gMinY = minOf(g.startGridY, g.endGridY)
    val gMaxY = maxOf(g.startGridY, g.endGridY)
    val bW = g.buildingSize.width
    val bH = g.buildingSize.height
    val scale = cameraState.scale

    val selLeft = ((gMinX * tileSize).toFloat() - cameraState.cameraX) * scale
    val selTop = ((gMinY * tileSize).toFloat() - cameraState.cameraY) * scale
    // 边框覆盖 [gMinX, gMaxX] 与建造循环（gx + bW - 1 <= gMaxX）一致，
    // 不能用 +bW/+bH（会在右/下多出 bW-1/bH-1 格，视觉范围大于实际建造范围）
    val selW = ((gMaxX - gMinX + 1) * tileSize).toFloat() * scale
    val selH = ((gMaxY - gMinY + 1) * tileSize).toFloat() * scale
    val cellW = (bW * tileSize).toFloat() * scale
    val cellH = (bH * tileSize).toFloat() * scale

    return GoldFingerSelectionGeometry(
        selLeft = selLeft,
        selTop = selTop,
        selW = selW,
        selH = selH,
        gMinX = gMinX,
        gMinY = gMinY,
        gMaxX = gMaxX,
        gMaxY = gMaxY,
        bW = bW,
        bH = bH,
        cellW = cellW,
        cellH = cellH,
        ts = tileSize,
        scale = scale,
        canAfford = g.canAfford
    )
}

/** 金手指选区绘制（GoldFingerSelectionOverlay 拆分）：半透明方块 + 边框 + 图标 */
private fun DrawScope.drawGoldFingerSelection(
    g: GoldFingerState,
    geo: GoldFingerSelectionGeometry,
    goldenFingerBmp: ImageBitmap?
) {
    // 1. 半透明方块（每格可建性）
    var gx = geo.gMinX
    while (gx + geo.bW - 1 <= geo.gMaxX) {
        var gy = geo.gMinY
        while (gy + geo.bH - 1 <= geo.gMaxY) {
            val key = GridSystem.packCell(gx, gy)
            val valid = g.cellValidity[key] ?: false
            val color = if (valid && geo.canAfford) {
                Color(0x404CAF50)
            } else {
                Color(0x40F44336)
            }
            val rx = ((gx - geo.gMinX) * geo.ts).toFloat() * geo.scale
            val ry = ((gy - geo.gMinY) * geo.ts).toFloat() * geo.scale
            drawRect(color = color,
                topLeft = Offset(rx, ry), size = Size(geo.cellW, geo.cellH))
            gy += geo.bH
        }
        gx += geo.bW
    }
    // 2. 选区边框（4 条 2px 线）
    val bClr = if (geo.canAfford) GameColors.Success else GameColors.Error
    drawRect(color = bClr, topLeft = Offset(0f, 0f),
        size = Size(geo.selW, 2f))
    drawRect(color = bClr, topLeft = Offset(0f, geo.selH - 2f),
        size = Size(geo.selW, 2f))
    drawRect(color = bClr, topLeft = Offset(0f, 0f),
        size = Size(2f, geo.selH))
    drawRect(color = bClr, topLeft = Offset(geo.selW - 2f, 0f),
        size = Size(2f, geo.selH))
    // 3. 金手指图标（拖拽末端）— 固定 40px 基准，随相机缩放
    val bmp = goldenFingerBmp ?: return
    val iw = (GOLDEN_FINGER_ICON_SIZE_PX * geo.scale).toInt()
    val ih = (GOLDEN_FINGER_ICON_SIZE_PX * geo.scale).toInt()
    // 先乘 scale 再取整（与 iw/ih 取整时机一致），避免先取整丢失亚格精度
    val ix = ((g.endGridX - geo.gMinX) * geo.ts * geo.scale).toInt()
    val iy = ((g.endGridY - geo.gMinY) * geo.ts * geo.scale).toInt()
    drawImage(bmp,
        dstOffset = IntOffset(
            ix.coerceIn(0, (geo.selW.toInt() - iw).coerceAtLeast(0)),
            iy.coerceIn(0, (geo.selH.toInt() - ih).coerceAtLeast(0))),
        dstSize = IntSize(iw, ih))
}

// 网格线（GridOverlay/GridPlacement）已迁移至 native 渲染层
// （RenderFrame.gridOverlayVisible + 双后端 drawGridOverlay，2026-08-11）——
// Compose 覆盖层锚定 cameraState 与渲染线程异步消费存在相位差，拖拽视角时 1 帧错位。
