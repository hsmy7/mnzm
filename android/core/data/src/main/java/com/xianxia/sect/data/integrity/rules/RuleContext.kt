package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 规则执行上下文。
 *
 * 在遍历规则之前一次性预计算所有派生数据，避免每个规则重复计算。
 * 中间状态（[removedDiscipleIds]）由规则动态填充，供后续规则使用。
 */
class RuleContext(saveData: SaveData) {

    /** 所有装备 ID（equipmentStacks + equipmentInstances 的 ID 并集） */
    val allEquipmentIds: Set<String> = run {
        val ids = mutableSetOf<String>()
        saveData.equipmentStacks.forEach { ids.add(it.id) }
        saveData.equipmentInstances.forEach { ids.add(it.id) }
        ids
    }

    /** 所有建筑实例 ID（placedBuildings 中非空的 instanceId） */
    val buildingInstanceIds: Set<String> =
        saveData.gameData.placedBuildings
            .map { it.instanceId }
            .filter { it.isNotEmpty() }
            .toHashSet()

    /**
     * 被幽灵清理规则移除的弟子 ID 集合。
     * 由 [GhostDiscipleCleanupRule] 在 order=10 时填充，
     * [GhostRefCleanupRule] 在 order=11 时消费。
     */
    val removedDiscipleIds: MutableSet<String> = mutableSetOf()
}
