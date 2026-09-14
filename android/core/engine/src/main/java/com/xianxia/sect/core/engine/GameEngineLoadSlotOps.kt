package com.xianxia.sect.core.engine

import kotlinx.coroutines.flow.map
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.repository.getSlotsByBuildingId


/** 读档生产槽恢复：按建筑数量修正槽位并回填 Repository */
internal suspend fun GameEngine.restoreProductionSlotsForLoad(
    gameData: GameData,
    productionSlots: List<ProductionSlot>
) {
    val alchemyCount = BuildingFeatureRegistry.countByType(gameData, BuildingType.ALCHEMY)
    val forgeCount = BuildingFeatureRegistry.countByType(gameData, BuildingType.FORGE)
    // 防御（Bugly #13014）：loadData 参数列表可能携带 null（旧调用方/损坏存档），
    // 净化后再交给 fixAlchemyForgeSlotCount（内部访问 buildingType）
    val safeProductionSlots = productionSlots.filterNotNull()
    val fixedProductionSlots = fixAlchemyForgeSlotCount(safeProductionSlots, alchemyCount, forgeCount)
    if (fixedProductionSlots.isNotEmpty()) {
        productionCoordinator.repository.restoreSlots(fixedProductionSlots, gameData.currentSlot)
    } else {
        productionCoordinator.repository.initializeAllSlots(gameData.currentSlot)
    }
}

/** 读档双存储对齐：以 Repository 为真源写回镜像 productionSlots */
internal suspend fun GameEngine.alignProductionSlotsWithRepository() {
    // 双存储对齐（读档自愈）：以 Repository 为真源（restoreSlots 刚写入），
    // 写回镜像 gameData.productionSlots，消除历史分叉存档——镜像残留/缺失会导致
    // 状态推导与 UI 展示不一致（弟子自动脱离槽位/被自动任命其他槽位根因）
    stateStore.update {
        val repoSlots = productionCoordinator.repository.getSlots()
        if (repoSlots.isNotEmpty()) {
            this.gameData = this.gameData.copy(productionSlots = repoSlots)
        }
    }
}

internal fun migratePatrolSlotsIfNeeded(gameData: GameData, disciples: List<Disciple>): Pair<GameData, List<Disciple>> {
    val numTowers = gameData.placedBuildings.count {
        BuildingFeatureRegistry.findByDisplayName(it.displayName)?.buildingType == BuildingType.PATROL
    }
    if (numTowers == 0) return gameData to disciples
    val oldSlots = gameData.patrolSlots
    val expectedSize = numTowers * 8
    val towers = gameData.placedBuildings.filter {
        BuildingFeatureRegistry.findByDisplayName(it.displayName)?.buildingType == BuildingType.PATROL
    }
    if (oldSlots.size <= expectedSize) {
        // 即使数量正确，也需回填 buildingInstanceId（旧存档可能为空）
        val needsBackfill = oldSlots.any { it.buildingInstanceId.isEmpty() }
        if (!needsBackfill) return gameData to disciples
        return backfillPatrolTowerInstances(gameData, oldSlots, towers) to disciples
    }
    DomainLog.w("GameEngine", "迁移巡逻槽位: ${oldSlots.size}槽/${numTowers}塔 → ${expectedSize}槽")
    return rebuildPatrolSlotsResized(gameData, oldSlots, disciples, numTowers)
}

/** 槽位数正确时的 buildingInstanceId 回填（migratePatrolSlotsIfNeeded 子步骤）：按塔序每塔 8 槽重锚 */
internal fun backfillPatrolTowerInstances(
    gameData: GameData,
    oldSlots: List<PatrolSlot>,
    towers: List<GridBuildingData>
): GameData {
    val backfilledSlots = mutableListOf<PatrolSlot>()
    var globalIdx = 0
    for (tower in towers) {
        for (localIdx in 0 until 8) {
            if (globalIdx < oldSlots.size) {
                backfilledSlots.add(oldSlots[globalIdx].copy(buildingInstanceId = tower.instanceId))
            }
            globalIdx++
        }
    }
    return gameData.copy(patrolSlots = backfilledSlots)
}

/**
 * 槽位数变化时的槽位重建（migratePatrolSlotsIfNeeded 子步骤）：旧 10 槽/塔布局迁移为
 * 8 槽/塔布局，被裁撤槽位（8/10 与孤儿尾段）的驻守弟子归位 IDLE。
 */
