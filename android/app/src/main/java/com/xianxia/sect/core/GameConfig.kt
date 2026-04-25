package com.xianxia.sect.core

import com.xianxia.sect.BuildConfig
import com.xianxia.sect.core.util.GameRandom

enum class SkillType {
    ATTACK, SUPPORT;

    val displayName: String get() = when (this) {
        ATTACK -> "攻击"
        SUPPORT -> "辅助"
    }
}

enum class DamageType {
    PHYSICAL, MAGIC
}

enum class BuffType {
    HP_BOOST, MP_BOOST, SPEED_BOOST,
    PHYSICAL_ATTACK_BOOST, MAGIC_ATTACK_BOOST, PHYSICAL_DEFENSE_BOOST, MAGIC_DEFENSE_BOOST,
    CRIT_RATE_BOOST,
    PHYSICAL_ATTACK_REDUCE, MAGIC_ATTACK_REDUCE, PHYSICAL_DEFENSE_REDUCE, MAGIC_DEFENSE_REDUCE,
    SPEED_REDUCE, CRIT_RATE_REDUCE,
    POISON, BURN,
    STUN, FREEZE, SILENCE, TAUNT;

    val displayName: String get() = when (this) {
        HP_BOOST -> "生命加成"
        MP_BOOST -> "灵力加成"
        SPEED_BOOST -> "速度加成"
        PHYSICAL_ATTACK_BOOST -> "物攻加成"
        MAGIC_ATTACK_BOOST -> "法攻加成"
        PHYSICAL_DEFENSE_BOOST -> "物防加成"
        MAGIC_DEFENSE_BOOST -> "法防加成"
        CRIT_RATE_BOOST -> "暴击加成"
        PHYSICAL_ATTACK_REDUCE -> "物攻降低"
        MAGIC_ATTACK_REDUCE -> "法攻降低"
        PHYSICAL_DEFENSE_REDUCE -> "物防降低"
        MAGIC_DEFENSE_REDUCE -> "法防降低"
        SPEED_REDUCE -> "速度降低"
        CRIT_RATE_REDUCE -> "暴击降低"
        POISON -> "中毒"
        BURN -> "灼烧"
        STUN -> "眩晕"
        FREEZE -> "冰冻"
        SILENCE -> "沉默"
        TAUNT -> "嘲讽"
    }

    val isDebuff: Boolean get() = this in setOf(
        PHYSICAL_ATTACK_REDUCE, MAGIC_ATTACK_REDUCE, PHYSICAL_DEFENSE_REDUCE, MAGIC_DEFENSE_REDUCE,
        SPEED_REDUCE, CRIT_RATE_REDUCE, POISON, BURN, STUN, FREEZE, SILENCE, TAUNT
    )
}

enum class CombatantSide {
    ATTACKER, DEFENDER
}

enum class HealType {
    HP, MP;

    val displayName: String get() = when (this) {
        HP -> "生命值"
        MP -> "灵力"
    }
}

object GameConfig {
    
    object Game {
        const val NAME = "模拟宗门"
        const val VERSION = BuildConfig.VERSION_NAME
        const val AUTO_SAVE_INTERVAL_SECONDS = 60L
        const val AUTO_SAVE_DEBOUNCE_MS = 30_000L
        const val MAX_SAVE_SLOTS = 5
    }
    
    object Disciple {
        const val MAX_DISCIPLES = 1000
        const val RECRUIT_COST = 1000L
        const val MIN_LOYALTY = 0
        const val MAX_LOYALTY = 100
        const val MIN_AGE = 5
        const val MAX_AGE = 100
        const val PROTECTION_MONTHS = 12
    }
    
    object Time {
        const val TICK_INTERVAL = 200L
        const val TICKS_PER_SECOND = 5
        const val SECONDS_PER_REAL_MONTH = 10
        const val DAYS_PER_MONTH = 30
        const val MONTHS_PER_YEAR = 12
        const val MAX_EXPLORE_TIME = 12
        const val HIGH_FREQUENCY_UPDATE_INTERVAL = 200L
        const val LOW_FREQUENCY_UPDATE_INTERVAL = 1000L
    }
    
