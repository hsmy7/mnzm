package com.xianxia.sect.core.data

import com.xianxia.sect.core.model.EquipmentSlot

object ForgeRecipeDatabase {
    
    data class ForgeRecipe(
        val id: String,
        val name: String,
        val type: EquipmentSlot,
        val tier: Int,
        val rarity: Int,
        val description: String,
        val materials: Map<String, Int>,
        val duration: Int,
        val successRate: Double
    )

    private val tier1Recipes = listOf(
        ForgeRecipe("ironSword", "精铁剑", EquipmentSlot.WEAPON, 1, 1, "普通铁匠打造的精铁剑", mapOf("tigerBone0" to 3, "tigerTooth0" to 2), 2, 0.70),
        ForgeRecipe("bronzeDagger", "青铜匕首", EquipmentSlot.WEAPON, 1, 1, "青铜铸造的短刃", mapOf("tigerTooth0" to 4, "eagleClaw0" to 2), 2, 0.70),
        ForgeRecipe("woodenStaff", "桃木杖", EquipmentSlot.WEAPON, 1, 1, "百年桃木制成的法杖", mapOf("snakeBone0" to 3, "snakeCore0" to 2), 2, 0.70),
        ForgeRecipe("crystalOrb", "水晶珠", EquipmentSlot.WEAPON, 1, 1, "蕴含微量灵气的水晶球", mapOf("snakeCore0" to 3, "foxCore0" to 2), 2, 0.70),
        ForgeRecipe("leatherArmor", "皮甲", EquipmentSlot.ARMOR, 1, 1, "野兽皮革制成的护甲", mapOf("bearHide0" to 4, "bearBone0" to 2), 2, 0.70),
        ForgeRecipe("chainMail", "锁子甲", EquipmentSlot.ARMOR, 1, 1, "铁环相扣的护甲", mapOf("bearBone0" to 5, "bearHide0" to 2), 2, 0.70),
        ForgeRecipe("bronzePlate", "青铜铠", EquipmentSlot.ARMOR, 1, 1, "青铜铸造的铠甲", mapOf("turtleShell0" to 4, "turtleBone0" to 2), 2, 0.70),
        ForgeRecipe("clothRobe", "布衣", EquipmentSlot.ARMOR, 1, 1, "普通布料制成的衣物", mapOf("turtleShell0" to 3, "snakeHide0" to 2), 2, 0.70),
        ForgeRecipe("clothBoots", "布鞋", EquipmentSlot.BOOTS, 1, 1, "轻便的布鞋", mapOf("wolfHide0" to 3, "wolfBone0" to 2), 2, 0.70),
        ForgeRecipe("leatherBoots", "皮靴", EquipmentSlot.BOOTS, 1, 1, "野兽皮革制成的靴子", mapOf("wolfHide0" to 4, "wolfTooth0" to 1), 2, 0.70),
        ForgeRecipe("jadeRing", "玉戒指", EquipmentSlot.ACCESSORY, 1, 1, "蕴含微量灵气的玉戒指", mapOf("foxCore0" to 2, "foxBone0" to 3), 2, 0.70),
        ForgeRecipe("copperNecklace", "铜项链", EquipmentSlot.ACCESSORY, 1, 1, "铜制项链", mapOf("foxBone0" to 3, "foxTail0" to 2), 2, 0.70)
    )

