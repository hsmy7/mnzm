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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import com.xianxia.sect.ui.game.map.CameraState
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.game.map.MapStyle

@Composable
fun CaveExplorationTeamMarker(
    item: MapItem.CaveExplorationTeam,
    cameraState: CameraState
) {
    val x = cameraState.worldToScreenX(item.worldX)
    val y = cameraState.worldToScreenY(item.worldY)

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
                .background(MapStyle.Colors.caveExplorationBg)
                .border(
                    MapStyle.Dimensions.teamBorderWidth,
                    MapStyle.Colors.caveExplorationBorder,
                    RoundedCornerShape(MapStyle.Dimensions.teamBorderRadius)
                )
                .padding(
                    horizontal = MapStyle.Dimensions.teamPaddingH,
                    vertical = MapStyle.Dimensions.teamPaddingV
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "探索队",
                fontSize = MapStyle.Typography.teamLabel,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
