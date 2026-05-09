package com.xianxia.sect.ui.game.map.markers

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.xianxia.sect.R
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.ui.game.map.CameraState
import com.xianxia.sect.ui.game.map.MapItem

private val beastImages = listOf(
    R.drawable.tiger_beast,
    R.drawable.wolf_beast,
    R.drawable.snake_beast,
    R.drawable.bear_beast,
    R.drawable.eagle_beast,
    R.drawable.fox_beast,
    R.drawable.dragon_beast,
    R.drawable.turtle_beast
)

private val caveImages = listOf(
    R.drawable.cave_1,
    R.drawable.cave_2,
    R.drawable.cave_3
)

@Composable
fun LevelMarker(
    item: MapItem.Level,
    cameraState: CameraState,
    onClick: () -> Unit
) {
    val screenX = cameraState.worldToScreenX(item.worldX)
    val screenY = cameraState.worldToScreenY(item.worldY)

    val imageRes = when (item.levelType) {
        LevelType.BEAST -> beastImages.getOrElse(item.beastType ?: 0) { R.drawable.tiger_beast }
        LevelType.CAVE -> caveImages.getOrElse(item.caveImageIndex) { R.drawable.cave_1 }
    }

    Image(
        painter = painterResource(id = imageRes),
        contentDescription = item.name,
        modifier = Modifier
            .size(48.dp)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(
                        (screenX - placeable.width / 2).toInt(),
                        (screenY - placeable.height / 2).toInt()
                    )
                }
            }
            .clickable { onClick() }
    )
}
