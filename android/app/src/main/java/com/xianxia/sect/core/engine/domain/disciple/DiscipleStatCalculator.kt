package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.engine.ManualProficiencySystem
import kotlin.math.roundToInt

object DiscipleStatCalculator {

    fun getBaseStats(disciple: Disciple): DiscipleStats {
        val realmConfig = GameConfig.Realm.get(disciple.realm)
        val layerMult = 1.0 + (disciple.realmLayer - 1) * 0.1
        val c = disciple.combat

        val talentEffects = getTalentEffects(disciple)
        val hpBonus = talentEffects["maxHp"] ?: 0.0
        val mpBonus = talentEffects["maxMp"] ?: 0.0
        val attackBonus = talentEffects["physicalAttack"] ?: 0.0
        val magicAttackBonus = talentEffects["magicAttack"] ?: 0.0
        val defenseBonus = talentEffects["physicalDefense"] ?: 0.0
        val magicDefenseBonus = talentEffects["magicDefense"] ?: 0.0
        val speedBonus = talentEffects["speed"] ?: 0.0
        val critBonus = talentEffects["critRate"] ?: 0.0
        val intelligenceFlat = (talentEffects["intelligenceFlat"] ?: 0.0).toInt()
        val charmFlat = (talentEffects["charmFlat"] ?: 0.0).toInt()
        val loyaltyFlat = (talentEffects["loyaltyFlat"] ?: 0.0).toInt()
        val comprehensionFlat = (talentEffects["comprehensionFlat"] ?: 0.0).toInt()
        val teachingFlat = (talentEffects["teachingFlat"] ?: 0.0).toInt()
        val moralityFlat = (talentEffects["moralityFlat"] ?: 0.0).toInt()
        val miningFlat = (talentEffects["miningFlat"] ?: 0.0).toInt()

        val hpVar = 1.0 + c.hpVariance / 100.0
        val mpVar = 1.0 + c.mpVariance / 100.0
        val paVar = 1.0 + c.physicalAttackVariance / 100.0
        val maVar = 1.0 + c.magicAttackVariance / 100.0
        val pdVar = 1.0 + c.physicalDefenseVariance / 100.0
        val mdVar = 1.0 + c.magicDefenseVariance / 100.0
        val spdVar = 1.0 + c.speedVariance / 100.0

        val base = DiscipleStats(
            hp = (realmConfig.baseHp * hpVar * layerMult * (1.0 + hpBonus)).roundToInt(),
            maxHp = (realmConfig.baseHp * hpVar * layerMult * (1.0 + hpBonus)).roundToInt(),
            mp = (realmConfig.baseMp * mpVar * layerMult * (1.0 + mpBonus)).roundToInt(),
            maxMp = (realmConfig.baseMp * mpVar * layerMult * (1.0 + mpBonus)).roundToInt(),
            physicalAttack = (realmConfig.basePhysicalAttack * paVar * layerMult * (1.0 + attackBonus)).roundToInt(),
            magicAttack = (realmConfig.baseMagicAttack * maVar * layerMult * (1.0 + magicAttackBonus)).roundToInt(),
            physicalDefense = (realmConfig.basePhysicalDefense * pdVar * layerMult * (1.0 + defenseBonus)).roundToInt(),
            magicDefense = (realmConfig.baseMagicDefense * mdVar * layerMult * (1.0 + magicDefenseBonus)).roundToInt(),
            speed = (realmConfig.baseSpeed * spdVar * layerMult * (1.0 + speedBonus)).roundToInt(),
            critRate = 0.05 + critBonus,
            intelligence = disciple.skills.intelligence + intelligenceFlat,
            charm = disciple.skills.charm + charmFlat,
            loyalty = disciple.skills.loyalty + loyaltyFlat,
            comprehension = disciple.skills.comprehension + comprehensionFlat,
            teaching = disciple.skills.teaching + teachingFlat,
            morality = disciple.skills.morality + moralityFlat,
            mining = disciple.skills.mining + miningFlat
        )

        return base
    }

