package com.xianxia.sect.ui.game.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.model.MapCoordinateSystem
import kotlin.math.sqrt

@Composable
fun MapCanvas(
    paths: List<MapPathData>,
    caveExplorationPaths: List<CaveExplorationPathData>,
    cameraState: MapCameraState,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val cw = cameraState.canvasWidth
        val ch = cameraState.canvasHeight

        paths.forEach { pathData ->
            val fromX = (pathData.fromWorldX / MapCoordinateSystem.WORLD_WIDTH) * cw
            val fromY = (pathData.fromWorldY / MapCoordinateSystem.WORLD_HEIGHT) * ch
            val toX = (pathData.toWorldX / MapCoordinateSystem.WORLD_WIDTH) * cw
            val toY = (pathData.toWorldY / MapCoordinateSystem.WORLD_HEIGHT) * ch

            val dx = toX - fromX
            val dy = toY - fromY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance < 1f) return@forEach

            val midX = (fromX + toX) / 2
            val midY = (fromY + toY) / 2

            val normalX = -dy / distance
            val normalY = dx / distance

            val randomOffset = ((pathData.fromId.hashCode() + pathData.toId.hashCode()) % 100 - 50) / 100f
            val curveStrength = distance * 0.2f * randomOffset

            val controlX = midX + normalX * curveStrength
            val controlY = midY + normalY * curveStrength

            val pathObj = Path().apply {
                moveTo(fromX, fromY)
                quadraticTo(controlX, controlY, toX, toY)
            }

            drawPath(
                path = pathObj,
                color = MapStyle.Colors.path.copy(alpha = MapStyle.Colors.pathAlpha),
                style = Stroke(
                    width = MapStyle.Dimensions.pathStrokeWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        caveExplorationPaths.forEach { pathData ->
            val sx = (pathData.startWorldX / MapCoordinateSystem.WORLD_WIDTH) * cw
            val sy = (pathData.startWorldY / MapCoordinateSystem.WORLD_HEIGHT) * ch
            val ex = (pathData.endWorldX / MapCoordinateSystem.WORLD_WIDTH) * cw
            val ey = (pathData.endWorldY / MapCoordinateSystem.WORLD_HEIGHT) * ch

            val path = Path().apply {
                moveTo(sx, sy)
                lineTo(ex, ey)
            }

            drawPath(
                path = path,
                color = MapStyle.Colors.caveExplorationPath.copy(alpha = 0.6f),
                style = Stroke(
                    width = MapStyle.Dimensions.cavePathStrokeWidth.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        intervals = floatArrayOf(8.dp.toPx(), 4.dp.toPx())
                    )
                )
            )
        }
    }
}

data class CaveExplorationPathData(
    val startWorldX: Float,
    val startWorldY: Float,
    val endWorldX: Float,
    val endWorldY: Float
)
