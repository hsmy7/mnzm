package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SlotStatus
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.str
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.engine.domain.building.BuildingFacadeImpl.PlantingTime

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── BuildingFacadeImpl 拆分域 1/1（行为零变更） ──

private val TAG = BuildingFacadeImpl.TAG
/**
 * 建造灵矿场后同步 slot：为所有灵矿场建筑重建 3 槽位，
 * 新槽位初始化 lastSettledGameMonth 为当前月份（防回档双计）。
 */
internal fun BuildingFacadeImpl.syncSpiritMineSlotsAfterPlace() {
    gameEngineCore.launchInScope {
        stateStore.update {
            val data = gameData
            val globalMines = data.placedBuildings.filter {
                BuildingFeatureRegistry.findByDisplayName(it.displayName)?.buildingType == BuildingType.MINING
            }
            val rebuiltSlots = mutableListOf<SpiritMineSlot>()
            var slotIdx = 0
            for (mine in globalMines) {
                for (offset in 0 until 3) {
                    val existing = data.spiritMineSlots.getOrNull(slotIdx + offset)
                    val slot = if (existing != null) {
                        existing.copy(index = rebuiltSlots.size, buildingInstanceId = mine.instanceId)
                    } else {
                        SpiritMineSlot(
                            index = rebuiltSlots.size,
                            sectId = mine.sectId,
                            buildingInstanceId = mine.instanceId
                        )
                    }
                    rebuiltSlots.add(slot)
                }
                slotIdx += 3
            }
            if (rebuiltSlots != data.spiritMineSlots) {
                gameData = data.copy(spiritMineSlots = rebuiltSlots)
            }
        }
    }
}

/**
 * 手动排班 native 转发 + 后置持久化。
 * C++ 事务（production.h startProductionTransaction）写镜像（槽位 WORKING +
 * herbs/materials 扣减，tryExecuteNative 经 applyDirtyFromNative 回读）；
 * 本函数把镜像槽位回放 Room（S4 restoreSlots 同族单槽）——"C++ 真相先行 +
 * Room 持久化后置"。native 失败/降级返回 null（调用方回退 Kotlin 原路径）。
 */
@Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/镜像缺失/成功逐级返回）
internal suspend fun BuildingFacadeImpl.productionNativeStart(
    buildingId: String,
    slotIndex: Int,
    recipeId: String,
    isAlchemy: Boolean
): DomainResult<ProductionSlot>? {
    if (!NativeEngineFlag.authoritative) return null
    val data = GameEngineNativeOps.tryExecuteNative(
        stateSyncService = gameEngineCore.stateSyncServiceRef,
        actionId = ActionIds.PRODUCTION_START,
        paramsJson = params {
            put("buildingId", buildingId)
            put("slotIndex", slotIndex)
            put("recipeId", recipeId)
            put("successRate", -1.0)
            put("policyBonus", 0.0)
            put("isAlchemy", isAlchemy)
        }
    ) ?: return null
    if (data.str("started") != "true") return null
    // 镜像槽位 → Room 回放（单槽；镜像为真源）
    val mirrored = stateStore.gameDataSnapshot.productionSlots
        .find { it.buildingId == buildingId && it.slotIndex == slotIndex }
        ?: return DomainResult.Failure(
            AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex)
        )
    val existing = productionCoordinator.repository.getSlotByBuildingId(buildingId, slotIndex)
    if (existing == null) {
        productionCoordinator.repository.addSlot(mirrored)
    } else {
        productionCoordinator.repository.updateSlotByBuildingId(buildingId, slotIndex) {
            mirrored
        }
    }
    return DomainResult.Success(mirrored)
}

internal fun ProductionSlot.toBuildingSlot(): BuildingSlot = BuildingSlot(
    id = id,
    buildingId = buildingId,
    slotIndex = slotIndex,
    discipleId = assignedDiscipleId,
    discipleName = assignedDiscipleName,
    startYear = startYear,
    startMonth = startMonth,
    duration = duration,
    recipeId = recipeId,
    recipeName = recipeName,
    status = when (status) {
        ProductionSlotStatus.IDLE -> SlotStatus.IDLE
        ProductionSlotStatus.WORKING -> SlotStatus.WORKING
        ProductionSlotStatus.COMPLETED -> SlotStatus.COMPLETED
    }
)

