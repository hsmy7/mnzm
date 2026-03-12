package com.xianxia.sect.core.data

import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.EquipmentSlot
import kotlin.random.Random

object EquipmentDatabase {
    
    data class EquipmentTemplate(
        val id: String,
        val name: String,
        val slot: EquipmentSlot,
        val rarity: Int,
        val physicalAttack: Int = 0,
        val magicAttack: Int = 0,
        val physicalDefense: Int = 0,
        val magicDefense: Int = 0,
        val speed: Int = 0,
        val hp: Int = 0,
        val mp: Int = 0,
        val critChance: Double = 0.0,
        val description: String,
        val price: Int = 0
    )
    
    val weapons: Map<String, EquipmentTemplate> = mapOf(
        "ironSword" to EquipmentTemplate("ironSword", "精铁剑", EquipmentSlot.WEAPON, 1, physicalAttack = 15, critChance = 0.03, description = "普通铁匠打造的精铁剑，物理攻击力出众", price = 3600),
        "bronzeDagger" to EquipmentTemplate("bronzeDagger", "青铜匕首", EquipmentSlot.WEAPON, 1, physicalAttack = 10, critChance = 0.08, description = "青铜铸造的短刃，锋利易暴击", price = 3600),
        "woodenStaff" to EquipmentTemplate("woodenStaff", "桃木杖", EquipmentSlot.WEAPON, 1, magicAttack = 13, critChance = 0.03, description = "百年桃木制成的法杖，法术攻击力强大", price = 3600),
        "crystalOrb" to EquipmentTemplate("crystalOrb", "水晶珠", EquipmentSlot.WEAPON, 1, magicAttack = 9, critChance = 0.08, description = "蕴含微量灵气的水晶球，易触发暴击", price = 3600),

        "spiritSword" to EquipmentTemplate("spiritSword", "灵锋剑", EquipmentSlot.WEAPON, 2, physicalAttack = 32, critChance = 0.05, description = "注入灵气的锋利长剑，物理攻击力强大", price = 16200),
        "battleAxe" to EquipmentTemplate("battleAxe", "战斧", EquipmentSlot.WEAPON, 2, physicalAttack = 22, critChance = 0.12, description = "沉重的战斧，威力巨大且易暴击", price = 16200),
        "jadeStaff" to EquipmentTemplate("jadeStaff", "碧玉杖", EquipmentSlot.WEAPON, 2, magicAttack = 28, critChance = 0.05, description = "碧玉雕刻的法杖，法术攻击力出众", price = 16200),
        "spiritFan" to EquipmentTemplate("spiritFan", "灵风扇", EquipmentSlot.WEAPON, 2, magicAttack = 20, critChance = 0.12, description = "可扇出灵风的法器，攻击易暴击", price = 16200),

        "frostBlade" to EquipmentTemplate("frostBlade", "寒霜刃", EquipmentSlot.WEAPON, 3, physicalAttack = 58, critChance = 0.07, description = "蕴含寒冰之力的宝刀，物理攻击力出众", price = 64800),
        "flameSword" to EquipmentTemplate("flameSword", "烈焰剑", EquipmentSlot.WEAPON, 3, physicalAttack = 40, critChance = 0.15, description = "燃烧着火焰的灵剑，攻击易暴击", price = 64800),
        "thunderStaff" to EquipmentTemplate("thunderStaff", "雷霆杖", EquipmentSlot.WEAPON, 3, magicAttack = 52, critChance = 0.07, description = "可召唤雷电的法杖，法术攻击力强大", price = 64800),
        "frostOrb" to EquipmentTemplate("frostOrb", "玄冰珠", EquipmentSlot.WEAPON, 3, magicAttack = 35, critChance = 0.15, description = "蕴含玄冰之力的宝珠，攻击易暴击", price = 64800),

        "thunderSword" to EquipmentTemplate("thunderSword", "雷霆剑", EquipmentSlot.WEAPON, 4, physicalAttack = 110, critChance = 0.10, description = "引动天雷的玄妙飞剑，物理攻击力惊人", price = 259200),
        "shadowBlade" to EquipmentTemplate("shadowBlade", "暗影刃", EquipmentSlot.WEAPON, 4, physicalAttack = 75, critChance = 0.20, description = "来无影去无踪的暗杀之刃，暴击率极高", price = 259200),
        "voidStaff" to EquipmentTemplate("voidStaff", "虚空杖", EquipmentSlot.WEAPON, 4, magicAttack = 100, critChance = 0.10, description = "可撕裂虚空的法杖，法术攻击力出众", price = 259200),
        "phoenixFan" to EquipmentTemplate("phoenixFan", "凤凰扇", EquipmentSlot.WEAPON, 4, magicAttack = 65, critChance = 0.20, description = "凤凰羽毛制成的神扇，攻击易暴击", price = 259200),

        "dragonSlayer" to EquipmentTemplate("dragonSlayer", "屠龙刀", EquipmentSlot.WEAPON, 5, physicalAttack = 220, critChance = 0.12, description = "传说中屠龙的绝世神兵，物理攻击力无双", price = 1036800),
        "godSlayer" to EquipmentTemplate("godSlayer", "弑神剑", EquipmentSlot.WEAPON, 5, physicalAttack = 150, critChance = 0.25, description = "可弑杀神明的神剑，暴击率极高", price = 1036800),
        "phoenixWing" to EquipmentTemplate("phoenixWing", "凤凰羽", EquipmentSlot.WEAPON, 5, magicAttack = 200, critChance = 0.12, description = "凤凰羽翼化作的神兵，法术攻击力出众", price = 1036800),
        "celestialOrb" to EquipmentTemplate("celestialOrb", "天星珠", EquipmentSlot.WEAPON, 5, magicAttack = 130, critChance = 0.25, description = "凝聚星辰之力的宝珠，攻击易暴击", price = 1036800),

        "immortalSword" to EquipmentTemplate("immortalSword", "诛仙剑", EquipmentSlot.WEAPON, 6, physicalAttack = 450, critChance = 0.15, description = "上古仙人遗留的仙器，物理攻击力毁天灭地", price = 4147200),
        "chaosBlade" to EquipmentTemplate("chaosBlade", "混沌刀", EquipmentSlot.WEAPON, 6, physicalAttack = 300, critChance = 0.30, description = "开天辟地时的混沌之刃，暴击率超凡入圣", price = 4147200),
        "primordialStaff" to EquipmentTemplate("primordialStaff", "鸿蒙杖", EquipmentSlot.WEAPON, 6, magicAttack = 400, critChance = 0.15, description = "鸿蒙初开时诞生的法杖，法术攻击力无双", price = 4147200),
        "yinYangOrb" to EquipmentTemplate("yinYangOrb", "阴阳珠", EquipmentSlot.WEAPON, 6, magicAttack = 260, critChance = 0.30, description = "蕴含阴阳大道的至宝，攻击易暴击", price = 4147200)
    )
    