    fun getBaseStats(aggregate: DiscipleAggregate): DiscipleStats {
        val realmConfig = GameConfig.Realm.get(aggregate.realm)
        val layerMult = 1.0 + (aggregate.realmLayer - 1) * 0.1
        val cs = aggregate.combatStats
        val hpVar = if (cs != null) 1.0 + cs.hpVariance / 100.0 else 1.0
        val mpVar = if (cs != null) 1.0 + cs.mpVariance / 100.0 else 1.0
        val paVar = if (cs != null) 1.0 + cs.physicalAttackVariance / 100.0 else 1.0
        val maVar = if (cs != null) 1.0 + cs.magicAttackVariance / 100.0 else 1.0
        val pdVar = if (cs != null) 1.0 + cs.physicalDefenseVariance / 100.0 else 1.0
        val mdVar = if (cs != null) 1.0 + cs.magicDefenseVariance / 100.0 else 1.0
        val spdVar = if (cs != null) 1.0 + cs.speedVariance / 100.0 else 1.0

        val talentEffects = getTalentEffects(aggregate)
        val hpBonus = talentEffects["maxHp"] ?: 0.0
        val mpBonus = talentEffects["maxMp"] ?: 0.0
        val attackBonus = talentEffects["physicalAttack"] ?: 0.0
        val magicAttackBonus = talentEffects["magicAttack"] ?: 0.0
        val defenseBonus = talentEffects["physicalDefense"] ?: 0.0
        val magicDefenseBonus = talentEffects["magicDefense"] ?: 0.0
        val speedBonus = talentEffects["speed"] ?: 0.0
        val critBonus = talentEffects["critRate"] ?: 0.0
        val intelligenceFlat = (talentEffects["intelligenceFlat"] ?: 0.0).toInt()
        val charmFlat = (talentEffects["charmFlat"] ?: 0.0).toInt()
        val loyaltyFlat = (talentEffects["loyaltyFlat"] ?: 0.0).toInt()
        val comprehensionFlat = (talentEffects["comprehensionFlat"] ?: 0.0).toInt()
        val teachingFlat = (talentEffects["teachingFlat"] ?: 0.0).toInt()
        val moralityFlat = (talentEffects["moralityFlat"] ?: 0.0).toInt()
        val miningFlat = (talentEffects["miningFlat"] ?: 0.0).toInt()

        val attr = aggregate.attributes
        return DiscipleStats(
            hp = (realmConfig.baseHp * hpVar * layerMult * (1.0 + hpBonus)).roundToInt(),
            maxHp = (realmConfig.baseHp * hpVar * layerMult * (1.0 + hpBonus)).roundToInt(),
            mp = (realmConfig.baseMp * mpVar * layerMult * (1.0 + mpBonus)).roundToInt(),
            maxMp = (realmConfig.baseMp * mpVar * layerMult * (1.0 + mpBonus)).roundToInt(),
            physicalAttack = (realmConfig.basePhysicalAttack * paVar * layerMult * (1.0 + attackBonus)).roundToInt(),
            magicAttack = (realmConfig.baseMagicAttack * maVar * layerMult * (1.0 + magicAttackBonus)).roundToInt(),
            physicalDefense = (realmConfig.basePhysicalDefense * pdVar * layerMult * (1.0 + defenseBonus)).roundToInt(),
            magicDefense = (realmConfig.baseMagicDefense * mdVar * layerMult * (1.0 + magicDefenseBonus)).roundToInt(),
            speed = (realmConfig.baseSpeed * spdVar * layerMult * (1.0 + speedBonus)).roundToInt(),
            critRate = 0.05 + critBonus,
            intelligence = (attr?.intelligence ?: 50) + intelligenceFlat,
            charm = (attr?.charm ?: 50) + charmFlat,
            loyalty = (attr?.loyalty ?: 50) + loyaltyFlat,
            comprehension = (attr?.comprehension ?: 50) + comprehensionFlat,
            teaching = (attr?.teaching ?: 50) + teachingFlat,
            morality = (attr?.morality ?: 50) + moralityFlat,
            mining = (attr?.mining ?: 50) + miningFlat
        )
    }

    fun getTalentEffects(disciple: Disciple): Map<String, Double> {
        val effects = mutableMapOf<String, Double>()
        val talents = TalentDatabase.getTalentsByIds(disciple.talentIds)
        talents.forEach { talent ->
            talent.effects.forEach { (key, value) ->
                effects[key] = (effects[key] ?: 0.0) + value
            }
        }
        return effects
    }

    fun getTalentEffects(aggregate: DiscipleAggregate): Map<String, Double> {
        val effects = mutableMapOf<String, Double>()
        val talents = TalentDatabase.getTalentsByIds(aggregate.talentIds)
        talents.forEach { talent ->
            talent.effects.forEach { (key, value) ->
                effects[key] = (effects[key] ?: 0.0) + value
            }
        }
        return effects
    }

