package com.xianxia.sect.core

object GameConfig {
    
    object Game {
        const val NAME = "模拟宗门"
        const val VERSION = "1.5.30"
        const val AUTO_SAVE_INTERVAL_SECONDS = 60L
        const val AUTO_SAVE_DEBOUNCE_MS = 30_000L
        const val MAX_SAVE_SLOTS = 5
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
        const val BREAKTHROUGH_LAYER_PENALTY = 0.9
        const val SPIRIT_ROOT_QUALITY_BONUS_PER_LEVEL = 0.15
    }
    
    object Rarity {
        val CONFIGS = mapOf(
            1 to RarityConfig(1, "凡品", "#95a5a6", 1.0, 20000),
            2 to RarityConfig(2, "灵品", "#27ae60", 1.3, 70000),
            3 to RarityConfig(3, "宝品", "#3498db", 1.6, 210000),
            4 to RarityConfig(4, "玄品", "#9b59b6", 2.0, 700000),
            5 to RarityConfig(5, "地品", "#f39c12", 2.5, 1400000),
            6 to RarityConfig(6, "天品", "#e74c3c", 3.2, 7000000)
        )
        
        fun get(rarity: Int): RarityConfig = CONFIGS[rarity] ?: CONFIGS.getValue(1)
        
        fun getColor(rarity: Int): String = get(rarity).color
        
        fun getName(rarity: Int): String = get(rarity).name
    }
    
    object Realm {
        val CONFIGS = mapOf(
            9 to RealmConfig(9, "炼气", 225, 10, 0.75, 1.0, 80, 9),
            8 to RealmConfig(8, "筑基", 450, 30, 0.60, 1.5, 120, 9),
            7 to RealmConfig(7, "金丹", 900, 50, 0.50, 2.0, 200, 9),
            6 to RealmConfig(6, "元婴", 1800, 80, 0.40, 3.0, 300, 9),
            5 to RealmConfig(5, "化神", 3600, 110, 0.30, 4.0, 500, 9),
            4 to RealmConfig(4, "炼虚", 16000, 180, 0.30, 5.0, 800, 9),
            3 to RealmConfig(3, "合体", 32000, 220, 0.20, 6.0, 1500, 9),
            2 to RealmConfig(2, "大乘", 64000, 280, 0.10, 8.0, 3000, 9),
            1 to RealmConfig(1, "渡劫", 128000, 360, 0.05, 10.0, 5000, 9),
            0 to RealmConfig(0, "仙人", 256000, 500, 0.0, 15.0, 9999, 9)
        )
        
        fun get(realm: Int): RealmConfig = CONFIGS[realm] ?: CONFIGS.getValue(9)
        
        fun getName(realm: Int): String = get(realm).name
        
        fun getCultivationBase(realm: Int): Int = get(realm).cultivationBase
        
        fun getBreakthroughChance(realm: Int): Double = get(realm).breakthroughChance
        
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
            1 to 0.06,  // 单灵根 6%
            2 to 0.12,  // 双灵根 12%
            3 to 0.15,  // 三灵根 15%
            4 to 0.27,  // 四灵根 27%
            5 to 0.40   // 五灵根 40%
        )
        
        fun get(type: String): SpiritRootConfig = TYPES[type] ?: TYPES.getValue("metal")
        
        fun getAll(): List<SpiritRootConfig> = TYPES.values.toList()
        
        fun getElementName(type: String): String = get(type).name
        
        fun generateRandomSpiritRootCount(): Int {
            val rand = Math.random()
            var cumulative = 0.0
            for ((count, weight) in COUNT_WEIGHTS.toSortedMap()) {
                cumulative += weight
                if (rand <= cumulative) return count
            }
            return 5
        }
        
    }
    
    object Buildings {
        val CONFIGS = mapOf(
            "mainHall" to BuildingConfig("mainHall", "主殿", "宗门核心建筑", 0),
            "discipleHall" to BuildingConfig("discipleHall", "弟子堂", "管理弟子", 6),
            "herbGarden" to BuildingConfig("herbGarden", "灵药宛", "种植灵草", 3),
            "alchemyRoom" to BuildingConfig("alchemyRoom", "丹鼎殿", "炼制丹药", 3),
            "forge" to BuildingConfig("forge", "天工峰", "锻造装备", 3),
            "library" to BuildingConfig("library", "藏经阁", "提升弟子修习功法的速度", 3),
            "tianShuHall" to BuildingConfig("tianShuHall", "天枢殿", "处理宗门事务", 2),
            "wenDaoPeak" to BuildingConfig("wenDaoPeak", "问道峰", "管理外门弟子与传道", 0)
        )
        
        fun get(id: String): BuildingConfig? = CONFIGS[id]
        
        fun getAll(): List<BuildingConfig> = CONFIGS.values.toList()
    }
    
    object Beast {
        val TYPES = listOf(
            BeastTypeConfig("虎妖", "狂暴", 1.3, 1.2, 0.9, 1.0, 1.1),
            BeastTypeConfig("狼妖", "迅捷", 0.9, 1.1, 0.8, 1.3, 1.0),
            BeastTypeConfig("蛇妖", "剧毒", 0.8, 1.3, 0.7, 1.1, 1.2),
            BeastTypeConfig("熊妖", "铁甲", 1.5, 0.9, 1.3, 0.7, 1.3),
            BeastTypeConfig("鹰妖", "神风", 0.7, 1.2, 0.6, 1.4, 0.9),
            BeastTypeConfig("狐妖", "幻魅", 0.8, 1.0, 0.8, 1.2, 1.5),
            BeastTypeConfig("龙妖", "远古", 1.4, 1.4, 1.2, 1.1, 2.0),
            BeastTypeConfig("龟妖", "玄甲", 1.8, 0.8, 1.5, 0.5, 1.4)
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
        const val MIN_BEAST_COUNT = 1
        const val MAX_BEAST_COUNT = 10
        const val MAX_TURNS = 25
        const val CRIT_MULTIPLIER: Double = 2.0
        const val MAX_DODGE_CHANCE: Double = 0.5
        const val DODGE_PER_SPEED_DIFF: Double = 0.005
        const val MAX_BATTLE_DURATION_MS = 5000L
        const val BATTLE_TIMEOUT_WARNING_MS = 3000L
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
    
    data class RarityConfig(
        val level: Int,
        val name: String,
        val color: String,
        val multiplier: Double,
        val basePrice: Int
    )
    
    data class RealmConfig(
        val level: Int,
        val name: String,
        val cultivationBase: Int,
        val salary: Int,
        val breakthroughChance: Double,
        val multiplier: Double = 1.0,
        val maxAge: Int = 100,
        val maxLayers: Int = 10
    )
    
    data class SpiritRootConfig(
        val type: String,
        val name: String,
        val color: String,
        val cultivationBonus: Double
    )
    
    data class BuildingConfig(
        val id: String,
        val name: String,
        val description: String,
        val maxSlots: Int
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
        val lootBonus: Double
    )
}
