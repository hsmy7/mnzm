package com.xianxia.sect.ui.game.map.markers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import com.xianxia.sect.core.model.MapCoordinateSystem
import kotlin.math.roundToInt
import com.xianxia.sect.ui.game.map.MapCameraState
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.game.map.MapStyle

@Composable
fun BattleTeamMarker(
    item: MapItem.BattleTeam,
    cameraState: MapCameraState,
    onClick: () -> Unit
) {
    val (nx, ny) = MapCoordinateSystem.worldToNormalized(item.worldX, item.worldY)
    val x = nx * cameraState.canvasWidth
    val y = ny * cameraState.canvasHeight

    val yOffset = if (item.isAtSect) -MapStyle.Dimensions.battleTeamYOffset.toFloat() else 0f

    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.place(
                        (x - placeable.width / 2f).roundToInt(),
                        (y - placeable.height / 2f + yOffset).roundToInt()
                    )
                }
            }
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(MapStyle.Dimensions.teamBorderRadius))
                .background(MapStyle.Colors.battleTeamBg)
                .border(
                    MapStyle.Dimensions.teamBorderWidth,
                    MapStyle.Colors.battleTeamBorder,
                    RoundedCornerShape(MapStyle.Dimensions.teamBorderRadius)
                )
                .padding(
                    horizontal = MapStyle.Dimensions.teamPaddingH,
                    vertical = MapStyle.Dimensions.teamPaddingV
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.name,
                fontSize = MapStyle.Typography.teamLabel,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun AIBattleTeamMarker(
    item: MapItem.AIBattleTeam,
    cameraState: MapCameraState
) {
    val (nx, ny) = MapCoordinateSystem.worldToNormalized(item.worldX, item.worldY)
    val x = nx * cameraState.canvasWidth
    val y = ny * cameraState.canvasHeight

    val teamColor = if (item.attackerIsRighteous) MapStyle.Colors.righteousTeam else MapStyle.Colors.evilTeam

    Box(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.place(
                    (x - placeable.width / 2f).roundToInt(),
                    (y - placeable.height / 2f).roundToInt()
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(MapStyle.Dimensions.teamBorderRadius))
                .background(teamColor)
                .border(
                    MapStyle.Dimensions.teamBorderWidth,
                    teamColor.copy(
                        red = teamColor.red * 0.7f,
                        green = teamColor.green * 0.7f,
                        blue = teamColor.blue * 0.7f
                    ),
                    RoundedCornerShape(MapStyle.Dimensions.teamBorderRadius)
                )
                .padding(
                    horizontal = MapStyle.Dimensions.teamPaddingH,
                    vertical = MapStyle.Dimensions.teamPaddingV
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${item.attackerSectName}攻队",
                fontSize = MapStyle.Typography.aiTeamLabel,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun BattleIndicatorMarker(
    item: MapItem.BattleIndicator,
    cameraState: MapCameraState
) {
    val (nx, ny) = MapCoordinateSystem.worldToNormalized(item.worldX, item.worldY)
    val x = nx * cameraState.canvasWidth
    val y = ny * cameraState.canvasHeight

    Box(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.place(
                    (x - placeable.width / 2f).roundToInt(),
                    (y - placeable.height / 2f + MapStyle.Dimensions.battleIndicatorYOffset.toFloat()).roundToInt()
                )
            }
        }
    ) {
        Text(
            text = if (item.isBattling) "⚔战" else "⚠攻",
            fontSize = MapStyle.Typography.battleIndicator,
            color = if (item.isBattling) MapStyle.Colors.battleBattling else MapStyle.Colors.battleAttacking,
            fontWeight = FontWeight.Bold
        )
    }
}
