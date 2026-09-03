package com.xianxia.sect.ui.game.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.ui.game.map.sect.SectCameraState
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.ui.components.clickableWithSound

/**
 * 建筑上方确认/取消（✓/x）按钮的统一大小（dp）。
 * 需求：选中建筑时（含建造建筑时）建筑上方的勾按钮和 x 按钮大小改为 32dp。
 */
internal val CONFIRM_BUTTON_SIZE = 32.dp

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
    BuildingConfirmCancelRow(
        buildingCenterXDp = buildingCenterXDp,
        buildingTopYDp = buildingTopYDp,
        confirmEnabled = canConfirm,
        onConfirm = onConfirm,
        onCancel = onCancel
    )
    // ★ 2026-09 修复：删除 Compose"放置预览覆盖层"（40% 半透明绿矩形）——
    //   与渲染层预览框（描边）叠加成"两个绿色半透明背景"；渲染层已承担
    //   绿/红可放置提示职责，此覆盖层冗余
}

/**
 * 建筑上方居中、固定 48dp 的 ✓/x 按钮行（移动/放置/选中态共用）。
 * 使用新素材勾图标 / x 图标精灵图，不带绿色/红色圆形背景（仅图标，图标素材自带外观）。
 */
@Composable
internal fun BuildingConfirmCancelRow(
    buildingCenterXDp: Float,
    buildingTopYDp: Float,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val spacerDp = CONFIRM_BUTTON_SIZE * 0.4f
    val rowWidth = CONFIRM_BUTTON_SIZE * 2 + spacerDp
    Box(
        modifier = Modifier
            .offset(x = buildingCenterXDp.dp - rowWidth / 2, y = buildingTopYDp.dp - CONFIRM_BUTTON_SIZE - 6.dp)
            .size(width = rowWidth, height = CONFIRM_BUTTON_SIZE)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(CONFIRM_BUTTON_SIZE)
                    .clickableWithSound(enabled = confirmEnabled) { onConfirm() },
                contentAlignment = Alignment.Center
            ) {
                SpriteImage(
                    name = "ui_check_button",
                    contentDescription = "确认",
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds
                )
            }
            Spacer(modifier = Modifier.width(spacerDp))
            Box(
                modifier = Modifier
                    .size(CONFIRM_BUTTON_SIZE)
                    .clickableWithSound { onCancel() },
                contentAlignment = Alignment.Center
            ) {
                SpriteImage(
                    name = "ui_x_button",
                    contentDescription = "取消",
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds
                )
            }
        }
    }
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
