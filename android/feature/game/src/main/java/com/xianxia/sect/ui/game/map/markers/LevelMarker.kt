package com.xianxia.sect.ui.game.map.markers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.game.map.world.WorldCameraState
import com.xianxia.sect.ui.game.map.MapItem

private val beastNames =
    listOf("tiger", "wolf", "snake", "bear", "eagle", "fox", "dragon", "turtle")

@Composable
fun LevelMarker(
    item: MapItem.Level,
    cameraState: WorldCameraState,
    onClick: () -> Unit
) {
    val screenX = cameraState.worldToScreenX(item.worldX)
    val screenY = cameraState.worldToScreenY(item.worldY)

    val spriteName = when (item.levelType) {
        LevelType.BEAST -> beastNames.getOrElse(item.beastType ?: 0) { "tiger" }
        LevelType.CAVE -> "cave_" + ((item.caveImageIndex).coerceIn(0, 2) + 1)
    }

    SpriteImage(
        name = spriteName,
        contentDescription = item.name,
        modifier = Modifier
            .size(48.dp)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(
                        (screenX - placeable.width / 2).toInt(),
                        (screenY - placeable.height / 2).toInt()
                    )
                }
            }
            .clickable { onClick() }
    )
}
