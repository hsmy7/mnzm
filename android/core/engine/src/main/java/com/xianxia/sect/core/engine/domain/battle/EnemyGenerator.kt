package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.RngRandomAdapter

/** EnemyGenerator 的 RNG 管理器（由 GameEngine 初始化时注入） */
object EnemyGenerator {

    data class HumanEnemyData(
        val combatant: Combatant,
        val equipmentInstances: List<EquipmentInstance>,
        val manualInstances: List<ManualInstance>
    )

    fun generateHumanEnemies(
        realmMin: Int,
        realmMax: Int,
        count: Int,
        rngManager: GameRngManager
    ): List<HumanEnemyData> {
        // W4-C 随机源收敛：ENEMY_GEN 分区由调用方经 rngManager 显式传入
        //（形参必传），摘除顶层可变 `enemyGenRngManager`（消除双引擎同进程
        // "后构造者覆写"污染面；生产单引擎下抽取序逐位不变）
        val rng = rngManager.getRng(RngPartition.ENEMY_GEN)
        return (1..count).map { index ->
            generateHumanEnemy(index, realmMin, realmMax, rng)
        }
    }

    private fun generateHumanEnemy(
        index: Int,
        realmMin: Int,
        realmMax: Int,
        rng: DeterministicRng
    ): HumanEnemyData {
        // 配置反转（realmMin > realmMax）时退化为 realmMin 而非抛异常；
        // 当前 MissionDifficulty 恒 min<max，正常路径逐位相同
        val realm = realmMin + rng.nextInt((realmMax + 1 - realmMin).coerceAtLeast(1))
        val realmLayer = 1 + rng.nextInt(9)

        val minRarity = GameConfig.Realm.getMaxRarity(realm)
        val maxRarity = (minRarity + 1).coerceAtMost(6)

        // 装备生成（W3 拆分，RNG 调用序与内联时完全一致）
        val (equipmentInstances, equipmentStatsAccumulator) = generateEquipmentForEnemy(minRarity, maxRarity, rng)
        // 功法生成（W3 拆分，含技能倍率调整与属性累加）
        val (manualInstances, manualSkills, manualStatsAccumulator) = generateManualsForEnemy(minRarity, maxRarity, rng)

        val combatant = createHumanCombatant(
            index = index,
            realm = realm,
            realmLayer = realmLayer,
            equipmentStats = equipmentStatsAccumulator,
            manualStats = manualStatsAccumulator,
            skills = manualSkills,
            rng = rng
        )

        return HumanEnemyData(
            combatant = combatant,
            equipmentInstances = equipmentInstances,
            manualInstances = manualInstances
        )
    }

    /**
     * 随机装备生成（W3 从 generateHumanEnemy 提取，逐行搬移 RNG 调用序不变）。
     * @return (装备实例列表, 装备属性累加器)
     */
    private fun generateEquipmentForEnemy(
        minRarity: Int, maxRarity: Int, rng: DeterministicRng
    ): Pair<List<EquipmentInstance>, EquipmentStatsAccumulator> {
        val equipmentSlots = listOf(
            EquipmentSlot.WEAPON, EquipmentSlot.ARMOR,
            EquipmentSlot.BOOTS, EquipmentSlot.ACCESSORY
        ).let { list ->
            val seed = rng.nextInt()
            list.shuffled(java.util.Random(seed.toLong()))
        }

        val equipmentCount = rng.nextInt(5)
        val equipmentInstances = mutableListOf<EquipmentInstance>()
        val equipmentStatsAccumulator = EquipmentStatsAccumulator()

        for (i in 0 until equipmentCount) {
            val slot = equipmentSlots[i]
            val rarity = minRarity + rng.nextInt(maxRarity + 1 - minRarity)
            // 模板选择经 ENEMY_GEN 分区适配器（读档可重放的确定性来源）
            val stack = EquipmentDatabase.generateRandomBySlot(
                slot, rarity, RngRandomAdapter(rng)
            )
            val maxNurture = EquipmentNurtureSystem.getMaxNurtureLevel(rarity)
            val nurtureLevel = rng.nextInt(maxNurture + 1)
            val instance = stackToInstance(stack, nurtureLevel)
            equipmentInstances.add(instance)
            equipmentStatsAccumulator.add(instance.getFinalStats())
            equipmentStatsAccumulator.addCrit(instance.critChance)
        }
        return Pair(equipmentInstances, equipmentStatsAccumulator)
    }

