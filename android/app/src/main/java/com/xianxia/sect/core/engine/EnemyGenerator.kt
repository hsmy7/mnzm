package com.xianxia.sect.core.engine

import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import kotlin.math.roundToInt
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import kotlin.random.Random

object EnemyGenerator {

    data class HumanEnemyData(
        val combatant: Combatant,
        val equipmentInstances: List<EquipmentInstance>,
        val manualInstances: List<ManualInstance>
    )

    fun generateHumanEnemies(
        realmMin: Int,
        realmMax: Int,
        count: Int
    ): List<HumanEnemyData> {
        return (1..count).map { index ->
            generateHumanEnemy(index, realmMin, realmMax)
        }
    }

    private fun generateHumanEnemy(
        index: Int,
        realmMin: Int,
        realmMax: Int
    ): HumanEnemyData {
        val realm = Random.nextInt(realmMin, realmMax + 1)
        val realmLayer = Random.nextInt(1, 10)

        val minRarity = GameConfig.Realm.getMaxRarity(realm)
        val maxRarity = (minRarity + 1).coerceAtMost(6)

        val equipmentSlots = listOf(
            EquipmentSlot.WEAPON, EquipmentSlot.ARMOR,
            EquipmentSlot.BOOTS, EquipmentSlot.ACCESSORY
        ).shuffled()

        val equipmentCount = Random.nextInt(0, 5)
        val equipmentInstances = mutableListOf<EquipmentInstance>()
        val equipmentStatsAccumulator = EquipmentStatsAccumulator()

        for (i in 0 until equipmentCount) {
            val slot = equipmentSlots[i]
            val rarity = Random.nextInt(minRarity, maxRarity + 1)
            val stack = EquipmentDatabase.generateRandomBySlot(slot, rarity)
            val maxNurture = EquipmentNurtureSystem.getMaxNurtureLevel(rarity)
            val nurtureLevel = Random.nextInt(0, maxNurture + 1)
            val instance = stackToInstance(stack, nurtureLevel)
            equipmentInstances.add(instance)
            equipmentStatsAccumulator.add(instance.getFinalStats())
            equipmentStatsAccumulator.addCrit(instance.critChance)
        }

        val manualCount = Random.nextInt(0, 6)
        val manualInstances = mutableListOf<ManualInstance>()
        val manualSkills = mutableListOf<CombatSkill>()
        var hasMindManual = false

        for (i in 0 until manualCount) {
            val type = if (!hasMindManual && Random.nextDouble() < 0.2) {
                ManualType.MIND
            } else {
                listOf(ManualType.ATTACK, ManualType.DEFENSE, ManualType.SUPPORT).random()
            }

            if (type == ManualType.MIND) hasMindManual = true

            val rarity = Random.nextInt(minRarity, maxRarity + 1)
            val stack = try {
                ManualDatabase.generateRandom(minRarity, maxRarity, type)
            } catch (_: Exception) {
                continue
            }
            val masteryLevel = Random.nextInt(0, 4)
            val instance = stackToInstance(stack)
            manualInstances.add(instance)

            val skill = instance.skill
            if (skill != null) {
                val adjustedMultiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(
                    skill.damageMultiplier,
                    masteryLevel
                )
                manualSkills.add(
                    skill.copy(damageMultiplier = adjustedMultiplier).toCombatSkill(manualName = instance.name)
                )
            }
        }

        val combatant = createHumanCombatant(
            index = index,
            realm = realm,
            realmLayer = realmLayer,
            equipmentStats = equipmentStatsAccumulator,
            skills = manualSkills
        )

        return HumanEnemyData(
            combatant = combatant,
            equipmentInstances = equipmentInstances,
            manualInstances = manualInstances
        )
    }

