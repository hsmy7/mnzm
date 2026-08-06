package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.util.DomainLog

/**
 * 建筑读档自愈（D-11~D-14 批次，2026-08-06）— 全部纯函数，无状态无 IO。
 *
 * 统一在 [BootSequenceController] Step 3/3.5 编排（所有读档路径收敛点）：
 * - [normalizeOrphanBuildingSectIds]：D-13，孤儿宗门归属归一化（sectId 无对应宗门 → 归入本宗 ""）
 * - [purifyStaleActiveSectId]：D-11，activeSectId 残留净化（指向非玩家持有宗门 → 归回本宗 ""）
 * - [computeBuildingOverflowMigration]：溢出迁移纯计算（自 SaveLoadLoadDelegate 迁入，
 *   须在 fixup/归一化之后执行——迁移按 sectId 分组 + 用最终尺寸判定）
 */
internal data class SectNormalizationResult(
    val buildings: List<GridBuildingData>,
    val spiritMineSlots: List<SpiritMineSlot>
)

/** 溢出迁移结果（public：跨模块供 feature/game 薄包装与测试使用）。 */
data class MigrationResult(
    val kept: List<GridBuildingData>,
    val demolished: List<GridBuildingData>,
    val totalRefund: Long,
    val freedDiscipleIds: Set<String>
)

private const val TAG = "BuildingSelfHeal"

/**
 * D-13：将"无对应宗门"的孤儿建筑归入本宗（""）。
 *
 * 仅处理 `sectId` 非空且不在 [worldSects] 中的建筑——`sectId=""`（本宗）与对应现存
 * 宗门的建筑不动。worldMapSects 为空时跳过（世界重生前的临时状态，防误伤占领宗门建筑）。
 * 同步修正 [SpiritMineSlot.sectId]（矿场槽位与建筑同源 stamp，孤儿矿场槽位一并归入本宗）。
 * 幂等：重复调用结果一致。
 *
 * @param buildings 全局建筑列表（跨宗门）
 * @param spiritMineSlots 灵矿场槽位列表
 * @param worldSects 当前世界宗门列表（含 isPlayerSect/isPlayerOccupied 标记）
 * @return 归一化后的建筑与槽位
 */
internal fun normalizeOrphanBuildingSectIds(
    buildings: List<GridBuildingData>,
    spiritMineSlots: List<SpiritMineSlot>,
    worldSects: List<WorldSect>
): SectNormalizationResult {
    if (worldSects.isEmpty()) return SectNormalizationResult(buildings, spiritMineSlots)
    val existingIds = worldSects.mapTo(mutableSetOf()) { it.id }
    val normalized = buildings.map { b ->
        if (b.sectId.isNotEmpty() && b.sectId !in existingIds) b.copy(sectId = "") else b
    }
    val normalizedSlots = if (normalized != buildings) {
        spiritMineSlots.map { s ->
            if (s.sectId.isNotEmpty() && s.sectId !in existingIds) s.copy(sectId = "") else s
        }
    } else {
        spiritMineSlots
    }
    val count = buildings.zip(normalized).count { (before, after) -> before != after }
    if (count > 0) {
        DomainLog.w(TAG, "sectId 归一化：$count 座孤儿建筑归入本宗（含矿场槽位同步）")
    }
    return SectNormalizationResult(normalized, normalizedSlots)
}

/**
 * D-11：净化残留的 [activeSectId]。
 *
 * activeSectId 非空但不对应"现存且玩家持有（isPlayerSect || isPlayerOccupied）"的宗门时
 * 归回本宗 ""。worldMapSects 为空（世界损坏待重生）时任何残留 id 必无效，同样归 ""。
 * 否则玩家本宗全部建筑（sectId=""）会被 [activeSectId] 过滤整体排除（不可见/不可点/可叠建）。
 *
 * @param activeSectId 当前存档中的 activeSectId
 * @param worldSects 当前世界宗门列表
 * @return 净化后的 activeSectId
 */
internal fun purifyStaleActiveSectId(activeSectId: String, worldSects: List<WorldSect>): String {
    if (activeSectId.isEmpty()) return activeSectId
    val sect = if (worldSects.isEmpty()) null else worldSects.find { it.id == activeSectId }
    val keep = sect != null && (sect.isPlayerSect || sect.isPlayerOccupied)
    if (!keep) {
        DomainLog.w(TAG, "activeSectId 净化：\"$activeSectId\" 非玩家持有宗门，归回本宗")
    }
    return if (keep) activeSectId else ""
}

