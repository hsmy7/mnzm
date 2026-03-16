package com.xianxia.sect.core.data

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.ManualType
import kotlin.random.Random

object ManualDatabase {
    
    data class ManualTemplate(
        val id: String,
        val name: String,
        val type: ManualType,
        val rarity: Int,
        val description: String,
        val stats: Map<String, Int> = emptyMap(),
        val skillName: String? = null,
        val skillDescription: String? = null,
        val skillType: String = "attack",
        val skillDamageType: String = "physical",
        val skillHits: Int = 1,
        val skillDamageMultiplier: Double = 1.0,
        val skillCooldown: Int = 3,
        val skillMpCost: Int = 10,
        val skillHealPercent: Double = 0.0,
        val skillHealType: String = "hp",
        val price: Int = 0,
        val minRealm: Int = 9
    )
    
    val attackManuals: Map<String, ManualTemplate> = mapOf(
        "commonPhysicalAtk" to ManualTemplate(
            id = "commonPhysicalAtk",
            name = "青锋剑诀",
            type = ManualType.ATTACK,
            rarity = 1,
            description = "基础剑法，提升物理攻击",
            stats = mapOf("physicalAttack" to 15),
            skillName = "剑气斩",
            skillDescription = "发出一道剑气攻击敌人",
            skillDamageType = "physical",
            skillHits = 1,
            skillDamageMultiplier = 1.2,
            skillCooldown = 3,
            skillMpCost = 10,
            price = 3600
        ),
        "commonMagicAtk" to ManualTemplate(
            id = "commonMagicAtk",
            name = "炎阳真诀",
            type = ManualType.ATTACK,
            rarity = 1,
            description = "基础法术，提升法术攻击",
            stats = mapOf("magicAttack" to 15),
            skillName = "火球术",
            skillDescription = "发射一颗火球攻击敌人",
            skillDamageType = "magic",
            skillHits = 1,
            skillDamageMultiplier = 1.2,
            skillCooldown = 3,
            skillMpCost = 10,
            price = 3600
        ),
        "commonCrit" to ManualTemplate(
            id = "commonCrit",
            name = "锐气诀",
            type = ManualType.ATTACK,
            rarity = 1,
            description = "基础功法，提升暴击率",
            stats = mapOf("critRate" to 8),
            skillName = "致命一击",
            skillDescription = "寻找敌人破绽进行攻击",
            skillDamageType = "physical",
            skillHits = 1,
            skillDamageMultiplier = 1.0,
            skillCooldown = 3,
            skillMpCost = 8,
            price = 3600
        ),
        "uncommonPhysicalAtk" to ManualTemplate(
            id = "uncommonPhysicalAtk",
            name = "破军剑典",
            type = ManualType.ATTACK,
            rarity = 2,
            description = "进阶剑法，大幅提升物理攻击",
            stats = mapOf("physicalAttack" to 30),
            skillName = "破军斩",
            skillDescription = "凝聚灵力发出强力一击",
            skillDamageType = "physical",
            skillHits = 1,
            skillDamageMultiplier = 1.8,
            skillCooldown = 3,
            skillMpCost = 25,
            price = 16200
        ),
        "uncommonMagicAtk" to ManualTemplate(
            id = "uncommonMagicAtk",
            name = "赤焰真经",
            type = ManualType.ATTACK,
            rarity = 2,
            description = "进阶法术，大幅提升法术攻击",
            stats = mapOf("magicAttack" to 30),
            skillName = "赤焰冲击",
            skillDescription = "释放火焰冲击波攻击敌人",
            skillDamageType = "magic",
            skillHits = 1,
            skillDamageMultiplier = 1.8,
            skillCooldown = 3,
            skillMpCost = 25,
            price = 16200
        ),
        "uncommonCrit" to ManualTemplate(
            id = "uncommonCrit",
            name = "战意诀",
            type = ManualType.ATTACK,
            rarity = 2,
            description = "进阶功法，提升暴击率",
            stats = mapOf("critRate" to 15),
            skillName = "战意爆发",
            skillDescription = "激发战意提升暴击",
            skillDamageType = "physical",
            skillHits = 1,
            skillDamageMultiplier = 1.0,
            skillCooldown = 3,
            skillMpCost = 20,
            price = 16200
        ),
        "rarePhysicalMagicAtk" to ManualTemplate(
            id = "rarePhysicalMagicAtk",
            name = "斩龙剑诀",
            type = ManualType.ATTACK,
            rarity = 3,
            description = "高阶剑法，同时提升物理和法术攻击",
            stats = mapOf("physicalAttack" to 35, "magicAttack" to 25),
            skillName = "斩龙击",
            skillDescription = "发出一道锐利的剑气",
            skillDamageType = "physical",
            skillHits = 1,
            skillDamageMultiplier = 2.4,
            skillCooldown = 4,
            skillMpCost = 60,
            price = 64800
        ),
        "rarePhysicalCrit" to ManualTemplate(
            id = "rarePhysicalCrit",
            name = "狂风剑诀",
            type = ManualType.ATTACK,
            rarity = 3,
            description = "高阶剑法，提升物理攻击和暴击率",
            stats = mapOf("physicalAttack" to 35, "critRate" to 12),
            skillName = "狂风斩",
            skillDescription = "掀起暴风进行攻击",
            skillDamageType = "physical",
            skillHits = 2,
            skillDamageMultiplier = 1.2,
            skillCooldown = 4,
            skillMpCost = 70,
            price = 64800
        ),
        "rareMagicCrit" to ManualTemplate(
            id = "rareMagicCrit",
            name = "九阳真诀",
            type = ManualType.ATTACK,
            rarity = 3,
            description = "高阶法术，提升法术攻击和暴击率",
            stats = mapOf("magicAttack" to 35, "critRate" to 12),
            skillName = "九阳焚天",
            skillDescription = "召唤焚天之火焚烧敌人",
            skillDamageType = "magic",
            skillHits = 1,
            skillDamageMultiplier = 2.4,
            skillCooldown = 4,
            skillMpCost = 60,
            price = 64800
        ),
        "epicPhysicalMagicAtk" to ManualTemplate(
            id = "epicPhysicalMagicAtk",
            name = "太虚剑典",
            type = ManualType.ATTACK,
            rarity = 4,
            description = "玄阶剑法，同时提升物理和法术攻击",
            stats = mapOf("physicalAttack" to 55, "magicAttack" to 40),
            skillName = "太虚剑意",
            skillDescription = "召唤万道剑气合一攻击",
            skillDamageType = "physical",
            skillHits = 1,
            skillDamageMultiplier = 3.0,
            skillCooldown = 5,
            skillMpCost = 150,
            price = 259200
        ),
        "epicPhysicalCrit" to ManualTemplate(
            id = "epicPhysicalCrit",
            name = "天罡剑典",
            type = ManualType.ATTACK,
            rarity = 4,
            description = "玄阶剑法，提升物理攻击和暴击率",
            stats = mapOf("physicalAttack" to 55, "critRate" to 18),
            skillName = "天罡斩",
            skillDescription = "融合灵力进行斩击",
            skillDamageType = "physical",
            skillHits = 2,
            skillDamageMultiplier = 1.5,
            skillCooldown = 5,
            skillMpCost = 170,
            price = 259200
        ),
        "epicMagicCrit" to ManualTemplate(
            id = "epicMagicCrit",
            name = "紫霄真经",
            type = ManualType.ATTACK,
            rarity = 4,
            description = "玄阶法术，提升法术攻击和暴击率",
            stats = mapOf("magicAttack" to 55, "critRate" to 18),
            skillName = "紫霄神雷",
            skillDescription = "释放神雷焚烧一切",
            skillDamageType = "magic",
            skillHits = 1,
            skillDamageMultiplier = 3.0,
            skillCooldown = 5,
            skillMpCost = 150,
            price = 259200
        ),
        "legendaryFullAtk" to ManualTemplate(
            id = "legendaryFullAtk",
            name = "诛仙剑诀",
            type = ManualType.ATTACK,
            rarity = 5,
            description = "地阶剑法，全面提升攻击属性",
            stats = mapOf("physicalAttack" to 80, "magicAttack" to 60, "critRate" to 25),
            skillName = "诛仙剑气",
            skillDescription = "引动天外之力发出绝世一剑",
            skillDamageType = "physical",
            skillHits = 1,
            skillDamageMultiplier = 4.2,
            skillCooldown = 6,
            skillMpCost = 400,
            price = 1036800
        ),
        "legendaryPhysicalFocus" to ManualTemplate(
            id = "legendaryPhysicalFocus",
            name = "戮神剑诀",
            type = ManualType.ATTACK,
            rarity = 5,
            description = "地阶剑法，专注物理攻击",
            stats = mapOf("physicalAttack" to 100, "magicAttack" to 40, "critRate" to 25),
            skillName = "戮神七斩",
            skillDescription = "瞬间移动进行七次致命打击",
            skillDamageType = "physical",
            skillHits = 3,
            skillDamageMultiplier = 1.4,
            skillCooldown = 6,
            skillMpCost = 450,
            price = 1036800
        ),
        "legendaryMagicFocus" to ManualTemplate(
            id = "legendaryMagicFocus",
            name = "灭世真诀",
            type = ManualType.ATTACK,
            rarity = 5,
            description = "地阶法术，专注法术攻击",
            stats = mapOf("physicalAttack" to 40, "magicAttack" to 100, "critRate" to 25),
            skillName = "灭世业火",
            skillDescription = "召唤业火红莲焚烧天地",
            skillDamageType = "magic",
            skillHits = 1,
            skillDamageMultiplier = 4.2,
            skillCooldown = 6,
            skillMpCost = 400,
            price = 1036800
        ),
        "mythicFullAtk" to ManualTemplate(
            id = "mythicFullAtk",
            name = "开天剑典",
            type = ManualType.ATTACK,
            rarity = 6,
            description = "天阶剑法，开天辟地之力",
            stats = mapOf("physicalAttack" to 130, "magicAttack" to 100, "critRate" to 35),
            skillName = "开天一击",
            skillDescription = "一剑开天辟地，毁天灭地之力",
            skillDamageType = "physical",
            skillHits = 1,
            skillDamageMultiplier = 6.0,
            skillCooldown = 8,
            skillMpCost = 1000,
            price = 4147200
        ),
        "mythicPhysicalFocus" to ManualTemplate(
            id = "mythicPhysicalFocus",
            name = "混沌剑典",
            type = ManualType.ATTACK,
            rarity = 6,
            description = "天阶剑法，混沌之力",
            stats = mapOf("physicalAttack" to 160, "magicAttack" to 70, "critRate" to 35),
            skillName = "混沌十斩",
            skillDescription = "引动天地之力进行十连斩",
            skillDamageType = "physical",
            skillHits = 4,
            skillDamageMultiplier = 1.5,
            skillCooldown = 8,
            skillMpCost = 1100,
            price = 4147200
        ),
        "mythicMagicFocus" to ManualTemplate(
            id = "mythicMagicFocus",
            name = "鸿蒙真诀",
            type = ManualType.ATTACK,
            rarity = 6,
            description = "天阶法术，鸿蒙之力",
            stats = mapOf("physicalAttack" to 70, "magicAttack" to 160, "critRate" to 35),
            skillName = "鸿蒙神火",
            skillDescription = "召唤混沌初开时的神火",
            skillDamageType = "magic",
            skillHits = 1,
            skillDamageMultiplier = 6.0,
            skillCooldown = 8,
            skillMpCost = 1000,
            price = 4147200
        )
    )
    