/**
 * 目标槽位 occupant 变更时释放其 gate 注册（事务前执行——GameData 仍含旧槽位，
 * sync 在事务后执行，此处仅 release 防双槽位注册残留）。
 */
internal fun BuildingFacadeImpl.releaseGateIfOccupantChanged(existingSlot: ProductionSlot?, discipleId: String) {
    existingSlot?.assignedDiscipleId?.let { oldDiscipleId ->
        if (oldDiscipleId.isNotEmpty() && oldDiscipleId != discipleId) {
            assignmentGate.release(oldDiscipleId)
        }
    }
}

/**
 * 目标槽 repo 写失败时回滚镜像为分配前快照——双端一致，
 * gate 不登记，玩家可重试任命。
 */

internal fun BuildingFacadeImpl.rollbackMirrorSlot(
    buildingType: BuildingType,
    slotIndex: Int,
    existingSlot: ProductionSlot?
) {
    stateStore.update {
        val oldId = existingSlot?.assignedDiscipleId
        val oldName = existingSlot?.assignedDiscipleName ?: ""
        gameData = gameData.copy(
            productionSlots = gameData.productionSlots.map { slot ->
                if (slot.buildingType == buildingType && slot.slotIndex == slotIndex) {
                    slot.copy(assignedDiscipleId = oldId, assignedDiscipleName = oldName)
                } else slot
            }
        )
    }
}

/** 旧 occupant 状态在事务完成后同步（推导式，此时 GameData 已清旧槽位）。 */

internal fun BuildingFacadeImpl.syncOldOccupantStatus(existingSlot: ProductionSlot?, discipleId: String) {
    existingSlot?.assignedDiscipleId?.let { oldDiscipleId ->
        if (oldDiscipleId.isNotEmpty() && oldDiscipleId != discipleId) {
            discipleStatusService.syncSingleDiscipleStatus(oldDiscipleId)
        }
    }
}

/**
 * repo 写入目标槽并清该弟子他处占用。
 * @return true 表示目标槽写入失败——调用方须回滚镜像且不登记 gate
 *（否则镜像已写而 repo 未写，UI 显示空闲——4.00.91 玩家"任命不生效"主症状路径）
 */

internal suspend fun BuildingFacadeImpl.writeRepoAssignment(
    buildingType: BuildingType,
    slotIndex: Int,
    discipleId: String,
    discipleName: String
): Boolean {
    var targetWriteFailed = false
    withContext(ioDispatcher.dispatcher) {
        productionCoordinator.repository.getSlots()
            .filter { it.assignedDiscipleId == discipleId }
            .forEach { slot ->
                val cleared = productionCoordinator.repository.updateSlot(
                    slot.buildingType, slot.slotIndex
                ) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
                if (cleared.isFailure) {
                    DomainLog.w(
                        TAG,
                        "清他处占用失败: ${slot.buildingType}[${slot.slotIndex}] disciple=$discipleId, " +
                            (cleared.exceptionOrNull()?.message ?: "unknown")
                    )
                }
            }
        val targetResult = productionCoordinator.repository.updateSlot(buildingType, slotIndex) { slot ->
            slot.copy(
                assignedDiscipleId = discipleId,
                assignedDiscipleName = discipleName
            )
        }
        if (targetResult.isFailure) {
            targetWriteFailed = true
            DomainLog.e(
                TAG,
                "任命失败: ${buildingType}[$slotIndex] disciple=$discipleId, " +
                    (targetResult.exceptionOrNull()?.message ?: "unknown")
            )
        }
    }
    return targetWriteFailed
}

