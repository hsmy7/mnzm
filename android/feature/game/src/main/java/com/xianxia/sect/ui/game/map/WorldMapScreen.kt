package com.xianxia.sect.ui.game.map

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.xianxia.sect.core.model.MapCoordinateSystem
import com.xianxia.sect.ui.game.map.markers.LevelMarker
import com.xianxia.sect.ui.game.map.markers.SecretRealmMarker
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
    focusWorld: Offset? = null,
    onBack: () -> Unit = {},
    onItemClick: (MapItem) -> Unit = {},
    onUserInteraction: () -> Unit = {}
) {
    LaunchedEffect(focusWorld, cameraState.viewportWidth, cameraState.viewportHeight) {
        if (focusWorld != null) {
            cameraState.tryCenterOn(focusWorld.x, focusWorld.y)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                cameraState.updateViewport(size.width, size.height)
                val autoScale = maxOf(
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

        // Layer 2: 标记（宗门 + 关卡）
        items.forEach { item ->
            if (!cameraState.isVisible(item.worldX, item.worldY)) return@forEach

            when (item) {
                is MapItem.Sect -> SectMarker(
                    item = item,
                    cameraState = cameraState,
                    onClick = { onItemClick(item) }
                )

                is MapItem.Level -> LevelMarker(
                    item = item,
                    cameraState = cameraState,
                    onClick = { onItemClick(item) }
                )

                is MapItem.SecretRealm -> SecretRealmMarker(
                    item = item,
                    cameraState = cameraState,
                    onClick = { onItemClick(item) }
                )
            }
        }

        // Layer 4: UI 控件
        MapControls(
            onBack = onBack
        )
    }
}
