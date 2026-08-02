package com.xianxia.sect.ui.game.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.game.map.sect.SectCameraState

/** 未选中占地范围：半透明绿色（与放置预览一致） */
private val UNSELECTED_COLOR = Color(0x664CAF50)
/** 选中占地范围：半透明红色 */
private val SELECTED_COLOR = Color(0x66F44336)
/** 选中建筑边框：不透明红色 */
private val SELECTED_BORDER_COLOR = Color(0xFFF44336)
/** 绿→红过渡时长（毫秒） */
private const val SELECTION_ANIM_MS = 180
/** 选中边框厚度（像素） */
private const val BORDER_THICKNESS = 2f

/**
 * 一键拆除模式占地范围覆盖层。
 *
 * 拆除模式下所有可拆建筑常显绿色半透明占地框（随相机移动），
 * 点击某建筑后该建筑范围绿→红过渡动画 = 选中，再次点击变回绿色。
 */
@Composable
internal fun DemolishSelectionOverlay(
    buildings: List<GridBuildingData>,
    selectedIds: Set<String>,
    cameraState: SectCameraState,
    tileSize: Int
) {
    val density = LocalDensity.current.density
    // 仅渲染已注册建筑（旧档未知建筑不可选中不可拆，与引擎防御一致）
    buildings.filter { BuildingFeatureRegistry.findByDisplayName(it.displayName) != null }
        .forEach { b ->
        key(b.instanceId) {
            val selected = b.instanceId in selectedIds
            val color by animateColorAsState(
                targetValue = if (selected) SELECTED_COLOR else UNSELECTED_COLOR,
                animationSpec = tween(SELECTION_ANIM_MS)
            )
            val worldX = GridSnapHelper.gridToWorld(b.gridX, tileSize).toFloat()
            val worldY = GridSnapHelper.gridToWorld(b.gridY, tileSize).toFloat()
            val overlayWDp = (b.width * tileSize * cameraState.scale) / density
            val overlayHDp = (b.height * tileSize * cameraState.scale) / density
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = cameraState.worldToScreenX(worldX)
                        translationY = cameraState.worldToScreenY(worldY)
                    }
                    .size(width = overlayWDp.dp, height = overlayHDp.dp)
                    .background(color)
            ) {
                if (selected) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawRect(
                            color = SELECTED_BORDER_COLOR,
                            topLeft = Offset(0f, 0f),
                            size = Size(size.width, BORDER_THICKNESS)
                        )
                        drawRect(
                            color = SELECTED_BORDER_COLOR,
                            topLeft = Offset(0f, size.height - BORDER_THICKNESS),
                            size = Size(size.width, BORDER_THICKNESS)
                        )
                        drawRect(
                            color = SELECTED_BORDER_COLOR,
                            topLeft = Offset(0f, 0f),
                            size = Size(BORDER_THICKNESS, size.height)
                        )
                        drawRect(
                            color = SELECTED_BORDER_COLOR,
                            topLeft = Offset(size.width - BORDER_THICKNESS, 0f),
                            size = Size(BORDER_THICKNESS, size.height)
                        )
                    }
                }
            }
        }
    }
}
