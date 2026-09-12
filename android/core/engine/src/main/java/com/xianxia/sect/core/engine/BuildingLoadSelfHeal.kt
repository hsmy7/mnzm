package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.FixedSectGateway

/**
 * 建筑读档自愈 — 全部纯函数，无状态无 IO。
 *
 * 统一在 [BootSequenceController] Step 3/3.5 编排（所有读档路径收敛点）：
 * - [normalizeOrphanBuildingSectIds]：孤儿宗门归属归一化（sectId 无对应宗门 → 归入本宗 ""）
 * - [purifyStaleActiveSectId]：activeSectId 残留净化（指向非玩家持有宗门 → 归回本宗 ""）
 * - [computeBuildingOverflowMigration]：溢出迁移纯计算（
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
 * 守卫阈值：建筑/矿场槽位引用的宗门 id 在 worldMapSects 中缺失数
 * 达到该值时，判定 roster 与建筑"严重失配"（世界重生/重型数据分叉异常态），
 * 跳过归一化并保留原 sectId（而非静默归 "" 主宗）。单个真孤儿（缺失 1 个）仍归一化，
 * 保证正常"宗门被摧毁→建筑归主宗恢复"语义不回归。
 */
private const val ORPHAN_BULK_DIVERGE_THRESHOLD = 2

/**
 * 旧档住所显示名（存量存档中的历史命名）。
 * 读档时改写为分级前缀新名：单人住所 → 初级单人住所、多人住所 → 初级多人住所。
 */
internal const val LEGACY_SINGLE_RESIDENCE_NAME = "单人住所"
internal const val LEGACY_MULTI_RESIDENCE_NAME = "多人住所"

/**
 * 住所显示名分级前缀迁移：旧档「单人住所/多人住所」→「初级单人住所/初级多人住所」。
 *
 * 显示名补全分级前缀后，旧存档中的 displayName 不再匹配注册表（建筑不可点/不可拆/不可升级），
 * 读档时须将旧名原地改写为新名；同步迁移引导累计建造计数 key（`buildingBuilt:{旧名}` → `{新名}`，
 * 计数数值不变）。幂等：已是新名的建筑不动，重复调用结果一致。
 *
 * @param buildings 全局建筑列表（跨宗门）
 * @param guideCounters 引导计数器
 * @return 改名后的建筑列表与计数器
 */
internal fun normalizeResidenceDisplayNames(
    buildings: List<GridBuildingData>,
    guideCounters: Map<String, Long>
): Pair<List<GridBuildingData>, Map<String, Long>> {
    val renamed = buildings.map { b ->
        when (b.displayName) {
            LEGACY_SINGLE_RESIDENCE_NAME -> b.copy(displayName = "初级单人住所")
            LEGACY_MULTI_RESIDENCE_NAME -> b.copy(displayName = "初级多人住所")
            else -> b
        }
    }
    val counterKeyMap = mapOf(
        GuideCounterKeys.buildingBuiltKey(LEGACY_SINGLE_RESIDENCE_NAME) to GuideCounterKeys.buildingBuiltKey("初级单人住所"),
        GuideCounterKeys.buildingBuiltKey(LEGACY_MULTI_RESIDENCE_NAME) to GuideCounterKeys.buildingBuiltKey("初级多人住所")
    )
    val renamedCounters = if (guideCounters.keys.any { it in counterKeyMap }) {
        guideCounters.mapKeys { (key, _) -> counterKeyMap[key] ?: key }
    } else {
        guideCounters
    }
    val renamedCount = buildings.zip(renamed).count { (before, after) -> before != after }
    if (renamedCount > 0) {
        DomainLog.w(TAG, "住所显示名迁移：$renamedCount 座旧名建筑改写为初级前缀")
    }
    return renamed to renamedCounters
}

/**
 * 推导"玩家持有（占领）宗门"权威 id 集合。
 *
 * 由 [SectDetail.isOwned]（持久化、不随世界重生被清）∪ [WorldSect.isPlayerOccupied]
 * （当前占领状态）合成——作为归一化 / activeSectId 净化 / 世界重生保留判定的事实来源，
 * 独立于可能分叉 / 重生的 worldMapSects。
 *
 * @param sectDetails 宗门详情（Map<宗门id, SectDetail>）
 * @param worldSects 当前世界宗门列表
 * @return 玩家持有（占领）宗门 id 集合
 */
