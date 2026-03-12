package com.xianxia.sect.core.data

import com.xianxia.sect.core.model.PillCategory

object PillRecipeDatabase {
    
    data class PillRecipe(
        val id: String,
        val name: String,
        val tier: Int,
        val rarity: Int,
        val category: PillCategory,
        val description: String,
        val materials: Map<String, Int>,
        val duration: Int,
        val successRate: Double,
        val breakthroughChance: Double = 0.0,
        val targetRealm: Int = 0,
        val cultivationSpeed: Double = 1.0,
        val effectDuration: Int = 0,
        val physicalAttackPercent: Double = 0.0,
        val magicAttackPercent: Double = 0.0,
        val physicalDefensePercent: Double = 0.0,
        val magicDefensePercent: Double = 0.0,
        val hpPercent: Double = 0.0,
        val mpPercent: Double = 0.0,
        val speedPercent: Double = 0.0,
        val healPercent: Double = 0.0,
        val healMaxHpPercent: Double = 0.0,
        val heal: Int = 0,
        val battleCount: Int = 0,
        val extendLife: Int = 0,
        val cultivation: Int = 0,
        val cultivationPercent: Double = 0.0,
        val skillExpPercent: Double = 0.0,
        val mpRecoverMaxMpPercent: Double = 0.0
    )

    private val tier1Recipes = listOf(
        PillRecipe(
            id = "spiritGatheringPill",
            name = "引灵丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.CULTIVATION,
            description = "提升修炼速度20%，持续1个月，效果期间不可重复使用",
            materials = mapOf("spiritGrass1" to 2, "spiritGrass3" to 2),
            duration = 2,
            successRate = 1.0,
            cultivationSpeed = 1.2,
            effectDuration = 1
        ),
        PillRecipe(
            id = "commonPhysAtkPill",
            name = "虎力丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.BATTLE,
            description = "物理攻击+10%基础值，持续1场战斗",
            materials = mapOf("spiritGrass2" to 2, "spiritFruit1" to 2),
            duration = 2,
            successRate = 1.0,
            physicalAttackPercent = 0.10,
            battleCount = 1
        ),
        PillRecipe(
            id = "commonMagicAtkPill",
            name = "灵火丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.BATTLE,
            description = "法术攻击+10%基础值，持续1场战斗",
            materials = mapOf("spiritFlower1" to 2, "spiritFruit2" to 2),
            duration = 2,
            successRate = 1.0,
            magicAttackPercent = 0.10,
            battleCount = 1
        ),
        PillRecipe(
            id = "commonPhysDefPill",
            name = "铁甲丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.BATTLE,
            description = "物理防御+10%基础值，持续1场战斗",
            materials = mapOf("spiritGrass3" to 2, "spiritFlower2" to 2),
            duration = 2,
            successRate = 1.0,
            physicalDefensePercent = 0.10,
            battleCount = 1
        ),
        PillRecipe(
            id = "commonMagicDefPill",
            name = "灵盾丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.BATTLE,
            description = "法术防御+10%基础值，持续1场战斗",
            materials = mapOf("spiritFlower2" to 2, "spiritFruit3" to 2),
            duration = 2,
            successRate = 1.0,
            magicDefensePercent = 0.10,
            battleCount = 1
        ),
        PillRecipe(
            id = "commonHpPill",
            name = "气血丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.BATTLE,
            description = "生命值+10%基础值，持续1场战斗",
            materials = mapOf("spiritGrass1" to 2, "spiritFruit1" to 2),
            duration = 2,
            successRate = 1.0,
            hpPercent = 0.10,
            battleCount = 1
        ),
        PillRecipe(
            id = "commonMpPill",
            name = "回灵丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.BATTLE,
            description = "灵力容量+10%基础值，持续1场战斗",
            materials = mapOf("spiritFlower3" to 2, "spiritFruit2" to 2),
            duration = 2,
            successRate = 1.0,
            mpPercent = 0.10,
            battleCount = 1
        ),
        PillRecipe(
            id = "commonSpeedPill",
            name = "疾风丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.BATTLE,
            description = "速度+10%基础值，持续1场战斗",
            materials = mapOf("spiritGrass2" to 2, "spiritFlower1" to 2),
            duration = 2,
            successRate = 1.0,
            speedPercent = 0.10,
            battleCount = 1
        ),
        PillRecipe(
            id = "commonWarriorPill",
            name = "战体丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.BATTLE,
            description = "物理攻击+8%，物理防御+8%，持续1场战斗",
            materials = mapOf("spiritGrass3" to 2, "spiritFruit3" to 2),
            duration = 2,
            successRate = 1.0,
            physicalAttackPercent = 0.08,
            physicalDefensePercent = 0.08,
            battleCount = 1
        ),
        PillRecipe(
            id = "commonMagePill",
            name = "法体丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.BATTLE,
            description = "法术攻击+8%，法术防御+8%，持续1场战斗",
            materials = mapOf("spiritFlower1" to 2, "spiritFruit1" to 2),
            duration = 2,
            successRate = 1.0,
            magicAttackPercent = 0.08,
            magicDefensePercent = 0.08,
            battleCount = 1
        ),
        PillRecipe(
            id = "commonVitalityPill",
            name = "生灵丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.BATTLE,
            description = "生命值+8%，灵力容量+8%，持续1场战斗",
            materials = mapOf("spiritGrass1" to 2, "spiritFlower2" to 2),
            duration = 2,
            successRate = 1.0,
            hpPercent = 0.08,
            mpPercent = 0.08,
            battleCount = 1
        ),
        PillRecipe(
            id = "commonHpPill_heal",
            name = "回春丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.HEALING,
            description = "恢复15%最大生命值",
            materials = mapOf("spiritGrass2" to 2, "spiritFlower3" to 2),
            duration = 2,
            successRate = 1.0,
            healMaxHpPercent = 0.15
        ),
        PillRecipe(
            id = "commonMpPill_heal",
            name = "补气丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.HEALING,
            description = "恢复15%最大灵力",
            materials = mapOf("spiritFlower2" to 2, "spiritFruit1" to 2),
            duration = 2,
            successRate = 1.0,
            mpRecoverMaxMpPercent = 0.15
        ),
        PillRecipe(
            id = "commonCultivationPill",
            name = "增元丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.CULTIVATION,
            description = "立即获得5%修为上限的修为",
            materials = mapOf("spiritGrass3" to 2, "spiritFruit2" to 2),
            duration = 2,
            successRate = 1.0,
            cultivationPercent = 0.05
        ),
        PillRecipe(
            id = "commonSkillPill",
            name = "悟道丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.CULTIVATION,
            description = "增加所有功法20%当前等级所需熟练度",
            materials = mapOf("spiritFlower3" to 2, "spiritFruit3" to 2),
            duration = 2,
            successRate = 1.0,
            skillExpPercent = 0.20
        ),
        PillRecipe(
            id = "commonLifePill",
            name = "延寿丹",
            tier = 1,
            rarity = 1,
            category = PillCategory.CULTIVATION,
            description = "增加5年寿元",
            materials = mapOf("spiritGrass1" to 2, "spiritFruit3" to 2),
            duration = 2,
            successRate = 1.0,
            extendLife = 5
        )
    )