    private val tier2Recipes = listOf(
        ForgeRecipe("spiritSword", "灵锋剑", EquipmentSlot.WEAPON, 2, 2, "注入灵气的锋利长剑", mapOf("tigerBone1" to 4, "tigerTooth1" to 3), 5, 0.65),
        ForgeRecipe("battleAxe", "战斧", EquipmentSlot.WEAPON, 2, 2, "沉重的战斧", mapOf("tigerBone1" to 5, "eagleClaw1" to 2), 5, 0.65),
        ForgeRecipe("jadeStaff", "碧玉杖", EquipmentSlot.WEAPON, 2, 2, "碧玉雕刻的法杖", mapOf("snakeBone1" to 4, "snakeCore1" to 2), 5, 0.65),
        ForgeRecipe("spiritFan", "灵风扇", EquipmentSlot.WEAPON, 2, 2, "可扇出灵风的法器", mapOf("eagleFeather1" to 4, "snakeCore1" to 2), 5, 0.65),
        ForgeRecipe("ironPlate", "铁叶甲", EquipmentSlot.ARMOR, 2, 2, "精铁叶片打造的护甲", mapOf("bearHide1" to 5, "bearBone1" to 2), 5, 0.65),
        ForgeRecipe("steelArmor", "精钢铠", EquipmentSlot.ARMOR, 2, 2, "百炼精钢打造的铠甲", mapOf("bearBone1" to 4, "bearCore1" to 2), 5, 0.65),
        ForgeRecipe("spiritRobe", "灵丝袍", EquipmentSlot.ARMOR, 2, 2, "灵蚕丝织成的法袍", mapOf("turtleShell1" to 4, "snakeHide1" to 2), 5, 0.65),
        ForgeRecipe("cloudRobe", "云纹袍", EquipmentSlot.ARMOR, 2, 2, "绣有云纹的法袍", mapOf("turtleShell1" to 3, "turtleBone1" to 3), 5, 0.65),
        ForgeRecipe("swiftBoots", "疾风靴", EquipmentSlot.BOOTS, 2, 2, "穿上可大幅提升移动速度", mapOf("wolfHide1" to 4, "wolfBone1" to 2), 5, 0.65),
        ForgeRecipe("lightBoots", "轻羽靴", EquipmentSlot.BOOTS, 2, 2, "如羽毛般轻盈", mapOf("wolfHide1" to 3, "eagleFeather1" to 3), 5, 0.65),
        ForgeRecipe("spiritPendant", "灵玉佩", EquipmentSlot.ACCESSORY, 2, 2, "蕴含灵气的玉佩", mapOf("foxCore1" to 3, "foxBone1" to 3), 5, 0.65),
        ForgeRecipe("healthRing", "疾风戒", EquipmentSlot.ACCESSORY, 2, 2, "可提升身法速度的戒指", mapOf("wolfTooth1" to 3, "foxTail1" to 2), 5, 0.65)
    )

    private val tier3Recipes = listOf(
        ForgeRecipe("frostBlade", "寒霜刃", EquipmentSlot.WEAPON, 3, 3, "蕴含寒冰之力的宝刀", mapOf("tigerBone2" to 5, "snakeHide2" to 3, "tigerTooth2" to 2), 9, 0.60),
        ForgeRecipe("flameSword", "烈焰剑", EquipmentSlot.WEAPON, 3, 3, "燃烧着火焰的灵剑", mapOf("tigerBone2" to 4, "tigerCore2" to 2, "tigerTooth2" to 3), 9, 0.60),
        ForgeRecipe("thunderStaff", "雷霆杖", EquipmentSlot.WEAPON, 3, 3, "可召唤雷电的法杖", mapOf("snakeBone2" to 4, "snakeCore2" to 3, "eagleFeather2" to 2), 9, 0.60),
        ForgeRecipe("frostOrb", "玄冰珠", EquipmentSlot.WEAPON, 3, 3, "蕴含玄冰之力的宝珠", mapOf("snakeCore2" to 4, "foxCore2" to 2, "snakeBone2" to 2), 9, 0.60),
        ForgeRecipe("scaleArmor", "鳞甲", EquipmentSlot.ARMOR, 3, 3, "妖兽鳞片打造的护甲", mapOf("snakeHide2" to 5, "snakeBone2" to 3, "snakeCore2" to 2), 9, 0.60),
        ForgeRecipe("plateArmor", "板甲", EquipmentSlot.ARMOR, 3, 3, "厚重的铁板护甲", mapOf("bearHide2" to 4, "bearBone2" to 3, "bearCore2" to 2), 9, 0.60),
        ForgeRecipe("mysticRobe", "玄法袍", EquipmentSlot.ARMOR, 3, 3, "蕴含玄妙法力的长袍", mapOf("turtleShell2" to 5, "turtleBone2" to 3, "turtleCore2" to 2), 9, 0.60),
        ForgeRecipe("starRobe", "星辰袍", EquipmentSlot.ARMOR, 3, 3, "绣有星辰图案的法袍", mapOf("bearHide2" to 3, "snakeHide2" to 3, "bearCore2" to 2), 9, 0.60),
        ForgeRecipe("windBoots", "追风靴", EquipmentSlot.BOOTS, 3, 3, "追逐风的速度", mapOf("wolfHide2" to 4, "wolfBone2" to 3, "wolfCore2" to 2), 9, 0.60),
        ForgeRecipe("mistBoots", "迷雾靴", EquipmentSlot.BOOTS, 3, 3, "踏雾而行的靴子", mapOf("wolfHide2" to 4, "eagleFeather2" to 2, "wolfTooth2" to 2), 9, 0.60),
        ForgeRecipe("storageRing", "灵泉戒", EquipmentSlot.ACCESSORY, 3, 3, "蕴含灵泉之力的戒指", mapOf("foxCore2" to 4, "foxBone2" to 3, "foxTail2" to 2), 9, 0.60),
        ForgeRecipe("wisdomOrb", "迅捷珠", EquipmentSlot.ACCESSORY, 3, 3, "可提升身法速度的宝珠", mapOf("wolfTooth2" to 4, "eagleClaw2" to 2, "foxCore2" to 2), 9, 0.60)
    )

