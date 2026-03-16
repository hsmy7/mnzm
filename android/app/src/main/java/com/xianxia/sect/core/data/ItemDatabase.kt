package com.xianxia.sect.core.data

import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import kotlin.random.Random

object ItemDatabase {
    
    data class PillTemplate(
        val id: String,
        val name: String,
        val category: PillCategory,
        val rarity: Int,
        val description: String,
        val price: Int = 0,
        val breakthroughChance: Double = 0.0,
        val targetRealm: Int = 0,
        val isAscension: Boolean = false,
        val cultivationSpeed: Double = 1.0,
        val duration: Int = 0,
        val cannotStack: Boolean = false,
        val cultivationPercent: Double = 0.0,
        val skillExpPercent: Double = 0.0,
        val extendLife: Int = 0,
        val physicalAttackPercent: Double = 0.0,
        val magicAttackPercent: Double = 0.0,
        val physicalDefensePercent: Double = 0.0,
        val magicDefensePercent: Double = 0.0,
        val hpPercent: Double = 0.0,
        val mpPercent: Double = 0.0,
        val speedPercent: Double = 0.0,
        val healMaxHpPercent: Double = 0.0,
        val healPercent: Double = 0.0,
        val heal: Int = 0,
        val battleCount: Int = 0,
        val revive: Boolean = false,
        val clearAll: Boolean = false,
        val mpRecoverMaxMpPercent: Double = 0.0
    )
    
    data class MaterialTemplate(
        val id: String,
        val name: String,
        val category: MaterialCategory,
        val rarity: Int,
        val description: String,
        val price: Int = 0
    )
    
    val breakthroughPills: Map<String, PillTemplate> = mapOf(
        "foundationPill" to PillTemplate(
            id = "foundationPill",
            name = "筑基丹",
            category = PillCategory.BREAKTHROUGH,
            rarity = 2,
            description = "增加炼气期突破筑基期的成功概率30%",
            price = 16200,
            breakthroughChance = 0.30,
            targetRealm = 8
        ),
        "goldenCorePill" to PillTemplate(
            id = "goldenCorePill",
            name = "凝金丹",
            category = PillCategory.BREAKTHROUGH,
            rarity = 2,
            description = "增加筑基期突破金丹期的成功概率30%",
            price = 16200,
            breakthroughChance = 0.30,
            targetRealm = 7
        ),
        "nascentSoulPill" to PillTemplate(
            id = "nascentSoulPill",
            name = "结婴丹",
            category = PillCategory.BREAKTHROUGH,
            rarity = 2,
            description = "增加金丹期突破元婴期的成功概率30%",
            price = 16200,
            breakthroughChance = 0.30,
            targetRealm = 6
        ),
        "spiritSeveringPill" to PillTemplate(
            id = "spiritSeveringPill",
            name = "化神丹",
            category = PillCategory.BREAKTHROUGH,
            rarity = 3,
            description = "增加元婴期突破化神期的成功概率30%",
            price = 64800,
            breakthroughChance = 0.30,
            targetRealm = 5
        ),
        "voidBreakPill" to PillTemplate(
            id = "voidBreakPill",
            name = "破虚丹",
            category = PillCategory.BREAKTHROUGH,
            rarity = 3,
            description = "增加化神期突破炼虚期的成功概率30%",
            price = 64800,
            breakthroughChance = 0.30,
            targetRealm = 4
        ),
        "unityPill" to PillTemplate(
            id = "unityPill",
            name = "合道丹",
            category = PillCategory.BREAKTHROUGH,
            rarity = 5,
            description = "增加炼虚期突破合体期的成功概率30%",
            price = 1036800,
            breakthroughChance = 0.30,
            targetRealm = 3
        ),
        "mahayanaPill" to PillTemplate(
            id = "mahayanaPill",
            name = "大乘丹",
            category = PillCategory.BREAKTHROUGH,
            rarity = 6,
            description = "增加合体期突破大乘期的成功概率30%",
            price = 4147200,
            breakthroughChance = 0.30,
            targetRealm = 2
        ),
        "tribulationPill" to PillTemplate(
            id = "tribulationPill",
            name = "渡劫丹",
            category = PillCategory.BREAKTHROUGH,
            rarity = 6,
            description = "增加大乘期突破渡劫期的成功概率30%",
            price = 4147200,
            breakthroughChance = 0.30,
            targetRealm = 1
        ),
        "ascensionPill" to PillTemplate(
            id = "ascensionPill",
            name = "登仙丹",
            category = PillCategory.BREAKTHROUGH,
            rarity = 6,
            description = "增加渡劫期飞升仙界的成功概率30%",
            price = 4147200,
            breakthroughChance = 0.30,
            isAscension = true,
            targetRealm = 0
        )
    )
    