    private val tier2Recipes = listOf(
        PillRecipe(
            id = "foundationPill",
            name = "筑基丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BREAKTHROUGH,
            description = "增加炼气期突破筑基期的成功概率30%",
            materials = mapOf("spiritGrass4" to 2, "spiritFlower5" to 2),
            duration = 5,
            successRate = 0.95,
            breakthroughChance = 0.30,
            targetRealm = 8
        ),
        PillRecipe(
            id = "goldenCorePill",
            name = "凝金丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BREAKTHROUGH,
            description = "增加筑基期突破金丹期的成功概率30%",
            materials = mapOf("spiritGrass4" to 2, "spiritFlower5" to 2),
            duration = 5,
            successRate = 0.95,
            breakthroughChance = 0.30,
            targetRealm = 7
        ),
        PillRecipe(
            id = "nascentSoulPill",
            name = "结婴丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BREAKTHROUGH,
            description = "增加金丹期突破元婴期的成功概率30%",
            materials = mapOf("spiritGrass5" to 2, "spiritFlower6" to 2),
            duration = 5,
            successRate = 0.95,
            breakthroughChance = 0.30,
            targetRealm = 6
        ),
        PillRecipe(
            id = "spiritCondensingPill",
            name = "聚灵丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.CULTIVATION,
            description = "提升修炼速度40%，持续1个月，效果期间不可重复使用",
            materials = mapOf("spiritGrass5" to 2, "spiritFlower4" to 2),
            duration = 5,
            successRate = 0.95,
            cultivationSpeed = 1.4,
            effectDuration = 1
        ),
        PillRecipe(
            id = "uncommonPhysAtkPill",
            name = "熊力丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BATTLE,
            description = "物理攻击+15%基础值，持续1场战斗",
            materials = mapOf("spiritGrass6" to 2, "spiritFruit4" to 2),
            duration = 5,
            successRate = 0.95,
            physicalAttackPercent = 0.15,
            battleCount = 1
        ),
        PillRecipe(
            id = "uncommonMagicAtkPill",
            name = "真火丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BATTLE,
            description = "法术攻击+15%基础值，持续1场战斗",
            materials = mapOf("spiritFlower4" to 2, "spiritFruit5" to 2),
            duration = 5,
            successRate = 0.95,
            magicAttackPercent = 0.15,
            battleCount = 1
        ),
        PillRecipe(
            id = "uncommonPhysDefPill",
            name = "铜墙丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BATTLE,
            description = "物理防御+15%基础值，持续1场战斗",
            materials = mapOf("spiritGrass4" to 2, "spiritFlower6" to 2),
            duration = 5,
            successRate = 0.95,
            physicalDefensePercent = 0.15,
            battleCount = 1
        ),
        PillRecipe(
            id = "uncommonMagicDefPill",
            name = "法盾丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BATTLE,
            description = "法术防御+15%基础值，持续1场战斗",
            materials = mapOf("spiritFlower5" to 2, "spiritFruit6" to 2),
            duration = 5,
            successRate = 0.95,
            magicDefensePercent = 0.15,
            battleCount = 1
        ),
        PillRecipe(
            id = "uncommonHpPill",
            name = "血精丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BATTLE,
            description = "生命值+15%基础值，持续1场战斗",
            materials = mapOf("spiritGrass5" to 2, "spiritFruit4" to 2),
            duration = 5,
            successRate = 0.95,
            hpPercent = 0.15,
            battleCount = 1
        ),
        PillRecipe(
            id = "uncommonMpPill",
            name = "汇灵丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BATTLE,
            description = "灵力容量+15%基础值，持续1场战斗",
            materials = mapOf("spiritFlower6" to 2, "spiritFruit5" to 2),
            duration = 5,
            successRate = 0.95,
            mpPercent = 0.15,
            battleCount = 1
        ),
        PillRecipe(
            id = "uncommonSpeedPill",
            name = "迅风丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BATTLE,
            description = "速度+15%基础值，持续1场战斗",
            materials = mapOf("spiritGrass6" to 2, "spiritFlower4" to 2),
            duration = 5,
            successRate = 0.95,
            speedPercent = 0.15,
            battleCount = 1
        ),
        PillRecipe(
            id = "uncommonWarriorPill",
            name = "战魂丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BATTLE,
            description = "物理攻击+12%，物理防御+12%，持续1场战斗",
            materials = mapOf("spiritGrass4" to 2, "spiritFruit6" to 2),
            duration = 5,
            successRate = 0.95,
            physicalAttackPercent = 0.12,
            physicalDefensePercent = 0.12,
            battleCount = 1
        ),
        PillRecipe(
            id = "uncommonMagePill",
            name = "法魂丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BATTLE,
            description = "法术攻击+12%，法术防御+12%，持续1场战斗",
            materials = mapOf("spiritFlower5" to 2, "spiritFruit4" to 2),
            duration = 5,
            successRate = 0.95,
            magicAttackPercent = 0.12,
            magicDefensePercent = 0.12,
            battleCount = 1
        ),
        PillRecipe(
            id = "uncommonVitalityPill",
            name = "命灵丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.BATTLE,
            description = "生命值+12%，灵力容量+12%，持续1场战斗",
            materials = mapOf("spiritGrass5" to 2, "spiritFlower6" to 2),
            duration = 5,
            successRate = 0.95,
            hpPercent = 0.12,
            mpPercent = 0.12,
            battleCount = 1
        ),
        PillRecipe(
            id = "uncommonHpPill_heal",
            name = "复元丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.HEALING,
            description = "恢复25%最大生命值",
            materials = mapOf("spiritGrass6" to 2, "spiritFruit5" to 2),
            duration = 5,
            successRate = 0.95,
            healMaxHpPercent = 0.25
        ),
        PillRecipe(
            id = "uncommonMpPill_heal",
            name = "复灵丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.HEALING,
            description = "恢复25%最大灵力",
            materials = mapOf("spiritFlower4" to 2, "spiritFruit6" to 2),
            duration = 5,
            successRate = 0.95,
            mpRecoverMaxMpPercent = 0.25
        ),
        PillRecipe(
            id = "uncommonCultivationPill",
            name = "培元丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.CULTIVATION,
            description = "立即获得10%修为上限的修为",
            materials = mapOf("spiritGrass4" to 2, "spiritFlower5" to 2),
            duration = 5,
            successRate = 0.95,
            cultivationPercent = 0.10
        ),
        PillRecipe(
            id = "uncommonSkillPill",
            name = "明心丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.CULTIVATION,
            description = "增加所有功法40%当前等级所需熟练度",
            materials = mapOf("spiritFlower6" to 2, "spiritFruit4" to 2),
            duration = 5,
            successRate = 0.95,
            skillExpPercent = 0.40
        ),
        PillRecipe(
            id = "uncommonLifePill",
            name = "续命丹",
            tier = 2,
            rarity = 2,
            category = PillCategory.CULTIVATION,
            description = "增加10年寿元",
            materials = mapOf("spiritGrass5" to 2, "spiritFruit6" to 2),
            duration = 5,
            successRate = 0.95,
            extendLife = 10
        )
    )

