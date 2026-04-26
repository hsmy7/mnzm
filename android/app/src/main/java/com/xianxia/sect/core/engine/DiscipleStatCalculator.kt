package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import kotlin.math.roundToInt

object DiscipleStatCalculator {

    private const val BASE_CULTIVATION_SPEED = 8.0

    fun getBaseStats(disciple: Disciple): DiscipleStats {
        val realmConfig = GameConfig.Realm.get(disciple.realm)
        val realmMultiplier = realmConfig.multiplier
        val layerBonus = 1.0 + (disciple.realmLayer - 1) * 0.1

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

        val maxHpGrowth = disciple.statusData["winGrowth.maxHp"]?.toIntOrNull() ?: 0
        val maxMpGrowth = disciple.statusData["winGrowth.maxMp"]?.toIntOrNull() ?: 0
        val physicalAttackGrowth = disciple.statusData["winGrowth.physicalAttack"]?.toIntOrNull() ?: 0
        val magicAttackGrowth = disciple.statusData["winGrowth.magicAttack"]?.toIntOrNull() ?: 0
        val physicalDefenseGrowth = disciple.statusData["winGrowth.physicalDefense"]?.toIntOrNull() ?: 0
        val magicDefenseGrowth = disciple.statusData["winGrowth.magicDefense"]?.toIntOrNull() ?: 0
        val speedGrowth = disciple.statusData["winGrowth.speed"]?.toIntOrNull() ?: 0

        val totalHpBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + hpBonus
        val totalMpBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + mpBonus
        val totalAttackBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + attackBonus
        val totalMagicAttackBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + magicAttackBonus
        val totalDefenseBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + defenseBonus
        val totalMagicDefenseBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + magicDefenseBonus
        val totalSpeedBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + speedBonus

        return DiscipleStats(
            hp = (disciple.combat.baseHp * (1.0 + totalHpBonus)).roundToInt() + maxHpGrowth,
            maxHp = (disciple.combat.baseHp * (1.0 + totalHpBonus)).roundToInt() + maxHpGrowth,
            mp = (disciple.combat.baseMp * (1.0 + totalMpBonus)).roundToInt() + maxMpGrowth,
            maxMp = (disciple.combat.baseMp * (1.0 + totalMpBonus)).roundToInt() + maxMpGrowth,
            physicalAttack = (disciple.combat.basePhysicalAttack * (1.0 + totalAttackBonus)).roundToInt() + physicalAttackGrowth,
            magicAttack = (disciple.combat.baseMagicAttack * (1.0 + totalMagicAttackBonus)).roundToInt() + magicAttackGrowth,
            physicalDefense = (disciple.combat.basePhysicalDefense * (1.0 + totalDefenseBonus)).roundToInt() + physicalDefenseGrowth,
            magicDefense = (disciple.combat.baseMagicDefense * (1.0 + totalMagicDefenseBonus)).roundToInt() + magicDefenseGrowth,
            speed = (disciple.combat.baseSpeed * (1.0 + totalSpeedBonus)).roundToInt() + speedGrowth,
            critRate = 0.05 + critBonus,
            intelligence = disciple.skills.intelligence + intelligenceFlat,
            charm = disciple.skills.charm + charmFlat,
            loyalty = disciple.skills.loyalty + loyaltyFlat,
            comprehension = disciple.skills.comprehension + comprehensionFlat,
            teaching = disciple.skills.teaching + teachingFlat,
            morality = disciple.skills.morality + moralityFlat
        )
    }

