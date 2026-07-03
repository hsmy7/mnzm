package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState

/**
 * 获取当前已用槽位数。
 */
internal fun getTotalSlotCount(
    currentEquipmentStacks: List<*>,
    currentManualStacks: List<*>,
    currentPills: List<*>,
    currentMaterials: List<*>,
    currentHerbs: List<*>,
    currentSeeds: List<*>
): Int {
    return currentEquipmentStacks.size + currentManualStacks.size +
        currentPills.size + currentMaterials.size +
        currentHerbs.size + currentSeeds.size
}

/**
 * 在 MutableGameState 事务内计算已用槽位。
 */
internal fun MutableGameState.computeSlotCount(): Int =
    equipmentStacks.size + manualStacks.size + pills.size +
        materials.size + herbs.size + seeds.size

/**
 * 在 MutableGameState 事务内计算最大槽位数。
 */
internal fun MutableGameState.computeMaxSlots(): Int {
    val warehouseCount = gameData.placedBuildings.count {
        it.displayName == BuildingType.WAREHOUSE.displayName
    }
    return GameConfig.Warehouse.BASE_CAPACITY +
        warehouseCount * GameConfig.Warehouse.CAPACITY_PER_BUILDING
}

/**
 * 获取当前仓库容量信息。
 */
internal fun inventoryCapacityInfo(stateStore: GameStateStore): CapacityInfo {
    val current = getTotalSlotCount(
        stateStore.equipmentStacks.value,
        stateStore.manualStacks.value,
        stateStore.pills.value,
        stateStore.materials.value,
        stateStore.herbs.value,
        stateStore.seeds.value
    )
    val maxSlots = getMaxSlots(stateStore)
    return CapacityInfo(
        currentSlots = current,
        maxSlots = maxSlots,
        remainingSlots = maxSlots - current,
        isFull = current >= maxSlots
    )
}

/**
 * 获取最大槽位数（从 GameData 的建筑列表计算）。
 */
internal fun getMaxSlots(stateStore: GameStateStore): Int {
    val buildings = stateStore.gameData.value.placedBuildings
    val warehouseCount = buildings.count {
        it.displayName == BuildingType.WAREHOUSE.displayName
    }
    return GameConfig.Warehouse.BASE_CAPACITY +
        warehouseCount * GameConfig.Warehouse.CAPACITY_PER_BUILDING
}

/**
 * 判断是否可以添加新的物品。
 */
internal fun inventoryCanAddItem(stateStore: GameStateStore): Boolean {
    val full = getTotalSlotCount(
        stateStore.equipmentStacks.value,
        stateStore.manualStacks.value,
        stateStore.pills.value,
        stateStore.materials.value,
        stateStore.herbs.value,
        stateStore.seeds.value
    ) >= getMaxSlots(stateStore)
    if (full) stateStore.warehouseFullEvent.tryEmit(Unit)
    return !full
}

/**
 * 判断是否可以添加指定数量的物品。
 */
internal fun inventoryCanAddItems(stateStore: GameStateStore, count: Int): Boolean {
    val full = getTotalSlotCount(
        stateStore.equipmentStacks.value,
        stateStore.manualStacks.value,
        stateStore.pills.value,
        stateStore.materials.value,
        stateStore.herbs.value,
        stateStore.seeds.value
    ) + count > getMaxSlots(stateStore)
    if (full) stateStore.warehouseFullEvent.tryEmit(Unit)
    return !full
}