    object Cultivation {
        const val BASE_SPEED = 8.0
        const val REALM_SPEED_BONUS_THRESHOLD = 3
        const val REALM_SPEED_BONUS = 1.5
    }
    
    object Rarity {
        val CONFIGS = mapOf(
            1 to RarityConfig(1, "凡品", "#95a5a6", 1.0, 20000, 16000, 1000),
            2 to RarityConfig(2, "灵品", "#27ae60", 1.3, 70000, 56000, 3500),
            3 to RarityConfig(3, "宝品", "#3498db", 1.6, 210000, 168000, 10500),
            4 to RarityConfig(4, "玄品", "#9b59b6", 2.0, 700000, 560000, 35000),
            5 to RarityConfig(5, "地品", "#f39c12", 2.5, 1400000, 1120000, 70000),
            6 to RarityConfig(6, "天品", "#e74c3c", 3.2, 7000000, 5600000, 350000)
        )
        
        fun get(rarity: Int): RarityConfig = CONFIGS[rarity] ?: CONFIGS.getValue(1)
        
        const val PRICE_MULTIPLIER = 0.9
        const val SELL_PRICE_MULTIPLIER = 0.8
        
        fun calculateSellPrice(basePrice: Int, quantity: Int): Long {
            return (basePrice.toLong() * quantity * SELL_PRICE_MULTIPLIER).toLong()
        }
        
        fun getColor(rarity: Int): String = get(rarity).color
        
        fun getName(rarity: Int): String = get(rarity).name
    }
    
    object Realm {
        val CONFIGS = mapOf(
            9 to RealmConfig(9, "炼气", 225, 10, 0.80, 1.0, 80, 9),
            8 to RealmConfig(8, "筑基", 450, 30, 0.60, 2.5, 120, 9),
            7 to RealmConfig(7, "金丹", 900, 50, 0.46, 6.5, 200, 9),
            6 to RealmConfig(6, "元婴", 1800, 80, 0.32, 17.0, 300, 9),
            5 to RealmConfig(5, "化神", 3600, 110, 0.24, 45.0, 500, 9, 60),
            4 to RealmConfig(4, "炼虚", 16000, 180, 0.12, 110.0, 800, 9, 100),
            3 to RealmConfig(3, "合体", 32000, 220, 0.06, 260.0, 1500, 9, 160),
            2 to RealmConfig(2, "大乘", 64000, 280, 0.03, 580.0, 3000, 9, 240),
            1 to RealmConfig(1, "渡劫", 128000, 360, 0.01, 1200.0, 5000, 9, 340),
            0 to RealmConfig(0, "仙人", 256000, 500, 0.0, 2500.0, 9999, 9, 500)
        )

        val BREAKTHROUGH_CHANCES: Map<Int, Map<Int, Double>> = mapOf(
            9 to mapOf(1 to 1.00, 2 to 0.90, 3 to 0.80, 4 to 0.65, 5 to 0.45),
            8 to mapOf(1 to 1.00, 2 to 0.85, 3 to 0.75, 4 to 0.50, 5 to 0.32),
            7 to mapOf(1 to 0.95, 2 to 0.70, 3 to 0.55, 4 to 0.25, 5 to 0.18),
            6 to mapOf(1 to 0.80, 2 to 0.65, 3 to 0.42, 4 to 0.18, 5 to 0.08),
            5 to mapOf(1 to 0.65, 2 to 0.35, 3 to 0.25, 4 to 0.08, 5 to 0.00),
            4 to mapOf(1 to 0.38, 2 to 0.22, 3 to 0.08, 4 to 0.03, 5 to 0.00),
            3 to mapOf(1 to 0.22, 2 to 0.12, 3 to 0.02, 4 to 0.00, 5 to 0.00),
            2 to mapOf(1 to 0.12, 2 to 0.05, 3 to 0.00, 4 to 0.00, 5 to 0.00),
            1 to mapOf(1 to 0.06, 2 to 0.03, 3 to 0.00, 4 to 0.00, 5 to 0.00),
            0 to mapOf(1 to 0.03, 2 to 0.01, 3 to 0.00, 4 to 0.00, 5 to 0.00)
        )
        
        fun get(realm: Int): RealmConfig = CONFIGS[realm] ?: CONFIGS.getValue(9)
        