    val cultivationPills: Map<String, PillTemplate> = mapOf(
        "spiritGatheringPill" to PillTemplate(
            id = "spiritGatheringPill",
            name = "引灵丹",
            category = PillCategory.CULTIVATION,
            rarity = 1,
            description = "提升修炼速度20%，持续半年，效果期间不可重复使用",
            price = 3600,
            cultivationSpeed = 1.2,
            duration = 6,
            cannotStack = true
        ),
        "spiritCondensingPill" to PillTemplate(
            id = "spiritCondensingPill",
            name = "聚灵丹",
            category = PillCategory.CULTIVATION,
            rarity = 2,
            description = "提升修炼速度40%，持续半年，效果期间不可重复使用",
            price = 16200,
            cultivationSpeed = 1.4,
            duration = 6,
            cannotStack = true
        ),
        "essenceGatheringPill" to PillTemplate(
            id = "essenceGatheringPill",
            name = "凝元丹",
            category = PillCategory.CULTIVATION,
            rarity = 3,
            description = "提升修炼速度60%，持续半年，效果期间不可重复使用",
            price = 64800,
            cultivationSpeed = 1.6,
            duration = 6,
            cannotStack = true
        ),
        "spiritRefiningPill" to PillTemplate(
            id = "spiritRefiningPill",
            name = "炼气丹",
            category = PillCategory.CULTIVATION,
            rarity = 4,
            description = "提升修炼速度100%，持续半年，效果期间不可重复使用",
            price = 259200,
            cultivationSpeed = 2.0,
            duration = 6,
            cannotStack = true
        ),
        "heavenEarthPill" to PillTemplate(
            id = "heavenEarthPill",
            name = "混元丹",
            category = PillCategory.CULTIVATION,
            rarity = 5,
            description = "提升修炼速度150%，持续半年，效果期间不可重复使用",
            price = 1036800,
            cultivationSpeed = 2.5,
            duration = 6,
            cannotStack = true
        ),
        "celestialPill" to PillTemplate(
            id = "celestialPill",
            name = "仙灵丹",
            category = PillCategory.CULTIVATION,
            rarity = 6,
            description = "提升修炼速度200%，持续半年，效果期间不可重复使用",
            price = 4147200,
            cultivationSpeed = 3.0,
            duration = 6,
            cannotStack = true
        )
    )
    