    fun getStatsWithEquipment(
        disciple: Disciple,
        equipments: Map<String, EquipmentInstance>
    ): DiscipleStats {
        val base = getBaseStats(disciple)
        var total = base
        var totalCritChance = 0.0

        listOfNotNull(
            disciple.equipment.weaponId,
            disciple.equipment.armorId,
            disciple.equipment.bootsId,
            disciple.equipment.accessoryId
        ).forEach { equipId ->
            val equipment = equipments[equipId]
            if (equipment != null) {
                equipment.getFinalStats().toDiscipleStats().let { total = total + it }
                totalCritChance += equipment.critChance
            }
        }

        return total.copy(critRate = total.critRate + totalCritChance)
    }

    fun getStatsWithEquipment(
        aggregate: DiscipleAggregate,
        equipments: Map<String, EquipmentInstance>
    ): DiscipleStats {
        val base = getBaseStats(aggregate)
        var total = base
        var totalCritChance = 0.0

        val eq = aggregate.equipment
        listOfNotNull(
            eq?.weaponId, eq?.armorId, eq?.bootsId, eq?.accessoryId
        ).filter { it.isNotEmpty() }.forEach { equipId ->
            val equipment = equipments[equipId]
            if (equipment != null) {
                equipment.getFinalStats().toDiscipleStats().let { total = total + it }
                totalCritChance += equipment.critChance
            }
        }

        return total.copy(critRate = total.critRate + totalCritChance)
    }

    fun getFinalStats(
        disciple: Disciple,
        equipments: Map<String, EquipmentInstance>,
        manuals: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): DiscipleStats {
        val baseStats = getBaseStats(disciple)
        var total = baseStats
        var totalCritRate = total.critRate

        listOfNotNull(
            disciple.equipment.weaponId,
            disciple.equipment.armorId,
            disciple.equipment.bootsId,
            disciple.equipment.accessoryId
        ).forEach { equipId ->
            val equipment = equipments[equipId]
            if (equipment != null) {
                equipment.getFinalStats().toDiscipleStats().let { total = total + it }
                totalCritRate += equipment.critChance
            }
        }

        disciple.manualIds.forEach { manualId ->
            val manual = manuals[manualId]
            if (manual != null) {
                val proficiencyData = manualProficiencies[manualId]
                val masteryLevel = proficiencyData?.masteryLevel ?: 0
                val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus

                val hpValue = manual.stats["hp"] ?: manual.stats["maxHp"] ?: 0
                val mpValue = manual.stats["mp"] ?: manual.stats["maxMp"] ?: 0
                val manualStats = DiscipleStats(
                    hp = (hpValue * masteryBonus).toInt(),
                    maxHp = (hpValue * masteryBonus).toInt(),
                    mp = (mpValue * masteryBonus).toInt(),
                    maxMp = (mpValue * masteryBonus).toInt(),
                    physicalAttack = ((manual.stats["physicalAttack"] ?: 0) * masteryBonus).toInt(),
                    magicAttack = ((manual.stats["magicAttack"] ?: 0) * masteryBonus).toInt(),
                    physicalDefense = ((manual.stats["physicalDefense"] ?: 0) * masteryBonus).toInt(),
                    magicDefense = ((manual.stats["magicDefense"] ?: 0) * masteryBonus).toInt(),
                    speed = ((manual.stats["speed"] ?: 0) * masteryBonus).toInt(),
                    critRate = 1.0
                )
                total = total + manualStats
                totalCritRate += ((manual.stats["critRate"] ?: 0) * masteryBonus) / 100.0
            }
        }

        if (disciple.pillEffects.pillEffectDuration > 0) {
            val pe = disciple.pillEffects
            val pillBonus = DiscipleStats(
                hp = pe.pillHpBonus,
                maxHp = pe.pillHpBonus,
                mp = pe.pillMpBonus,
                maxMp = pe.pillMpBonus,
                physicalAttack = pe.pillPhysicalAttackBonus,
                magicAttack = pe.pillMagicAttackBonus,
                physicalDefense = pe.pillPhysicalDefenseBonus,
                magicDefense = pe.pillMagicDefenseBonus,
                speed = pe.pillSpeedBonus,
                critRate = pe.pillCritRateBonus
            )
            total = total + pillBonus
            totalCritRate += pe.pillCritRateBonus
        }

        return total.copy(critRate = totalCritRate)
    }