    val armors: Map<String, EquipmentTemplate> = mapOf(
        "leatherArmor" to EquipmentTemplate("leatherArmor", "皮甲", EquipmentSlot.ARMOR, 1, physicalDefense = 10, hp = 20, description = "野兽皮革制成的护甲，物理防御出众", price = 3600),
        "chainMail" to EquipmentTemplate("chainMail", "锁子甲", EquipmentSlot.ARMOR, 1, physicalDefense = 7, hp = 50, description = "铁环编织的护甲，增强生命力", price = 3600),
        "bronzePlate" to EquipmentTemplate("bronzePlate", "青铜铠", EquipmentSlot.ARMOR, 1, physicalDefense = 8, hp = 80, description = "青铜铸造的铠甲，坚固且增强生命", price = 3600),
        "clothRobe" to EquipmentTemplate("clothRobe", "布衣", EquipmentSlot.ARMOR, 1, magicDefense = 8, hp = 15, description = "普通布料制成的衣物，法术防御出众", price = 3600),

        "ironPlate" to EquipmentTemplate("ironPlate", "铁叶甲", EquipmentSlot.ARMOR, 2, physicalDefense = 40, description = "精铁叶片打造的护甲，物理防御力强大", price = 16200),
        "steelArmor" to EquipmentTemplate("steelArmor", "精钢铠", EquipmentSlot.ARMOR, 2, physicalDefense = 15, hp = 150, description = "百炼精钢打造的铠甲，增强生命力", price = 16200),
        "spiritRobe" to EquipmentTemplate("spiritRobe", "灵丝袍", EquipmentSlot.ARMOR, 2, magicDefense = 32, description = "灵蚕丝织成的法袍，法术防御出众", price = 16200),
        "cloudRobe" to EquipmentTemplate("cloudRobe", "云纹袍", EquipmentSlot.ARMOR, 2, magicDefense = 12, hp = 120, description = "绣有云纹的法袍，增强生命力", price = 16200),

        "scaleArmor" to EquipmentTemplate("scaleArmor", "鳞甲", EquipmentSlot.ARMOR, 3, physicalDefense = 75, description = "妖兽鳞片打造的护甲，物理防御力惊人", price = 64800),
        "plateArmor" to EquipmentTemplate("plateArmor", "板甲", EquipmentSlot.ARMOR, 3, physicalDefense = 25, hp = 250, description = "整块精钢锻造的重甲，增强生命力", price = 64800),
        "mysticRobe" to EquipmentTemplate("mysticRobe", "玄法袍", EquipmentSlot.ARMOR, 3, magicDefense = 60, description = "蕴含玄妙法力的长袍，法术防御出众", price = 64800),
        "starRobe" to EquipmentTemplate("starRobe", "星辰袍", EquipmentSlot.ARMOR, 3, magicDefense = 20, hp = 200, description = "绣有星辰图案的法袍，增强生命力", price = 64800),

        "dragonScale" to EquipmentTemplate("dragonScale", "龙鳞甲", EquipmentSlot.ARMOR, 4, physicalDefense = 130, description = "真龙鳞片锻造的宝甲，物理防御无双", price = 259200),
        "titanArmor" to EquipmentTemplate("titanArmor", "泰坦铠", EquipmentSlot.ARMOR, 4, physicalDefense = 40, hp = 450, description = "泰坦巨人使用的铠甲，增强生命力", price = 259200),
        "voidRobe" to EquipmentTemplate("voidRobe", "虚空袍", EquipmentSlot.ARMOR, 4, magicDefense = 105, description = "穿梭于虚空之中的法袍，法术防御出众", price = 259200),
        "moonRobe" to EquipmentTemplate("moonRobe", "月华袍", EquipmentSlot.ARMOR, 4, magicDefense = 35, hp = 350, description = "吸收月华之力织成的法袍，增强生命力", price = 259200),

        "earthArmor" to EquipmentTemplate("earthArmor", "大地甲", EquipmentSlot.ARMOR, 5, physicalDefense = 220, description = "承载大地之力的神甲，物理防御如大地般坚固", price = 1036800),
        "divinePlate" to EquipmentTemplate("divinePlate", "神铸铠", EquipmentSlot.ARMOR, 5, physicalDefense = 70, hp = 800, description = "神匠铸造的铠甲，增强生命力", price = 1036800),
        "celestialRobe" to EquipmentTemplate("celestialRobe", "天罡袍", EquipmentSlot.ARMOR, 5, magicDefense = 180, description = "天罡星力凝聚的法袍，法术防御超凡入圣", price = 1036800),
        "voidShadowRobe" to EquipmentTemplate("voidShadowRobe", "虚空影袍", EquipmentSlot.ARMOR, 5, magicDefense = 55, hp = 600, description = "穿梭于虚空阴影中的神秘法袍，增强生命力", price = 1036800),

        "immortalArmor" to EquipmentTemplate("immortalArmor", "不朽铠", EquipmentSlot.ARMOR, 6, physicalDefense = 350, description = "仙界神甲，物理防御不朽不灭", price = 4147200),
        "primordialArmor" to EquipmentTemplate("primordialArmor", "鸿蒙铠", EquipmentSlot.ARMOR, 6, physicalDefense = 100, hp = 1500, description = "鸿蒙初开时诞生的神甲，承载创世生命之力", price = 4147200),
        "immortalRobe" to EquipmentTemplate("immortalRobe", "仙衣", EquipmentSlot.ARMOR, 6, magicDefense = 300, description = "仙人穿着的防御至宝，法术防御超凡入圣", price = 4147200),
        "chaosRobe" to EquipmentTemplate("chaosRobe", "混沌袍", EquipmentSlot.ARMOR, 6, magicDefense = 80, hp = 1200, description = "混沌初开时诞生的法袍，蕴含无尽生命之力", price = 4147200)
    )
    
