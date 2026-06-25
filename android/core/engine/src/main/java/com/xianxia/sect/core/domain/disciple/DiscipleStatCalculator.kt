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
        griefCultivationSpeedPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0
    ): Double {
        val rootCount = disciple.spiritRoot.types.size.coerceAtLeast(1)
        val basePerPhase = GameConfig.Cultivation.getRealmPerPhase(disciple.realm) / rootCount.toDouble()

        var totalBonus = 0.0

        // 悟性不再影响修炼速度（仅影响突破/悟道等）

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

        // 师徒加成：师父大境界差每级 +5% 修炼速度
        totalBonus += masterDiscipleBonus

        // 亲人逝世对修炼速度的影响
        totalBonus -= griefCultivationSpeedPenalty

        // 寿命将尽对修炼速度的影响
        totalBonus -= calculateLifespanCultivationPenalty(disciple.age, disciple.lifespan)

        return (basePerPhase * (1.0 + totalBonus)).coerceAtLeast(1.0)
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
        griefCultivationSpeedPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0
    ): Double {
        val rootCount = aggregate.spiritRoot.types.size.coerceAtLeast(1)
        val basePerPhase = GameConfig.Cultivation.getRealmPerPhase(aggregate.realm) / rootCount.toDouble()

        var totalBonus = 0.0

        // 悟性不再影响修炼速度（仅影响突破/悟道等）

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

        // 师徒加成：师父大境界差每级 +5% 修炼速度
        totalBonus += masterDiscipleBonus

        // 亲人逝世对修炼速度的影响
        totalBonus -= griefCultivationSpeedPenalty

        // 寿命将尽对修炼速度的影响
        totalBonus -= calculateLifespanCultivationPenalty(aggregate.age, aggregate.lifespan)

        return (basePerPhase * (1.0 + totalBonus)).coerceAtLeast(1.0)
    }

    fun getBreakthroughChance(
        disciple: Disciple,
        innerElderComprehension: Int = 0,
        outerElderComprehensionBonus: Double = 0.0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0
    ): Double {
        if (disciple.realm < 0) return 0.0

        val rootCount = disciple.spiritRoot.types.size
        val baseChance = GameConfig.Realm.getBreakthroughChance(disciple.realm, rootCount, disciple.realmLayer)

        val innerElderBonus = if (innerElderComprehension >= 80) {
            ((innerElderComprehension - GameConfig.PolicyConfig.ELDER_SKILL_BASELINE) / GameConfig.PolicyConfig.ELDER_BONUS_DIVISOR) * 0.01
        } else {
            0.0
        }

        val talentEffects = getTalentEffects(disciple)
        val talentBreakthroughBonus = talentEffects["breakthroughChance"] ?: 0.0

        val soulPowerBonus = getSoulPowerBreakthroughBonus(disciple.soulPower)

        val totalBonus = innerElderBonus +
            outerElderComprehensionBonus +
            pillBonus +
            talentBreakthroughBonus +
            soulPowerBonus +
            adBonus +
            masterDiscipleBonus -
            griefBreakthroughPenalty -
            calculateLifespanBreakthroughPenalty(disciple.age, disciple.lifespan)

        return (baseChance + totalBonus).coerceIn(0.0, 1.0)
    }

    fun getBreakthroughChance(
        aggregate: DiscipleAggregate,
        innerElderComprehension: Int = 0,
        outerElderComprehensionBonus: Double = 0.0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0
    ): Double {
        if (aggregate.realm < 0) return 0.0

        val rootCount = aggregate.spiritRoot.types.size
        val baseChance = GameConfig.Realm.getBreakthroughChance(aggregate.realm, rootCount, aggregate.realmLayer)

        val innerElderBonus = if (innerElderComprehension >= 80) {
            ((innerElderComprehension - GameConfig.PolicyConfig.ELDER_SKILL_BASELINE) / GameConfig.PolicyConfig.ELDER_BONUS_DIVISOR) * 0.01
        } else {
            0.0
        }

        val talentEffects = getTalentEffects(aggregate)
        val talentBreakthroughBonus = talentEffects["breakthroughChance"] ?: 0.0

        val soulPowerBonus = getSoulPowerBreakthroughBonus(aggregate.soulPower)

        val totalBonus = innerElderBonus +
            outerElderComprehensionBonus +
            pillBonus +
            talentBreakthroughBonus +
            soulPowerBonus +
            adBonus +
            masterDiscipleBonus -
            griefBreakthroughPenalty -
            calculateLifespanBreakthroughPenalty(aggregate.age, aggregate.lifespan)
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
        val masterDiscipleBonus: Double,
        val griefPenalty: Double,
        val lifespanPenalty: Double,
        val total: Double
    )

    fun getBreakthroughBonusDetail(
        aggregate: DiscipleAggregate,
        innerElderComprehension: Int = 0,
        outerElderComprehensionBonus: Double = 0.0,
        pillBonus: Double = 0.0,
        adBonus: Double = 0.0,
        griefBreakthroughPenalty: Double = 0.0,
        masterDiscipleBonus: Double = 0.0
    ): BreakthroughBonusDetail {
        if (aggregate.realm < 0) return BreakthroughBonusDetail(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val rootCount = aggregate.spiritRoot.types.size
        val baseChance = GameConfig.Realm.getBreakthroughChance(aggregate.realm, rootCount, aggregate.realmLayer)
        val innerElderBonus = if (innerElderComprehension >= 80) ((innerElderComprehension - GameConfig.PolicyConfig.ELDER_SKILL_BASELINE) / GameConfig.PolicyConfig.ELDER_BONUS_DIVISOR) * 0.01 else 0.0
        val talentEffects = getTalentEffects(aggregate)
        val talentBonus = talentEffects["breakthroughChance"] ?: 0.0
        val soulPowerBonus = getSoulPowerBreakthroughBonus(aggregate.soulPower)
        val lifespanPenalty = calculateLifespanBreakthroughPenalty(aggregate.age, aggregate.lifespan)
        val total = baseChance + innerElderBonus + outerElderComprehensionBonus + pillBonus
            + talentBonus + soulPowerBonus + adBonus + masterDiscipleBonus
            - griefBreakthroughPenalty - lifespanPenalty
        return BreakthroughBonusDetail(
            baseChance = baseChance,
            innerElderBonus = innerElderBonus,
            outerElderBonus = outerElderComprehensionBonus,
            talentBonus = talentBonus,
            soulPowerBonus = soulPowerBonus,
            pillBonus = pillBonus,
            adBonus = adBonus,
            masterDiscipleBonus = masterDiscipleBonus,
            griefPenalty = griefBreakthroughPenalty,
            lifespanPenalty = lifespanPenalty,
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

    // ==================== 血炼单利计算（#8 修复） ====================

    /**
     * 计算单利血炼加成。
     *
     * 修复历史 bug：旧实现使用 `当前 base × bonusPercent` 计算加成，
     * 导致 baseₙ = base₀ × (1+p)ⁿ 复利叠加。改为 `(当前 base - 已累计 bonus) × bonusPercent`，
     * 确保每次加成基于原始 base 值，实现单利。
     *
     * @param currentBase 当前 base 值（含历史血炼加成）
     * @param accumulatedBonus 已累计的血炼加成总量
     * @param bonusPercent 加成比例（如 0.01 = 1%）
     * @return 本次血炼的加成值（至少为 1）
     */
    fun calculateSimpleInterestBonus(
        currentBase: Int,
        accumulatedBonus: Int,
        bonusPercent: Double
    ): Int {
        val originalBase = (currentBase - accumulatedBonus).coerceAtLeast(1)
        return (originalBase * bonusPercent).toInt().coerceAtLeast(1)
    }

    /**
     * 从累计加成记录中读取指定属性的已累计 bonus。
     */
    fun getAccumulatedBonus(
        total: com.xianxia.sect.core.model.BloodRefinementBonusTotal?,
        statKey: String
    ): Int {
        if (total == null) return 0
        return when (statKey) {
            "speed" -> total.speedBonus
            "hp" -> total.hpBonus
            "physicalAttack" -> total.physicalAttackBonus
            "magicAttack" -> total.magicAttackBonus
            "physicalDefense" -> total.physicalDefenseBonus
            "magicDefense" -> total.magicDefenseBonus
            else -> 0
        }
    }

    /**
     * 将本次血炼加成累加到累计记录中，返回更新后的记录。
     */
    fun addBonusToTotal(
        total: com.xianxia.sect.core.model.BloodRefinementBonusTotal,
        statKey: String,
        bonus: Int
    ): com.xianxia.sect.core.model.BloodRefinementBonusTotal {
        return when (statKey) {
            "speed" -> total.copy(speedBonus = total.speedBonus + bonus)
            "hp" -> total.copy(hpBonus = total.hpBonus + bonus)
            "physicalAttack" -> total.copy(physicalAttackBonus = total.physicalAttackBonus + bonus)
            "magicAttack" -> total.copy(magicAttackBonus = total.magicAttackBonus + bonus)
            "physicalDefense" -> total.copy(physicalDefenseBonus = total.physicalDefenseBonus + bonus)
            "magicDefense" -> total.copy(magicDefenseBonus = total.magicDefenseBonus + bonus)
            else -> total
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

    // ==================== 师徒加成 ====================

    /** 每位师父最多可收徒弟数 */
    const val MAX_APPRENTICES_PER_MASTER = 5

    /** 师徒大境界差每级提供的修炼速度加成：5% */
    const val MASTER_DISCIPLE_CULTIVATION_BONUS_PER_GAP = 0.05

    /** 师徒大境界差每级提供的突破率加成：3% */
    const val MASTER_DISCIPLE_BREAKTHROUGH_BONUS_PER_GAP = 0.03

    /**
     * 计算师父与徒弟之间的大境界差。
     * 境界 Int 值越小境界越高（练气=9, 筑基=8, 金丹=7...）。
     * "隔整境界才算"：金丹师父(7)+练气徒弟(9) 中间隔筑基(8) 一个大境界 → gap=1。
     * 同境界 / 徒弟境界 ≥ 师父境界时 gap=0。
     */
    fun getMasterDiscipleRealmGap(discipleRealm: Int, masterRealm: Int): Int =
        (discipleRealm - masterRealm - 1).coerceAtLeast(0)

    /**
     * 计算徒弟从师父处获得的修炼速度加成（已乘以 gap）。
     */
    fun getMasterDiscipleCultivationBonus(discipleRealm: Int, masterRealm: Int): Double =
        getMasterDiscipleRealmGap(discipleRealm, masterRealm) * MASTER_DISCIPLE_CULTIVATION_BONUS_PER_GAP

    /**
     * 计算徒弟从师父处获得的突破率加成（已乘以 gap）。
     */
    fun getMasterDiscipleBreakthroughBonus(discipleRealm: Int, masterRealm: Int): Double =
        getMasterDiscipleRealmGap(discipleRealm, masterRealm) * MASTER_DISCIPLE_BREAKTHROUGH_BONUS_PER_GAP

    // ==================== 寿命将尽惩罚 ====================

    /** 寿命惩罚阈值：剩余寿命低于此比例时触发 */
    private const val LIFESPAN_PENALTY_THRESHOLD = 0.20
    /** 每低于阈值1个百分点降低5%修炼速度 */
    private const val LIFESPAN_CULTIVATION_PENALTY_PER_PCT = 0.05
    /** 每低于阈值1个百分点降低2%突破率 */
    private const val LIFESPAN_BREAKTHROUGH_PENALTY_PER_PCT = 0.02

    /**
     * 计算剩余寿命百分比（0.0~1.0）
     * lifespan <= 0 时返回 1.0（无惩罚，避免除零）
     */
    fun calculateLifespanRemainingPercent(age: Int, lifespan: Int): Double {
        if (lifespan <= 0) return 1.0
        return ((lifespan - age).coerceAtLeast(0)).toDouble() / lifespan
    }

    /**
     * 计算寿命将尽对修炼速度的惩罚值
     * 剩余寿命低于20%时，每少1个百分点降低5%修炼速度
     * @return 惩罚值（非负数），可直接从 totalBonus 中扣除
     */
    fun calculateLifespanCultivationPenalty(age: Int, lifespan: Int): Double {
        val remaining = calculateLifespanRemainingPercent(age, lifespan)
        if (remaining >= LIFESPAN_PENALTY_THRESHOLD) return 0.0
        val deficitPercent = (LIFESPAN_PENALTY_THRESHOLD - remaining) * 100
        return deficitPercent * LIFESPAN_CULTIVATION_PENALTY_PER_PCT
    }

    /**
     * 计算寿命将尽对突破率的惩罚值
     * 剩余寿命低于20%时，每少1个百分点降低2%突破率
     * @return 惩罚值（非负数），可直接从 totalBonus 中扣除
     */
    fun calculateLifespanBreakthroughPenalty(age: Int, lifespan: Int): Double {
        val remaining = calculateLifespanRemainingPercent(age, lifespan)
        if (remaining >= LIFESPAN_PENALTY_THRESHOLD) return 0.0
        val deficitPercent = (LIFESPAN_PENALTY_THRESHOLD - remaining) * 100
        return deficitPercent * LIFESPAN_BREAKTHROUGH_PENALTY_PER_PCT
    }

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
