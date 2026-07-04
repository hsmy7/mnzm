package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Rect
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.util.BuildingSpatialIndex
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.components.fallbackToTier1
import com.xianxia.sect.ui.game.building.BuildingDef
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xianxia.sect.core.util.GridSystem

private enum class DragTarget { CAMERA, BUILDING_MOVE, BUILDING_PLACE, GOLD_FINGER }

/** 灵田作物生长阶段 */
private enum class GrowthStage { SEED, GROWING, MATURE }

/**
 * 根据种植时间和当前时间计算作物生长阶段。
 * 前20%时间 → 种子期，中70%时间 → 成长期，后10%时间 → 成熟期。
 * 返回 null 表示灵田未种植或数据无效。
 */
private fun getGrowthStage(
    plant: SpiritFieldPlant,
    currentYear: Int,
    currentMonth: Int
): GrowthStage? {
    if (plant.seedId.isEmpty() || plant.growTime <= 0) return null
    val elapsed = (currentYear - plant.plantYear) * 12 +
            (currentMonth - plant.plantMonth)
    val progress = (elapsed.toFloat() / plant.growTime.toFloat())
        .coerceIn(0f, 1f)
    return when {
        progress < 0.20f -> GrowthStage.SEED
        progress < 0.90f -> GrowthStage.GROWING
        else -> GrowthStage.MATURE
    }
}

