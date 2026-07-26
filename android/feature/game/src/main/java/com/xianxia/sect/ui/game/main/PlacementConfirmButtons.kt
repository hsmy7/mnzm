package com.xianxia.sect.ui.game.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.ui.game.map.sect.SectCameraState
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.ui.components.clickableWithSound

/**
 * 建筑放置确认/取消按钮 — 固定出现在建筑上方居中，不受地图方格尺寸限制。
 */
@Composable
internal fun PlacementConfirmButtons(
    snappedGridX: Int,
    snappedGridY: Int,
    buildingSize: GridSnapHelper.BuildingSize,
    cameraState: SectCameraState,
    tileSize: Int,
    validity: GridSnapHelper.PlacementValidity,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val density = LocalDensity.current.density
    val worldX = GridSnapHelper.gridToWorld(snappedGridX, tileSize).toFloat()
    val worldY = GridSnapHelper.gridToWorld(snappedGridY, tileSize).toFloat()
    val buildingCenterXDp = cameraState.worldToScreenX(worldX + buildingSize.width * tileSize / 2f) / density
    val buildingTopYDp = cameraState.worldToScreenY(worldY) / density
    val canConfirm = validity == GridSnapHelper.PlacementValidity.Valid
    val btnDp = (tileSize / density).dp
    val spacerDp = btnDp * 0.4f
    // 按钮行独立于建筑宽度，居中出现在建筑正上方（2×2 按钮）
    Box(
        modifier = Modifier
            .offset(x = buildingCenterXDp.dp - btnDp * 2.2f, y = buildingTopYDp.dp - btnDp * 2.8f)
            .size(width = btnDp * 4f + spacerDp, height = btnDp * 2)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(btnDp * 2)
                    .background(if (canConfirm) Color(0xFF4CAF50) else Color.Black, CircleShape)
                    .clickableWithSound(enabled = canConfirm) { onConfirm() },
                contentAlignment = Alignment.Center
            ) { Text("✓", fontSize = (btnDp.value * 0.5f).sp, color = Color.Black, fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.width(spacerDp))
            Box(
                modifier = Modifier.size(btnDp * 2)
                    .background(Color(0xFFF44336), CircleShape)
                    .clickableWithSound { onCancel() },
                contentAlignment = Alignment.Center
            ) { Text("✗", fontSize = (btnDp.value * 0.5f).sp, color = Color.Black, fontWeight = FontWeight.Bold) }
        }
    }
    // 放置预览覆盖层 — 尺寸需乘以相机缩放（与 worldToScreenX 同步）
    val overlayWDp = (buildingSize.width * tileSize * cameraState.scale) / density
    val overlayHDp = (buildingSize.height * tileSize * cameraState.scale) / density
    val overlayColor = if (canConfirm) Color(0x664CAF50) else Color(0x66F44336)
    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = cameraState.worldToScreenX(worldX)
                translationY = cameraState.worldToScreenY(worldY)
            }
            .size(width = overlayWDp.dp, height = overlayHDp.dp)
            .background(overlayColor)
    )
}

internal fun getBuildingColor(displayName: String): Color {
    val c = BuildingFeatureRegistry.findByDisplayName(displayName)?.color ?: 0xFFEEEEEE
    return Color(c)
}

/**
 * 拆除按钮 — 在移动建筑模式下显示在建筑下方。
 */
@Composable
internal fun DemolishButton(
    building: GridBuildingData,
    snappedGridX: Int,
    snappedGridY: Int,
    buildingSize: GridSnapHelper.BuildingSize,
    cameraState: SectCameraState,
    tileSize: Int,
    onDemolish: () -> Unit
) {
    val density = LocalDensity.current.density
    val worldX = (snappedGridX * tileSize).toFloat()
    val worldY = (snappedGridY * tileSize).toFloat()
    val buildingBottomYDp = cameraState.worldToScreenY(worldY + buildingSize.height * tileSize) / density
    val buildingCenterXDp = cameraState.worldToScreenX(
        worldX + buildingSize.width * tileSize / 2f
    ) / density

    var showConfirm by remember { mutableStateOf(false) }
    val btnW = (tileSize / density).dp * 4
    val btnH = (tileSize / density).dp * 2

    Box(
        modifier = Modifier
            .offset(
                x = buildingCenterXDp.dp - btnW / 2,
                y = buildingBottomYDp.dp + 8.dp
            )
            .width(btnW)
            .height(btnH)
            .background(Color(0xFFD32F2F), RoundedCornerShape(6.dp))
            .clickableWithSound { showConfirm = true },
        contentAlignment = Alignment.Center
    ) {
        Text("拆除", fontSize = 14.sp, color = Color.Black, fontWeight = FontWeight.Bold)
    }

    if (showConfirm) {
        StandardPromptDialog(
            onDismissRequest = { showConfirm = false },
            title = "确认拆除",
            text = "确定要拆除「${building.displayName}」吗？\n将返还 50% 建造灵石。",
            confirmLabel = "拆除",
            onConfirm = {
                showConfirm = false
                onDemolish()
            },
            dismissLabel = "取消"
        )
    }
}