    private val tier4Recipes = listOf(
        ForgeRecipe("thunderSword", "雷霆剑", EquipmentSlot.WEAPON, 4, 4, "引动天雷的玄妙飞剑", mapOf("tigerBone3" to 6, "tigerHide3" to 4, "tigerCore3" to 2), 18, 0.35),
        ForgeRecipe("shadowBlade", "暗影刃", EquipmentSlot.WEAPON, 4, 4, "融入暗影的短刃", mapOf("tigerBone3" to 5, "eagleClaw3" to 4, "foxCore3" to 2), 18, 0.35),
        ForgeRecipe("voidStaff", "虚空杖", EquipmentSlot.WEAPON, 4, 4, "可撕裂虚空的法杖", mapOf("snakeBone3" to 5, "snakeCore3" to 4, "foxCore3" to 2), 18, 0.35),
        ForgeRecipe("phoenixFan", "凤凰扇", EquipmentSlot.WEAPON, 4, 4, "凤凰羽毛制成的神扇", mapOf("eagleFeather3" to 6, "eagleClaw3" to 3, "eagleCore3" to 2), 18, 0.35),
        ForgeRecipe("dragonScale", "龙鳞甲", EquipmentSlot.ARMOR, 4, 4, "真龙鳞片锻造的宝甲", mapOf("snakeHide3" to 6, "snakeBone3" to 4, "snakeCore3" to 2), 18, 0.35),
        ForgeRecipe("titanArmor", "泰坦铠", EquipmentSlot.ARMOR, 4, 4, "泰坦巨人使用的铠甲", mapOf("bearHide3" to 5, "bearBone3" to 4, "bearCore3" to 3), 18, 0.35),
        ForgeRecipe("voidRobe", "虚空袍", EquipmentSlot.ARMOR, 4, 4, "穿梭于虚空之中的法袍", mapOf("turtleShell3" to 6, "turtleBone3" to 4, "turtleCore3" to 2), 18, 0.35),
        ForgeRecipe("moonRobe", "月华袍", EquipmentSlot.ARMOR, 4, 4, "吸收月华之力织成的法袍", mapOf("bearHide3" to 5, "snakeHide3" to 3, "bearCore3" to 3), 18, 0.35),
        ForgeRecipe("cloudBoots", "踏云履", EquipmentSlot.BOOTS, 4, 4, "踏云而行的仙家法宝", mapOf("wolfHide3" to 5, "eagleFeather3" to 4, "wolfCore3" to 2), 18, 0.35),
        ForgeRecipe("thunderBoots", "奔雷靴", EquipmentSlot.BOOTS, 4, 4, "如雷电般迅捷的靴子", mapOf("wolfHide3" to 5, "wolfBone3" to 3, "wolfCore3" to 3), 18, 0.35),
        ForgeRecipe("dragonEye", "龙灵珠", EquipmentSlot.ACCESSORY, 4, 4, "真龙之灵凝聚的宝珠", mapOf("foxCore3" to 5, "foxBone3" to 4, "foxHide3" to 2), 18, 0.35),
        ForgeRecipe("phoenixHeart", "凤羽坠", EquipmentSlot.ACCESSORY, 4, 4, "凤凰羽翼炼制的坠饰", mapOf("wolfTooth3" to 5, "eagleClaw3" to 3, "foxTail3" to 3), 18, 0.35)
    )

