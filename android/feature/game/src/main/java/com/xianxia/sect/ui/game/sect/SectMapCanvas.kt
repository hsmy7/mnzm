package com.xianxia.sect.ui.game.sect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.util.BuildingSpatialIndex
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.game.building.BuildingDef
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DragTarget { CAMERA, BUILDING_MOVE, BUILDING_PLACE }

@Composable
fun SectMapCanvas(
    config: SectMapRenderConfig,
    staticData: SectMapStaticData,
    placement: PlacementModeState,
    move: MoveModeState,
    buildingIndex: BuildingSpatialIndex,
    onBuildingClick: (GridBuildingData) -> Unit,
    onBuildingLongPress: (GridBuildingData) -> Unit,
    onPlacementDrag: (Float, Float) -> Unit,
    onMovingDrag: (Float, Float) -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    val currentOnBuildingClick by rememberUpdatedState(onBuildingClick)
    val currentOnBuildingLongPress by rememberUpdatedState(onBuildingLongPress)
    val currentOnPlacementDrag by rememberUpdatedState(onPlacementDrag)
    val currentOnMovingDrag by rememberUpdatedState(onMovingDrag)
    val currentOnUserInteraction by rememberUpdatedState(onUserInteraction)
    val currentIsMoving by rememberUpdatedState(move.isActive)
    val currentIsPlacing by rememberUpdatedState(placement.isActive)
    val currentMovingWorldX by rememberUpdatedState(move.worldX)
    val currentMovingWorldY by rememberUpdatedState(move.worldY)
    val currentPreviewWorldX by rememberUpdatedState(placement.worldX)
    val currentPreviewWorldY by rememberUpdatedState(placement.worldY)
    val currentMovingSize by rememberUpdatedState(move.size)
    val currentPreviewSize by rememberUpdatedState(placement.size)
    val currentMovingInstanceId by rememberUpdatedState(move.building?.instanceId)
    val longPressScope = rememberCoroutineScope()

    val worldPixelWidth = config.worldWidthCells * config.tileSize
    val worldPixelHeight = config.worldHeightCells * config.tileSize

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val scaledPos = downPos

                    val wx = config.cameraState.screenToWorldX(scaledPos.x)
                    val wy = config.cameraState.screenToWorldY(scaledPos.y)

                    val gridX = (wx / config.tileSize).toInt()
                    val gridY = (wy / config.tileSize).toInt()
                    val touchedBuilding = buildingIndex.findBuildingAt(gridX, gridY)
                        ?.takeIf { it.instanceId != currentMovingInstanceId }

                    val onMovingBuilding = currentIsMoving && run {
                        val bw = currentMovingSize.width * config.tileSize
                        val bh = currentMovingSize.height * config.tileSize
                        wx >= currentMovingWorldX && wx < currentMovingWorldX + bw &&
                            wy >= currentMovingWorldY && wy < currentMovingWorldY + bh
                    }

                    val onPlacingBuilding = currentIsPlacing && run {
                        val bw = currentPreviewSize.width * config.tileSize
                        val bh = currentPreviewSize.height * config.tileSize
                        wx >= currentPreviewWorldX && wx < currentPreviewWorldX + bw &&
                            wy >= currentPreviewWorldY && wy < currentPreviewWorldY + bh
                    }

                    var longPressTriggered = false
                    var dragStarted = false
                    var dragTarget = DragTarget.CAMERA
                    var lastPos = downPos

                    val longPressJob = longPressScope.launch {
                        delay(viewConfiguration.longPressTimeoutMillis)
                        if (!dragStarted && touchedBuilding != null &&
                            !currentIsMoving && !currentIsPlacing
                        ) {
                            longPressTriggered = true
                            currentOnBuildingLongPress(touchedBuilding)
                            dragTarget = DragTarget.BUILDING_MOVE
                        }
                    }

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break

                        if (!change.pressed) {
                            longPressJob.cancel()
                            if (!dragStarted && !longPressTriggered && touchedBuilding != null) {
                                currentOnBuildingClick(touchedBuilding)
                            }
                            change.consume()
                            break
                        }

                        if (!dragStarted) {
                            val dx = change.position.x - downPos.x
                            val dy = change.position.y - downPos.y
                            if (dx * dx + dy * dy > viewConfiguration.touchSlop * viewConfiguration.touchSlop) {
                                dragStarted = true
                                longPressJob.cancel()
                                dragTarget = when {
                                    longPressTriggered -> DragTarget.BUILDING_MOVE
                                    currentIsMoving && onMovingBuilding -> DragTarget.BUILDING_MOVE
                                    currentIsPlacing && onPlacingBuilding -> DragTarget.BUILDING_PLACE
                                    else -> DragTarget.CAMERA
                                }
                            }
                        }

                        if (dragStarted) {
                            change.consume()
                            val dragAmountX = change.position.x - lastPos.x
                            val dragAmountY = change.position.y - lastPos.y
                            when (dragTarget) {
                                DragTarget.BUILDING_MOVE -> currentOnMovingDrag(dragAmountX, dragAmountY)
                                DragTarget.BUILDING_PLACE -> currentOnPlacementDrag(dragAmountX, dragAmountY)
                                DragTarget.CAMERA -> {
                                    config.cameraState.pan(dragAmountX, dragAmountY)
                                    currentOnUserInteraction()
                                }
                            }
                        }

                        lastPos = change.position
                    } while (true)
                }
            }
    ) {
        val sw = size.width
        val sh = size.height

        // 渲染时摄像机坐标取整 —— 消除浮点亚像素反走样
        // 对标: Unity PixelPerfectCamera, LÖVE2D math.floor(camera)
        // 逻辑层保持 Float 精度，仅在最终绘制时 snap
        val renderCamX = config.cameraState.cameraX.roundToInt().toFloat()
        val renderCamY = config.cameraState.cameraY.roundToInt().toFloat()

        clipRect(0f, 0f, size.width, size.height) {
        withTransform({
            translate(-renderCamX, -renderCamY)
        }) {
            // 1. 静态背景层（含已烘焙建筑，或纯地形背景）
            // 来源: docs/gpu-tier-fairness-plan.md §3 — 始终拉伸到世界尺寸，内部位图可能低于世界分辨率
            // drawImage 四边各外扩 1px —— 防御 GPU 双线性边缘采样偏差
            // 对标: Skia chromium:1324336 epsilon clamping
            // 3072→3074 拉伸率 <0.1%，肉眼不可见，不影响建筑对齐
            drawImage(
                staticData.fullMapBmp,
                dstOffset = IntOffset(-1, -1),
                dstSize = IntSize(worldPixelWidth + 2, worldPixelHeight + 2)
            )

            // 2. 动态建筑绘制
            if (!staticData.buildingsBaked) {
                // 低配设备：所有建筑动态绘制（未烘焙进 fullMapBmp）
                for (building in staticData.placedBuildings) {
                    if (move.building?.instanceId != null && building.instanceId == move.building?.instanceId) continue
                    val bx = building.gridX * config.tileSize
                    val by = building.gridY * config.tileSize
                    val bw = building.width * config.tileSize
                    val bh = building.height * config.tileSize
                    val bmp = staticData.buildingBitmaps[building.displayName]
                    if (bmp != null) {
                        drawImage(bmp, dstOffset = IntOffset(bx, by), dstSize = IntSize(bw, bh))
                    } else {
                        drawRect(Color(0xFFBDBDBD).copy(alpha = 0.8f), Offset(bx.toFloat(), by.toFloat()), Size(bw.toFloat(), bh.toFloat()))
                    }
                }
            }
            // 3. 移动中的建筑（0.5 alpha）— 高配设备烘焙层排除它，低配在步骤 2 中跳过了它
            if (move.building?.instanceId != null) {
                for (building in staticData.placedBuildings) {
                    if (building.instanceId != move.building?.instanceId) continue
                    val bx = building.gridX * config.tileSize
                    val by = building.gridY * config.tileSize
                    val bw = building.width * config.tileSize
                    val bh = building.height * config.tileSize
                    val bmp = staticData.buildingBitmaps[building.displayName]
                    if (bmp != null) {
                        drawImage(bmp, dstOffset = IntOffset(bx, by), dstSize = IntSize(bw, bh), alpha = 0.5f)
                    }
                }
            }

            // 2.5. 灵植阁光环预览 — 按 GPU 等级分层渲染
            // 来源: docs/huawei-performance-research.md §4.3
            val herbGardenAuraName = BuildingDef.HERB_GARDEN.displayName
            val showHerbGardenAura = (placement.isActive && placement.buildingName == herbGardenAuraName) ||
                    (move.isActive && (move.building?.displayName ?: "") == herbGardenAuraName)
            if (showHerbGardenAura && config.gpuRenderConfig.auraEffectMode != "off") {
                val hgGridX = if (placement.isActive) placement.gridX else move.gridX
                val hgGridY = if (placement.isActive) placement.gridY else move.gridY
                val hgW = if (placement.isActive) placement.size.width else move.size.width
                val hgH = if (placement.isActive) placement.size.height else move.size.height
                val hgCenterX = hgGridX + hgW / 2.0
                val hgCenterY = hgGridY + hgH / 2.0
                val auraRadius = GameConfig.HerbGarden.AURA_RADIUS_TILES
                // "simple" 模式：仅绘制圆形轮廓，不逐格填充矩形（减少 Overdraw）
                // "full" 模式：逐格矩形填充 + 圆形轮廓
                if (config.gpuRenderConfig.auraEffectMode == "full") {
                    val spiritFieldName = BuildingDef.SPIRIT_FIELD.displayName
                    for (building in staticData.placedBuildings) {
                        if (building.displayName != spiritFieldName) continue
                        val closestX = hgCenterX.coerceIn(
                            building.gridX.toDouble(), (building.gridX + building.width).toDouble()
                        )
                        val closestY = hgCenterY.coerceIn(
                            building.gridY.toDouble(), (building.gridY + building.height).toDouble()
                        )
                        val dx = closestX - hgCenterX
                        val dy = closestY - hgCenterY
                        if (dx * dx + dy * dy <= auraRadius * auraRadius) {
                            drawRect(
                                Color(0x404CAF50),
                                Offset(building.gridX * config.tileSize.toFloat(), building.gridY * config.tileSize.toFloat()),
                                Size(building.width * config.tileSize.toFloat(), building.height * config.tileSize.toFloat())
                            )
                        }
                    }
                }
            }

            // 3. 网格线（放置/移动模式时显示）
            // 来源: docs/huawei-performance-research.md §4.2 — LOW 级别仅绘制边界
            if (placement.isActive || move.isActive) {
                val gridColor = Color(0xFFE4DDD0)
                val visibleStartX = config.cameraState.cameraX
                val visibleEndX = config.cameraState.cameraX + sw
                val visibleStartY = config.cameraState.cameraY
                val visibleEndY = config.cameraState.cameraY + sh

                if (config.gpuRenderConfig.gridLineMode == "border") {
                    // LOW 模式：仅绘制可视区域边界线（4条线替代 ~100 条线）
                    val clippedStartX = visibleStartX.coerceAtLeast(0f)
                    val clippedEndX = visibleEndX.coerceAtMost(worldPixelWidth.toFloat())
                    val clippedStartY = visibleStartY.coerceAtLeast(0f)
                    val clippedEndY = visibleEndY.coerceAtMost(worldPixelHeight.toFloat())
                    // 放置/移动区域的边界
                    val activeGridX = if (placement.isActive) placement.gridX else move.gridX
                    val activeGridY = if (placement.isActive) placement.gridY else move.gridY
                    val activeW = if (placement.isActive) placement.size.width else move.size.width
                    val activeH = if (placement.isActive) placement.size.height else move.size.height
                    val bx1 = (activeGridX * config.tileSize).toFloat()
                    val by1 = (activeGridY * config.tileSize).toFloat()
                    val bx2 = ((activeGridX + activeW) * config.tileSize).toFloat()
                    val by2 = ((activeGridY + activeH) * config.tileSize).toFloat()
                    drawLine(gridColor, Offset(bx1, by1), Offset(bx2, by1), strokeWidth = 1f)
                    drawLine(gridColor, Offset(bx2, by1), Offset(bx2, by2), strokeWidth = 1f)
                    drawLine(gridColor, Offset(bx2, by2), Offset(bx1, by2), strokeWidth = 1f)
                    drawLine(gridColor, Offset(bx1, by2), Offset(bx1, by1), strokeWidth = 1f)
                } else {
                    // 完整网格线
                    val firstCol = (visibleStartX / config.tileSize).toInt().coerceAtLeast(0)
                    val lastCol = (visibleEndX / config.tileSize).toInt().coerceAtMost(config.worldWidthCells)
                    val clippedStartY = visibleStartY.coerceAtLeast(0f)
                    val clippedEndY = visibleEndY.coerceAtMost(worldPixelHeight.toFloat())
                    for (col in firstCol..lastCol) {
                        val x = (col * config.tileSize).toFloat()
                        drawLine(gridColor, Offset(x, clippedStartY), Offset(x, clippedEndY), strokeWidth = 1f)
                    }

                    val firstRow = (visibleStartY / config.tileSize).toInt().coerceAtLeast(0)
                    val lastRow = (visibleEndY / config.tileSize).toInt().coerceAtMost(config.worldHeightCells)
                    val clippedStartX = visibleStartX.coerceAtLeast(0f)
                    val clippedEndX = visibleEndX.coerceAtMost(worldPixelWidth.toFloat())
                    for (row in firstRow..lastRow) {
                        val y = (row * config.tileSize).toFloat()
                        drawLine(gridColor, Offset(clippedStartX, y), Offset(clippedEndX, y), strokeWidth = 1f)
                    }
                }
            }

            // 4. 放置预览
            // 来源: docs/huawei-performance-research.md §4.3 — 单一矩形替代逐格矩形填充
            if (placement.isActive) {
                // 建筑素材图
                if (placement.buildingName.isNotEmpty()) {
                    val placeBmp = staticData.buildingBitmaps[placement.buildingName]
                    if (placeBmp != null) {
                        drawImage(
                            placeBmp,
                            dstOffset = IntOffset(placement.worldX.roundToInt(), placement.worldY.roundToInt()),
                            dstSize = IntSize(placement.size.width * config.tileSize, placement.size.height * config.tileSize)
                        )
                    }
                }
                val previewColor = when (placement.validity) {
                    is GridSnapHelper.PlacementValidity.Valid -> Color(0x404CAF50)
                    is GridSnapHelper.PlacementValidity.OutOfBounds -> Color(0x40F44336)
                    is GridSnapHelper.PlacementValidity.Overlap -> Color(0x40FF5722)
                }
                // 单一矩形覆盖替代逐格矩形（减少 Overdraw）
                drawRect(
                    previewColor,
                    Offset((placement.gridX * config.tileSize).toFloat(), (placement.gridY * config.tileSize).toFloat()),
                    Size((placement.size.width * config.tileSize).toFloat(), (placement.size.height * config.tileSize).toFloat())
                )
            }

            // 5. 移动预览
            // 来源: docs/huawei-performance-research.md §4.3 — 单一矩形替代逐格矩形填充
            if (move.isActive) {
                if ((move.building?.displayName ?: "").isNotEmpty()) {
                    val moveBmp = staticData.buildingBitmaps[move.building?.displayName ?: ""]
                    if (moveBmp != null) {
                        drawImage(
                            moveBmp,
                            dstOffset = IntOffset(move.worldX.roundToInt(), move.worldY.roundToInt()),
                            dstSize = IntSize(move.size.width * config.tileSize, move.size.height * config.tileSize),
                            alpha = 0.7f
                        )
                    }
                }
                val moveColor = when (move.validity) {
                    is GridSnapHelper.PlacementValidity.Valid -> Color(0x404CAF50)
                    is GridSnapHelper.PlacementValidity.OutOfBounds -> Color(0x40F44336)
                    is GridSnapHelper.PlacementValidity.Overlap -> Color(0x40FF5722)
                }
                // 单一矩形覆盖替代逐格矩形（减少 Overdraw）
                drawRect(
                    moveColor,
                    Offset((move.gridX * config.tileSize).toFloat(), (move.gridY * config.tileSize).toFloat()),
                    Size((move.size.width * config.tileSize).toFloat(), (move.size.height * config.tileSize).toFloat())
                )
            }

            // 6. 灵植阁光环范围圈
            if (showHerbGardenAura) {
                val centerX: Float
                val centerY: Float
                if (placement.isActive) {
                    centerX = placement.worldX + (placement.size.width * config.tileSize) / 2f
                    centerY = placement.worldY + (placement.size.height * config.tileSize) / 2f
                } else {
                    centerX = move.worldX + (move.size.width * config.tileSize) / 2f
                    centerY = move.worldY + (move.size.height * config.tileSize) / 2f
                }
                drawCircle(
                    color = Color(0x404CAF50),
                    radius = (GameConfig.HerbGarden.AURA_RADIUS_TILES * config.tileSize).toFloat(),
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
        }
    }
}