        fun getName(realm: Int): String = get(realm).name
        
        fun getCultivationBase(realm: Int): Int = get(realm).cultivationBase
        
        @Deprecated("使用 getBreakthroughChance(realm, rootCount) 代替，突破概率现在依赖灵根数量")
        fun getBreakthroughChance(realm: Int): Double = get(realm).breakthroughChance

        fun getBreakthroughChance(realm: Int, rootCount: Int): Double {
            val clampedRootCount = rootCount.coerceIn(1, 5)
            return BREAKTHROUGH_CHANCES[realm]?.get(clampedRootCount) ?: 0.0
        }
        
        fun getSoulPowerRequirement(realm: Int): Int = get(realm).soulPowerRequirement
        
        fun getMaxRarity(realm: Int): Int = when (realm) {
            9, 8 -> 1
            7 -> 2
            6 -> 3
            5 -> 4
            4, 3 -> 5
            2, 1, 0 -> 6
            else -> 1
        }
        
        fun getMinRealmForRarity(rarity: Int): Int = when (rarity) {
            1 -> 9
            2 -> 7
            3 -> 6
            4 -> 5
            5 -> 4
            6 -> 2
            else -> 9
        }

        fun meetsRealmRequirement(discipleRealm: Int, minRealm: Int): Boolean = discipleRealm <= minRealm
    }
    
    object SpiritRoot {
        val ELEMENTS = listOf("金", "木", "水", "火", "土")
        
        val TYPES = mapOf(
            "metal" to SpiritRootConfig("metal", "金", "#f1c40f", 1.0),
            "wood" to SpiritRootConfig("wood", "木", "#27ae60", 1.0),
            "water" to SpiritRootConfig("water", "水", "#3498db", 1.0),
            "fire" to SpiritRootConfig("fire", "火", "#e74c3c", 1.0),
            "earth" to SpiritRootConfig("earth", "土", "#95a5a6", 1.0)
        )
        
        // 灵根数量权重配置（增量值，非累积值）
        val COUNT_WEIGHTS = mapOf(
            1 to 0.05,
            2 to 0.20,
            3 to 0.30,
            4 to 0.30,
            5 to 0.15
        )
        
        fun get(type: String): SpiritRootConfig = TYPES[type] ?: TYPES.getValue("metal")
        
        fun getAll(): List<SpiritRootConfig> = TYPES.values.toList()
        
        fun getElementName(type: String): String = get(type).name
        
        fun generateRandomSpiritRootCount(): Int {
            val rand = GameRandom.nextDouble()
            var cumulative = 0.0
            for ((count, weight) in COUNT_WEIGHTS.toSortedMap()) {
                cumulative += weight
                if (rand <= cumulative) return count
            }
            return 5
        }
        
    }
    