    val battlePills: Map<String, PillTemplate> = mapOf(
        "commonPhysAtkPill" to PillTemplate("commonPhysAtkPill", "虎力丹", PillCategory.BATTLE, 1, "物理攻击+10%，持续1场战斗", 3600, physicalAttackPercent = 0.10, battleCount = 1),
        "commonMagicAtkPill" to PillTemplate("commonMagicAtkPill", "灵火丹", PillCategory.BATTLE, 1, "法术攻击+10%，持续1场战斗", 3600, magicAttackPercent = 0.10, battleCount = 1),
        "commonPhysDefPill" to PillTemplate("commonPhysDefPill", "铁甲丹", PillCategory.BATTLE, 1, "物理防御+10%，持续1场战斗", 3600, physicalDefensePercent = 0.10, battleCount = 1),
        "commonMagicDefPill" to PillTemplate("commonMagicDefPill", "灵盾丹", PillCategory.BATTLE, 1, "法术防御+10%，持续1场战斗", 3600, magicDefensePercent = 0.10, battleCount = 1),
        "commonHpPill" to PillTemplate("commonHpPill", "气血丹", PillCategory.BATTLE, 1, "生命+10%，持续1场战斗", 3600, hpPercent = 0.10, battleCount = 1),
        "commonMpPill" to PillTemplate("commonMpPill", "回灵丹", PillCategory.BATTLE, 1, "灵力+10%，持续1场战斗", 3600, mpPercent = 0.10, battleCount = 1),
        "commonSpeedPill" to PillTemplate("commonSpeedPill", "疾风丹", PillCategory.BATTLE, 1, "速度+10%，持续1场战斗", 3600, speedPercent = 0.10, battleCount = 1),
        "commonWarriorPill" to PillTemplate("commonWarriorPill", "战体丹", PillCategory.BATTLE, 1, "物理攻击+8%，物理防御+8%，持续1场战斗", 4320, physicalAttackPercent = 0.08, physicalDefensePercent = 0.08, battleCount = 1),
        "commonMagePill" to PillTemplate("commonMagePill", "法体丹", PillCategory.BATTLE, 1, "法术攻击+8%，法术防御+8%，持续1场战斗", 4320, magicAttackPercent = 0.08, magicDefensePercent = 0.08, battleCount = 1),
        "commonVitalityPill" to PillTemplate("commonVitalityPill", "生灵丹", PillCategory.BATTLE, 1, "生命+8%，灵力+8%，持续1场战斗", 4320, hpPercent = 0.08, mpPercent = 0.08, battleCount = 1),

        "uncommonPhysAtkPill" to PillTemplate("uncommonPhysAtkPill", "熊力丹", PillCategory.BATTLE, 2, "物理攻击+15%，持续1场战斗", 16200, physicalAttackPercent = 0.15, battleCount = 1),
        "uncommonMagicAtkPill" to PillTemplate("uncommonMagicAtkPill", "真火丹", PillCategory.BATTLE, 2, "法术攻击+15%，持续1场战斗", 16200, magicAttackPercent = 0.15, battleCount = 1),
        "uncommonPhysDefPill" to PillTemplate("uncommonPhysDefPill", "铜墙丹", PillCategory.BATTLE, 2, "物理防御+15%，持续1场战斗", 16200, physicalDefensePercent = 0.15, battleCount = 1),
        "uncommonMagicDefPill" to PillTemplate("uncommonMagicDefPill", "法盾丹", PillCategory.BATTLE, 2, "法术防御+15%，持续1场战斗", 16200, magicDefensePercent = 0.15, battleCount = 1),
        "uncommonHpPill" to PillTemplate("uncommonHpPill", "血精丹", PillCategory.BATTLE, 2, "生命+15%，持续1场战斗", 16200, hpPercent = 0.15, battleCount = 1),
        "uncommonMpPill" to PillTemplate("uncommonMpPill", "汇灵丹", PillCategory.BATTLE, 2, "灵力+15%，持续1场战斗", 16200, mpPercent = 0.15, battleCount = 1),
        "uncommonSpeedPill" to PillTemplate("uncommonSpeedPill", "迅风丹", PillCategory.BATTLE, 2, "速度+15%，持续1场战斗", 16200, speedPercent = 0.15, battleCount = 1),
        "uncommonWarriorPill" to PillTemplate("uncommonWarriorPill", "战魂丹", PillCategory.BATTLE, 2, "物理攻击+12%，物理防御+12%，持续1场战斗", 19440, physicalAttackPercent = 0.12, physicalDefensePercent = 0.12, battleCount = 1),
        "uncommonMagePill" to PillTemplate("uncommonMagePill", "法魂丹", PillCategory.BATTLE, 2, "法术攻击+12%，法术防御+12%，持续1场战斗", 19440, magicAttackPercent = 0.12, magicDefensePercent = 0.12, battleCount = 1),
        "uncommonVitalityPill" to PillTemplate("uncommonVitalityPill", "命灵丹", PillCategory.BATTLE, 2, "生命+12%，灵力+12%，持续1场战斗", 19440, hpPercent = 0.12, mpPercent = 0.12, battleCount = 1),

        "rarePhysAtkPill" to PillTemplate("rarePhysAtkPill", "龙力丹", PillCategory.BATTLE, 3, "物理攻击+20%，持续1场战斗", 64800, physicalAttackPercent = 0.20, battleCount = 1),
        "rareMagicAtkPill" to PillTemplate("rareMagicAtkPill", "三昧丹", PillCategory.BATTLE, 3, "法术攻击+20%，持续1场战斗", 64800, magicAttackPercent = 0.20, battleCount = 1),
        "rarePhysDefPill" to PillTemplate("rarePhysDefPill", "金刚丹", PillCategory.BATTLE, 3, "物理防御+20%，持续1场战斗", 64800, physicalDefensePercent = 0.20, battleCount = 1),
        "rareMagicDefPill" to PillTemplate("rareMagicDefPill", "神盾丹", PillCategory.BATTLE, 3, "法术防御+20%，持续1场战斗", 64800, magicDefensePercent = 0.20, battleCount = 1),
        "rareHpPill" to PillTemplate("rareHpPill", "血魂丹", PillCategory.BATTLE, 3, "生命+20%，持续1场战斗", 64800, hpPercent = 0.20, battleCount = 1),
        "rareMpPill" to PillTemplate("rareMpPill", "凝灵丹", PillCategory.BATTLE, 3, "灵力+20%，持续1场战斗", 64800, mpPercent = 0.20, battleCount = 1),
        "rareSpeedPill" to PillTemplate("rareSpeedPill", "神风丹", PillCategory.BATTLE, 3, "速度+20%，持续1场战斗", 64800, speedPercent = 0.20, battleCount = 1),
        "rareWarriorPill" to PillTemplate("rareWarriorPill", "战意丹", PillCategory.BATTLE, 3, "物理攻击+16%，物理防御+16%，持续1场战斗", 77760, physicalAttackPercent = 0.16, physicalDefensePercent = 0.16, battleCount = 1),
        "rareMagePill" to PillTemplate("rareMagePill", "法意丹", PillCategory.BATTLE, 3, "法术攻击+16%，法术防御+16%，持续1场战斗", 77760, magicAttackPercent = 0.16, magicDefensePercent = 0.16, battleCount = 1),
        "rareVitalityPill" to PillTemplate("rareVitalityPill", "元灵丹", PillCategory.BATTLE, 3, "生命+16%，灵力+16%，持续1场战斗", 77760, hpPercent = 0.16, mpPercent = 0.16, battleCount = 1),

        "epicPhysAtkPill" to PillTemplate("epicPhysAtkPill", "神力丹", PillCategory.BATTLE, 4, "物理攻击+25%，持续1场战斗", 259200, physicalAttackPercent = 0.25, battleCount = 1),
        "epicMagicAtkPill" to PillTemplate("epicMagicAtkPill", "玄火丹", PillCategory.BATTLE, 4, "法术攻击+25%，持续1场战斗", 259200, magicAttackPercent = 0.25, battleCount = 1),
        "epicPhysDefPill" to PillTemplate("epicPhysDefPill", "玄盾丹", PillCategory.BATTLE, 4, "物理防御+25%，持续1场战斗", 259200, physicalDefensePercent = 0.25, battleCount = 1),
        "epicMagicDefPill" to PillTemplate("epicMagicDefPill", "玄罡丹", PillCategory.BATTLE, 4, "法术防御+25%，持续1场战斗", 259200, magicDefensePercent = 0.25, battleCount = 1),
        "epicHpPill" to PillTemplate("epicHpPill", "玄血丹", PillCategory.BATTLE, 4, "生命+25%，持续1场战斗", 259200, hpPercent = 0.25, battleCount = 1),
        "epicMpPill" to PillTemplate("epicMpPill", "玄灵丹", PillCategory.BATTLE, 4, "灵力+25%，持续1场战斗", 259200, mpPercent = 0.25, battleCount = 1),
        "epicSpeedPill" to PillTemplate("epicSpeedPill", "玄风丹", PillCategory.BATTLE, 4, "速度+25%，持续1场战斗", 259200, speedPercent = 0.25, battleCount = 1),
        "epicWarriorPill" to PillTemplate("epicWarriorPill", "战心丹", PillCategory.BATTLE, 4, "物理攻击+20%，物理防御+20%，持续1场战斗", 311040, physicalAttackPercent = 0.20, physicalDefensePercent = 0.20, battleCount = 1),
        "epicMagePill" to PillTemplate("epicMagePill", "法心丹", PillCategory.BATTLE, 4, "法术攻击+20%，法术防御+20%，持续1场战斗", 311040, magicAttackPercent = 0.20, magicDefensePercent = 0.20, battleCount = 1),
        "epicVitalityPill" to PillTemplate("epicVitalityPill", "真灵丹", PillCategory.BATTLE, 4, "生命+20%，灵力+20%，持续1场战斗", 311040, hpPercent = 0.20, mpPercent = 0.20, battleCount = 1),

        "legendaryPhysAtkPill" to PillTemplate("legendaryPhysAtkPill", "霸力丹", PillCategory.BATTLE, 5, "物理攻击+30%，持续1场战斗", 1036800, physicalAttackPercent = 0.30, battleCount = 1),
        "legendaryMagicAtkPill" to PillTemplate("legendaryMagicAtkPill", "地火丹", PillCategory.BATTLE, 5, "法术攻击+30%，持续1场战斗", 1036800, magicAttackPercent = 0.30, battleCount = 1),
        "legendaryPhysDefPill" to PillTemplate("legendaryPhysDefPill", "地罡丹", PillCategory.BATTLE, 5, "物理防御+30%，持续1场战斗", 1036800, physicalDefensePercent = 0.30, battleCount = 1),
        "legendaryMagicDefPill" to PillTemplate("legendaryMagicDefPill", "地护丹", PillCategory.BATTLE, 5, "法术防御+30%，持续1场战斗", 1036800, magicDefensePercent = 0.30, battleCount = 1),
        "legendaryHpPill" to PillTemplate("legendaryHpPill", "地血丹", PillCategory.BATTLE, 5, "生命+30%，持续1场战斗", 1036800, hpPercent = 0.30, battleCount = 1),
        "legendaryMpPill" to PillTemplate("legendaryMpPill", "地灵丹", PillCategory.BATTLE, 5, "灵力+30%，持续1场战斗", 1036800, mpPercent = 0.30, battleCount = 1),
        "legendarySpeedPill" to PillTemplate("legendarySpeedPill", "地风丹", PillCategory.BATTLE, 5, "速度+30%，持续1场战斗", 1036800, speedPercent = 0.30, battleCount = 1),
        "legendaryWarriorPill" to PillTemplate("legendaryWarriorPill", "战圣丹", PillCategory.BATTLE, 5, "物理攻击+24%，物理防御+24%，持续1场战斗", 1244160, physicalAttackPercent = 0.24, physicalDefensePercent = 0.24, battleCount = 1),
        "legendaryMagePill" to PillTemplate("legendaryMagePill", "法圣丹", PillCategory.BATTLE, 5, "法术攻击+24%，法术防御+24%，持续1场战斗", 1244160, magicAttackPercent = 0.24, magicDefensePercent = 0.24, battleCount = 1),
        "legendaryVitalityPill" to PillTemplate("legendaryVitalityPill", "混灵丹", PillCategory.BATTLE, 5, "生命+24%，灵力+24%，持续1场战斗", 1244160, hpPercent = 0.24, mpPercent = 0.24, battleCount = 1),

        "mythicPhysAtkPill" to PillTemplate("mythicPhysAtkPill", "天力丹", PillCategory.BATTLE, 6, "物理攻击+40%，持续1场战斗", 4147200, physicalAttackPercent = 0.40, battleCount = 1),
        "mythicMagicAtkPill" to PillTemplate("mythicMagicAtkPill", "天火丹", PillCategory.BATTLE, 6, "法术攻击+40%，持续1场战斗", 4147200, magicAttackPercent = 0.40, battleCount = 1),
        "mythicPhysDefPill" to PillTemplate("mythicPhysDefPill", "天罡丹", PillCategory.BATTLE, 6, "物理防御+40%，持续1场战斗", 4147200, physicalDefensePercent = 0.40, battleCount = 1),
        "mythicMagicDefPill" to PillTemplate("mythicMagicDefPill", "天护丹", PillCategory.BATTLE, 6, "法术防御+40%，持续1场战斗", 4147200, magicDefensePercent = 0.40, battleCount = 1),
        "mythicHpPill" to PillTemplate("mythicHpPill", "天血丹", PillCategory.BATTLE, 6, "生命+40%，持续1场战斗", 4147200, hpPercent = 0.40, battleCount = 1),
        "mythicMpPill" to PillTemplate("mythicMpPill", "天灵丹", PillCategory.BATTLE, 6, "灵力+40%，持续1场战斗", 4147200, mpPercent = 0.40, battleCount = 1),
        "mythicSpeedPill" to PillTemplate("mythicSpeedPill", "天风丹", PillCategory.BATTLE, 6, "速度+40%，持续1场战斗", 4147200, speedPercent = 0.40, battleCount = 1),
        "mythicWarriorPill" to PillTemplate("mythicWarriorPill", "战神丹", PillCategory.BATTLE, 6, "物理攻击+32%，物理防御+32%，持续1场战斗", 4976640, physicalAttackPercent = 0.32, physicalDefensePercent = 0.32, battleCount = 1),
        "mythicMagePill" to PillTemplate("mythicMagePill", "法神丹", PillCategory.BATTLE, 6, "法术攻击+32%，法术防御+32%，持续1场战斗", 4976640, magicAttackPercent = 0.32, magicDefensePercent = 0.32, battleCount = 1),
        "mythicVitalityPill" to PillTemplate("mythicVitalityPill", "圣灵丹", PillCategory.BATTLE, 6, "生命+32%，灵力+32%，持续1场战斗", 4976640, hpPercent = 0.32, mpPercent = 0.32, battleCount = 1)
    )
    