/** 批量播种到空地：返回 (新植物列表, 实际种植数)。同事务内调用，条件与单块播种一致 */
internal fun BuildingFacadeImpl.plantFields(
    plants: List<SpiritFieldPlant>,
    emptyFieldIds: Set<String>,
    seed: Seed,
    maxToPlant: Int,
    timing: PlantingTime,
    sectId: String
): Pair<List<SpiritFieldPlant>, Int> {
    val updated = plants.toMutableList()
    var planted = 0
    for (i in updated.indices) {
        if (planted >= maxToPlant) break
        val p = updated[i]
        // sectId 校验：非本宗地块不可播种；空 sectId 为旧数据兼容
        if (p.buildingInstanceId in emptyFieldIds && p.isPlantable(sectId)) {
            updated[i] = p.copy(
                seedId = seed.id, seedName = seed.name,
                growTime = seed.growTime, expectedYield = seed.yield,
                plantYear = timing.year, plantMonth = timing.month, sectId = sectId,
                completionMonth = timing.absoluteMonth + seed.growTime.coerceAtLeast(1),
                completionPhase = 3  // 种植下旬
            )
            planted++
        }
    }
    return updated to planted
}

/** 手动重置 native 转发（槽位回 IDLE）+ 镜像→Room 回放。降级返回 false。 */
@Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/镜像缺失/成功逐级返回）
internal suspend fun BuildingFacadeImpl.productionNativeReset(buildingId: String, slotIndex: Int): Boolean {
    if (!NativeEngineFlag.authoritative) return false
    val data = GameEngineNativeOps.tryExecuteNative(
        stateSyncService = gameEngineCore.stateSyncServiceRef,
        actionId = ActionIds.PRODUCTION_RESET,
        paramsJson = params {
            put("buildingId", buildingId)
            put("slotIndex", slotIndex)
        }
    ) ?: return false
    if (data.str("reset") != "true") return false
    val mirrored = stateStore.gameDataSnapshot.productionSlots
        .find { it.buildingId == buildingId && it.slotIndex == slotIndex } ?: return false
    productionCoordinator.repository.updateSlotByBuildingId(buildingId, slotIndex) {
        mirrored
    }
    return true
}

/** 批量拆除主体（无真挂起点，非 suspend 供月度结算链复用） */
internal fun BuildingFacadeImpl.removeBuildingsInternal(refunds: Map<String, Long>) {
    if (refunds.isEmpty()) return
    val productionIds = mutableSetOf<String>()
    stateStore.update {
        // 预解析目标：未知建筑/实例在此跳过（避免事务循环内多处 continue）
        val targets = refunds.mapNotNull { (instanceId, refund) ->
            val building = gameData.placedBuildings.find { it.instanceId == instanceId }
                ?: return@mapNotNull null
            val feature = BuildingFeatureRegistry.findByDisplayName(building.displayName)
                ?: return@mapNotNull null
            Triple(feature, building, refund)
        }
        for ((feature, building, refund) in targets) {
            if (feature.slotGroups.any { it is SlotGroup.ProductionSlotGroup }) {
                productionIds.add(building.instanceId)
            }
            gameData = cleanupBuildingSlots(feature, building, refund)
        }
    }
    removeProductionSlotsFromRepository(productionIds)
    discipleStatusService.syncAllDiscipleStatuses()
}

/** 事务外单个协程批量删除 Repository 生产槽位（避免原事务内多协程竞态）。 */

internal fun BuildingFacadeImpl.removeProductionSlotsFromRepository(instanceIds: Set<String>) {
    if (instanceIds.isEmpty()) return
    gameEngineCore.launchInScope {
        productionCoordinator.repository.getSlots()
            .filter { it.buildingInstanceId in instanceIds }
            .forEach { slot -> productionCoordinator.repository.removeSlot(slot.id) }
    }
}

/** 监牢拆除：释放所有思过弟子（监牢限建 1 座，无实例归属记录，全量释放）。 */
internal fun MutableGameState.releaseReflectingDisciples() {
    for (id in discipleTables.ids) {
        if (discipleTables.statuses[id] == DiscipleStatus.REFLECTING) {
            discipleTables.statuses[id] = DiscipleStatus.IDLE
            discipleTables.statusData[id] =
                (discipleTables.statusData[id] ?: emptyMap()) -
                setOf("reflectionStartYear", "reflectionEndYear")
        }
    }
}

@Suppress("UnusedParameter") // status: 语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费
internal fun BuildingFacadeImpl.updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
    discipleStatusService.syncSingleDiscipleStatus(discipleId)
}
