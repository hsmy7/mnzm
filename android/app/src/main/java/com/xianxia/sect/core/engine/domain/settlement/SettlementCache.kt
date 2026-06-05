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

class SettlementCache(state: MutableGameState) {

    val equipmentInstanceMap: Map<String, EquipmentInstance> =
        state.equipmentInstances.associateBy { it.id }

    val manualInstanceMap: Map<String, ManualInstance> =
        state.manualInstances.associateBy { it.id }

    val residenceDiscipleIds: Set<String> =
        state.gameData.residenceSlots.filter { it.isActive }.map { it.discipleId }.toSet()

    val salaryAmountMap: Map<Int, Int> =
        state.gameData.monthlySalary

    val realmConfigCache: Map<Int, GameConfig.RealmConfig> =
        (0..9).associateWith { GameConfig.Realm.get(it) }

    val cultivationRateCache: Map<String, Double>

    val spiritMineDiscipleIds: Set<String> =
        state.gameData.spiritMineSlots.filter { it.isActive }.map { it.discipleId }.toSet()

    val preachingBonusCache: Map<String, Pair<Double, Double>>

    val dirtyFlags: Map<String, Set<DiscipleDirtyFlag>>

    val discipleMap: Map<String, Disciple> =
        state.disciples.filter { it.isAlive }.associateBy { it.id }

    val aliveDisciples: List<Disciple> =
        state.disciples.filter { it.isAlive }

    val cleanDiscipleIds: Set<String>
    val dirtyDiscipleIds: Set<String>

    init {
        dirtyFlags = buildDirtyFlags(state)
        cleanDiscipleIds = dirtyFlags.filter { DiscipleDirtyFlag.NONE in it.value }.keys
        dirtyDiscipleIds = dirtyFlags.filter { DiscipleDirtyFlag.NONE !in it.value }.keys

        cultivationRateCache = buildCultivationRateCache(state)
        preachingBonusCache = buildPreachingBonusCache(state)
    }

    private fun buildDirtyFlags(state: MutableGameState): Map<String, Set<DiscipleDirtyFlag>> {
        return state.disciples.filter { it.isAlive }.associate { d ->
            val flags = mutableSetOf<DiscipleDirtyFlag>()
            if (d.cultivation >= d.maxCultivation * 0.8) flags += DiscipleDirtyFlag.BREAKTHROUGH
            if (d.equipment.hasEquippedItems) flags += DiscipleDirtyFlag.EQUIPMENT
            if (d.manualIds.isNotEmpty()) flags += DiscipleDirtyFlag.MANUAL
            d.id to (if (flags.isEmpty()) setOf(DiscipleDirtyFlag.NONE) else flags)
        }
    }

    private fun buildCultivationRateCache(state: MutableGameState): Map<String, Double> {
        val data = state.gameData
        val allDisciples = state.disciples.filter { it.isAlive }.associateBy { it.id }
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

        return DiscipleStatCalculator.calculateCultivationSpeed(
            disciple = disciple,
            manuals = manualInstanceMapLocal,
            manualProficiencies = discipleProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = wenDaoElderBonus + qingyunElderBonus,
            preachingMastersBonus = wenDaoMastersBonus + qingyunMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus
        ).coerceIn(1.0, 1000.0)
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
        val allDisciples = state.disciples.filter { it.isAlive }.associateBy { it.id }
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
