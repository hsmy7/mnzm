package com.xianxia.sect.ui.game.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.ui.game.map.sect.SectCameraState

/**
 * 灵植阁光环范围覆盖层 — 在放置/移动灵植阁时显示：
 * 1. 光环范围圆（绿色半透明圈）
 * 2. 光环范围内的灵田高亮（绿色半透明方块）
 */
@Composable
internal fun HerbGardenAuraOverlay(
    showAura: Boolean,
    buildingGridX: Int,
    buildingGridY: Int,
    buildingW: Int,
    buildingH: Int,
    spiritFieldBuildings: List<GridBuildingData>,
    cameraState: SectCameraState,
    tileSize: Int
) {
    if (!showAura) return

    val scale = cameraState.scale
    val ts = tileSize

    // 灵植阁中心坐标（世界像素）
    val centerWx = (buildingGridX + buildingW / 2.0) * ts
    val centerWy = (buildingGridY + buildingH / 2.0) * ts
    val auraRadiusPx = (GameConfig.HerbGarden.AURA_RADIUS_TILES * ts).toFloat()

    // 将中心坐标转为屏幕坐标
    val centerSx = (centerWx - cameraState.cameraX).toFloat() * scale
    val centerSy = (centerWy - cameraState.cameraY).toFloat() * scale

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 1. 高亮光环范围内所有灵田
            for (sf in spiritFieldBuildings) {
                val sfCenterX = sf.gridX + sf.width / 2.0
                val sfCenterY = sf.gridY + sf.height / 2.0
                val dx = sfCenterX - (buildingGridX + buildingW / 2.0)
                val dy = sfCenterY - (buildingGridY + buildingH / 2.0)
                if (dx * dx + dy * dy <= auraRadiusPx / ts.toDouble() * auraRadiusPx / ts.toDouble()) {
                    val rx = (sf.gridX * ts - cameraState.cameraX) * scale
                    val ry = (sf.gridY * ts - cameraState.cameraY) * scale
                    val rw = (sf.width * ts).toFloat() * scale
                    val rh = (sf.height * ts).toFloat() * scale
                    drawRect(
                        color = Color(0x404CAF50),
                        topLeft = Offset(rx, ry),
                        size = Size(rw, rh)
                    )
                }
            }

            // 2. 光环范围圈（虚线圆环）
            val circleRadius = auraRadiusPx * scale
            if (circleRadius > 0f && circleRadius < 5000f) { // 限制极端值
                drawCircle(
                    color = Color(0x804CAF50),
                    radius = circleRadius,
                    center = Offset(centerSx, centerSy),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}
