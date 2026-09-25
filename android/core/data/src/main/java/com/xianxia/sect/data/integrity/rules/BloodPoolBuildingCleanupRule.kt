package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 血炼池建筑下线：旧档残留清理规则。
 *
 * 血炼玩法与血炼池建筑已整体下线，旧存档可能残留：
 * - [SaveData.gameData.placedBuildings] 中 [BLOOD_POOL_BUILDING_ID] 建筑实例；
 * - 挂靠这些实例（[com.xianxia.sect.core.model.GridBuildingData.instanceId]）的
 *   [SaveData.gameData.productionSlots] 槽位。
 *
 * 本规则移除全部残留：存在移除时返回 [RuleOutcome.Repaired]（触发落盘），
 * 无残留时返回 [RuleOutcome.Passed]，避免每次存档校验都触发 Repaired 落盘。
 *
 * 规则必须零抛异常——抛异常会被框架转为 Corrupted，阻断读档。
 */
object BloodPoolBuildingCleanupRule : SaveValidationRule {
    override val id = "blood_pool_building_cleanup"
    override val order = 17

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val gd = data.gameData
        val bloodPoolBuildings = gd.placedBuildings.filter { it.buildingId == BLOOD_POOL_BUILDING_ID }
        if (bloodPoolBuildings.isEmpty()) return RuleOutcome.Passed

        val bloodPoolInstanceIds = bloodPoolBuildings
            .map { it.instanceId }
            .filter { it.isNotEmpty() }
            .toSet()
        val keptSlots = if (bloodPoolInstanceIds.isEmpty()) {
            gd.productionSlots
        } else {
            gd.productionSlots.filterNot { it.buildingInstanceId in bloodPoolInstanceIds }
        }

        val repairs = mutableListOf("血炼池已下线，移除建筑实例 ${bloodPoolBuildings.size} 个")
        if (keptSlots.size != gd.productionSlots.size) {
            repairs.add("血炼池已下线，移除关联槽位 ${gd.productionSlots.size - keptSlots.size} 个")
        }

        return RuleOutcome.Repaired(
            data.copy(
                gameData = gd.copy(
                    placedBuildings = gd.placedBuildings.filterNot { it.buildingId == BLOOD_POOL_BUILDING_ID },
                    productionSlots = keptSlots
                )
            ),
            repairs
        )
    }
}

/** 血炼池建筑在建筑配置中的唯一标识。 */
private const val BLOOD_POOL_BUILDING_ID = "blood_refining_pool"