    private val tier3Recipes = listOf(
        PillRecipe(
            id = "spiritSeveringPill",
            name = "化神丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.BREAKTHROUGH,
            description = "增加元婴期突破化神期的成功概率30%",
            materials = mapOf("spiritGrass7" to 2, "spiritFlower8" to 2, "spiritFruit7" to 2),
            duration = 9,
            successRate = 0.90,
            breakthroughChance = 0.30,
            targetRealm = 5
        ),
        PillRecipe(
            id = "voidBreakPill",
            name = "破虚丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.BREAKTHROUGH,
            description = "增加化神期突破炼虚期的成功概率30%",
            materials = mapOf("spiritGrass8" to 2, "spiritFlower9" to 2, "spiritFruit8" to 2),
            duration = 9,
            successRate = 0.90,
            breakthroughChance = 0.30,
            targetRealm = 4
        ),
        PillRecipe(
            id = "essenceGatheringPill",
            name = "凝元丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.CULTIVATION,
            description = "提升修炼速度60%，持续1个月，效果期间不可重复使用",
            materials = mapOf("spiritGrass8" to 2, "spiritFlower7" to 2, "spiritFruit8" to 2),
            duration = 9,
            successRate = 0.90,
            cultivationSpeed = 1.6,
            effectDuration = 1
        ),
        PillRecipe(
            id = "rarePhysAtkPill",
            name = "龙力丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.BATTLE,
            description = "物理攻击+20%基础值，持续1场战斗",
            materials = mapOf("spiritGrass9" to 2, "spiritFlower9" to 2, "spiritFruit7" to 2),
            duration = 9,
            successRate = 0.90,
            physicalAttackPercent = 0.20,
            battleCount = 1
        ),
        PillRecipe(
            id = "rareMagicAtkPill",
            name = "三昧丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.BATTLE,
            description = "法术攻击+20%基础值，持续1场战斗",
            materials = mapOf("spiritFlower7" to 2, "spiritFruit8" to 2, "spiritGrass7" to 2),
            duration = 9,
            successRate = 0.90,
            magicAttackPercent = 0.20,
            battleCount = 1
        ),
        PillRecipe(
            id = "rarePhysDefPill",
            name = "金刚丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.BATTLE,
            description = "物理防御+20%基础值，持续1场战斗",
            materials = mapOf("spiritGrass8" to 2, "spiritFlower8" to 2, "spiritFruit9" to 2),
            duration = 9,
            successRate = 0.90,
            physicalDefensePercent = 0.20,
            battleCount = 1
        ),
        PillRecipe(
            id = "rareMagicDefPill",
            name = "神盾丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.BATTLE,
            description = "法术防御+20%基础值，持续1场战斗",
            materials = mapOf("spiritFlower9" to 2, "spiritFruit7" to 2, "spiritGrass8" to 2),
            duration = 9,
            successRate = 0.90,
            magicDefensePercent = 0.20,
            battleCount = 1
        ),
        PillRecipe(
            id = "rareHpPill",
            name = "血魂丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.BATTLE,
            description = "生命值+20%基础值，持续1场战斗",
            materials = mapOf("spiritGrass7" to 2, "spiritFruit8" to 2, "spiritFlower9" to 2),
            duration = 9,
            successRate = 0.90,
            hpPercent = 0.20,
            battleCount = 1
        ),
        PillRecipe(
            id = "rareMpPill",
            name = "凝灵丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.BATTLE,
            description = "灵力容量+20%基础值，持续1场战斗",
            materials = mapOf("spiritFlower8" to 2, "spiritFruit9" to 2, "spiritGrass9" to 2),
            duration = 9,
            successRate = 0.90,
            mpPercent = 0.20,
            battleCount = 1
        ),
        PillRecipe(
            id = "rareSpeedPill",
            name = "神风丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.BATTLE,
            description = "速度+20%基础值，持续1场战斗",
            materials = mapOf("spiritGrass9" to 2, "spiritFlower7" to 2, "spiritFruit8" to 2),
            duration = 9,
            successRate = 0.90,
            speedPercent = 0.20,
            battleCount = 1
        ),
        PillRecipe(
            id = "rareWarriorPill",
            name = "战意丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.BATTLE,
            description = "物理攻击+16%，物理防御+16%，持续1场战斗",
            materials = mapOf("spiritGrass7" to 2, "spiritFruit9" to 2, "spiritFlower8" to 2),
            duration = 9,
            successRate = 0.90,
            physicalAttackPercent = 0.16,
            physicalDefensePercent = 0.16,
            battleCount = 1
        ),
        PillRecipe(
            id = "rareMagePill",
            name = "法意丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.BATTLE,
            description = "法术攻击+16%，法术防御+16%，持续1场战斗",
            materials = mapOf("spiritFlower9" to 2, "spiritFruit7" to 2, "spiritGrass8" to 2),
            duration = 9,
            successRate = 0.90,
            magicAttackPercent = 0.16,
            magicDefensePercent = 0.16,
            battleCount = 1
        ),
        PillRecipe(
            id = "rareVitalityPill",
            name = "元灵丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.BATTLE,
            description = "生命值+16%，灵力容量+16%，持续1场战斗",
            materials = mapOf("spiritGrass8" to 2, "spiritFlower7" to 2, "spiritFruit9" to 2),
            duration = 9,
            successRate = 0.90,
            hpPercent = 0.16,
            mpPercent = 0.16,
            battleCount = 1
        ),
        PillRecipe(
            id = "rareHpPill_heal",
            name = "回生丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.HEALING,
            description = "恢复40%最大生命值",
            materials = mapOf("spiritGrass9" to 2, "spiritFlower8" to 2, "spiritFruit7" to 2),
            duration = 9,
            successRate = 0.90,
            healMaxHpPercent = 0.40
        ),
        PillRecipe(
            id = "rareMpPill_heal",
            name = "凝神丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.HEALING,
            description = "恢复40%最大灵力",
            materials = mapOf("spiritFlower7" to 2, "spiritFruit8" to 2, "spiritGrass7" to 2),
            duration = 9,
            successRate = 0.90,
            mpRecoverMaxMpPercent = 0.40
        ),
        PillRecipe(
            id = "rareCultivationPill",
            name = "固元丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.CULTIVATION,
            description = "立即获得20%修为上限的修为",
            materials = mapOf("spiritGrass8" to 2, "spiritFlower9" to 2, "spiritFruit8" to 2),
            duration = 9,
            successRate = 0.90,
            cultivationPercent = 0.20
        ),
        PillRecipe(
            id = "rareSkillPill",
            name = "通玄丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.CULTIVATION,
            description = "增加所有功法60%当前等级所需熟练度",
            materials = mapOf("spiritFlower8" to 2, "spiritFruit9" to 2, "spiritGrass9" to 2),
            duration = 9,
            successRate = 0.90,
            skillExpPercent = 0.60
        ),
        PillRecipe(
            id = "rareLifePill",
            name = "长生丹",
            tier = 3,
            rarity = 3,
            category = PillCategory.CULTIVATION,
            description = "增加20年寿元",
            materials = mapOf("spiritGrass7" to 2, "spiritFruit7" to 2, "spiritFlower9" to 2),
            duration = 9,
            successRate = 0.90,
            extendLife = 20
        )
    )

