package com.xianxia.sect.core.model.guide

import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.state.DiscipleTables

// ==================== 条件类型 ====================

sealed interface GuideCondition {
    /** 条件标签文本，如"建造10座灵矿场" */
    val label: String

    /** 是否满足（仅 GameData） */
    fun isMet(gameData: GameData): Boolean

    /**
     * 是否满足（带 DiscipleTables 参数）。
     * 默认委托到 [isMet]，需要访问弟子组件表的条件覆盖此方法。
     */
    fun isMet(gameData: GameData, discipleTables: DiscipleTables?): Boolean =
        isMet(gameData)

    /** 进度文本，如"(3/10)" */
    fun progressText(gameData: GameData): String

    /** 当前值 */
    fun currentValue(gameData: GameData): Long

    /**
     * 当前值（带 DiscipleTables 参数）。
     * 默认委托到 [currentValue]，需要访问弟子组件表的条件覆盖此方法。
     */
    fun currentValue(gameData: GameData, discipleTables: DiscipleTables?): Long =
        currentValue(gameData)

    /** 目标值 */
    val targetValue: Long

    /** 建造数量 */
    data class BuildingCount(val buildingDisplayName: String, override val targetValue: Long) : GuideCondition {
        override val label: String get() = "建造${targetValue}座${buildingDisplayName}"
        override fun isMet(gameData: GameData): Boolean =
            currentValue(gameData) >= targetValue
        override fun progressText(gameData: GameData): String =
            "(${currentValue(gameData)}/${targetValue})"
        override fun currentValue(gameData: GameData): Long =
            gameData.placedBuildings.count { it.displayName == buildingDisplayName }.toLong()
    }

    /** 长老已任命 */
    data class ElderAppointed(
        val elderField: String,
        val targetLabel: String,
        override val targetValue: Long = 1
    ) : GuideCondition {
        override val label: String get() = "任命${targetLabel}"
        override fun isMet(gameData: GameData): Boolean =
            currentValue(gameData) >= targetValue
        override fun progressText(gameData: GameData): String =
            if (isMet(gameData)) "(已完成)" else "(未完成)"
        override fun currentValue(gameData: GameData): Long {
            val slots = gameData.elderSlots
            return when (elderField) {
                "viceSectMaster" -> if (slots.viceSectMaster.isNotEmpty()) 1 else 0
                "outerElder" -> if (slots.outerElder.isNotEmpty()) 1 else 0
                "innerElder" -> if (slots.innerElder.isNotEmpty()) 1 else 0
                "preachingElder" -> if (slots.preachingElder.isNotEmpty()) 1 else 0
                "lawEnforcementElder" -> if (slots.lawEnforcementElder.isNotEmpty()) 1 else 0
                "recruitingElder" -> if (slots.recruitingElder.isNotEmpty()) 1 else 0
                "qingyunPreachingElder" -> if (slots.qingyunPreachingElder.isNotEmpty()) 1 else 0
                else -> 0
            }
        }
    }

    /** 亲传弟子/执事已任命（检查 ElterSlots 中的 List<DirectDiscipleSlot>） */
    data class DirectDiscipleActive(
        val slotListField: String,
        val targetLabel: String,
        override val targetValue: Long = 1
    ) : GuideCondition {
        override val label: String get() = "任命${targetValue}位${targetLabel}"
        override fun isMet(gameData: GameData): Boolean =
            currentValue(gameData) >= targetValue
        override fun progressText(gameData: GameData): String =
            "(${currentValue(gameData)}/${targetValue})"
        override fun currentValue(gameData: GameData): Long {
            val slots = gameData.elderSlots
            val list: List<DirectDiscipleSlot> = when (slotListField) {
                "spiritMineDeacon" -> slots.spiritMineDeaconDisciples
                "preachingMasters" -> slots.preachingMasters
                "lawEnforcementDisciples" -> slots.lawEnforcementDisciples
                "qingyunPreachingMasters" -> slots.qingyunPreachingMasters
                else -> emptyList()
            }
            return list.count { it.discipleId.isNotEmpty() }.toLong()
        }
    }

