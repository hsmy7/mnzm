package com.xianxia.sect.core.data

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.EquipmentSlot
import java.util.UUID

/**
 * 装备模板注册表
 *
 * 管理所有装备的静态模板数据（武器、防具、靴子、饰品）。
 * 数据来源：硬编码定义。
 *
 * ## 与原 EquipmentDatabase 的差异
 * - 继承 BaseTemplateRegistry，提供统一的查询接口
 * - 保留原有的分类存储（weapons/armors/boots/accessories）
 * - 支持按槽位、稀有度等多维度查询
 */
class EquipmentRegistry : BaseTemplateRegistry<EquipmentDatabase.EquipmentTemplate>() {

    // ==================== 数据定义 ====================

    /**
     * 武器模板映射
     */
    val weapons: Map<String, EquipmentDatabase.EquipmentTemplate> = mapOf(
        // 凡品 (rarity: 1)
        "ironSword" to EquipmentDatabase.EquipmentTemplate("ironSword", "精铁剑", EquipmentSlot.WEAPON, 1, physicalAttack = 10, critChance = 0.02, description = "普通铁匠打造的精铁剑，物理攻击力出众", price = 3600),
        "bronzeDagger" to EquipmentDatabase.EquipmentTemplate("bronzeDagger", "青铜匕首", EquipmentSlot.WEAPON, 1, physicalAttack = 14, critChance = 0.04, description = "青铜铸造的短刃，锋利易暴击", price = 3600),
        "woodenStaff" to EquipmentDatabase.EquipmentTemplate("woodenStaff", "桃木杖", EquipmentSlot.WEAPON, 1, magicAttack = 10, critChance = 0.02, description = "百年桃木制成的法杖，法术攻击力强大", price = 3600),
        "crystalOrb" to EquipmentDatabase.EquipmentTemplate("crystalOrb", "水晶珠", EquipmentSlot.WEAPON, 1, magicAttack = 14, critChance = 0.04, description = "蕴含微量灵气的水晶球，易触发暴击", price = 3600),

        // 灵品 (rarity: 2)
        "spiritSword" to EquipmentDatabase.EquipmentTemplate("spiritSword", "灵锋剑", EquipmentSlot.WEAPON, 2, physicalAttack = 30, critChance = 0.04, description = "注入灵气的锋利长剑，物理攻击力强大", price = 16200),
        "battleAxe" to EquipmentDatabase.EquipmentTemplate("battleAxe", "战斧", EquipmentSlot.WEAPON, 2, physicalAttack = 44, critChance = 0.06, description = "沉重的战斧，威力巨大且易暴击", price = 16200),
        "jadeStaff" to EquipmentDatabase.EquipmentTemplate("jadeStaff", "碧玉杖", EquipmentSlot.WEAPON, 2, magicAttack = 30, critChance = 0.04, description = "碧玉雕刻的法杖，法术攻击力出众", price = 16200),
        "spiritFan" to EquipmentDatabase.EquipmentTemplate("spiritFan", "灵风扇", EquipmentSlot.WEAPON, 2, magicAttack = 44, critChance = 0.06, description = "可扇出灵风的法器，攻击易暴击", price = 16200),

        // 宝品 (rarity: 3)
        "frostBlade" to EquipmentDatabase.EquipmentTemplate("frostBlade", "寒霜刃", EquipmentSlot.WEAPON, 3, physicalAttack = 92, critChance = 0.06, description = "蕴含寒冰之力的宝刀，物理攻击力出众", price = 64800),
        "flameSword" to EquipmentDatabase.EquipmentTemplate("flameSword", "烈焰剑", EquipmentSlot.WEAPON, 3, physicalAttack = 136, critChance = 0.09, description = "燃烧着火焰的灵剑，攻击易暴击", price = 64800),
        "thunderStaff" to EquipmentDatabase.EquipmentTemplate("thunderStaff", "雷霆杖", EquipmentSlot.WEAPON, 3, magicAttack = 92, critChance = 0.06, description = "可召唤雷电的法杖，法术攻击力强大", price = 64800),
        "frostOrb" to EquipmentDatabase.EquipmentTemplate("frostOrb", "玄冰珠", EquipmentSlot.WEAPON, 3, magicAttack = 136, critChance = 0.09, description = "蕴含玄冰之力的宝珠，攻击易暴击", price = 64800),

        // 玄品 (rarity: 4)
        "thunderSword" to EquipmentDatabase.EquipmentTemplate("thunderSword", "雷霆剑", EquipmentSlot.WEAPON, 4, physicalAttack = 285, critChance = 0.08, description = "引动天雷的玄妙飞剑，物理攻击力惊人", price = 259200),
        "shadowBlade" to EquipmentDatabase.EquipmentTemplate("shadowBlade", "暗影刃", EquipmentSlot.WEAPON, 4, physicalAttack = 420, critChance = 0.12, description = "来无影去无踪的暗杀之刃，暴击率极高", price = 259200),
        "voidStaff" to EquipmentDatabase.EquipmentTemplate("voidStaff", "虚空杖", EquipmentSlot.WEAPON, 4, magicAttack = 285, critChance = 0.08, description = "可撕裂虚空的法杖，法术攻击力出众", price = 259200),
        "phoenixFan" to EquipmentDatabase.EquipmentTemplate("phoenixFan", "凤凰扇", EquipmentSlot.WEAPON, 4, magicAttack = 420, critChance = 0.12, description = "凤凰羽毛制成的神扇，攻击易暴击", price = 259200),

        // 地品 (rarity: 5)
        "dragonSlayer" to EquipmentDatabase.EquipmentTemplate("dragonSlayer", "屠龙刀", EquipmentSlot.WEAPON, 5, physicalAttack = 880, critChance = 0.10, description = "传说中屠龙的绝世神兵，物理攻击力无双", price = 1036800),
        "godSlayer" to EquipmentDatabase.EquipmentTemplate("godSlayer", "弑神剑", EquipmentSlot.WEAPON, 5, physicalAttack = 1280, critChance = 0.15, description = "可弑杀神明的神剑，暴击率极高", price = 1036800),
        "phoenixWing" to EquipmentDatabase.EquipmentTemplate("phoenixWing", "凤凰羽", EquipmentSlot.WEAPON, 5, magicAttack = 880, critChance = 0.10, description = "凤凰羽翼化作的神兵，法术攻击力出众", price = 1036800),
        "celestialOrb" to EquipmentDatabase.EquipmentTemplate("celestialOrb", "天星珠", EquipmentSlot.WEAPON, 5, magicAttack = 1280, critChance = 0.15, description = "凝聚星辰之力的宝珠，攻击易暴击", price = 1036800),

        // 天品 (rarity: 6)
        "immortalSword" to EquipmentDatabase.EquipmentTemplate("immortalSword", "诛仙剑", EquipmentSlot.WEAPON, 6, physicalAttack = 2700, critChance = 0.13, description = "上古仙人遗留的仙器，物理攻击力毁天灭地", price = 6000000),
        "chaosBlade" to EquipmentDatabase.EquipmentTemplate("chaosBlade", "混沌刀", EquipmentSlot.WEAPON, 6, physicalAttack = 3980, critChance = 0.18, description = "开天辟地时的混沌之刃，暴击率超凡入圣", price = 6000000),
        "primordialStaff" to EquipmentDatabase.EquipmentTemplate("primordialStaff", "鸿蒙杖", EquipmentSlot.WEAPON, 6, magicAttack = 2700, critChance = 0.13, description = "鸿蒙初开时诞生的法杖，法术攻击力无双", price = 6000000),
        "yinYangOrb" to EquipmentDatabase.EquipmentTemplate("yinYangOrb", "阴阳珠", EquipmentSlot.WEAPON, 6, magicAttack = 3980, critChance = 0.18, description = "蕴含阴阳大道的至宝，攻击易暴击", price = 6000000)
    )