@Composable
fun SectMapCanvas(
    config: SectMapRenderConfig,
    staticData: SectMapStaticData,
    placement: PlacementModeState,
    move: MoveModeState,
    goldFinger: GoldFingerState = GoldFingerState.INACTIVE,
    buildingIndex: BuildingSpatialIndex,
    onBuildingClick: (GridBuildingData) -> Unit,
    onBuildingLongPress: (GridBuildingData) -> Unit,
    onPlacementDrag: (Float, Float) -> Unit,
    onMovingDrag: (Float, Float) -> Unit,
    onGoldFingerStart: () -> Unit = {},
    onGoldFingerDrag: (endGridX: Int, endGridY: Int) -> Unit = { _, _ -> },
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    val currentOnBuildingClick by rememberUpdatedState(onBuildingClick)
    val currentOnBuildingLongPress by rememberUpdatedState(onBuildingLongPress)
    val currentOnPlacementDrag by rememberUpdatedState(onPlacementDrag)
    val currentOnMovingDrag by rememberUpdatedState(onMovingDrag)
    val currentOnGoldFingerStart by rememberUpdatedState(onGoldFingerStart)
    val currentOnGoldFingerDrag by rememberUpdatedState(onGoldFingerDrag)
    val currentOnUserInteraction by rememberUpdatedState(onUserInteraction)
    val currentIsMoving by rememberUpdatedState(move.isActive)
    val currentIsPlacing by rememberUpdatedState(placement.isActive)
    val currentIsGoldFinger by rememberUpdatedState(goldFinger.isActive)
    val currentMovingWorldX by rememberUpdatedState(move.worldX)
    val currentMovingWorldY by rememberUpdatedState(move.worldY)
    val currentPreviewWorldX by rememberUpdatedState(placement.worldX)
    val currentPreviewWorldY by rememberUpdatedState(placement.worldY)
    val currentPreviewSize by rememberUpdatedState(placement.size)
    val currentMovingSize by rememberUpdatedState(move.size)
    val currentMovingInstanceId by rememberUpdatedState(move.building?.instanceId)
    val longPressScope = rememberCoroutineScope()

    val worldPixelWidth = config.worldWidthCells * config.tileSize
    val worldPixelHeight = config.worldHeightCells * config.tileSize

    // 预计算的建筑显示名常量
    val herbGardenDisplayName = BuildingDef.HERB_GARDEN.displayName

    // ===== 静态层离屏缓存（背景 + 建筑，避免每帧 drawImage） =====
    // 仅在 staticData 或移动建筑变化时重建，相机平移不触发重建
    val skipMovingId: String? = move.building?.instanceId
    var staticLayerCache by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(staticData, skipMovingId) {
        staticLayerCache = withContext(Dispatchers.Default) {
            buildStaticLayerCache(config, staticData, skipMovingId)
        }
    }
    // 组件销毁时回收缓存 Bitmap
    DisposableEffect(Unit) {
        onDispose {
            staticLayerCache?.asAndroidBitmap()?.takeIf { !it.isRecycled }?.recycle()
            staticLayerCache = null
        }
    }

    Box(
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

                    // 金手指区域判定：建筑预览框外部右下角单格（与图标绘制位置一致）
                    val onGoldFingerArea = currentIsPlacing && !currentIsGoldFinger && run {
                        val ts = config.tileSize
                        val bGridX = (currentPreviewWorldX / ts).roundToInt()
                        val bGridY = (currentPreviewWorldY / ts).roundToInt()
                        val gfWx = (bGridX + currentPreviewSize.width) * ts
                        val gfWy = (bGridY + currentPreviewSize.height) * ts
                        wx >= gfWx && wx < gfWx + ts && wy >= gfWy && wy < gfWy + ts
                    }

                    var longPressTriggered = false
                    var dragStarted = false
                    var dragTarget = DragTarget.CAMERA
                    var lastPos = downPos

                    val longPressJob = longPressScope.launch {
                        delay(viewConfiguration.longPressTimeoutMillis)
                        if (!dragStarted && !currentIsMoving) {
                            if (currentIsPlacing && onGoldFingerArea && !currentIsGoldFinger) {
                                // 金手指长按 → 进入金手指模式
                                longPressTriggered = true
                                dragTarget = DragTarget.GOLD_FINGER
                                currentOnGoldFingerStart()
                            } else if (touchedBuilding != null && !currentIsPlacing) {
                                // 已有建筑长按 → 移动模式
                                longPressTriggered = true
                                currentOnBuildingLongPress(touchedBuilding)
                                dragTarget = DragTarget.BUILDING_MOVE
                            }
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
                                    currentIsGoldFinger -> DragTarget.GOLD_FINGER
                                    onGoldFingerArea -> {
                                        currentOnGoldFingerStart()
                                        DragTarget.GOLD_FINGER
                                    }
                                    longPressTriggered && currentIsGoldFinger -> DragTarget.GOLD_FINGER
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
                                DragTarget.GOLD_FINGER -> {
                                    val newWx = config.cameraState.screenToWorldX(change.position.x)
                                    val newWy = config.cameraState.screenToWorldY(change.position.y)
                                    val newGridX = (newWx / config.tileSize).toInt()
                                    val newGridY = (newWy / config.tileSize).toInt()
                                    currentOnGoldFingerDrag(newGridX, newGridY)
                                    currentOnUserInteraction()
                                }
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
            // ----- LAYER 1: 静态背景层（离屏缓存加速） -----
            // 使用 drawBehind 绘制在 Box 内容之后、子元素 (Canvas) 之前。
            // 静态内容（背景 + 建筑）已预渲染到 staticLayerCache 中，
            // 每帧仅 1 次 drawImage 而非 N+1 次，拖拽时开销恒定。
            // 缓存仅在 staticData 或 skipMovingId 变化时重建（LaunchedEffect）。
            .drawBehind {
                // 渲染时摄像机坐标取整 —— 消除浮点亚像素反走样
                // 对标: Unity PixelPerfectCamera, LÖVE2D math.floor(camera)
                val renderCamX = config.cameraState.cameraX.roundToInt().toFloat()
                val renderCamY = config.cameraState.cameraY.roundToInt().toFloat()

                clipRect(0f, 0f, size.width, size.height) {
                    withTransform({
                        translate(
                            -renderCamX * config.cameraState.scale,
                            -renderCamY * config.cameraState.scale
                        )
                        scale(config.cameraState.scale, config.cameraState.scale)
                    }) {
                        // 静态层：使用离屏缓存（存在时）或回退到直接绘制
                        // drawImage 四边各外扩 1px —— 防御 GPU 双线性边缘采样偏差
                        // 对标: Skia chromium:1324336 epsilon clamping
                        // 3072→3074 拉伸率 <0.1%，肉眼不可见，不影响建筑对齐
                        val cached = staticLayerCache
                        if (cached != null) {
                            drawImage(
                                cached,
                                dstOffset = IntOffset(-1, -1),
                                dstSize = IntSize(worldPixelWidth + 2, worldPixelHeight + 2)
                            )
                        } else {
                            // 兜底：缓存未就绪时直接绘制（仅前几帧）
                            val ts = config.tileSize
                            drawImage(
                                staticData.fullMapBmp,
                                dstOffset = IntOffset(-1, -1),
                                dstSize = IntSize(worldPixelWidth + 2, worldPixelHeight + 2)
                            )
                            if (!staticData.buildingsBaked) {
                                val skipId = move.building?.instanceId
                                for (building in staticData.placedBuildings) {
                                    if (skipId != null && building.instanceId == skipId) continue
                                    val bx = building.gridX * ts
                                    val by = building.gridY * ts
                                    val bw = building.width * ts
                                    val bh = building.height * ts
                                    val bmp = staticData.buildingBitmaps[building.displayName]
                                    if (bmp != null) {
                                        drawImage(bmp, dstOffset = IntOffset(bx, by), dstSize = IntSize(bw, bh))
                                    } else {
                                        drawRect(Color(0xFFBDBDBD).copy(alpha = 0.8f), Offset(bx.toFloat(), by.toFloat()), Size(bw.toFloat(), bh.toFloat()))
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        // ===== LAYER 2: 动态叠加层 =====
        // 独立的 Canvas，仅当交互状态 (placement/move/goldFinger) 变化时重绘。
        // 静态背景由 Box.drawBehind 渲染，互不干扰。
        // key(config, staticData) 确保仅当 config/staticData 真正变化时重建 Canvas，
        // 避免交互状态变化时 Canvas 结构被不必要地销毁重建。
        key(config, staticData) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val renderCamX = config.cameraState.cameraX.roundToInt().toFloat()
                val renderCamY = config.cameraState.cameraY.roundToInt().toFloat()
                val ts = config.tileSize
                val sw = size.width
                val sh = size.height

                clipRect(0f, 0f, sw, sh) {
                    withTransform({
                        translate(
                            -renderCamX * config.cameraState.scale,
                            -renderCamY * config.cameraState.scale
                        )
                        scale(config.cameraState.scale, config.cameraState.scale)
                    }) {
                        // 3. 移动中的建筑（0.5 alpha）— 高配设备烘焙层排除它，低配在静态层中跳过了它
                        if (move.building?.instanceId != null) {
                            for (building in staticData.placedBuildings) {
                                if (building.instanceId != move.building?.instanceId) continue
                                val bx = building.gridX * ts
                                val by = building.gridY * ts
                                val bw = building.width * ts
                                val bh = building.height * ts
                                val bmp = staticData.buildingBitmaps[building.displayName]
                                if (bmp != null) {
                                    drawImage(bmp, dstOffset = IntOffset(bx, by), dstSize = IntSize(bw, bh), alpha = 0.5f)
                                }
                            }
                        }

                        // 4. 灵田作物动态叠加层 — 不参与烘焙（生长状态随时间逐月变化）
                        val cropBitmaps = staticData.cropBitmaps
                        if (cropBitmaps.isNotEmpty() && staticData.spiritFieldPlants.isNotEmpty()) {
                            // 使用预过滤的灵田建筑列表，避免遍历全量建筑
                            for (building in staticData.spiritFieldBuildings) {
                                val plant = staticData.spiritFieldPlants
                                    .firstOrNull { it.buildingInstanceId == building.instanceId }
                                    ?: continue
                                if (plant.seedId.isEmpty()) continue
                                val stage = getGrowthStage(
                                    plant, staticData.currentGameYear, staticData.currentGameMonth
                                ) ?: continue
                                val herbId = HerbDatabase.getHerbIdFromSeedId(plant.seedId)
                                    ?: continue
                                val displayHerbId = if (SpriteResRegistry.resolve("growing_$herbId") != null) herbId
                                    else fallbackToTier1(herbId) ?: continue

                                val cropBmpKey = when (stage) {
                                    GrowthStage.SEED -> "seed_$displayHerbId"
                                    GrowthStage.GROWING -> "growing_$displayHerbId"
                                    GrowthStage.MATURE -> "herb_$displayHerbId"
                                }
                                val cropBmp = cropBitmaps[cropBmpKey] ?: continue

                                val bx = building.gridX * ts
                                val by = building.gridY * ts
                                val bw = building.width * ts
                                val bh = building.height * ts
                                drawImage(
                                    cropBmp,
                                    dstOffset = IntOffset(bx, by),
                                    dstSize = IntSize(bw, bh),
                                    alpha = 0.9f
                                )
                            }
                        }

                        // 5. 灵植阁光环预览 — 按 GPU 等级分层渲染
                        // 来源: docs/huawei-performance-research.md §4.3
                        val showHerbGardenAura = (placement.isActive && placement.buildingName == herbGardenDisplayName) ||
                                (move.isActive && (move.building?.displayName ?: "") == herbGardenDisplayName)
                        if (showHerbGardenAura && config.gpuRenderConfig.auraEffectMode != "off") {
                            val hgGridX = if (placement.isActive) placement.gridX else move.gridX
                            val hgGridY = if (placement.isActive) placement.gridY else move.gridY
                            val hgW = if (placement.isActive) placement.size.width else move.size.width
                            val hgH = if (placement.isActive) placement.size.height else move.size.height
                            val hgCenterX = hgGridX + hgW / 2.0
                            val hgCenterY = hgGridY + hgH / 2.0
                            val auraRadius = GameConfig.HerbGarden.AURA_RADIUS_TILES
                            if (config.gpuRenderConfig.auraEffectMode == "full") {
                                // "full" 模式：逐格矩形填充 + 圆形轮廓
                                for (building in staticData.spiritFieldBuildings) {
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
                                            Offset(building.gridX * ts.toFloat(), building.gridY * ts.toFloat()),
                                            Size(building.width * ts.toFloat(), building.height * ts.toFloat())
                                        )
                                    }
                                }
                            }
                        }

                        // 6. 网格线（放置/移动模式时显示）
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
                                val activeGridX = if (placement.isActive) placement.gridX else move.gridX
                                val activeGridY = if (placement.isActive) placement.gridY else move.gridY
                                val activeW = if (placement.isActive) placement.size.width else move.size.width
                                val activeH = if (placement.isActive) placement.size.height else move.size.height
                                val bx1 = (activeGridX * ts).toFloat()
                                val by1 = (activeGridY * ts).toFloat()
                                val bx2 = ((activeGridX + activeW) * ts).toFloat()
                                val by2 = ((activeGridY + activeH) * ts).toFloat()
                                drawLine(gridColor, Offset(bx1, by1), Offset(bx2, by1), strokeWidth = 1f)
                                drawLine(gridColor, Offset(bx2, by1), Offset(bx2, by2), strokeWidth = 1f)
                                drawLine(gridColor, Offset(bx2, by2), Offset(bx1, by2), strokeWidth = 1f)
                                drawLine(gridColor, Offset(bx1, by2), Offset(bx1, by1), strokeWidth = 1f)
                            } else {
                                // 完整网格线
                                val firstCol = (visibleStartX / ts).toInt().coerceAtLeast(0)
                                val lastCol = (visibleEndX / ts).toInt().coerceAtMost(config.worldWidthCells)
                                val clippedStartY = visibleStartY.coerceAtLeast(0f)
                                val clippedEndY = visibleEndY.coerceAtMost(worldPixelHeight.toFloat())
                                for (col in firstCol..lastCol) {
                                    val x = (col * ts).toFloat()
                                    drawLine(gridColor, Offset(x, clippedStartY), Offset(x, clippedEndY), strokeWidth = 1f)
                                }

                                val firstRow = (visibleStartY / ts).toInt().coerceAtLeast(0)
                                val lastRow = (visibleEndY / ts).toInt().coerceAtMost(config.worldHeightCells)
                                val clippedStartX = visibleStartX.coerceAtLeast(0f)
                                val clippedEndX = visibleEndX.coerceAtMost(worldPixelWidth.toFloat())
                                for (row in firstRow..lastRow) {
                                    val y = (row * ts).toFloat()
                                    drawLine(gridColor, Offset(clippedStartX, y), Offset(clippedEndX, y), strokeWidth = 1f)
                                }
                            }
                        }

                        // 7. 放置预览
                        // 来源: docs/huawei-performance-research.md §4.3 — 单一矩形替代逐格矩形填充
                        if (placement.isActive) {
                            if (placement.buildingName.isNotEmpty()) {
                                val placeBmp = staticData.buildingBitmaps[placement.buildingName]
                                if (placeBmp != null) {
                                    drawImage(
                                        placeBmp,
                                        dstOffset = IntOffset(placement.worldX.roundToInt(), placement.worldY.roundToInt()),
                                        dstSize = IntSize(placement.size.width * ts, placement.size.height * ts)
                                    )
                                }
                            }
                            val previewColor = when (placement.validity) {
                                is GridSnapHelper.PlacementValidity.Valid -> Color(0x404CAF50)
                                is GridSnapHelper.PlacementValidity.OutOfBounds -> Color(0x40F44336)
                                is GridSnapHelper.PlacementValidity.Overlap -> Color(0x40FF5722)
                            }
                            drawRect(
                                previewColor,
                                Offset((placement.gridX * ts).toFloat(), (placement.gridY * ts).toFloat()),
                                Size((placement.size.width * ts).toFloat(), (placement.size.height * ts).toFloat())
                            )

                            // 金手指图标（建筑预览框外部右下角，非金手指模式时始终显示）
                            if (!goldFinger.isActive) {
                                val gfBmp = staticData.goldenFingerBmp
                                if (gfBmp != null) {
                                    val gfX = (placement.gridX + placement.size.width) * ts
                                    val gfY = (placement.gridY + placement.size.height) * ts
                                    drawImage(gfBmp, dstOffset = IntOffset(gfX, gfY), dstSize = IntSize(ts, ts))
                                }
                            }
                        }

                        // 8. 移动预览
                        // 来源: docs/huawei-performance-research.md §4.3 — 单一矩形替代逐格矩形填充
                        if (move.isActive) {
                            if ((move.building?.displayName ?: "").isNotEmpty()) {
                                val moveBmp = staticData.buildingBitmaps[move.building?.displayName ?: ""]
                                if (moveBmp != null) {
                                    drawImage(
                                        moveBmp,
                                        dstOffset = IntOffset(move.worldX.roundToInt(), move.worldY.roundToInt()),
                                        dstSize = IntSize(move.size.width * ts, move.size.height * ts),
                                        alpha = 0.7f
                                    )
                                }
                            }
                            val moveColor = when (move.validity) {
                                is GridSnapHelper.PlacementValidity.Valid -> Color(0x404CAF50)
                                is GridSnapHelper.PlacementValidity.OutOfBounds -> Color(0x40F44336)
                                is GridSnapHelper.PlacementValidity.Overlap -> Color(0x40FF5722)
                            }
                            drawRect(
                                moveColor,
                                Offset((move.gridX * ts).toFloat(), (move.gridY * ts).toFloat()),
                                Size((move.size.width * ts).toFloat(), (move.size.height * ts).toFloat())
                            )
                        }

                        // 9. 金手指框选区域渲染
                        if (goldFinger.isActive) {
                            val gMinX = minOf(goldFinger.startGridX, goldFinger.endGridX)
                                .coerceIn(0, config.worldWidthCells - 1)
                            val gMaxX = maxOf(goldFinger.startGridX, goldFinger.endGridX)
                                .coerceIn(0, config.worldWidthCells - 1)
                            val gMinY = minOf(goldFinger.startGridY, goldFinger.endGridY)
                                .coerceIn(0, config.worldHeightCells - 1)
                            val gMaxY = maxOf(goldFinger.startGridY, goldFinger.endGridY)
                                .coerceIn(0, config.worldHeightCells - 1)

                            val bW = goldFinger.buildingSize.width
                            val bH = goldFinger.buildingSize.height
                            // 将 canAfford 提取到循环外（每帧不变）
                            val canAfford = goldFinger.canAfford

                            // 按列逐行绘制，每格一个 drawRect
                            var gx = gMinX
                            while (gx + bW - 1 <= gMaxX && gx + bW <= config.worldWidthCells) {
                                var gy = gMinY
                                while (gy + bH - 1 <= gMaxY && gy + bH <= config.worldHeightCells) {
                                    val cellKey = GridSystem.packCell(gx, gy)
                                    val valid = goldFinger.cellValidity[cellKey] ?: false
                                    val color = if (valid && canAfford) Color(0x404CAF50) else Color(0x40F44336)
                                    drawRect(
                                        color = color,
                                        topLeft = Offset((gx * ts).toFloat(), (gy * ts).toFloat()),
                                        size = Size((bW * ts).toFloat(), (bH * ts).toFloat())
                                    )
                                    gy += bH
                                }
                                gx += bW
                            }

                            // 框选边框（四个方向厚度 2px 的条形）
                            val borderColor = if (canAfford) Color(0xFF4CAF50) else Color(0xFFF44336)
                            val bx1 = (gMinX * ts).toFloat(); val by1 = (gMinY * ts).toFloat()
                            val bx2 = ((gMaxX + 1) * ts).toFloat(); val by2 = ((gMaxY + 1) * ts).toFloat()
                            drawRect(color = borderColor, topLeft = Offset(bx1, by1), size = Size(bx2 - bx1, 2f))
                            drawRect(color = borderColor, topLeft = Offset(bx1, by2 - 2f), size = Size(bx2 - bx1, 2f))
                            drawRect(color = borderColor, topLeft = Offset(bx1, by1), size = Size(2f, by2 - by1))
                            drawRect(color = borderColor, topLeft = Offset(bx2 - 2f, by1), size = Size(2f, by2 - by1))

                            // 金手指图标（跟随拖拽，画在 endGrid 位置）
                            val gfBmp = staticData.goldenFingerBmp
                            if (gfBmp != null) {
                                drawImage(gfBmp, dstOffset = IntOffset(goldFinger.endGridX * ts, goldFinger.endGridY * ts), dstSize = IntSize(ts, ts))
                            }
                        }

                        // 10. 灵植阁光环范围圈
                        if (showHerbGardenAura) {
                            val centerX: Float
                            val centerY: Float
                            if (placement.isActive) {
                                centerX = placement.worldX + (placement.size.width * ts) / 2f
                                centerY = placement.worldY + (placement.size.height * ts) / 2f
                            } else {
                                centerX = move.worldX + (move.size.width * ts) / 2f
                                centerY = move.worldY + (move.size.height * ts) / 2f
                            }
                            drawCircle(
                                color = Color(0x404CAF50),
                                radius = (GameConfig.HerbGarden.AURA_RADIUS_TILES * ts).toFloat(),
                                center = Offset(centerX, centerY),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 在后台线程构建静态层离屏缓存位图。
 *
 * 将背景地图（fullMapBmp）和所有非移动中建筑合成为一张完整的世界尺寸位图，
 * 使 [SectMapCanvas] 的 [drawBehind] 每帧只需 1 次 drawImage 而非 N+1 次。
 *
 * @param config 渲染配置（含 tileSize、世界尺寸）
 * @param staticData 静态地图数据
 * @param skipInstanceId 移动中的建筑 instanceId（跳过其在静态层中的绘制）
 * @return 合成的 [ImageBitmap]，包含背景 + 所有非移动建筑
 */
private fun buildStaticLayerCache(
    config: SectMapRenderConfig,
    staticData: SectMapStaticData,
    skipInstanceId: String?
): ImageBitmap {
    val ts = config.tileSize
    val w = config.worldWidthCells * ts
    val h = config.worldHeightCells * ts

    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)

    // 1. 绘制背景地图（外扩 1px 防御 GPU 双线性边缘采样偏差）
    val srcBmp = staticData.fullMapBmp.asAndroidBitmap()
    val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(srcBmp, null, Rect(-1, -1, w + 1, h + 1), bgPaint)

    // 2. 非烘焙模式：绘制建筑
    if (!staticData.buildingsBaked) {
        for (building in staticData.placedBuildings) {
            if (skipInstanceId != null && building.instanceId == skipInstanceId) continue
            val bmp = staticData.buildingBitmaps[building.displayName]
            if (bmp != null) {
                canvas.drawBitmap(
                    bmp.asAndroidBitmap(),
                    null,
                    Rect(
                        building.gridX * ts,
                        building.gridY * ts,
                        (building.gridX + building.width) * ts,
                        (building.gridY + building.height) * ts
                    ),
                    bgPaint
                )
            } else {
                // 无精灵图兜底：半透明灰色矩形
                val fallbackPaint = Paint().apply {
                    color = 0xCCBDBDBD.toInt() // ~0.8 alpha
                    style = Paint.Style.FILL
                }
                canvas.drawRect(
                    (building.gridX * ts).toFloat(),
                    (building.gridY * ts).toFloat(),
                    ((building.gridX + building.width) * ts).toFloat(),
                    ((building.gridY + building.height) * ts).toFloat(),
                    fallbackPaint
                )
            }
        }
    }

    return bitmap.asImageBitmap()
}