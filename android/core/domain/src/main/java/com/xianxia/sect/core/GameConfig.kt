package com.xianxia.sect.core

import com.xianxia.sect.core.config.FavorConfig
import com.xianxia.sect.core.config.GameConfigData
import com.xianxia.sect.core.domain.BuildConfig
import com.xianxia.sect.core.util.DomainLog
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
    STUN, FREEZE, SILENCE, TAUNT,
    DAMAGE_BOOST, DAMAGE_REDUCTION,
    SHIELD, DAMAGE_SHARE, DAMAGE_LINK,
    TURN_ADVANCE;

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
        DAMAGE_BOOST -> "伤害加成"
        DAMAGE_REDUCTION -> "伤害减免"
        SHIELD -> "护盾"
        DAMAGE_SHARE -> "伤害分摊"
        DAMAGE_LINK -> "伤害链接"
        TURN_ADVANCE -> "行动提前"
    }

    val isDebuff: Boolean get() = this in setOf(
        PHYSICAL_ATTACK_REDUCE, MAGIC_ATTACK_REDUCE, PHYSICAL_DEFENSE_REDUCE, MAGIC_DEFENSE_REDUCE,
        SPEED_REDUCE, CRIT_RATE_REDUCE,
        POISON, BURN, STUN, FREEZE, SILENCE, TAUNT,
        DAMAGE_LINK
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

    /** 运行时配置数据（由 [initialize] 设置，启动后不应修改） */
    @Volatile
    private var _configData: GameConfigData? = null

    /**
     * 使用 [GameConfigData] 初始化运行时配置。
     * 此后 [Production]、[Warehouse]、[Battle.RealmGap]、[LawEnforcementConfig] 中的
     * 对应字段将返回 GameConfigData 中的值而非编译期常量。
     * 不调用此方法时，仍使用原有的 [const val] 默认值，保证向后兼容。
     */
    fun initialize(config: GameConfigData) {
        _configData = config
        DomainLog.i("GameConfig", "Runtime config initialized: v${config.version}")
    }

    /** 获取当前运行时配置（可能为 null，此时使用编译期默认值） */
    private fun config(): GameConfigData? = _configData

    object Game {
        const val NAME = "模拟宗门"
        const val VERSION = BuildConfig.VERSION_NAME
        const val MAX_SAVE_SLOTS = 5
    }
    
    object Disciple {
        const val MIN_LOYALTY = 0
        const val MAX_LOYALTY = 100
        const val MIN_AGE = 5
        const val MAX_AGE = 100
        const val PROTECTION_MONTHS = 12
    }

    object Elder {
        const val REALM_VICE_SECT_MASTER = 4
        const val REALM_LAW_ENFORCEMENT = 5
        const val REALM_ELDER = 6
        const val REALM_PREACHING_MASTER = 7
    }
    
    object Time {
        const val TICK_INTERVAL = 100L
        const val TICKS_PER_SECOND = 10
        const val DAYS_PER_MONTH = 30  // 保留兼容，旬制下不再使用天
        const val PHASES_PER_MONTH = 3  // 上/中/下旬
        const val MONTHS_PER_YEAR = 12
        const val MAX_EXPLORE_TIME = 12
        const val HIGH_FREQUENCY_UPDATE_INTERVAL = 1000L
        const val LOW_FREQUENCY_UPDATE_INTERVAL = 2000L
    }
    
    object Cultivation {
        /** 单灵根每旬修炼速度（按境界查表，realm → 每旬修为值） */
        val REALM_SPEED_PER_PHASE: Map<Int, Double> = mapOf(
            9 to 19.0,      // 炼气（原28，减30%→19）
            8 to 26.0,      // 筑基（原38，减30%→26）
            7 to 43.0,      // 金丹（原62，减30%→43）
            6 to 70.0,      // 元婴（原100，减30%→70）
            5 to 109.0,     // 化神（原156，减30%→109）
            4 to 212.0,     // 炼虚（原304，减30%→212）
            3 to 330.0,     // 合体（原472，减30%→330）
            2 to 533.0,     // 大乘（原762，减30%→533）
            1 to 826.0,     // 渡劫（原1180，减30%→826）
            0 to 1120.0     // 仙人（原1600，减30%→1120）
        )

        /** 查询某境界的单灵根每旬修炼速度 */
        fun getRealmPerPhase(realm: Int): Double =
            REALM_SPEED_PER_PHASE[realm] ?: REALM_SPEED_PER_PHASE.getValue(9)

        const val DAILY_HP_MP_RECOVERY_RATE = 0.05

        /** 住所建筑修炼速度加成系数（按建筑 displayName 查表） */
        val BUILDING_BONUSES: Map<String, Double> = mapOf(
            "中级单人住所" to 1.40,
            "单人住所" to 1.20,
            "多人住所" to 1.10
        )
    }

    object Production {

        val SPIRIT_MINE_BASE_OUTPUT_PER_MINER: Int
            get() = config()?.production?.spiritMineBaseOutputPerMiner ?: 160
        val SPIRIT_MINE_MINING_THRESHOLD: Int
            get() = config()?.production?.spiritMineMiningThreshold ?: 70
        val SPIRIT_MINE_MINING_BONUS_RATE: Double
            get() = config()?.production?.spiritMineMiningBonusRate ?: 0.02
    }

    object HerbGarden {
        /** Aura radius in grid tiles centered on the herb garden building */
        const val AURA_RADIUS_TILES = 6.0
    }

    object Warehouse {
        val BASE_CAPACITY: Int
            get() = config()?.warehouse?.baseCapacity ?: 50
        val CAPACITY_PER_BUILDING: Int
            get() = config()?.warehouse?.capacityPerBuilding ?: 75
    }

    object Rarity {
        val CONFIGS = mapOf(
            1 to RarityConfig(1, "凡品", "#b8b8b8", 1.0,
                basePrice = 4000, pillBasePrice = 4000, materialBasePrice = 400,
                herbPrice = 400, seedPrice = 80),
            2 to RarityConfig(2, "灵品", "#afcb8a", 1.3,
                basePrice = 16000, pillBasePrice = 16000, materialBasePrice = 1600,
                herbPrice = 1600, seedPrice = 320),
            3 to RarityConfig(3, "宝品", "#9fc2ee", 1.6,
                basePrice = 80000, pillBasePrice = 80000, materialBasePrice = 8000,
                herbPrice = 8000, seedPrice = 1600),
            4 to RarityConfig(4, "玄品", "#c0a2dd", 2.0,
                basePrice = 480000, pillBasePrice = 480000, materialBasePrice = 48000,
                herbPrice = 48000, seedPrice = 9600),
            5 to RarityConfig(5, "地品", "#e7c67d", 2.5,
                basePrice = 3360000, pillBasePrice = 3360000, materialBasePrice = 336000,
                herbPrice = 336000, seedPrice = 67200),
            6 to RarityConfig(6, "天品", "#e3a0a0", 3.2,
                basePrice = 26880000, pillBasePrice = 26880000, materialBasePrice = 2688000,
                herbPrice = 2688000, seedPrice = 537600)
        )
        
        fun get(rarity: Int): RarityConfig = CONFIGS[rarity] ?: CONFIGS.getValue(1)
        
        const val SELL_PRICE_MULTIPLIER = 0.8
        
        fun calculateSellPrice(basePrice: Int, quantity: Int): Long {
            return (basePrice.toLong() * quantity * SELL_PRICE_MULTIPLIER).toLong()
        }
        
        fun getColor(rarity: Int): String = get(rarity).color
        
        fun getName(rarity: Int): String = get(rarity).name
    }
    
    object Realm {
        val CONFIGS = mapOf(
            9 to RealmConfig(9, "炼气", 65, 10,
                maxAge = 80, maxLayers = 9,
                baseHp = 203, baseMp = 78, basePhysicalAttack = 16, baseMagicAttack = 16,
                basePhysicalDefense = 13, baseMagicDefense = 10, baseSpeed = 15),
            8 to RealmConfig(8, "筑基", 260, 30,
                maxAge = 120, maxLayers = 9,
                baseHp = 507, baseMp = 195, basePhysicalAttack = 39, baseMagicAttack = 39,
                basePhysicalDefense = 33, baseMagicDefense = 26, baseSpeed = 38),
            7 to RealmConfig(7, "金丹", 1040, 50,
                maxAge = 200, maxLayers = 9,
                baseHp = 1318, baseMp = 507, basePhysicalAttack = 101, baseMagicAttack = 101,
                basePhysicalDefense = 85, baseMagicDefense = 68, baseSpeed = 98),
            6 to RealmConfig(6, "元婴", 3900, 80,
                maxAge = 300, maxLayers = 9,
                baseHp = 3448, baseMp = 1326, basePhysicalAttack = 265, baseMagicAttack = 265,
                basePhysicalDefense = 221, baseMagicDefense = 177, baseSpeed = 255),
            5 to RealmConfig(5, "化神", 13000, 110,
                maxAge = 500, maxLayers = 9,                baseHp = 9126, baseMp = 3510, basePhysicalAttack = 702, baseMagicAttack = 702,
                basePhysicalDefense = 585, baseMagicDefense = 468, baseSpeed = 675),
            4 to RealmConfig(4, "炼虚", 39000, 180,
                maxAge = 800, maxLayers = 9,                baseHp = 22308, baseMp = 8580, basePhysicalAttack = 1716, baseMagicAttack = 1716,
                basePhysicalDefense = 1430, baseMagicDefense = 1144, baseSpeed = 1650),
            3 to RealmConfig(3, "合体", 130000, 220,
                maxAge = 1500, maxLayers = 9,                baseHp = 52728, baseMp = 20280, basePhysicalAttack = 4056, baseMagicAttack = 4056,
                basePhysicalDefense = 3380, baseMagicDefense = 2704, baseSpeed = 3900),
            2 to RealmConfig(2, "大乘", 390000, 280,
                maxAge = 2500, maxLayers = 9,                baseHp = 117624, baseMp = 45240, basePhysicalAttack = 9048, baseMagicAttack = 9048,
                basePhysicalDefense = 7540, baseMagicDefense = 6032, baseSpeed = 8700),
            1 to RealmConfig(1, "渡劫", 1300000, 360,
                maxAge = 4000, maxLayers = 9,                baseHp = 243360, baseMp = 93600, basePhysicalAttack = 18720, baseMagicAttack = 18720,
                basePhysicalDefense = 15600, baseMagicDefense = 12480, baseSpeed = 18000),
            0 to RealmConfig(0, "仙人", 3900000, 500,
                maxAge = 9999, maxLayers = 9,                baseHp = 507000, baseMp = 195000, basePhysicalAttack = 39000, baseMagicAttack = 39000,
                basePhysicalDefense = 32500, baseMagicDefense = 26000, baseSpeed = 37500)
        )

        val BREAKTHROUGH_CHANCES: Map<Int, Map<Int, Double>> = mapOf(
            9 to mapOf(1 to 0.90, 2 to 0.70, 3 to 0.60, 4 to 0.40, 5 to 0.30),
            8 to mapOf(1 to 0.80, 2 to 0.60, 3 to 0.50, 4 to 0.30, 5 to 0.20),
            7 to mapOf(1 to 0.60, 2 to 0.40, 3 to 0.30, 4 to 0.10, 5 to 0.00),
            6 to mapOf(1 to 0.42, 2 to 0.22, 3 to 0.12, 4 to 0.00, 5 to 0.00),
            5 to mapOf(1 to 0.34, 2 to 0.14, 3 to 0.04, 4 to 0.00, 5 to 0.00),
            4 to mapOf(1 to 0.26, 2 to 0.06, 3 to 0.00, 4 to 0.00, 5 to 0.00),
            3 to mapOf(1 to 0.16, 2 to 0.00, 3 to 0.00, 4 to 0.00, 5 to 0.00),
            2 to mapOf(1 to 0.12, 2 to 0.00, 3 to 0.00, 4 to 0.00, 5 to 0.00),
            1 to mapOf(1 to 0.06, 2 to 0.00, 3 to 0.00, 4 to 0.00, 5 to 0.00),
            0 to mapOf(1 to 0.02, 2 to 0.00, 3 to 0.00, 4 to 0.00, 5 to 0.00)
        )
        
        fun get(realm: Int): RealmConfig = CONFIGS[realm] ?: CONFIGS.getValue(9)
        
        fun getName(realm: Int): String = get(realm).name
        
        fun getCultivationBase(realm: Int): Int = get(realm).cultivationBase
        
        fun getBreakthroughChance(realm: Int, rootCount: Int, realmLayer: Int = 1): Double {
            if (realmLayer <= 0) return 0.0
            val clampedRootCount = rootCount.coerceIn(1, 5)
            val currentProb = BREAKTHROUGH_CHANCES[realm]?.get(clampedRootCount) ?: 0.0
            val nextRealmProb = BREAKTHROUGH_CHANCES[realm - 1]?.get(clampedRootCount) ?: 0.0
            val maxLayers = get(realm).maxLayers
            if (maxLayers <= 1 || realmLayer == 1) return currentProb
            if (realmLayer >= maxLayers) return nextRealmProb
            val progress = (realmLayer - 1).toDouble() / (maxLayers - 1)
            val rawProb = currentProb + (nextRealmProb - currentProb) * progress
            return kotlin.math.round(rawProb * 100.0) / 100.0
        }
        
        
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

        /**
         * 各境界最小合理年龄（AI 弟子生成时年龄-境界匹配，防"38岁炼虚"类数据）。
         * 所有值均低于对应境界 [RealmConfig.maxAge]，无寿元冲突。
         */
        val REALM_MIN_REASONABLE_AGE: Map<Int, Int> = mapOf(
            9 to 10, 8 to 30, 7 to 60, 6 to 100, 5 to 200,
            4 to 300, 3 to 500, 2 to 800, 1 to 1200, 0 to 2000
        )

        /** 获取指定境界的最小合理年龄，未知境界回退炼气标准 */
        fun minReasonableAge(realm: Int): Int =
            REALM_MIN_REASONABLE_AGE[realm] ?: REALM_MIN_REASONABLE_AGE.getValue(9)
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
            1 to 0.01,  // 1%  单灵根
            2 to 0.03,  // 3%  双灵根
            3 to 0.26,  // 26% 三灵根
            4 to 0.30,  // 30% 四灵根
            5 to 0.40   // 40% 五灵根
        )
        
        fun get(type: String): SpiritRootConfig = TYPES[type] ?: TYPES.getValue("metal")
        
        fun getAll(): List<SpiritRootConfig> = TYPES.values.toList()
        
        fun getElementName(type: String): String = get(type).name
        
        fun generateRandomSpiritRootCount(): Int {
            val rand = GameRandom.nextDouble()
            var cumulative = 0.0
            for ((count, weight) in COUNT_WEIGHTS.toSortedMap()) {
                cumulative += weight
                if (rand < cumulative) return count
            }
            DomainLog.w("SpiritRoot", "灵根权重和<1.0（累积=$cumulative），回退到5灵根，请检查COUNT_WEIGHTS配置")
            return 5
        }
        
    }
    
    object Beast {
        data class RealmStats(
            val hp: Int,
            val mp: Int,
            val attack: Int,
            val defense: Int,
            val speed: Int
        )

        val REALM_STATS = mapOf(
            9  to RealmStats(hp=339,    mp=130,    attack=31,     defense=22,     speed=16),
            8  to RealmStats(hp=847,    mp=326,    attack=76,     defense=57,     speed=41),
            7  to RealmStats(hp=2201,   mp=847,    attack=195,    defense=148,    speed=104),
            6  to RealmStats(hp=5756,   mp=2214,   attack=514,    defense=384,    speed=272),
            5  to RealmStats(hp=15236,  mp=5860,   attack=1359,   defense=1016,   speed=722),
            4  to RealmStats(hp=37243,  mp=14324,  attack=3324,   defense=2483,   speed=1763),
            3  to RealmStats(hp=88029,  mp=33858,  attack=7855,   defense=5869,   speed=4167),
            2  to RealmStats(hp=196374, mp=75528,  attack=17523,  defense=13091,  speed=9296),
            1  to RealmStats(hp=406249, mp=156265, attack=36254,  defense=27087,  speed=19233),
            0  to RealmStats(hp=846353, mp=325553, attack=75528,  defense=56429,  speed=40068)
        )

        fun getRealmStats(realm: Int): RealmStats = REALM_STATS[realm] ?: REALM_STATS.getValue(9)

        val TYPES = listOf(
            // 虎妖 — Metal — 物理爆发DPS
            BeastTypeConfig("虎妖", "狂暴", 1.3, 1.4, 0.7, 1.0, 1.1, "metal",
                listOf(
                    BeastSkillConfig("猛虎下山", 1.8, 3, 20, SkillType.ATTACK,
                        DamageType.PHYSICAL),
                    BeastSkillConfig("虎爪撕裂", 0.9, 2, 15, SkillType.ATTACK,
                        DamageType.PHYSICAL, hits = 2),
                    BeastSkillConfig("虎啸", 0.7, 4, 30, SkillType.ATTACK,
                        DamageType.PHYSICAL, isAoe = true),
                    BeastSkillConfig("咆哮", 0.0, 5, 25, SkillType.SUPPORT,
                        DamageType.PHYSICAL,
                        buffType = BuffType.PHYSICAL_ATTACK_BOOST,
                        buffValue = 0.2, buffDuration = 3,
                        targetScope = "team")
                )),
            // 狼妖 — Wood — 速度/多段DPS
            BeastTypeConfig("狼妖", "迅捷", 0.6, 1.2, 0.6, 1.5, 1.0, "wood",
                listOf(
                    BeastSkillConfig("狼群撕咬", 0.6, 3, 20, SkillType.ATTACK,
                        DamageType.PHYSICAL, hits = 3),
                    BeastSkillConfig("疾风步", 0.0, 4, 20, SkillType.SUPPORT,
                        DamageType.PHYSICAL,
                        buffType = BuffType.SPEED_BOOST,
                        buffValue = 0.3, buffDuration = 3,
                        targetScope = "self"),
                    BeastSkillConfig("围猎", 0.5, 4, 30, SkillType.ATTACK,
                        DamageType.PHYSICAL, isAoe = true),
                    BeastSkillConfig("狼王嚎", 0.0, 5, 25, SkillType.SUPPORT,
                        DamageType.PHYSICAL,
                        buffType = BuffType.CRIT_RATE_BOOST,
                        buffValue = 0.1, buffDuration = 3,
                        targetScope = "team")
                )),
            // 蛇妖 — Water — 毒/控制DPS
            BeastTypeConfig("蛇妖", "剧毒", 0.7, 1.5, 0.5, 1.1, 1.2, "water",
                listOf(
                    BeastSkillConfig("毒牙", 1.2, 3, 15, SkillType.ATTACK,
                        DamageType.PHYSICAL,
                        buffType = BuffType.POISON,
                        buffValue = 0.08, buffDuration = 2),
                    BeastSkillConfig("毒雾", 0.5, 4, 30, SkillType.ATTACK,
                        DamageType.PHYSICAL, isAoe = true,
                        buffType = BuffType.POISON,
                        buffValue = 0.04, buffDuration = 2),
                    BeastSkillConfig("缠绕", 0.4, 4, 20, SkillType.ATTACK,
                        DamageType.PHYSICAL,
                        buffType = BuffType.SPEED_REDUCE,
                        buffValue = 0.3, buffDuration = 2),
                    BeastSkillConfig("蜕皮新生", 0.0, 5, 25, SkillType.SUPPORT,
                        DamageType.PHYSICAL,
                        healPercent = 0.25,
                        targetScope = "self")
                )),
            // 熊妖 — Earth — 坦克/嘲讽
            BeastTypeConfig("熊妖", "铁甲", 1.5, 0.5, 1.4, 0.5, 1.1, "earth",
                listOf(
                    BeastSkillConfig("震地", 0.6, 4, 30, SkillType.ATTACK,
                        DamageType.PHYSICAL, isAoe = true),
                    BeastSkillConfig("铁壁", 0.0, 4, 15, SkillType.SUPPORT,
                        DamageType.PHYSICAL,
                        buffType = BuffType.PHYSICAL_DEFENSE_BOOST,
                        buffValue = 0.4, buffDuration = 3,
                        targetScope = "self"),
                    BeastSkillConfig("熊吼", 0.3, 4, 20, SkillType.ATTACK,
                        DamageType.PHYSICAL,
                        buffType = BuffType.TAUNT,
                        buffValue = 1.0, buffDuration = 2),
                    BeastSkillConfig("坚韧熊躯", 0.0, 5, 25, SkillType.SUPPORT,
                        DamageType.PHYSICAL,
                        buffType = BuffType.DAMAGE_REDUCTION,
                        buffValue = 0.2, buffDuration = 3,
                        targetScope = "team")
                )),
            // 鹰妖 — Metal — 高速暴击DPS
            BeastTypeConfig("鹰妖", "神风", 0.5, 1.3, 0.5, 1.6, 1.3, "metal",
                listOf(
                    BeastSkillConfig("俯冲", 2.0, 3, 25, SkillType.ATTACK,
                        DamageType.PHYSICAL),
                    BeastSkillConfig("鹰眼", 0.0, 3, 15, SkillType.SUPPORT,
                        DamageType.PHYSICAL,
                        buffType = BuffType.CRIT_RATE_BOOST,
                        buffValue = 0.2, buffDuration = 3,
                        targetScope = "self"),
                    BeastSkillConfig("旋风斩", 0.8, 4, 30, SkillType.ATTACK,
                        DamageType.PHYSICAL, isAoe = true),
                    BeastSkillConfig("天翔一闪", 0.0, 5, 25, SkillType.SUPPORT,
                        DamageType.PHYSICAL,
                        turnAdvancePercent = 1.0,
                        targetScope = "self")
                )),
            // 狐妖 — Fire — 魔法/控制
            BeastTypeConfig("狐妖", "幻魅", 0.7, 1.0, 0.7, 1.4, 1.4, "fire",
                listOf(
                    BeastSkillConfig("妖术", 1.5, 3, 20, SkillType.ATTACK,
                        DamageType.MAGIC,
                        buffType = BuffType.SILENCE,
                        buffValue = 1.0, buffDuration = 1),
                    BeastSkillConfig("狐火", 1.2, 3, 15, SkillType.ATTACK,
                        DamageType.MAGIC,
                        buffType = BuffType.BURN,
                        buffValue = 0.05, buffDuration = 2),
                    BeastSkillConfig("魅惑", 0.5, 4, 20, SkillType.ATTACK,
                        DamageType.MAGIC,
                        buffType = BuffType.PHYSICAL_ATTACK_REDUCE,
                        buffValue = 0.25, buffDuration = 2),
                    BeastSkillConfig("幻阵", 0.0, 5, 25, SkillType.SUPPORT,
                        DamageType.MAGIC,
                        buffType = BuffType.DAMAGE_BOOST,
                        buffValue = 0.2, buffDuration = 3,
                        targetScope = "team")
                )),
            // 龙妖 — Fire — 精英/领袖
            BeastTypeConfig("龙妖", "远古", 1.2, 1.3, 1.1, 1.0, 1.5, "fire",
                listOf(
                    BeastSkillConfig("龙息", 0.8, 4, 35, SkillType.ATTACK,
                        DamageType.MAGIC, isAoe = true),
                    BeastSkillConfig("龙爪撕裂", 1.6, 3, 20, SkillType.ATTACK,
                        DamageType.PHYSICAL),
                    BeastSkillConfig("龙威", 0.0, 6, 30, SkillType.SUPPORT,
                        DamageType.PHYSICAL,
                        buffs = listOf(
                            Triple(BuffType.PHYSICAL_ATTACK_BOOST, 0.25, 3),
                            Triple(BuffType.MAGIC_ATTACK_BOOST, 0.25, 3)
                        ),
                        targetScope = "team"),
                    BeastSkillConfig("龙鳞护体", 0.0, 4, 20, SkillType.SUPPORT,
                        DamageType.PHYSICAL,
                        buffType = BuffType.DAMAGE_REDUCTION,
                        buffValue = 0.2, buffDuration = 3,
                        targetScope = "self")
                )),
            // 龟妖 — Water — 要塞坦克
            BeastTypeConfig("龟妖", "玄甲", 1.6, 0.4, 1.5, 0.4, 1.0, "water",
                listOf(
                    BeastSkillConfig("缩壳", 0.0, 4, 15, SkillType.SUPPORT,
                        DamageType.PHYSICAL,
                        buffType = BuffType.PHYSICAL_DEFENSE_BOOST,
                        buffValue = 0.5, buffDuration = 2,
                        targetScope = "self"),
                    BeastSkillConfig("水盾", 0.0, 5, 25, SkillType.SUPPORT,
                        DamageType.MAGIC,
                        buffType = BuffType.MAGIC_DEFENSE_BOOST,
                        buffValue = 0.3, buffDuration = 3,
                        targetScope = "team"),
                    BeastSkillConfig("激流", 0.5, 4, 25, SkillType.ATTACK,
                        DamageType.PHYSICAL, isAoe = true),
                    BeastSkillConfig("龟甲术", 0.0, 6, 25, SkillType.SUPPORT,
                        DamageType.PHYSICAL,
                        shieldPercent = 0.15, buffDuration = 3,
                        targetScope = "team")
                ))
        )

        fun getType(index: Int): BeastTypeConfig = TYPES.getOrElse(index) { TYPES[0] }
    }

    // Enemy.REALM_STATS 已删除 — 敌对弟子统一使用 GameConfig.Realm 基础属性 + DiscipleStatCalculator 公式
    object Starting {
        val RESOURCES = StartingResources(
            spiritStones = 2000,
            reputation = 100,
            spiritHerbs = 50
        )
    }
    
    object PlayerProtection {
        const val PROTECTION_YEARS = 100
    }

    /** AI宗门智能进攻配置 */
    object AIAttack {
        /** 谴责→正式进攻间隔（月） */
        const val DENUNCIATION_BEFORE_ATTACK_MONTHS = 6
        /** 战书→正式进攻间隔（月） */
        const val WAR_WARNING_BEFORE_ATTACK_MONTHS = 3
        /** 缓和关系薄礼灵石数量 */
        const val APPEASE_GIFT_SPIRIT_STONES = 20_000L
        /** 附庸年贡比例（上年灵石收入的百分比） */
        const val VASSAL_TRIBUTE_RATIO = 0.5
        /** 附庸年贡最低灵石 */
        const val VASSAL_TRIBUTE_MIN = 1L
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
        const val MAX_YEARLY_REPORTS = 100
    }

    /** 招募弟子每月上限 */
    const val RECRUIT_MONTHLY_LIMIT = 30
    
    object Battle {
        const val MAX_TEAM_SIZE = 7
        const val MIN_BEAST_COUNT = 3
        const val MAX_BEAST_COUNT = 11
        const val MAX_TURNS = 25
        /** 基础暴伤倍率（暴击时额外增加的伤害比例，0.5 = +50% 伤害） */
        const val CRIT_BASE_MULTIPLIER: Double = 0.5
        const val MAX_DODGE_CHANCE: Double = 0.5
        const val MAX_SKILL_DODGE_CHANCE: Double = 0.3
        const val DODGE_PER_SPEED_DIFF: Double = 0.005
        const val MAX_BATTLE_DURATION_MS = 5000L
        const val BATTLE_TIMEOUT_WARNING_MS = 3000L
        const val DEFENSE_CONSTANT: Double = 500.0
        const val DAMAGE_VARIANCE_PERCENT: Double = 20.0
        const val MIN_DAMAGE: Int = 1
        const val ELDER_SLOTS = 2
        const val DISCIPLE_SLOTS = 8
        const val MIN_FORMATION_SIZE = ELDER_SLOTS + DISCIPLE_SLOTS

        object RealmGap {
            val DAMAGE_BONUS_PER_REALM: Double
                get() = config()?.battle?.realmGap?.damageBonusPerRealm ?: 0.35
            val DAMAGE_PENALTY_PER_REALM: Double
                get() = config()?.battle?.realmGap?.damagePenaltyPerRealm ?: 0.35
            const val INSTANT_KILL_GAP = 1
        }
    }
    
    /**
     * 宗门政策配置
     * 包含所有政策的消耗金额、基础效果和名称
     */
    object PolicyConfig {
        // ═══════════════════════════════════════════════════
        // 政策月消耗金额（灵石/月）
        // ═══════════════════════════════════════════════════
        // 固定月消耗
        const val SPIRIT_MINE_BOOST_MONTHLY = 0L           // 灵矿增产无灵石消耗
        const val ENHANCED_SECURITY_MONTHLY = 3000L
        const val ALCHEMY_INCENTIVE_MONTHLY = 3000L
        const val FORGE_INCENTIVE_MONTHLY = 3000L
        const val HERB_CULTIVATION_MONTHLY = 3000L
        const val MANUAL_RESEARCH_MONTHLY = 4000L
        const val CURFEW_MONTHLY = 1000L
        const val REWARD_PUNISH_MONTHLY = 3000L
        const val STRICT_TRAINING_MONTHLY = 20000L
        const val RELAXED_MGMT_MONTHLY = 3000L
        const val SPIRIT_SPRING_MONTHLY = 2000L
        const val FRUGALITY_MONTHLY = 0L
        // 按弟子数计费（单价/弟子/月）
        const val CULTIVATION_SUBSIDY_PER_DISCIPLE = 300L   // 化神下弟子
        const val ASCETIC_TRAINING_PER_DISCIPLE = 800L      // 全弟子
        const val MORAL_EDUCATION_PER_DISCIPLE = 100L       // 全弟子
        const val BENEVOLENT_GOVERNANCE_PER_DISCIPLE = 100L // 全弟子
        // 周期性消耗
        const val OPEN_RECRUITMENT_COST = 50000L            // 每3年
        const val OPEN_RECRUITMENT_COOLDOWN_MONTHS = 36

        // ═══════════════════════════════════════════════════
        // 政策名称
        // ═══════════════════════════════════════════════════
        const val SPIRIT_MINE_BOOST_NAME = "灵矿增产"
        const val ENHANCED_SECURITY_NAME = "增强治安"
        const val ALCHEMY_INCENTIVE_NAME = "丹道激励"
        const val FORGE_INCENTIVE_NAME = "锻造激励"
        const val HERB_CULTIVATION_NAME = "灵药培育"
        const val CULTIVATION_SUBSIDY_NAME = "修行津贴"
        const val MANUAL_RESEARCH_NAME = "功法研习"
        const val OPEN_RECRUITMENT_NAME = "广纳门徒"
        const val ASCETIC_TRAINING_NAME = "苦修令"
        const val CURFEW_NAME = "宵禁"
        const val REWARD_PUNISH_NAME = "赏善罚恶"
        const val STRICT_TRAINING_NAME = "严苛训练"
        const val RELAXED_MGMT_NAME = "松弛管理"
        const val SPIRIT_SPRING_NAME = "灵泉灌溉"
        const val FRUGALITY_NAME = "开源节流"
        const val MORAL_EDUCATION_NAME = "教化之道"
        const val BENEVOLENT_GOVERNANCE_NAME = "仁政爱徒"

        // ═══════════════════════════════════════════════════
        // 政策基础效果
        // ═══════════════════════════════════════════════════
        const val SPIRIT_MINE_BOOST_EFFECT = 0.20            // 灵石产出+20%
        const val ENHANCED_SECURITY_EFFECT = 0.20            // 抓捕率+20%
        const val ALCHEMY_INCENTIVE_EFFECT = 0.10            // 炼丹成功率+10%
        const val ALCHEMY_TIME_PENALTY = 0.10                // 炼丹时间+10%
        const val FORGE_INCENTIVE_EFFECT = 0.10              // 锻造成功率+10%
        const val FORGE_TIME_PENALTY = 0.10                  // 锻造时间+10%
        const val HERB_CULTIVATION_EFFECT = 0.20             // 灵药生长速度+20%
        const val CULTIVATION_SUBSIDY_EFFECT = 0.15          // 化神下修炼+15%
        const val MANUAL_RESEARCH_EFFECT = 0.20              // 功法速度+20%
        const val ASCETIC_TRAINING_EFFECT = 0.25             // 修炼速度+25%
        const val CURFEW_EVENT_REDUCTION = 0.30              // 治安事件-30%
        const val CURFEW_DESERTION_REDUCTION = 0.20          // 叛逃-20%
        const val REWARD_PUNISH_EFFECT = 0.30                // 执法效率+30%
        const val OPEN_RECRUITMENT_POOL_BONUS = 0.50         // 招募上限+50%
        const val STRICT_TRAINING_DAMAGE = 0.05              // 战斗伤害+5%
        const val RELAXED_MGMT_CULTIVATION_PENALTY = 0.10    // 修炼速度-10%
        const val SPIRIT_SPRING_YIELD = 0.15                 // 灵田产量+15%
        const val FRUGALITY_SALARY_REDUCTION = 0.30          // 年俸-30%
        // 政策月度忠诚/道德变化值
        const val MORAL_EDUCATION_PER_MONTH = 1              // 每月道德+1
        const val BENEVOLENT_LOYALTY_PER_MONTH = 1            // 仁政爱徒 每月忠诚+1
        const val RELAXED_MGMT_LOYALTY_PER_MONTH = 2          // 松弛管理 每月忠诚+2
        const val STRICT_TRAINING_LOYALTY_PER_MONTH = -1      // 严苛训练 每月忠诚-1
        const val ENHANCED_SECURITY_LOYALTY_PER_MONTH = -1    // 增强治安 每月忠诚-1
        const val CURFEW_LOYALTY_PER_MONTH = -1               // 宵禁 每月忠诚-1
        const val MORAL_EDUCATION_MAX = 70                   // 道德上限70
        const val BENEVOLENT_GOVERNANCE_PER_MONTH = 1        // 每月忠诚+1
        const val BENEVOLENT_GOVERNANCE_MAX = 100            // 忠诚上限100
        
        // 副宗主智力加成基准值
        const val VICE_SECT_MASTER_INTELLIGENCE_BASE = 50
        // 每超过基准值5点智力，政策效果增加1%
        const val VICE_SECT_MASTER_INTELLIGENCE_STEP = 5
        const val VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP = 0.01

        // 灵植长老/灵植弟子成熟速度加成
        const val HERB_GARDEN_ELDER_SPIRIT_BASE = 80
        const val HERB_GARDEN_ELDER_SPIRIT_STEP = 4

        // 长老技能基线 / 加成除数
        const val ELDER_SKILL_BASELINE = 80
        const val ELDER_BONUS_DIVISOR = 5
        /** 内外门长老突破率加成最大步数（每次1%），即最多+5% */
        const val ELDER_BREAKTHROUGH_MAX_STEPS = 5
        const val SPEED_REDUCTION_DIVISOR = 4.0
        const val HERB_GARDEN_ELDER_MAX = 0.20
        const val HERB_GARDEN_DISCIPLE_SPIRIT_BASE = 50
        const val HERB_GARDEN_DISCIPLE_SPIRIT_STEP = 5
        const val HERB_GARDEN_DISCIPLE_MAX = 0.20
    }

    object LawEnforcementConfig {
        val LOYALTY_THRESHOLD: Int
            get() = config()?.lawEnforcement?.loyaltyThreshold ?: 30
        val MORALITY_THRESHOLD: Int
            get() = config()?.lawEnforcement?.moralityThreshold ?: 30
        val PROB_PER_POINT: Double
            get() = config()?.lawEnforcement?.probPerPoint ?: 0.01
        val MAX_PROB: Double
            get() = config()?.lawEnforcement?.maxProb ?: 0.90
        val BASE_CAPTURE_RATE: Double
            get() = config()?.lawEnforcement?.baseCaptureRate ?: 0.0
        val INTELLIGENCE_BASE: Int
            get() = config()?.lawEnforcement?.intelligenceBase ?: 50
        val ELDER_BONUS_PER_POINT: Double
            get() = config()?.lawEnforcement?.elderBonusPerPoint ?: 0.01
        val DISCIPLE_INTELLIGENCE_STEP: Int
            get() = config()?.lawEnforcement?.discipleIntelligenceStep ?: 5
        val DISCIPLE_BONUS_PER_STEP: Double
            get() = config()?.lawEnforcement?.discipleBonusPerStep ?: 0.01
        val REFLECTION_YEARS: Int
            get() = config()?.lawEnforcement?.reflectionYears ?: 5
        val NEW_DISCIPLE_PROTECTION_MONTHS: Int
            get() = config()?.lawEnforcement?.newDiscipleProtectionMonths ?: 12
        val HERD_LOYALTY_THRESHOLD: Int
            get() = config()?.lawEnforcement?.herdLoyaltyThreshold ?: 50

        // ── 境界基准偷盗量（等比数列 ×4，1=炼气 … 9=渡劫） ──
        val THEFT_REALM_BASE_AMOUNTS: Map<Int, Long> = mapOf(
            1 to 500L,
            2 to 2_000L,
            3 to 8_000L,
            4 to 32_000L,
            5 to 128_000L,
            6 to 512_000L,
            7 to 2_000_000L,
            8 to 8_000_000L,
            9 to 32_000_000L
        )
        const val THEFT_SPEED_BONUS_PER_POINT = 0.005   // 每点身法（超基准）加成
        const val THEFT_SPEED_BASE = 50                  // 身法基准值
        const val THEFT_INTELLIGENCE_BONUS_PER_POINT = 0.003
        const val THEFT_INTELLIGENCE_BASE = 50
        const val THEFT_MAX_RATIO_OF_TOTAL = 0.10       // 单次偷盗不超过宗门灵石 10%
        const val THEFT_MIN_AMOUNT = 100L               // 最少偷 100 灵石
        const val THEFT_ITEM_BASE_DIVISOR = 20_000L     // 境界基准/此值=可偷物品单位数
        const val THEFT_ITEM_GUARD_REDUCTION = 2        // 每个守卫减少物品单位
        const val THEFT_ITEM_UNIT_SPEED_FACTOR = 3      // 身法→可偷物品单位的转换系数
        const val THEFT_ITEM_UNIT_INTEL_FACTOR = 3      // 智力→可偷物品单位的转换系数
        const val MAX_THEFT_PER_YEAR = 3                  // 宗门每年最多被偷盗次数
        const val MAX_THEFT_JUDGEMENTS_PER_MONTH = 3      // 每月最多判定3名弟子
        const val THEFT_MORAL_EDUCATION_THRESHOLD = 30  // 教化之道下道德仍低于此值需检查偷盗
    }

    data class RarityConfig(
        val level: Int,
        val name: String,
        val color: String,
        val multiplier: Double,
        val basePrice: Int,
        val pillBasePrice: Int = 0,
        val materialBasePrice: Int = 0,
        val herbPrice: Int = 0,
        val seedPrice: Int = 0
    )
    
    data class RealmConfig(
        val level: Int,
        val name: String,
        val cultivationBase: Int,
        val salary: Int,
        val maxAge: Int = 100,
        val maxLayers: Int = 9,
        val baseHp: Int = 156,
        val baseMp: Int = 78,
        val basePhysicalAttack: Int = 16,
        val baseMagicAttack: Int = 16,
        val basePhysicalDefense: Int = 13,
        val baseMagicDefense: Int = 10,
        val baseSpeed: Int = 15
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
        val targetScope: String = "enemy",
        val healPercent: Double = 0.0,
        val healFixed: Int = 0,
        val healType: HealType = HealType.HP,
        val shieldPercent: Double = 0.0,
        val turnAdvancePercent: Double = 0.0,
        val damageSharePercent: Double = 0.0,
        val damageLinkPercent: Double = 0.0,
        val buffs: List<Triple<BuffType, Double, Int>> = emptyList(),
        val skillDescription: String = ""
    )
    
    object AI {
        const val MIN_DISCIPLES_FOR_ATTACK = 10
        const val POWER_RATIO_THRESHOLD = 0.8
        const val TEAM_SIZE = 10
        const val MAX_BATTLE_TURNS = 200
    }
    
    object SectMap {
        const val TILE_SIZE = 32
        const val WORLD_WIDTH_CELLS = 128
        const val WORLD_HEIGHT_CELLS = 128
        val WORLD_PIXEL_WIDTH = WORLD_WIDTH_CELLS * TILE_SIZE
        val WORLD_PIXEL_HEIGHT = WORLD_HEIGHT_CELLS * TILE_SIZE
        /** 地图边界不可建造区域厚度（格数）。必须 < 地图半宽。 */
        const val BORDER_TREE_RING = 3

        init {
            require(BORDER_TREE_RING >= 0 && BORDER_TREE_RING < WORLD_WIDTH_CELLS / 2) {
                "BORDER_TREE_RING($BORDER_TREE_RING) 必须在 [0, ${WORLD_WIDTH_CELLS / 2}) 范围内"
            }
        }
    }

    object WorldMap {
        const val MAP_WIDTH = 1698
        const val MAP_HEIGHT = 926
        const val SECT_RADIUS = 20
        const val MIN_DISTANCE = 34
        const val MAX_CONNECTION_DISTANCE = 200.0
        const val BORDER_PADDING = 34
        const val TARGET_SECT_COUNT = 80
        const val MAX_ATTEMPTS = 50000
        const val CONNECTION_DISTANCE_LIMIT = 280.0
        const val TARGET_CONNECTIONS_PER_SECT = 3
        const val MAX_CONNECTIONS_PER_SECT = 5
        const val MIN_CONNECTIONS_PER_SECT = 2
        const val RELAXATION_ITERATIONS = 5
        const val RELAXATION_STRENGTH = 0.4
        const val K_NEAREST_NEIGHBORS = 6
        const val CROSSING_PENALTY = 20.0
        const val CLUSTER_MIN_COUNT = 5
        const val CLUSTER_MAX_COUNT = 9
        const val CLUSTER_MIN_RADIUS = 70.0
        const val CLUSTER_MAX_RADIUS = 200.0
        const val ISOLATED_SECT_MIN = 5
        const val ISOLATED_SECT_MAX = 10
        const val MIN_SECT_DISTANCE = 28.0
        const val PATH_WAYPOINT_MIN = 2
        const val PATH_WAYPOINT_MAX = 4
        const val PATH_CURVE_STRENGTH = 0.05
        const val CAVE_MIN_SECT_DISTANCE = 28.0
        const val CAVE_MIN_PATH_DISTANCE = 20.0
        const val CAVE_MIN_CAVE_DISTANCE = 20.0
        const val LEVEL_MIN_DISTANCE = 20.0

        // 妖兽移动与攻击
        const val BEAST_MOVE_DISTANCE = 25.0     // 每月最大移动距离
        const val BEAST_ATTACK_RADIUS = 80.0     // 攻击触发最远距离
        const val BEAST_ATTACK_BASE_PROB = 0.20  // 距离0时的攻击概率
        const val BEAST_TRIBUTE_RATIO = 0.30     // 上交灵石比例 30%
        const val BEAST_TRIBUTE_MIN = 20_000L    // 最少上交 2 万灵石
        const val BEAST_LOOT_RATIO = 0.50        // 掠夺比例 50%
        const val BEAST_AI_ATTACK_BASE_CHANCE = 0.6  // AI妖兽基础攻击概率
        const val BEAST_AI_ATTACK_POWER_RATIO = 1.0  // AI妖兽攻击战力系数
        const val SPIRIT_STONES_PER_ITEM = 20_000L  // 2万灵石=1物品
    }

    /**
     * 远古秘境玩法配置。
     */
    object SecretRealm {
        const val SPAWN_PROBABILITY_PER_YEAR = 0.016      // 每年刷新概率 1.6%
        const val COOLDOWN_YEARS = 40                      // 探索结束后冷却 40 年
        const val REALM_MIN = 0                            // 境界下限（仙人）
        const val REALM_MAX = 9                            // 境界上限（炼气）
        const val BEAST_LAYER_VARIANT_COUNT = 9            // 妖兽层数方差档位（1..9 层）
        const val SPRITE_VARIANT_COUNT = 3                 // 秘境精灵图变体数量（cave_1..3）
        const val POSITION_ATTEMPTS = 100                  // 地图位置随机尝试次数
        const val FALLBACK_SCAN_STEP = 8                   // 兜底位置扫描步长（px）
        const val SECT_CLEARANCE = 20                      // 秘境与宗门的安全距离余量（px）
        const val STAMINA_MAX = 20                         // 初始体力上限
        const val STAMINA_COST_PER_CHOICE = 1              // 每选一个选项扣 1 体力
        const val TEAM_SIZE = 4                            // 探索队伍人数
        const val BEAST_COUNT_MIN = 1                      // 妖兽数量下限
        const val BEAST_COUNT_MAX = 6                      // 妖兽数量上限
        const val AMBUSH_BEAST_HP_REDUCTION = 0.10         // 偷袭成功：妖兽初始血量 -10%
        const val FLEE_DETECT_CHANCE = 0.30                // 选择"远离"被妖兽发现的概率
        const val AMBUSH_DETECT_CHANCE = 0.50              // 选择"偷袭"被妖兽发现的概率
        const val REST_AREA_CHANCE = 0.30                  // 衔接方向选择后出现空地事件的概率
        const val REST_RECOVERY_RATIO = 0.40               // 原地休整恢复最大生命值的比例
        const val LOOT_LOSS_MIN = 0.20                     // 战斗失败丢失物品比例下限
        const val LOOT_LOSS_MAX = 0.45                     // 战斗失败丢失物品比例上限
        const val AI_TEAM_SIZE = 4                         // AI 宗门队伍人数
    }

    /**
     * 免广告特权白名单。
     *
     * 在此列表中的 TapTap unionId 对应玩家跳过广告播放、
     * 无视冷却和每日次数限制。
     *
     * 由管理员手动维护，无需运行时修改。
     * 添加方式：`"目标用户的 unionId",`
     */
    object Whitelist {
        val AD_FREE_UNION_IDS: Set<String> = setOf(
            "Ck9z455SQZadDIwBueJvRQ==",
            "4FTGX7tp7MO1nr+j/Vwm5A==",
        )
    }
}