    private val tier4Recipes = listOf(
        PillRecipe(
            id = "spiritRefiningPill",
            name = "炼气丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.CULTIVATION,
            description = "提升修炼速度100%，持续1个月，效果期间不可重复使用",
            materials = mapOf("spiritGrass11" to 2, "spiritFlower10" to 2, "spiritFruit11" to 2),
            duration = 12,
            successRate = 0.85,
            cultivationSpeed = 2.0,
            effectDuration = 1
        ),
        PillRecipe(
            id = "epicPhysAtkPill",
            name = "神力丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.BATTLE,
            description = "物理攻击+25%基础值，持续1场战斗",
            materials = mapOf("spiritGrass12" to 2, "spiritFlower12" to 2, "spiritFruit10" to 2),
            duration = 12,
            successRate = 0.85,
            physicalAttackPercent = 0.25,
            battleCount = 1
        ),
        PillRecipe(
            id = "epicMagicAtkPill",
            name = "玄火丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.BATTLE,
            description = "法术攻击+25%基础值，持续1场战斗",
            materials = mapOf("spiritFlower10" to 2, "spiritFruit11" to 2, "spiritGrass10" to 2),
            duration = 12,
            successRate = 0.85,
            magicAttackPercent = 0.25,
            battleCount = 1
        ),
        PillRecipe(
            id = "epicPhysDefPill",
            name = "玄盾丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.BATTLE,
            description = "物理防御+25%基础值，持续1场战斗",
            materials = mapOf("spiritGrass11" to 2, "spiritFlower11" to 2, "spiritFruit12" to 2),
            duration = 12,
            successRate = 0.85,
            physicalDefensePercent = 0.25,
            battleCount = 1
        ),
        PillRecipe(
            id = "epicMagicDefPill",
            name = "玄罡丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.BATTLE,
            description = "法术防御+25%基础值，持续1场战斗",
            materials = mapOf("spiritFlower12" to 2, "spiritFruit10" to 2, "spiritGrass11" to 2),
            duration = 12,
            successRate = 0.85,
            magicDefensePercent = 0.25,
            battleCount = 1
        ),
        PillRecipe(
            id = "epicHpPill",
            name = "玄血丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.BATTLE,
            description = "生命值+25%基础值，持续1场战斗",
            materials = mapOf("spiritGrass10" to 2, "spiritFruit11" to 2, "spiritFlower12" to 2),
            duration = 12,
            successRate = 0.85,
            hpPercent = 0.25,
            battleCount = 1
        ),
        PillRecipe(
            id = "epicMpPill",
            name = "玄灵丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.BATTLE,
            description = "灵力容量+25%基础值，持续1场战斗",
            materials = mapOf("spiritFlower11" to 2, "spiritFruit12" to 2, "spiritGrass12" to 2),
            duration = 12,
            successRate = 0.85,
            mpPercent = 0.25,
            battleCount = 1
        ),
        PillRecipe(
            id = "epicSpeedPill",
            name = "玄风丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.BATTLE,
            description = "速度+25%基础值，持续1场战斗",
            materials = mapOf("spiritGrass12" to 2, "spiritFlower10" to 2, "spiritFruit11" to 2),
            duration = 12,
            successRate = 0.85,
            speedPercent = 0.25,
            battleCount = 1
        ),
        PillRecipe(
            id = "epicWarriorPill",
            name = "战心丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.BATTLE,
            description = "物理攻击+20%，物理防御+20%，持续1场战斗",
            materials = mapOf("spiritGrass10" to 2, "spiritFruit12" to 2, "spiritFlower11" to 2),
            duration = 12,
            successRate = 0.85,
            physicalAttackPercent = 0.20,
            physicalDefensePercent = 0.20,
            battleCount = 1
        ),
        PillRecipe(
            id = "epicMagePill",
            name = "法心丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.BATTLE,
            description = "法术攻击+20%，法术防御+20%，持续1场战斗",
            materials = mapOf("spiritFlower12" to 2, "spiritFruit10" to 2, "spiritGrass10" to 2),
            duration = 12,
            successRate = 0.85,
            magicAttackPercent = 0.20,
            magicDefensePercent = 0.20,
            battleCount = 1
        ),
        PillRecipe(
            id = "epicVitalityPill",
            name = "真灵丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.BATTLE,
            description = "生命值+20%，灵力容量+20%，持续1场战斗",
            materials = mapOf("spiritGrass11" to 2, "spiritFlower10" to 2, "spiritFruit12" to 2),
            duration = 12,
            successRate = 0.85,
            hpPercent = 0.20,
            mpPercent = 0.20,
            battleCount = 1
        ),
        PillRecipe(
            id = "epicHpPill_heal",
            name = "还魂丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.HEALING,
            description = "恢复55%最大生命值",
            materials = mapOf("spiritGrass12" to 2, "spiritFlower11" to 2, "spiritFruit10" to 2),
            duration = 12,
            successRate = 0.85,
            healMaxHpPercent = 0.55
        ),
        PillRecipe(
            id = "epicMpPill_heal",
            name = "聚魂丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.HEALING,
            description = "恢复55%最大灵力",
            materials = mapOf("spiritFlower10" to 2, "spiritFruit11" to 2, "spiritGrass11" to 2),
            duration = 12,
            successRate = 0.85,
            mpRecoverMaxMpPercent = 0.55
        ),
        PillRecipe(
            id = "epicCultivationPill",
            name = "真元丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.CULTIVATION,
            description = "立即获得35%修为上限的修为",
            materials = mapOf("spiritGrass10" to 2, "spiritFlower12" to 2, "spiritFruit11" to 2),
            duration = 12,
            successRate = 0.85,
            cultivationPercent = 0.35
        ),
        PillRecipe(
            id = "epicSkillPill",
            name = "慧灵丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.CULTIVATION,
            description = "增加所有功法80%当前等级所需熟练度",
            materials = mapOf("spiritFlower11" to 2, "spiritFruit12" to 2, "spiritGrass12" to 2),
            duration = 12,
            successRate = 0.85,
            skillExpPercent = 0.80
        ),
        PillRecipe(
            id = "epicLifePill",
            name = "不老丹",
            tier = 4,
            rarity = 4,
            category = PillCategory.CULTIVATION,
            description = "增加35年寿元",
            materials = mapOf("spiritGrass11" to 2, "spiritFruit10" to 2, "spiritFlower10" to 2),
            duration = 12,
            successRate = 0.85,
            extendLife = 35
        )
    )

