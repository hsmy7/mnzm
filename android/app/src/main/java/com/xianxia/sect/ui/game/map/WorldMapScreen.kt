package com.xianxia.sect.ui.game.map

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.xianxia.sect.core.model.MapCoordinateSystem
import com.xianxia.sect.ui.game.map.markers.AIBattleTeamMarker
import com.xianxia.sect.ui.game.map.markers.BattleIndicatorMarker
import com.xianxia.sect.ui.game.map.markers.BattleTeamMarker
import com.xianxia.sect.ui.game.map.markers.CaveExplorationTeamMarker
import com.xianxia.sect.ui.game.map.markers.CaveMarker
import com.xianxia.sect.ui.game.map.markers.ScoutTeamMarker
import com.xianxia.sect.ui.game.map.markers.SectMarker

@Composable
fun WorldMapScreen(
    items: List<MapItem>,
    paths: List<MapPathData>,
    caveExplorationPaths: List<CaveExplorationPathData>,
    cameraState: MapCameraState = rememberMapCameraState(),
    hasBattleTeam: Boolean = false,
    isBattleTeamAtSect: Boolean = false,
    focusWorldX: Float? = null,
    focusWorldY: Float? = null,
    onBack: () -> Unit = {},
    onSectClick: (MapItem.Sect) -> Unit = {},
    onMovableTargetClick: (String) -> Unit = {},
    onCaveClick: (MapItem.Cave) -> Unit = {},
    onBattleTeamClick: (MapItem.BattleTeam) -> Unit = {},
    onCreateTeamClick: () -> Unit = {},
    onManageTeamClick: () -> Unit = {}
) {
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        cameraState.initialize(
            density = density.density,
            baseWidthDp = MapCoordinateSystem.WORLD_WIDTH / density.density,
            baseHeightDp = MapCoordinateSystem.WORLD_HEIGHT / density.density
        )
    }

    LaunchedEffect(focusWorldX, focusWorldY) {
        if (focusWorldX != null && focusWorldY != null) {
            cameraState.tryInitialFocus(focusWorldX, focusWorldY)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MapStyle.Colors.background)
            .onSizeChanged { size ->
                cameraState.updateScreenSize(size.width, size.height)
            }
            .pointerInput(cameraState) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (zoom != 1f) {
                        cameraState.applyZoom(zoom, centroid)
                    }
                    if (pan != Offset.Zero) {
                        cameraState.applyDrag(pan.x, pan.y)
                    }
                }
            }
            .pointerInput(cameraState) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val newScale = if (cameraState.scale >= 2.5f) 1f
                        else (cameraState.scale * 1.5f).coerceIn(
                            cameraState.effectiveMinScale,
                            cameraState.scaleRange.endInclusive
                        )
                        cameraState.applyZoom(newScale / cameraState.scale, offset)
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = cameraState.offsetX.toInt(),
                        y = cameraState.offsetY.toInt()
                    )
                }
                .width((MapCoordinateSystem.WORLD_WIDTH / density.density * cameraState.scale).dp)
                .height((MapCoordinateSystem.WORLD_HEIGHT / density.density * cameraState.scale).dp)
        ) {
            MapCanvas(
                paths = paths,
                caveExplorationPaths = caveExplorationPaths,
                cameraState = cameraState,
                modifier = Modifier.fillMaxSize()
            )

            items.forEach { item ->
                if (!cameraState.isWorldPositionVisible(item.worldX, item.worldY)) return@forEach

                when (item) {
                    is MapItem.Sect -> SectMarker(
                        item = item,
                        cameraState = cameraState,
                        onClick = {
                            if (item.isHighlighted) {
                                onMovableTargetClick(item.id)
                            } else {
                                onSectClick(item)
                            }
                        }
                    )

                    is MapItem.ScoutTeam -> ScoutTeamMarker(
                        item = item,
                        cameraState = cameraState
                    )

                    is MapItem.Cave -> CaveMarker(
                        item = item,
                        cameraState = cameraState,
                        onClick = { onCaveClick(item) }
                    )

                    is MapItem.CaveExplorationTeam -> CaveExplorationTeamMarker(
                        item = item,
                        cameraState = cameraState
                    )

                    is MapItem.BattleTeam -> BattleTeamMarker(
                        item = item,
                        cameraState = cameraState,
                        onClick = { onBattleTeamClick(item) }
                    )

                    is MapItem.AIBattleTeam -> AIBattleTeamMarker(
                        item = item,
                        cameraState = cameraState
                    )

                    is MapItem.BattleIndicator -> BattleIndicatorMarker(
                        item = item,
                        cameraState = cameraState
                    )
                }
            }
        }

        MapControls(
            hasBattleTeam = hasBattleTeam,
            isBattleTeamAtSect = isBattleTeamAtSect,
            onBack = onBack,
            onCreateTeamClick = onCreateTeamClick,
            onManageTeamClick = onManageTeamClick
        )
    }
}