    fun getFinalStats(
        aggregate: DiscipleAggregate,
        equipments: Map<String, EquipmentInstance>,
        manuals: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): DiscipleStats {
        val baseStats = getBaseStats(aggregate)
        var total = baseStats
        var totalCritRate = total.critRate

        val eq = aggregate.equipment
        listOfNotNull(
            eq?.weaponId, eq?.armorId, eq?.bootsId, eq?.accessoryId
        ).filter { it.isNotEmpty() }.forEach { equipId ->
            val equipment = equipments[equipId]
            if (equipment != null) {
                equipment.getFinalStats().toDiscipleStats().let { total = total + it }
                totalCritRate += equipment.critChance
            }
        }

        aggregate.manualIds.forEach { manualId ->
            val manual = manuals[manualId]
            if (manual != null) {
                val proficiencyData = manualProficiencies[manualId]
                val masteryLevel = proficiencyData?.masteryLevel ?: 0
                val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus

                val hpValue = manual.stats["hp"] ?: manual.stats["maxHp"] ?: 0
                val mpValue = manual.stats["mp"] ?: manual.stats["maxMp"] ?: 0
                val manualStats = DiscipleStats(
                    hp = (hpValue * masteryBonus).toInt(),
                    maxHp = (hpValue * masteryBonus).toInt(),
                    mp = (mpValue * masteryBonus).toInt(),
                    maxMp = (mpValue * masteryBonus).toInt(),
                    physicalAttack = ((manual.stats["physicalAttack"] ?: 0) * masteryBonus).toInt(),
                    magicAttack = ((manual.stats["magicAttack"] ?: 0) * masteryBonus).toInt(),
                    physicalDefense = ((manual.stats["physicalDefense"] ?: 0) * masteryBonus).toInt(),
                    magicDefense = ((manual.stats["magicDefense"] ?: 0) * masteryBonus).toInt(),
                    speed = ((manual.stats["speed"] ?: 0) * masteryBonus).toInt(),
                    critRate = 1.0
                )
                total = total + manualStats
                totalCritRate += ((manual.stats["critRate"] ?: 0) * masteryBonus) / 100.0
            }
        }

        val cs = aggregate.combatStats
        if (cs != null && cs.pillEffectDuration > 0) {
            val pillBonus = DiscipleStats(
                hp = cs.pillHpBonus,
                maxHp = cs.pillHpBonus,
                mp = cs.pillMpBonus,
                maxMp = cs.pillMpBonus,
                physicalAttack = cs.pillPhysicalAttackBonus,
                magicAttack = cs.pillMagicAttackBonus,
                physicalDefense = cs.pillPhysicalDefenseBonus,
                magicDefense = cs.pillMagicDefenseBonus,
                speed = cs.pillSpeedBonus,
                critRate = cs.pillCritRateBonus
            )
            total = total + pillBonus
            totalCritRate += cs.pillCritRateBonus
        }

        return total.copy(critRate = totalCritRate)
    }

    fun calculateCultivationSpeed(
        disciple: Disciple,
        manuals: Map<String, ManualInstance> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        buildingBonus: Double = 1.0,
        additionalBonus: Double = 0.0,
        preachingElderBonus: Double = 0.0,
        preachingMastersBonus: Double = 0.0,
        cultivationSubsidyBonus: Double = 0.0,
        parentCultivationBonus: Double = 0.0,
        griefCultivationSpeedPenalty: Double = 0.0
    ): Double {
        val baseCultivation = disciple.spiritRoot.cultivationBonus

        var totalBonus = 0.0

        totalBonus += (disciple.skills.comprehensionSpeedBonus - 1.0)

        if (manuals.isNotEmpty()) {
            disciple.manualIds.forEach { manualId ->
                val manual = manuals[manualId]
                if (manual != null) {
                    val proficiencyData = manualProficiencies[manualId]
                    val masteryLevel = proficiencyData?.masteryLevel ?: 0
                    val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
                    totalBonus += manual.cultivationSpeedPercent * masteryBonus / 100.0
                }
            }
        } else if (disciple.manualIds.isNotEmpty()) {
            disciple.manualIds.forEach { manualId ->
                val manual = ManualDatabase.getById(manualId)
                if (manual != null) {
                    val proficiencyData = manualProficiencies[manualId]
                    val masteryLevel = proficiencyData?.masteryLevel ?: 0
                    val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
                    totalBonus += (manual.stats["cultivationSpeedPercent"] ?: 0) * masteryBonus / 100.0
                }
            }
        }

        val talentEffects = getTalentEffects(disciple)
        totalBonus += (talentEffects["cultivationSpeed"] ?: 0.0)

        totalBonus += (buildingBonus - 1.0)

        totalBonus += additionalBonus

        totalBonus += preachingElderBonus
        totalBonus += preachingMastersBonus
        totalBonus += cultivationSubsidyBonus

        if (disciple.cultivationSpeedDuration > 0 && disciple.cultivationSpeedBonus > 0.0) {
            totalBonus += disciple.cultivationSpeedBonus
        }

        // 父母灵根对子嗣修炼速度的影响（仅存活时影响）
        totalBonus += parentCultivationBonus

        // 亲人逝世对修炼速度的影响
        totalBonus -= griefCultivationSpeedPenalty

        return (baseCultivation * (1.0 + totalBonus)).coerceAtLeast(1.0)
    }

