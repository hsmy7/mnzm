package com.xianxia.sect.ui.game

/**
 * MainGameScreen 手势处理簇（计划 v2 阶段 7 批 7-4 / R-13 拆分方向：自 MainGameScreen.kt
 * 拆出，解决 FileLength 破限）。单一职责：跨平台手势引擎回调装配 + 点击/长按/拖拽/
 * 金手指/拆除/建造入口处理。
 */

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.touch.LongPressResult
import com.xianxia.sect.core.touch.TouchEngineCallbacks
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.game.main.GoldFingerSelection
import com.xianxia.sect.ui.game.main.clampGoldFingerSelection
import com.xianxia.sect.ui.game.main.recomputeGoldFingerState
import com.xianxia.sect.ui.game.main.translateGoldFingerSelection
import com.xianxia.sect.ui.game.sect.GoldFingerState


/** MainGameScreen 触控回调（MainGameScreen 拆分）：跨平台手势引擎回调 */
internal fun buildMainGameScreenTouchCallbacks(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    viewModel: GameViewModel
): TouchEngineCallbacks = object : TouchEngineCallbacks {
    override fun onPanCamera(dx: Float, dy: Float) {
        viewportData.cameraState.pan(dx, dy)
        viewportData.cancelCameraAnim()
        viewModel.onUserInteraction()
    }
    override fun onPinchZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        viewportData.cameraState.zoom(scaleFactor, focusX, focusY)
        viewportData.cancelCameraAnim()
        viewModel.onUserInteraction()
    }
    override fun onTap(screenX: Float, screenY: Float) {
        handleMainGameScreenTap(
            state = state, derived = derived, mapData = mapData,
            renderData = renderData, viewportData = viewportData,
            viewModel = viewModel, screenX = screenX, screenY = screenY
        )
    }
    override fun onLongPress(screenX: Float, screenY: Float): LongPressResult {
        return handleMainGameScreenLongPress(
            state = state, derived = derived, mapData = mapData,
            renderData = renderData, viewportData = viewportData,
            viewModel = viewModel, screenX = screenX, screenY = screenY
        )
    }
    override fun onBuildingDragUpdate(worldDx: Float, worldDy: Float) {
        handleMainGameScreenDragUpdate(
            state = state, derived = derived, mapData = mapData,
            renderData = renderData,
            worldDx = worldDx, worldDy = worldDy
        )
    }
    override fun onBuildingDragEnd() { /* 松手后保持最后位置，显示确认/取消按钮 */ }
    override fun onGoldFingerUpdate(screenX: Float, screenY: Float) {
        handleMainGameScreenGoldFingerUpdate(
            state = state, derived = derived, mapData = mapData,
            viewportData = viewportData,
            screenX = screenX, screenY = screenY
        )
    }
    override fun isGoldFingerActive(): Boolean = state.goldFingerState.isActive
    override fun getCameraScale(): Float = viewportData.cameraState.scale
    override fun findBuildingAt(screenX: Float, screenY: Float): Any? {
        return findMainGameScreenBuildingAt(
            state = state, mapData = mapData,
            renderData = renderData, viewportData = viewportData,
            screenX = screenX, screenY = screenY
        )
    }
    override fun isInEditMode(): Boolean = state.isPlacingBuilding || state.movingBuilding != null
    override fun onDragStart() { viewModel.setGameScene(GameEngineCore.GameScene.GAMEPLAY) }
    override fun onDragEnd() { /* 由 idle timeout 自动降帧 (30s → IDLE 10fps) */ }
    override fun onFlingStart() { viewModel.setGameScene(GameEngineCore.GameScene.MAP_SCROLL) }
    override fun onFlingEnd() { /* 由 idle timeout 自动降帧 (30s → IDLE 10fps) */ }
}

