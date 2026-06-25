package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.state.MutableGameState

enum class DiscipleDirtyFlag {
    NONE,
    EQUIPMENT,
    MANUAL,
    /** 丹药效果或修炼加速持续中 — 影响修炼速率计算 */
    PILL_EFFECT,
    /** 丧亲状态中 — 影响修炼速率计算 */
    GRIEF
}

/**
 * 修炼速率缓存指纹 — 检测影响修炼速率计算的变化。
 *
 * 检测维度（任一变化 → 缓存重建，纯 Int 比较 O(1)）：
 * - 建筑布局、长老分配、宗门政策（结构性因素）
 * - 弟子境界分布（境界变化 → 修炼速率变化）
 * - 弟子集合变化（招募/死亡 → 新弟子需要初始速率）
 * - 丹药效果/修炼加速/丧亲状态（持续效果开始或结束 → 速率变化）
 * - 功法装备状态（功法熟练度/装备温养 → 速率变化）
 *
 * 不依赖弟子当前修炼值等逐旬变化的动态属性。
 * perDiscipleHash 通过遍历 DiscipleTables 组件列直接计算，无需 assemble，
 * O(n) 增量成本极低（~100弟子 <<1ms）。
 */
data class CultivationRateFingerprint(
    val residenceLayout: Int,
    val elderAssignments: Int,
    val preachingAssignments: Int,
    val policyFlags: Int,
    val aliveDiscipleIdsHash: Int,
    val realmHash: Int,
    val perDiscipleHash: Int
)

class SettlementCache(state: MutableGameState) {

    // ── 轻量映射：O(n) 小集合，直接计算 ──

    val equipmentInstanceMap: Map<String, EquipmentInstance> =
        state.equipmentInstances.associateBy { it.id }

    val manualInstanceMap: Map<String, ManualInstance> =
        state.manualInstances.associateBy { it.id }

    val residenceDiscipleIds: Set<String> =
        state.gameData.residenceSlots.filter { it.isActive }.map { it.discipleId }.toSet()

    val salaryAmountMap: Map<Int, Int> =
        state.gameData.yearlySalary

    val realmConfigCache: Map<Int, GameConfig.RealmConfig> =
        (0..9).associateWith { GameConfig.Realm.get(it) }

    val spiritMineDiscipleIds: Set<String> =
        state.gameData.spiritMineSlots.filter { it.isActive }.map { it.discipleId }.toSet()

    // ── 单次 assemble 共享：所有弟子仅组装一次，供后续所有构建器复用 ──
    // 优化：原本 discipleMap + aliveDisciples + buildDirtyFlags + buildCultivationRateCache
    //       + buildPreachingBonusCache 各自独立组装，共 5 次全量 assemble()
    // 现：一次组装，Map 与 List 共享同一批 Disciple 对象
    // 注意：修炼速率缓存由 SettlementCoordinator 的 CultivationRateFingerprint 机制
    //       控制重建频率——仅在结构变化时重建，每月无条件重建会造成不必要性能损耗。

    private val allDiscipleMap: Map<String, Disciple> =
        state.discipleTables.ids
            .filter { state.discipleTables.isAlive[it] == 1 }
            .associate { it.toString() to state.discipleTables.assemble(it) }

    val discipleMap: Map<String, Disciple> = allDiscipleMap

    val aliveDisciples: List<Disciple> = allDiscipleMap.values.toList()

    val cultivationRateCache: Map<String, Double>

    val preachingBonusCache: Map<String, Pair<Double, Double>>

    val dirtyFlags: Map<String, Set<DiscipleDirtyFlag>>

    val cleanDiscipleIds: Set<String>
    val dirtyDiscipleIds: Set<String>

    init {
        val currentYear = state.gameData.gameYear
        dirtyFlags = buildDirtyFlags(allDiscipleMap, currentYear)
        cleanDiscipleIds = dirtyFlags.filter { DiscipleDirtyFlag.NONE in it.value }.keys
        dirtyDiscipleIds = dirtyFlags.filter { DiscipleDirtyFlag.NONE !in it.value }.keys

        cultivationRateCache = buildCultivationRateCache(state, allDiscipleMap)
        preachingBonusCache = buildPreachingBonusCache(state, allDiscipleMap)
    }

    private fun buildDirtyFlags(
        discipleMap: Map<String, Disciple>,
        currentYear: Int
    ): Map<String, Set<DiscipleDirtyFlag>> {
        return discipleMap.mapValues { (_, d) ->
            val flags = mutableSetOf<DiscipleDirtyFlag>()
            if (d.equipment.hasEquippedItems) flags += DiscipleDirtyFlag.EQUIPMENT
            if (d.manualIds.isNotEmpty()) flags += DiscipleDirtyFlag.MANUAL
            // 丹药效果或修炼加速持续中 → dirty（影响修炼速率计算）
            if (d.cultivationSpeedBonus > 0.0 || d.pillEffects.pillEffectDuration > 0) {
                flags += DiscipleDirtyFlag.PILL_EFFECT
            }
            // 丧亲状态中 → dirty（影响修炼速率计算）
            if (d.social.griefEndYear != null &&
                com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
                    .isGrieving(d.social.griefEndYear!!, currentYear)) {
                flags += DiscipleDirtyFlag.GRIEF
            }
            if (flags.isEmpty()) setOf(DiscipleDirtyFlag.NONE) else flags
        }
    }