    val defenseManuals: Map<String, ManualTemplate> = mapOf(
        "commonPhysicalDef" to ManualTemplate(
            id = "commonPhysicalDef",
            name = "铁骨功",
            type = ManualType.DEFENSE,
            rarity = 1,
            description = "基础防御功法，提升物理防御",
            stats = mapOf("physicalDefense" to 10),
            price = 3600
        ),
        "commonMagicDef" to ManualTemplate(
            id = "commonMagicDef",
            name = "静心咒",
            type = ManualType.DEFENSE,
            rarity = 1,
            description = "基础防御功法，提升法术防御",
            stats = mapOf("magicDefense" to 10),
            price = 3600
        ),
        "commonHp" to ManualTemplate(
            id = "commonHp",
            name = "回春心法",
            type = ManualType.DEFENSE,
            rarity = 1,
            description = "基础防御功法，提升生命上限",
            stats = mapOf("hp" to 30),
            price = 3600
        ),
        "uncommonPhysicalDef" to ManualTemplate(
            id = "uncommonPhysicalDef",
            name = "铜皮铁骨",
            type = ManualType.DEFENSE,
            rarity = 2,
            description = "进阶防御功法，大幅提升物理防御",
            stats = mapOf("physicalDefense" to 20),
            price = 16200
        ),
        "uncommonMagicDef" to ManualTemplate(
            id = "uncommonMagicDef",
            name = "灵台清明",
            type = ManualType.DEFENSE,
            rarity = 2,
            description = "进阶防御功法，大幅提升法术防御",
            stats = mapOf("magicDefense" to 20),
            price = 16200
        ),
        "uncommonHp" to ManualTemplate(
            id = "uncommonHp",
            name = "玄龟心法",
            type = ManualType.DEFENSE,
            rarity = 2,
            description = "进阶防御功法，大幅提升生命上限",
            stats = mapOf("hp" to 60),
            price = 16200
        ),
        "rarePhysicalMagicDef" to ManualTemplate(
            id = "rarePhysicalMagicDef",
            name = "金刚不坏身",
            type = ManualType.DEFENSE,
            rarity = 3,
            description = "高阶防御功法，同时提升物理和法术防御",
            stats = mapOf("physicalDefense" to 35, "magicDefense" to 25),
            price = 64800
        ),
        "rarePhysicalHp" to ManualTemplate(
            id = "rarePhysicalHp",
            name = "玄武护体",
            type = ManualType.DEFENSE,
            rarity = 3,
            description = "高阶防御功法，提升物理防御和生命",
            stats = mapOf("physicalDefense" to 35, "hp" to 80),
            price = 64800
        ),
        "rareMagicHp" to ManualTemplate(
            id = "rareMagicHp",
            name = "灵龟心经",
            type = ManualType.DEFENSE,
            rarity = 3,
            description = "高阶防御功法，提升法术防御和生命",
            stats = mapOf("magicDefense" to 35, "hp" to 80),
            price = 64800
        ),
        "epicPhysicalMagicDef" to ManualTemplate(
            id = "epicPhysicalMagicDef",
            name = "龙鳞护甲",
            type = ManualType.DEFENSE,
            rarity = 4,
            description = "玄阶防御功法，同时提升物理和法术防御",
            stats = mapOf("physicalDefense" to 55, "magicDefense" to 40),
            price = 259200
        ),
        "epicPhysicalHp" to ManualTemplate(
            id = "epicPhysicalHp",
            name = "泰坦之躯",
            type = ManualType.DEFENSE,
            rarity = 4,
            description = "玄阶防御功法，提升物理防御和生命",
            stats = mapOf("physicalDefense" to 55, "hp" to 150),
            price = 259200
        ),
        "epicMagicHp" to ManualTemplate(
            id = "epicMagicHp",
            name = "虚空护体",
            type = ManualType.DEFENSE,
            rarity = 4,
            description = "玄阶防御功法，提升法术防御和生命",
            stats = mapOf("magicDefense" to 55, "hp" to 150),
            price = 259200
        ),
        "legendaryFullDef" to ManualTemplate(
            id = "legendaryFullDef",
            name = "不灭金身",
            type = ManualType.DEFENSE,
            rarity = 5,
            description = "地阶防御功法，全面提升防御属性",
            stats = mapOf("physicalDefense" to 80, "magicDefense" to 60, "hp" to 200),
            price = 1036800
        ),
        "legendaryPhysicalFocus" to ManualTemplate(
            id = "legendaryPhysicalFocusDef",
            name = "大地守护",
            type = ManualType.DEFENSE,
            rarity = 5,
            description = "地阶防御功法，专注物理防御",
            stats = mapOf("physicalDefense" to 100, "hp" to 300),
            price = 1036800
        ),
        "legendaryMagicFocus" to ManualTemplate(
            id = "legendaryMagicFocusDef",
            name = "天罡护体",
            type = ManualType.DEFENSE,
            rarity = 5,
            description = "地阶防御功法，专注法术防御",
            stats = mapOf("magicDefense" to 100, "hp" to 300),
            price = 1036800
        ),
        "mythicFullDef" to ManualTemplate(
            id = "mythicFullDef",
            name = "鸿蒙金身",
            type = ManualType.DEFENSE,
            rarity = 6,
            description = "天阶防御功法，鸿蒙不灭之身",
            stats = mapOf("physicalDefense" to 150, "magicDefense" to 120, "hp" to 500),
            price = 4147200
        ),
        "mythicPhysicalFocus" to ManualTemplate(
            id = "mythicPhysicalFocusDef",
            name = "混沌之躯",
            type = ManualType.DEFENSE,
            rarity = 6,
            description = "天阶防御功法，混沌不灭",
            stats = mapOf("physicalDefense" to 180, "hp" to 600),
            price = 4147200
        ),
        "mythicMagicFocus" to ManualTemplate(
            id = "mythicMagicFocusDef",
            name = "仙体护身",
            type = ManualType.DEFENSE,
            rarity = 6,
            description = "天阶防御功法，仙体不坏",
            stats = mapOf("magicDefense" to 180, "hp" to 600),
            price = 4147200
        )
    )
    