    private val tier5Recipes = listOf(
        PillRecipe(
            id = "unityPill",
            name = "合道丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.BREAKTHROUGH,
            description = "增加炼虚期突破合体期的成功概率30%",
            materials = mapOf("spiritGrass13" to 2, "spiritFlower14" to 2, "spiritFruit13" to 2, "spiritGrass15" to 2),
            duration = 18,
            successRate = 0.80,
            breakthroughChance = 0.30,
            targetRealm = 3
        ),
        PillRecipe(
            id = "heavenEarthPill",
            name = "混元丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.CULTIVATION,
            description = "提升修炼速度150%，持续1个月，效果期间不可重复使用",
            materials = mapOf("spiritGrass14" to 2, "spiritFlower13" to 2, "spiritFruit14" to 2, "spiritFlower15" to 2),
            duration = 18,
            successRate = 0.80,
            cultivationSpeed = 2.5,
            effectDuration = 1
        ),
        PillRecipe(
            id = "legendaryPhysAtkPill",
            name = "霸力丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.BATTLE,
            description = "物理攻击+30%基础值，持续1场战斗",
            materials = mapOf("spiritGrass15" to 2, "spiritFlower15" to 2, "spiritFruit13" to 2, "spiritGrass13" to 2),
            duration = 18,
            successRate = 0.80,
            physicalAttackPercent = 0.30,
            battleCount = 1
        ),
        PillRecipe(
            id = "legendaryMagicAtkPill",
            name = "地火丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.BATTLE,
            description = "法术攻击+30%基础值，持续1场战斗",
            materials = mapOf("spiritFlower13" to 2, "spiritFruit14" to 2, "spiritGrass14" to 2, "spiritFruit15" to 2),
            duration = 18,
            successRate = 0.80,
            magicAttackPercent = 0.30,
            battleCount = 1
        ),
        PillRecipe(
            id = "legendaryPhysDefPill",
            name = "地罡丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.BATTLE,
            description = "物理防御+30%基础值，持续1场战斗",
            materials = mapOf("spiritGrass14" to 2, "spiritFlower14" to 2, "spiritFruit15" to 2, "spiritGrass15" to 2),
            duration = 18,
            successRate = 0.80,
            physicalDefensePercent = 0.30,
            battleCount = 1
        ),
        PillRecipe(
            id = "legendaryMagicDefPill",
            name = "地护丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.BATTLE,
            description = "法术防御+30%基础值，持续1场战斗",
            materials = mapOf("spiritFlower15" to 2, "spiritFruit13" to 2, "spiritGrass13" to 2, "spiritFlower14" to 2),
            duration = 18,
            successRate = 0.80,
            magicDefensePercent = 0.30,
            battleCount = 1
        ),
        PillRecipe(
            id = "legendaryHpPill",
            name = "地血丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.BATTLE,
            description = "生命值+30%基础值，持续1场战斗",
            materials = mapOf("spiritGrass13" to 2, "spiritFruit14" to 2, "spiritFlower15" to 2, "spiritFruit13" to 2),
            duration = 18,
            successRate = 0.80,
            hpPercent = 0.30,
            battleCount = 1
        ),
        PillRecipe(
            id = "legendaryMpPill",
            name = "地灵丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.BATTLE,
            description = "灵力容量+30%基础值，持续1场战斗",
            materials = mapOf("spiritFlower14" to 2, "spiritFruit15" to 2, "spiritGrass15" to 2, "spiritFlower13" to 2),
            duration = 18,
            successRate = 0.80,
            mpPercent = 0.30,
            battleCount = 1
        ),
        PillRecipe(
            id = "legendarySpeedPill",
            name = "地风丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.BATTLE,
            description = "速度+30%基础值，持续1场战斗",
            materials = mapOf("spiritGrass15" to 2, "spiritFlower13" to 2, "spiritFruit14" to 2, "spiritGrass14" to 2),
            duration = 18,
            successRate = 0.80,
            speedPercent = 0.30,
            battleCount = 1
        ),
        PillRecipe(
            id = "legendaryWarriorPill",
            name = "战圣丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.BATTLE,
            description = "物理攻击+24%，物理防御+24%，持续1场战斗",
            materials = mapOf("spiritGrass13" to 2, "spiritFruit15" to 2, "spiritFlower14" to 2, "spiritFruit14" to 2),
            duration = 18,
            successRate = 0.80,
            physicalAttackPercent = 0.24,
            physicalDefensePercent = 0.24,
            battleCount = 1
        ),
        PillRecipe(
            id = "legendaryMagePill",
            name = "法圣丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.BATTLE,
            description = "法术攻击+24%，法术防御+24%，持续1场战斗",
            materials = mapOf("spiritFlower15" to 2, "spiritFruit13" to 2, "spiritGrass14" to 2, "spiritFlower13" to 2),
            duration = 18,
            successRate = 0.80,
            magicAttackPercent = 0.24,
            magicDefensePercent = 0.24,
            battleCount = 1
        ),
        PillRecipe(
            id = "legendaryVitalityPill",
            name = "混灵丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.BATTLE,
            description = "生命值+24%，灵力容量+24%，持续1场战斗",
            materials = mapOf("spiritGrass14" to 2, "spiritFlower15" to 2, "spiritFruit13" to 2, "spiritGrass13" to 2),
            duration = 18,
            successRate = 0.80,
            hpPercent = 0.24,
            mpPercent = 0.24,
            battleCount = 1
        ),
        PillRecipe(
            id = "legendaryHpPill_heal",
            name = "涅槃丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.HEALING,
            description = "恢复70%最大生命值",
            materials = mapOf("spiritGrass15" to 2, "spiritFlower14" to 2, "spiritFruit15" to 2, "spiritGrass14" to 2),
            duration = 18,
            successRate = 0.80,
            healMaxHpPercent = 0.70
        ),
        PillRecipe(
            id = "legendaryMpPill_heal",
            name = "归元丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.HEALING,
            description = "恢复70%最大灵力",
            materials = mapOf("spiritFlower13" to 2, "spiritFruit14" to 2, "spiritGrass13" to 2, "spiritFlower15" to 2),
            duration = 18,
            successRate = 0.80,
            mpRecoverMaxMpPercent = 0.70
        ),
        PillRecipe(
            id = "legendaryCultivationPill",
            name = "玄元丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.CULTIVATION,
            description = "立即获得50%修为上限的修为",
            materials = mapOf("spiritGrass13" to 2, "spiritFlower13" to 2, "spiritFruit14" to 2, "spiritFruit15" to 2),
            duration = 18,
            successRate = 0.80,
            cultivationPercent = 0.50
        ),
        PillRecipe(
            id = "legendarySkillPill",
            name = "道悟丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.CULTIVATION,
            description = "增加所有功法100%当前等级所需熟练度",
            materials = mapOf("spiritFlower14" to 2, "spiritFruit13" to 2, "spiritGrass15" to 2, "spiritFlower15" to 2),
            duration = 18,
            successRate = 0.80,
            skillExpPercent = 1.0
        ),
        PillRecipe(
            id = "legendaryLifePill",
            name = "万寿丹",
            tier = 5,
            rarity = 5,
            category = PillCategory.CULTIVATION,
            description = "增加50年寿元",
            materials = mapOf("spiritGrass14" to 2, "spiritFruit15" to 2, "spiritFlower13" to 2, "spiritGrass15" to 2),
            duration = 18,
            successRate = 0.80,
            extendLife = 50
        )
    )

