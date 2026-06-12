package com.xianxia.sect.core.registry

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.EquipmentStack
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
        "ironSword" to EquipmentTemplate("ironSword", "精铁剑", EquipmentSlot.WEAPON, 1, physicalAttack = 15, critChance = 0.03, description = "普通铁匠打造的精铁剑，物理攻击力出众", price = 3600),
        "bronzeDagger" to EquipmentTemplate("bronzeDagger", "精铁刀", EquipmentSlot.WEAPON, 1, physicalAttack = 21, critChance = 0.06, description = "精铁锻造的宝刀，锋利易暴击", price = 3600),
        "woodenStaff" to EquipmentTemplate("woodenStaff", "桃木杖", EquipmentSlot.WEAPON, 1, magicAttack = 15, critChance = 0.03, description = "百年桃木制成的法杖，法术攻击力强大", price = 3600),
        "crystalOrb" to EquipmentTemplate("crystalOrb", "碧木扇", EquipmentSlot.WEAPON, 1, magicAttack = 21, critChance = 0.06, description = "碧木炼制的灵扇，蕴含微量灵气，易触发暴击", price = 3600),

        "spiritSword" to EquipmentTemplate("spiritSword", "灵锋剑", EquipmentSlot.WEAPON, 2, physicalAttack = 45, critChance = 0.06, description = "注入灵气的锋利长剑，物理攻击力强大", price = 16200),
        "battleAxe" to EquipmentTemplate("battleAxe", "凌华刀", EquipmentSlot.WEAPON, 2, physicalAttack = 66, critChance = 0.09, description = "刀光凌厉如华，威力巨大且易暴击", price = 16200),
        "jadeStaff" to EquipmentTemplate("jadeStaff", "碧玉杖", EquipmentSlot.WEAPON, 2, magicAttack = 45, critChance = 0.06, description = "碧玉雕刻的法杖，法术攻击力出众", price = 16200),
        "spiritFan" to EquipmentTemplate("spiritFan", "灵风扇", EquipmentSlot.WEAPON, 2, magicAttack = 66, critChance = 0.09, description = "可扇出灵风的法器，攻击易暴击", price = 16200),

        "frostBlade" to EquipmentTemplate("frostBlade", "青碧刃", EquipmentSlot.WEAPON, 3, physicalAttack = 138, critChance = 0.09, description = "蕴含青碧灵力的宝刀，物理攻击力出众", price = 64800),
        "flameSword" to EquipmentTemplate("flameSword", "烈焰剑", EquipmentSlot.WEAPON, 3, physicalAttack = 204, critChance = 0.135, description = "燃烧着火焰的灵剑，攻击易暴击", price = 64800),
        "thunderStaff" to EquipmentTemplate("thunderStaff", "玄雷杖", EquipmentSlot.WEAPON, 3, magicAttack = 138, critChance = 0.09, description = "可召唤雷电的法杖，法术攻击力强大", price = 64800),
        "frostOrb" to EquipmentTemplate("frostOrb", "玄冰扇", EquipmentSlot.WEAPON, 3, magicAttack = 204, critChance = 0.135, description = "蕴含玄冰之力的宝扇，攻击易暴击", price = 64800),

        "thunderSword" to EquipmentTemplate("thunderSword", "雷霆剑", EquipmentSlot.WEAPON, 4, physicalAttack = 428, critChance = 0.12, description = "引动天雷的玄妙飞剑，物理攻击力惊人", price = 259200),
        "shadowBlade" to EquipmentTemplate("shadowBlade", "暗影刃", EquipmentSlot.WEAPON, 4, physicalAttack = 630, critChance = 0.18, description = "来无影去无踪的暗杀之刃，暴击率极高", price = 259200),
        "voidStaff" to EquipmentTemplate("voidStaff", "虚华杖", EquipmentSlot.WEAPON, 4, magicAttack = 428, critChance = 0.12, description = "虚华流转的玄妙法杖，法术攻击力出众", price = 259200),
        "phoenixFan" to EquipmentTemplate("phoenixFan", "凰焰扇", EquipmentSlot.WEAPON, 4, magicAttack = 630, critChance = 0.18, description = "凰焰淬炼的神扇，攻击易暴击", price = 259200),

        "dragonSlayer" to EquipmentTemplate("dragonSlayer", "凤炎刃", EquipmentSlot.WEAPON, 5, physicalAttack = 1320, critChance = 0.15000000000000002, description = "蕴含凤炎之力的绝世神兵，物理攻击力无双", price = 1036800),
        "godSlayer" to EquipmentTemplate("godSlayer", "青莲剑", EquipmentSlot.WEAPON, 5, physicalAttack = 1920, critChance = 0.22499999999999998, description = "传说中自青莲中诞生的神剑，剑气纵横天地间", price = 1036800),
        "phoenixWing" to EquipmentTemplate("phoenixWing", "阴阳扇", EquipmentSlot.WEAPON, 5, magicAttack = 1320, critChance = 0.15000000000000002, description = "蕴含阴阳之力的神扇，法术攻击力出众", price = 1036800),
        "celestialOrb" to EquipmentTemplate("celestialOrb", "天玄杖", EquipmentSlot.WEAPON, 5, magicAttack = 1920, critChance = 0.22499999999999998, description = "蕴含天玄之力的神杖，攻击易暴击", price = 1036800),

        "immortalSword" to EquipmentTemplate("immortalSword", "诛仙剑", EquipmentSlot.WEAPON, 6, physicalAttack = 4050, critChance = 0.195, description = "上古仙人遗留的仙器，物理攻击力毁天灭地", price = 6000000),
        "chaosBlade" to EquipmentTemplate("chaosBlade", "玄玉刃", EquipmentSlot.WEAPON, 6, physicalAttack = 5970, critChance = 0.27, description = "玄玉淬炼的神刃，暴击率超凡入圣", price = 6000000),
        "primordialStaff" to EquipmentTemplate("primordialStaff", "天星杖", EquipmentSlot.WEAPON, 6, magicAttack = 4050, critChance = 0.195, description = "凝聚天星之力的法杖，法术攻击力无双", price = 6000000),
        "yinYangOrb" to EquipmentTemplate("yinYangOrb", "天玄扇", EquipmentSlot.WEAPON, 6, magicAttack = 5970, critChance = 0.27, description = "蕴含天玄道韵的至宝，攻击易暴击", price = 6000000)
    )

    val armors: Map<String, EquipmentTemplate> = mapOf(
        "leatherArmor" to EquipmentTemplate("leatherArmor", "皮甲", EquipmentSlot.ARMOR, 1, physicalDefense = 11, hp = 126, description = "野兽皮革制成的护甲，物理防御出众", price = 3600),
        "chainMail" to EquipmentTemplate("chainMail", "锁子甲", EquipmentSlot.ARMOR, 1, physicalDefense = 17, hp = 189, description = "铁环编织的护甲，增强生命力", price = 3600),
        "bronzePlate" to EquipmentTemplate("bronzePlate", "精铁甲", EquipmentSlot.ARMOR, 1, magicDefense = 11, hp = 126, description = "精铁铸造的铠甲，坚固且增强生命", price = 3600),
        "clothRobe" to EquipmentTemplate("clothRobe", "灵竹衣", EquipmentSlot.ARMOR, 1, magicDefense = 17, hp = 189, description = "灵竹纤维制成的衣物，法术防御出众", price = 3600),

        "ironPlate" to EquipmentTemplate("ironPlate", "碧叶甲", EquipmentSlot.ARMOR, 2, physicalDefense = 33, hp = 396, description = "碧玉叶片打造的护甲，物理防御力强大", price = 16200),
        "steelArmor" to EquipmentTemplate("steelArmor", "丹羽衣", EquipmentSlot.ARMOR, 2, physicalDefense = 51, hp = 594, description = "丹砂羽线织成的法衣，增强生命力", price = 16200),
        "spiritRobe" to EquipmentTemplate("spiritRobe", "灵丝袍", EquipmentSlot.ARMOR, 2, magicDefense = 33, hp = 396, description = "灵蚕丝织成的法袍，法术防御出众", price = 16200),
        "cloudRobe" to EquipmentTemplate("cloudRobe", "云纹袍", EquipmentSlot.ARMOR, 2, magicDefense = 51, hp = 594, description = "绣有云纹的法袍，增强生命力", price = 16200),

        "scaleArmor" to EquipmentTemplate("scaleArmor", "青鳞铠", EquipmentSlot.ARMOR, 3, physicalDefense = 102, hp = 1269, description = "妖兽青鳞打造的铠甲，物理防御力惊人", price = 64800),
        "plateArmor" to EquipmentTemplate("plateArmor", "银板铠", EquipmentSlot.ARMOR, 3, physicalDefense = 153, hp = 1905, description = "整块银钢锻造的铠甲，增强生命力", price = 64800),
        "mysticRobe" to EquipmentTemplate("mysticRobe", "汐流衣", EquipmentSlot.ARMOR, 3, magicDefense = 102, hp = 1269, description = "蕴含汐流之力的法衣，法术防御出众", price = 64800),
        "starRobe" to EquipmentTemplate("starRobe", "星辰袍", EquipmentSlot.ARMOR, 3, magicDefense = 153, hp = 1905, description = "绣有星辰图案的法袍，增强生命力", price = 64800),

        "dragonScale" to EquipmentTemplate("dragonScale", "龙鳞铠", EquipmentSlot.ARMOR, 4, physicalDefense = 315, hp = 4032, description = "真龙鳞片锻造的铠甲，物理防御无双", price = 259200),
        "titanArmor" to EquipmentTemplate("titanArmor", "渊岩铠", EquipmentSlot.ARMOR, 4, physicalDefense = 473, hp = 6048, description = "深渊岩铁铸造的铠甲，增强生命力", price = 259200),
        "voidRobe" to EquipmentTemplate("voidRobe", "瑶光袍", EquipmentSlot.ARMOR, 4, magicDefense = 315, hp = 4032, description = "蕴含瑶光之力的法袍，法术防御出众", price = 259200),
        "moonRobe" to EquipmentTemplate("moonRobe", "月华袍", EquipmentSlot.ARMOR, 4, magicDefense = 473, hp = 6048, description = "吸收月华之力织成的法袍，增强生命力", price = 259200),

        "earthArmor" to EquipmentTemplate("earthArmor", "玄幽袍", EquipmentSlot.ARMOR, 5, physicalDefense = 975, hp = 12750, description = "承载玄幽之力的法袍，物理防御如幽渊般坚固", price = 1036800),
        "divinePlate" to EquipmentTemplate("divinePlate", "墨幽铠", EquipmentSlot.ARMOR, 5, physicalDefense = 1467, hp = 19140, description = "墨幽玄铁铸造的铠甲，增强生命力", price = 1036800),
        "celestialRobe" to EquipmentTemplate("celestialRobe", "凌星袍", EquipmentSlot.ARMOR, 5, magicDefense = 975, hp = 12750, description = "凌驾星辰之力的法袍，法术防御超凡入圣", price = 1036800),
        "voidShadowRobe" to EquipmentTemplate("voidShadowRobe", "定海铠", EquipmentSlot.ARMOR, 5, magicDefense = 1467, hp = 19140, description = "定海之力凝聚的铠甲，增强生命力", price = 1036800),

        "immortalArmor" to EquipmentTemplate("immortalArmor", "不朽铠", EquipmentSlot.ARMOR, 6, physicalDefense = 3000, hp = 39300, description = "仙界神甲，物理防御不朽不灭", price = 6000000),
        "primordialArmor" to EquipmentTemplate("primordialArmor", "苍罡铠", EquipmentSlot.ARMOR, 6, physicalDefense = 4500, hp = 59100, description = "苍罡之力凝聚的神甲，承载创世生命之力", price = 6000000),
        "immortalRobe" to EquipmentTemplate("immortalRobe", "曦光铠", EquipmentSlot.ARMOR, 6, magicDefense = 3000, hp = 39300, description = "蕴含曦光之力的铠甲，法术防御超凡入圣", price = 6000000),
        "chaosRobe" to EquipmentTemplate("chaosRobe", "云影袍", EquipmentSlot.ARMOR, 6, magicDefense = 4500, hp = 59100, description = "云影交织的法袍，蕴含无尽生命之力", price = 6000000)
    )

    val boots: Map<String, EquipmentTemplate> = mapOf(
        "clothBoots" to EquipmentTemplate("clothBoots", "青澜靴", EquipmentSlot.BOOTS, 1, speed = 11, hp = 126, description = "青澜丝线织就的轻靴，提升移动速度", price = 3600),
        "leatherBoots" to EquipmentTemplate("leatherBoots", "兽皮靴", EquipmentSlot.BOOTS, 1, speed = 15, hp = 189, description = "兽皮鞣制的厚靴，增强生命力", price = 3600),

        "swiftBoots" to EquipmentTemplate("swiftBoots", "疾风靴", EquipmentSlot.BOOTS, 2, speed = 33, hp = 396, description = "穿上可大幅提升移动速度", price = 16200),
        "lightBoots" to EquipmentTemplate("lightBoots", "轻羽靴", EquipmentSlot.BOOTS, 2, speed = 50, hp = 594, description = "如羽毛般轻盈，蕴含生命之力", price = 16200),

        "windBoots" to EquipmentTemplate("windBoots", "追风靴", EquipmentSlot.BOOTS, 3, speed = 104, hp = 1269, description = "追逐风的速度，极速无双", price = 64800),
        "mistBoots" to EquipmentTemplate("mistBoots", "云栖靴", EquipmentSlot.BOOTS, 3, speed = 155, hp = 1905, description = "云栖之处步履轻盈，蕴含浓郁生命气息", price = 64800),

        "cloudBoots" to EquipmentTemplate("cloudBoots", "踏云履", EquipmentSlot.BOOTS, 4, speed = 323, hp = 4032, description = "踏云而行的仙家法宝，速度惊人", price = 259200),
        "thunderBoots" to EquipmentTemplate("thunderBoots", "奔雷靴", EquipmentSlot.BOOTS, 4, speed = 480, hp = 6048, description = "如雷电般厚重，蕴含磅礴生命力", price = 259200),

        "voidBoots" to EquipmentTemplate("voidBoots", "溯光靴", EquipmentSlot.BOOTS, 5, speed = 996, hp = 12750, description = "溯光逐影穿梭虚空，速度无双", price = 1036800),
        "shadowStepBoots" to EquipmentTemplate("shadowStepBoots", "赤煞靴", EquipmentSlot.BOOTS, 5, speed = 1488, hp = 19140, description = "赤煞之气凝聚的战靴，蕴含浩瀚生命力", price = 1036800),

        "immortalBoots" to EquipmentTemplate("immortalBoots", "鸾羽履", EquipmentSlot.BOOTS, 6, speed = 3072, hp = 39300, description = "鸾鸟仙羽织就的灵履，速度超凡入圣", price = 6000000),
        "chaosStepBoots" to EquipmentTemplate("chaosStepBoots", "鹤岚靴", EquipmentSlot.BOOTS, 6, speed = 4575, hp = 59100, description = "鹤翔岚雾而行，蕴含无尽生命之力", price = 6000000)
    )

    val accessories: Map<String, EquipmentTemplate> = mapOf(
        "jadeRing" to EquipmentTemplate("jadeRing", "玉戒指", EquipmentSlot.ACCESSORY, 1, mp = 63, speed = 11, description = "蕴含微量灵气的玉戒指，灵力出众", price = 3600),
        "copperNecklace" to EquipmentTemplate("copperNecklace", "铜项链", EquipmentSlot.ACCESSORY, 1, mp = 95, speed = 15, description = "铜制项链，可提升身法速度", price = 3600),

        "spiritPendant" to EquipmentTemplate("spiritPendant", "灵玉佩", EquipmentSlot.ACCESSORY, 2, mp = 204, speed = 33, description = "蕴含灵气的玉佩，灵力出众", price = 16200),
        "healthRing" to EquipmentTemplate("healthRing", "蕴灵戒", EquipmentSlot.ACCESSORY, 2, mp = 308, speed = 50, description = "可蕴养灵力的戒指，灵力出众", price = 16200),

        "storageRing" to EquipmentTemplate("storageRing", "灵泉戒", EquipmentSlot.ACCESSORY, 3, mp = 645, speed = 104, description = "蕴含灵泉之力的戒指，灵力出众", price = 64800),
        "wisdomOrb" to EquipmentTemplate("wisdomOrb", "迅捷珠", EquipmentSlot.ACCESSORY, 3, mp = 968, speed = 155, description = "可提升身法速度的宝珠", price = 64800),

        "dragonEye" to EquipmentTemplate("dragonEye", "龙灵珠", EquipmentSlot.ACCESSORY, 4, mp = 2040, speed = 323, description = "真龙之灵凝聚的宝珠，灵力出众", price = 259200),
        "phoenixHeart" to EquipmentTemplate("phoenixHeart", "凤羽坠", EquipmentSlot.ACCESSORY, 4, mp = 3060, speed = 480, description = "凤凰羽翼炼制的坠饰，可提升身法速度", price = 259200),

        "earthCore" to EquipmentTemplate("earthCore", "渡厄佩", EquipmentSlot.ACCESSORY, 5, mp = 6420, speed = 996, description = "可渡厄解难的灵佩，灵力出众", price = 1036800),
        "dragonEyePendant" to EquipmentTemplate("dragonEyePendant", "隐云佩", EquipmentSlot.ACCESSORY, 5, mp = 9630, speed = 1488, description = "隐于云端的灵佩，可大幅提升身法速度", price = 1036800),

        "chaosBead" to EquipmentTemplate("chaosBead", "幽朔珠", EquipmentSlot.ACCESSORY, 6, mp = 20100, speed = 3072, description = "蕴含幽朔之力的灵珠，灵力超凡入圣", price = 6000000),
        "heavenRing" to EquipmentTemplate("heavenRing", "长明坠", EquipmentSlot.ACCESSORY, 6, mp = 30150, speed = 4575, description = "长明不灭的灵坠，身法速度无双", price = 6000000)
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

    fun generateRandom(minRarity: Int = 1, maxRarity: Int = 6): EquipmentStack {
        val rarity = if (minRarity == maxRarity) {
            minRarity
        } else {
            generateRarity(minRarity, maxRarity)
        }
        val templates = getByRarity(rarity)
        val template = if (templates.isNotEmpty()) templates.random() else allTemplates.values.random()
        return createFromTemplate(template)
    }

    fun generateRandomBySlot(slot: EquipmentSlot, rarity: Int): EquipmentStack {
        val templates = getBySlot(slot).filter { it.rarity == rarity }
        val template = if (templates.isNotEmpty()) templates.random() else getBySlot(slot).random()
        return createFromTemplate(template)
    }

    fun createFromTemplate(template: EquipmentTemplate): EquipmentStack {
        return EquipmentStack(
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