    /**
     * 随机功法生成（W3 从 generateHumanEnemy 提取，逐行搬移 RNG 调用序不变）。
     * 含技能倍率调整（熟练度）与功法属性累加（与玩家 computeFinalStats 一致——
     * 修复 07-20"统一玩家公式"只统一基础属性、敌人缺功法属性加成的问题）。
     * @return (功法实例列表, 战斗技能列表, 功法属性累加器)
     */
    private fun generateManualsForEnemy(
        minRarity: Int, maxRarity: Int, rng: DeterministicRng
    ): Triple<List<ManualInstance>, List<CombatSkill>, ManualStatsAccumulator> {
        val manualCount = rng.nextInt(6)
        val manualInstances = mutableListOf<ManualInstance>()
        val manualSkills = mutableListOf<CombatSkill>()
        val manualStatsAccumulator = ManualStatsAccumulator()
        var hasMindManual = false

        for (i in 0 until manualCount) {
            val type = if (!hasMindManual && rng.nextDouble() < 0.2) {
                ManualType.MIND
            } else {
                listOf(ManualType.ATTACK, ManualType.DEFENSE, ManualType.SUPPORT)[rng.nextInt(3)]
            }

            if (type == ManualType.MIND) hasMindManual = true

            val rarity = minRarity + rng.nextInt(maxRarity + 1 - minRarity)
            val stack = try {
                // S5：模板选择经 ENEMY_GEN 分区适配器（同上）
                ManualDatabase.generateRandom(minRarity, maxRarity, type, RngRandomAdapter(rng))
            } catch (_: Exception) {
                continue
            }
            val masteryLevel = rng.nextInt(4)
            val instance = stackToInstance(stack)
            manualInstances.add(instance)
            manualStatsAccumulator.add(instance, masteryLevel)

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
        return Triple(manualInstances, manualSkills, manualStatsAccumulator)
    }

    private fun createHumanCombatant(
        index: Int,
        realm: Int,
        realmLayer: Int,
        equipmentStats: EquipmentStatsAccumulator,
        manualStats: ManualStatsAccumulator,
        skills: List<CombatSkill>,
        rng: DeterministicRng
    ): Combatant {
        // 使用与玩家弟子相同的属性公式：境界基础值 × (1 + 方差) × 层数倍率
        // + 装备加成 + 功法属性加成（stats × 熟练度 bonus，与 computeFinalStats 一致）
        // 方差 ±30%，与 DiscipleStatCalculator.computeBaseStats 的 hpVariance 等一致
        val realmConfig = GameConfig.Realm.get(realm)
        val layerMult = 1.0 + (realmLayer - 1) * 0.1

        fun rngVar(): Double = 1.0 + (rng.nextInt(61) - 30) / 100.0

        val hp = (realmConfig.baseHp * rngVar() * layerMult).toInt() + equipmentStats.hp + manualStats.hp
        val mp = (realmConfig.baseMp * rngVar() * layerMult).toInt() + equipmentStats.mp + manualStats.mp
        val physicalAttack = (realmConfig.basePhysicalAttack * rngVar() * layerMult).toInt() +
            equipmentStats.physicalAttack + manualStats.physicalAttack
        val magicAttack = (realmConfig.baseMagicAttack * rngVar() * layerMult).toInt() +
            equipmentStats.magicAttack + manualStats.magicAttack
        val physicalDefense = (realmConfig.basePhysicalDefense * rngVar() * layerMult).toInt() +
            equipmentStats.physicalDefense + manualStats.physicalDefense
        val magicDefense = (realmConfig.baseMagicDefense * rngVar() * layerMult).toInt() +
            equipmentStats.magicDefense + manualStats.magicDefense
        val speed = (realmConfig.baseSpeed * rngVar() * layerMult).toInt() + equipmentStats.speed + manualStats.speed

        val elements = listOf("metal", "wood", "water", "fire", "earth")
        val element = elements[rng.nextInt(5)]

        val enemyNames = listOf("魔修", "邪修", "散修", "山匪", "暗杀者", "邪道修士")

        return Combatant(
            id = "human_enemy_$index",
            name = "${enemyNames[rng.nextInt(enemyNames.size)]}${index}",
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
            // 基础暴击(与玩家 BASE_CRIT_RATE 一致) + 境界暴击 + 装备 + 功法暴击
            critRate = 0.05 + realm * 0.01 + equipmentStats.critChance + manualStats.critChance,
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

    /**
     * 功法属性累加器。
     *
     * 与 DiscipleStatCalculator.computeFinalStats 的功法逻辑逐字一致：
     * hp 取 stats["hp"] ?: stats["maxHp"]，各属性 × 熟练度 bonus（NOVICE=1.5 起），
     * critRate 为百分比值 ÷ 100。
     */
    internal class ManualStatsAccumulator {
        var hp: Int = 0
            private set
        var mp: Int = 0
            private set
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
        var critChance: Double = 0.0
            private set

        fun add(manual: ManualInstance, masteryLevel: Int) {
            val masteryBonus = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel).bonus
            val hpValue = manual.stats["hp"] ?: manual.stats["maxHp"] ?: 0
            val mpValue = manual.stats["mp"] ?: manual.stats["maxMp"] ?: 0
            hp += (hpValue * masteryBonus).toInt()
            mp += (mpValue * masteryBonus).toInt()
            physicalAttack += ((manual.stats["physicalAttack"] ?: 0) * masteryBonus).toInt()
            magicAttack += ((manual.stats["magicAttack"] ?: 0) * masteryBonus).toInt()
            physicalDefense += ((manual.stats["physicalDefense"] ?: 0) * masteryBonus).toInt()
            magicDefense += ((manual.stats["magicDefense"] ?: 0) * masteryBonus).toInt()
            speed += ((manual.stats["speed"] ?: 0) * masteryBonus).toInt()
            critChance += ((manual.stats["critRate"] ?: 0) * masteryBonus) / 100.0
        }
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
