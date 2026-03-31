package com.xianxia.sect.core.data

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.EquipmentSlot
import kotlin.random.Random

object EquipmentDatabase {

    val isInitialized: Boolean = true

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
        "ironSword" to EquipmentTemplate("ironSword", "精铁剑", EquipmentSlot.WEAPON, 1, physicalAttack = 4, critChance = 0.03, description = "普通铁匠打造的精铁剑，物理攻击力出众", price = 3600),
        "bronzeDagger" to EquipmentTemplate("bronzeDagger", "青铜匕首", EquipmentSlot.WEAPON, 1, physicalAttack = 4, critChance = 0.05, description = "青铜铸造的短刃，锋利易暴击", price = 3600),
        "woodenStaff" to EquipmentTemplate("woodenStaff", "桃木杖", EquipmentSlot.WEAPON, 1, magicAttack = 4, critChance = 0.03, description = "百年桃木制成的法杖，法术攻击力强大", price = 3600),
        "crystalOrb" to EquipmentTemplate("crystalOrb", "水晶珠", EquipmentSlot.WEAPON, 1, magicAttack = 4, critChance = 0.05, description = "蕴含微量灵气的水晶球，易触发暴击", price = 3600),

        "spiritSword" to EquipmentTemplate("spiritSword", "灵锋剑", EquipmentSlot.WEAPON, 2, physicalAttack = 36, critChance = 0.05, description = "注入灵气的锋利长剑，物理攻击力强大", price = 16200),
        "battleAxe" to EquipmentTemplate("battleAxe", "战斧", EquipmentSlot.WEAPON, 2, physicalAttack = 36, critChance = 0.08, description = "沉重的战斧，威力巨大且易暴击", price = 16200),
        "jadeStaff" to EquipmentTemplate("jadeStaff", "碧玉杖", EquipmentSlot.WEAPON, 2, magicAttack = 36, critChance = 0.05, description = "碧玉雕刻的法杖，法术攻击力出众", price = 16200),
        "spiritFan" to EquipmentTemplate("spiritFan", "灵风扇", EquipmentSlot.WEAPON, 2, magicAttack = 36, critChance = 0.08, description = "可扇出灵风的法器，攻击易暴击", price = 16200),

        "frostBlade" to EquipmentTemplate("frostBlade", "寒霜刃", EquipmentSlot.WEAPON, 3, physicalAttack = 126, critChance = 0.07, description = "蕴含寒冰之力的宝刀，物理攻击力出众", price = 64800),
        "flameSword" to EquipmentTemplate("flameSword", "烈焰剑", EquipmentSlot.WEAPON, 3, physicalAttack = 126, critChance = 0.10, description = "燃烧着火焰的灵剑，攻击易暴击", price = 64800),
        "thunderStaff" to EquipmentTemplate("thunderStaff", "雷霆杖", EquipmentSlot.WEAPON, 3, magicAttack = 126, critChance = 0.07, description = "可召唤雷电的法杖，法术攻击力强大", price = 64800),
        "frostOrb" to EquipmentTemplate("frostOrb", "玄冰珠", EquipmentSlot.WEAPON, 3, magicAttack = 126, critChance = 0.10, description = "蕴含玄冰之力的宝珠，攻击易暴击", price = 64800),

        "thunderSword" to EquipmentTemplate("thunderSword", "雷霆剑", EquipmentSlot.WEAPON, 4, physicalAttack = 432, critChance = 0.10, description = "引动天雷的玄妙飞剑，物理攻击力惊人", price = 259200),
        "shadowBlade" to EquipmentTemplate("shadowBlade", "暗影刃", EquipmentSlot.WEAPON, 4, physicalAttack = 432, critChance = 0.13, description = "来无影去无踪的暗杀之刃，暴击率极高", price = 259200),
        "voidStaff" to EquipmentTemplate("voidStaff", "虚空杖", EquipmentSlot.WEAPON, 4, magicAttack = 432, critChance = 0.10, description = "可撕裂虚空的法杖，法术攻击力出众", price = 259200),
        "phoenixFan" to EquipmentTemplate("phoenixFan", "凤凰扇", EquipmentSlot.WEAPON, 4, magicAttack = 432, critChance = 0.13, description = "凤凰羽毛制成的神扇，攻击易暴击", price = 259200),

