package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.long
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.str
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 建筑域 native 事务转发臂（batch-06 写者下沉；roadNativeTx/batch-07 同构）。
 *
 * AUTHORITATIVE 门控下把建筑四操作的 C++ 事务（building_tx.h——校验链/
 * placedBuildings 变更/灵石/引导计数）经 nativeExecute 转发；成功内含
 * applyDirtyFromNative 脏段回读（gameData 镜像域，无 Room 回写）；失败信封/
 * 降级返回 null/false（调用方回退 Kotlin 原路径重执行校验链——双实现并行
 * 契约，用户可见文案由 Kotlin 臂产出）。
 *
 * 偏差登记（§2.36 同口径）：C++ GridBuildingData 无 sectId 字段（models.h
 * 禁改），限建/占位/升级 canFit 的同宗过滤由本类组装 sectScopedIds 随请求
 * 传入；占位几何/限建标志/造价/counterKey 均为参数——单一事实源留 Kotlin。
 *
 * 拆除事务的残差派生清理（槽位过滤/弟子释放/监牢·任务阁特例/仓库 repo）
 * 亦由本类承担——BuildingFeatureRegistry 槽组语义留 Kotlin，范围以 C++ 回执
 * removedIds 为准（幽灵实例两端同跳）。
 */
internal class BuildingNativeTx(
    private val stateStore: GameStateStore,
    private val gameEngineCore: GameEngineCore,
    private val productionCoordinator: ProductionCoordinator,
    private val assignmentGate: com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate,
    private val discipleStatusService: DiscipleStatusService
) {

    /** 建筑放置事务尝试（调用方只承担槽位派生残差）。@return native 已写 true */
    fun tryPlace(building: GridBuildingData, feature: BuildingFeature, cost: Long): Boolean {
        val data = tx(ActionIds.BUILDING_PLACE) {
            put("buildingId", building.buildingId)
            put("displayName", building.displayName)
            put("gridX", building.gridX)
            put("gridY", building.gridY)
            put("width", building.width)
            put("height", building.height)
            put("instanceId", building.instanceId)
            put("cost", cost)
            put("requiredSectLevel", feature.requiredSectLevel)
            put("unlimitedBuild", feature.unlimitedBuild)
            put("globallyUnique", feature.isGloballyUnique)
            put("counterKey", GuideCounterKeys.buildingBuiltKey(building.displayName))
            putPlaceGeom()
            put("sectScopedIds", sectScopedIds(building.sectId))
        } ?: return false
        return data.str("placed") == "true"
    }

    /** 建筑迁移事务（存在性/环界门楼校验 + 坐标改写）。 */
    fun move(instanceId: String, newGridX: Int, newGridY: Int): Boolean {
        tx(ActionIds.BUILDING_MOVE) {
            put("instanceId", instanceId)
            put("newGridX", newGridX)
            put("newGridY", newGridY)
            putPlaceGeom()
        } ?: return false
        return true
    }

    /**
     * 单座升级事务（等级/差价/canFit 校验 + 原地变换 + 差价直扣）。
     * @param def/target/cost 调用方按注册表解析（与回退臂同源）
     */
    fun upgradeSingle(
        instanceId: String,
        buildingSectId: String,
        target: BuildingFeature,
        cost: Long
    ): UpgradeResult? {
        tx(ActionIds.BUILDING_UPGRADE) {
            put("instanceId", instanceId)
            put("targetKey", target.key)
            put("targetDisplayName", target.displayName)
            put("targetWidth", target.gridWidth)
            put("targetHeight", target.gridHeight)
            put("cost", cost)
            putPlaceGeom()
            put("sectScopedIds", sectScopedIds(buildingSectId))
        } ?: return null
        return UpgradeResult.Success(1)
    }

    /** 批量升级事务（整批等级/候选稳定序/可负担上限/增量 canFit）。 */
    fun upgradeBatch(
        sectId: String,
        sourceKey: String,
        target: BuildingFeature,
        maxCount: Int,
        cost: Long
    ): UpgradeResult? {
        val data = tx(ActionIds.BUILDING_UPGRADE_BATCH) {
            put("sourceKey", sourceKey)
            put("targetKey", target.key)
            put("targetDisplayName", target.displayName)
            put("targetWidth", target.gridWidth)
            put("targetHeight", target.gridHeight)
            put("maxCount", maxCount)
            put("cost", cost)
            putPlaceGeom()
            put("sectScopedIds", sectScopedIds(sectId))
        } ?: return null
        val upgraded = data.long("upgradedCount") ?: 0
        val spaceBlocked = data.long("spaceBlockedCount") ?: 0
        return UpgradeResult.Success(
            upgradedCount = upgraded.toInt(),
            spaceBlockedCount = spaceBlocked.toInt()
        )
    }

    /**
     * 拆除事务（存在性/幽灵防御 + 灵石返还）+ 残差派生清理。
     * 返还走 C++ wallet.add（记年度账，与回退臂 Kotlin wallet.add 同原语）。
     *
     * W4-A·w3-09 残差清扫下沉：BUILDING_REMOVE 成功后关联弟子收集
     * （清扫前行删除会丢 id；生产 repo 侧来源同现状——平台存储 C++ 不可见），
     * 随槽组知识组装传入 1810（C++ 扫实例键控七集合 + 血炼 + 监牢/任务阁
     * 特例 + REFINING 破除）；**生产/长老组留 Kotlin**（偏差登记：
     * C++ ProductionSlot 行无 buildingInstanceId、ElderPositions clearSpec
     * 为注册表 lambda 单一事实源）——清扫成功后 Kotlin 在同一 update 内
     * 补扫两类 + Gate 释放（运行态域）。降级/失败信封回退 Kotlin 原路径
     * 全量残差（cleanupBuildingSlotsResidual，行为零变更）。
     */
    fun removeBuildings(refunds: Map<String, Long>): Boolean {
        // 拆除前快照：残差清理需要被拆建筑的 feature 映射（native 成功后
        // placedBuildings 已不含它们）
        val before = stateStore.gameDataSnapshot.placedBuildings.associateBy { it.instanceId }
        val refundList = refunds.map { (instanceId, refund) ->
            buildJsonObject {
                put("instanceId", instanceId)
                put("refund", refund)
            }
        }
        val data = tx(ActionIds.BUILDING_REMOVE) {
            put("refunds", JsonArray(refundList))
        } ?: return false
        val removedIds = (data as? JsonObject)?.get("removedIds")?.let { arr ->
            (arr as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        } ?: return false
        if (removedIds.isEmpty()) return true
        val removedSet = removedIds.toSet()
        val targets = before.filterKeys { it in removedSet }.values.mapNotNull { building ->
            BuildingFeatureRegistry.findByDisplayName(building.displayName)
                ?.let { feature -> building to feature }
        }
        val productionIds = targets
            .filter { (_, feature) ->
                feature.slotGroups.any { it is SlotGroup.ProductionSlotGroup }
            }.map { (building, _) -> building.instanceId }
        // 关联弟子收集（清扫前——收集读槽位行 + 生产 repo 运行态）
        val allDiscipleIds = collectRemovedDiscipleIds(targets, productionIds)
        val clearedNative = tryNativeResidualClear(targets, allDiscipleIds)
        if (clearedNative) {
            stateStore.update {
                // 生产 + 长老组补扫（偏差登记——C++ 清扫范围外；过滤幂等）
                for ((building, feature) in targets) {
                    gameData = cleanupProductionAndElderSlotsOnly(gameData, feature, building)
                }
            }
            // Gate 释放（Kotlin 运行态域）；REFINING 破除已由 C++ 承担
            allDiscipleIds.forEach { assignmentGate.release(it) }
        } else {
            stateStore.update {
                for ((building, feature) in targets) {
                    gameData = cleanupBuildingSlotsResidual(feature, building)
                }
            }
        }
        if (productionIds.isNotEmpty()) {
            gameEngineCore.launchInScope {
                productionCoordinator.repository.getSlots()
                    .filter { it.buildingInstanceId in productionIds }
                    .forEach { slot -> productionCoordinator.repository.removeSlot(slot.id) }
            }
        }
        discipleStatusService.syncAllDiscipleStatuses()
        return true
    }

    /**
     * 放置槽位派生事务（W4-A·w3-09，1811）：batch-06 tryPlace 成功后的
     * createSlots 残差下沉——七组实例键控集合建槽 + 每塔一份 PatrolConfig；
     * 生产/长老组留 Kotlin（偏差登记同 removeBuildings）。
     * 成功后调用方仅承担生产槽 gameData 写 + Room 回流。
     */
    fun tryPlaceSlots(
        feature: BuildingFeature,
        instanceId: String,
        activeId: String
    ): Boolean {
        val data = tx(ActionIds.BUILDING_PLACE_SLOTS) {
            put("instanceId", instanceId)
            put("sectId", activeId)
            put("groups", JsonArray(feature.slotGroups.mapNotNull { group ->
                val kind = group.kindName() ?: return@mapNotNull null
                buildJsonObject {
                    put("kind", kind)
                    put("count", group.slotsPerInstance)
                }
            }))
        } ?: return false
        return (data as? JsonObject)?.get("created") != null
    }

    // ── 内部 ────────────────────────────────────────────────────

    /**
     * 关联弟子收集（清扫前调用——槽位行删除后 id 丢失；集合侧读
     * pre-clear 快照，生产侧读 Room 运行态 repo——平台存储 C++ 不可见）。
     */
    private fun collectRemovedDiscipleIds(
        targets: List<Pair<GridBuildingData, BuildingFeature>>,
        productionIds: List<String>
    ): Set<String> {
        val snapshotData = stateStore.gameDataSnapshot
        val gameDataDiscipleIds = buildSet {
            for ((building, feature) in targets) {
                addAll(
                    feature.slotGroups.flatMap {
                        it.collectDiscipleIds(snapshotData, building.instanceId, feature)
                    }
                )
            }
        }
        if (productionIds.isEmpty()) return gameDataDiscipleIds
        val roomDiscipleIds = productionCoordinator.repository.getSlots()
            .filter { it.buildingInstanceId in productionIds }
            .mapNotNull { it.assignedDiscipleId }
            .filter { it.isNotEmpty() }
            .toSet()
        return gameDataDiscipleIds + roomDiscipleIds
    }

    /**
     * 1810 残差清扫转发：仅发 C++ 清扫范围内的组（生产/长老组不发线）。
     * @return true=已清扫；false=降级/失败信封（调用方回退 Kotlin 原路径）
     */
    private fun tryNativeResidualClear(
        targets: List<Pair<GridBuildingData, BuildingFeature>>,
        discipleIds: Set<String>
    ): Boolean {
        val data = tx(ActionIds.BUILDING_RESIDUAL_CLEAR) {
            put("targets", JsonArray(targets.map { (building, feature) ->
                buildJsonObject {
                    put("instanceId", building.instanceId)
                    put("groups", JsonArray(feature.slotGroups.mapNotNull { it.kindName() }
                        .map { JsonPrimitive(it) }))
                    put("isMissionHall", feature.buildingType == BuildingType.MISSION_HALL)
                    put("isReflectionCliff", feature.buildingType == BuildingType.REFLECTION_CLIFF)
                    put("discipleIds", JsonArray(discipleIds.map { JsonPrimitive(it) }))
                }
            }))
        } ?: return false
        return (data as? JsonObject)?.get("cleared") != null
    }

    /** 生产 + 长老组补扫（1810 清扫范围外的两类——注册表语义留 Kotlin）。 */
    private fun MutableGameState.cleanupProductionAndElderSlotsOnly(
        current: GameData,
        feature: BuildingFeature,
        building: GridBuildingData
    ): GameData {
        var gd = current
        for (group in feature.slotGroups) {
            gd = when (group) {
                is SlotGroup.ProductionSlotGroup ->
                    group.filterFromGameData(gd, building.instanceId, feature)
                is SlotGroup.ElderPositions ->
                    group.filterFromGameData(gd, building.instanceId, feature)
                else -> gd
            }
        }
        return gd
    }

    /**
     * 槽组 → 协议组名（C++ parseSlotGroupKind 同名映射）。
     * @return null = C++ 清扫范围外的组（生产/长老——不发线）
     */
    private fun SlotGroup.kindName(): String? = when (this) {
        is SlotGroup.SpiritMine -> "SPIRIT_MINE"
        is SlotGroup.PatrolTower -> "PATROL_TOWER"
        is SlotGroup.Residence -> "RESIDENCE"
        is SlotGroup.SpiritField -> "SPIRIT_FIELD"
        is SlotGroup.Warehouse -> "WAREHOUSE"
        is SlotGroup.BloodRefining -> "BLOOD_REFINING"
        is SlotGroup.Library -> "LIBRARY"
        is SlotGroup.ProductionSlotGroup -> null
        is SlotGroup.ElderPositions -> null
    }

    /**
     * native 事务转发：AUTHORITATIVE 门控 + tryExecuteNative（成功内含
     * applyDirtyFromNative 脏段回读：placedBuildings/spiritStones/guideCounters
     * 整段镜像）；失败信封/降级返回 null。
     */
    private fun tx(actionId: Int, build: JsonObjectBuilder.() -> Unit): JsonElement? {
        if (!NativeEngineFlag.authoritative) return null
        return GameEngineNativeOps.tryExecuteNative(
            stateSyncService = gameEngineCore.stateSyncServiceRef,
            actionId = actionId,
            paramsJson = params(build)
        )
    }

    /** 占位几何常量（GameConfig.SectMap 环界/世界尺寸 + FixedSectGateway 门楼矩形——单一事实源留 Kotlin）。 */
    private fun JsonObjectBuilder.putPlaceGeom() {
        put("border", GameConfig.SectMap.BORDER_TREE_RING)
        put("worldWidth", GameConfig.SectMap.WORLD_WIDTH_CELLS)
        put("worldHeight", GameConfig.SectMap.WORLD_HEIGHT_CELLS)
        put("gateX", GameConfig.SectMap.GATE_X)
        put("gateY", GameConfig.SectMap.GATE_Y)
        put("gateWidth", GameConfig.SectMap.GATE_WIDTH)
        put("gateHeight", GameConfig.SectMap.GATE_HEIGHT)
    }

    /** 同宗建筑实例集（宗门过滤归 Kotlin——C++ GridBuildingData 无 sectId，§2.36 同偏差）。 */
    private fun sectScopedIds(sectId: String): JsonArray =
        JsonArray(
            stateStore.gameDataSnapshot.placedBuildings
                .filter { it.sectId == sectId }
                .map { JsonPrimitive(it.instanceId) }
        )

    /**
     * 拆除残差清理：与 Kotlin 回退臂 cleanupBuildingSlots 同构（槽位过滤/
     * 弟子释放/监牢·任务阁特例），仅缺建筑移除与灵石返还两段——已由 C++
     * 事务承担（building_tx.h removeBuildingsTx）。
     */
    private fun MutableGameState.cleanupBuildingSlotsResidual(
        feature: BuildingFeature, building: GridBuildingData
    ): GameData {
        val instanceId = building.instanceId
        val discipleIds = feature.slotGroups
            .flatMap { it.collectDiscipleIds(gameData, instanceId, feature) }
            .toMutableSet()
        if (feature.slotGroups.any { it is SlotGroup.ProductionSlotGroup }) {
            discipleIds += productionCoordinator.repository.getSlots()
                .filter { it.buildingInstanceId == instanceId }
                .mapNotNull { it.assignedDiscipleId }
        }
        var gd = gameData
        for (group in feature.slotGroups) {
            gd = group.filterFromGameData(gd, instanceId, feature)
        }
        releaseBuildingDiscipleIds(assignmentGate, discipleIds)
        if (feature.buildingType == BuildingType.REFLECTION_CLIFF) {
            releaseReflectingDisciples()
        }
        // 任务阁拆除：清理所有活跃任务并释放卡在 ON_MISSION 的弟子
        if (feature.buildingType == BuildingType.MISSION_HALL) {
            gd = gd.copy(activeMissions = emptyList())
            for (id in discipleTables.ids) {
                if (discipleTables.statuses[id] == DiscipleStatus.ON_MISSION &&
                    discipleTables.isAlive[id] == 1
                ) {
                    discipleTables.statuses[id] = DiscipleStatus.IDLE
                }
            }
        }
        return gd
    }
}

/**
 * 释放建筑关联弟子：Gate 注册 + 血炼 REFINING 状态（Kotlin 回退臂
 * cleanupBuildingSlots 与 native 残差臂 cleanupBuildingSlotsResidual 共用）。
 * 血炼受保护状态须在事务内显式打破，否则事务外重推拉不回 IDLE。
 */
internal fun MutableGameState.releaseBuildingDiscipleIds(
    gate: com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate,
    discipleIds: Set<String>
) {
    discipleIds.forEach { gate.release(it) }
    discipleIds.mapNotNull { it.toIntOrNull() }
        .filter { it in discipleTables.ids }
        .filter { discipleTables.statuses[it] == DiscipleStatus.REFINING }
        .forEach { dId ->
            discipleTables.statuses[dId] = DiscipleStatus.IDLE
            discipleTables.statusData[dId] =
                (discipleTables.statusData[dId] ?: emptyMap()) - setOf("buildingId")
        }
}
