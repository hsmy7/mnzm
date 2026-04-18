@file:Suppress("DEPRECATION")

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
 * - 突破概率计算（灵根品质）
 *
 * 设计原则：将 Disciple 数据模型的计算逻辑剥离到独立类中，
 * 保持数据模型的纯粹性，提高可测试性和可维护性。
 */
object DiscipleStatCalculator {

    private const val BASE_CULTIVATION_SPEED = 8.0

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
            intelligence = disciple.intelligence + intelligenceFlat,
            charm = disciple.charm + charmFlat,
            loyalty = disciple.loyalty + loyaltyFlat,
            comprehension = disciple.comprehension + comprehensionFlat,
            teaching = disciple.teaching + teachingFlat,
            morality = disciple.morality + moralityFlat
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

    // ==================== 修炼速度计算 ====================

    /**
     * 计算每秒修炼速度（支持外部传入功法和熟练度数据）
     *
     * 基础值固定为 [BASE_CULTIVATION_SPEED]，所有境界统一，不随境界或层级变化。
     * 弟子间的修炼速度差异完全由加成项体现（灵根、悟性、功法、天赋等）。
     *
     * 加成来源（按顺序累加）：
     * 1. 灵根品质基础加成
     * 2. 悟性加成（每点超出50的悟性+2%）
     * 3. 已学功法的修炼速度加成（考虑熟练度）
     * 4. 天赋中的修炼速度加成
     * 5. 建筑加成（青云峰讲道加成，不含藏经阁）
     * 6. 外部额外加成（如事件等）
     * 7. 外门/内门传道加成
     * 8. 修炼补贴加成
     *
     * @param disciple 弟子实例
     * @param manuals 功法字典（可选，为空则不计算功法加成）
     * @param manualProficiencies 功法熟练度字典
     * @param buildingBonus 建筑加成倍率（默认1.0即无加成）
     * @param additionalBonus 额外加成比例（如0.1表示+10%）
     * @param preachingElderBonus 内门长老传道加成比例
     * @param preachingMastersBonus 外门/执事传道加成比例
     * @return 每秒修为增长值
     */
    fun calculateCultivationSpeed(
        disciple: Disciple,
        manuals: Map<String, Manual> = emptyMap(),
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

        totalBonus += (disciple.comprehensionSpeedBonus - 1.0)

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

    // ==================== 突破概率计算 ====================

    /**
     * 计算突破成功率
     *
     * 基础突破概率由目标境界决定，再叠加长老悟性加成和丹药加成。
     * 化神及以上大境界突破增加神魂限制，神魂不足时突破概率为0。
     *
     * @param disciple 弟子实例
     * @param innerElderComprehension 内门长老悟性（0表示无加成）
     * @param outerElderComprehensionBonus 外门长老突破加成（0.0表示无加成）
     * @param pillBonus 丹药突破加成（0.0表示无加成）
     * @return 突破成功概率（0.0 ~ 1.0）
     */
    fun getBreakthroughChance(
        disciple: Disciple,
        innerElderComprehension: Int = 0,
        outerElderComprehensionBonus: Double = 0.0,
        pillBonus: Double = 0.0
    ): Double {
        if (disciple.realm <= 0) return 0.0

        if (!meetsSoulPowerRequirement(disciple)) return 0.0

        val baseChance = GameConfig.Realm.getBreakthroughChance(disciple.realm)

        val innerElderBonus = if (innerElderComprehension >= 80) {
            (innerElderComprehension - 80) * 0.01
        } else {
            0.0
        }

        // 天赋突破概率加成（如"悟道通玄"/"灵脉紊乱"）
        val talentEffects = getTalentEffects(disciple)
        val talentBreakthroughBonus = talentEffects["breakthroughChance"] ?: 0.0

        val totalBonus = innerElderBonus + outerElderComprehensionBonus + pillBonus + talentBreakthroughBonus
        return (baseChance + totalBonus).coerceIn(0.0, 1.0)
    }

    /**
     * 检查弟子是否满足神魂突破要求
     *
     * 化神及以上大境界突破（9层突破到下一境界）需要满足神魂限制：
     * - 化神: 60 神魂
     * - 炼虚: 100 神魂
     * - 合体: 160 神魂
     * - 大乘: 240 神魂
     * - 渡劫: 340 神魂
     * - 仙人: 500 神魂
     *
     * 非大境界突破（层内突破）不需要神魂检查
     */
    fun meetsSoulPowerRequirement(disciple: Disciple): Boolean {
        return meetsSoulPowerRequirement(disciple.realm, disciple.realmLayer, disciple.soulPower)
    }

    /**
     * 检查是否满足神魂突破要求（参数化版本，用于循环中动态境界/层次场景）
     */
    fun meetsSoulPowerRequirement(realm: Int, realmLayer: Int, soulPower: Int): Boolean {
        val isMajorBreakthrough = realmLayer >= GameConfig.Realm.get(realm).maxLayers
        if (!isMajorBreakthrough) return true

        val targetRealm = realm - 1
        if (targetRealm < 0) return true

        val requiredSoul = GameConfig.Realm.getSoulPowerRequirement(targetRealm)
        if (requiredSoul <= 0) return true

        return soulPower >= requiredSoul
    }

    // ==================== 功法槽位计算 ====================

    /**
     * 计算弟子最大功法槽位数
     *
     * 基础6个槽位，天赋"天衍道藏"可额外+1
     *
     * @param disciple 弟子实例
     * @return 最大功法槽位数
     */
    fun getMaxManualSlots(disciple: Disciple): Int {
        val talentEffects = getTalentEffects(disciple)
        val manualSlotBonus = talentEffects["manualSlot"]?.toInt() ?: 0
        return 6 + manualSlotBonus
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
            val elderTeaching = getBaseStats(qingyunPreachingElder).teaching
            // realm越小境界越高，disciple.realm >= elder.realm 表示弟子境界不高于长老，长老才能指导
            if (disciple.realm >= qingyunPreachingElder.realm && elderTeaching >= 80) {
                cultivationSpeedBonus += (elderTeaching - 80) * 0.01
            }
        }

        qingyunPreachingMasters.filter { it.isAlive }.forEach { master ->
            val masterTeaching = getBaseStats(master).teaching
            // realm越小境界越高，disciple.realm >= master.realm 表示弟子境界不高于师傅，师傅才能指导
            if (disciple.realm >= master.realm && masterTeaching >= 80) {
                cultivationSpeedBonus += (masterTeaching - 80) * 0.005
            }
        }

        return cultivationSpeedBonus
    }
}
