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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import com.xianxia.sect.core.model.MapCoordinateSystem
import kotlin.math.roundToInt
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.game.map.MapStyle
import com.xianxia.sect.ui.game.map.MapCameraState

@Composable
fun SectMarker(
    item: MapItem.Sect,
    cameraState: MapCameraState,
    onClick: () -> Unit
) {
    val markerColor = if (item.isPlayerSect) MapStyle.Colors.sectPlayer else MapStyle.Colors.sectNormal
    val borderColor = if (item.isHighlighted) MapStyle.Colors.sectHighlighted else MapStyle.Colors.sectBorderNormal
    val textColor = if (item.isPlayerSect) MapStyle.Colors.sectTextPlayer else MapStyle.Colors.sectTextNormal
    val fontSize = if (item.isPlayerSect) MapStyle.Typography.sectNamePlayer else MapStyle.Typography.sectNameNormal
    val borderWidth = if (item.isHighlighted) MapStyle.Dimensions.sectHighlightedBorderWidth else MapStyle.Dimensions.sectBorderWidth

    val (nx, ny) = MapCoordinateSystem.worldToNormalized(item.worldX, item.worldY)
    val x = nx * cameraState.canvasWidth
    val y = ny * cameraState.canvasHeight

    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.place(
                        (x - placeable.width / 2f).roundToInt(),
                        (y - placeable.height / 2f).roundToInt()
                    )
                }
            }
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(MapStyle.Dimensions.sectBorderRadius))
                .background(markerColor)
                .border(
                    width = borderWidth,
                    color = borderColor,
                    shape = RoundedCornerShape(MapStyle.Dimensions.sectBorderRadius)
                )
                .padding(
                    horizontal = MapStyle.Dimensions.sectPaddingH,
                    vertical = MapStyle.Dimensions.sectPaddingV
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.name,
                fontSize = fontSize,
                color = textColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