    /**
     * 防具模板映射
     */
    val armors: Map<String, EquipmentDatabase.EquipmentTemplate> = mapOf(
        // 凡品 (rarity: 1)
        "leatherArmor" to EquipmentDatabase.EquipmentTemplate("leatherArmor", "皮甲", EquipmentSlot.ARMOR, 1, physicalDefense = 7, hp = 84, description = "野兽皮革制成的护甲，物理防御出众", price = 3600),
        "chainMail" to EquipmentDatabase.EquipmentTemplate("chainMail", "锁子甲", EquipmentSlot.ARMOR, 1, physicalDefense = 11, hp = 126, description = "铁环编织的护甲，增强生命力", price = 3600),
        "bronzePlate" to EquipmentDatabase.EquipmentTemplate("bronzePlate", "青铜铠", EquipmentSlot.ARMOR, 1, magicDefense = 7, hp = 84, description = "青铜铸造的铠甲，坚固且增强生命", price = 3600),
        "clothRobe" to EquipmentDatabase.EquipmentTemplate("clothRobe", "布衣", EquipmentSlot.ARMOR, 1, magicDefense = 11, hp = 126, description = "普通布料制成的衣物，法术防御出众", price = 3600),

        // 灵品 (rarity: 2)
        "ironPlate" to EquipmentDatabase.EquipmentTemplate("ironPlate", "铁叶甲", EquipmentSlot.ARMOR, 2, physicalDefense = 22, hp = 264, description = "精铁叶片打造的护甲，物理防御力强大", price = 16200),
        "steelArmor" to EquipmentDatabase.EquipmentTemplate("steelArmor", "精钢铠", EquipmentSlot.ARMOR, 2, physicalDefense = 34, hp = 396, description = "百炼精钢打造的铠甲，增强生命力", price = 16200),
        "spiritRobe" to EquipmentDatabase.EquipmentTemplate("spiritRobe", "灵丝袍", EquipmentSlot.ARMOR, 2, magicDefense = 22, hp = 264, description = "灵蚕丝织成的法袍，法术防御出众", price = 16200),
        "cloudRobe" to EquipmentDatabase.EquipmentTemplate("cloudRobe", "云纹袍", EquipmentSlot.ARMOR, 2, magicDefense = 34, hp = 396, description = "绣有云纹的法袍，增强生命力", price = 16200),

        // 宝品 (rarity: 3)
        "scaleArmor" to EquipmentDatabase.EquipmentTemplate("scaleArmor", "鳞甲", EquipmentSlot.ARMOR, 3, physicalDefense = 68, hp = 846, description = "妖兽鳞片打造的护甲，物理防御力惊人", price = 64800),
        "plateArmor" to EquipmentDatabase.EquipmentTemplate("plateArmor", "板甲", EquipmentSlot.ARMOR, 3, physicalDefense = 102, hp = 1270, description = "整块精钢锻造的重甲，增强生命力", price = 64800),
        "mysticRobe" to EquipmentDatabase.EquipmentTemplate("mysticRobe", "玄法袍", EquipmentSlot.ARMOR, 3, magicDefense = 68, hp = 846, description = "蕴含玄妙法力的长袍，法术防御出众", price = 64800),
        "starRobe" to EquipmentDatabase.EquipmentTemplate("starRobe", "星辰袍", EquipmentSlot.ARMOR, 3, magicDefense = 102, hp = 1270, description = "绣有星辰图案的法袍，增强生命力", price = 64800),

        // 玄品 (rarity: 4)
        "dragonScale" to EquipmentDatabase.EquipmentTemplate("dragonScale", "龙鳞甲", EquipmentSlot.ARMOR, 4, physicalDefense = 210, hp = 2688, description = "真龙鳞片锻造的宝甲，物理防御无双", price = 259200),
        "titanArmor" to EquipmentDatabase.EquipmentTemplate("titanArmor", "泰坦铠", EquipmentSlot.ARMOR, 4, physicalDefense = 315, hp = 4032, description = "泰坦巨人使用的铠甲，增强生命力", price = 259200),
        "voidRobe" to EquipmentDatabase.EquipmentTemplate("voidRobe", "虚空袍", EquipmentSlot.ARMOR, 4, magicDefense = 210, hp = 2688, description = "穿梭于虚空之中的法袍，法术防御出众", price = 259200),
        "moonRobe" to EquipmentDatabase.EquipmentTemplate("moonRobe", "月华袍", EquipmentSlot.ARMOR, 4, magicDefense = 315, hp = 4032, description = "吸收月华之力织成的法袍，增强生命力", price = 259200),

        // 地品 (rarity: 5)
        "earthArmor" to EquipmentDatabase.EquipmentTemplate("earthArmor", "大地甲", EquipmentSlot.ARMOR, 5, physicalDefense = 650, hp = 8500, description = "承载大地之力的神甲，物理防御如大地般坚固", price = 1036800),
        "divinePlate" to EquipmentDatabase.EquipmentTemplate("divinePlate", "神铸铠", EquipmentSlot.ARMOR, 5, physicalDefense = 978, hp = 12760, description = "神匠铸造的铠甲，增强生命力", price = 1036800),
        "celestialRobe" to EquipmentDatabase.EquipmentTemplate("celestialRobe", "天罡袍", EquipmentSlot.ARMOR, 5, magicDefense = 650, hp = 8500, description = "天罡星力凝聚的法袍，法术防御超凡入圣", price = 1036800),
        "voidShadowRobe" to EquipmentDatabase.EquipmentTemplate("voidShadowRobe", "虚空影袍", EquipmentSlot.ARMOR, 5, magicDefense = 978, hp = 12760, description = "穿梭于虚空阴影中的神秘法袍，增强生命力", price = 1036800),

        // 天品 (rarity: 6)
        "immortalArmor" to EquipmentDatabase.EquipmentTemplate("immortalArmor", "不朽铠", EquipmentSlot.ARMOR, 6, physicalDefense = 2000, hp = 26200, description = "仙界神甲，物理防御不朽不灭", price = 6000000),
        "primordialArmor" to EquipmentDatabase.EquipmentTemplate("primordialArmor", "鸿蒙铠", EquipmentSlot.ARMOR, 6, physicalDefense = 3000, hp = 39400, description = "鸿蒙初开时诞生的神甲，承载创世生命之力", price = 6000000),
        "immortalRobe" to EquipmentDatabase.EquipmentTemplate("immortalRobe", "仙衣", EquipmentSlot.ARMOR, 6, magicDefense = 2000, hp = 26200, description = "仙人穿着的防御至宝，法术防御超凡入圣", price = 6000000),
        "chaosRobe" to EquipmentDatabase.EquipmentTemplate("chaosRobe", "混沌袍", EquipmentSlot.ARMOR, 6, magicDefense = 3000, hp = 39400, description = "混沌初开时诞生的法袍，蕴含无尽生命之力", price = 6000000)
    )

