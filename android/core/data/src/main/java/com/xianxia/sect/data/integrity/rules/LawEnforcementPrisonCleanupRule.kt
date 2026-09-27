package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.data.model.SaveData

/**
 * 执法堂 / 监牢下线：旧档残留清理与弟子状态归一化规则。
 *
 * 执法堂（`law_enforcement_hall`）与监牢（`reflection_cliff`）建筑整体下线，旧存档可能残留：
 * - [SaveData.gameData] 的 `placedBuildings` 中两栋建筑的实例；
 * - 挂靠这些实例（`instanceId`）的实例键控槽位（矿场 / 藏经阁 / 住所 / 灵田 / 巡视 / 生产六类）；
 * - 弟子状态残留的 `REFLECTING`（思过中）与 `LAW_ENFORCING`（执法弟子）。
 *
 * **为什么必须归一化弟子状态**：`REFLECTING` 是受保护状态（推导永不对其回退），而它的生产
 * 写入方（叛逃捕获 / 偷盗捕获 / 年结思过释放）与唯一的解除入口（监牢「释放」）都已下线——
 * 不归一化即等于把存量弟子永久卡死（不可分配、不可参战，玩家无任何出口）。
 *
 * 执法长老 / 执法亲传槽位字段本身已随模型退役，由 ProtoBuf「未知字段号跳过」机制在解码阶段
 * 丢弃（见 `GameDataSectModels.ElderSlots` 的 `reserved 9,10`），**不是本规则职责**。
 *
 * 存在清理时返回 [RuleOutcome.Repaired]（触发落盘），无残留时返回 [RuleOutcome.Passed]，
 * 避免每次存档校验都触发 Repaired 落盘。规则必须零抛异常——抛异常会被框架转为
 * Corrupted，阻断读档。
 */
object LawEnforcementPrisonCleanupRule : SaveValidationRule {
    override val id = "law_enforcement_prison_cleanup"
    override val order = 24

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val gd = data.gameData
        val retiredBuildings = gd.placedBuildings.filter { it.buildingId in RETIRED_BUILDING_IDS }
        val retiredInstances = retiredBuildings
            .map { it.instanceId }
            .filter { it.isNotEmpty() }
            .toSet()
        val normalizedCount = data.disciples.count { it.status in RETIRED_STATUSES }

        if (retiredBuildings.isEmpty() && normalizedCount == 0) return RuleOutcome.Passed

        val repairs = mutableListOf<String>()
        if (retiredBuildings.isNotEmpty()) {
            repairs += "执法堂/监牢已下线，移除建筑实例 ${retiredBuildings.size} 个"
        }
        if (normalizedCount > 0) {
            repairs += "已下线状态的弟子归一化为空闲：$normalizedCount 名"
        }

        return RuleOutcome.Repaired(
            data.copy(
                gameData = gd.copy(
                    placedBuildings = gd.placedBuildings.filterNot { it.buildingId in RETIRED_BUILDING_IDS },
                    spiritMineSlots = gd.spiritMineSlots.withoutInstances(retiredInstances) { it.buildingInstanceId },
                    librarySlots = gd.librarySlots.withoutInstances(retiredInstances) { it.buildingInstanceId },
                    residenceSlots = gd.residenceSlots.withoutInstances(retiredInstances) { it.buildingInstanceId },
                    spiritFieldPlants =
                        gd.spiritFieldPlants.withoutInstances(retiredInstances) { it.buildingInstanceId },
                    patrolSlots = gd.patrolSlots.withoutInstances(retiredInstances) { it.buildingInstanceId },
                    productionSlots = gd.productionSlots.withoutInstances(retiredInstances) { it.buildingInstanceId }
                ),
                disciples = data.disciples.map { it.normalizedStatus() }
            ),
            repairs
        )
    }
}

/**
 * 剔除挂靠已下线建筑实例的槽位行。
 *
 * 六类实例键控槽位的字段名一致，用取值函数统一（`ids` 为空时原样返回，避免无谓的列表重建）。
 */
private inline fun <T> List<T>.withoutInstances(
    ids: Set<String>,
    instanceId: (T) -> String
): List<T> = if (ids.isEmpty()) this else filterNot { instanceId(it) in ids }

/** 已下线状态 → 空闲并剥离派生键；其余状态原样返回。 */
private fun Disciple.normalizedStatus(): Disciple =
    if (status in RETIRED_STATUSES) {
        copy(status = DiscipleStatus.IDLE, statusData = statusData - RETIRED_STATUS_DATA_KEYS)
    } else {
        this
    }

/** 建筑配置中的下线建筑标识（`BuildingConfigModel.id` / `GridBuildingData.buildingId`）。 */
private val RETIRED_BUILDING_IDS = setOf("law_enforcement_hall", "reflection_cliff")

/** 无生产写入方的存量状态：思过中 / 执法弟子。 */
private val RETIRED_STATUSES = setOf(DiscipleStatus.REFLECTING, DiscipleStatus.LAW_ENFORCING)

/** 随状态归一化一并剥离的派生键（思过年限 + 职位名缓存）。 */
private val RETIRED_STATUS_DATA_KEYS =
    setOf("reflectionStartYear", "reflectionEndYear", "positionName")