internal fun derivePlayerOwnedSectIds(
    sectDetails: Map<String, SectDetail>,
    worldSects: List<WorldSect>
): Set<String> = (
    worldSects.filter { it.isPlayerOccupied }.map { it.id } +
        sectDetails.filterValues { it.isOwned }.keys
).toSet()

/**
 * 存量存档回填：把当前"正在被玩家占领"（[WorldSect.isPlayerOccupied]）的宗门在
 * [SectDetail.isOwned] 上补标为已持有——否则老档的占领状态只存在于 worldMapSects
 * （会被世界重生清除），未来重生死后占领进度仍会丢失。幂等：已是 isOwned 的项不动。
 *
 * @param sectDetails 宗门详情（Map<宗门id, SectDetail>）
 * @param worldSects 当前世界宗门列表
 * @return 回填后的宗门详情
 */
internal fun backfillPlayerOwnedSectDetails(
    sectDetails: Map<String, SectDetail>,
    worldSects: List<WorldSect>
): Map<String, SectDetail> {
    val occupiedIds = worldSects.filter { it.isPlayerOccupied }.map { it.id }
    if (occupiedIds.isEmpty()) return sectDetails
    return occupiedIds.fold(sectDetails) { acc, id ->
        if (acc[id]?.isOwned == true) acc
        else acc + (id to (acc[id] ?: SectDetail(sectId = id)).copy(isOwned = true))
    }
}

/**
 * 将"无对应宗门"的孤儿建筑归入本宗（""）。
 *
 * 仅处理 `sectId` 非空且不在 [worldSects] 中的建筑——`sectId=""`（本宗）与对应现存
 * 宗门的建筑不动。worldMapSects 为空时跳过（世界重生前的临时状态，防误伤占领宗门建筑）。
 * 同步修正 [SpiritMineSlot.sectId]（矿场槽位与建筑同源 stamp，孤儿矿场槽位一并归入本宗）。
 * 幂等：重复调用结果一致。
 *
 * @param buildings 全局建筑列表（跨宗门）
 * @param spiritMineSlots 灵矿场槽位列表
 * @param worldSects 当前世界宗门列表（含 isPlayerSect/isPlayerOccupied 标记）
 * @param playerOwnedSectIds 玩家持有（占领）宗门 id 集合——独立于 roster 的权威标记
 *   （sectDetails.isOwned ∪ worldMapSects.isPlayerOccupied），用于被占宗门缺失于 roster 时保留归属
 * @return 归一化后的建筑与槽位
 */