    val healingPills: Map<String, PillTemplate> = mapOf(
        "commonHpPill_heal" to PillTemplate("commonHpPill_heal", "回春丹", PillCategory.HEALING, 1, "恢复30%生命值", 1800, healMaxHpPercent = 0.30),
        "uncommonHpPill_heal" to PillTemplate("uncommonHpPill_heal", "复元丹", PillCategory.HEALING, 2, "恢复50%生命值", 8100, healMaxHpPercent = 0.50),
        "rareHpPill_heal" to PillTemplate("rareHpPill_heal", "回生丹", PillCategory.HEALING, 3, "恢复70%生命值", 32400, healMaxHpPercent = 0.70),
        "epicHpPill_heal" to PillTemplate("epicHpPill_heal", "还魂丹", PillCategory.HEALING, 4, "恢复90%生命值", 129600, healMaxHpPercent = 0.90),
        "legendaryHpPill_heal" to PillTemplate("legendaryHpPill_heal", "涅槃丹", PillCategory.HEALING, 5, "恢复100%生命值", 518400, healMaxHpPercent = 1.0),
        "mythicHpPill_heal" to PillTemplate("mythicHpPill_heal", "九转还魂丹", PillCategory.HEALING, 6, "恢复100%生命值并清除所有负面状态", 2073600, healMaxHpPercent = 1.0, clearAll = true),

        "commonMpPill_heal" to PillTemplate("commonMpPill_heal", "补气丹", PillCategory.HEALING, 1, "恢复30%灵力", 1800, mpRecoverMaxMpPercent = 0.30),
        "uncommonMpPill_heal" to PillTemplate("uncommonMpPill_heal", "复灵丹", PillCategory.HEALING, 2, "恢复50%灵力", 8100, mpRecoverMaxMpPercent = 0.50),
        "rareMpPill_heal" to PillTemplate("rareMpPill_heal", "凝神丹", PillCategory.HEALING, 3, "恢复70%灵力", 32400, mpRecoverMaxMpPercent = 0.70),
        "epicMpPill_heal" to PillTemplate("epicMpPill_heal", "聚魂丹", PillCategory.HEALING, 4, "恢复90%灵力", 129600, mpRecoverMaxMpPercent = 0.90),
        "legendaryMpPill_heal" to PillTemplate("legendaryMpPill_heal", "归元丹", PillCategory.HEALING, 5, "恢复100%灵力", 518400, mpRecoverMaxMpPercent = 1.0),
        "mythicMpPill_heal" to PillTemplate("mythicMpPill_heal", "九转归元丹", PillCategory.HEALING, 6, "恢复100%灵力并清除所有负面状态", 2073600, mpRecoverMaxMpPercent = 1.0, clearAll = true)
    )
    
