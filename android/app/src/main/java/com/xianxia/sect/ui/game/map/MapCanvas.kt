package com.xianxia.sect.ui.game.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.sqrt

@Composable
fun MapCanvas(
    paths: List<MapPathData>,
    cameraState: CameraState,
    modifier: Modifier = Modifier
) {
    val cachedPaths = remember(paths) {
        paths.map { pathData ->
            val fromX = pathData.fromWorldX
            val fromY = pathData.fromWorldY
            val toX = pathData.toWorldX
            val toY = pathData.toWorldY

            val dx = toX - fromX
            val dy = toY - fromY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance < 1f) return@map null

            val pathObj = Path()

            if (pathData.waypoints.isEmpty()) {
                pathObj.moveTo(fromX, fromY)
                pathObj.lineTo(toX, toY)
            } else {
                val allPoints = mutableListOf<Pair<Float, Float>>()
                allPoints.add(Pair(fromX, fromY))
                allPoints.addAll(pathData.waypoints)
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
            pathObj
        }.filterNotNull()
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        withTransform({
            translate(-cameraState.cameraX, -cameraState.cameraY)
        }) {
            val pathColor = MapStyle.Colors.path.copy(alpha = MapStyle.Colors.pathAlpha)
            val pathStroke = Stroke(
                width = MapStyle.Dimensions.pathStrokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            for (pathObj in cachedPaths) {
                drawPath(
                    path = pathObj,
                    color = pathColor,
                    style = pathStroke
                )
            }
        }
    }
}
