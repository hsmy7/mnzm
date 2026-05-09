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
import com.xianxia.sect.ui.game.map.markers.AIBattleTeamMarker
import com.xianxia.sect.ui.game.map.markers.BattleIndicatorMarker
import com.xianxia.sect.ui.game.map.markers.BattleTeamMarker
import com.xianxia.sect.ui.game.map.markers.CaveExplorationTeamMarker
import com.xianxia.sect.ui.game.map.markers.CaveMarker
import com.xianxia.sect.ui.game.map.markers.LevelMarker
import com.xianxia.sect.ui.game.map.markers.ScoutTeamMarker
import com.xianxia.sect.ui.game.map.markers.SectMarker
import com.xianxia.sect.ui.game.map.markers.TeamBadgeInfo
import com.xianxia.sect.ui.game.map.markers.TeamAction

@Composable
fun WorldMapScreen(
    items: List<MapItem>,
    paths: List<MapPathData>,
    caveExplorationPaths: List<CaveExplorationPathData>,
    battleTeamPaths: List<BattleTeamPathData> = emptyList(),
    cameraState: CameraState = rememberCameraState(
        worldWidth = MapCoordinateSystem.WORLD_WIDTH,
        worldHeight = MapCoordinateSystem.WORLD_HEIGHT
    ),
    hasBattleTeam: Boolean = false,
    isBattleTeamAtSect: Boolean = false,
    focusWorldX: Float? = null,
    focusWorldY: Float? = null,
    onBack: () -> Unit = {},
    onSectClick: (MapItem.Sect) -> Unit = {},
    onMovableTargetClick: (String) -> Unit = {},
    onCaveClick: (MapItem.Cave) -> Unit = {},
    onLevelClick: (MapItem.Level) -> Unit = {},
    onBattleTeamClick: (MapItem.BattleTeam) -> Unit = {},
    onCreateTeamClick: () -> Unit = {},
    onManageTeamClick: () -> Unit = {},
    teamBadgesBySect: Map<String, List<TeamBadgeInfo>> = emptyMap(),
    onTeamBadgeClick: (String) -> Unit = {},
    onTeamAction: (String, TeamAction) -> Unit = { _, _ -> }
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
                }
            }
    ) {
        MapCanvas(
            paths = paths,
            caveExplorationPaths = caveExplorationPaths,
            battleTeamPaths = battleTeamPaths,
            cameraState = cameraState,
            modifier = Modifier.fillMaxSize()
        )

        items.forEach { item ->
            if (!cameraState.isVisible(item.worldX, item.worldY)) return@forEach

            when (item) {
                is MapItem.Sect -> SectMarker(
                    item = item,
                    cameraState = cameraState,
                    teamBadges = teamBadgesBySect[item.id] ?: emptyList(),
                    onClick = {
                        if (item.isHighlighted) {
                            onMovableTargetClick(item.id)
                        } else {
                            onSectClick(item)
                        }
                    },
                    onTeamBadgeClick = onTeamBadgeClick,
                    onTeamAction = onTeamAction
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

                is MapItem.Level -> LevelMarker(
                    item = item,
                    cameraState = cameraState,
                    onClick = { onLevelClick(item) }
                )
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
