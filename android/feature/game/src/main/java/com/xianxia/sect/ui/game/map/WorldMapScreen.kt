package com.xianxia.sect.ui.game.map

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.xianxia.sect.core.engine.domain.exploration.MSTEdge
import com.xianxia.sect.core.model.MapCoordinateSystem
import com.xianxia.sect.ui.game.map.markers.LevelMarker
import com.xianxia.sect.ui.game.map.markers.SectMarker
import com.xianxia.sect.ui.game.map.world.WorldCameraState
import com.xianxia.sect.ui.game.map.world.rememberWorldCamera

@Composable
fun WorldMapScreen(
    items: List<MapItem>,
    cameraState: WorldCameraState = rememberWorldCamera(
        worldWidth = MapCoordinateSystem.WORLD_WIDTH,
        worldHeight = MapCoordinateSystem.WORLD_HEIGHT
    ),
    focusWorldX: Float? = null,
    focusWorldY: Float? = null,
    onBack: () -> Unit = {},
    onSectClick: (MapItem.Sect) -> Unit = {},
    onLevelClick: (MapItem.Level) -> Unit = {},
    onUserInteraction: () -> Unit = {},
    connectionEdges: List<MSTEdge> = emptyList()
) {
    LaunchedEffect(focusWorldX, focusWorldY, cameraState.viewportWidth, cameraState.viewportHeight) {
        if (focusWorldX != null && focusWorldY != null) {
            cameraState.tryCenterOn(focusWorldX, focusWorldY)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                cameraState.updateViewport(size.width, size.height)
                val autoScale = minOf(
                    size.width.toFloat() / cameraState.worldWidth,
                    size.height.toFloat() / cameraState.worldHeight
                )
                cameraState.updateScale(autoScale)
            }
            .pointerInput(cameraState) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    cameraState.pan(dragAmount.x, dragAmount.y)
                    onUserInteraction()
                }
            }
    ) {
        // Layer 1: 地图背景
        MapBackground(
            cameraState = cameraState,
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: 宗门连接线
        if (connectionEdges.isNotEmpty()) {
            WorldMapConnections(
                edges = connectionEdges,
                cameraState = cameraState,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Layer 3: 标记（宗门 + 关卡）
        items.forEach { item ->
            if (!cameraState.isVisible(item.worldX, item.worldY)) return@forEach

            when (item) {
                is MapItem.Sect -> SectMarker(
                    item = item,
                    cameraState = cameraState,
                    onClick = { onSectClick(item) }
                )

                is MapItem.Level -> LevelMarker(
                    item = item,
                    cameraState = cameraState,
                    onClick = { onLevelClick(item) }
                )
            }
        }

        // Layer 4: UI 控件
        MapControls(
            onBack = onBack
        )
    }
}