    val functionPills: Map<String, PillTemplate> = mapOf(
        "commonCultivationPill" to PillTemplate("commonCultivationPill", "增元丹", PillCategory.CULTIVATION, 1, "立即获得5%修为上限的修为", 3600, cultivationPercent = 0.05),
        "commonSkillPill" to PillTemplate("commonSkillPill", "悟道丹", PillCategory.CULTIVATION, 1, "增加所有功法20%当前等级所需熟练度", 3600, skillExpPercent = 0.20),
        "commonLifePill" to PillTemplate("commonLifePill", "延寿丹", PillCategory.CULTIVATION, 1, "增加5年寿元", 3600, extendLife = 5),
        "uncommonCultivationPill" to PillTemplate("uncommonCultivationPill", "培元丹", PillCategory.CULTIVATION, 2, "立即获得10%修为上限的修为", 16200, cultivationPercent = 0.10),
        "uncommonSkillPill" to PillTemplate("uncommonSkillPill", "明心丹", PillCategory.CULTIVATION, 2, "增加所有功法40%当前等级所需熟练度", 16200, skillExpPercent = 0.40),
        "uncommonLifePill" to PillTemplate("uncommonLifePill", "续命丹", PillCategory.CULTIVATION, 2, "增加10年寿元", 16200, extendLife = 10),
        "rareCultivationPill" to PillTemplate("rareCultivationPill", "固元丹", PillCategory.CULTIVATION, 3, "立即获得20%修为上限的修为", 64800, cultivationPercent = 0.20),
        "rareSkillPill" to PillTemplate("rareSkillPill", "通玄丹", PillCategory.CULTIVATION, 3, "增加所有功法60%当前等级所需熟练度", 64800, skillExpPercent = 0.60),
        "rareLifePill" to PillTemplate("rareLifePill", "长生丹", PillCategory.CULTIVATION, 3, "增加20年寿元", 64800, extendLife = 20),
        "epicCultivationPill" to PillTemplate("epicCultivationPill", "真元丹", PillCategory.CULTIVATION, 4, "立即获得35%修为上限的修为", 259200, cultivationPercent = 0.35),
        "epicSkillPill" to PillTemplate("epicSkillPill", "慧灵丹", PillCategory.CULTIVATION, 4, "增加所有功法80%当前等级所需熟练度", 259200, skillExpPercent = 0.80),
        "epicLifePill" to PillTemplate("epicLifePill", "不老丹", PillCategory.CULTIVATION, 4, "增加35年寿元", 259200, extendLife = 35),
        "legendaryCultivationPill" to PillTemplate("legendaryCultivationPill", "玄元丹", PillCategory.CULTIVATION, 5, "立即获得50%修为上限的修为", 1036800, cultivationPercent = 0.50),
        "legendarySkillPill" to PillTemplate("legendarySkillPill", "道悟丹", PillCategory.CULTIVATION, 5, "增加所有功法100%当前等级所需熟练度", 1036800, skillExpPercent = 1.0),
        "legendaryLifePill" to PillTemplate("legendaryLifePill", "万寿丹", PillCategory.CULTIVATION, 5, "增加50年寿元", 1036800, extendLife = 50),
        "mythicCultivationPill" to PillTemplate("mythicCultivationPill", "仙元丹", PillCategory.CULTIVATION, 6, "立即获得80%修为上限的修为", 4147200, cultivationPercent = 0.80),
        "mythicSkillPill" to PillTemplate("mythicSkillPill", "天机丹", PillCategory.CULTIVATION, 6, "增加所有功法150%当前等级所需熟练度", 4147200, skillExpPercent = 1.5),
        "mythicLifePill" to PillTemplate("mythicLifePill", "永生丹", PillCategory.CULTIVATION, 6, "增加80年寿元", 4147200, extendLife = 80)
    )
    