    val boots: Map<String, EquipmentTemplate> = mapOf(
        "clothBoots" to EquipmentTemplate("clothBoots", "布鞋", EquipmentSlot.BOOTS, 1, speed = 5, hp = 10, description = "轻便的布鞋，提升移动速度", price = 3600),
        "leatherBoots" to EquipmentTemplate("leatherBoots", "皮靴", EquipmentSlot.BOOTS, 1, speed = 3, hp = 30, description = "厚实的皮靴，增强生命力", price = 3600),

        "swiftBoots" to EquipmentTemplate("swiftBoots", "疾风靴", EquipmentSlot.BOOTS, 2, speed = 12, hp = 25, description = "穿上可大幅提升移动速度", price = 16200),
        "lightBoots" to EquipmentTemplate("lightBoots", "轻羽靴", EquipmentSlot.BOOTS, 2, speed = 6, hp = 80, description = "如羽毛般轻盈，蕴含生命之力", price = 16200),

        "windBoots" to EquipmentTemplate("windBoots", "追风靴", EquipmentSlot.BOOTS, 3, speed = 22, hp = 40, description = "追逐风的速度，极速无双", price = 64800),
        "mistBoots" to EquipmentTemplate("mistBoots", "迷雾靴", EquipmentSlot.BOOTS, 3, speed = 10, hp = 150, description = "踏雾而行，蕴含浓郁生命气息", price = 64800),

        "cloudBoots" to EquipmentTemplate("cloudBoots", "踏云履", EquipmentSlot.BOOTS, 4, speed = 32, hp = 70, description = "踏云而行的仙家法宝，速度惊人", price = 259200),
        "thunderBoots" to EquipmentTemplate("thunderBoots", "奔雷靴", EquipmentSlot.BOOTS, 4, speed = 14, hp = 300, description = "如雷电般厚重，蕴含磅礴生命力", price = 259200),

        "voidBoots" to EquipmentTemplate("voidBoots", "虚空步", EquipmentSlot.BOOTS, 5, speed = 45, hp = 120, description = "行走于虚空之间，速度无双", price = 1036800),
        "shadowStepBoots" to EquipmentTemplate("shadowStepBoots", "影舞步", EquipmentSlot.BOOTS, 5, speed = 20, hp = 600, description = "如影子般厚重，蕴含浩瀚生命力", price = 1036800),

        "immortalBoots" to EquipmentTemplate("immortalBoots", "仙踪步", EquipmentSlot.BOOTS, 6, speed = 65, hp = 200, description = "仙人留下的神行之靴，速度超凡入圣", price = 4147200),
        "chaosStepBoots" to EquipmentTemplate("chaosStepBoots", "混沌履", EquipmentSlot.BOOTS, 6, speed = 28, hp = 1200, description = "踏混沌而行，蕴含无尽生命之力", price = 4147200)
    )
    
