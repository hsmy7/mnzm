package com.xianxia.sect.ui.game.map.markers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.game.map.MapStyle
import com.xianxia.sect.ui.game.map.world.WorldCameraState

@Composable
fun SectMarker(
    item: MapItem.Sect,
    cameraState: WorldCameraState,
    onClick: () -> Unit
) {
    val markerColor = if (item.isPlayerSect) MapStyle.Colors.sectPlayer else MapStyle.Colors.sectNormal
    val borderColor = if (item.isHighlighted) MapStyle.Colors.sectHighlighted else MapStyle.Colors.sectBorderNormal
    val textColor = if (item.isPlayerSect) MapStyle.Colors.sectTextPlayer else MapStyle.Colors.sectTextNormal
    val fontSize = if (item.isPlayerSect) MapStyle.Typography.sectNamePlayer else MapStyle.Typography.sectNameNormal
    val borderWidth = if (item.isHighlighted) MapStyle.Dimensions.sectHighlightedBorderWidth else MapStyle.Dimensions.sectBorderWidth

    Box(
        modifier = Modifier
            // ★ 2026 修复：相机读取仅发生在 graphicsLayer lambda（draw 阶段求值）——
            // 拖动视角时只重算图层平移，不触发组合/布局。原实现组合内读
            // worldToScreenX/Y + layout{} 重排，每次 pan 全量重组几十个标记 → 卡顿
            .graphicsLayer {
                translationX = cameraState.worldToScreenX(item.worldX) - size.width / 2f
                translationY = cameraState.worldToScreenY(item.worldY) - size.height / 2f
            }
    ) {
        // 外层：最小命中面积（40dp）承载点击，视觉盒居中——文字标记的命中区不再随字号缩水
        Box(
            modifier = Modifier
                .sizeIn(
                    minWidth = MapStyle.Dimensions.sectMinHitSize,
                    minHeight = MapStyle.Dimensions.sectMinHitSize
                )
                .clip(RoundedCornerShape(MapStyle.Dimensions.sectBorderRadius))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
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
}