    /**
     * 靴子模板映射
     */
    val boots: Map<String, EquipmentDatabase.EquipmentTemplate> = mapOf(
        // 凡品 (rarity: 1)
        "clothBoots" to EquipmentDatabase.EquipmentTemplate("clothBoots", "布鞋", EquipmentSlot.BOOTS, 1, speed = 7, hp = 84, description = "轻便的布鞋，提升移动速度", price = 3600),
        "leatherBoots" to EquipmentDatabase.EquipmentTemplate("leatherBoots", "皮靴", EquipmentSlot.BOOTS, 1, speed = 10, hp = 126, description = "厚实的皮靴，增强生命力", price = 3600),

        // 灵品 (rarity: 2)
        "swiftBoots" to EquipmentDatabase.EquipmentTemplate("swiftBoots", "疾风靴", EquipmentSlot.BOOTS, 2, speed = 22, hp = 264, description = "穿上可大幅提升移动速度", price = 16200),
        "lightBoots" to EquipmentDatabase.EquipmentTemplate("lightBoots", "轻羽靴", EquipmentSlot.BOOTS, 2, speed = 33, hp = 396, description = "如羽毛般轻盈，蕴含生命之力", price = 16200),

        // 宝品 (rarity: 3)
        "windBoots" to EquipmentDatabase.EquipmentTemplate("windBoots", "追风靴", EquipmentSlot.BOOTS, 3, speed = 69, hp = 846, description = "追逐风的速度，极速无双", price = 64800),
        "mistBoots" to EquipmentDatabase.EquipmentTemplate("mistBoots", "迷雾靴", EquipmentSlot.BOOTS, 3, speed = 103, hp = 1270, description = "踏雾而行，蕴含浓郁生命气息", price = 64800),

        // 玄品 (rarity: 4)
        "cloudBoots" to EquipmentDatabase.EquipmentTemplate("cloudBoots", "踏云履", EquipmentSlot.BOOTS, 4, speed = 215, hp = 2688, description = "踏云而行的仙家法宝，速度惊人", price = 259200),
        "thunderBoots" to EquipmentDatabase.EquipmentTemplate("thunderBoots", "奔雷靴", EquipmentSlot.BOOTS, 4, speed = 320, hp = 4032, description = "如雷电般厚重，蕴含磅礴生命力", price = 259200),

        // 地品 (rarity: 5)
        "voidBoots" to EquipmentDatabase.EquipmentTemplate("voidBoots", "虚空步", EquipmentSlot.BOOTS, 5, speed = 664, hp = 8500, description = "行走于虚空之间，速度无双", price = 1036800),
        "shadowStepBoots" to EquipmentDatabase.EquipmentTemplate("shadowStepBoots", "影舞步", EquipmentSlot.BOOTS, 5, speed = 992, hp = 12760, description = "如影子般厚重，蕴含浩瀚生命力", price = 1036800),

        // 天品 (rarity: 6)
        "immortalBoots" to EquipmentDatabase.EquipmentTemplate("immortalBoots", "仙踪步", EquipmentSlot.BOOTS, 6, speed = 2048, hp = 26200, description = "仙人留下的神行之靴，速度超凡入圣", price = 6000000),
        "chaosStepBoots" to EquipmentDatabase.EquipmentTemplate("chaosStepBoots", "混沌履", EquipmentSlot.BOOTS, 6, speed = 3050, hp = 39400, description = "踏混沌而行，蕴含无尽生命之力", price = 6000000)
    )

