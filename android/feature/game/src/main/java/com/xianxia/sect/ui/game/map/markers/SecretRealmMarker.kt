package com.xianxia.sect.ui.game.map.markers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.game.map.world.WorldCameraState
import com.xianxia.sect.ui.game.map.MapItem

/**
 * 远古秘境地图标记（复用 LevelMarker 精灵图标记样式，48dp 可点击）。
 */
@Composable
fun SecretRealmMarker(
    item: MapItem.SecretRealm,
    cameraState: WorldCameraState,
    onClick: () -> Unit
) {
    val screenX = cameraState.worldToScreenX(item.worldX)
    val screenY = cameraState.worldToScreenY(item.worldY)

    SpriteImage(
        name = "secret_realm",
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