        "dragonSlayer" to EquipmentTemplate("dragonSlayer", "屠龙刀", EquipmentSlot.WEAPON, 5, physicalAttack = 1440, critChance = 0.12, description = "传说中屠龙的绝世神兵，物理攻击力无双", price = 1036800),
        "godSlayer" to EquipmentTemplate("godSlayer", "弑神剑", EquipmentSlot.WEAPON, 5, physicalAttack = 1440, critChance = 0.16, description = "可弑杀神明的神剑，暴击率极高", price = 1036800),
        "phoenixWing" to EquipmentTemplate("phoenixWing", "凤凰羽", EquipmentSlot.WEAPON, 5, magicAttack = 1440, critChance = 0.12, description = "凤凰羽翼化作的神兵，法术攻击力出众", price = 1036800),
        "celestialOrb" to EquipmentTemplate("celestialOrb", "天星珠", EquipmentSlot.WEAPON, 5, magicAttack = 1440, critChance = 0.16, description = "凝聚星辰之力的宝珠，攻击易暴击", price = 1036800),

        "immortalSword" to EquipmentTemplate("immortalSword", "诛仙剑", EquipmentSlot.WEAPON, 6, physicalAttack = 21600, critChance = 0.15, description = "上古仙人遗留的仙器，物理攻击力毁天灭地", price = 4147200),
        "chaosBlade" to EquipmentTemplate("chaosBlade", "混沌刀", EquipmentSlot.WEAPON, 6, physicalAttack = 21600, critChance = 0.20, description = "开天辟地时的混沌之刃，暴击率超凡入圣", price = 4147200),
        "primordialStaff" to EquipmentTemplate("primordialStaff", "鸿蒙杖", EquipmentSlot.WEAPON, 6, magicAttack = 21600, critChance = 0.15, description = "鸿蒙初开时诞生的法杖，法术攻击力无双", price = 4147200),
        "yinYangOrb" to EquipmentTemplate("yinYangOrb", "阴阳珠", EquipmentSlot.WEAPON, 6, magicAttack = 21600, critChance = 0.20, description = "蕴含阴阳大道的至宝，攻击易暴击", price = 4147200)
    )

    val armors: Map<String, EquipmentTemplate> = mapOf(
        "leatherArmor" to EquipmentTemplate("leatherArmor", "皮甲", EquipmentSlot.ARMOR, 1, physicalDefense = 3, hp = 36, description = "野兽皮革制成的护甲，物理防御出众", price = 3600),
        "chainMail" to EquipmentTemplate("chainMail", "锁子甲", EquipmentSlot.ARMOR, 1, physicalDefense = 3, hp = 42, description = "铁环编织的护甲，增强生命力", price = 3600),
        "bronzePlate" to EquipmentTemplate("bronzePlate", "青铜铠", EquipmentSlot.ARMOR, 1, magicDefense = 3, hp = 36, description = "青铜铸造的铠甲，坚固且增强生命", price = 3600),
        "clothRobe" to EquipmentTemplate("clothRobe", "布衣", EquipmentSlot.ARMOR, 1, magicDefense = 3, hp = 42, description = "普通布料制成的衣物，法术防御出众", price = 3600),

        "ironPlate" to EquipmentTemplate("ironPlate", "铁叶甲", EquipmentSlot.ARMOR, 2, physicalDefense = 30, hp = 360, description = "精铁叶片打造的护甲，物理防御力强大", price = 16200),
        "steelArmor" to EquipmentTemplate("steelArmor", "精钢铠", EquipmentSlot.ARMOR, 2, physicalDefense = 30, hp = 420, description = "百炼精钢打造的铠甲，增强生命力", price = 16200),
        "spiritRobe" to EquipmentTemplate("spiritRobe", "灵丝袍", EquipmentSlot.ARMOR, 2, magicDefense = 24, hp = 360, description = "灵蚕丝织成的法袍，法术防御出众", price = 16200),
        "cloudRobe" to EquipmentTemplate("cloudRobe", "云纹袍", EquipmentSlot.ARMOR, 2, magicDefense = 24, hp = 420, description = "绣有云纹的法袍，增强生命力", price = 16200),

        "scaleArmor" to EquipmentTemplate("scaleArmor", "鳞甲", EquipmentSlot.ARMOR, 3, physicalDefense = 105, hp = 1260, description = "妖兽鳞片打造的护甲，物理防御力惊人", price = 64800),
        "plateArmor" to EquipmentTemplate("plateArmor", "板甲", EquipmentSlot.ARMOR, 3, physicalDefense = 105, hp = 1470, description = "整块精钢锻造的重甲，增强生命力", price = 64800),
        "mysticRobe" to EquipmentTemplate("mysticRobe", "玄法袍", EquipmentSlot.ARMOR, 3, magicDefense = 84, hp = 1260, description = "蕴含玄妙法力的长袍，法术防御出众", price = 64800),
        "starRobe" to EquipmentTemplate("starRobe", "星辰袍", EquipmentSlot.ARMOR, 3, magicDefense = 84, hp = 1470, description = "绣有星辰图案的法袍，增强生命力", price = 64800),

        "dragonScale" to EquipmentTemplate("dragonScale", "龙鳞甲", EquipmentSlot.ARMOR, 4, physicalDefense = 360, hp = 4320, description = "真龙鳞片锻造的宝甲，物理防御无双", price = 259200),
        "titanArmor" to EquipmentTemplate("titanArmor", "泰坦铠", EquipmentSlot.ARMOR, 4, physicalDefense = 360, hp = 5040, description = "泰坦巨人使用的铠甲，增强生命力", price = 259200),
        "voidRobe" to EquipmentTemplate("voidRobe", "虚空袍", EquipmentSlot.ARMOR, 4, magicDefense = 288, hp = 4320, description = "穿梭于虚空之中的法袍，法术防御出众", price = 259200),
        "moonRobe" to EquipmentTemplate("moonRobe", "月华袍", EquipmentSlot.ARMOR, 4, magicDefense = 288, hp = 5040, description = "吸收月华之力织成的法袍，增强生命力", price = 259200),

        "earthArmor" to EquipmentTemplate("earthArmor", "大地甲", EquipmentSlot.ARMOR, 5, physicalDefense = 1200, hp = 14400, description = "承载大地之力的神甲，物理防御如大地般坚固", price = 1036800),
        "divinePlate" to EquipmentTemplate("divinePlate", "神铸铠", EquipmentSlot.ARMOR, 5, physicalDefense = 1200, hp = 16800, description = "神匠铸造的铠甲，增强生命力", price = 1036800),
        "celestialRobe" to EquipmentTemplate("celestialRobe", "天罡袍", EquipmentSlot.ARMOR, 5, magicDefense = 960, hp = 14400, description = "天罡星力凝聚的法袍，法术防御超凡入圣", price = 1036800),
        "voidShadowRobe" to EquipmentTemplate("voidShadowRobe", "虚空影袍", EquipmentSlot.ARMOR, 5, magicDefense = 960, hp = 16800, description = "穿梭于虚空阴影中的神秘法袍，增强生命力", price = 1036800),

        "immortalArmor" to EquipmentTemplate("immortalArmor", "不朽铠", EquipmentSlot.ARMOR, 6, physicalDefense = 18000, hp = 216000, description = "仙界神甲，物理防御不朽不灭", price = 4147200),
        "primordialArmor" to EquipmentTemplate("primordialArmor", "鸿蒙铠", EquipmentSlot.ARMOR, 6, physicalDefense = 18000, hp = 252000, description = "鸿蒙初开时诞生的神甲，承载创世生命之力", price = 4147200),
        "immortalRobe" to EquipmentTemplate("immortalRobe", "仙衣", EquipmentSlot.ARMOR, 6, magicDefense = 14400, hp = 216000, description = "仙人穿着的防御至宝，法术防御超凡入圣", price = 4147200),
        "chaosRobe" to EquipmentTemplate("chaosRobe", "混沌袍", EquipmentSlot.ARMOR, 6, magicDefense = 14400, hp = 252000, description = "混沌初开时诞生的法袍，蕴含无尽生命之力", price = 4147200)
    )

    val boots: Map<String, EquipmentTemplate> = mapOf(
        "clothBoots" to EquipmentTemplate("clothBoots", "布鞋", EquipmentSlot.BOOTS, 1, speed = 5, hp = 18, description = "轻便的布鞋，提升移动速度", price = 3600),
        "leatherBoots" to EquipmentTemplate("leatherBoots", "皮靴", EquipmentSlot.BOOTS, 1, speed = 5, hp = 21, description = "厚实的皮靴，增强生命力", price = 3600),

        "swiftBoots" to EquipmentTemplate("swiftBoots", "疾风靴", EquipmentSlot.BOOTS, 2, speed = 45, hp = 180, description = "穿上可大幅提升移动速度", price = 16200),
        "lightBoots" to EquipmentTemplate("lightBoots", "轻羽靴", EquipmentSlot.BOOTS, 2, speed = 45, hp = 210, description = "如羽毛般轻盈，蕴含生命之力", price = 16200),

        "windBoots" to EquipmentTemplate("windBoots", "追风靴", EquipmentSlot.BOOTS, 3, speed = 158, hp = 630, description = "追逐风的速度，极速无双", price = 64800),
        "mistBoots" to EquipmentTemplate("mistBoots", "迷雾靴", EquipmentSlot.BOOTS, 3, speed = 158, hp = 735, description = "踏雾而行，蕴含浓郁生命气息", price = 64800),

        "cloudBoots" to EquipmentTemplate("cloudBoots", "踏云履", EquipmentSlot.BOOTS, 4, speed = 540, hp = 2160, description = "踏云而行的仙家法宝，速度惊人", price = 259200),
        "thunderBoots" to EquipmentTemplate("thunderBoots", "奔雷靴", EquipmentSlot.BOOTS, 4, speed = 540, hp = 2520, description = "如雷电般厚重，蕴含磅礴生命力", price = 259200),

        "voidBoots" to EquipmentTemplate("voidBoots", "虚空步", EquipmentSlot.BOOTS, 5, speed = 1800, hp = 7200, description = "行走于虚空之间，速度无双", price = 1036800),
        "shadowStepBoots" to EquipmentTemplate("shadowStepBoots", "影舞步", EquipmentSlot.BOOTS, 5, speed = 1800, hp = 8400, description = "如影子般厚重，蕴含浩瀚生命力", price = 1036800),

        "immortalBoots" to EquipmentTemplate("immortalBoots", "仙踪步", EquipmentSlot.BOOTS, 6, speed = 27000, hp = 108000, description = "仙人留下的神行之靴，速度超凡入圣", price = 4147200),
        "chaosStepBoots" to EquipmentTemplate("chaosStepBoots", "混沌履", EquipmentSlot.BOOTS, 6, speed = 27000, hp = 126000, description = "踏混沌而行，蕴含无尽生命之力", price = 4147200)
    )

    val accessories: Map<String, EquipmentTemplate> = mapOf(
        "jadeRing" to EquipmentTemplate("jadeRing", "玉戒指", EquipmentSlot.ACCESSORY, 1, mp = 18, speed = 2, description = "蕴含微量灵气的玉戒指，灵力出众", price = 3600),
        "copperNecklace" to EquipmentTemplate("copperNecklace", "铜项链", EquipmentSlot.ACCESSORY, 1, mp = 21, speed = 3, description = "铜制项链，可提升身法速度", price = 3600),

        "spiritPendant" to EquipmentTemplate("spiritPendant", "灵玉佩", EquipmentSlot.ACCESSORY, 2, mp = 180, speed = 22, description = "蕴含灵气的玉佩，灵力出众", price = 16200),
        "healthRing" to EquipmentTemplate("healthRing", "疾风戒", EquipmentSlot.ACCESSORY, 2, mp = 210, speed = 26, description = "可提升身法速度的戒指", price = 16200),

        "storageRing" to EquipmentTemplate("storageRing", "灵泉戒", EquipmentSlot.ACCESSORY, 3, mp = 630, speed = 79, description = "蕴含灵泉之力的戒指，灵力出众", price = 64800),
        "wisdomOrb" to EquipmentTemplate("wisdomOrb", "迅捷珠", EquipmentSlot.ACCESSORY, 3, mp = 735, speed = 92, description = "可提升身法速度的宝珠", price = 64800),

        "dragonEye" to EquipmentTemplate("dragonEye", "龙灵珠", EquipmentSlot.ACCESSORY, 4, mp = 2160, speed = 270, description = "真龙之灵凝聚的宝珠，灵力出众", price = 259200),
        "phoenixHeart" to EquipmentTemplate("phoenixHeart", "凤羽坠", EquipmentSlot.ACCESSORY, 4, mp = 2520, speed = 315, description = "凤凰羽翼炼制的坠饰，可提升身法速度", price = 259200),

        "earthCore" to EquipmentTemplate("earthCore", "地灵核", EquipmentSlot.ACCESSORY, 5, mp = 7200, speed = 900, description = "大地灵核凝聚的至宝，灵力出众", price = 1036800),
        "dragonEyePendant" to EquipmentTemplate("dragonEyePendant", "风行坠", EquipmentSlot.ACCESSORY, 5, mp = 8400, speed = 1050, description = "蕴含风行法则的坠饰，可大幅提升身法速度", price = 1036800),

        "chaosBead" to EquipmentTemplate("chaosBead", "混沌灵珠", EquipmentSlot.ACCESSORY, 6, mp = 108000, speed = 13500, description = "蕴含混沌灵力的至宝，灵力超凡入圣", price = 4147200),
        "heavenRing" to EquipmentTemplate("heavenRing", "天行戒", EquipmentSlot.ACCESSORY, 6, mp = 126000, speed = 15750, description = "承载天行法则的神戒，身法速度无双", price = 4147200)
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
        val rarity = if (minRarity == maxRarity) {
            minRarity
        } else {
            generateRarity(minRarity, maxRarity)
        }
        val templates = getByRarity(rarity)
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
            critChance = template.critChance,
            minRealm = GameConfig.Realm.getMinRealmForRarity(template.rarity)
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