internal fun normalizeOrphanBuildingSectIds(
    buildings: List<GridBuildingData>,
    spiritMineSlots: List<SpiritMineSlot>,
    worldSects: List<WorldSect>,
    playerOwnedSectIds: Set<String>
): SectNormalizationResult {
    if (worldSects.isEmpty()) return SectNormalizationResult(buildings, spiritMineSlots)
    val existingIds = worldSects.mapTo(mutableSetOf()) { it.id }

    // playerOwnedSectIds 是独立于 roster 的"玩家持有宗门"权威——
    // 凡建筑 sectId 属于其中即保留，绝不静默归 ""（主宗），因为主宗 activeSectId="" 只显示
    // sectId=="" 的建筑，赋 "" 正是"占领宗门内建建筑跑到主宗地图显示"的直接成因。
    // 仅"真正孤儿"（既不在 worldMapSects、也不属玩家持有）才归并主宗（恢复语义）。
    // 辅助守卫：roster 与建筑 sectId 集合"严重失配"（≥2 个非玩家持有引用宗门缺失，
    // 世界重生/重型数据分叉异常态）时跳过归一化并保留原值+告警，避免异常态下大规模
    // 改写制造不可逆误归。
    val referencedSectIds = (buildings.map { it.sectId } + spiritMineSlots.map { it.sectId })
        .filter { it.isNotEmpty() }
        .toSet()
    val missingSectIds = referencedSectIds - existingIds
    val divergedOrphanIds = missingSectIds - playerOwnedSectIds
    val rosterDiverged = divergedOrphanIds.size >= ORPHAN_BULK_DIVERGE_THRESHOLD
    if (rosterDiverged) {
        DomainLog.w(
            TAG,
            "sectId 归一化跳过：roster 与建筑严重失配（缺失非持有宗门 $divergedOrphanIds，" +
                "引用宗门=${referencedSectIds.size}，现存=${existingIds.size}）——保留原 sectId"
        )
    }

    val normalized = if (rosterDiverged) buildings else buildings.map { b ->
        if (b.sectId.isNotEmpty() && b.sectId !in existingIds && b.sectId !in playerOwnedSectIds) {
            b.copy(sectId = "")
        } else {
            b
        }
    }
    val normalizedSlots = if (!rosterDiverged && normalized != buildings) {
        spiritMineSlots.map { s ->
            if (s.sectId.isNotEmpty() && s.sectId !in existingIds && s.sectId !in playerOwnedSectIds) {
                s.copy(sectId = "")
            } else {
                s
            }
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
 * 净化残留的 [activeSectId]。
 *
 * activeSectId 非空但不对应"现存且玩家持有（isPlayerSect || isPlayerOccupied）"的宗门时
 * 归回本宗 ""。worldMapSects 为空（世界损坏待重生）时任何残留 id 必无效，同样归 ""。
 * 否则玩家本宗全部建筑（sectId=""）会被 [activeSectId] 过滤整体排除（不可见/不可点/可叠建）。
 *
 * @param activeSectId 当前存档中的 activeSectId
 * @param worldSects 当前世界宗门列表
 * @param playerOwnedSectIds 玩家持有（占领）宗门 id 集合（见 [derivePlayerOwnedSectIds]）
 * @return 净化后的 activeSectId
 */
internal fun purifyStaleActiveSectId(
    activeSectId: String,
    worldSects: List<WorldSect>,
    playerOwnedSectIds: Set<String>
): String {
    if (activeSectId.isEmpty()) return activeSectId
    // 玩家持有（占领）宗门即使 roster 缺失（世界重生/重型数据分叉）也保留
    // activeSectId——否则玩家被"锁"回主宗视角，其宗门地图建筑全部不可见/不可点。
    val playerOwnedKeep = activeSectId in playerOwnedSectIds
    val sect = if (worldSects.isEmpty()) null else worldSects.find { it.id == activeSectId }
    val keep = playerOwnedKeep || (sect != null && (sect.isPlayerSect || sect.isPlayerOccupied))
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

/** 天枢殿显示名（旧档遗留天枢殿删除+补偿判定） */
internal const val TIANSHU_HALL_DISPLAY_NAME = "天枢殿"

/**
 * 识别旧档遗留天枢殿。
 *
 * 旧档遗留的天枢殿尺寸与当前配置不符。读档时直接删除旧档天枢殿并
 * 通过邮件补偿 1000 万灵石
 * （由 [BootSequenceController] 编排：先发邮件成功再删建筑）。
 *
 * **必须在 fixupBuildingSizes 之前判定**——fixup 会把尺寸统一修正为当前配置，
 * 先判定才能识别旧档遗留（尺寸不符的天枢殿）。
 *
 * @param buildings 全部建筑列表（fixup 修正前的原始数据）
 * @param gridSizeOf 建筑显示名 → 当前配置占地尺寸（宽, 高）
 * @return 旧档遗留天枢殿列表（尺寸与当前配置不符的天枢殿；天枢殿全局唯一，最多 1 座）
 */
internal fun filterLegacyTianshuHalls(
    buildings: List<GridBuildingData>,
    gridSizeOf: (String) -> Pair<Int, Int>
): List<GridBuildingData> = buildings.filter { b ->
    if (b.displayName != TIANSHU_HALL_DISPLAY_NAME) return@filter false
    val (w, h) = gridSizeOf(b.displayName)
    b.width != w || b.height != h
}

/** 检查建筑是否在地图内、不与其他建筑/固定结构重叠 */
private fun canPlaceAt(
    b: GridBuildingData,
    gridW: Int,
    gridH: Int,
    occupied: Set<Long>
): Boolean {
    // 零/负尺寸建筑无法占格，视为不可放置
    if (b.width <= 0 || b.height <= 0 || !isInsideWorld(b, gridW, gridH)) return false
    // 不与既有建筑重叠 + 不重叠固定结构（宗门入口门楼/阶梯）占地
    val blocked = FixedSectGateway.blockedCells
    return cellsAreFree(b, occupied) &&
        (b.gridX until b.gridX + b.width).all { cx ->
            (b.gridY until b.gridY + b.height).all { cy ->
                packCell(cx, cy) !in blocked
            }
        }
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