    /**
     * 饰品模板映射
     */
    val accessories: Map<String, EquipmentDatabase.EquipmentTemplate> = mapOf(
        // 凡品 (rarity: 1)
        "jadeRing" to EquipmentDatabase.EquipmentTemplate("jadeRing", "玉戒指", EquipmentSlot.ACCESSORY, 1, mp = 42, speed = 7, description = "蕴含微量灵气的玉戒指，灵力出众", price = 3600),
        "copperNecklace" to EquipmentDatabase.EquipmentTemplate("copperNecklace", "铜项链", EquipmentSlot.ACCESSORY, 1, mp = 63, speed = 10, description = "铜制项链，可提升身法速度", price = 3600),

        // 灵品 (rarity: 2)
        "spiritPendant" to EquipmentDatabase.EquipmentTemplate("spiritPendant", "灵玉佩", EquipmentSlot.ACCESSORY, 2, mp = 136, speed = 22, description = "蕴含灵气的玉佩，灵力出众", price = 16200),
        "healthRing" to EquipmentDatabase.EquipmentTemplate("healthRing", "疾风戒", EquipmentSlot.ACCESSORY, 2, mp = 205, speed = 33, description = "可提升身法速度的戒指", price = 16200),

        // 宝品 (rarity: 3)
        "storageRing" to EquipmentDatabase.EquipmentTemplate("storageRing", "灵泉戒", EquipmentSlot.ACCESSORY, 3, mp = 430, speed = 69, description = "蕴含灵泉之力的戒指，灵力出众", price = 64800),
        "wisdomOrb" to EquipmentDatabase.EquipmentTemplate("wisdomOrb", "迅捷珠", EquipmentSlot.ACCESSORY, 3, mp = 645, speed = 103, description = "可提升身法速度的宝珠", price = 64800),

        // 玄品 (rarity: 4)
        "dragonEye" to EquipmentDatabase.EquipmentTemplate("dragonEye", "龙灵珠", EquipmentSlot.ACCESSORY, 4, mp = 1360, speed = 215, description = "真龙之灵凝聚的宝珠，灵力出众", price = 259200),
        "phoenixHeart" to EquipmentDatabase.EquipmentTemplate("phoenixHeart", "凤羽坠", EquipmentSlot.ACCESSORY, 4, mp = 2040, speed = 320, description = "凤凰羽翼炼制的坠饰，可提升身法速度", price = 259200),

        // 地品 (rarity: 5)
        "earthCore" to EquipmentDatabase.EquipmentTemplate("earthCore", "地灵核", EquipmentSlot.ACCESSORY, 5, mp = 4280, speed = 664, description = "大地灵核凝聚的至宝，灵力出众", price = 1036800),
        "dragonEyePendant" to EquipmentDatabase.EquipmentTemplate("dragonEyePendant", "风行坠", EquipmentSlot.ACCESSORY, 5, mp = 6420, speed = 992, description = "蕴含风行法则的坠饰，可大幅提升身法速度", price = 1036800),

        // 天品 (rarity: 6)
        "chaosBead" to EquipmentDatabase.EquipmentTemplate("chaosBead", "混沌灵珠", EquipmentSlot.ACCESSORY, 6, mp = 13400, speed = 2048, description = "蕴含混沌灵力的至宝，灵力超凡入圣", price = 6000000),
        "heavenRing" to EquipmentDatabase.EquipmentTemplate("heavenRing", "天行戒", EquipmentSlot.ACCESSORY, 6, mp = 20100, speed = 3050, description = "承载天行法则的神戒，身法速度无双", price = 6000000)
    )