    val supportManuals: Map<String, ManualTemplate> = mapOf(
        "commonLifeSupport" to ManualTemplate(
            id = "commonLifeSupport",
            name = "回春术",
            type = ManualType.SUPPORT,
            rarity = 1,
            description = "凡阶辅助功法，提升生命上限",
            stats = mapOf("hp" to 30),
            skillName = "生命祝福",
            skillDescription = "为全队恢复施法者最大生命值8%的生命",
            skillType = "support",
            skillHealPercent = 0.08,
            skillCooldown = 5,
            skillMpCost = 12,
            price = 3600
        ),
        "commonManaSupport" to ManualTemplate(
            id = "commonManaSupport",
            name = "聚灵术",
            type = ManualType.SUPPORT,
            rarity = 1,
            description = "凡阶辅助功法，提升灵力上限",
            stats = mapOf("mp" to 15),
            skillName = "灵力祝福",
            skillDescription = "为全队恢复施法者最大灵力8%的灵力",
            skillType = "support",
            skillHealPercent = 0.08,
            skillHealType = "mp",
            skillCooldown = 5,
            skillMpCost = 12,
            price = 3600
        ),
        "uncommonLifeSupport" to ManualTemplate(
            id = "uncommonLifeSupport",
            name = "玄龟守护",
            type = ManualType.SUPPORT,
            rarity = 2,
            description = "灵阶辅助功法，大幅提升生命上限",
            stats = mapOf("hp" to 60),
            skillName = "玄龟祝福",
            skillDescription = "为全队恢复施法者最大生命值15%的生命",
            skillType = "support",
            skillHealPercent = 0.15,
            skillCooldown = 5,
            skillMpCost = 30,
            price = 16200
        ),
        "uncommonManaSupport" to ManualTemplate(
            id = "uncommonManaSupport",
            name = "灵泉涌动",
            type = ManualType.SUPPORT,
            rarity = 2,
            description = "灵阶辅助功法，大幅提升灵力上限",
            stats = mapOf("mp" to 30),
            skillName = "灵泉祝福",
            skillDescription = "为全队恢复施法者最大灵力15%的灵力",
            skillType = "support",
            skillHealPercent = 0.15,
            skillHealType = "mp",
            skillCooldown = 5,
            skillMpCost = 30,
            price = 16200
        ),
        "rareLifeSupport" to ManualTemplate(
            id = "rareLifeSupport",
            name = "长生秘术",
            type = ManualType.SUPPORT,
            rarity = 3,
            description = "宝阶辅助功法，大幅提升生命上限",
            stats = mapOf("hp" to 120),
            skillName = "长生祝福",
            skillDescription = "为全队恢复施法者最大生命值25%的生命",
            skillType = "support",
            skillHealPercent = 0.25,
            skillCooldown = 5,
            skillMpCost = 75,
            price = 64800
        ),
        "rareManaSupport" to ManualTemplate(
            id = "rareManaSupport",
            name = "灵海潮汐",
            type = ManualType.SUPPORT,
            rarity = 3,
            description = "宝阶辅助功法，大幅提升灵力上限",
            stats = mapOf("mp" to 60),
            skillName = "灵海祝福",
            skillDescription = "为全队恢复施法者最大灵力25%的灵力",
            skillType = "support",
            skillHealPercent = 0.25,
            skillHealType = "mp",
            skillCooldown = 5,
            skillMpCost = 75,
            price = 64800
        ),
        "epicLifeSupport" to ManualTemplate(
            id = "epicLifeSupport",
            name = "不死秘术",
            type = ManualType.SUPPORT,
            rarity = 4,
            description = "玄阶辅助功法，大幅提升生命上限",
            stats = mapOf("hp" to 250),
            skillName = "不死祝福",
            skillDescription = "为全队恢复施法者最大生命值40%的生命",
            skillType = "support",
            skillHealPercent = 0.40,
            skillCooldown = 5,
            skillMpCost = 180,
            price = 259200
        ),
        "epicManaSupport" to ManualTemplate(
            id = "epicManaSupport",
            name = "灵渊秘术",
            type = ManualType.SUPPORT,
            rarity = 4,
            description = "玄阶辅助功法，大幅提升灵力上限",
            stats = mapOf("mp" to 125),
            skillName = "灵渊祝福",
            skillDescription = "为全队恢复施法者最大灵力40%的灵力",
            skillType = "support",
            skillHealPercent = 0.40,
            skillHealType = "mp",
            skillCooldown = 5,
            skillMpCost = 180,
            price = 259200
        ),
        "legendaryLifeSupport" to ManualTemplate(
            id = "legendaryLifeSupport",
            name = "不灭神术",
            type = ManualType.SUPPORT,
            rarity = 5,
            description = "地阶辅助功法，大幅提升生命上限",
            stats = mapOf("hp" to 500),
            skillName = "不灭祝福",
            skillDescription = "为全队恢复施法者最大生命值60%的生命",
            skillType = "support",
            skillHealPercent = 0.60,
            skillCooldown = 5,
            skillMpCost = 500,
            price = 1036800
        ),
        "legendaryManaSupport" to ManualTemplate(
            id = "legendaryManaSupport",
            name = "灵源神术",
            type = ManualType.SUPPORT,
            rarity = 5,
            description = "地阶辅助功法，大幅提升灵力上限",
            stats = mapOf("mp" to 250),
            skillName = "灵源祝福",
            skillDescription = "为全队恢复施法者最大灵力60%的灵力",
            skillType = "support",
            skillHealPercent = 0.60,
            skillHealType = "mp",
            skillCooldown = 5,
            skillMpCost = 500,
            price = 1036800
        ),
        "mythicLifeSupport" to ManualTemplate(
            id = "mythicLifeSupport",
            name = "混沌生命术",
            type = ManualType.SUPPORT,
            rarity = 6,
            description = "天阶辅助功法，大幅提升生命上限",
            stats = mapOf("hp" to 1000),
            skillName = "混沌生命",
            skillDescription = "为全队恢复施法者最大生命值100%的生命",
            skillType = "support",
            skillHealPercent = 1.0,
            skillCooldown = 5,
            skillMpCost = 1200,
            price = 4147200
        ),
        "mythicManaSupport" to ManualTemplate(
            id = "mythicManaSupport",
            name = "混沌灵源术",
            type = ManualType.SUPPORT,
            rarity = 6,
            description = "天阶辅助功法，大幅提升灵力上限",
            stats = mapOf("mp" to 500),
            skillName = "混沌灵源",
            skillDescription = "为全队恢复施法者最大灵力100%的灵力",
            skillType = "support",
            skillHealPercent = 1.0,
            skillHealType = "mp",
            skillCooldown = 5,
            skillMpCost = 1200,
            price = 4147200
        )
    )
    
