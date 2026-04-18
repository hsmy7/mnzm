package com.xianxia.sect.core.data

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import kotlin.math.roundToInt
import kotlin.random.Random

object ItemDatabase {

    data class PillTemplate(
        val id: String,
        val name: String,
        val category: PillCategory,
        val grade: PillGrade,
        val rarity: Int,
        val pillType: String,
        val description: String,
        val price: Int = 0,
        val breakthroughChance: Double = 0.0,
        val targetRealm: Int = 0,
        val isAscension: Boolean = false,
        val cultivationSpeedPercent: Double = 0.0,
        val skillExpSpeedPercent: Double = 0.0,
        val nurtureSpeedPercent: Double = 0.0,
        val cultivationAdd: Int = 0,
        val skillExpAdd: Int = 0,
        val nurtureAdd: Int = 0,
        val duration: Int = 3,
        val cannotStack: Boolean = true,
        val physicalAttackAdd: Int = 0,
        val magicAttackAdd: Int = 0,
        val physicalDefenseAdd: Int = 0,
        val magicDefenseAdd: Int = 0,
        val hpAdd: Int = 0,
        val mpAdd: Int = 0,
        val speedAdd: Int = 0,
        val critRateAdd: Double = 0.0,
        val critEffectAdd: Double = 0.0,
        val extendLife: Int = 0,
        val intelligenceAdd: Int = 0,
        val charmAdd: Int = 0,
        val loyaltyAdd: Int = 0,
        val comprehensionAdd: Int = 0,
        val artifactRefiningAdd: Int = 0,
        val pillRefiningAdd: Int = 0,
        val spiritPlantingAdd: Int = 0,
        val teachingAdd: Int = 0,
        val moralityAdd: Int = 0,
        val healMaxHpPercent: Double = 0.0,
        val mpRecoverMaxMpPercent: Double = 0.0,
        val revive: Boolean = false,
        val clearAll: Boolean = false,
        val minRealm: Int = 9
    )

    data class MaterialTemplate(
        val id: String,
        val name: String,
        val category: MaterialCategory,
        val rarity: Int,
        val description: String,
        val price: Int = 0
    )