    fun calculateCultivationSpeed(
        aggregate: DiscipleAggregate,
        manuals: Map<String, ManualInstance> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        buildingBonus: Double = 1.0,
        additionalBonus: Double = 0.0,
        preachingElderBonus: Double = 0.0,
        preachingMastersBonus: Double = 0.0,
        cultivationSubsidyBonus: Double = 0.0,
        parentCultivationBonus: Double = 0.0,
        griefCultivationSpeedPenalty: Double = 0.0
    ): Double {
        val baseCultivation = aggregate.spiritRoot.cultivationBonus

        var totalBonus = 0.0

        totalBonus += (aggregate.comprehensionSpeedBonus - 1.0)

        val manualIds = aggregate.manualIds
        if (manuals.isNotEmpty()) {
            manualIds.forEach { manualId ->
                val manual = manuals[manualId]
                if (manual != null) {
                    val proficiencyData = manualProficiencies[manualId]
                    val masteryLevel = proficiencyData?.masteryLevel ?: 0
                    val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
                    totalBonus += manual.cultivationSpeedPercent * masteryBonus / 100.0
                }
            }
        } else if (manualIds.isNotEmpty()) {
            manualIds.forEach { manualId ->
                val manual = ManualDatabase.getById(manualId)
                if (manual != null) {
                    val proficiencyData = manualProficiencies[manualId]
                    val masteryLevel = proficiencyData?.masteryLevel ?: 0
                    val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
                    totalBonus += (manual.stats["cultivationSpeedPercent"] ?: 0) * masteryBonus / 100.0
                }
            }
        }

        val talentEffects = getTalentEffects(aggregate)
        totalBonus += (talentEffects["cultivationSpeed"] ?: 0.0)

        totalBonus += (buildingBonus - 1.0)

        totalBonus += additionalBonus

        totalBonus += preachingElderBonus
        totalBonus += preachingMastersBonus
        totalBonus += cultivationSubsidyBonus

        val ext = aggregate.extended
        if (ext != null && ext.cultivationSpeedDuration > 0 && ext.cultivationSpeedBonus > 0.0) {
            totalBonus += ext.cultivationSpeedBonus
        }

        // 父母灵根对子嗣修炼速度的影响（仅存活时影响）
        totalBonus += parentCultivationBonus

        // 亲人逝世对修炼速度的影响
        totalBonus -= griefCultivationSpeedPenalty

        return (baseCultivation * (1.0 + totalBonus)).coerceAtLeast(1.0)
    }

    fun getBreakthroughChance(
        disciple: Disciple,
        innerElderComprehension: Int = 0,
        outerElderComprehensionBonus: Double = 0.0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0
    ): Double {
        if (disciple.realm < 0) return 0.0

        val rootCount = disciple.spiritRoot.types.size
        val baseChance = GameConfig.Realm.getBreakthroughChance(disciple.realm, rootCount, disciple.realmLayer)

        val innerElderBonus = if (innerElderComprehension >= 80) {
            ((innerElderComprehension - 80) / 4) * 0.01
        } else {
            0.0
        }

        val talentEffects = getTalentEffects(disciple)
        val talentBreakthroughBonus = talentEffects["breakthroughChance"] ?: 0.0

        val soulPowerBonus = getSoulPowerBreakthroughBonus(disciple.soulPower)

        val totalBonus = innerElderBonus + outerElderComprehensionBonus + pillBonus + talentBreakthroughBonus + soulPowerBonus + adBonus - griefBreakthroughPenalty
        return (baseChance + totalBonus).coerceIn(0.0, 1.0)
    }

    fun getBreakthroughChance(
        aggregate: DiscipleAggregate,
        innerElderComprehension: Int = 0,
        outerElderComprehensionBonus: Double = 0.0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0
    ): Double {
        if (aggregate.realm < 0) return 0.0

        val rootCount = aggregate.spiritRoot.types.size
        val baseChance = GameConfig.Realm.getBreakthroughChance(aggregate.realm, rootCount, aggregate.realmLayer)

        val innerElderBonus = if (innerElderComprehension >= 80) {
            ((innerElderComprehension - 80) / 4) * 0.01
        } else {
            0.0
        }

        val talentEffects = getTalentEffects(aggregate)
        val talentBreakthroughBonus = talentEffects["breakthroughChance"] ?: 0.0

        val soulPowerBonus = getSoulPowerBreakthroughBonus(aggregate.soulPower)

        val totalBonus = innerElderBonus + outerElderComprehensionBonus + pillBonus + talentBreakthroughBonus + soulPowerBonus + adBonus - griefBreakthroughPenalty
        return (baseChance + totalBonus).coerceIn(0.0, 1.0)
    }

