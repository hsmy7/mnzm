package com.xianxia.sect.core

object GameConfig {
    
    object Game {
        const val NAME = "模拟宗门"
        const val VERSION = "1.4.19"
        const val AUTO_SAVE_INTERVAL = 60
        const val MAX_SAVE_SLOTS = 5
    }
    
    object Time {
        const val TICK_INTERVAL = 200L
        const val TICKS_PER_SECOND = 5
        const val SECONDS_PER_MONTH = 30
        const val MONTHS_PER_YEAR = 12
        const val MAX_EXPLORE_TIME = 12
        const val HIGH_FREQUENCY_UPDATE_INTERVAL = 200L
        const val LOW_FREQUENCY_UPDATE_INTERVAL = 1000L
    }
    
    object Cultivation {
        const val BASE_SPEED = 5.0
        const val REALM_SPEED_BONUS_THRESHOLD = 3
        const val REALM_SPEED_BONUS = 1.5
        const val BREAKTHROUGH_LAYER_PENALTY = 0.9
        const val SPIRIT_ROOT_QUALITY_BONUS_PER_LEVEL = 0.15
        const val SPIRIT_ROOT_COUNT_PENALTY_1 = 1.0
        const val SPIRIT_ROOT_COUNT_PENALTY_2 = 0.8
        const val SPIRIT_ROOT_COUNT_PENALTY_3 = 0.6
        const val SPIRIT_ROOT_COUNT_PENALTY_4 = 0.4
        const val SPIRIT_ROOT_COUNT_PENALTY_5 = 0.2
        
        // 灵根品质突破限制
        const val HIGH_REALM_BREAKTHROUGH_PENALTY_4_ROOT = 0.6
        const val HIGH_REALM_BREAKTHROUGH_PENALTY_3_ROOT = 0.75
        const val HIGH_REALM_THRESHOLD_4_ROOT = 5
        const val HIGH_REALM_THRESHOLD_3_ROOT = 2
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
            9 to RealmConfig(9, "炼气", 225, 10, 0.75, 1.0, 100, 9),
            8 to RealmConfig(8, "筑基", 450, 30, 0.60, 1.5, 300, 9),
            7 to RealmConfig(7, "金丹", 900, 50, 0.50, 2.0, 500, 9),
            6 to RealmConfig(6, "元婴", 1800, 80, 0.40, 3.0, 800, 9),
            5 to RealmConfig(5, "化神", 3600, 110, 0.30, 4.0, 1200, 9),
            4 to RealmConfig(4, "炼虚", 16000, 180, 0.30, 5.0, 2000, 9),
            3 to RealmConfig(3, "合体", 32000, 220, 0.20, 6.0, 3000, 9),
            2 to RealmConfig(2, "大乘", 64000, 280, 0.10, 8.0, 5000, 9),
            1 to RealmConfig(1, "渡劫", 128000, 360, 0.05, 10.0, 9999, 9),
            0 to RealmConfig(0, "仙人", 256000, 500, 0.0, 15.0, 99999, 9)
        )
        
        fun get(realm: Int): RealmConfig = CONFIGS[realm] ?: CONFIGS.getValue(9)
        
        fun getName(realm: Int): String = get(realm).name
        
        fun getCultivationBase(realm: Int): Int = get(realm).cultivationBase
        
        fun getBreakthroughChance(realm: Int): Double = get(realm).breakthroughChance
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
        
        // 灵根数量权重配置（参考项目）
        val COUNT_WEIGHTS = mapOf(
            1 to 0.08,  // 单灵根 8%
            2 to 0.20,  // 双灵根 20%
            3 to 0.35,  // 三灵根 35%
            4 to 0.60,  // 四灵根 60%
            5 to 0.75   // 五灵根 75%
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
    
    object Battle {
        const val MAX_TEAM_SIZE = 7
        const val MIN_BEAST_COUNT = 1
        const val MAX_BEAST_COUNT = 10
        const val MAX_TURNS = 25
        const val CRIT_MULTIPLIER: Double = 2.0
        const val MAX_DODGE_CHANCE: Double = 0.5
        const val DODGE_PER_SPEED_DIFF: Double = 0.005
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
        
        // 妖兽属性参照三灵根弟子（cultivationBonus = 1.2）
        val REALMS = mapOf(
            0 to BeastRealmConfig(0, "仙人", 1800, 180, 90, 500),
            1 to BeastRealmConfig(1, "渡劫", 1200, 120, 60, 360),
            2 to BeastRealmConfig(2, "大乘", 960, 96, 48, 280),
            3 to BeastRealmConfig(3, "合体", 720, 72, 36, 220),
            4 to BeastRealmConfig(4, "炼虚", 600, 60, 30, 180),
            5 to BeastRealmConfig(5, "化神", 480, 48, 24, 110),
            6 to BeastRealmConfig(6, "元婴", 360, 36, 18, 80),
            7 to BeastRealmConfig(7, "金丹", 240, 24, 12, 50),
            8 to BeastRealmConfig(8, "筑基", 180, 18, 9, 30),
            9 to BeastRealmConfig(9, "炼气", 120, 12, 6, 10)
        )
        
        fun getType(index: Int): BeastTypeConfig = TYPES.getOrElse(index) { TYPES[0] }
        fun getRealm(realm: Int): BeastRealmConfig = REALMS[realm] ?: REALMS.getValue(9)
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
    
    data class BeastRealmConfig(
        val level: Int,
        val name: String,
        val baseHp: Int,
        val baseAtk: Int,
        val baseDef: Int,
        val baseSpeed: Int
    )
}