    private val tier5Recipes = listOf(
        ForgeRecipe("dragonSlayer", "屠龙刀", EquipmentSlot.WEAPON, 5, 5, "传说中屠龙的绝世神兵", mapOf("tigerBone4" to 6, "tigerTooth4" to 4, "tigerCore4" to 2, "dragonHorn4" to 2), 27, 0.30),
        ForgeRecipe("godSlayer", "弑神剑", EquipmentSlot.WEAPON, 5, 5, "可弑杀神明的神剑", mapOf("tigerBone4" to 5, "tigerTooth4" to 4, "eagleClaw4" to 3, "tigerCore4" to 2), 27, 0.30),
        ForgeRecipe("phoenixWing", "凤凰羽", EquipmentSlot.WEAPON, 5, 5, "凤凰羽翼化作的神兵", mapOf("eagleFeather4" to 6, "eagleClaw4" to 4, "eagleCore4" to 2, "snakeCore4" to 2), 27, 0.30),
        ForgeRecipe("celestialOrb", "天星珠", EquipmentSlot.WEAPON, 5, 5, "凝聚星辰之力的宝珠", mapOf("snakeBone4" to 5, "snakeCore4" to 4, "foxCore4" to 2, "dragonCore4" to 2), 27, 0.30),
        ForgeRecipe("earthArmor", "大地甲", EquipmentSlot.ARMOR, 5, 5, "承载大地之力的神甲", mapOf("snakeHide4" to 6, "snakeBone4" to 4, "snakeCore4" to 2, "dragonScale4" to 2), 27, 0.30),
        ForgeRecipe("divinePlate", "神铸铠", EquipmentSlot.ARMOR, 5, 5, "神匠铸造的铠甲", mapOf("bearHide4" to 6, "bearBone4" to 4, "bearClaw4" to 2, "bearCore4" to 2), 27, 0.30),
        ForgeRecipe("celestialRobe", "天罡袍", EquipmentSlot.ARMOR, 5, 5, "天罡星力凝聚的法袍", mapOf("turtleShell4" to 6, "turtleBone4" to 4, "turtleCore4" to 2, "dragonScale4" to 2), 27, 0.30),
        ForgeRecipe("voidShadowRobe", "虚空影袍", EquipmentSlot.ARMOR, 5, 5, "穿梭于虚空阴影中的神秘法袍", mapOf("bearHide4" to 5, "snakeHide4" to 3, "bearCore4" to 3, "foxCore4" to 2), 27, 0.30),
        ForgeRecipe("voidBoots", "虚空步", EquipmentSlot.BOOTS, 5, 5, "行走于虚空之间", mapOf("wolfHide4" to 5, "wolfTooth4" to 4, "wolfCore4" to 2, "dragonScale4" to 2), 27, 0.30),
        ForgeRecipe("shadowStepBoots", "影舞步", EquipmentSlot.BOOTS, 5, 5, "如影子般飘逸的靴子", mapOf("wolfHide4" to 5, "eagleClaw4" to 3, "wolfCore4" to 2, "foxTail4" to 2), 27, 0.30),
        ForgeRecipe("earthCore", "地灵核", EquipmentSlot.ACCESSORY, 5, 5, "大地灵核凝聚的至宝", mapOf("foxCore4" to 6, "foxBone4" to 4, "foxHide4" to 2, "dragonCore4" to 2), 27, 0.30),
        ForgeRecipe("dragonEyePendant", "风行坠", EquipmentSlot.ACCESSORY, 5, 5, "蕴含风行法则的坠饰", mapOf("wolfTooth4" to 5, "eagleClaw4" to 4, "foxTail4" to 2, "wolfCore4" to 2), 27, 0.30)
    )