    /** 槽位填充数量（librarySlots / residenceSlots / patrolSlots / warehouseGarrisons / spiritMineSlots） */
    data class SlotFilledCount(
        val slotListField: String,
        override val targetValue: Long,
        val targetLabel: String
    ) : GuideCondition {
        override val label: String get() = "${targetValue}位弟子${targetLabel}"
        override fun isMet(gameData: GameData): Boolean =
            currentValue(gameData) >= targetValue
        override fun progressText(gameData: GameData): String =
            "(${currentValue(gameData)}/${targetValue})"
        override fun currentValue(gameData: GameData): Long = when (slotListField) {
            "librarySlots" -> gameData.librarySlots.count { it.discipleId.isNotEmpty() }.toLong()
            "residenceSlots" -> gameData.residenceSlots.count { it.discipleId.isNotEmpty() }.toLong()
            "patrolSlots" -> gameData.patrolSlots.count { it.discipleId.isNotEmpty() }.toLong()
            "warehouseGarrisons" -> gameData.warehouseGarrisons.count { it.discipleId.isNotEmpty() }.toLong()
            "spiritMineSlots" -> gameData.spiritMineSlots.count { it.discipleId.isNotEmpty() }.toLong()
            else -> 0
        }
    }

    /** 累计计数器（通过 guideCounters 跟踪） */
    data class CumulativeCounter(
        val counterKey: String,
        override val targetValue: Long,
        val targetLabel: String
    ) : GuideCondition {
        override val label: String get() = "累计${targetLabel}达${formatGuideCount(targetValue)}"
        override fun isMet(gameData: GameData): Boolean =
            currentValue(gameData) >= targetValue
        override fun progressText(gameData: GameData): String {
            val cur = currentValue(gameData)
            val fmtCur = formatGuideCount(cur)
            val fmtTarget = formatGuideCount(targetValue)
            return "($fmtCur/$fmtTarget)"
        }
        override fun currentValue(gameData: GameData): Long =
            gameData.guideCounters[counterKey] ?: 0
    }

    /** 已种植过灵植 */
    data object PlantCropOnce : GuideCondition {
        override val label: String get() = "种植1次灵植"
        override val targetValue: Long = 1
        override fun isMet(gameData: GameData): Boolean =
            gameData.spiritFieldPlants.isNotEmpty()
        override fun progressText(gameData: GameData): String =
            if (isMet(gameData)) "(已完成)" else "(未完成)"
        override fun currentValue(gameData: GameData): Long =
            if (gameData.spiritFieldPlants.isNotEmpty()) 1 else 0
    }

    /** 血炼完成次数 */
    data class BloodRefinementCompleted(override val targetValue: Long = 1) : GuideCondition {
        override val label: String get() = "完成${targetValue}次血炼"
        override fun isMet(gameData: GameData): Boolean =
            currentValue(gameData) >= targetValue
        override fun progressText(gameData: GameData): String =
            "(${currentValue(gameData)}/${targetValue})"
        override fun currentValue(gameData: GameData): Long =
            if (gameData.bloodRefinements.isNotEmpty()) gameData.bloodRefinements.size.toLong() else 0
    }

    /** 巡查弟子击败妖兽次数 */
    data class PatrolBeastDefeated(override val targetValue: Long = 1) : GuideCondition {
        override val label: String get() = "巡视弟子击败${targetValue}次妖兽"
        override fun isMet(gameData: GameData): Boolean =
            currentValue(gameData) >= targetValue
        override fun progressText(gameData: GameData): String =
            "(${currentValue(gameData)}/${targetValue})"
        override fun currentValue(gameData: GameData): Long =
            gameData.guideCounters[GuideCounterKeys.PATROL_BEAST_DEFEATED] ?: 0
    }

    /** 弟子总人数 */
    data class DiscipleTotalCount(override val targetValue: Long) : GuideCondition {
        override val label: String get() = "累计招募${targetValue}名弟子"
        override fun isMet(gameData: GameData): Boolean =
            currentValue(gameData) >= targetValue
        override fun progressText(gameData: GameData): String =
            "(${currentValue(gameData)}/${targetValue})"
        override fun currentValue(gameData: GameData): Long =
            gameData.guideCounters[GuideCounterKeys.DISCIPLES_RECRUITED] ?: 0
    }