    // ==================== BaseTemplateRegistry 实现 ====================

    override fun loadTemplates(): Map<String, EquipmentDatabase.EquipmentTemplate> {
        return weapons + armors + boots + accessories
    }

    override fun extractRarity(template: EquipmentDatabase.EquipmentTemplate): Int = template.rarity

    // ==================== 扩展查询方法 ====================

    /**
     * 根据装备槽位获取模板列表
     *
     * @param slot 装备槽位类型
     * @return 对应槽位的所有模板
     */
    fun getBySlot(slot: EquipmentSlot): List<EquipmentDatabase.EquipmentTemplate> = when (slot) {
        EquipmentSlot.WEAPON -> weapons.values.toList()
        EquipmentSlot.ARMOR -> armors.values.toList()
        EquipmentSlot.BOOTS -> boots.values.toList()
        EquipmentSlot.ACCESSORY -> accessories.values.toList()
    }

    /**
     * 根据名称查找模板
     *
     * @param name 装备名称
     * @return 匹配的模板，不存在则返回 null
     */
    fun getByName(name: String): EquipmentDatabase.EquipmentTemplate? =
        allTemplates.values.find { it.name == name }

    /**
     * 从模板创建装备实例
     *
     * @param template 装备模板
     * @return 新生成的装备实例（带随机 UUID）
     */
    fun createFromTemplate(template: EquipmentDatabase.EquipmentTemplate): Equipment {
        return Equipment(
            id = UUID.randomUUID().toString(),
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

    /**
     * 随机生成一个装备实例
     *
     * @param minRarity 最低稀有度（默认1）
     * @param maxRarity 最高稀有度（默认6）
     * @return 随机生成的装备实例
     */
    fun generateRandom(minRarity: Int = 1, maxRarity: Int = 6): Equipment {
        val template = getRandom(minRarity, maxRarity)
        return createFromTemplate(template)
    }

    /**
     * 根据槽位和稀有度随机生成装备
     *
     * @param slot 装备槽位
     * @param rarity 目标稀有度
     * @return 随机生成的装备实例
     */
    fun generateRandomBySlot(slot: EquipmentSlot, rarity: Int): Equipment {
        val templates = getBySlot(slot).filter { it.rarity == rarity }
        val template = if (templates.isNotEmpty()) templates.random() else getBySlot(slot).random()
        return createFromTemplate(template)
    }
}