    private val tier6Recipes = listOf(
        PillRecipe(
            id = "mahayanaPill",
            name = "大乘丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BREAKTHROUGH,
            description = "增加合体期突破大乘期的成功概率30%",
            materials = mapOf("spiritGrass18" to 2, "spiritFlower18" to 2, "spiritFruit18" to 2, "spiritFruit16" to 2),
            duration = 24,
            successRate = 0.75,
            breakthroughChance = 0.30,
            targetRealm = 2
        ),
        PillRecipe(
            id = "tribulationPill",
            name = "渡劫丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BREAKTHROUGH,
            description = "增加大乘期突破渡劫期的成功概率30%",
            materials = mapOf("spiritGrass16" to 2, "spiritFlower16" to 2, "spiritFruit16" to 2, "spiritGrass17" to 2),
            duration = 24,
            successRate = 0.75,
            breakthroughChance = 0.30,
            targetRealm = 1
        ),
        PillRecipe(
            id = "ascensionPill",
            name = "登仙丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BREAKTHROUGH,
            description = "增加渡劫期飞升仙界的成功概率30%",
            materials = mapOf("spiritGrass18" to 2, "spiritFlower17" to 2, "spiritFruit18" to 2, "spiritFruit17" to 2),
            duration = 24,
            successRate = 0.75,
            breakthroughChance = 0.30,
            targetRealm = 0
        ),
        PillRecipe(
            id = "celestialPill",
            name = "仙灵丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.CULTIVATION,
            description = "提升修炼速度200%，持续1个月，效果期间不可重复使用",
            materials = mapOf("spiritGrass17" to 2, "spiritFlower18" to 2, "spiritFruit17" to 2, "spiritFlower16" to 2),
            duration = 24,
            successRate = 0.75,
            cultivationSpeed = 3.0,
            effectDuration = 1
        ),
        PillRecipe(
            id = "mythicPhysAtkPill",
            name = "天力丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BATTLE,
            description = "物理攻击+40%基础值，持续1场战斗",
            materials = mapOf("spiritGrass18" to 2, "spiritFlower18" to 2, "spiritFruit16" to 2, "spiritGrass16" to 2),
            duration = 24,
            successRate = 0.75,
            physicalAttackPercent = 0.40,
            battleCount = 1
        ),
        PillRecipe(
            id = "mythicMagicAtkPill",
            name = "天火丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BATTLE,
            description = "法术攻击+40%基础值，持续1场战斗",
            materials = mapOf("spiritFlower16" to 2, "spiritFruit17" to 2, "spiritGrass17" to 2, "spiritFruit18" to 2),
            duration = 24,
            successRate = 0.75,
            magicAttackPercent = 0.40,
            battleCount = 1
        ),
        PillRecipe(
            id = "mythicPhysDefPill",
            name = "天罡丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BATTLE,
            description = "物理防御+40%基础值，持续1场战斗",
            materials = mapOf("spiritGrass17" to 2, "spiritFlower17" to 2, "spiritFruit18" to 2, "spiritGrass18" to 2),
            duration = 24,
            successRate = 0.75,
            physicalDefensePercent = 0.40,
            battleCount = 1
        ),
        PillRecipe(
            id = "mythicMagicDefPill",
            name = "天护丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BATTLE,
            description = "法术防御+40%基础值，持续1场战斗",
            materials = mapOf("spiritFlower18" to 2, "spiritFruit16" to 2, "spiritGrass16" to 2, "spiritFlower17" to 2),
            duration = 24,
            successRate = 0.75,
            magicDefensePercent = 0.40,
            battleCount = 1
        ),
        PillRecipe(
            id = "mythicHpPill",
            name = "天血丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BATTLE,
            description = "生命值+40%基础值，持续1场战斗",
            materials = mapOf("spiritGrass16" to 2, "spiritFruit17" to 2, "spiritFlower18" to 2, "spiritFruit16" to 2),
            duration = 24,
            successRate = 0.75,
            hpPercent = 0.40,
            battleCount = 1
        ),
        PillRecipe(
            id = "mythicMpPill",
            name = "天灵丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BATTLE,
            description = "灵力容量+40%基础值，持续1场战斗",
            materials = mapOf("spiritFlower17" to 2, "spiritFruit18" to 2, "spiritGrass18" to 2, "spiritFlower16" to 2),
            duration = 24,
            successRate = 0.75,
            mpPercent = 0.40,
            battleCount = 1
        ),
        PillRecipe(
            id = "mythicSpeedPill",
            name = "天风丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BATTLE,
            description = "速度+40%基础值，持续1场战斗",
            materials = mapOf("spiritGrass18" to 2, "spiritFlower16" to 2, "spiritFruit17" to 2, "spiritGrass17" to 2),
            duration = 24,
            successRate = 0.75,
            speedPercent = 0.40,
            battleCount = 1
        ),
        PillRecipe(
            id = "mythicWarriorPill",
            name = "战神丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BATTLE,
            description = "物理攻击+32%，物理防御+32%，持续1场战斗",
            materials = mapOf("spiritGrass16" to 2, "spiritFruit18" to 2, "spiritFlower17" to 2, "spiritFruit17" to 2),
            duration = 24,
            successRate = 0.75,
            physicalAttackPercent = 0.32,
            physicalDefensePercent = 0.32,
            battleCount = 1
        ),
        PillRecipe(
            id = "mythicMagePill",
            name = "法神丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BATTLE,
            description = "法术攻击+32%，法术防御+32%，持续1场战斗",
            materials = mapOf("spiritFlower18" to 2, "spiritFruit16" to 2, "spiritGrass17" to 2, "spiritFlower16" to 2),
            duration = 24,
            successRate = 0.75,
            magicAttackPercent = 0.32,
            magicDefensePercent = 0.32,
            battleCount = 1
        ),
        PillRecipe(
            id = "mythicVitalityPill",
            name = "圣灵丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.BATTLE,
            description = "生命值+32%，灵力容量+32%，持续1场战斗",
            materials = mapOf("spiritGrass17" to 2, "spiritFlower18" to 2, "spiritFruit16" to 2, "spiritGrass16" to 2),
            duration = 24,
            successRate = 0.75,
            hpPercent = 0.32,
            mpPercent = 0.32,
            battleCount = 1
        ),
        PillRecipe(
            id = "mythicHpPill_heal",
            name = "九转还魂丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.HEALING,
            description = "恢复85%最大生命值",
            materials = mapOf("spiritGrass18" to 2, "spiritFlower17" to 2, "spiritFruit18" to 2, "spiritGrass17" to 2),
            duration = 24,
            successRate = 0.75,
            healMaxHpPercent = 0.85
        ),
        PillRecipe(
            id = "mythicMpPill_heal",
            name = "九转归元丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.HEALING,
            description = "恢复85%最大灵力",
            materials = mapOf("spiritFlower16" to 2, "spiritFruit18" to 2, "spiritGrass16" to 2, "spiritFlower18" to 2),
            duration = 24,
            successRate = 0.75,
            mpRecoverMaxMpPercent = 0.85
        ),
        PillRecipe(
            id = "mythicCultivationPill",
            name = "仙元丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.CULTIVATION,
            description = "立即获得80%修为上限的修为",
            materials = mapOf("spiritGrass16" to 2, "spiritFlower16" to 2, "spiritFruit17" to 2, "spiritFruit18" to 2),
            duration = 24,
            successRate = 0.75,
            cultivationPercent = 0.80
        ),
        PillRecipe(
            id = "mythicSkillPill",
            name = "天机丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.CULTIVATION,
            description = "增加所有功法150%当前等级所需熟练度",
            materials = mapOf("spiritFlower17" to 2, "spiritFruit16" to 2, "spiritGrass18" to 2, "spiritFlower18" to 2),
            duration = 24,
            successRate = 0.75,
            skillExpPercent = 1.5
        ),
        PillRecipe(
            id = "mythicLifePill",
            name = "永生丹",
            tier = 6,
            rarity = 6,
            category = PillCategory.CULTIVATION,
            description = "增加80年寿元",
            materials = mapOf("spiritGrass17" to 2, "spiritFruit18" to 2, "spiritFlower16" to 2, "spiritGrass18" to 2),
            duration = 24,
            successRate = 0.75,
            extendLife = 80
        )
    )