    /** 任务完成次数 */
    data class MissionCompleted(override val targetValue: Long) : GuideCondition {
        override val label: String get() = "完成${targetValue}个宗门任务"
        override fun isMet(gameData: GameData): Boolean =
            currentValue(gameData) >= targetValue
        override fun progressText(gameData: GameData): String =
            "(${currentValue(gameData)}/${targetValue})"
        override fun currentValue(gameData: GameData): Long =
            gameData.guideCounters[GuideCounterKeys.MISSIONS_COMPLETED] ?: 0
    }

    /** 弟子达到指定境界（realm ≤ maxRealmLayer） */
    data class DiscipleReachRealm(
        val maxRealmLayer: Int,
        override val targetValue: Long,
        val targetLabel: String
    ) : GuideCondition {
        override val label: String get() = "${targetValue}位弟子${targetLabel}"
        override fun isMet(gameData: GameData): Boolean = false
        override fun currentValue(gameData: GameData): Long = 0
        override fun progressText(gameData: GameData): String =
            "(${currentValue(gameData)}/${targetValue})"

        override fun isMet(gameData: GameData, discipleTables: DiscipleTables?): Boolean {
            if (discipleTables == null) return false
            val count = discipleTables.ids.count { id ->
                discipleTables.isAlive.getOrNull(id) ?: 0 == 1 &&
                    (discipleTables.realms.getOrNull(id) ?: Int.MAX_VALUE) <= maxRealmLayer
            }
            return count >= targetValue
        }

        override fun currentValue(gameData: GameData, discipleTables: DiscipleTables?): Long {
            if (discipleTables == null) return 0
            return discipleTables.ids.count { id ->
                discipleTables.isAlive.getOrNull(id) ?: 0 == 1 &&
                    (discipleTables.realms.getOrNull(id) ?: Int.MAX_VALUE) <= maxRealmLayer
            }.toLong()
        }
    }
}

/** 将大数格式化为中文简洁表示：≥10000 显示 "X万"，否则原样显示 */
private fun formatGuideCount(value: Long): String = when {
    value >= 1_0000_0000 -> "${value / 1_0000_0000}亿"
    value >= 10_000 -> "${value / 10_000}万"
    else -> value.toString()
}

// ==================== 任务定义 ====================

data class GuideTask(
    val id: Int,
    val name: String,
    val description: String,
    val conditions: List<GuideCondition>,
    val rewardItemName: String = "凡品储物袋",
    val rewardItemQuantity: Int = 2
)

// ==================== 任务注册表 ====================