    object Beast {
        val TYPES = listOf(
            BeastTypeConfig("虎妖", "狂暴", 1.3, 1.4, 0.7, 1.0, 1.1, "metal",
                listOf(BeastSkillConfig("猛虎下山", 1.8, 3, 0, SkillType.ATTACK, DamageType.PHYSICAL),
                       BeastSkillConfig("咆哮", 0.0, 5, 0, SkillType.SUPPORT, DamageType.PHYSICAL, buffType = BuffType.PHYSICAL_ATTACK_BOOST, buffValue = 0.2, buffDuration = 3, targetScope = "team"))),
            BeastTypeConfig("狼妖", "迅捷", 0.6, 1.2, 0.6, 1.5, 1.0, "wood",
                listOf(BeastSkillConfig("狼群撕咬", 1.5, 2, 0, SkillType.ATTACK, DamageType.PHYSICAL, hits = 2))),
            BeastTypeConfig("蛇妖", "剧毒", 0.7, 1.5, 0.5, 1.1, 1.2, "water",
                listOf(BeastSkillConfig("毒牙", 1.2, 2, 0, SkillType.ATTACK, DamageType.PHYSICAL, buffType = BuffType.POISON, buffValue = 0.05, buffDuration = 3))),
            BeastTypeConfig("熊妖", "铁甲", 1.5, 0.5, 1.4, 0.5, 1.1, "earth",
                listOf(BeastSkillConfig("震地", 1.5, 4, 0, SkillType.ATTACK, DamageType.PHYSICAL, isAoe = true),
                       BeastSkillConfig("铁壁", 0.0, 5, 0, SkillType.SUPPORT, DamageType.PHYSICAL, buffType = BuffType.PHYSICAL_DEFENSE_BOOST, buffValue = 0.4, buffDuration = 3, targetScope = "self"))),
            BeastTypeConfig("鹰妖", "神风", 0.5, 1.3, 0.5, 1.6, 1.3, "metal",
                listOf(BeastSkillConfig("俯冲", 2.0, 3, 0, SkillType.ATTACK, DamageType.PHYSICAL))),
            BeastTypeConfig("狐妖", "幻魅", 0.7, 1.0, 0.7, 1.4, 1.4, "fire",
                listOf(BeastSkillConfig("妖术", 1.5, 3, 0, SkillType.ATTACK, DamageType.MAGIC, buffType = BuffType.SILENCE, buffValue = 1.0, buffDuration = 1))),
            BeastTypeConfig("龙妖", "远古", 1.2, 1.3, 1.1, 1.0, 1.5, "fire",
                listOf(BeastSkillConfig("龙息", 1.8, 4, 0, SkillType.ATTACK, DamageType.MAGIC, isAoe = true),
                       BeastSkillConfig("龙威", 0.0, 6, 0, SkillType.SUPPORT, DamageType.MAGIC, buffType = BuffType.PHYSICAL_ATTACK_BOOST, buffValue = 0.25, buffDuration = 3, targetScope = "team"))),
            BeastTypeConfig("龟妖", "玄甲", 1.6, 0.4, 1.5, 0.4, 1.0, "water",
                listOf(BeastSkillConfig("缩壳", 0.0, 4, 0, SkillType.SUPPORT, DamageType.PHYSICAL, buffType = BuffType.PHYSICAL_DEFENSE_BOOST, buffValue = 0.5, buffDuration = 2, targetScope = "self"),
                       BeastSkillConfig("水盾", 0.0, 5, 0, SkillType.SUPPORT, DamageType.MAGIC, buffType = BuffType.MAGIC_DEFENSE_BOOST, buffValue = 0.3, buffDuration = 3, targetScope = "team")))
        )

        fun getType(index: Int): BeastTypeConfig = TYPES.getOrElse(index) { TYPES[0] }
    }
    
    object Dungeons {
        val CONFIGS = mapOf(
            "tigerForest" to DungeonConfig(
                id = "tigerForest",
                name = "虎啸岭",
                description = "虎妖聚集之地，危机四伏",
                beastType = "虎妖",
                rewards = DungeonConfigRewards(
                    spiritStones = listOf(100, 300),
                    materials = listOf("虎皮", "虎骨"),
                    equipmentChance = 0.07,
                    manualChance = 0.07
                )
            ),
            "wolfValley" to DungeonConfig(
                id = "wolfValley",
                name = "狼牙谷",
                description = "狼妖出没的山谷，迅捷异常",
                beastType = "狼妖",
                rewards = DungeonConfigRewards(
                    spiritStones = listOf(100, 300),
                    materials = listOf("狼皮", "狼骨"),
                    equipmentChance = 0.07,
                    manualChance = 0.07
                )
            ),
            "snakeCave" to DungeonConfig(
                id = "snakeCave",
                name = "幽冥蛇窟",
                description = "剧毒蛇妖的巢穴，阴森恐怖",
                beastType = "蛇妖",
                rewards = DungeonConfigRewards(
                    spiritStones = listOf(100, 300),
                    materials = listOf("蛇皮", "蛇骨"),
                    equipmentChance = 0.07,
                    manualChance = 0.07
                )
            ),
            "bearMountain" to DungeonConfig(
                id = "bearMountain",
                name = "铁甲熊岭",
                description = "熊妖盘踞的山岭，防御惊人",
                beastType = "熊妖",
                rewards = DungeonConfigRewards(
                    spiritStones = listOf(100, 300),
                    materials = listOf("熊皮", "熊骨"),
                    equipmentChance = 0.07,
                    manualChance = 0.07
                )
            ),
            "eaglePeak" to DungeonConfig(
                id = "eaglePeak",
                name = "神风鹰巢",
                description = "鹰妖栖息的高峰，速度极快",
                beastType = "鹰妖",
                rewards = DungeonConfigRewards(
                    spiritStones = listOf(100, 300),
                    materials = listOf("鹰羽", "鹰骨"),
                    equipmentChance = 0.07,
                    manualChance = 0.07
                )
            ),
            "foxHollow" to DungeonConfig(
                id = "foxHollow",
                name = "幻魅狐谷",
                description = "狐妖出没的山谷，幻术迷人",
                beastType = "狐妖",
                rewards = DungeonConfigRewards(
                    spiritStones = listOf(100, 300),
                    materials = listOf("狐皮", "狐骨"),
                    equipmentChance = 0.07,
                    manualChance = 0.07
                )
            ),
            "dragonAbyss" to DungeonConfig(
                id = "dragonAbyss",
                name = "远古龙渊",
                description = "龙妖沉睡的深渊，力量惊人",
                beastType = "龙妖",
                rewards = DungeonConfigRewards(
                    spiritStones = listOf(100, 300),
                    materials = listOf("龙鳞", "龙骨"),
                    equipmentChance = 0.07,
                    manualChance = 0.07
                )
            ),
            "turtleIsland" to DungeonConfig(
                id = "turtleIsland",
                name = "玄甲龟岛",
                description = "龟妖栖息的岛屿，防御无双",
                beastType = "龟妖",
                rewards = DungeonConfigRewards(
                    spiritStones = listOf(100, 300),
                    materials = listOf("龟甲", "龟骨"),
                    equipmentChance = 0.07,
                    manualChance = 0.07
                )
            )
        )
        
