package com.xianxia.sect.ui.game

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.game.sect.FastPreviewSnapshot

/**
 * MainGameScreen 触控辅助函数簇（2026-08-30 触控优化拆分）。
 * 独立文件承载——预览快通道写入与手势引擎回调解耦，保持 MainGameScreenGestures.kt
 * 函数数低于 detekt 文件阈值。
 *
 * 注：命中宽容判定（hit slop 外扩 / 最近建筑兜底）已移除，建筑命中统一改为
 * 精确格命中（见 MainGameScreenGestures 的 findBuildingAt）。
 */

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