    val movementManuals: Map<String, ManualTemplate> = mapOf(
        "commonMovement" to ManualTemplate(
            id = "commonMovement",
            name = "轻身术",
            type = ManualType.MOVEMENT,
            rarity = 1,
            description = "基础身法，提升速度",
            stats = mapOf("speed" to 5),
            price = 3600
        ),
        "uncommonMovement" to ManualTemplate(
            id = "uncommonMovement",
            name = "疾风步",
            type = ManualType.MOVEMENT,
            rarity = 2,
            description = "进阶身法，大幅提升速度",
            stats = mapOf("speed" to 12),
            price = 16200
        ),
        "rareMovement" to ManualTemplate(
            id = "rareMovement",
            name = "追风步",
            type = ManualType.MOVEMENT,
            rarity = 3,
            description = "高阶身法，追逐风的速度",
            stats = mapOf("speed" to 25),
            price = 64800
        ),
        "epicMovement" to ManualTemplate(
            id = "epicMovement",
            name = "踏云步",
            type = ManualType.MOVEMENT,
            rarity = 4,
            description = "玄阶身法，踏云而行",
            stats = mapOf("speed" to 40),
            price = 259200
        ),
        "legendaryMovement" to ManualTemplate(
            id = "legendaryMovement",
            name = "虚空步",
            type = ManualType.MOVEMENT,
            rarity = 5,
            description = "地阶身法，穿梭虚空",
            stats = mapOf("speed" to 60),
            price = 1036800
        ),
        "mythicMovement" to ManualTemplate(
            id = "mythicMovement",
            name = "缩地成寸",
            type = ManualType.MOVEMENT,
            rarity = 6,
            description = "天阶身法，一步千里",
            stats = mapOf("speed" to 100),
            price = 4147200
        )
    )
    
