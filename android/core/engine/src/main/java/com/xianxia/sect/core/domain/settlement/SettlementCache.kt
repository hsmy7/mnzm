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
    MANUAL
}

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
    // 优化前：discipleMap + aliveDisciples + buildDirtyFlags + buildCultivationRateCache
    //         + buildPreachingBonusCache 各自独立组装，共 5 次全量 assemble()
    // 优化后：一次组装，Map 与 List 共享同一批 Disciple 对象
    // 移除指纹缓存（computeFingerprint/CultivationRateFingerprint）的原因：
    //   指纹仅检测结构变化（布局/长老/政策/弟子ID），不检测弟子属性变化。
    //   弟子修炼值增长会改变修炼速率计算，但指纹不会变化 → 旧缓存返回过期数据。
    //   月度 SettlementCache 重建成本：~100弟子 × 1次assemble ≈ 63K 次SparseArray查找，
    //   在月度批次中 << 1ms，不影响帧率。

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
        dirtyFlags = buildDirtyFlags(allDiscipleMap)
        cleanDiscipleIds = dirtyFlags.filter { DiscipleDirtyFlag.NONE in it.value }.keys
        dirtyDiscipleIds = dirtyFlags.filter { DiscipleDirtyFlag.NONE !in it.value }.keys

        cultivationRateCache = buildCultivationRateCache(state, allDiscipleMap)
        preachingBonusCache = buildPreachingBonusCache(state, allDiscipleMap)
    }

    private fun buildDirtyFlags(
        discipleMap: Map<String, Disciple>
    ): Map<String, Set<DiscipleDirtyFlag>> {
        return discipleMap.mapValues { (_, d) ->
            val flags = mutableSetOf<DiscipleDirtyFlag>()
            if (d.equipment.hasEquippedItems) flags += DiscipleDirtyFlag.EQUIPMENT
            if (d.manualIds.isNotEmpty()) flags += DiscipleDirtyFlag.MANUAL
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

        val discipleProficiencies = allProficiencies[disciple.id] ?: emptyMap()

        val perSecond = DiscipleStatCalculator.calculateCultivationSpeed(
            disciple = disciple,
            manuals = manualInstanceMapLocal,
            manualProficiencies = discipleProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = wenDaoElderBonus + qingyunElderBonus,
            preachingMastersBonus = wenDaoMastersBonus + qingyunMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus
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