    private val allRecipes = tier1Recipes + tier2Recipes + tier3Recipes + tier4Recipes + tier5Recipes + tier6Recipes

    fun getAllRecipes(): List<PillRecipe> = allRecipes

    fun getRecipeById(id: String): PillRecipe? = allRecipes.find { it.id == id }

    fun getRecipeByName(name: String): PillRecipe? = allRecipes.find { it.name == name }

    fun getRecipesByMaterial(materialId: String): List<PillRecipe> = allRecipes.filter { it.materials.containsKey(materialId) }

    fun getRecipesByHerb(herbId: String): List<PillRecipe> = allRecipes.filter { it.materials.containsKey(herbId) }

    fun getRecipesByTier(tier: Int): List<PillRecipe> {
        return when (tier) {
            1 -> tier1Recipes
            2 -> tier2Recipes
            3 -> tier3Recipes
            4 -> tier4Recipes
            5 -> tier5Recipes
            6 -> tier6Recipes
            else -> emptyList()
        }
    }

    fun getDurationByTier(tier: Int): Int {
        return when (tier) {
            1 -> 2   // 一阶丹药: 2个月
            2 -> 5   // 二阶丹药: 5个月
            3 -> 9   // 三阶丹药: 9个月
            4 -> 12  // 四阶丹药: 12个月
            5 -> 18  // 五阶丹药: 18个月
            6 -> 24  // 六阶丹药: 24个月
            else -> 2
        }
    }

    fun getTierName(tier: Int): String {
        return when (tier) {
            1 -> "凡品"
            2 -> "灵品"
            3 -> "宝品"
            4 -> "玄品"
            5 -> "地品"
            6 -> "天品"
            else -> "未知"
        }
    }
}