/** 点击处理（MainGameScreen 拆分）：拆除选中 / 建筑详情打开 */
// 拆分聚合:平铺参数搬移自原公共函数
@Suppress("LongParameterList")
private fun handleMainGameScreenTap(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    viewModel: GameViewModel,
    screenX: Float,
    screenY: Float
) {
    val wx = viewportData.cameraState.screenToWorldX(screenX)
    val wy = viewportData.cameraState.screenToWorldY(screenY)
    val gx = (wx / mapData.tileSize).toInt()
    val gy = (wy / mapData.tileSize).toInt()
    // 拆除模式：单点切换选中 / 区域模式范围选中，不弹详情
    if (state.isDemolishMode) {
        handleDemolishTap(
            state = state, derived = derived, renderData = renderData, viewModel = viewModel, gx = gx, gy = gy
        )
        return
    }
    // 石板道路：1×1，点击地图格即放置（替代拖拽+确认按钮）——直接调 placeRoad 由它自判可否放置
    if (state.isPlacingBuilding && state.placingBuildingName == GameConfig.Road.DISPLAY_NAME) {
        viewModel.placeRoad(gx, gy)
        state.isPlacingBuilding = false
        state.placingBuildingName = ""
        return
    }
    val clicked = renderData.buildingIndex.findBuildingAt(gx, gy)
    // 点击空地 → 清除选中高亮（任意模式）
    if (clicked == null) {
        state.selectedBuildingGrid = null
    }
    if (clicked != null && !state.isPlacingBuilding && state.movingBuilding == null) {
        // 点击建筑 → 记录选中格（渲染端金色高亮描边），并打开详情
        state.selectedBuildingGrid = gx to gy
        val def = BuildingFeatureRegistry.findByDisplayName(clicked.displayName)
        when (def?.key) {
            "spirit_mine" -> viewModel.navigateToDialog(DialogType.SpiritMine(clicked.instanceId))
            "alchemy" -> viewModel.navigateToDialog(DialogType.Alchemy(clicked.instanceId))
            "forge" -> viewModel.navigateToDialog(DialogType.Forge(clicked.instanceId))
            "single_residence", "single_residence_upgraded", "multi_residence", "multi_residence_upgraded" -> {
                viewModel.navigateToDialog(DialogType.Residence(clicked.instanceId))
            }
            else -> handleGenericBuildingTap(clicked, def, derived, mapData)
        }
    }
}

/**
 * 无专用 DialogType 的建筑点击兜底（MainGameScreen 拆分提取）：
 * 显示名回调分发 + 未注册诊断（R1/B1）。
 */
private fun handleGenericBuildingTap(
    clicked: GridBuildingData,
    def: com.xianxia.sect.core.engine.domain.building.BuildingFeature?,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData
) {
    // R1 诊断（B1）：displayName 未注册 / 无回调 → 点击被静默吞掉。
    // 渲染端会用索引 0 兜底画出该建筑，点击却无任何分支处理——唯一"可见但点不中"确定性路径。
    if (def == null) {
        DomainLog.w(
            BUILDING_TAP_TAG,
            "点击建筑 displayName 未注册: name=${clicked.displayName} " +
                "sectId=${clicked.sectId} instanceId=${clicked.instanceId} " +
                "grid=(${clicked.gridX},${clicked.gridY}) " +
                "activeSectId=${derived.gameData.activeSectId} " +
                "sectBuildings=${derived.activeSectBuildings.size}"
        )
    }
    val b = mapData.buildingList.find { it.first == clicked.displayName }
    if (b != null) {
        b.second?.invoke(clicked)
    } else {
        DomainLog.w(
            BUILDING_TAP_TAG,
            "点击建筑无回调处理: name=${clicked.displayName} " +
                "sectId=${clicked.sectId} instanceId=${clicked.instanceId} " +
                "grid=(${clicked.gridX},${clicked.gridY}) " +
                "activeSectId=${derived.gameData.activeSectId} " +
                "sectBuildings=${derived.activeSectBuildings.size}"
        )
    }
}