    fun getBaseStats(aggregate: DiscipleAggregate): DiscipleStats {
        val realmConfig = GameConfig.Realm.get(aggregate.realm)
        val realmMultiplier = realmConfig.multiplier
        val layerBonus = 1.0 + (aggregate.realmLayer - 1) * 0.1

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

        val statusData = aggregate.statusData
        val maxHpGrowth = statusData["winGrowth.maxHp"]?.toIntOrNull() ?: 0
        val maxMpGrowth = statusData["winGrowth.maxMp"]?.toIntOrNull() ?: 0
        val physicalAttackGrowth = statusData["winGrowth.physicalAttack"]?.toIntOrNull() ?: 0
        val magicAttackGrowth = statusData["winGrowth.magicAttack"]?.toIntOrNull() ?: 0
        val physicalDefenseGrowth = statusData["winGrowth.physicalDefense"]?.toIntOrNull() ?: 0
        val magicDefenseGrowth = statusData["winGrowth.magicDefense"]?.toIntOrNull() ?: 0
        val speedGrowth = statusData["winGrowth.speed"]?.toIntOrNull() ?: 0

        val totalHpBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + hpBonus
        val totalMpBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + mpBonus
        val totalAttackBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + attackBonus
        val totalMagicAttackBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + magicAttackBonus
        val totalDefenseBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + defenseBonus
        val totalMagicDefenseBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + magicDefenseBonus
        val totalSpeedBonus = (realmMultiplier - 1.0) + (layerBonus - 1.0) + speedBonus

        val cs = aggregate.combatStats
        val baseHp = cs?.baseHp ?: 100
        val baseMp = cs?.baseMp ?: 50
        val basePhysicalAttack = cs?.basePhysicalAttack ?: 7
        val baseMagicAttack = cs?.baseMagicAttack ?: 7
        val basePhysicalDefense = cs?.basePhysicalDefense ?: 5
        val baseMagicDefense = cs?.baseMagicDefense ?: 3
        val baseSpeed = cs?.baseSpeed ?: 10

        val attr = aggregate.attributes
        return DiscipleStats(
            hp = (baseHp * (1.0 + totalHpBonus)).roundToInt() + maxHpGrowth,
            maxHp = (baseHp * (1.0 + totalHpBonus)).roundToInt() + maxHpGrowth,
            mp = (baseMp * (1.0 + totalMpBonus)).roundToInt() + maxMpGrowth,
            maxMp = (baseMp * (1.0 + totalMpBonus)).roundToInt() + maxMpGrowth,
            physicalAttack = (basePhysicalAttack * (1.0 + totalAttackBonus)).roundToInt() + physicalAttackGrowth,
            magicAttack = (baseMagicAttack * (1.0 + totalMagicAttackBonus)).roundToInt() + magicAttackGrowth,
            physicalDefense = (basePhysicalDefense * (1.0 + totalDefenseBonus)).roundToInt() + physicalDefenseGrowth,
            magicDefense = (baseMagicDefense * (1.0 + totalMagicDefenseBonus)).roundToInt() + magicDefenseGrowth,
            speed = (baseSpeed * (1.0 + totalSpeedBonus)).roundToInt() + speedGrowth,
            critRate = 0.05 + critBonus,
            intelligence = (attr?.intelligence ?: 50) + intelligenceFlat,
            charm = (attr?.charm ?: 50) + charmFlat,
            loyalty = (attr?.loyalty ?: 50) + loyaltyFlat,
            comprehension = (attr?.comprehension ?: 50) + comprehensionFlat,
            teaching = (attr?.teaching ?: 50) + teachingFlat,
            morality = (attr?.morality ?: 50) + moralityFlat
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
        cultivationSubsidyBonus: Double = 0.0
    ): Double {
        val baseCultivation = BASE_CULTIVATION_SPEED

        var totalBonus = 0.0

        totalBonus += (disciple.spiritRoot.cultivationBonus - 1.0)

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
        cultivationSubsidyBonus: Double = 0.0
    ): Double {
        val baseCultivation = BASE_CULTIVATION_SPEED

        var totalBonus = 0.0

        totalBonus += (aggregate.spiritRoot.cultivationBonus - 1.0)

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

        return (baseCultivation * (1.0 + totalBonus)).coerceAtLeast(1.0)
    }

    fun getBreakthroughChance(
        disciple: Disciple,
        innerElderComprehension: Int = 0,
        outerElderComprehensionBonus: Double = 0.0,
        pillBonus: Double = 0.0
    ): Double {
        if (disciple.realm < 0) return 0.0

        if (!meetsSoulPowerRequirement(disciple)) return 0.0

        val rootCount = disciple.spiritRoot.types.size
        val baseChance = GameConfig.Realm.getBreakthroughChance(disciple.realm, rootCount, disciple.realmLayer)

        val innerElderBonus = if (innerElderComprehension >= 80) {
            (innerElderComprehension - 80) * 0.01
        } else {
            0.0
        }

        val talentEffects = getTalentEffects(disciple)
        val talentBreakthroughBonus = talentEffects["breakthroughChance"] ?: 0.0

        val totalBonus = innerElderBonus + outerElderComprehensionBonus + pillBonus + talentBreakthroughBonus
        return (baseChance + totalBonus).coerceIn(0.0, 1.0)
    }

    fun getBreakthroughChance(
        aggregate: DiscipleAggregate,
        innerElderComprehension: Int = 0,
        outerElderComprehensionBonus: Double = 0.0,
        pillBonus: Double = 0.0
    ): Double {
        if (aggregate.realm < 0) return 0.0

        if (!meetsSoulPowerRequirement(aggregate)) return 0.0

        val rootCount = aggregate.spiritRoot.types.size
        val baseChance = GameConfig.Realm.getBreakthroughChance(aggregate.realm, rootCount, aggregate.realmLayer)

        val innerElderBonus = if (innerElderComprehension >= 80) {
            (innerElderComprehension - 80) * 0.01
        } else {
            0.0
        }

        val talentEffects = getTalentEffects(aggregate)
        val talentBreakthroughBonus = talentEffects["breakthroughChance"] ?: 0.0

        val totalBonus = innerElderBonus + outerElderComprehensionBonus + pillBonus + talentBreakthroughBonus
        return (baseChance + totalBonus).coerceIn(0.0, 1.0)
    }

    fun meetsSoulPowerRequirement(disciple: Disciple): Boolean {
        return meetsSoulPowerRequirement(disciple.realm, disciple.realmLayer, disciple.equipment.soulPower)
    }

    fun meetsSoulPowerRequirement(aggregate: DiscipleAggregate): Boolean {
        return meetsSoulPowerRequirement(aggregate.realm, aggregate.realmLayer, aggregate.equipment?.soulPower ?: 10)
    }

    fun meetsSoulPowerRequirement(realm: Int, realmLayer: Int, soulPower: Int): Boolean {
        val isMajorBreakthrough = realmLayer >= GameConfig.Realm.get(realm).maxLayers
        if (!isMajorBreakthrough) return true

        val targetRealm = realm - 1
        if (targetRealm < 0) return true

        val requiredSoul = GameConfig.Realm.getSoulPowerRequirement(targetRealm)
        if (requiredSoul <= 0) return true

        return soulPower >= requiredSoul
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
}