    val accessories: Map<String, EquipmentTemplate> = mapOf(
        "jadeRing" to EquipmentTemplate("jadeRing", "玉戒指", EquipmentSlot.ACCESSORY, 1, mp = 10, speed = 2, description = "蕴含微量灵气的玉戒指，灵力出众", price = 3600),
        "copperNecklace" to EquipmentTemplate("copperNecklace", "铜项链", EquipmentSlot.ACCESSORY, 1, mp = 6, speed = 4, description = "铜制项链，可提升身法速度", price = 3600),

        "spiritPendant" to EquipmentTemplate("spiritPendant", "灵玉佩", EquipmentSlot.ACCESSORY, 2, mp = 25, speed = 4, description = "蕴含灵气的玉佩，灵力出众", price = 16200),
        "healthRing" to EquipmentTemplate("healthRing", "疾风戒", EquipmentSlot.ACCESSORY, 2, mp = 15, speed = 8, description = "可提升身法速度的戒指", price = 16200),

        "storageRing" to EquipmentTemplate("storageRing", "灵泉戒", EquipmentSlot.ACCESSORY, 3, mp = 60, speed = 6, description = "蕴含灵泉之力的戒指，灵力出众", price = 64800),
        "wisdomOrb" to EquipmentTemplate("wisdomOrb", "迅捷珠", EquipmentSlot.ACCESSORY, 3, mp = 35, speed = 12, description = "可提升身法速度的宝珠", price = 64800),

        "dragonEye" to EquipmentTemplate("dragonEye", "龙灵珠", EquipmentSlot.ACCESSORY, 4, mp = 130, speed = 8, description = "真龙之灵凝聚的宝珠，灵力出众", price = 259200),
        "phoenixHeart" to EquipmentTemplate("phoenixHeart", "凤羽坠", EquipmentSlot.ACCESSORY, 4, mp = 70, speed = 18, description = "凤凰羽翼炼制的坠饰，可提升身法速度", price = 259200),

        "earthCore" to EquipmentTemplate("earthCore", "地灵核", EquipmentSlot.ACCESSORY, 5, mp = 250, speed = 10, description = "大地灵核凝聚的至宝，灵力出众", price = 1036800),
        "dragonEyePendant" to EquipmentTemplate("dragonEyePendant", "风行坠", EquipmentSlot.ACCESSORY, 5, mp = 140, speed = 26, description = "蕴含风行法则的坠饰，可大幅提升身法速度", price = 1036800),

        "chaosBead" to EquipmentTemplate("chaosBead", "混沌灵珠", EquipmentSlot.ACCESSORY, 6, mp = 500, speed = 15, description = "蕴含混沌灵力的至宝，灵力超凡入圣", price = 4147200),
        "heavenRing" to EquipmentTemplate("heavenRing", "天行戒", EquipmentSlot.ACCESSORY, 6, mp = 280, speed = 40, description = "承载天行法则的神戒，身法速度无双", price = 4147200)
    )
    
