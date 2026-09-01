package com.xianxia.sect.ui.game.map.markers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
    val spriteName = when (item.levelType) {
        LevelType.BEAST -> beastNames.getOrElse(item.beastType ?: 0) { "tiger" }
        LevelType.CAVE -> "cave_" + ((item.caveImageIndex).coerceIn(0, 2) + 1)
    }

    SpriteImage(
        name = spriteName,
        contentDescription = item.name,
        modifier = Modifier
            .size(48.dp)
            // ★ 2026 修复：相机读取仅发生在 graphicsLayer lambda（draw 阶段求值）——
            // 拖动视角时只重算图层平移，不触发组合/布局。原实现组合内读
            // worldToScreenX/Y + layout{} 重排，每次 pan 全量重组几十个标记 → 卡顿
            .graphicsLayer {
                translationX = cameraState.worldToScreenX(item.worldX) - size.width / 2f
                translationY = cameraState.worldToScreenY(item.worldY) - size.height / 2f
            }
            .clickable { onClick() }
    )
}