    private val TIER_RARITY = mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 6)
    private val TIER_NAMES = mapOf(1 to "凡品", 2 to "灵品", 3 to "宝品", 4 to "玄品", 5 to "地品", 6 to "天品")

    private val REFERENCE_BASE_HP = mapOf(1 to 120, 2 to 780, 3 to 2040, 4 to 5400, 5 to 13200, 6 to 69600)
    private val REFERENCE_BASE_MP = mapOf(1 to 60, 2 to 390, 3 to 1020, 4 to 2700, 5 to 6600, 6 to 34800)
    private val REFERENCE_BASE_PA = mapOf(1 to 12, 2 to 78, 3 to 204, 4 to 540, 5 to 1320, 6 to 6960)
    private val REFERENCE_BASE_MA = mapOf(1 to 12, 2 to 78, 3 to 204, 4 to 540, 5 to 1320, 6 to 6960)
    private val REFERENCE_BASE_PD = mapOf(1 to 10, 2 to 65, 3 to 170, 4 to 450, 5 to 1100, 6 to 5800)
    private val REFERENCE_BASE_MD = mapOf(1 to 8, 2 to 52, 3 to 136, 4 to 360, 5 to 880, 6 to 4640)
    private val REFERENCE_BASE_SPD = mapOf(1 to 15, 2 to 97, 3 to 255, 4 to 675, 5 to 1650, 6 to 8700)
    private val REFERENCE_CULT_BASE = mapOf(1 to 225, 2 to 900, 3 to 1800, 4 to 3600, 5 to 16000, 6 to 64000)

    private val SPEED_PERCENT_MEDIUM = mapOf(1 to 0.30, 2 to 0.35, 3 to 0.40, 4 to 0.50, 5 to 0.60, 6 to 0.80)
    private val CRIT_RATE_MEDIUM = mapOf(1 to 0.03, 2 to 0.05, 3 to 0.07, 4 to 0.10, 5 to 0.13, 6 to 0.16)
    private val CRIT_EFFECT_MEDIUM = mapOf(1 to 0.10, 2 to 0.15, 3 to 0.20, 4 to 0.25, 5 to 0.30, 6 to 0.40)
    private val EXTEND_LIFE_MEDIUM = mapOf(1 to 5, 2 to 10, 3 to 20, 4 to 35, 5 to 50, 6 to 80)
    private val BASE_ATTR_MEDIUM = mapOf(1 to 3, 2 to 5, 3 to 8, 4 to 12, 5 to 16, 6 to 20)

    private fun applyGrade(value: Int, grade: PillGrade): Int = (value * grade.multiplier).roundToInt()
    private fun applyGrade(value: Double, grade: PillGrade): Double = value * grade.multiplier

    private fun tierPrice(tier: Int): Int = GameConfig.Rarity.get(TIER_RARITY[tier] ?: 1).pillBasePrice
    private fun tierMinRealm(tier: Int): Int = GameConfig.Realm.getMinRealmForRarity(TIER_RARITY[tier] ?: 1)

    private fun generateCultivationPills(): List<PillTemplate> {
        val pills = mutableListOf<PillTemplate>()
        val speedNames = mapOf(1 to "引灵丹", 2 to "聚灵丹", 3 to "凝元丹", 4 to "炼气丹", 5 to "混元丹", 6 to "仙灵丹")
        val skillSpeedNames = mapOf(1 to "悟法丹", 2 to "通法丹", 3 to "玄法丹", 4 to "道法丹", 5 to "天法丹", 6 to "仙法丹")
        val nurtureSpeedNames = mapOf(1 to "养器丹", 2 to "灵养丹", 3 to "宝养丹", 4 to "玄养丹", 5 to "地养丹", 6 to "天养丹")
        val cultAddNames = mapOf(1 to "增元丹", 2 to "培元丹", 3 to "固元丹", 4 to "真元丹", 5 to "玄元丹", 6 to "仙元丹")
        val skillAddNames = mapOf(1 to "悟道丹", 2 to "明心丹", 3 to "通玄丹", 4 to "慧灵丹", 5 to "道悟丹", 6 to "天机丹")
        val nurtureAddNames = mapOf(1 to "蕴器丹", 2 to "灵蕴丹", 3 to "宝蕴丹", 4 to "玄蕴丹", 5 to "地蕴丹", 6 to "天蕴丹")

        for (tier in 1..6) {
            val rarity = TIER_RARITY[tier]!!
            val baseCult = REFERENCE_CULT_BASE[tier]!!
            val speedPct = SPEED_PERCENT_MEDIUM[tier]!!

            for (grade in PillGrade.entries) {
                val g = grade.displayName
                val tierName = TIER_NAMES[tier]!!

                pills.add(PillTemplate(
                    id = "cultivationSpeed_${tier}_${grade.name.lowercase()}",
                    name = speedNames[tier]!!,
                    category = PillCategory.CULTIVATION,
                    grade = grade,
                    rarity = rarity,
                    pillType = "cultivationSpeed",
                    description = "${tierName}${g}修炼速度丹，提升境界修炼速度${(applyGrade(speedPct, grade) * 100).roundToInt()}%，持续3月",
                    price = (tierPrice(tier) * grade.priceMultiplier).roundToInt(),
                    cultivationSpeedPercent = applyGrade(speedPct, grade),
                    duration = 3,
                    cannotStack = true,
                    minRealm = tierMinRealm(tier)
                ))

                pills.add(PillTemplate(
                    id = "skillExpSpeed_${tier}_${grade.name.lowercase()}",
                    name = skillSpeedNames[tier]!!,
                    category = PillCategory.CULTIVATION,
                    grade = grade,
                    rarity = rarity,
                    pillType = "skillExpSpeed",
                    description = "${tierName}${g}功法速度丹，提升功法熟练度修炼速度${(applyGrade(speedPct, grade) * 100).roundToInt()}%，持续3月",
                    price = (tierPrice(tier) * grade.priceMultiplier).roundToInt(),
                    skillExpSpeedPercent = applyGrade(speedPct, grade),
                    duration = 3,
                    cannotStack = true,
                    minRealm = tierMinRealm(tier)
                ))

                pills.add(PillTemplate(
                    id = "nurtureSpeed_${tier}_${grade.name.lowercase()}",
                    name = nurtureSpeedNames[tier]!!,
                    category = PillCategory.CULTIVATION,
                    grade = grade,
                    rarity = rarity,
                    pillType = "nurtureSpeed",
                    description = "${tierName}${g}孕养速度丹，提升装备孕养等级修炼速度${(applyGrade(speedPct, grade) * 100).roundToInt()}%，持续3月",
                    price = (tierPrice(tier) * grade.priceMultiplier).roundToInt(),
                    nurtureSpeedPercent = applyGrade(speedPct, grade),
                    duration = 3,
                    cannotStack = true,
                    minRealm = tierMinRealm(tier)
                ))

                val cultAddVal = applyGrade((baseCult * 0.375).roundToInt(), grade)
                pills.add(PillTemplate(
                    id = "cultivationAdd_${tier}_${grade.name.lowercase()}",
                    name = cultAddNames[tier]!!,
                    category = PillCategory.CULTIVATION,
                    grade = grade,
                    rarity = rarity,
                    pillType = "cultivationAdd",
                    description = "${tierName}${g}境界修为丹，立即增加${cultAddVal}点境界修为",
                    price = (tierPrice(tier) * grade.priceMultiplier).roundToInt(),
                    cultivationAdd = cultAddVal,
                    duration = 0,
                    cannotStack = false,
                    minRealm = tierMinRealm(tier)
                ))

                val skillAddVal = applyGrade((baseCult * 0.1).roundToInt(), grade)
                pills.add(PillTemplate(
                    id = "skillExpAdd_${tier}_${grade.name.lowercase()}",
                    name = skillAddNames[tier]!!,
                    category = PillCategory.CULTIVATION,
                    grade = grade,
                    rarity = rarity,
                    pillType = "skillExpAdd",
                    description = "${tierName}${g}功法熟练丹，立即增加${skillAddVal}点功法熟练度",
                    price = (tierPrice(tier) * grade.priceMultiplier).roundToInt(),
                    skillExpAdd = skillAddVal,
                    duration = 0,
                    cannotStack = false,
                    minRealm = tierMinRealm(tier)
                ))

                val nurtureAddVal = applyGrade(listOf(50, 100, 200, 400, 800, 1600)[tier - 1], grade)
                pills.add(PillTemplate(
                    id = "nurtureAdd_${tier}_${grade.name.lowercase()}",
                    name = nurtureAddNames[tier]!!,
                    category = PillCategory.CULTIVATION,
                    grade = grade,
                    rarity = rarity,
                    pillType = "nurtureAdd",
                    description = "${tierName}${g}孕养度丹，立即增加${nurtureAddVal}点装备孕养度",
                    price = (tierPrice(tier) * grade.priceMultiplier).roundToInt(),
                    nurtureAdd = nurtureAddVal,
                    duration = 0,
                    cannotStack = false,
                    minRealm = tierMinRealm(tier)
                ))
            }
        }

        val breakthroughData = listOf(
            Pair(2, listOf(Triple(8, "筑基丹", false), Triple(7, "凝金丹", false), Triple(6, "结婴丹", false))),
            Pair(3, listOf(Triple(5, "化神丹", false), Triple(4, "破虚丹", false))),
            Pair(5, listOf(Triple(3, "合道丹", false))),
            Pair(6, listOf(Triple(2, "大乘丹", false), Triple(1, "渡劫丹", false), Triple(0, "登仙丹", true)))
        )

        for ((tier, targets) in breakthroughData) {
            val rarity = TIER_RARITY[tier]!!
            for ((targetRealm, name, isAscension) in targets) {
                for (grade in PillGrade.entries) {
                    val chance = applyGrade(0.30, grade)
                    pills.add(PillTemplate(
                        id = "breakthrough_${targetRealm}_${grade.name.lowercase()}",
                        name = name,
                        category = PillCategory.CULTIVATION,
                        grade = grade,
                        rarity = rarity,
                        pillType = "breakthrough",
                        description = "增加${GameConfig.Realm.getName(targetRealm)}期突破成功率${(chance * 100).roundToInt()}%",
                        price = (tierPrice(tier) * grade.priceMultiplier).roundToInt(),
                        breakthroughChance = chance,
                        targetRealm = targetRealm,
                        isAscension = isAscension,
                        duration = 0,
                        cannotStack = false,
                        minRealm = tierMinRealm(tier)
                    ))
                }
            }
        }

        return pills
    }

    private fun generateBattlePills(): List<PillTemplate> {
        val pills = mutableListOf<PillTemplate>()

        val singleAttrConfigs = listOf(
            Triple("physicalAttack", "物攻", mapOf(1 to "虎力丹", 2 to "熊力丹", 3 to "龙力丹", 4 to "神力丹", 5 to "霸力丹", 6 to "天力丹")),
            Triple("magicAttack", "法攻", mapOf(1 to "灵火丹", 2 to "真火丹", 3 to "三昧丹", 4 to "玄火丹", 5 to "地火丹", 6 to "天火丹")),
            Triple("physicalDefense", "物防", mapOf(1 to "铁甲丹", 2 to "铜墙丹", 3 to "金刚丹", 4 to "玄盾丹", 5 to "地罡丹", 6 to "天罡丹")),
            Triple("magicDefense", "法防", mapOf(1 to "灵盾丹", 2 to "法盾丹", 3 to "神盾丹", 4 to "玄罡丹", 5 to "地护丹", 6 to "天护丹")),
            Triple("hp", "生命", mapOf(1 to "气血丹", 2 to "血精丹", 3 to "血魂丹", 4 to "玄血丹", 5 to "地血丹", 6 to "天血丹")),
            Triple("mp", "灵力", mapOf(1 to "回灵丹", 2 to "汇灵丹", 3 to "凝灵丹", 4 to "玄灵丹", 5 to "地灵丹", 6 to "天灵丹")),
            Triple("speed", "速度", mapOf(1 to "疾风丹", 2 to "迅风丹", 3 to "神风丹", 4 to "玄风丹", 5 to "地风丹", 6 to "天风丹"))
        )

        val refMaps = mapOf(
            "physicalAttack" to REFERENCE_BASE_PA,
            "magicAttack" to REFERENCE_BASE_MA,
            "physicalDefense" to REFERENCE_BASE_PD,
            "magicDefense" to REFERENCE_BASE_MD,
            "hp" to REFERENCE_BASE_HP,
            "mp" to REFERENCE_BASE_MP,
            "speed" to REFERENCE_BASE_SPD
        )

        for ((pillType, attrName, names) in singleAttrConfigs) {
            val refMap = refMaps[pillType]!!
            for (tier in 1..6) {
                val rarity = TIER_RARITY[tier]!!
                val baseVal = refMap[tier]!!
                val mediumVal = (baseVal * 0.375).roundToInt()
                for (grade in PillGrade.entries) {
                    val val_ = applyGrade(mediumVal, grade)
                    val tierName = TIER_NAMES[tier]!!
                    val g = grade.displayName
                    pills.add(PillTemplate(
                        id = "${pillType}_${tier}_${grade.name.lowercase()}",
                        name = names[tier]!!,
                        category = PillCategory.BATTLE,
                        grade = grade,
                        rarity = rarity,
                        pillType = pillType,
                        description = "${tierName}${g}${attrName}丹，增加${val_}点${attrName}，持续3月",
                        price = (tierPrice(tier) * grade.priceMultiplier).roundToInt(),
                        duration = 3,
                        cannotStack = true,
                        physicalAttackAdd = if (pillType == "physicalAttack") val_ else 0,
                        magicAttackAdd = if (pillType == "magicAttack") val_ else 0,
                        physicalDefenseAdd = if (pillType == "physicalDefense") val_ else 0,
                        magicDefenseAdd = if (pillType == "magicDefense") val_ else 0,
                        hpAdd = if (pillType == "hp") val_ else 0,
                        mpAdd = if (pillType == "mp") val_ else 0,
                        speedAdd = if (pillType == "speed") val_ else 0,
                        minRealm = tierMinRealm(tier)
                    ))
                }
            }
        }

        data class DualAttrConfig(
            val pillType: String,
            val attr1: String,
            val attr2: String,
            val descName: String,
            val names: Map<Int, String>
        )

        val dualAttrConfigs = listOf(
            DualAttrConfig("physicalAttackDefense", "physicalAttack", "physicalDefense", "物攻物防",
                mapOf(1 to "战体丹", 2 to "战魂丹", 3 to "战意丹", 4 to "战心丹", 5 to "战圣丹", 6 to "战神丹")),
            DualAttrConfig("magicAttackDefense", "magicAttack", "magicDefense", "法攻法防",
                mapOf(1 to "法体丹", 2 to "法魂丹", 3 to "法意丹", 4 to "法心丹", 5 to "法圣丹", 6 to "法神丹")),
            DualAttrConfig("attackMixed", "physicalAttack", "magicAttack", "物法双攻",
                mapOf(1 to "双攻丹", 2 to "灵攻丹", 3 to "龙攻丹", 4 to "玄攻丹", 5 to "地攻丹", 6 to "天攻丹")),
            DualAttrConfig("defenseMixed", "physicalDefense", "magicDefense", "物法双防",
                mapOf(1 to "双御丹", 2 to "灵御丹", 3 to "龙御丹", 4 to "玄御丹", 5 to "地御丹", 6 to "天御丹")),
            DualAttrConfig("hpMp", "hp", "mp", "生命灵力",
                mapOf(1 to "生灵丹", 2 to "命灵丹", 3 to "元灵丹", 4 to "真灵丹", 5 to "混灵丹", 6 to "圣灵丹")),
            DualAttrConfig("attackSpeed", "physicalAttack", "speed", "攻速",
                mapOf(1 to "疾攻丹", 2 to "迅攻丹", 3 to "神攻丹", 4 to "玄攻丹", 5 to "地攻丹", 6 to "天攻丹")),
            DualAttrConfig("magicSpeed", "magicAttack", "speed", "法速",
                mapOf(1 to "疾法丹", 2 to "迅法丹", 3 to "神法丹", 4 to "玄法丹", 5 to "地法丹", 6 to "天法丹"))
        )

        for (config in dualAttrConfigs) {
            val ref1 = refMaps[config.attr1]!!
            val ref2 = refMaps[config.attr2]!!
            for (tier in 1..6) {
                val rarity = TIER_RARITY[tier]!!
                val med1 = (ref1[tier]!! * 0.375 * 0.6).roundToInt()
                val med2 = (ref2[tier]!! * 0.375 * 0.6).roundToInt()
                for (grade in PillGrade.entries) {
                    val v1 = applyGrade(med1, grade)
                    val v2 = applyGrade(med2, grade)
                    val tierName = TIER_NAMES[tier]!!
                    val g = grade.displayName
                    pills.add(PillTemplate(
                        id = "${config.pillType}_${tier}_${grade.name.lowercase()}",
                        name = config.names[tier]!!,
                        category = PillCategory.BATTLE,
                        grade = grade,
                        rarity = rarity,
                        pillType = config.pillType,
                        description = "${tierName}${g}${config.descName}丹，增加${v1}点${config.attr1}和${v2}点${config.attr2}，持续3月",
                        price = (tierPrice(tier) * 1.2 * grade.priceMultiplier).roundToInt(),
                        duration = 3,
                        cannotStack = true,
                        physicalAttackAdd = if (config.attr1 == "physicalAttack") v1 else if (config.attr2 == "physicalAttack") v2 else 0,
                        magicAttackAdd = if (config.attr1 == "magicAttack") v1 else if (config.attr2 == "magicAttack") v2 else 0,
                        physicalDefenseAdd = if (config.attr1 == "physicalDefense") v1 else if (config.attr2 == "physicalDefense") v2 else 0,
                        magicDefenseAdd = if (config.attr1 == "magicDefense") v1 else if (config.attr2 == "magicDefense") v2 else 0,
                        hpAdd = if (config.attr1 == "hp") v1 else if (config.attr2 == "hp") v2 else 0,
                        mpAdd = if (config.attr1 == "mp") v1 else if (config.attr2 == "mp") v2 else 0,
                        speedAdd = if (config.attr1 == "speed") v1 else if (config.attr2 == "speed") v2 else 0,
                        minRealm = tierMinRealm(tier)
                    ))
                }
            }
        }

        val critRateNames = mapOf(1 to "破击丹", 2 to "锐击丹", 3 to "必杀丹", 4 to "玄击丹", 5 to "绝杀丹", 6 to "天击丹")
        val critEffectNames = mapOf(1 to "烈击丹", 2 to "猛击丹", 3 to "暴烈丹", 4 to "玄烈丹", 5 to "毁灭丹", 6 to "天裂丹")

        for (tier in 1..6) {
            val rarity = TIER_RARITY[tier]!!
            for (grade in PillGrade.entries) {
                val cr = applyGrade(CRIT_RATE_MEDIUM[tier]!!, grade)
                val tierName = TIER_NAMES[tier]!!
                val g = grade.displayName
                pills.add(PillTemplate(
                    id = "critRate_${tier}_${grade.name.lowercase()}",
                    name = critRateNames[tier]!!,
                    category = PillCategory.BATTLE,
                    grade = grade,
                    rarity = rarity,
                    pillType = "critRate",
                    description = "${tierName}${g}暴击率丹，增加${(cr * 100).roundToInt()}%暴击率，持续3月",
                    price = (tierPrice(tier) * grade.priceMultiplier).roundToInt(),
                    critRateAdd = cr,
                    duration = 3,
                    cannotStack = true,
                    minRealm = tierMinRealm(tier)
                ))

                val ce = applyGrade(CRIT_EFFECT_MEDIUM[tier]!!, grade)
                pills.add(PillTemplate(
                    id = "critEffect_${tier}_${grade.name.lowercase()}",
                    name = critEffectNames[tier]!!,
                    category = PillCategory.BATTLE,
                    grade = grade,
                    rarity = rarity,
                    pillType = "critEffect",
                    description = "${tierName}${g}暴击效果丹，增加${(ce * 100).roundToInt()}%暴击效果，持续3月",
                    price = (tierPrice(tier) * grade.priceMultiplier).roundToInt(),
                    critEffectAdd = ce,
                    duration = 3,
                    cannotStack = true,
                    minRealm = tierMinRealm(tier)
                ))
            }
        }

        return pills
    }

    private fun generateFunctionalPills(): List<PillTemplate> {
        val pills = mutableListOf<PillTemplate>()

        val extendLifeNames = mapOf(1 to "延寿丹", 2 to "续命丹", 3 to "长生丹", 4 to "不老丹", 5 to "万寿丹", 6 to "永生丹")
        for (tier in 1..6) {
            val rarity = TIER_RARITY[tier]!!
            for (grade in PillGrade.entries) {
                val lifeVal = applyGrade(EXTEND_LIFE_MEDIUM[tier]!!, grade)
                val tierName = TIER_NAMES[tier]!!
                val g = grade.displayName
                pills.add(PillTemplate(
                    id = "extendLife_${tier}_${grade.name.lowercase()}",
                    name = extendLifeNames[tier]!!,
                    category = PillCategory.FUNCTIONAL,
                    grade = grade,
                    rarity = rarity,
                    pillType = "extendLife",
                    description = "${tierName}${g}延寿丹，增加${lifeVal}年寿元",
                    price = (tierPrice(tier) * grade.priceMultiplier).roundToInt(),
                    extendLife = lifeVal,
                    duration = 0,
                    cannotStack = false,
                    minRealm = tierMinRealm(tier)
                ))
            }
        }

        data class BaseAttrConfig(
            val pillType: String,
            val attrName: String,
            val names: Map<Int, String>
        )

        val baseAttrConfigs = listOf(
            BaseAttrConfig("intelligence", "智力", mapOf(1 to "慧根丹", 2 to "灵慧丹", 3 to "明慧丹", 4 to "玄慧丹", 5 to "地慧丹", 6 to "天慧丹")),
            BaseAttrConfig("charm", "魅力", mapOf(1 to "仙姿丹", 2 to "灵姿丹", 3 to "玉姿丹", 4 to "玄姿丹", 5 to "地姿丹", 6 to "天姿丹")),
            BaseAttrConfig("loyalty", "忠诚", mapOf(1 to "忠心丹", 2 to "赤诚丹", 3 to "铁心丹", 4 to "玄心丹", 5 to "地心丹", 6 to "天心丹")),
            BaseAttrConfig("comprehension", "悟性", mapOf(1 to "悟道丹", 2 to "明悟丹", 3 to "通悟丹", 4 to "玄悟丹", 5 to "地悟丹", 6 to "天悟丹")),
            BaseAttrConfig("artifactRefining", "炼器", mapOf(1 to "铸魂丹", 2 to "灵铸丹", 3 to "宝铸丹", 4 to "玄铸丹", 5 to "地铸丹", 6 to "天铸丹")),
            BaseAttrConfig("pillRefining", "炼丹", mapOf(1 to "丹心丹", 2 to "灵丹丹", 3 to "宝丹丹", 4 to "玄丹丹", 5 to "地丹丹", 6 to "天丹丹")),
            BaseAttrConfig("spiritPlanting", "种植", mapOf(1 to "灵植丹", 2 to "灵耘丹", 3 to "宝耘丹", 4 to "玄耘丹", 5 to "地耘丹", 6 to "天耘丹")),
            BaseAttrConfig("teaching", "教学", mapOf(1 to "传道丹", 2 to "灵传丹", 3 to "宝传丹", 4 to "玄传丹", 5 to "地传丹", 6 to "天传丹")),
            BaseAttrConfig("morality", "道德", mapOf(1 to "善行丹", 2 to "灵善丹", 3 to "宝善丹", 4 to "玄善丹", 5 to "地善丹", 6 to "天善丹"))
        )

        for (config in baseAttrConfigs) {
            for (tier in 1..6) {
                val rarity = TIER_RARITY[tier]!!
                for (grade in PillGrade.entries) {
                    val val_ = applyGrade(BASE_ATTR_MEDIUM[tier]!!, grade)
                    val tierName = TIER_NAMES[tier]!!
                    val g = grade.displayName
                    pills.add(PillTemplate(
                        id = "${config.pillType}_${tier}_${grade.name.lowercase()}",
                        name = config.names[tier]!!,
                        category = PillCategory.FUNCTIONAL,
                        grade = grade,
                        rarity = rarity,
                        pillType = config.pillType,
                        description = "${tierName}${g}${config.attrName}丹，永久增加${val_}点${config.attrName}",
                        price = (tierPrice(tier) * grade.priceMultiplier).roundToInt(),
                        intelligenceAdd = if (config.pillType == "intelligence") val_ else 0,
                        charmAdd = if (config.pillType == "charm") val_ else 0,
                        loyaltyAdd = if (config.pillType == "loyalty") val_ else 0,
                        comprehensionAdd = if (config.pillType == "comprehension") val_ else 0,
                        artifactRefiningAdd = if (config.pillType == "artifactRefining") val_ else 0,
                        pillRefiningAdd = if (config.pillType == "pillRefining") val_ else 0,
                        spiritPlantingAdd = if (config.pillType == "spiritPlanting") val_ else 0,
                        teachingAdd = if (config.pillType == "teaching") val_ else 0,
                        moralityAdd = if (config.pillType == "morality") val_ else 0,
                        duration = 0,
                        cannotStack = false,
                        minRealm = tierMinRealm(tier)
                    ))
                }
            }
        }

        data class DualBaseAttrConfig(
            val pillType: String,
            val attr1: String,
            val attr2: String,
            val descName: String,
            val names: Map<Int, String>
        )

        val dualBaseConfigs = listOf(
            DualBaseAttrConfig("intelligenceComprehension", "intelligence", "comprehension", "智悟",
                mapOf(1 to "智悟丹", 2 to "灵悟丹", 3 to "明悟丹", 4 to "玄悟丹", 5 to "地悟丹", 6 to "天悟丹")),
            DualBaseAttrConfig("charmLoyalty", "charm", "loyalty", "魅忠",
                mapOf(1 to "忠媚丹", 2 to "灵忠丹", 3 to "宝忠丹", 4 to "玄忠丹", 5 to "地忠丹", 6 to "天忠丹")),
            DualBaseAttrConfig("pillRefiningArtifactRefining", "pillRefining", "artifactRefining", "炼丹炼器",
                mapOf(1 to "双炼丹", 2 to "灵炼丹", 3 to "宝炼丹", 4 to "玄炼丹", 5 to "地炼丹", 6 to "天炼丹")),
            DualBaseAttrConfig("spiritPlantingTeaching", "spiritPlanting", "teaching", "种植教学",
                mapOf(1 to "师农丹", 2 to "灵师丹", 3 to "宝师丹", 4 to "玄师丹", 5 to "地师丹", 6 to "天师丹")),
            DualBaseAttrConfig("intelligenceCharm", "intelligence", "charm", "智魅",
                mapOf(1 to "智魅丹", 2 to "灵魅丹", 3 to "明魅丹", 4 to "玄魅丹", 5 to "地魅丹", 6 to "天魅丹")),
            DualBaseAttrConfig("comprehensionMorality", "comprehension", "morality", "悟德",
                mapOf(1 to "悟德丹", 2 to "灵德丹", 3 to "宝德丹", 4 to "玄德丹", 5 to "地德丹", 6 to "天德丹"))
        )

        for (config in dualBaseConfigs) {
            for (tier in 1..6) {
                val rarity = TIER_RARITY[tier]!!
                for (grade in PillGrade.entries) {
                    val med = BASE_ATTR_MEDIUM[tier]!!
                    val v1 = applyGrade((med * 0.6).roundToInt(), grade)
                    val v2 = applyGrade((med * 0.6).roundToInt(), grade)
                    val tierName = TIER_NAMES[tier]!!
                    val g = grade.displayName

                    fun attrVal(attr: String): Int {
                        return if (attr == config.attr1) v1 else if (attr == config.attr2) v2 else 0
                    }

                    pills.add(PillTemplate(
                        id = "${config.pillType}_${tier}_${grade.name.lowercase()}",
                        name = config.names[tier]!!,
                        category = PillCategory.FUNCTIONAL,
                        grade = grade,
                        rarity = rarity,
                        pillType = config.pillType,
                        description = "${tierName}${g}${config.descName}丹，永久增加${v1}点${config.attr1}和${v2}点${config.attr2}",
                        price = (tierPrice(tier) * 1.2 * grade.priceMultiplier).roundToInt(),
                        intelligenceAdd = attrVal("intelligence"),
                        charmAdd = attrVal("charm"),
                        loyaltyAdd = attrVal("loyalty"),
                        comprehensionAdd = attrVal("comprehension"),
                        artifactRefiningAdd = attrVal("artifactRefining"),
                        pillRefiningAdd = attrVal("pillRefining"),
                        spiritPlantingAdd = attrVal("spiritPlanting"),
                        teachingAdd = attrVal("teaching"),
                        moralityAdd = attrVal("morality"),
                        duration = 0,
                        cannotStack = false,
                        minRealm = tierMinRealm(tier)
                    ))
                }
            }
        }

        return pills
    }

    val allPills: Map<String, PillTemplate> by lazy {
        (generateCultivationPills() + generateBattlePills() + generateFunctionalPills()).associateBy { it.id }
    }

    val cultivationPills: Map<String, PillTemplate> by lazy {
        allPills.filter { it.value.category == PillCategory.CULTIVATION }
    }

    val battlePills: Map<String, PillTemplate> by lazy {
        allPills.filter { it.value.category == PillCategory.BATTLE }
    }

    val functionalPills: Map<String, PillTemplate> by lazy {
        allPills.filter { it.value.category == PillCategory.FUNCTIONAL }
    }

    val beastMaterials: Map<String, MaterialTemplate> by lazy {
        BeastMaterialDatabase.getAllMaterials().associate { beastMaterial ->
            beastMaterial.id to MaterialTemplate(
                id = beastMaterial.id,
                name = beastMaterial.name,
                category = beastMaterial.materialCategory,
                rarity = beastMaterial.rarity,
                description = beastMaterial.description,
                price = beastMaterial.price
            )
        }
    }

    val allMaterials: Map<String, MaterialTemplate> by lazy {
        beastMaterials
    }

    fun getPillById(id: String): PillTemplate? = allPills[id]
    fun getPillByName(name: String): PillTemplate? = allPills.values.find { it.name == name }
    fun getMaterialById(id: String): MaterialTemplate? = allMaterials[id]

    fun getPillsByCategory(category: PillCategory): List<PillTemplate> =
        allPills.values.filter { it.category == category }

    fun getPillsByRarity(rarity: Int): List<PillTemplate> =
        allPills.values.filter { it.rarity == rarity }

    fun getMaterialsByCategory(category: MaterialCategory): List<MaterialTemplate> =
        allMaterials.values.filter { it.category == category }

    fun createPillFromTemplate(template: PillTemplate, quantity: Int = 1): Pill {
        return Pill(
            id = java.util.UUID.randomUUID().toString(),
            name = template.name,
            rarity = template.rarity,
            description = template.description,
            category = template.category,
            grade = template.grade,
            pillType = template.pillType,
            breakthroughChance = template.breakthroughChance,
            targetRealm = template.targetRealm,
            isAscension = template.isAscension,
            cultivationSpeedPercent = template.cultivationSpeedPercent,
            skillExpSpeedPercent = template.skillExpSpeedPercent,
            nurtureSpeedPercent = template.nurtureSpeedPercent,
            cultivationAdd = template.cultivationAdd,
            skillExpAdd = template.skillExpAdd,
            nurtureAdd = template.nurtureAdd,
            duration = template.duration,
            cannotStack = template.cannotStack,
            physicalAttackAdd = template.physicalAttackAdd,
            magicAttackAdd = template.magicAttackAdd,
            physicalDefenseAdd = template.physicalDefenseAdd,
            magicDefenseAdd = template.magicDefenseAdd,
            hpAdd = template.hpAdd,
            mpAdd = template.mpAdd,
            speedAdd = template.speedAdd,
            critRateAdd = template.critRateAdd,
            critEffectAdd = template.critEffectAdd,
            extendLife = template.extendLife,
            intelligenceAdd = template.intelligenceAdd,
            charmAdd = template.charmAdd,
            loyaltyAdd = template.loyaltyAdd,
            comprehensionAdd = template.comprehensionAdd,
            artifactRefiningAdd = template.artifactRefiningAdd,
            pillRefiningAdd = template.pillRefiningAdd,
            spiritPlantingAdd = template.spiritPlantingAdd,
            teachingAdd = template.teachingAdd,
            moralityAdd = template.moralityAdd,
            healMaxHpPercent = template.healMaxHpPercent,
            mpRecoverMaxMpPercent = template.mpRecoverMaxMpPercent,
            revive = template.revive,
            clearAll = template.clearAll,
            minRealm = template.minRealm,
            quantity = quantity
        )
    }

    fun createMaterialFromTemplate(template: MaterialTemplate, quantity: Int = 1): Material {
        return Material(
            id = java.util.UUID.randomUUID().toString(),
            name = template.name,
            rarity = template.rarity,
            description = template.description,
            category = template.category,
            quantity = quantity
        )
    }

    fun generateRandomPill(minRarity: Int = 1, maxRarity: Int = 6): Pill {
        val pills = allPills.values.filter { it.rarity in minRarity..maxRarity }
        val template = pills.random()
        return createPillFromTemplate(template)
    }

    fun generateRandomMaterial(minRarity: Int = 1, maxRarity: Int = 6): Material {
        val materials = allMaterials.values.filter { it.rarity in minRarity..maxRarity }
        val template = materials.random()
        return createMaterialFromTemplate(template)
    }
}