    val mindManuals: Map<String, ManualTemplate> = mapOf(
        "mindCommonPhysicalAttack" to ManualTemplate(
            id = "mindCommonPhysicalAttack",
            name = "基础力心诀",
            type = ManualType.MIND,
            rarity = 1,
            description = "凡阶心法，修炼速度+10%，物理攻击+6",
            stats = mapOf("cultivationSpeedPercent" to 10, "physicalAttack" to 6),
            price = 3600
        ),
        "mindCommonMagicAttack" to ManualTemplate(
            id = "mindCommonMagicAttack",
            name = "基础法心诀",
            type = ManualType.MIND,
            rarity = 1,
            description = "凡阶心法，修炼速度+10%，法术攻击+6",
            stats = mapOf("cultivationSpeedPercent" to 10, "magicAttack" to 6),
            price = 3600
        ),
        "mindCommonPhysicalDefense" to ManualTemplate(
            id = "mindCommonPhysicalDefense",
            name = "基础盾心诀",
            type = ManualType.MIND,
            rarity = 1,
            description = "凡阶心法，修炼速度+10%，物理防御+6",
            stats = mapOf("cultivationSpeedPercent" to 10, "physicalDefense" to 6),
            price = 3600
        ),
        "mindCommonMagicDefense" to ManualTemplate(
            id = "mindCommonMagicDefense",
            name = "基础护心诀",
            type = ManualType.MIND,
            rarity = 1,
            description = "凡阶心法，修炼速度+10%，法术防御+6",
            stats = mapOf("cultivationSpeedPercent" to 10, "magicDefense" to 6),
            price = 3600
        ),
        "mindCommonHp" to ManualTemplate(
            id = "mindCommonHp",
            name = "基础生心诀",
            type = ManualType.MIND,
            rarity = 1,
            description = "凡阶心法，修炼速度+10%，生命+25",
            stats = mapOf("cultivationSpeedPercent" to 10, "hp" to 25),
            price = 3600
        ),
        "mindCommonMp" to ManualTemplate(
            id = "mindCommonMp",
            name = "基础灵心诀",
            type = ManualType.MIND,
            rarity = 1,
            description = "凡阶心法，修炼速度+10%，灵力+20",
            stats = mapOf("cultivationSpeedPercent" to 10, "mp" to 20),
            price = 3600
        ),
        "mindUncommonDualAttack" to ManualTemplate(
            id = "mindUncommonDualAttack",
            name = "灵心双攻诀",
            type = ManualType.MIND,
            rarity = 2,
            description = "灵阶心法，修炼速度+20%，双攻+10",
            stats = mapOf("cultivationSpeedPercent" to 20, "physicalAttack" to 10, "magicAttack" to 10),
            price = 16200
        ),
        "mindUncommonDualDefense" to ManualTemplate(
            id = "mindUncommonDualDefense",
            name = "灵心双防诀",
            type = ManualType.MIND,
            rarity = 2,
            description = "灵阶心法，修炼速度+20%，双防+10",
            stats = mapOf("cultivationSpeedPercent" to 20, "physicalDefense" to 10, "magicDefense" to 10),
            price = 16200
        ),
        "mindUncommonVitality" to ManualTemplate(
            id = "mindUncommonVitality",
            name = "灵心生命诀",
            type = ManualType.MIND,
            rarity = 2,
            description = "灵阶心法，修炼速度+20%，生命+50，灵力+40",
            stats = mapOf("cultivationSpeedPercent" to 20, "hp" to 50, "mp" to 40),
            price = 16200
        ),
        "mindUncommonAttackCrit" to ManualTemplate(
            id = "mindUncommonAttackCrit",
            name = "灵心暴击诀",
            type = ManualType.MIND,
            rarity = 2,
            description = "灵阶心法，修炼速度+20%，物攻+10，暴击+6",
            stats = mapOf("cultivationSpeedPercent" to 20, "physicalAttack" to 10, "critRate" to 6),
            price = 16200
        ),
        "mindUncommonDefenseHp" to ManualTemplate(
            id = "mindUncommonDefenseHp",
            name = "灵心铁壁诀",
            type = ManualType.MIND,
            rarity = 2,
            description = "灵阶心法，修炼速度+20%，物防+10，生命+40",
            stats = mapOf("cultivationSpeedPercent" to 20, "physicalDefense" to 10, "hp" to 40),
            price = 16200
        ),
        "mindUncommonSpeedMp" to ManualTemplate(
            id = "mindUncommonSpeedMp",
            name = "灵心疾风诀",
            type = ManualType.MIND,
            rarity = 2,
            description = "灵阶心法，修炼速度+20%，速度+3，灵力+40",
            stats = mapOf("cultivationSpeedPercent" to 20, "speed" to 3, "mp" to 40),
            price = 16200
        ),
        "mindRareTripleAttack" to ManualTemplate(
            id = "mindRareTripleAttack",
            name = "玄心三攻典",
            type = ManualType.MIND,
            rarity = 3,
            description = "宝阶心法，修炼速度+35%，三攻属性提升",
            stats = mapOf("cultivationSpeedPercent" to 35, "physicalAttack" to 15, "magicAttack" to 15, "critRate" to 6),
            price = 64800
        ),
        "mindRareTripleDefense" to ManualTemplate(
            id = "mindRareTripleDefense",
            name = "玄心三防典",
            type = ManualType.MIND,
            rarity = 3,
            description = "宝阶心法，修炼速度+35%，三防属性提升",
            stats = mapOf("cultivationSpeedPercent" to 35, "physicalDefense" to 15, "magicDefense" to 15, "hp" to 80),
            price = 64800
        ),
        "mindRareBalanced" to ManualTemplate(
            id = "mindRareBalanced",
            name = "玄心平衡典",
            type = ManualType.MIND,
            rarity = 3,
            description = "宝阶心法，修炼速度+35%，攻防平衡",
            stats = mapOf("cultivationSpeedPercent" to 35, "physicalAttack" to 10, "physicalDefense" to 10, "hp" to 60),
            price = 64800
        ),
        "mindRareMagicCrit" to ManualTemplate(
            id = "mindRareMagicCrit",
            name = "玄心法爆典",
            type = ManualType.MIND,
            rarity = 3,
            description = "宝阶心法，修炼速度+35%，法攻暴击",
            stats = mapOf("cultivationSpeedPercent" to 35, "magicAttack" to 15, "critRate" to 6, "speed" to 4),
            price = 64800
        ),
        "mindRareTank" to ManualTemplate(
            id = "mindRareTank",
            name = "玄心不屈典",
            type = ManualType.MIND,
            rarity = 3,
            description = "宝阶心法，修炼速度+35%，坦克属性",
            stats = mapOf("cultivationSpeedPercent" to 35, "hp" to 100, "mp" to 80, "magicDefense" to 12),
            price = 64800
        ),
        "mindRareSpeed" to ManualTemplate(
            id = "mindRareSpeed",
            name = "玄心迅捷典",
            type = ManualType.MIND,
            rarity = 3,
            description = "宝阶心法，修炼速度+35%，速度属性",
            stats = mapOf("cultivationSpeedPercent" to 35, "speed" to 5, "physicalAttack" to 12, "mp" to 50),
            price = 64800
        ),
        "mindEpicFullAttack" to ManualTemplate(
            id = "mindEpicFullAttack",
            name = "太初狂攻心法",
            type = ManualType.MIND,
            rarity = 4,
            description = "玄阶心法，修炼速度+55%，全攻属性",
            stats = mapOf("cultivationSpeedPercent" to 55, "physicalAttack" to 22, "magicAttack" to 22, "critRate" to 8, "speed" to 5),
            price = 259200
        ),
        "mindEpicFullDefense" to ManualTemplate(
            id = "mindEpicFullDefense",
            name = "太初铁壁心法",
            type = ManualType.MIND,
            rarity = 4,
            description = "玄阶心法，修炼速度+55%，全防属性",
            stats = mapOf("cultivationSpeedPercent" to 55, "physicalDefense" to 22, "magicDefense" to 22, "hp" to 120, "mp" to 100),
            price = 259200
        ),
        "mindEpicWarrior" to ManualTemplate(
            id = "mindEpicWarrior",
            name = "太初战士心法",
            type = ManualType.MIND,
            rarity = 4,
            description = "玄阶心法，修炼速度+55%，战士属性",
            stats = mapOf("cultivationSpeedPercent" to 55, "physicalAttack" to 20, "physicalDefense" to 18, "hp" to 100, "critRate" to 6),
            price = 259200
        ),
        "mindEpicMage" to ManualTemplate(
            id = "mindEpicMage",
            name = "太初法师心法",
            type = ManualType.MIND,
            rarity = 4,
            description = "玄阶心法，修炼速度+55%，法师属性",
            stats = mapOf("cultivationSpeedPercent" to 55, "magicAttack" to 20, "magicDefense" to 18, "mp" to 120, "critRate" to 6),
            price = 259200
        ),
        "mindEpicAssassin" to ManualTemplate(
            id = "mindEpicAssassin",
            name = "太初刺客心法",
            type = ManualType.MIND,
            rarity = 4,
            description = "玄阶心法，修炼速度+55%，刺客属性",
            stats = mapOf("cultivationSpeedPercent" to 55, "physicalAttack" to 18, "critRate" to 10, "speed" to 8, "mp" to 80),
            price = 259200
        ),
        "mindEpicTank" to ManualTemplate(
            id = "mindEpicTank",
            name = "太初肉盾心法",
            type = ManualType.MIND,
            rarity = 4,
            description = "玄阶心法，修炼速度+55%，肉盾属性",
            stats = mapOf("cultivationSpeedPercent" to 55, "hp" to 180, "physicalDefense" to 18, "magicDefense" to 16, "speed" to 4),
            price = 259200
        ),
        "mindLegendaryDestroyer" to ManualTemplate(
            id = "mindLegendaryDestroyer",
            name = "天神破灭心法",
            type = ManualType.MIND,
            rarity = 5,
            description = "地阶心法，修炼速度+80%，破灭属性",
            stats = mapOf("cultivationSpeedPercent" to 80, "physicalAttack" to 32, "magicAttack" to 28, "critRate" to 12, "speed" to 8, "hp" to 100),
            price = 1036800
        ),
        "mindLegendaryFortress" to ManualTemplate(
            id = "mindLegendaryFortress",
            name = "天神堡垒心法",
            type = ManualType.MIND,
            rarity = 5,
            description = "地阶心法，修炼速度+80%，堡垒属性",
            stats = mapOf("cultivationSpeedPercent" to 80, "physicalDefense" to 32, "magicDefense" to 30, "hp" to 200, "mp" to 150, "critRate" to 4),
            price = 1036800
        ),
        "mindLegendaryBerserker" to ManualTemplate(
            id = "mindLegendaryBerserker",
            name = "天神狂战心法",
            type = ManualType.MIND,
            rarity = 5,
            description = "地阶心法，修炼速度+80%，狂战属性",
            stats = mapOf("cultivationSpeedPercent" to 80, "physicalAttack" to 35, "hp" to 180, "critRate" to 14, "speed" to 7, "physicalDefense" to 12),
            price = 1036800
        ),
        "mindLegendaryArchmage" to ManualTemplate(
            id = "mindLegendaryArchmage",
            name = "天神大法师心法",
            type = ManualType.MIND,
            rarity = 5,
            description = "地阶心法，修炼速度+80%，大法师属性",
            stats = mapOf("cultivationSpeedPercent" to 80, "magicAttack" to 35, "mp" to 200, "critRate" to 10, "magicDefense" to 15, "speed" to 6),
            price = 1036800
        ),
        "mindLegendaryShadow" to ManualTemplate(
            id = "mindLegendaryShadow",
            name = "天神暗影心法",
            type = ManualType.MIND,
            rarity = 5,
            description = "地阶心法，修炼速度+80%，暗影属性",
            stats = mapOf("cultivationSpeedPercent" to 80, "speed" to 12, "physicalAttack" to 25, "critRate" to 14, "mp" to 120, "magicDefense" to 10),
            price = 1036800
        ),
        "mindLegendaryImmortal" to ManualTemplate(
            id = "mindLegendaryImmortal",
            name = "天神不朽心法",
            type = ManualType.MIND,
            rarity = 5,
            description = "地阶心法，修炼速度+80%，不朽属性",
            stats = mapOf("cultivationSpeedPercent" to 80, "hp" to 300, "mp" to 200, "physicalDefense" to 20, "magicDefense" to 18, "speed" to 5),
            price = 1036800
        ),
        "mindMythicPhysicalFocus" to ManualTemplate(
            id = "mindMythicPhysicalFocus",
            name = "混沌极武录",
            type = ManualType.MIND,
            rarity = 6,
            description = "天阶心法，修炼速度+150%，极武属性",
            stats = mapOf("cultivationSpeedPercent" to 150, "physicalAttack" to 70, "magicAttack" to 30, "critRate" to 15, "speed" to 12, "hp" to 200, "mp" to 150),
            price = 4147200
        ),
        "mindMythicMagicFocus" to ManualTemplate(
            id = "mindMythicMagicFocus",
            name = "混沌极法录",
            type = ManualType.MIND,
            rarity = 6,
            description = "天阶心法，修炼速度+150%，极法属性",
            stats = mapOf("cultivationSpeedPercent" to 150, "magicAttack" to 70, "physicalAttack" to 30, "critRate" to 15, "speed" to 12, "mp" to 250, "hp" to 150),
            price = 4147200
        ),
        "mindMythicDefenseFocus" to ManualTemplate(
            id = "mindMythicDefenseFocus",
            name = "混沌极盾录",
            type = ManualType.MIND,
            rarity = 6,
            description = "天阶心法，修炼速度+150%，极盾属性",
            stats = mapOf("cultivationSpeedPercent" to 150, "physicalDefense" to 60, "magicDefense" to 60, "hp" to 400, "mp" to 250, "critRate" to 8, "speed" to 8),
            price = 4147200
        ),
        "mindMythicHpFocus" to ManualTemplate(
            id = "mindMythicHpFocus",
            name = "混沌极生录",
            type = ManualType.MIND,
            rarity = 6,
            description = "天阶心法，修炼速度+150%，极生属性",
            stats = mapOf("cultivationSpeedPercent" to 150, "hp" to 550, "mp" to 350, "physicalDefense" to 35, "magicDefense" to 35, "critRate" to 10, "speed" to 10),
            price = 4147200
        ),
        "mindMythicCritFocus" to ManualTemplate(
            id = "mindMythicCritFocus",
            name = "混沌极暴击录",
            type = ManualType.MIND,
            rarity = 6,
            description = "天阶心法，修炼速度+150%，极暴击属性",
            stats = mapOf("cultivationSpeedPercent" to 150, "critRate" to 25, "physicalAttack" to 45, "magicAttack" to 45, "speed" to 18, "hp" to 180, "mp" to 150),
            price = 4147200
        ),
        "mindMythicSpeedFocus" to ManualTemplate(
            id = "mindMythicSpeedFocus",
            name = "混沌极速录",
            type = ManualType.MIND,
            rarity = 6,
            description = "天阶心法，修炼速度+150%，极速属性",
            stats = mapOf("cultivationSpeedPercent" to 150, "speed" to 25, "physicalAttack" to 40, "magicAttack" to 40, "critRate" to 18, "hp" to 150, "mp" to 180),
            price = 4147200
        )
    )
    
