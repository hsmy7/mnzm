package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.ManualProficiencyData
import kotlin.math.roundToInt

/**
 * 弟子属性计算器
 *
 * 负责所有与弟子属性计算相关的业务逻辑，包括：
 * - 基础属性计算（境界加成、天赋效果、战斗成长）
 * - 最终属性汇总（装备、功法、丹药效果叠加）
 * - 修炼速度计算（灵根、悟性、功法熟练度、天赋）
 * - 突破概率计算（灵根品质、天赋、失败累计）
 *
 * 设计原则：将 Disciple 数据模型的计算逻辑剥离到独立类中，
 * 保持数据模型的纯粹性，提高可测试性和可维护性。
 */
object DiscipleStatCalculator {

    // ==================== 基础属性计算 ====================

    /**
     * 计算弟子的基础属性（不含装备和功法加成）
     *
     * 计算逻辑：
     * 1. 获取当前境界的倍率系数和层级加成
     * 2. 汇总所有天赋效果
     * 3. 读取战斗成长值（来自 statusData）
     * 4. 综合计算出最终基础属性
     *
     * @param disciple 弟子实例
     * @return 基础属性对象
     */
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
            hp = (disciple.baseHp * (1.0 + totalHpBonus)).roundToInt() + maxHpGrowth,
            maxHp = (disciple.baseHp * (1.0 + totalHpBonus)).roundToInt() + maxHpGrowth,
            mp = (disciple.baseMp * (1.0 + totalMpBonus)).roundToInt() + maxMpGrowth,
            maxMp = (disciple.baseMp * (1.0 + totalMpBonus)).roundToInt() + maxMpGrowth,
            physicalAttack = (disciple.basePhysicalAttack * (1.0 + totalAttackBonus)).roundToInt() + physicalAttackGrowth,
            magicAttack = (disciple.baseMagicAttack * (1.0 + totalMagicAttackBonus)).roundToInt() + magicAttackGrowth,
            physicalDefense = (disciple.basePhysicalDefense * (1.0 + totalDefenseBonus)).roundToInt() + physicalDefenseGrowth,
            magicDefense = (disciple.baseMagicDefense * (1.0 + totalMagicDefenseBonus)).roundToInt() + magicDefenseGrowth,
            speed = (disciple.baseSpeed * (1.0 + totalSpeedBonus)).roundToInt() + speedGrowth,
            critRate = 0.05 + critBonus,
            intelligence = disciple.intelligence,
            charm = disciple.charm,
            loyalty = disciple.loyalty
        )
    }

    // ==================== 天赋效果 ====================

    /**
     * 获取弟子所有天赋的效果汇总
     *
     * 遍历弟子的所有天赋ID，查询对应的天赋数据，
     * 将相同key的效果值累加后返回。
     *
     * @param disciple 弟子实例
     * @return 天赋效果映射表（效果名称 -> 累计值）
     */
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

    // ==================== 装备属性计算 ====================

    /**
     * 计算弟子穿戴装备后的属性（不含功法和丹药）
     *
     * 在基础属性基础上，叠加所有已装备物品的属性加成。
     *
     * @param disciple 弟子实例
     * @param equipments 装备字典（装备ID -> 装备数据）
     * @return 叠加装备后的属性
     */
    fun getStatsWithEquipment(
        disciple: Disciple,
        equipments: Map<String, Equipment>
    ): DiscipleStats {
        val base = getBaseStats(disciple)
        var total = base
        var totalCritChance = 0.0

        listOfNotNull(
            disciple.weaponId,
            disciple.armorId,
            disciple.bootsId,
            disciple.accessoryId
        ).forEach { equipId ->
            val equipment = equipments[equipId]
            if (equipment != null) {
                equipment.getFinalStats().toDiscipleStats().let { total = total + it }
                totalCritChance += equipment.critChance
            }
        }

        return total.copy(critRate = total.critRate + totalCritChance)
    }

    // ==================== 最终属性计算 ====================

    /**
     * 计算弟子的最终完整属性
     *
     * 综合计算以下所有加成来源：
     * 1. 基础属性（境界、天赋、成长）
     * 2. 装备属性加成
     * 3. 功法属性加成（考虑熟练度等级）
     * 4. 丹药临时效果（如果在持续时间内）
     *
     * @param disciple 弟子实例
     * @param equipments 装备字典
     * @param manuals 功法字典
     * @param manualProficiencies 功法熟练度字典（功法ID -> 熟练度数据）
     * @return 最终完整属性
     */
    fun getFinalStats(
        disciple: Disciple,
        equipments: Map<String, Equipment>,
        manuals: Map<String, Manual>,
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap()
    ): DiscipleStats {
        val baseStats = getBaseStats(disciple)
        var total = baseStats
        var totalCritRate = total.critRate

        // 装备加成
        listOfNotNull(
            disciple.weaponId,
            disciple.armorId,
            disciple.bootsId,
            disciple.accessoryId
        ).forEach { equipId ->
            val equipment = equipments[equipId]
            if (equipment != null) {
                equipment.getFinalStats().toDiscipleStats().let { total = total + it }
                totalCritRate += equipment.critChance
            }
        }

        // 功法加成
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

        // 丹药临时效果
        if (disciple.pillEffectDuration > 0) {
            val pillBonus = DiscipleStats(
                hp = (baseStats.maxHp * disciple.pillHpBonus).toInt(),
                maxHp = (baseStats.maxHp * disciple.pillHpBonus).toInt(),
                mp = (baseStats.maxMp * disciple.pillMpBonus).toInt(),
                maxMp = (baseStats.maxMp * disciple.pillMpBonus).toInt(),
                physicalAttack = (baseStats.physicalAttack * disciple.pillPhysicalAttackBonus).toInt(),
                magicAttack = (baseStats.magicAttack * disciple.pillMagicAttackBonus).toInt(),
                physicalDefense = (baseStats.physicalDefense * disciple.pillPhysicalDefenseBonus).toInt(),
                magicDefense = (baseStats.magicDefense * disciple.pillMagicDefenseBonus).toInt(),
                speed = (baseStats.speed * disciple.pillSpeedBonus).toInt(),
                critRate = 0.0
            )
            total = total + pillBonus
        }

        return total.copy(critRate = totalCritRate)
    }

    // ==================== 修炼速度计算 ====================

    /**
     * 计算每秒修炼速度（支持外部传入功法和熟练度数据）
     *
     * 加成来源：
     * 1. 灵根品质基础加成
     * 2. 悟性加成（每点超出50的悟性+2%）
     * 3. 修炼加速buff加成
     * 4. 已学功法的修炼速度加成（考虑熟练度）
     * 5. 天赋中的修炼速度加成
     * 6. 外部额外加成（如丹药、建筑等）
     *
     * @param disciple 弟子实例
     * @param manuals 功法字典（可选，为空则不计算功法加成）
     * @param manualProficiencies 功法熟练度字典
     * @param additionalBonus 额外加成比例（如0.1表示+10%）
     * @return 每秒修为增长值
     */
    fun calculateCultivationSpeed(
        disciple: Disciple,
        manuals: Map<String, Manual> = emptyMap(),
        manualProficiencies: Map<String, ManualProficiencyData> = emptyMap(),
        buildingBonus: Double = 1.0,
        additionalBonus: Double = 0.0,
        preachingElderBonus: Double = 0.0,
        preachingMastersBonus: Double = 0.0
    ): Double {
        val baseCultivation = when (disciple.realm) {
            0 -> 100.0
            1 -> 80.0
            2 -> 60.0
            3 -> 45.0
            4 -> 30.0
            5 -> 20.0
            6 -> 12.0
            7 -> 8.0
            8 -> 5.0
            9 -> 3.0
            else -> 1.0
        }

        var totalBonus = 0.0

        totalBonus += disciple.realmLayer * 0.1

        totalBonus += (disciple.spiritRoot.cultivationBonus - 1.0)

        totalBonus += (disciple.comprehensionSpeedBonus - 1.0)

        totalBonus += (disciple.cultivationSpeedBonus - 1.0)

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
                    val mastery = disciple.manualMasteries[manualId] ?: 0
                    val masteryMultiplier = when {
                        mastery >= 100 -> 1.5
                        mastery >= 80 -> 1.3
                        mastery >= 60 -> 1.2
                        mastery >= 40 -> 1.1
                        mastery >= 20 -> 1.05
                        else -> 1.0
                    }
                    val cultivationSpeedPercent = manual.stats["cultivationSpeedPercent"] ?: 0
                    totalBonus += cultivationSpeedPercent * masteryMultiplier / 100.0
                }
            }
        }

        val talentEffects = getTalentEffects(disciple)
        totalBonus += (talentEffects["cultivationSpeed"] ?: 0.0)

        totalBonus += (buildingBonus - 1.0)

        totalBonus += additionalBonus

        totalBonus += preachingElderBonus
        totalBonus += preachingMastersBonus

        return baseCultivation * (1.0 + totalBonus)
    }

    // ==================== 突破概率计算 ====================

    /**
     * 计算突破成功率
     *
     * 影响因素：
     * 1. 基础突破率（由目标境界决定）
     * 2. 每次尝试固定-1%惩罚
     * 3. 灵根品质加成（单/双灵根在高境界有额外加成）
     * 4. 丹药加成
     * 5. 天赋突破加成
     * 6. 内门长老指导加成
     * 7. 外门长老指导加成
     * 8. 失败累积加成（每次失败+3%）
     *
     * 最终结果限制在 [0.01, 1.0] 范围内
     *
     * @param disciple 弟子实例
     * @param pillBonus 丹药提供的突破加成
     * @param innerElderComprehension 内门长老悟性
     * @param outerElderComprehensionBonus 外门长老加成比例
     * @return 突破成功概率（0.01 ~ 1.0）
     */
    fun getBreakthroughChance(
        disciple: Disciple,
        pillBonus: Double = 0.0,
        innerElderComprehension: Int = 0,
        outerElderComprehensionBonus: Double = 0.0
    ): Double {
        if (disciple.realm <= 0) return 0.0

        val baseChance = GameConfig.Realm.getBreakthroughChance(disciple.realm)

        var totalBonus = 0.0

        // 每次尝试的基础惩罚
        totalBonus += -0.01

        // 灵根品质加成
        val spiritRootCount = disciple.spiritRoot.types.size
        val targetRealm = disciple.realm - 1

        val spiritRootBonus = when (spiritRootCount) {
            1 -> if (targetRealm >= 5) 0.5 else 0.2
            2 -> if (targetRealm >= 5) 0.25 else 0.1
            else -> 0.0
        }
        totalBonus += spiritRootBonus

        // 丹药加成
        totalBonus += pillBonus

        // 天赋加成
        val talentEffects = getTalentEffects(disciple)
        val breakthroughBonus = talentEffects["breakthroughChance"] ?: 0.0
        totalBonus += breakthroughBonus

        // 内门长老指导加成（悟性）
        // 悟性80点为基准，每多1点增加1%突破率，最多20%
        // 仅对内门弟子有效，且弟子境界不能超过长老境界
        if (innerElderComprehension >= 80) {
            val innerElderBonus = ((innerElderComprehension - 80) * 0.01).coerceAtMost(0.20)
            totalBonus += innerElderBonus
        }

        // 外门长老指导加成
        totalBonus += outerElderComprehensionBonus

        // 失败累积加成
        totalBonus += disciple.breakthroughFailCount * 0.03

        val finalChance = baseChance * (1.0 + totalBonus)
        return finalChance.coerceIn(0.01, 1.0)
    }

    // ==================== 青云峰加成计算 ====================

    fun calculateQingyunPeakCultivationSpeedBonus(
        disciple: Disciple,
        innerElder: Disciple? = null,
        qingyunPreachingElder: Disciple? = null,
        qingyunPreachingMasters: List<Disciple> = emptyList()
    ): Double {
        if (disciple.discipleType != "inner") return 0.0

        var cultivationSpeedBonus = 0.0

        if (qingyunPreachingElder != null && qingyunPreachingElder.isAlive) {
            if (disciple.realm <= qingyunPreachingElder.realm && qingyunPreachingElder.skills.teaching >= 80) {
                cultivationSpeedBonus += (qingyunPreachingElder.skills.teaching - 80) * 0.01
            }
        }

        qingyunPreachingMasters.filter { it.isAlive }.forEach { master ->
            if (disciple.realm <= master.realm && master.skills.teaching >= 80) {
                cultivationSpeedBonus += (master.skills.teaching - 80) * 0.005
            }
        }

        return cultivationSpeedBonus
    }
}
