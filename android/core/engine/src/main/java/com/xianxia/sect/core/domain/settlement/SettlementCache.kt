package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.state.MutableGameState

enum class DiscipleDirtyFlag {
    NONE,
    BREAKTHROUGH,
    EQUIPMENT,
    MANUAL
}

data class CultivationRateFingerprint(
    val residenceLayout: Int,
    val elderAssignments: Int,
    val preachingAssignments: Int,
    val policyFlags: Int,
    val aliveDiscipleIdsHash: Int
)

class SettlementCache(state: MutableGameState) {

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

    val cultivationRateCache: Map<String, Double>

    val spiritMineDiscipleIds: Set<String> =
        state.gameData.spiritMineSlots.filter { it.isActive }.map { it.discipleId }.toSet()

    val preachingBonusCache: Map<String, Pair<Double, Double>>

    val dirtyFlags: Map<String, Set<DiscipleDirtyFlag>>

    val discipleMap: Map<String, Disciple> =
        state.discipleTables.ids.filter { state.discipleTables.isAlive[it] == 1 }
            .associate { it.toString() to state.discipleTables.assemble(it) }

    val aliveDisciples: List<Disciple> =
        state.discipleTables.ids.filter { state.discipleTables.isAlive[it] == 1 }
            .map { state.discipleTables.assemble(it) }

    val cleanDiscipleIds: Set<String>
    val dirtyDiscipleIds: Set<String>

    /**
     * 距离突破超过 2 个月的弟子 — 本月跳过结算。
     * 仅当 remainingCultivation / (rate * monthSeconds) > 2 时才跳过。
     * 焦点域强制结算，不受此限制。
     */
    val farFromCompletionIds: Set<String>

    init {
        dirtyFlags = buildDirtyFlags(state)
        cleanDiscipleIds = dirtyFlags.filter { DiscipleDirtyFlag.NONE in it.value }.keys
        dirtyDiscipleIds = dirtyFlags.filter { DiscipleDirtyFlag.NONE !in it.value }.keys

        cultivationRateCache = buildCultivationRateCache(state)
        preachingBonusCache = buildPreachingBonusCache(state)
        farFromCompletionIds = computeFarFromCompletion(state, cultivationRateCache)
    }

    /**
     * 计算距离突破超过 2 个月的弟子集合。
     * remaining / (rate * 3) > 2 → 跳过本月结算，等快满时再月结。
     * rate 为每旬修炼值，每月 3 旬。
     */
    private fun computeFarFromCompletion(
        state: MutableGameState,
        rateCache: Map<String, Double>
    ): Set<String> {
        return state.discipleTables.ids.filter { state.discipleTables.isAlive[it] == 1 }.mapNotNull { id ->
            val d = state.discipleTables.assemble(id)
            val rate = rateCache[d.id] ?: return@mapNotNull null
            val remaining = d.maxCultivation - d.cultivation
            if (remaining <= 0) return@mapNotNull null  // 已满，必须结算
            val monthsToFull = if (rate > 0) remaining / (rate * 3) else 999.0
            if (monthsToFull > 2.0) d.id else null
        }.toSet()
    }

    private fun buildDirtyFlags(state: MutableGameState): Map<String, Set<DiscipleDirtyFlag>> {
        return state.discipleTables.ids.filter { state.discipleTables.isAlive[it] == 1 }.associate { id ->
            val d = state.discipleTables.assemble(id)
            val flags = mutableSetOf<DiscipleDirtyFlag>()
            if (d.cultivation >= d.maxCultivation * 0.8) flags += DiscipleDirtyFlag.BREAKTHROUGH
            if (d.equipment.hasEquippedItems) flags += DiscipleDirtyFlag.EQUIPMENT
            if (d.manualIds.isNotEmpty()) flags += DiscipleDirtyFlag.MANUAL
            d.id to (if (flags.isEmpty()) setOf(DiscipleDirtyFlag.NONE) else flags)
        }
    }

    private fun buildCultivationRateCache(state: MutableGameState): Map<String, Double> {
        val data = state.gameData
        val allDisciples = state.discipleTables.ids.filter { state.discipleTables.isAlive[it] == 1 }
            .associate { it.toString() to state.discipleTables.assemble(it) }
        val manualInstanceMapLocal = manualInstanceMap
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        return allDisciples.values.associate { disciple ->
            val rate = calculateCultivationRate(
                disciple, data, allDisciples, manualInstanceMapLocal, allProficiencies
            )
            disciple.id to rate
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

    private fun buildPreachingBonusCache(state: MutableGameState): Map<String, Pair<Double, Double>> {
        val data = state.gameData
        val allDisciples = state.discipleTables.ids.filter { state.discipleTables.isAlive[it] == 1 }
            .associate { it.toString() to state.discipleTables.assemble(it) }
        return allDisciples.values.associate { disciple ->
            val (wenDaoElderBonus, wenDaoMastersBonus) = calculatePreachingBonuses(
                disciple, data, allDisciples, "outer"
            )
            val (qingyunElderBonus, qingyunMastersBonus) = calculatePreachingBonuses(
                disciple, data, allDisciples, "inner"
            )
            disciple.id to (wenDaoElderBonus + qingyunElderBonus to wenDaoMastersBonus + qingyunMastersBonus)
        }
    }
}