    fun getSoulPowerBreakthroughBonus(soulPower: Int): Double {
        return ((soulPower / 20).coerceAtMost(5)) / 100.0
    }

    data class BreakthroughBonusDetail(
        val baseChance: Double,
        val innerElderBonus: Double,
        val outerElderBonus: Double,
        val talentBonus: Double,
        val soulPowerBonus: Double,
        val pillBonus: Double,
        val adBonus: Double,
        val griefPenalty: Double,
        val total: Double
    )

    fun getBreakthroughBonusDetail(
        aggregate: DiscipleAggregate,
        innerElderComprehension: Int = 0,
        outerElderComprehensionBonus: Double = 0.0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0
    ): BreakthroughBonusDetail {
        if (aggregate.realm < 0) return BreakthroughBonusDetail(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val rootCount = aggregate.spiritRoot.types.size
        val baseChance = GameConfig.Realm.getBreakthroughChance(aggregate.realm, rootCount, aggregate.realmLayer)
        val innerElderBonus = if (innerElderComprehension >= 80) ((innerElderComprehension - 80) / 4) * 0.01 else 0.0
        val talentEffects = getTalentEffects(aggregate)
        val talentBonus = talentEffects["breakthroughChance"] ?: 0.0
        val soulPowerBonus = getSoulPowerBreakthroughBonus(aggregate.soulPower)
        val total = baseChance + innerElderBonus + outerElderComprehensionBonus + pillBonus + talentBonus + soulPowerBonus + adBonus - griefBreakthroughPenalty
        return BreakthroughBonusDetail(
            baseChance = baseChance,
            innerElderBonus = innerElderBonus,
            outerElderBonus = outerElderComprehensionBonus,
            talentBonus = talentBonus,
            soulPowerBonus = soulPowerBonus,
            pillBonus = pillBonus,
            adBonus = adBonus,
            griefPenalty = griefBreakthroughPenalty,
            total = total.coerceIn(0.0, 1.0)
        )
    }

    fun getMaxManualSlots(disciple: Disciple): Int {
        val talentEffects = getTalentEffects(disciple)
        val manualSlotBonus = talentEffects["manualSlot"]?.toInt() ?: 0
        return 6 + manualSlotBonus
    }

    fun getMaxManualSlots(aggregate: DiscipleAggregate): Int {
        val talentEffects = getTalentEffects(aggregate)
        val manualSlotBonus = talentEffects["manualSlot"]?.toInt() ?: 0
        return 6 + manualSlotBonus
    }

    fun calculatePreachingBonus(
        disciple: Disciple,
        targetDiscipleType: String,
        preachingElder: Disciple?,
        preachingMasters: List<Disciple>
    ): Pair<Double, Double> {
        if (disciple.discipleType != targetDiscipleType) return 0.0 to 0.0

        var elderBonus = 0.0
        var mastersBonus = 0.0

        if (preachingElder != null && preachingElder.isAlive) {
            val elderTeaching = getBaseStats(preachingElder).teaching
            if (disciple.realm >= preachingElder.realm && elderTeaching >= 80) {
                elderBonus = (elderTeaching - 80) * 0.01
            }
        }

        preachingMasters.filter { it.isAlive }.forEach { master ->
            val masterTeaching = getBaseStats(master).teaching
            if (disciple.realm >= master.realm && masterTeaching >= 80) {
                mastersBonus += (masterTeaching - 80) * 0.005
            }
        }

        return elderBonus to mastersBonus
    }

    fun calculatePreachingBonus(
        aggregate: DiscipleAggregate,
        targetDiscipleType: String,
        preachingElder: Disciple?,
        preachingMasters: List<Disciple>
    ): Pair<Double, Double> {
        if (aggregate.discipleType != targetDiscipleType) return 0.0 to 0.0

        var elderBonus = 0.0
        var mastersBonus = 0.0

        if (preachingElder != null && preachingElder.isAlive) {
            val elderTeaching = getBaseStats(preachingElder).teaching
            if (aggregate.realm >= preachingElder.realm && elderTeaching >= 80) {
                elderBonus = (elderTeaching - 80) * 0.01
            }
        }

        preachingMasters.filter { it.isAlive }.forEach { master ->
            val masterTeaching = getBaseStats(master).teaching
            if (aggregate.realm >= master.realm && masterTeaching >= 80) {
                mastersBonus += (masterTeaching - 80) * 0.005
            }
        }

        return elderBonus to mastersBonus
    }

    fun calculateQingyunPeakCultivationSpeedBonus(
        disciple: Disciple,
        innerElder: Disciple? = null,
        qingyunPreachingElder: Disciple? = null,
        qingyunPreachingMasters: List<Disciple> = emptyList()
    ): Double {
        val (elderBonus, mastersBonus) = calculatePreachingBonus(
            disciple = disciple,
            targetDiscipleType = "inner",
            preachingElder = qingyunPreachingElder,
            preachingMasters = qingyunPreachingMasters
        )
        return elderBonus + mastersBonus
    }

    fun calculateQingyunPeakCultivationSpeedBonus(
        aggregate: DiscipleAggregate,
        innerElder: Disciple? = null,
        qingyunPreachingElder: Disciple? = null,
        qingyunPreachingMasters: List<Disciple> = emptyList()
    ): Double {
        val (elderBonus, mastersBonus) = calculatePreachingBonus(
            aggregate = aggregate,
            targetDiscipleType = "inner",
            preachingElder = qingyunPreachingElder,
            preachingMasters = qingyunPreachingMasters
        )
        return elderBonus + mastersBonus
    }

    // ==================== 血炼系统属性加成 ====================

    /**
     * 计算血炼加成并应用到 CombatAttributes 的 base* 字段。
     * 遍历 bloodRefinements 中该弟子已完成的材料ID，累加属性加成。
     */
    fun applyBloodRefinementBonuses(
        disciple: Disciple,
        bloodRefinements: Map<String, List<String>>
    ): Disciple {
        val completedMaterials = bloodRefinements[disciple.id] ?: return disciple
        if (completedMaterials.isEmpty()) return disciple

        var c = disciple.combat
        for (materialId in completedMaterials) {
            val bloodType = BeastMaterialDatabase.getBloodTypeFromMaterialId(materialId) ?: continue
            val rule = BeastMaterialDatabase.BLOOD_RULES[bloodType] ?: continue
            val material = BeastMaterialDatabase.getMaterialById(materialId) ?: continue
            val percentage = BeastMaterialDatabase.getTierPercentage(material.tier)

            // 每个材料只加一个属性，但这里无法知道当初随机选了哪个，
            // 所以血炼完成时已直接修改了 base* 字段，此处无需重复计算。
            // 此方法保留供未来查询/显示使用。
        }
        return disciple
    }

    /**
     * 根据血种随机选择属性（50/50），返回属性key。
     */
    fun randomBloodRefineStat(bloodType: String): String {
        val rule = BeastMaterialDatabase.BLOOD_RULES[bloodType] ?: return ""
        return if (kotlin.random.Random.nextBoolean()) rule.statA else rule.statB
    }

    /**
     * 获取 CombatAttributes 中指定 stat key 的 base 值。
     */
    fun getBaseStatValue(combat: com.xianxia.sect.core.model.CombatAttributes, statKey: String): Int = when (statKey) {
        "speed" -> combat.baseSpeed
        "hp" -> combat.baseHp
        "physicalAttack" -> combat.basePhysicalAttack
        "magicAttack" -> combat.baseMagicAttack
        "physicalDefense" -> combat.basePhysicalDefense
        "magicDefense" -> combat.baseMagicDefense
        else -> 0
    }

    /**
     * 对 CombatAttributes 应用属性加成（直接修改 base* 字段）。
     */
    fun applyStatBonus(combat: com.xianxia.sect.core.model.CombatAttributes, statKey: String, bonus: Int): com.xianxia.sect.core.model.CombatAttributes {
        return when (statKey) {
            "speed" -> combat.copy(baseSpeed = combat.baseSpeed + bonus)
            "hp" -> combat.copy(baseHp = combat.baseHp + bonus)
            "physicalAttack" -> combat.copy(basePhysicalAttack = combat.basePhysicalAttack + bonus)
            "magicAttack" -> combat.copy(baseMagicAttack = combat.baseMagicAttack + bonus)
            "physicalDefense" -> combat.copy(basePhysicalDefense = combat.basePhysicalDefense + bonus)
            "magicDefense" -> combat.copy(baseMagicDefense = combat.baseMagicDefense + bonus)
            else -> combat
        }
    }

    /**
     * 获取属性显示名称
     */
    fun getStatDisplayName(statKey: String): String = when (statKey) {
        "speed" -> "速度"
        "hp" -> "气血"
        "physicalAttack" -> "物攻"
        "magicAttack" -> "法攻"
        "physicalDefense" -> "物防"
        "magicDefense" -> "法防"
        else -> statKey
    }

    // ==================== 父母灵根对子嗣修炼速度的影响 ====================

    /**
     * 根据灵根数量计算父母对子嗣修炼速度的加成比例
     * 单灵根 +10%, 双灵根 +5%, 三灵根 0%, 四灵根 -5%, 五灵根 -10%
     */
    fun getParentSpiritRootBonus(spiritRootCount: Int): Double {
        return when (spiritRootCount) {
            1 -> 0.10
            2 -> 0.05
            3 -> 0.0
            4 -> -0.05
            5 -> -0.10
            else -> 0.0
        }
    }

    /**
     * 计算父母灵根对子嗣修炼速度的总加成
     * 仅存活父母影响，父母各自独立计算
     * @param parent1 父亲（或父母之一），null表示不存在或已故
     * @param parent2 母亲（或父母之一），null表示不存在或已故
     * @return 总加成比例（如 0.20 表示 +20%）
     */
    fun calculateParentCultivationBonus(parent1: Disciple?, parent2: Disciple?): Double {
        var bonus = 0.0
        if (parent1 != null && parent1.isAlive) {
            bonus += getParentSpiritRootBonus(parent1.spiritRoot.types.size)
        }
        if (parent2 != null && parent2.isAlive) {
            bonus += getParentSpiritRootBonus(parent2.spiritRoot.types.size)
        }
        return bonus
    }

    /**
     * 计算父母灵根对子嗣修炼速度的总加成（DiscipleAggregate版本）
     */
    fun calculateParentCultivationBonusForAggregate(parent1: DiscipleAggregate?, parent2: DiscipleAggregate?): Double {
        var bonus = 0.0
        if (parent1 != null && parent1.isAlive) {
            bonus += getParentSpiritRootBonus(parent1.spiritRoot.types.size)
        }
        if (parent2 != null && parent2.isAlive) {
            bonus += getParentSpiritRootBonus(parent2.spiritRoot.types.size)
        }
        return bonus
    }

    // ==================== 亲人逝世影响 ====================

    /**
     * 亲人逝世对修炼速度的惩罚比例：降低50%
     */
    const val GRIEF_CULTIVATION_SPEED_PENALTY = 0.50

    /**
     * 亲人逝世对突破率的惩罚比例：降低20%
     */
    const val GRIEF_BREAKTHROUGH_CHANCE_PENALTY = 0.20

    /**
     * 判断弟子是否处于丧亲悲痛期
     * @param griefEndYear 悲痛结束年份，null表示未处于悲痛期
     * @param currentYear 当前游戏年份
     */
    fun isGrieving(griefEndYear: Int?, currentYear: Int): Boolean {
        return griefEndYear != null && currentYear < griefEndYear
    }

    // ==================== 亲属关系判定 ====================

    /**
     * 判断两个弟子是否为亲属关系（道侣、父母/子嗣、兄弟姐妹）
     * 用于丧亲悲痛系统
     */
    fun areRelatives(a: Disciple, b: Disciple): Boolean {
        // 道侣关系
        if (a.social.partnerId == b.id || b.social.partnerId == a.id) return true

        // 父母-子女关系：a是b的父母 或 b是a的父母
        if (a.id == b.social.parentId1 || a.id == b.social.parentId2) return true
        if (b.id == a.social.parentId1 || b.id == a.social.parentId2) return true

        // 兄弟姐妹关系：有共同父母（支持单亲匹配）
        val aParents = setOfNotNull(a.social.parentId1, a.social.parentId2)
        val bParents = setOfNotNull(b.social.parentId1, b.social.parentId2)
        if (aParents.isNotEmpty() && aParents.intersect(bParents).isNotEmpty()) return true

        return false
    }

    /**
     * 为所有存活亲属设置悲痛期（持续1年），支持多个逝者批量处理
     * @param disciples 当前弟子列表
     * @param deceasedList 阵亡/逝世的弟子列表
     * @param currentYear 当前游戏年份
     * @return 更新后的弟子列表
     */
    fun applyGriefToRelatives(
        disciples: List<Disciple>,
        deceasedList: List<Disciple>,
        currentYear: Int
    ): List<Disciple> {
        val griefEndYear = currentYear + 1
        var updated = disciples
        for (deceased in deceasedList) {
            updated = updated.map { d ->
                if (!d.isAlive || d.id == deceased.id) return@map d
                if (areRelatives(d, deceased)) {
                    val existingGriefEnd = d.social.griefEndYear
                    val newGriefEnd = if (existingGriefEnd != null && existingGriefEnd > griefEndYear) existingGriefEnd else griefEndYear
                    d.copy(social = d.social.copy(griefEndYear = newGriefEnd))
                } else {
                    d
                }
            }
        }
        return updated
    }
}
