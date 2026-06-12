package com.xianxia.sect.ui.game.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.xianxia.sect.core.engine.domain.exploration.MSTEdge
import com.xianxia.sect.ui.game.map.world.WorldCameraState
import kotlin.random.Random

@Composable
fun WorldMapConnections(
    edges: List<MSTEdge>,
    cameraState: WorldCameraState,
    modifier: Modifier = Modifier
) {
    val pathEffect = remember {
        PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    Canvas(modifier = modifier) {
        edges.forEach { edge ->
            val from = edge.sect1; val to = edge.sect2
            val sx = cameraState.worldToScreenX(from.x)
            val sy = cameraState.worldToScreenY(from.y)
            val ex = cameraState.worldToScreenX(to.x)
            val ey = cameraState.worldToScreenY(to.y)

            if (sx < -100 && ex < -100) return@forEach
            if (sy < -100 && ey < -100) return@forEach
            if (sx > size.width + 100 && ex > size.width + 100) return@forEach
            if (sy > size.height + 100 && ey > size.height + 100) return@forEach

            val path = Path().apply {
                moveTo(sx, sy)
                val mx = (sx + ex) / 2 + Random.nextFloat() * 40 - 20
                val my = (sy + ey) / 2 + Random.nextFloat() * 40 - 20
                quadraticTo(mx, my, ex, ey)
            }

            drawPath(
                path = path,
                color = Color(0x608B7355),
                style = Stroke(width = 1.5f, pathEffect = pathEffect, cap = StrokeCap.Round)
            )
        }
    }
}