    private fun buildCultivationRateCache(
        state: MutableGameState,
        allDiscipleMap: Map<String, Disciple>
    ): Map<String, Double> {
        val data = state.gameData
        val manualInstanceMapLocal = manualInstanceMap
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        return allDiscipleMap.mapValues { (_, disciple) ->
            calculateCultivationRate(
                disciple, data, allDiscipleMap, manualInstanceMapLocal, allProficiencies
            )
        }
    }

    private fun calculateCultivationRate(
        disciple: Disciple,
        data: com.xianxia.sect.core.model.GameData,
        allDisciples: Map<String, Disciple>,
        manualInstanceMapLocal: Map<String, ManualInstance>,
        allProficiencies: Map<String, Map<String, com.xianxia.sect.core.model.ManualProficiencyData>>
    ): Double {
        val buildingBonus = calculateBuildingCultivationBonus(disciple, data)
        val (wenDaoElderBonus, wenDaoMastersBonus) = calculatePreachingBonuses(
            disciple, data, allDisciples, "outer"
        )
        val (qingyunElderBonus, qingyunMastersBonus) = calculatePreachingBonuses(
            disciple, data, allDisciples, "inner"
        )

        var cultivationSubsidyBonus = 0.0
        if (data.sectPolicies.cultivationSubsidy && disciple.realm > 5) {
            cultivationSubsidyBonus = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_BASE_EFFECT
        }

        // 师徒加成：徒弟有师父且师父存活时，按大境界差提供修炼速度加成
        val masterDiscipleBonus = disciple.social.masterId?.let { mid ->
            val master = allDisciples[mid]
            if (master != null && master.isAlive) {
                DiscipleStatCalculator.getMasterDiscipleCultivationBonus(disciple.realm, master.realm)
            } else 0.0
        } ?: 0.0

        val discipleProficiencies = allProficiencies[disciple.id] ?: emptyMap()

        val perSecond = DiscipleStatCalculator.calculateCultivationSpeed(
            disciple = disciple,
            manuals = manualInstanceMapLocal,
            manualProficiencies = discipleProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = wenDaoElderBonus + qingyunElderBonus,
            preachingMastersBonus = wenDaoMastersBonus + qingyunMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus,
            masterDiscipleBonus = masterDiscipleBonus
        ).coerceAtLeast(1.0)
        // calculateCultivationSpeed 已直接返回每旬值，无需再换算
        return perSecond
    }

    private fun calculateBuildingCultivationBonus(
        disciple: Disciple,
        data: com.xianxia.sect.core.model.GameData
    ): Double {
        val slot = data.residenceSlots.firstOrNull { it.discipleId == disciple.id } ?: return 1.0
        val building = data.placedBuildings.firstOrNull { it.instanceId == slot.buildingInstanceId } ?: return 1.0
        return when (building.displayName) {
            "中级单人住所" -> 1.40
            "单人住所" -> 1.20
            "多人住所" -> 1.10
            else -> 1.0
        }
    }

    private fun calculatePreachingBonuses(
        disciple: Disciple,
        data: com.xianxia.sect.core.model.GameData,
        allDisciples: Map<String, Disciple>,
        targetDiscipleType: String
    ): Pair<Double, Double> {
        val elderSlots = data.elderSlots
        val preachingElder = when (targetDiscipleType) {
            "outer" -> elderSlots.preachingElder.let { id -> if (id.isNotEmpty()) allDisciples[id] else null }
            "inner" -> elderSlots.qingyunPreachingElder.let { id -> if (id.isNotEmpty()) allDisciples[id] else null }
            else -> null
        }
        val preachingMasters = when (targetDiscipleType) {
            "outer" -> elderSlots.preachingMasters.mapNotNull { slot -> slot.discipleId?.let { allDisciples[it] } }
            "inner" -> elderSlots.qingyunPreachingMasters.mapNotNull { slot -> slot.discipleId?.let { allDisciples[it] } }
            else -> emptyList()
        }

        return DiscipleStatCalculator.calculatePreachingBonus(
            disciple = disciple,
            targetDiscipleType = targetDiscipleType,
            preachingElder = preachingElder,
            preachingMasters = preachingMasters
        )
    }

    private fun buildPreachingBonusCache(
        state: MutableGameState,
        allDiscipleMap: Map<String, Disciple>
    ): Map<String, Pair<Double, Double>> {
        val data = state.gameData
        return allDiscipleMap.mapValues { (_, disciple) ->
            val (wenDaoElderBonus, wenDaoMastersBonus) = calculatePreachingBonuses(
                disciple, data, allDiscipleMap, "outer"
            )
            val (qingyunElderBonus, qingyunMastersBonus) = calculatePreachingBonuses(
                disciple, data, allDiscipleMap, "inner"
            )
            wenDaoElderBonus + qingyunElderBonus to wenDaoMastersBonus + qingyunMastersBonus
        }
    }
}