    val allPills: Map<String, PillTemplate> by lazy {
        breakthroughPills + cultivationPills + battlePills + healingPills + functionPills
    }
    
    // 从BeastMaterialDatabase导入所有材料，确保锻造配方所需材料都能被玩家获得
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
            breakthroughChance = template.breakthroughChance,
            targetRealm = template.targetRealm,
            isAscension = template.isAscension,
            cultivationSpeed = template.cultivationSpeed,
            duration = template.duration,
            cannotStack = template.cannotStack,
            cultivationPercent = template.cultivationPercent,
            skillExpPercent = template.skillExpPercent,
            extendLife = template.extendLife,
            physicalAttackPercent = template.physicalAttackPercent,
            magicAttackPercent = template.magicAttackPercent,
            physicalDefensePercent = template.physicalDefensePercent,
            magicDefensePercent = template.magicDefensePercent,
            hpPercent = template.hpPercent,
            mpPercent = template.mpPercent,
            speedPercent = template.speedPercent,
            healMaxHpPercent = template.healMaxHpPercent,
            healPercent = template.healPercent,
            heal = template.heal,
            battleCount = template.battleCount,
            revive = template.revive,
            clearAll = template.clearAll,
            mpRecoverMaxMpPercent = template.mpRecoverMaxMpPercent,
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