/** 长按处理（MainGameScreen 拆分）：金手指入口检测 / 建筑移动模式 */
// 拆分聚合:平铺参数搬移自原公共函数
// 拆分搬移:多出口与原函数一致
@Suppress("LongParameterList", "ReturnCount")
private fun handleMainGameScreenLongPress(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    viewModel: GameViewModel,
    screenX: Float,
    screenY: Float
): LongPressResult {
    val wx = viewportData.cameraState.screenToWorldX(screenX)
    val wy = viewportData.cameraState.screenToWorldY(screenY)
    val gx = (wx / mapData.tileSize).toInt()
    val gy = (wy / mapData.tileSize).toInt()

    // 放置模式 → 金手指图标检测：唯一图标随激活状态移动
    // （未激活在预览角作入口；激活后跟随 endGrid，按住它即可重入框选）
    if (state.isPlacingBuilding) {
        return handleGoldFingerLongPress(
            state = state, derived = derived, mapData = mapData,
            viewModel = viewModel, wx = wx, wy = wy
        )
    }

    // 非放置模式 → 建筑长按 → 移动模式
    // 注意：movingBuilding 可能非 null（上次拖拽后确认/取消按钮还在显示）
    // 如果按钮显示期间再次长按同一建筑，应允许继续拖拽
    // 拆除模式禁止长按移动
    if (!state.isPlacingBuilding && !state.isDemolishMode) {
        val touched = renderData.buildingIndex.findBuildingAt(gx, gy)
            ?: (if (state.movingBuilding != null) state.movingBuilding else null)
        if (touched != null) {
            val isResumeDrag = state.movingBuilding?.instanceId == touched.instanceId
            if (!isResumeDrag) {
                // 新建筑拖拽 → 从该建筑的原始网格坐标开始
                state.movingWorldX = (touched.gridX * mapData.tileSize).toFloat()
                state.movingWorldY = (touched.gridY * mapData.tileSize).toFloat()
                state.movingSnappedGridX = touched.gridX
                state.movingSnappedGridY = touched.gridY
                state.movingValid = GridSnapHelper.PlacementValidity.Valid
            }
            state.movingBuilding = touched
            return LongPressResult.BuildingDrag
        }
    }
    return LongPressResult.NotHandled
}

/** 金手指图标长按（MainGameScreen 拆分）：首次激活锚定预览位置并重算状态 */
// 拆分搬移:嵌套/条件结构与原函数一致
@Suppress("ComplexCondition")
private fun handleGoldFingerLongPress(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    viewModel: GameViewModel,
    wx: Float,
    wy: Float
): LongPressResult {
    val gfWx = (if (state.goldFingerState.isActive) state.goldFingerState.endGridX
        else state.placingSnappedGridX + state.placingBuildingSize.width) * mapData.tileSize
    val gfWy = (if (state.goldFingerState.isActive) state.goldFingerState.endGridY
        else state.placingSnappedGridY + state.placingBuildingSize.height) * mapData.tileSize
    if (wx >= gfWx && wx < gfWx + mapData.tileSize &&
        wy >= gfWy && wy < gfWy + mapData.tileSize
    ) {
        if (!state.goldFingerState.isActive) {
            // 首次激活：起点锚定预览位置，钳制到可建区后重算状态
            val sel = clampGoldFingerSelection(
                GoldFingerSelection(
                    state.placingSnappedGridX, state.placingSnappedGridY,
                    state.placingSnappedGridX, state.placingSnappedGridY
                ),
                mapData.worldWidthCells, mapData.worldHeightCells,
                GameConfig.SectMap.BORDER_TREE_RING
            )
            state.goldFingerState = recomputeGoldFingerState(
                f = GoldFingerState(
                    isActive = true,
                    buildingName = state.placingBuildingName,
                    buildingSize = state.placingBuildingSize,
                    buildingCost = viewModel.getBuildingCost(state.placingBuildingName)
                ),
                sel = sel,
                existingBuildings = derived.effectivePlacedBuildings,
                worldWidthCells = mapData.worldWidthCells,
                worldHeightCells = mapData.worldHeightCells,
                buildableBorder = GameConfig.SectMap.BORDER_TREE_RING,
                spiritStones = derived.gameData?.spiritStones ?: 0L
            )
        }
        // 已激活：不改动选区（等待 MOVE 重新框定，可扩大可缩小），直接重入框选
        return LongPressResult.GoldFingerDrag
    }
    return LongPressResult.NotHandled
}

