package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.util.DomainLog


// ── Cross-domain: Spirit mine / patrol / salary ─────────────────────

fun GameEngine.validateAndFixSpiritMineData() {
    // unifiedState → 独立窄流直读
    val data = stateStore.gameData.value
    val discipleMap = stateStore.disciples.value.associateBy { it.id }
    val globalMines = data.placedBuildings.filter {
        BuildingFeatureRegistry.findByDisplayName(it.displayName)?.buildingType == BuildingType.MINING
    }
    val rebuiltSlots = mutableListOf<SpiritMineSlot>()
    var slotIdx = 0
    var sectAlignedCount = 0
    for (mine in globalMines) {
        for (offset in 0 until 3) {
            val existing = data.spiritMineSlots.getOrNull(slotIdx + offset)
            val (slot, sectAligned) =
                rebuildSpiritMineSlot(existing, mine, rebuiltSlots.size, discipleMap)
            rebuiltSlots.add(slot)
            if (sectAligned) sectAlignedCount++
        }
        slotIdx += 3
    }
    val finalSlots = rebuiltSlots.toList()
    if (sectAlignedCount > 0) {
        DomainLog.w("GameEngine", "validateAndFixSpiritMineData: $sectAlignedCount 个矿场槽位 sectId 对齐至建筑")
    }
    if (finalSlots != data.spiritMineSlots) {
        updateGameDataSync { it.copy(spiritMineSlots = finalSlots) }
    }
}

/**
 * 重建单个矿场槽位：孤儿弟子引用清空、
 * 行号按重建序重排、buildingInstanceId 重锚到所属矿场建筑。
 * B3：槽位 sectId 与矿场建筑对齐——失配时 SpiritMineDialog 按建筑 sectId 过滤
 * 显示虚构空槽，玩家任命后 UI 不刷新（矿场版"点击不生效"）。
 *
 * @return 重建后的槽位 + 是否发生了 sectId 对齐（用于日志计数）
 */
private fun rebuildSpiritMineSlot(
    existing: SpiritMineSlot?,
    mine: GridBuildingData,
    newIndex: Int,
    discipleMap: Map<String, Disciple>
): Pair<SpiritMineSlot, Boolean> {
    val base = if (
        existing != null &&
        existing.discipleId.isNotEmpty() && existing.discipleId !in discipleMap
    ) {
        existing.copy(
            discipleId = "", discipleName = "",
            index = newIndex, buildingInstanceId = mine.instanceId
        )
    } else {
        existing?.copy(index = newIndex, buildingInstanceId = mine.instanceId)
    } ?: SpiritMineSlot(index = newIndex, sectId = mine.sectId, buildingInstanceId = mine.instanceId)
    return if (base.sectId != mine.sectId) {
        base.copy(sectId = mine.sectId) to true
    } else {
        base to false
    }
}

fun GameEngine.updateSpiritMineSlots(slots: List<SpiritMineSlot>) {
    updateGameDataSync { it.copy(spiritMineSlots = slots) }
}
fun GameEngine.updatePatrolSlots(slots: List<PatrolSlot>) { updateGameDataSync { it.copy(patrolSlots = slots) } }
fun GameEngine.updatePatrolConfig(config: PatrolConfig) { updateGameDataSync { it.copy(patrolConfig = config) } }
fun GameEngine.updatePatrolConfigs(configs: List<PatrolConfig>) {
    updateGameDataSync { it.copy(patrolConfigs = configs) }
}

fun GameEngine.addSpiritStones(amount: Long) {
    gameEngineCore.launchInScope {
        stateStore.modifyState { spiritStoneWallet.add(this, amount, com.xianxia.sect.core.model.SpiritStoneGrade.LOW,
            com.xianxia.sect.core.wallet.SpiritStoneSource.Internal) }
    }
}

fun GameEngine.updateYearlySalary(newSalary: Map<Int, Int>) { updateGameDataSync { it.copy(yearlySalary = newSalary) } }

// ── Private: Migration ──────────────────────────────────────────────