object GuideTaskRegistry {
    val ALL_TASKS: List<GuideTask> = listOf(
        GuideTask(
            id = 1, name = "初识灵石",
            description = "灵矿场：每座可容纳3名矿工，基础产出220灵石/月/人。采矿技能越高产出越高。",
            conditions = listOf(
                GuideCondition.BuildingCount("灵矿场", 10),
                GuideCondition.CumulativeCounter(GuideCounterKeys.MINING_OUTPUT, 100_000, "灵矿产出")
            )
        ),
        GuideTask(
            id = 2, name = "灵矿管理",
            description = "灵矿执事：在灵矿场中任命，凭道德修养加成全矿产出。道德越高加成越大。",
            conditions = listOf(
                GuideCondition.DirectDiscipleActive("spiritMineDeacon", "灵矿执事"),
                GuideCondition.CumulativeCounter(GuideCounterKeys.MINING_OUTPUT, 300_000, "灵矿产出")
            )
        ),
        GuideTask(
            id = 3, name = "灵田开垦",
            description = "灵田：1×1格的小型田地，种植灵草幼苗成熟后可收获草药，是炼丹的重要材料来源。",
            conditions = listOf(
                GuideCondition.BuildingCount("灵田", 5),
                GuideCondition.PlantCropOnce
            )
        ),
        GuideTask(
            id = 4, name = "灵植培育",
            description = "灵植阁：灵植弟子在此种植灵草幼苗。范围内（半径6格）的灵田享受生长速度增益，弟子种植技能越高增益越大。",
            conditions = listOf(
                GuideCondition.BuildingCount("灵植阁", 3),
                GuideCondition.CumulativeCounter(GuideCounterKeys.HERBS_HARVESTED, 5, "灵植收获")
            )
        ),
        GuideTask(
            id = 5, name = "丹药炼制",
            description = "炼丹炉：拥有1个生产槽，投入草药炼制丹药。基础成功率70%，可开启自动重新炼制。",
            conditions = listOf(
                GuideCondition.BuildingCount("炼丹炉", 3),
                GuideCondition.CumulativeCounter(GuideCounterKeys.ALCHEMY_COMPLETED, 3, "炼制丹药")
            )
        ),
        GuideTask(
            id = 6, name = "法器锻造",
            description = "锻造坊：拥有1个生产槽，消耗材料锻造法器。基础成功率70%，可开启自动重新锻造。",
            conditions = listOf(
                GuideCondition.BuildingCount("锻造坊", 3),
                GuideCondition.CumulativeCounter(GuideCounterKeys.FORGE_COMPLETED, 3, "锻造装备")
            )
        ),
        GuideTask(
            id = 7, name = "天枢管理",
            description = "天枢殿：宗门中枢建筑，任免所有长老职位、管理宗门政策和自动分配设置。",
            conditions = listOf(
                GuideCondition.BuildingCount("天枢殿", 1),
                GuideCondition.ElderAppointed("viceSectMaster", "副宗主")
            )
        ),
        GuideTask(
            id = 8, name = "自动采矿",
            description = "在天枢殿开启自动分配灵矿后，闲置弟子将自动前往灵矿场开采。",
            conditions = listOf(
                GuideCondition.BuildingCount("天枢殿", 1),
                GuideCondition.CumulativeCounter(GuideCounterKeys.AUTO_MINE_ACTIVATED, 1, "自动采矿开启")
            )
        ),
        GuideTask(
            id = 9, name = "自动种植",
            description = "在天枢殿开启自动分配灵植后，闲置弟子将自动前往灵植阁种植。",
            conditions = listOf(
                GuideCondition.BuildingCount("天枢殿", 1),
                GuideCondition.CumulativeCounter(GuideCounterKeys.AUTO_PLANT_ACTIVATED, 1, "自动种植开启")
            )
        ),
        GuideTask(
            id = 10, name = "自动生产",
            description = "在天枢殿开启自动分配炼丹锻造后，闲置弟子将自动进入生产队列。",
            conditions = listOf(
                GuideCondition.BuildingCount("天枢殿", 1),
                GuideCondition.CumulativeCounter(GuideCounterKeys.AUTO_PRODUCTION_ACTIVATED, 1, "自动生产开启")
            )
        ),
        GuideTask(
            id = 11, name = "宗门政策",
            description = "天枢殿中可启用多项政策，包括：灵矿增产、增强治安、丹道激励、锻造激励、灵药培育、修行津贴、功法研习。",
            conditions = listOf(
                GuideCondition.BuildingCount("天枢殿", 1),
                GuideCondition.CumulativeCounter(GuideCounterKeys.POLICY_ACTIVATED, 1, "政策开启")
            )
        ),
        GuideTask(
            id = 12, name = "功法研习",
            description = "藏经阁：3个研习槽位。弟子入内研习功法可大幅提升功法熟练度增长速度（+50%），提升功法境界。",
            conditions = listOf(
                GuideCondition.BuildingCount("藏经阁", 1),
                GuideCondition.SlotFilledCount("librarySlots", 3, "在藏经阁研习")
            )
        ),
        GuideTask(
            id = 13, name = "宗门律法",
            description = "执法堂：处理弟子偷盗、叛逃等违规行为。执法长老智力越高抓捕率越高。",
            conditions = listOf(
                GuideCondition.BuildingCount("执法堂", 1),
                GuideCondition.ElderAppointed("lawEnforcementElder", "执法长老")
            )
        ),
        GuideTask(
            id = 14, name = "执法亲传",
            description = "执法亲传弟子：协助执法长老执行宗门律法，处理违规弟子事务。",
            conditions = listOf(
                GuideCondition.BuildingCount("执法堂", 1),
                GuideCondition.DirectDiscipleActive("lawEnforcementDisciples", "执法亲传弟子")
            )
        ),
        GuideTask(
            id = 15, name = "宗门任务",
            description = "任务阁：发布探索/押运/镇压等宗门任务，派遣弟子完成后获得灵石、材料等奖励。",
            conditions = listOf(
                GuideCondition.BuildingCount("任务阁", 1),
                GuideCondition.MissionCompleted(3)
            )
        ),
        GuideTask(
            id = 16, name = "外门长老",
            description = "外门长老：坐镇问道塔，管理外门弟子。外门弟子突破时根据长老悟性提供额外领悟加成。",
            conditions = listOf(
                GuideCondition.BuildingCount("问道塔", 1),
                GuideCondition.ElderAppointed("outerElder", "外门长老")
            )
        ),
        GuideTask(
            id = 17, name = "问道传道",
            description = "讲道传道师：在问道塔为外门弟子讲道，提升外门弟子修炼速度。传道师人数越多加成越高。",
            conditions = listOf(
                GuideCondition.BuildingCount("问道塔", 1),
                GuideCondition.DirectDiscipleActive("preachingMasters", "讲道传道师")
            )
        ),
        GuideTask(
            id = 18, name = "内门长老",
            description = "内门长老：坐镇青云塔，管理内门弟子。内门弟子突破时根据长老悟性提供额外领悟加成。",
            conditions = listOf(
                GuideCondition.BuildingCount("青云塔", 1),
                GuideCondition.ElderAppointed("innerElder", "内门长老")
            )
        ),
        GuideTask(
            id = 19, name = "青云传道",
            description = "青云传道师：在青云塔为内门弟子传道，提升内门弟子修炼速度。传道师人数越多加成越高。",
            conditions = listOf(
                GuideCondition.BuildingCount("青云塔", 1),
                GuideCondition.DirectDiscipleActive("qingyunPreachingMasters", "青云传道师")
            )
        ),
        GuideTask(
            id = 20, name = "巡逻除妖",
            description = "巡视楼：最多8个巡视槽位。派遣弟子巡逻后自动攻击地图上的妖兽，击败可获得掉落奖励。",
            conditions = listOf(
                GuideCondition.BuildingCount("巡视楼", 1),
                GuideCondition.PatrolBeastDefeated(1)
            )
        ),
        GuideTask(
            id = 21, name = "安顿住所",
            description = "单人住所：1人/间，修炼速度+20%。可升级为中级单人住所（+40%）。",
            conditions = listOf(
                GuideCondition.BuildingCount("单人住所", 5),
                GuideCondition.SlotFilledCount("residenceSlots", 5, "入住住所")
            )
        ),
        GuideTask(
            id = 22, name = "多人聚居",
            description = "多人住所：4人/间，修炼速度+10%。适合大量弟子集中安置。",
            conditions = listOf(
                GuideCondition.BuildingCount("多人住所", 3),
                GuideCondition.SlotFilledCount("residenceSlots", 12, "入住住所")
            )
        ),
        GuideTask(
            id = 23, name = "仓库管理",
            description = "仓库：每座+75格容量。派遣弟子驻守可防范偷盗。",
            conditions = listOf(
                GuideCondition.BuildingCount("仓库", 3),
                GuideCondition.SlotFilledCount("warehouseGarrisons", 1, "驻守仓库")
            )
        ),
        GuideTask(
            id = 24, name = "血炼强化",
            description = "血炼池：消耗妖兽精血材料淬炼弟子，可永久提升生命/攻击/防御/速度等基础属性。",
            conditions = listOf(
                GuideCondition.BuildingCount("血炼池", 1),
                GuideCondition.BloodRefinementCompleted(1)
            )
        ),
        GuideTask(
            id = 25, name = "监牢惩戒",
            description = "监牢：关押违反门规的弟子。入狱弟子思过期间无法参与任何宗门活动，期满后释放。",
            conditions = listOf(
                GuideCondition.BuildingCount("监牢", 1),
                GuideCondition.CumulativeCounter(GuideCounterKeys.DISCIPLE_IMPRISONED, 1, "弟子入狱")
            )
        )
    )

    fun getTask(id: Int): GuideTask? = ALL_TASKS.find { it.id == id }
}
