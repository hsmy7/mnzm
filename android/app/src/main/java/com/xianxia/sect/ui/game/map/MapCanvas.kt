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

            val pathObj = Path()

            if (pathData.waypoints.isEmpty()) {
                pathObj.moveTo(fromX, fromY)
                pathObj.lineTo(toX, toY)
            } else {
                val canvasWaypoints = pathData.waypoints.map { (wx, wy) ->
                    Pair(
                        (wx / MapCoordinateSystem.WORLD_WIDTH) * cw,
                        (wy / MapCoordinateSystem.WORLD_HEIGHT) * ch
                    )
                }

                val allPoints = mutableListOf<Pair<Float, Float>>()
                allPoints.add(Pair(fromX, fromY))
                allPoints.addAll(canvasWaypoints)
                allPoints.add(Pair(toX, toY))

                pathObj.moveTo(allPoints[0].first, allPoints[0].second)

                val midX0 = (allPoints[0].first + allPoints[1].first) / 2f
                val midY0 = (allPoints[0].second + allPoints[1].second) / 2f
                pathObj.lineTo(midX0, midY0)

                for (i in 1 until allPoints.size - 1) {
                    val curr = allPoints[i]
                    val next = allPoints[i + 1]
                    val midX = (curr.first + next.first) / 2f
                    val midY = (curr.second + next.second) / 2f

                    pathObj.quadraticTo(
                        curr.first, curr.second,
                        midX, midY
                    )
                }

                pathObj.lineTo(allPoints.last().first, allPoints.last().second)
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