/**
 * 计算旧档建筑溢出迁移：将放不下（越界/重叠）的建筑拆除，全额返还造价，弟子恢复空闲。
 *
 * 须按 `sectId` 分组调用（不同宗门的建筑使用独立网格，坐标互不干扰）；
 * 必须在 [BuildingConfigService.fixupBuildingSizes] 之后执行（用最终尺寸判定）。
 * 灵田（占地不变）优先保留；造价高的优先保留。
 *
 * @param buildings 同一宗门作用域内的建筑列表
 * @param gameData 完整游戏数据（用于收集被拆建筑中已分配弟子的 ID）
 * @param buildingConfigService 建筑配置服务（造价/占地查询）
 * @return 迁移结果（保留/拆除/退款/解放弟子）
 */
fun computeBuildingOverflowMigration(
    buildings: List<GridBuildingData>,
    gameData: GameData,
    buildingConfigService: BuildingConfigService
): MigrationResult {
    val gridW = GameConfig.SectMap.WORLD_WIDTH_CELLS
    val gridH = GameConfig.SectMap.WORLD_HEIGHT_CELLS

    val sorted = buildings.sortedByDescending { b ->
        if (b.displayName == SPIRIT_FIELD_NAME) Long.MAX_VALUE
        else buildingConfigService.getBuildingConfigByDisplayName(b.displayName)?.cost ?: 1000L
    }

    val occupied = mutableSetOf<Long>()
    val kept = mutableListOf<GridBuildingData>()
    val demolished = mutableListOf<GridBuildingData>()
    var totalRefund = 0L
    val freedDiscipleIds = mutableSetOf<String>()

    for (b in sorted) {
        // 空名称建筑无配置可查，退路造价为 0（防经济不一致）
        val cost = if (b.displayName.isBlank()) 0L
        else buildingConfigService.getBuildingConfigByDisplayName(
            b.displayName)?.cost ?: 1000L

        if (!canPlaceAt(b, gridW, gridH, occupied)) {
            demolished.add(b)
            // 饱和加法防止溢出导致灵石变为负数
            if (totalRefund > Long.MAX_VALUE - cost) totalRefund = Long.MAX_VALUE
            else totalRefund += cost
            collectFreedDiscipleIds(b, freedDiscipleIds, gameData)
            continue
        }
        markOccupied(b, occupied)
        kept.add(b)
    }

    return MigrationResult(
        kept = kept,
        demolished = demolished,
        totalRefund = totalRefund,
        freedDiscipleIds = freedDiscipleIds
    )
}

/** 灵田显示名（占地尺寸不变，迁移中优先保留） */
private const val SPIRIT_FIELD_NAME = "灵田"

/** 检查建筑是否在地图内且不与其他建筑重叠（迁移自 SaveLoadLoadDelegate，条件拆分过 detekt） */
private fun canPlaceAt(
    b: GridBuildingData,
    gridW: Int,
    gridH: Int,
    occupied: Set<Long>
): Boolean {
    // 零/负尺寸建筑无法占格，视为不可放置
    if (b.width <= 0 || b.height <= 0 || !isInsideWorld(b, gridW, gridH)) return false
    return cellsAreFree(b, occupied)
}

/** 建筑是否完整位于世界地图内 */
private fun isInsideWorld(b: GridBuildingData, gridW: Int, gridH: Int): Boolean =
    b.gridX >= 0 && b.gridY >= 0 &&
        b.gridX + b.width <= gridW &&
        b.gridY + b.height <= gridH

/** 建筑占地格子是否全部空闲 */
private fun cellsAreFree(b: GridBuildingData, occupied: Set<Long>): Boolean {
    for (cx in b.gridX until b.gridX + b.width) {
        for (cy in b.gridY until b.gridY + b.height) {
            if (packCell(cx, cy) in occupied) return false
        }
    }
    return true
}

/** 标记建筑占据的格子 */
private fun markOccupied(b: GridBuildingData, occupied: MutableSet<Long>) {
    for (cx in b.gridX until b.gridX + b.width) {
        for (cy in b.gridY until b.gridY + b.height) {
            occupied.add(packCell(cx, cy))
        }
    }
}

/** 将 (x, y) 格子编码为 Long（与 GridSystem.packCell 一致） */
private fun packCell(x: Int, y: Int): Long =
    (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFF)

/** 收集被拆除建筑中已分配弟子的 ID（通过 BuildingFeatureRegistry + SlotGroup） */
private fun collectFreedDiscipleIds(
    building: GridBuildingData,
    ids: MutableSet<String>,
    gameData: GameData
) {
    val feature = BuildingFeatureRegistry.findByDisplayName(building.displayName) ?: return
    ids.addAll(feature.slotGroups.flatMap { it.collectDiscipleIds(gameData, building.instanceId, feature) })
}
