package com.xianxia.sect.ui.game.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.util.GridSystem
import com.xianxia.sect.ui.game.map.sect.SectCameraState
import com.xianxia.sect.ui.game.sect.GoldFingerState
import com.xianxia.sect.ui.theme.GameColors

/** 网格线覆盖层所需放置参数 */
internal data class GridPlacement(
    val snappedGridX: Int,
    val snappedGridY: Int,
    val buildingW: Int,
    val buildingH: Int
)

/**
 * 金手指图标 — 建筑预览框右下角单格内显示，
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
    val iconDp = (tileSize / density).dp

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
    val ts = tileSize
    val scale = cameraState.scale
    val gMinX = minOf(g.startGridX, g.endGridX)
    val gMaxX = maxOf(g.startGridX, g.endGridX)
    val gMinY = minOf(g.startGridY, g.endGridY)
    val gMaxY = maxOf(g.startGridY, g.endGridY)
    val bW = g.buildingSize.width
    val bH = g.buildingSize.height
    val canAfford = g.canAfford

    val selLeft = ((gMinX * ts).toFloat() - cameraState.cameraX) * scale
    val selTop = ((gMinY * ts).toFloat() - cameraState.cameraY) * scale
    // 边框覆盖 [gMinX, gMaxX] 与建造循环（gx + bW - 1 <= gMaxX）一致，
    // 不能用 +bW/+bH（会在右/下多出 bW-1/bH-1 格，视觉范围大于实际建造范围）
    val selW = ((gMaxX - gMinX + 1) * ts).toFloat() * scale
    val selH = ((gMaxY - gMinY + 1) * ts).toFloat() * scale
    val cellW = (bW * ts).toFloat() * scale
    val cellH = (bH * ts).toFloat() * scale

    Box(
        modifier = Modifier
            .offset(x = (selLeft / density).dp, y = (selTop / density).dp)
            .size(width = (selW / density).dp, height = (selH / density).dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            // 1. 半透明方块（每格可建性）
            var gx = gMinX
            while (gx + bW - 1 <= gMaxX) {
                var gy = gMinY
                while (gy + bH - 1 <= gMaxY) {
                    val key = GridSystem.packCell(gx, gy)
                    val valid = g.cellValidity[key] ?: false
                    val color = if (valid && canAfford) {
                        Color(0x404CAF50)
                    } else {
                        Color(0x40F44336)
                    }
                    val rx = ((gx - gMinX) * ts).toFloat() * scale
                    val ry = ((gy - gMinY) * ts).toFloat() * scale
                    drawRect(color = color,
                        topLeft = Offset(rx, ry), size = Size(cellW, cellH))
                    gy += bH
                }
                gx += bW
            }
            // 2. 选区边框（4 条 2px 线）
            val bClr = if (canAfford) GameColors.Success else GameColors.Error
            drawRect(color = bClr, topLeft = Offset(0f, 0f),
                size = Size(selW, 2f))
            drawRect(color = bClr, topLeft = Offset(0f, selH - 2f),
                size = Size(selW, 2f))
            drawRect(color = bClr, topLeft = Offset(0f, 0f),
                size = Size(2f, selH))
            drawRect(color = bClr, topLeft = Offset(selW - 2f, 0f),
                size = Size(2f, selH))
            // 3. 金手指图标（拖拽末端）
            val bmp = goldenFingerBmp ?: return@Canvas
            val iw = (ts * scale).toInt()
            val ih = (ts * scale).toInt()
            // 先乘 scale 再取整（与 iw/ih 取整时机一致），避免先取整丢失亚格精度
            val ix = ((g.endGridX - gMinX) * ts * scale).toInt()
            val iy = ((g.endGridY - gMinY) * ts * scale).toInt()
            drawImage(bmp,
                dstOffset = IntOffset(
                    ix.coerceIn(0, (selW.toInt() - iw).coerceAtLeast(0)),
                    iy.coerceIn(0, (selH.toInt() - ih).coerceAtLeast(0))),
                dstSize = IntSize(iw, ih))
        }
    }
}

/**
 * 网格线覆盖层 — 放置/移动模式时在地图上显示方格线。
 *
 * 两种模式：
 * - "border"：仅当前放置建筑外框（4 条线）
 * - "full"：视口内所有网格线
 */
@Composable
internal fun GridOverlay(
    placement: GridPlacement?,
    cameraState: SectCameraState,
    tileSize: Int,
    worldWidthCells: Int,
    worldHeightCells: Int,
    mode: String = "full"
) {
    if (placement == null) return

    val density = LocalDensity.current.density
    val scale = cameraState.scale
    val ts = tileSize
    val gridColor = Color(0xFFE4DDD0)

    if (mode == "border") {
        drawBorderGrid(placement, cameraState, ts, scale, density, gridColor)
    } else {
        drawFullGrid(cameraState, ts, scale, worldWidthCells, worldHeightCells,
            gridColor)
    }
}

@Composable
private fun drawBorderGrid(
    p: GridPlacement,
    cam: SectCameraState,
    ts: Int,
    scale: Float,
    density: Float,
    gridColor: Color
) {
    val bx1 = ((p.snappedGridX * ts).toFloat() - cam.cameraX) * scale
    val by1 = ((p.snappedGridY * ts).toFloat() - cam.cameraY) * scale
    val bx2 = (((p.snappedGridX + p.buildingW) * ts).toFloat() - cam.cameraX) * scale
    val by2 = (((p.snappedGridY + p.buildingH) * ts).toFloat() - cam.cameraY) * scale

    Box(
        modifier = Modifier
            .offset(x = (bx1 / density).dp, y = (by1 / density).dp)
            .size(width = ((bx2 - bx1) / density).dp,
                height = ((by2 - by1) / density).dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawLine(gridColor, Offset(0f, 0f), Offset(w, 0f), 1f)
            drawLine(gridColor, Offset(w, 0f), Offset(w, h), 1f)
            drawLine(gridColor, Offset(w, h), Offset(0f, h), 1f)
            drawLine(gridColor, Offset(0f, h), Offset(0f, 0f), 1f)
        }
    }
}

@Composable
private fun drawFullGrid(
    cam: SectCameraState,
    ts: Int,
    scale: Float,
    worldWidthCells: Int,
    worldHeightCells: Int,
    gridColor: Color
) {
    val vpWF = cam.viewportWidth.toFloat()
    val vpHF = cam.viewportHeight.toFloat()
    if (vpWF <= 0f || vpHF <= 0f) return

    val firstCol = (cam.cameraX / ts).toInt().coerceAtLeast(0)
    val lastCol = ((cam.cameraX + vpWF / scale) / ts).toInt()
        .coerceAtMost(worldWidthCells)
    val firstRow = (cam.cameraY / ts).toInt().coerceAtLeast(0)
    val lastRow = ((cam.cameraY + vpHF / scale) / ts).toInt()
        .coerceAtMost(worldHeightCells)

    Canvas(modifier = Modifier.fillMaxSize()) {
        for (col in firstCol..lastCol) {
            val sx = (col * ts - cam.cameraX) * scale
            drawLine(gridColor, Offset(sx, 0f), Offset(sx, vpHF), 1f)
        }
        for (row in firstRow..lastRow) {
            val sy = (row * ts - cam.cameraY) * scale
            drawLine(gridColor, Offset(0f, sy), Offset(vpWF, sy), 1f)
        }
    }
}
