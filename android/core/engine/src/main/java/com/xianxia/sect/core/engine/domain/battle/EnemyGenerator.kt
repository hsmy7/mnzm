package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.EquipmentSetDatabase
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.engine.domain.EquipmentFactory
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
     * 随机装备生成（W3 从 generateHumanEnemy 提取）。
     * 装备重构 B3 新口径：六部位随机排列、敌人随机穿其中若干件；实例经
     * [EquipmentFactory.create] 唯一产出入口生成（套装随机二选一），属性按
     * 逐件 `totalBonus()` 累加（孕养随机等级逻辑已随孕养系统删除）。
     * @return (装备实例列表, 装备属性累加器)
     */
    private fun generateEquipmentForEnemy(
        minRarity: Int, maxRarity: Int, rng: DeterministicRng
    ): Pair<List<EquipmentInstance>, EquipmentStatsAccumulator> {
        val equipmentSlots = EquipmentSlot.entries.let { list ->
            val seed = rng.nextInt()
            list.shuffled(java.util.Random(seed.toLong()))
        }

        val equipmentCount = rng.nextInt(5)
        val equipmentInstances = mutableListOf<EquipmentInstance>()
        val equipmentStatsAccumulator = EquipmentStatsAccumulator()

        for (i in 0 until equipmentCount) {
            val part = equipmentSlots[i]
            // 品阶沿用旧口径：在 [minRarity, maxRarity] 均匀抽取
            val rarity = minRarity + rng.nextInt(maxRarity + 1 - minRarity)
            // 套装随机六选一（物理 + 五行，EquipmentSetDatabase.ALL_IDS 单一真源）；接 ENEMY_GEN 分区
            //（与旧 generateRandomBySlot 同一确定性来源）
            val setId = EquipmentSetDatabase.ALL_IDS[rng.nextInt(EquipmentSetDatabase.ALL_IDS.size)]
            val instance = EquipmentFactory.create(setId, part, rarity, RngRandomAdapter(rng))
            equipmentInstances.add(instance)
            equipmentStatsAccumulator.add(instance)
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

        // 单列口径（B1 §15.4）：境界面物法两列各自 round 后相加（与 computeBaseStats 同式），
        // 攻/防各一个方差；装备/功法段取和相加
        // （装备重构 B3：装备不再提供速度/灵力——S14，故 mp/speed 只含功法段）
        val hp = (realmConfig.baseHp * rngVar() * layerMult).toInt() + equipmentStats.hp + manualStats.hp
        val mp = (realmConfig.baseMp * rngVar() * layerMult).toInt() + manualStats.mp
        val atkVar = rngVar()
        val defVar = rngVar()
        val attack = (realmConfig.basePhysicalAttack * atkVar * layerMult).toInt() +
            (realmConfig.baseMagicAttack * atkVar * layerMult).toInt() +
            equipmentStats.attack + manualStats.attack
        val defense = (realmConfig.basePhysicalDefense * defVar * layerMult).toInt() +
            (realmConfig.baseMagicDefense * defVar * layerMult).toInt() +
            equipmentStats.defense + manualStats.defense
        val speed = (realmConfig.baseSpeed * rngVar() * layerMult).toInt() + manualStats.speed

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
            attack = attack,
            defense = defense,
            speed = speed,
            // 基础暴击(与玩家 BASE_CRIT_RATE 一致) + 境界暴击 + 装备 + 功法暴击
            critRate = 0.05 + realm * 0.01 + equipmentStats.critRate + manualStats.critChance,
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
    /** 功法属性累加器（单列口径 B1：物法攻/防相加进 attack/defense，Q2 结算层相加） */
    internal class ManualStatsAccumulator {
        var hp: Int = 0
            private set
        var mp: Int = 0
            private set
        var attack: Int = 0
            private set
        var defense: Int = 0
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
            attack += ((manual.stats["physicalAttack"] ?: 0) * masteryBonus).toInt() +
                ((manual.stats["magicAttack"] ?: 0) * masteryBonus).toInt()
            defense += ((manual.stats["physicalDefense"] ?: 0) * masteryBonus).toInt() +
                ((manual.stats["magicDefense"] ?: 0) * masteryBonus).toInt()
            speed += ((manual.stats["speed"] ?: 0) * masteryBonus).toInt()
            critChance += ((manual.stats["critRate"] ?: 0) * masteryBonus) / 100.0
        }
    }

    /**
     * 装备属性累加器（装备重构 B3 新口径）：逐件 `totalBonus()` 累加——
     * ATTACK/DEFENSE/HP 为 flat（Int 取整），CRIT_RATE 为比例值直加；
     * CRIT_DAMAGE 与乘区项（ATTACK_PCT/物理/法术伤害%）的战斗公式消费点
     * 待 B4 接线，此处跳过不崩。装备不提供速度/灵力（S14）。
     */
    private class EquipmentStatsAccumulator {
        var attack: Int = 0
            private set
        var defense: Int = 0
            private set
        var hp: Int = 0
            private set
        var critRate: Double = 0.0
            private set

        fun add(instance: EquipmentInstance) {
            instance.totalBonus().forEach { bonus ->
                when (bonus.stat) {
                    EquipStat.ATTACK -> attack += bonus.value.toInt()
                    EquipStat.DEFENSE -> defense += bonus.value.toInt()
                    EquipStat.HP -> hp += bonus.value.toInt()
                    EquipStat.CRIT_RATE -> critRate += bonus.value
                    else -> {}
                }
            }
        }
    }
}