    val allManuals: Map<String, ManualTemplate> by lazy {
        attackManuals + defenseManuals + supportManuals + movementManuals + mindManuals
    }
    
    fun getById(id: String): ManualTemplate? = allManuals[id]

    fun getByName(name: String): ManualTemplate? = allManuals.values.find { it.name == name }

    fun getByType(type: ManualType): List<ManualTemplate> =
        allManuals.values.filter { it.type == type }
    
    fun getByRarity(rarity: Int): List<ManualTemplate> =
        allManuals.values.filter { it.rarity == rarity }
    
    fun createFromTemplate(template: ManualTemplate): Manual {
        return Manual(
            id = java.util.UUID.randomUUID().toString(),
            name = template.name,
            type = template.type,
            rarity = template.rarity,
            description = template.description,
            stats = template.stats,
            skillName = template.skillName,
            skillDescription = template.skillDescription,
            skillType = template.skillType,
            skillDamageType = template.skillDamageType,
            skillHits = template.skillHits,
            skillDamageMultiplier = template.skillDamageMultiplier,
            skillCooldown = template.skillCooldown,
            skillMpCost = template.skillMpCost,
            skillHealPercent = template.skillHealPercent,
            skillHealType = template.skillHealType,
            minRealm = template.minRealm
        )
    }
    
    fun generateRandom(minRarity: Int = 1, maxRarity: Int = 6, type: ManualType? = null): Manual {
        val rarity = generateRarity(minRarity, maxRarity)
        val templates = if (type != null) {
            getByType(type).filter { it.rarity == rarity }
        } else {
            getByRarity(rarity)
        }
        val template = templates.random()
        return createFromTemplate(template)
    }
    
    private fun generateRarity(minRarity: Int, maxRarity: Int): Int {
        val random = Random.nextDouble()
        return when {
            random < 0.5 -> minRarity.coerceAtMost(maxRarity)
            random < 0.75 -> (minRarity + 1).coerceAtMost(maxRarity)
            random < 0.9 -> (minRarity + 2).coerceAtMost(maxRarity)
            random < 0.97 -> (minRarity + 3).coerceAtMost(maxRarity)
            else -> maxRarity
        }
    }
}