    private val tier6Recipes = listOf(
        ForgeRecipe("immortalSword", "诛仙剑", EquipmentSlot.WEAPON, 6, 6, "上古仙人遗留的仙器", mapOf("tigerBone5" to 8, "tigerTooth5" to 5, "tigerCore5" to 3, "dragonHorn5" to 3), 36, 0.25),
        ForgeRecipe("chaosBlade", "混沌刀", EquipmentSlot.WEAPON, 6, 6, "开天辟地时的混沌之刃", mapOf("tigerBone5" to 6, "tigerTooth5" to 5, "eagleClaw5" to 4, "tigerCore5" to 3), 36, 0.25),
        ForgeRecipe("primordialStaff", "鸿蒙杖", EquipmentSlot.WEAPON, 6, 6, "鸿蒙初开时诞生的法杖", mapOf("snakeBone5" to 7, "snakeCore5" to 5, "eagleFeather5" to 3, "dragonCore5" to 3), 36, 0.25),
        ForgeRecipe("yinYangOrb", "阴阳珠", EquipmentSlot.WEAPON, 6, 6, "蕴含阴阳大道的至宝", mapOf("snakeBone5" to 6, "snakeCore5" to 5, "foxCore5" to 3, "dragonCore5" to 3), 36, 0.25),
        ForgeRecipe("immortalArmor", "不朽铠", EquipmentSlot.ARMOR, 6, 6, "仙界神甲", mapOf("snakeHide5" to 8, "snakeBone5" to 5, "snakeCore5" to 3, "dragonScale5" to 3), 36, 0.25),
        ForgeRecipe("primordialArmor", "鸿蒙铠", EquipmentSlot.ARMOR, 6, 6, "鸿蒙初开时诞生的神甲", mapOf("bearHide5" to 8, "bearBone5" to 5, "bearCore5" to 3, "dragonBone5" to 3), 36, 0.25),
        ForgeRecipe("immortalRobe", "仙衣", EquipmentSlot.ARMOR, 6, 6, "仙人穿着的防御至宝", mapOf("turtleShell5" to 8, "turtleBone5" to 5, "turtleCore5" to 3, "dragonScale5" to 3), 36, 0.25),
        ForgeRecipe("chaosRobe", "混沌袍", EquipmentSlot.ARMOR, 6, 6, "混沌初开时诞生的法袍", mapOf("bearHide5" to 6, "snakeHide5" to 4, "bearCore5" to 4, "dragonBone5" to 3), 36, 0.25),
        ForgeRecipe("immortalBoots", "仙踪步", EquipmentSlot.BOOTS, 6, 6, "仙人留下的神行之靴", mapOf("wolfHide5" to 7, "wolfTooth5" to 5, "wolfCore5" to 3, "dragonScale5" to 3), 36, 0.25),
        ForgeRecipe("chaosStepBoots", "混沌履", EquipmentSlot.BOOTS, 6, 6, "踏混沌而行", mapOf("wolfHide5" to 6, "eagleClaw5" to 4, "wolfCore5" to 4, "dragonBone5" to 3), 36, 0.25),
        ForgeRecipe("chaosBead", "混沌灵珠", EquipmentSlot.ACCESSORY, 6, 6, "混沌之力凝聚的宝珠", mapOf("foxCore5" to 8, "foxBone5" to 5, "foxHide5" to 3, "dragonCore5" to 3), 36, 0.25),
        ForgeRecipe("heavenRing", "天行戒", EquipmentSlot.ACCESSORY, 6, 6, "承载天行法则的神戒", mapOf("wolfTooth5" to 7, "eagleClaw5" to 5, "foxTail5" to 3, "dragonScale5" to 3), 36, 0.25)
    )

    private val allRecipes = tier1Recipes + tier2Recipes + tier3Recipes + tier4Recipes + tier5Recipes + tier6Recipes

    fun getAllRecipes(): List<ForgeRecipe> = allRecipes

    fun getRecipeById(id: String): ForgeRecipe? = allRecipes.find { it.id == id }

    fun getRecipesByMaterial(materialId: String): List<ForgeRecipe> = allRecipes.filter { it.materials.containsKey(materialId) }

    fun getRecipesByTier(tier: Int): List<ForgeRecipe> {
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

    fun getRecipesByType(type: EquipmentSlot): List<ForgeRecipe> = allRecipes.filter { it.type == type }

    fun getDurationByTier(tier: Int): Int {
        return when (tier) {
            1 -> 2
            2 -> 5
            3 -> 9
            4 -> 18
            5 -> 27
            6 -> 36
            else -> 2
        }
    }
}