    private fun createHumanCombatant(
        index: Int,
        realm: Int,
        realmLayer: Int,
        equipmentStats: EquipmentStatsAccumulator,
        skills: List<CombatSkill>
    ): Combatant {
        val realmConfig = GameConfig.Realm.get(realm)
        val layerMult = 1.0 + (realmLayer - 1) * 0.1

        val hp = (realmConfig.baseHp * 2.1 * layerMult).roundToInt() + equipmentStats.hp
        val mp = (realmConfig.baseMp * 2.0 * layerMult).roundToInt() + equipmentStats.mp
        val physicalAttack = (realmConfig.basePhysicalAttack * 2.5 * layerMult).roundToInt() + equipmentStats.physicalAttack
        val magicAttack = (realmConfig.baseMagicAttack * 2.5 * layerMult).roundToInt() + equipmentStats.magicAttack
        val physicalDefense = (realmConfig.basePhysicalDefense * 2.2 * layerMult).roundToInt() + equipmentStats.physicalDefense
        val magicDefense = (realmConfig.baseMagicDefense * 2.2 * layerMult).roundToInt() + equipmentStats.magicDefense
        val speed = (realmConfig.baseSpeed * 1.33 * layerMult).roundToInt() + equipmentStats.speed

        val elements = listOf("metal", "wood", "water", "fire", "earth")
        val element = elements.random()

        val enemyNames = listOf("魔修", "邪修", "散修", "山匪", "暗杀者", "邪道修士")

        return Combatant(
            id = "human_enemy_$index",
            name = "${enemyNames.random()}${index}",
            side = CombatantSide.ATTACKER,
            hp = hp,
            maxHp = hp,
            mp = mp,
            maxMp = mp,
            physicalAttack = physicalAttack,
            magicAttack = magicAttack,
            physicalDefense = physicalDefense,
            magicDefense = magicDefense,
            speed = speed,
            critRate = 0.05 + realm * 0.01 + equipmentStats.critChance,
            skills = if (skills.isNotEmpty()) skills else listOf(createDefaultAttackSkill()),
            realm = realm,
            realmName = GameConfig.Realm.getName(realm),
            realmLayer = realmLayer,
            element = element
        )
    }

    private fun createDefaultAttackSkill(): CombatSkill = CombatSkill(
        name = "普通攻击",
        skillType = com.xianxia.sect.core.SkillType.ATTACK,
        damageType = com.xianxia.sect.core.DamageType.PHYSICAL,
        damageMultiplier = 1.0,
        mpCost = 0,
        cooldown = 0
    )

    private fun stackToInstance(stack: EquipmentStack, nurtureLevel: Int = 0): EquipmentInstance {
        return EquipmentInstance(
            name = stack.name,
            rarity = stack.rarity,
            description = stack.description,
            slot = stack.slot,
            physicalAttack = stack.physicalAttack,
            magicAttack = stack.magicAttack,
            physicalDefense = stack.physicalDefense,
            magicDefense = stack.magicDefense,
            speed = stack.speed,
            hp = stack.hp,
            mp = stack.mp,
            critChance = stack.critChance,
            nurtureLevel = nurtureLevel,
            minRealm = stack.minRealm
        )
    }

    private fun stackToInstance(stack: ManualStack): ManualInstance {
        return ManualInstance(
            name = stack.name,
            rarity = stack.rarity,
            description = stack.description,
            type = stack.type,
            stats = stack.stats,
            skillName = stack.skillName,
            skillDescription = stack.skillDescription,
            skillType = stack.skillType,
            skillDamageType = stack.skillDamageType,
            skillHits = stack.skillHits,
            skillDamageMultiplier = stack.skillDamageMultiplier,
            skillCooldown = stack.skillCooldown,
            skillMpCost = stack.skillMpCost,
            skillHealPercent = stack.skillHealPercent,
            skillHealType = stack.skillHealType,
            skillBuffType = stack.skillBuffType,
            skillBuffValue = stack.skillBuffValue,
            skillBuffDuration = stack.skillBuffDuration,
            skillBuffsJson = stack.skillBuffsJson,
            skillIsAoe = stack.skillIsAoe,
            skillTargetScope = stack.skillTargetScope,
            minRealm = stack.minRealm
        )
    }

    private class EquipmentStatsAccumulator {
        var physicalAttack: Int = 0
            private set
        var magicAttack: Int = 0
            private set
        var physicalDefense: Int = 0
            private set
        var magicDefense: Int = 0
            private set
        var speed: Int = 0
            private set
        var hp: Int = 0
            private set
        var mp: Int = 0
            private set
        var critChance: Double = 0.0
            private set

        fun add(stats: com.xianxia.sect.core.model.EquipmentStats) {
            physicalAttack += stats.physicalAttack
            magicAttack += stats.magicAttack
            physicalDefense += stats.physicalDefense
            magicDefense += stats.magicDefense
            speed += stats.speed
            hp += stats.hp
            mp += stats.mp
        }

        fun addCrit(chance: Double) {
            critChance += chance
        }
    }
}
