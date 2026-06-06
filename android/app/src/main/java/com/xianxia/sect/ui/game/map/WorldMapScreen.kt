package com.xianxia.sect.ui.game.map

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.xianxia.sect.core.model.MapCoordinateSystem
import com.xianxia.sect.ui.game.map.markers.LevelMarker
import com.xianxia.sect.ui.game.map.markers.SectMarker

@Composable
fun WorldMapScreen(
    items: List<MapItem>,
    paths: List<MapPathData>,
    cameraState: CameraState = rememberCameraState(
        worldWidth = MapCoordinateSystem.WORLD_WIDTH,
        worldHeight = MapCoordinateSystem.WORLD_HEIGHT
    ),
    focusWorldX: Float? = null,
    focusWorldY: Float? = null,
    onBack: () -> Unit = {},
    onSectClick: (MapItem.Sect) -> Unit = {},
    onLevelClick: (MapItem.Level) -> Unit = {},
    onUserInteraction: () -> Unit = {}
) {
    LaunchedEffect(focusWorldX, focusWorldY) {
        if (focusWorldX != null && focusWorldY != null) {
            cameraState.tryCenterOn(focusWorldX, focusWorldY)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MapStyle.Colors.background)
            .onSizeChanged { size ->
                cameraState.updateViewport(size.width, size.height)
            }
            .pointerInput(cameraState) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    cameraState.pan(dragAmount.x, dragAmount.y)
                    onUserInteraction()
                }
            }
    ) {
        MapCanvas(
            paths = paths,
            cameraState = cameraState,
            modifier = Modifier.fillMaxSize()
        )

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

        MapControls(
            onBack = onBack
        )
    }
}