    val allTemplates: Map<String, EquipmentTemplate> by lazy {
        weapons + armors + boots + accessories
    }
    
    fun getById(id: String): EquipmentTemplate? = allTemplates[id]
    
    fun getTemplateByName(name: String): EquipmentTemplate? = allTemplates.values.find { it.name == name }
    
    fun getBySlot(slot: EquipmentSlot): List<EquipmentTemplate> = when (slot) {
        EquipmentSlot.WEAPON -> weapons.values.toList()
        EquipmentSlot.ARMOR -> armors.values.toList()
        EquipmentSlot.BOOTS -> boots.values.toList()
        EquipmentSlot.ACCESSORY -> accessories.values.toList()
    }
    
    fun getByRarity(rarity: Int): List<EquipmentTemplate> = 
        allTemplates.values.filter { it.rarity == rarity }
    
    fun generateRandom(minRarity: Int = 1, maxRarity: Int = 6): Equipment {
        // 如果min和max相同，直接使用该品阶
        val rarity = if (minRarity == maxRarity) {
            minRarity
        } else {
            generateRarity(minRarity, maxRarity)
        }
        val templates = getByRarity(rarity)
        // 如果该品阶没有模板，从所有模板中随机选择
        val template = if (templates.isNotEmpty()) templates.random() else allTemplates.values.random()
        return createFromTemplate(template)
    }
    
    fun generateRandomBySlot(slot: EquipmentSlot, rarity: Int): Equipment {
        val templates = getBySlot(slot).filter { it.rarity == rarity }
        val template = if (templates.isNotEmpty()) templates.random() else getBySlot(slot).random()
        return createFromTemplate(template)
    }
    
    fun createFromTemplate(template: EquipmentTemplate): Equipment {
        return Equipment(
            id = java.util.UUID.randomUUID().toString(),
            name = template.name,
            rarity = template.rarity,
            description = template.description,
            slot = template.slot,
            physicalAttack = template.physicalAttack,
            magicAttack = template.magicAttack,
            physicalDefense = template.physicalDefense,
            magicDefense = template.magicDefense,
            speed = template.speed,
            hp = template.hp,
            mp = template.mp,
            critChance = template.critChance
        )
    }
    
    private fun generateRarity(min: Int, max: Int): Int {
        val roll = Random.nextDouble()
        return when {
            roll < 0.5 -> min.coerceAtLeast(1)
            roll < 0.75 -> (min + 1).coerceIn(min, max)
            roll < 0.9 -> (min + 2).coerceIn(min, max)
            roll < 0.97 -> (min + 3).coerceIn(min, max)
            roll < 0.99 -> (min + 4).coerceIn(min, max)
            else -> max
        }
    }
}