/** 拖拽更新（MainGameScreen 拆分）：放置预览 / 移动建筑位置实时更新 */
private fun handleMainGameScreenDragUpdate(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    worldDx: Float,
    worldDy: Float
) {
    if (state.isPlacingBuilding) {
        // 放置模式：更新预览位置
        state.placingWorldX += worldDx
        state.placingWorldY += worldDy
        val oldSnappedX = state.placingSnappedGridX
        val oldSnappedY = state.placingSnappedGridY
        state.placingSnappedGridX = GridSnapHelper.worldToGrid(state.placingWorldX, mapData.tileSize)
        state.placingSnappedGridY = GridSnapHelper.worldToGrid(state.placingWorldY, mapData.tileSize)
        val dgx = state.placingSnappedGridX - oldSnappedX
        val dgy = state.placingSnappedGridY - oldSnappedY
        // 金手指激活时选区随预览同增量平移（Bug 2 修复），钳制到可建区后重算
        if (state.goldFingerState.isActive && (dgx != 0 || dgy != 0)) {
            val f = state.goldFingerState
            val sel = translateGoldFingerSelection(
                GoldFingerSelection(f.startGridX, f.startGridY, f.endGridX, f.endGridY),
                dgx, dgy, mapData.worldWidthCells, mapData.worldHeightCells,
                GameConfig.SectMap.BORDER_TREE_RING
            )
            state.goldFingerState = recomputeGoldFingerState(
                f = f, sel = sel,
                existingBuildings = derived.effectivePlacedBuildings,
                worldWidthCells = mapData.worldWidthCells,
                worldHeightCells = mapData.worldHeightCells,
                buildableBorder = GameConfig.SectMap.BORDER_TREE_RING,
                spiritStones = derived.gameData?.spiritStones ?: 0L
            )
        }
        state.placementValidity = renderData.gridSystem.validatePlacement(
            state.placingSnappedGridX, state.placingSnappedGridY,
            state.placingBuildingSize.width, state.placingBuildingSize.height
        )
    } else {
        // 移动模式：更新被拖建筑位置
        state.movingWorldX += worldDx
        state.movingWorldY += worldDy
        state.movingSnappedGridX = GridSnapHelper.worldToGrid(state.movingWorldX, mapData.tileSize)
        state.movingSnappedGridY = GridSnapHelper.worldToGrid(state.movingWorldY, mapData.tileSize)
        state.movingValid = renderData.gridSystem.validatePlacement(
            state.movingSnappedGridX, state.movingSnappedGridY,
            derived.movingBuildingSize.width, derived.movingBuildingSize.height
        )
    }
}

/** 金手指拖拽更新（MainGameScreen 拆分）：终点格吸附 + 钳制可建区 + 重算状态 */
private fun handleMainGameScreenGoldFingerUpdate(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    viewportData: MainGameScreenViewportData,
    screenX: Float,
    screenY: Float
) {
    if (!state.goldFingerState.isActive) return
    val newWx = viewportData.cameraState.screenToWorldX(screenX)
    val newWy = viewportData.cameraState.screenToWorldY(screenY)
    // 终点格用 GridSnapHelper.worldToGrid（roundToInt，与预览吸附一致），
    // 并整体钳制到可建区，保证视觉框 == 实际建造区
    val newGridX = GridSnapHelper.worldToGrid(newWx, mapData.tileSize)
    val newGridY = GridSnapHelper.worldToGrid(newWy, mapData.tileSize)
    val f = state.goldFingerState
    val sel = clampGoldFingerSelection(
        GoldFingerSelection(f.startGridX, f.startGridY, newGridX, newGridY),
        mapData.worldWidthCells, mapData.worldHeightCells,
        GameConfig.SectMap.BORDER_TREE_RING
    )
    state.goldFingerState = recomputeGoldFingerState(
        f = f, sel = sel,
        existingBuildings = derived.effectivePlacedBuildings,
        worldWidthCells = mapData.worldWidthCells,
        worldHeightCells = mapData.worldHeightCells,
        buildableBorder = GameConfig.SectMap.BORDER_TREE_RING,
        spiritStones = derived.gameData?.spiritStones ?: 0L
    )
}

/** 触控命中建筑检测（MainGameScreen 拆分）：拆除/放置/移动模式分支 */
// 拆分搬移:多出口与原函数一致
// 拆分搬移:嵌套/条件结构与原函数一致
@Suppress("ReturnCount", "ComplexCondition")
private fun findMainGameScreenBuildingAt(
    state: MainGameScreenState,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    screenX: Float,
    screenY: Float
): Any? {
    // 拆除模式：不返回建筑 → touch 引擎不会启动 BuildingDrag 定时器，
    // 短按/滑动正常走 onTap / 平移相机
    if (state.isDemolishMode) return null
    val wx = viewportData.cameraState.screenToWorldX(screenX)
    val wy = viewportData.cameraState.screenToWorldY(screenY)

    // 放置模式：用世界坐标检测触摸是否在预览区域内（比网格检测更精准）
    if (state.isPlacingBuilding) {
        val previewLeft = state.placingWorldX
        val previewTop = state.placingWorldY
        val previewRight = previewLeft + state.placingBuildingSize.width * mapData.tileSize
        val previewBottom = previewTop + state.placingBuildingSize.height * mapData.tileSize
        if (wx >= previewLeft && wx < previewRight &&
            wy >= previewTop && wy < previewBottom
        ) {
            return Any()
        }
        return null
    }

    val gx = (wx / mapData.tileSize).toInt()
    val gy = (wy / mapData.tileSize).toInt()

    // buildingIndex 不包含 movingBuilding，手动检查
    val mb = state.movingBuilding
    if (mb != null) {
        // 用当前拖拽位置（movingSnappedGridX/Y）而非原始位置检查
        if (gx >= state.movingSnappedGridX && gx < state.movingSnappedGridX + mb.width &&
            gy >= state.movingSnappedGridY && gy < state.movingSnappedGridY + mb.height
        ) {
            return mb
        }
    }
    return renderData.buildingIndex.findBuildingAt(gx, gy)
}