internal fun rebuildPatrolSlotsResized(
    gameData: GameData,
    oldSlots: List<PatrolSlot>,
    disciples: List<Disciple>,
    numTowers: Int
): Pair<GameData, List<Disciple>> {
    var updatedDisciples = disciples.toMutableList()
    val towers = gameData.placedBuildings.filter { it.displayName == "巡视楼" }
    val newSlots = mutableListOf<PatrolSlot>()
    var newGlobalIndex = 0
    for (towerIdx in 0 until numTowers) {
        val tower = towers[towerIdx]
        val oldStart = towerIdx * 10
        for (localIdx in 0 until 8) {
            val globalIdx = oldStart + localIdx
            newSlots.add(
                if (globalIdx < oldSlots.size) {
                    oldSlots[globalIdx].copy(index = newGlobalIndex, buildingInstanceId = tower.instanceId)
                } else {
                    PatrolSlot(index = newGlobalIndex, buildingInstanceId = tower.instanceId)
                }
            )
            newGlobalIndex++
        }
        for (discardLocalIdx in 8 until 10) {
            val discardGlobalIdx = oldStart + discardLocalIdx
            if (discardGlobalIdx < oldSlots.size) {
                updatedDisciples = releasePatrolDisciple(updatedDisciples, oldSlots[discardGlobalIdx])
            }
        }
    }
    val orphanedStart = numTowers * 10
    for (i in orphanedStart until oldSlots.size) {
        updatedDisciples = releasePatrolDisciple(updatedDisciples, oldSlots[i])
    }
    val newConfigs = gameData.patrolConfigs.take(numTowers)
    DomainLog.i("GameEngine", "巡逻槽位迁移完成: ${oldSlots.size} → ${newSlots.size}")
    return gameData.copy(patrolSlots = newSlots, patrolConfigs = newConfigs) to updatedDisciples
}

/** 被裁撤槽位的驻守弟子归位 IDLE（migratePatrolSlotsIfNeeded 子步骤）：空槽零触碰 */
internal fun releasePatrolDisciple(
    disciples: MutableList<Disciple>,
    slot: PatrolSlot
): MutableList<Disciple> {
    if (slot.discipleId.isEmpty()) return disciples
    return disciples.map {
        if (it.id == slot.discipleId) it.copy(status = DiscipleStatus.IDLE) else it
    }.toMutableList()
}

internal suspend fun GameEngine.checkAndCollectCompletedSlots() {
    autoHarvestCompletedAlchemySlots()
    val forgeSlots = productionCoordinator.repository.getSlotsByBuildingId("forge")
    forgeSlots.forEach { slot -> if (slot.status == com.xianxia.sect.core.model.production.ProductionSlotStatus
        .COMPLETED) buildingService.autoHarvestForgeSlot(slot) }
}

internal fun fixAlchemyForgeSlotCount(slots: List<ProductionSlot>, alchemyCount: Int,
    forgeCount: Int): List<ProductionSlot> {
    val result = slots.toMutableList()
    val alchemySlots = result.filter { it.buildingType == BuildingType.ALCHEMY }; result.removeAll { it
        .buildingType == BuildingType.ALCHEMY }
    val fixedAlchemy = mutableListOf<ProductionSlot>()
    alchemySlots.sortedBy { it.slotIndex }.take(alchemyCount).forEach { fixedAlchemy.add(it) }
    if (fixedAlchemy.size < alchemyCount) {
        val existingIndices = fixedAlchemy.map { it.slotIndex }.toSet()
        var nextIdx = 0
        while (fixedAlchemy.size < alchemyCount) {
            if (nextIdx !in existingIndices) {
                fixedAlchemy.add(
                    ProductionSlot.createIdle(
                        slotIndex = nextIdx, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy"
                    )
                )
            }
            nextIdx++
        }
    }
    result.addAll(fixedAlchemy)
    val forgeSlots = result.filter { it.buildingType == BuildingType.FORGE }
    result.removeAll { it.buildingType == BuildingType.FORGE }
    val fixedForge = mutableListOf<ProductionSlot>()
    forgeSlots.sortedBy { it.slotIndex }.take(forgeCount).forEach { fixedForge.add(it) }
    if (fixedForge.size < forgeCount) {
        val existingIndices = fixedForge.map { it.slotIndex }.toSet()
        var nextIdx = 0
        while (fixedForge.size < forgeCount) {
            if (nextIdx !in existingIndices) {
                fixedForge.add(
                    ProductionSlot.createIdle(
                        slotIndex = nextIdx, buildingType = BuildingType.FORGE, buildingId = "forge"
                    )
                )
            }
            nextIdx++
        }
    }
    result.addAll(fixedForge)
    return result
}
