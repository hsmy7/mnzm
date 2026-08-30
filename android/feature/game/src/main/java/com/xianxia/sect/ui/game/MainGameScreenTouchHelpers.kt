package com.xianxia.sect.ui.game

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.core.touch.HitSlopPolicy
import com.xianxia.sect.core.touch.TouchEngineConfig
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.game.sect.FastPreviewSnapshot

/**
 * MainGameScreen 触控辅助函数簇（2026-08-30 触控优化拆分）。
 * 独立文件承载——命中宽容判定（hit slop / 最近兜底）与预览快通道写入
 * 均与手势引擎回调解耦，保持 MainGameScreenGestures.kt 函数数低于 detekt 文件阈值。
 */

/**
 * 外扩矩形命中（hit slop）：把被点格向四周外扩 [HitSlopPolicy.expandCells] 格后，
 * 返回绘制顺序最上层的建筑——小建筑（灵田等 1×1）按下点偏一格仍可命中。
 */
internal fun findBuildingExpanded(
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    hitSlopPolicy: HitSlopPolicy,
    screenX: Float,
    screenY: Float
): GridBuildingData? {
    val wx = viewportData.cameraState.screenToWorldX(screenX)
    val wy = viewportData.cameraState.screenToWorldY(screenY)
    return findBuildingExpandedAtWorld(mapData, renderData, viewportData, hitSlopPolicy, wx, wy)
}

/** 外扩矩形命中（世界坐标变体）：供已持有世界坐标的调用方使用（拆除模式格中心等）。 */
internal fun findBuildingExpandedAtWorld(
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    hitSlopPolicy: HitSlopPolicy,
    wx: Float,
    wy: Float
): GridBuildingData? {
    val gx = GridSnapHelper.worldToGrid(wx, mapData.tileSize)
    val gy = GridSnapHelper.worldToGrid(wy, mapData.tileSize)
    val expand = hitSlopPolicy.expandCells(mapData.tileSize, viewportData.cameraState.scale)
    if (expand <= 0) return renderData.buildingIndex.findBuildingAt(gx, gy)
    return renderData.buildingIndex.findBuildingAtRect(
        gx - expand, gy - expand, gx + expand, gy + expand
    )
}

/**
 * 双点宽容命中（tap 用）：按下/抬起点任一做外扩矩形命中（[findBuildingExpanded]），
 * 都未命中时以按下点为锚做最近建筑兜底（半径 [TouchEngineConfig.nearestFallbackMaxDp]）。
 */
// 拆分聚合:平铺参数搬移自原公共函数（与 MainGameScreenGestures 既有 @Suppress 惯例一致）
@Suppress("LongParameterList", "ReturnCount")
internal fun findBuildingTolerant(
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    hitSlopPolicy: HitSlopPolicy,
    config: TouchEngineConfig,
    downX: Float,
    downY: Float,
    upX: Float,
    upY: Float
): GridBuildingData? {
    findBuildingExpanded(mapData, renderData, viewportData, hitSlopPolicy, downX, downY)?.let { return it }
    findBuildingExpanded(mapData, renderData, viewportData, hitSlopPolicy, upX, upY)?.let { return it }
    // 最近建筑兜底（以下点为锚，容忍手指落点偏差）
    val wx = viewportData.cameraState.screenToWorldX(downX)
    val wy = viewportData.cameraState.screenToWorldY(downY)
    val maxDistPx = hitSlopPolicy.density * config.nearestFallbackMaxDp
    return renderData.buildingIndex.findNearestBuilding(wx, wy, mapData.tileSize, maxDistPx)
}

/**
 * 预览快通道写入（拖拽高频路径）：不经 Compose 重组/帧率门控，直接把最新预览
 * 写到渲染视图（renderTick 每帧合成进帧）——软件渲染路径下预览 60fps 响应。
 * 退出预览的清理由 MainGameScreen 在编辑模式退出时统一执行（避免门控窗口回跳）。
 */
internal fun pushFastPreview(
    state: MainGameScreenState,
    mapData: MainGameScreenMapData
) {
    val view = state.nativeSurfaceView ?: return
    val snapshot = computeFastPreview(state, mapData) ?: return
    view.fastPreviewChannel.set(snapshot)
}

/**
 * 预览快照计算：由放置/移动状态派生预览几何与 UV（与 SectMapViewport 的
 * computeMapPreview 同源公式——精灵水平居中 + 底部对齐，UV 同图集索引），
 * 保证快通道与 Compose 帧视觉一致。null = 当前无有效预览。
 */
@Suppress("ReturnCount")
private fun computeFastPreview(
    state: MainGameScreenState,
    mapData: MainGameScreenMapData
): FastPreviewSnapshot? {
    val isPlacing = state.isPlacingBuilding
    val mb = state.movingBuilding
    val name = if (isPlacing) state.placingBuildingName else mb?.displayName ?: ""
    val pSize = if (isPlacing) {
        state.placingBuildingSize
    } else {
        GridSnapHelper.BuildingSize(mb?.width ?: 0, mb?.height ?: 0)
    }
    val active = (isPlacing || mb != null) && name.isNotEmpty() && pSize.width > 0 && pSize.height > 0
    if (!active) return null
    val wx = if (isPlacing) state.placingWorldX else state.movingWorldX
    val wy = if (isPlacing) state.placingWorldY else state.movingWorldY
    val isRoad = name == GameConfig.Road.DISPLAY_NAME
    val uvs = resolvePreviewUvs(name, isRoad) ?: return null
    val spriteSize = resolvePreviewSpriteSize(mapData, name, isRoad, pSize)
    val offX = (pSize.width - spriteSize.width) * mapData.tileSize * 0.5f
    val offY = (pSize.height - spriteSize.height) * mapData.tileSize.toFloat()
    return FastPreviewSnapshot(
        active = true,
        x = wx + offX,
        y = wy + offY,
        w = spriteSize.width * mapData.tileSize.toFloat(),
        h = spriteSize.height * mapData.tileSize.toFloat(),
        u0 = uvs[0],
        v0 = uvs[1],
        u1 = uvs[2],
        v1 = uvs[3],
        alpha = 0.5f
    )
}

/**
 * 预览精灵尺寸解析：道路为 1×1 石板，建筑取注册精灵尺寸（缺省回退占地尺寸，
 * 与 SectMapViewport 的 computeMapPreview 一致）。
 */
private fun resolvePreviewSpriteSize(
    mapData: MainGameScreenMapData,
    name: String,
    isRoad: Boolean,
    pSize: GridSnapHelper.BuildingSize
): GridSnapHelper.BuildingSize {
    if (isRoad) return GridSnapHelper.BuildingSize(1, 1)
    return mapData.buildingSpriteSizes[name] ?: GridSnapHelper.BuildingSize(pSize.width, pSize.height)
}

/**
 * 预览精灵 UV 解析：道路走 ROAD_UV_MAP（1×1 石板），建筑走图集建筑段
 * （effectiveSpriteName 同源 key——与 SectMapViewport 查询一致）。
 */
private fun resolvePreviewUvs(name: String, isRoad: Boolean): FloatArray? {
    val (map, base) = if (isRoad) {
        SpriteAtlasDef.ROAD_UV_MAP to 0
    } else {
        val spriteKey = BuildingFeatureRegistry.findByDisplayName(name)?.effectiveSpriteName() ?: name
        val nameIdx = BUILDING_NAME_INDEX[spriteKey] ?: return null
        BUILDING_UV_MAP to nameIdx * 4
    }
    return floatArrayOf(map[base], map[base + 1], map[base + 2], map[base + 3])
}