        fun get(id: String): DungeonConfig? = CONFIGS[id]
        
        fun getAll(): List<DungeonConfig> = CONFIGS.values.toList()
    }
    
    object Starting {
        val RESOURCES = StartingResources(
            spiritStones = 1000,
            reputation = 100,
            spiritHerbs = 50
        )
    }
    
    object PlayerProtection {
        const val PROTECTION_YEARS = 100
    }
    
    object Performance {
        const val MAX_TICK_SAMPLES = 100
        const val MAX_BATCH_SAMPLES = 100
        const val BATCH_THRESHOLD = 50
        const val UPDATE_INTERVAL_MS = 200L
        const val HIGH_FREQUENCY_INTERVAL_MS = 200L
        const val LOW_FREQUENCY_INTERVAL_MS = 1000L
    }
    
    object Logs {
        const val MAX_BATTLE_LOGS = 100
        const val MAX_EVENT_LOGS = 200
        const val MAX_MONTHLY_EVENT_LOGS = 50
        const val MAX_EXPLORATION_LOGS = 100
    }
    
    object Battle {
        const val MAX_TEAM_SIZE = 7
        const val MIN_BEAST_COUNT = 3
        const val MAX_BEAST_COUNT = 11
        const val MAX_TURNS = 25
        const val CRIT_MULTIPLIER: Double = 1.5
        const val MAX_DODGE_CHANCE: Double = 0.5
        const val MAX_SKILL_DODGE_CHANCE: Double = 0.3
        const val DODGE_PER_SPEED_DIFF: Double = 0.005
        const val MAX_BATTLE_DURATION_MS = 5000L
        const val BATTLE_TIMEOUT_WARNING_MS = 3000L
        const val DEFENSE_CONSTANT: Double = 500.0
        const val DAMAGE_VARIANCE_PERCENT: Double = 20.0
        const val MIN_DAMAGE: Int = 1

        object Element {
            const val ADVANTAGE_MULTIPLIER: Double = 1.3
            const val DISADVANTAGE_MULTIPLIER: Double = 0.8
            val ADVANTAGES = mapOf(
                "metal" to "wood",
                "wood" to "earth",
                "earth" to "water",
                "water" to "fire",
                "fire" to "metal"
            )
        }

        object RealmGap {
            const val DAMAGE_BONUS_PER_REALM: Double = 0.15
            const val DAMAGE_PENALTY_PER_REALM: Double = 0.12
            const val MAX_REALM_GAP: Int = 5
            const val MIN_DAMAGE_RATIO: Double = 0.1
            const val MAX_DAMAGE_RATIO: Double = 3.0
        }
    }
    
