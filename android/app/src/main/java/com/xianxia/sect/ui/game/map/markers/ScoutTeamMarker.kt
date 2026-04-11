package com.xianxia.sect.ui.game.map.markers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import com.xianxia.sect.core.model.MapCoordinateSystem
import kotlin.math.roundToInt
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.game.map.MapStyle
import com.xianxia.sect.ui.game.map.MapCameraState

@Composable
fun ScoutTeamMarker(
    item: MapItem.ScoutTeam,
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
                    (y - placeable.height / 2f).roundToInt()
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(MapStyle.Dimensions.teamBorderRadius))
                .background(MapStyle.Colors.scoutTeamBg)
                .border(
                    MapStyle.Dimensions.teamBorderWidth,
                    MapStyle.Colors.scoutTeamBorder,
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
                color = MapStyle.Colors.scoutTeamText,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