/** 拆除模式点击处理（MainGameScreen 拆分）：区域模式范围选中 / 单点切换选中 / 删路 */
private fun handleDemolishTap(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    renderData: MainGameScreenRenderData,
    viewModel: GameViewModel,
    gx: Int,
    gy: Int
) {
    if (state.isAreaSelectMode) {
        // 区域模式：以点击格为中心做正方形范围选中（并集累积 + Set 幂等——
        // 新区域内已选中的建筑保持选中，重复框选不取消；点击任意格都触发，
        // 不要求格上有建筑——越界格纯几何计算天然安全）。
        state.demolishSelectedIds = state.demolishSelectedIds + buildingsInSquare(
            buildings = derived.effectivePlacedBuildings,
            centerX = gx,
            centerY = gy,
            diameter = state.areaDiameter
        )
    } else {
        handleDemolishSingleTap(state, derived, renderData, viewModel, gx, gy)
    }
}

/**
 * 单点拆除模式（MainGameScreen 拆分提取）：点击建筑切换选中状态；
 * 无建筑但为道路格则直接删路。
 */
private fun handleDemolishSingleTap(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    renderData: MainGameScreenRenderData,
    viewModel: GameViewModel,
    gx: Int,
    gy: Int
) {
    val b = renderData.buildingIndex.findBuildingAt(gx, gy)
    if (b == null) {
        // 石板道路：拆除模式下点道路格立即删除（自动重算邻居拼接）
        val hasRoad = derived.gameData?.roads?.any { it.gridX == gx && it.gridY == gy } == true
        if (hasRoad) viewModel.removeRoad(gx, gy)
        return
    }
    // 未注册显示名不进入拆除选中（与原嵌套守卫语义一致）
    if (BuildingFeatureRegistry.findByDisplayName(b.displayName) == null) return
    state.demolishSelectedIds = if (b.instanceId in state.demolishSelectedIds)
        state.demolishSelectedIds - b.instanceId
    else state.demolishSelectedIds + b.instanceId
}

/** 建造卡片点击（MainGameScreen 拆分）：进入放置模式（拆除模式下忽略） */
internal fun onSelectBuildingFromBar(
    state: MainGameScreenState,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    name: String
) {
    // 拆除模式下点击建造卡片不进入放置模式
    if (state.isDemolishMode) return
    val size = if (name == GameConfig.Road.DISPLAY_NAME) {
        GridSnapHelper.BuildingSize(1, 1)  // 石板道路为单格
    } else {
        mapData.buildingSizes[name] ?: GridSnapHelper.BuildingSize(2, 3)
    }
    state.isPlacingBuilding = true
    state.placingBuildingName = name
    state.placingBuildingSize = size
    state.placingWorldX = viewportData.cameraState.screenToWorldX(state.screenWidthPx / 2f) -
        size.width * mapData.tileSize / 2f
    state.placingWorldY = viewportData.cameraState.screenToWorldY(state.screenHeightPx / 2f) -
        size.height * mapData.tileSize / 2f
    state.placingSnappedGridX = GridSnapHelper.worldToGrid(state.placingWorldX, mapData.tileSize)
    state.placingSnappedGridY = GridSnapHelper.worldToGrid(state.placingWorldY, mapData.tileSize)
    state.placementValidity = renderData.gridSystem.validatePlacement(
        state.placingSnappedGridX, state.placingSnappedGridY,
        size.width, size.height
    )
}