    /**
     * 宗门政策配置
     * 包含所有政策的消耗金额、基础效果和名称
     */
    object PolicyConfig {
        // 政策消耗金额（灵石/月）
        const val SPIRIT_MINE_BOOST_COST = 0L           // 灵矿增产无灵石消耗
        const val ENHANCED_SECURITY_COST = 3000L
        const val ALCHEMY_INCENTIVE_COST = 3000L
        const val FORGE_INCENTIVE_COST = 3000L
        const val HERB_CULTIVATION_COST = 3000L
        const val CULTIVATION_SUBSIDY_COST = 4000L
        const val MANUAL_RESEARCH_COST = 4000L
        
        // 政策名称
        const val SPIRIT_MINE_BOOST_NAME = "灵矿增产"
        const val ENHANCED_SECURITY_NAME = "增强治安"
        const val ALCHEMY_INCENTIVE_NAME = "丹道激励"
        const val FORGE_INCENTIVE_NAME = "锻造激励"
        const val HERB_CULTIVATION_NAME = "灵药培育"
        const val CULTIVATION_SUBSIDY_NAME = "修行津贴"
        const val MANUAL_RESEARCH_NAME = "功法研习"
        
        // 政策基础效果
        const val SPIRIT_MINE_BOOST_BASE_EFFECT = 0.20  // 灵石产出+20%
        const val ENHANCED_SECURITY_BASE_EFFECT = 0.20  // 抓捕率+20%
        const val ALCHEMY_INCENTIVE_BASE_EFFECT = 0.10  // 炼丹成功率+10%
        const val FORGE_INCENTIVE_BASE_EFFECT = 0.10    // 锻造成功率+10%
        const val HERB_CULTIVATION_BASE_EFFECT = 0.20   // 灵药生长速度+20%
        const val CULTIVATION_SUBSIDY_BASE_EFFECT = 0.15 // 修炼速度+15%
        const val MANUAL_RESEARCH_BASE_EFFECT = 0.20    // 功法修炼速度+20%
        
        // 副宗主智力加成基准值
        const val VICE_SECT_MASTER_INTELLIGENCE_BASE = 50
        // 每超过基准值5点智力，政策效果增加1%
        const val VICE_SECT_MASTER_INTELLIGENCE_STEP = 5
        const val VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP = 0.01
    }

    object LawEnforcementConfig {
        const val LOYALTY_THRESHOLD = 30
        const val MORALITY_THRESHOLD = 30
        const val PROB_PER_POINT = 0.03
        const val MAX_PROB = 0.90
        const val THEFT_MIN_RATIO = 0.01
        const val THEFT_MAX_RATIO = 0.05
        const val BASE_CAPTURE_RATE = 0.0
        const val INTELLIGENCE_BASE = 50
        const val ELDER_BONUS_PER_POINT = 0.01
        const val DISCIPLE_INTELLIGENCE_STEP = 5
        const val DISCIPLE_BONUS_PER_STEP = 0.01
        const val REFLECTION_YEARS = 10
        const val NEW_DISCIPLE_PROTECTION_MONTHS = 12  // 新弟子入门一年内不会偷盗和叛逃
    }

    data class RarityConfig(
        val level: Int,
        val name: String,
        val color: String,
        val multiplier: Double,
        val basePrice: Int,
        val pillBasePrice: Int = 0,
        val materialBasePrice: Int = 0
    )
    
    data class RealmConfig(
        val level: Int,
        val name: String,
        val cultivationBase: Int,
        val salary: Int,
        val breakthroughChance: Double,
        val multiplier: Double = 1.0,
        val maxAge: Int = 100,
        val maxLayers: Int = 10,
        val soulPowerRequirement: Int = 0
    )
    
    data class SpiritRootConfig(
        val type: String,
        val name: String,
        val color: String,
        val cultivationBonus: Double
    )
    
    data class StartingResources(
        val spiritStones: Int,
        val reputation: Int,
        val spiritHerbs: Int
    )
    
    data class DungeonConfig(
        val id: String,
        val name: String,
        val description: String,
        val beastType: String,
        val rewards: DungeonConfigRewards
    )
    
