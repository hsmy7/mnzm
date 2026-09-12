package com.xianxia.sect.ui.game.map.markers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.game.map.world.WorldCameraState
import com.xianxia.sect.ui.game.map.MapItem

/**
 * 远古秘境地图标记（复用 LevelMarker 精灵图标记样式，48dp 可点击）。
 *
 * 相机读取仅发生在 graphicsLayer lambda（draw 阶段求值）——拖动视角时只重算
 * 图层平移，不触发组合/布局（与 LevelMarker 同款 2026 卡顿修复）。
 */
@Composable
fun SecretRealmMarker(
    item: MapItem.SecretRealm,
    cameraState: WorldCameraState,
    onClick: () -> Unit
) {
    SpriteImage(
        name = "secret_realm",
        contentDescription = item.name,
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                translationX = cameraState.worldToScreenX(item.worldX) - size.width / 2f
                translationY = cameraState.worldToScreenY(item.worldY) - size.height / 2f
            }
            .clickable { onClick() }
    )
}
