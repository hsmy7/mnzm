package com.xianxia.sect.core.config

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.GameConfig.BeastTypeConfig
import com.xianxia.sect.core.GameConfig.DungeonConfig
import com.xianxia.sect.core.GameConfig.RealmConfig
import com.xianxia.sect.core.GameConfig.RarityConfig
import com.xianxia.sect.core.GameConfig.SpiritRootConfig
import com.xianxia.sect.core.model.*

/**
 * 配置加载器 - 外部化配置基础设施
 *
 * 提供统一的配置加载接口，当前采用桥接模式委托给 GameConfig 读取。
 * 未来可扩展为从 JSON/assets/网络加载配置，实现配置的外部化和热更新。
 */
object ConfigLoader {
    private const val DEFAULT_CONFIG_VERSION = 1

    /**
     * 加载游戏配置数据
     *
     * 当前实现：从 GameConfig 读取（桥接模式）
     * 未来可扩展：
     * - 从 assets/json 文件加载
     * - 从远程服务器加载
     * - 支持配置版本管理和热更新
     *
     * @return 包含所有配置数据的 GameConfigData 对象
     */
    fun load(): GameConfigData {
        return GameConfigData(
            version = DEFAULT_CONFIG_VERSION,
            // 基础游戏配置
            gameConfig = GameConfigData.GameConfig(
                name = GameConfig.Game.NAME,
                version = GameConfig.Game.VERSION,
                autoSaveIntervalSeconds = GameConfig.Game.AUTO_SAVE_INTERVAL_SECONDS,
                maxSaveSlots = GameConfig.Game.MAX_SAVE_SLOTS
            ),
            // 弟子相关配置
            discipleConfig = GameConfigData.DiscipleConfig(
                maxDisciples = GameConfig.Disciple.MAX_DISCIPLES,
                recruitCost = GameConfig.Disciple.RECRUIT_COST,
                minAge = GameConfig.Disciple.MIN_AGE,
                maxAge = GameConfig.Disciple.MAX_AGE
            ),
            // 时间系统配置
            timeConfig = GameConfigData.TimeConfig(
                tickInterval = GameConfig.Time.TICK_INTERVAL,
                ticksPerSecond = GameConfig.Time.TICKS_PER_SECOND,
                daysPerMonth = GameConfig.Time.DAYS_PER_MONTH,
                monthsPerYear = GameConfig.Time.MONTHS_PER_YEAR
            ),
            // 境界配置
            realmConfigs = GameConfig.Realm.CONFIGS,
            // 稀有度配置
            rarityConfigs = GameConfig.Rarity.CONFIGS,
            // 灵根配置
            spiritRootConfigs = GameConfigData.SpiritRootConfigData(
                elements = GameConfig.SpiritRoot.ELEMENTS,
                types = GameConfig.SpiritRoot.TYPES,
                countWeights = GameConfig.SpiritRoot.COUNT_WEIGHTS
            ),
            // 妖兽类型配置
            beastTypeConfigs = GameConfig.Beast.TYPES,
            // 地牢配置
            dungeonConfigs = GameConfig.Dungeons.CONFIGS,
            // 战斗配置
            battleConfig = GameConfigData.BattleConfig(
                maxTeamSize = GameConfig.Battle.MAX_TEAM_SIZE,
                minBeastCount = GameConfig.Battle.MIN_BEAST_COUNT,
                maxBeastCount = GameConfig.Battle.MAX_BEAST_COUNT,
                maxTurns = GameConfig.Battle.MAX_TURNS,
                critMultiplier = GameConfig.Battle.CRIT_MULTIPLIER
            ),
            // AI 配置
            aiConfig = GameConfigData.AIConfig(
                minDisciplesForAttack = GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK,
                powerRatioThreshold = GameConfig.AI.POWER_RATIO_THRESHOLD,
                teamSize = GameConfig.AI.TEAM_SIZE
            ),
            // 世界地图配置
            worldMapConfig = GameConfigData.WorldMapConfig(
                mapWidth = GameConfig.WorldMap.MAP_WIDTH,
                mapHeight = GameConfig.WorldMap.MAP_HEIGHT,
                targetSectCount = GameConfig.WorldMap.TARGET_SECT_COUNT
            ),
            // 外交配置
            diplomacyConfig = GameConfigData.DiplomacyConfig(
                minAllianceFavor = GameConfig.Diplomacy.MIN_ALLIANCE_FAVOR,
                allianceDurationYears = GameConfig.Diplomacy.ALLIANCE_DURATION_YEARS,
                diplomaticEventChance = GameConfig.Diplomacy.DIPLOMATIC_EVENT_CHANCE
            )
        )
    }
}

/**
 * 游戏配置数据容器
 *
 * 统一的数据结构，用于承载从不同来源加载的配置数据。
 * 支持未来从 JSON、数据库或远程服务器加载配置。
 */
data class GameConfigData(
    val version: Int,
    
    // 基础游戏配置
    val gameConfig: GameConfig,
    
    // 弟子配置
    val discipleConfig: DiscipleConfig,
    
    // 时间系统配置
    val timeConfig: TimeConfig,
    
    // 境界配置映射 (level -> RealmConfig)
    val realmConfigs: Map<Int, RealmConfig>,
    
    // 稀有度配置映射 (level -> RarityConfig)
    val rarityConfigs: Map<Int, RarityConfig>,
    
    // 灵根配置
    val spiritRootConfigs: SpiritRootConfigData,
    
    val beastTypeConfigs: List<BeastTypeConfig>,
    
    // 地牢配置映射 (id -> DungeonConfig)
    val dungeonConfigs: Map<String, DungeonConfig>,
    
    // 战斗配置
    val battleConfig: BattleConfig,
    
    // AI 配置
    val aiConfig: AIConfig,
    
    // 世界地图配置
    val worldMapConfig: WorldMapConfig,
    
    // 外交配置
    val diplomacyConfig: DiplomacyConfig
) {
    /**
     * 基础游戏配置
     */
    data class GameConfig(
        val name: String,
        val version: String,
        val autoSaveIntervalSeconds: Long,
        val maxSaveSlots: Int
    )
    
    /**
     * 弟子系统配置
     */
    data class DiscipleConfig(
        val maxDisciples: Int,
        val recruitCost: Long,
        val minAge: Int,
        val maxAge: Int
    )
    
    /**
     * 时间系统配置
     */
    data class TimeConfig(
        val tickInterval: Long,
        val ticksPerSecond: Int,
        val daysPerMonth: Int,
        val monthsPerYear: Int
    )
    
    /**
     * 灵根配置数据
     */
    data class SpiritRootConfigData(
        val elements: List<String>,
        val types: Map<String, SpiritRootConfig>,
        val countWeights: Map<Int, Double>
    )
    
    /**
     * 战斗系统配置
     */
    data class BattleConfig(
        val maxTeamSize: Int,
        val minBeastCount: Int,
        val maxBeastCount: Int,
        val maxTurns: Int,
        val critMultiplier: Double
    )
    
    /**
     * AI 系统配置
     */
    data class AIConfig(
        val minDisciplesForAttack: Int,
        val powerRatioThreshold: Double,
        val teamSize: Int
    )
    
    /**
     * 世界地图配置
     */
    data class WorldMapConfig(
        val mapWidth: Int,
        val mapHeight: Int,
        val targetSectCount: Int
    )
    
    /**
     * 外交系统配置
     */
    data class DiplomacyConfig(
        val minAllianceFavor: Int,
        val allianceDurationYears: Int,
        val diplomaticEventChance: Double
    )
}