    data class DungeonConfigRewards(
        val spiritStones: List<Int>,
        val materials: List<String>,
        val equipmentChance: Double,
        val manualChance: Double
    )
    
    data class BeastTypeConfig(
        val name: String,
        val prefix: String,
        val hpMod: Double,
        val atkMod: Double,
        val defMod: Double,
        val speedMod: Double,
        val lootBonus: Double,
        val element: String = "metal",
        val skills: List<BeastSkillConfig> = emptyList()
    )

    data class BeastSkillConfig(
        val name: String,
        val damageMultiplier: Double,
        val cooldown: Int,
        val mpCost: Int,
        val skillType: SkillType,
        val damageType: DamageType,
        val hits: Int = 1,
        val isAoe: Boolean = false,
        val buffType: BuffType? = null,
        val buffValue: Double = 0.0,
        val buffDuration: Int = 0,
        val targetScope: String = "enemy"
    )
    
    object AI {
        const val MIN_DISCIPLES_FOR_ATTACK = 10
        const val POWER_RATIO_THRESHOLD = 0.8
        const val TEAM_SIZE = 10
        const val MAX_BATTLE_TURNS = 25
        
        object PowerWeights {
            const val REALM_BASE = 100.0
            const val EQUIPMENT_RARITY = 20.0
            const val EQUIPMENT_LEVEL = 5.0
            const val MANUAL_RARITY = 15.0
            const val MANUAL_LEVEL = 3.0
            const val MANUAL_MASTERY = 0.5
            const val TALENT_RARITY = 10.0
        }
    }
    
    object WorldMap {
        const val MAP_WIDTH = 6000
        const val MAP_HEIGHT = 5000
        const val SECT_RADIUS = 70
        const val MIN_DISTANCE = 120
        const val MAX_CONNECTION_DISTANCE = 700.0
        const val BORDER_PADDING = 120
        const val TARGET_SECT_COUNT = 80
        const val MAX_ATTEMPTS = 50000
        const val INITIAL_SECT_FAVOR = 50
        const val SAME_ALIGNMENT_BONUS = 10
        const val CONNECTION_DISTANCE_LIMIT = 1000.0
        const val TARGET_CONNECTIONS_PER_SECT = 3
        const val MAX_CONNECTIONS_PER_SECT = 5
        const val MIN_CONNECTIONS_PER_SECT = 2
        const val RELAXATION_ITERATIONS = 5
        const val RELAXATION_STRENGTH = 0.4
        const val K_NEAREST_NEIGHBORS = 6
        const val CROSSING_PENALTY = 80.0
        const val CLUSTER_MIN_COUNT = 5
        const val CLUSTER_MAX_COUNT = 9
        const val CLUSTER_MIN_RADIUS = 250.0
        const val CLUSTER_MAX_RADIUS = 700.0
        const val ISOLATED_SECT_MIN = 5
        const val ISOLATED_SECT_MAX = 10
        const val MIN_SECT_DISTANCE = 100.0
        const val PATH_WAYPOINT_MIN = 2
        const val PATH_WAYPOINT_MAX = 4
        const val PATH_CURVE_STRENGTH = 0.18
        const val CAVE_MIN_SECT_DISTANCE = 100.0
        const val CAVE_MIN_PATH_DISTANCE = 70.0
        const val CAVE_MIN_CAVE_DISTANCE = 70.0
    }
    
    object Diplomacy {
        const val MIN_ALLIANCE_FAVOR = 80
        const val ALLIANCE_DURATION_YEARS = 5
        const val MAX_ALLIANCE_SLOTS_DEFAULT = 3
        const val DIPLOMATIC_EVENT_CHANCE = 0.12
        const val FAVOR_DECAY_NO_GIFT_YEARS = 1
        const val FAVOR_DECAY_AMOUNT = 1
        const val FAVOR_DECAY_THRESHOLD = 80
        const val MIN_FAVOR = 0
        const val MAX_FAVOR = 100
        
        object AllianceScore {
            const val THRESHOLD = 80
            const val PROBABILITY_DIVISOR = 200.0
            const val MAX_AI_ALLIANCES = 2
        }
        
        object BreakPenalty {
            const val SPIRIT_STONE_PENALTY_RATIO = 0.1
        }
    }
}
