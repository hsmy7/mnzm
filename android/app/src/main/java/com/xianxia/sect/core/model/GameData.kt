package com.xianxia.sect.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_data")
data class GameData(
    @PrimaryKey
    var id: String = "game_data",
    var sectName: String = "青云宗",
    var currentSlot: Int = 1,
    
    // 游戏时间
    var gameYear: Int = 1,
    var gameMonth: Int = 1,
    var gameDay: Int = 1,
    
    // 资源
    var spiritStones: Long = 1000,
    var spiritHerbs: Int = 0,
    
    // 自动存档设置（月数：3, 6, 12）
    var autoSaveIntervalMonths: Int = 3,
    
    // 月俸配置
    var monthlySalary: Map<Int, Int> = mapOf(
        9 to 20,   // 练气
        8 to 60,   // 筑基
        7 to 100,  // 金丹
        6 to 160,  // 元婴
        5 to 220,  // 化神
        4 to 360,  // 炼虚
        3 to 440,  // 合体
        2 to 560,  // 大乘
        1 to 720,  // 渡劫
        0 to 1000  // 仙人
    ),
    
    // 月俸发放开关（按境界）
    var monthlySalaryEnabled: Map<Int, Boolean> = mapOf(
        9 to true,
        8 to true,
        7 to true,
        6 to true,
        5 to true,
        4 to true,
        3 to true,
        2 to true,
        1 to true,
        0 to true
    ),
    
    // 战堂队伍
    var warTeams: List<WarTeam> = emptyList(),
    
    // 世界地图宗门
    var worldMapSects: List<WorldSect> = emptyList(),
    
    // 已探索宗门信息
    var exploredSects: Map<String, ExploredSectInfo> = emptyMap(),
    
    // 宗门侦查信息
    var scoutInfo: Map<String, SectScoutInfo> = emptyMap(),
    
    // 灵药园种植槽位
    var herbGardenPlantSlots: List<PlantSlotData> = emptyList(),
    
    // 功法熟练度
    var manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    
    // 旅行商人
    var travelingMerchantItems: List<MerchantItem> = emptyList(),
    var merchantLastRefreshYear: Int = 0,
    var merchantRefreshCount: Int = 0,
    
    // 玩家上架商品
    var playerListedItems: List<MerchantItem> = emptyList(),
    
    // 交易堂
    var tradingSellList: List<TradingItem> = emptyList(),
    var tradingBuyList: List<TradingItem> = emptyList(),
    
    // 弟子招募
    var recruitList: List<Disciple> = emptyList(),
    var lastRecruitYear: Int = 0,
    
    // 大比
    var tournamentLastYear: Int = 0,
    // 外门弟子大比（每3年一次，前20名晋升内门弟子）
    var outerTournamentLastYear: Int = 0,
    var tournamentRewards: Map<Int, TournamentReward> = mapOf(
        9 to TournamentReward(9, true, 0, 100, 50, 0, 0, emptyList(), emptyList(), "练气期奖励"),
        8 to TournamentReward(8, true, 0, 200, 100, 0, 0, emptyList(), emptyList(), "筑基期奖励"),
        7 to TournamentReward(7, true, 0, 500, 250, 0, 0, emptyList(), emptyList(), "金丹期奖励"),
        6 to TournamentReward(6, true, 0, 1000, 500, 0, 0, emptyList(), emptyList(), "元婴期奖励"),
        5 to TournamentReward(5, true, 0, 2000, 1000, 0, 0, emptyList(), emptyList(), "化神期奖励"),
        4 to TournamentReward(4, true, 0, 5000, 2500, 0, 0, emptyList(), emptyList(), "炼虚期奖励"),
        3 to TournamentReward(3, true, 0, 10000, 5000, 0, 0, emptyList(), emptyList(), "合体期奖励"),
        2 to TournamentReward(2, true, 0, 20000, 10000, 0, 0, emptyList(), emptyList(), "大乘期奖励"),
        1 to TournamentReward(1, true, 0, 50000, 25000, 0, 0, emptyList(), emptyList(), "渡劫期奖励")
    ),
    var tournamentAutoHold: Boolean = false,
    var tournamentRealmEnabled: Map<Int, Boolean> = mapOf(
        9 to true,
        8 to true,
        7 to true,
        6 to false,
        5 to false,
        4 to false,
        3 to false,
        2 to false,
        1 to false,
        0 to false
    ),

    // 修士洞府
    var cultivatorCaves: List<CultivatorCave> = emptyList(),

    // 洞府探索队伍
    var caveExplorationTeams: List<CaveExplorationTeam> = emptyList(),

    // AI洞府探索队伍
    var aiCaveTeams: List<AICaveTeam> = emptyList(),

    // 解锁的副本
    var unlockedDungeons: List<String> = emptyList(),

    // 解锁的配方
    var unlockedRecipes: List<String> = emptyList(),

    // 解锁的功法
    var unlockedManuals: List<String> = emptyList(),

    // 最后保存时间
    var lastSaveTime: Long = System.currentTimeMillis(),
    
    // 长老槽位
    var elderSlots: ElderSlots = ElderSlots(),
    
    // 灵矿槽位（独立3个）
    var spiritMineSlots: List<SpiritMineSlot> = emptyList(),
    
    // 藏经阁弟子槽位（独立3个）
    var librarySlots: List<LibrarySlot> = emptyList(),
    
    // 结盟关系
    var alliances: List<Alliance> = emptyList(),
    
    // AI 宗门间关系
    var sectRelations: List<SectRelation> = emptyList(),
    
    // 玩家最大结盟数量
    var playerAllianceSlots: Int = 3,
    
    // 支援队伍
    var supportTeams: List<SupportTeam> = emptyList(),

    // 宗门政策
    var sectPolicies: SectPolicies = SectPolicies()
) {
    val displayTime: String get() = "第${gameYear}年${gameMonth}月"
}

// 宗门政策数据
data class SectPolicies(
    val spiritMineBoost: Boolean = false,
    val enhancedSecurity: Boolean = false
)

// 长老槽位数据
data class ElderSlots(
    val viceSectMaster: String? = null,
    val herbGardenElder: String? = null,
    val alchemyElder: String? = null,
    val forgeElder: String? = null,
    val libraryElder: String? = null,
    val spiritMineElder: String? = null,
    val recruitElder: String? = null,
    // 问道峰相关槽位
    val outerElder: String? = null,
    val preachingElder: String? = null,
    val preachingMasters: List<DirectDiscipleSlot> = emptyList(),
    // 执法堂相关槽位
    val lawEnforcementElder: String? = null,
    val lawEnforcementDisciples: List<DirectDiscipleSlot> = emptyList(),
    val lawEnforcementReserveDisciples: List<DirectDiscipleSlot> = emptyList(),
    // 青云峰相关槽位
    val innerElder: String? = null,
    val qingyunPreachingElder: String? = null,
    val qingyunPreachingMasters: List<DirectDiscipleSlot> = emptyList(),
    // 每个长老的亲传弟子槽位（每个长老 2 个）
    val herbGardenDisciples: List<DirectDiscipleSlot> = emptyList(),
    val alchemyDisciples: List<DirectDiscipleSlot> = emptyList(),
    val forgeDisciples: List<DirectDiscipleSlot> = emptyList(),
    val libraryDisciples: List<DirectDiscipleSlot> = emptyList(),
    val spiritMineDisciples: List<DirectDiscipleSlot> = emptyList(),
    val recruitDisciples: List<DirectDiscipleSlot> = emptyList(),
    // 内门弟子槽位（天工峰8个）
    val forgeInnerDisciples: List<DirectDiscipleSlot> = emptyList(),
    // 内门弟子槽位（丹鼎殿8个）
    val alchemyInnerDisciples: List<DirectDiscipleSlot> = emptyList(),
    // 内门弟子槽位（灵药宛8个）
    val herbGardenInnerDisciples: List<DirectDiscipleSlot> = emptyList()
)

// 亲传弟子槽位数据
data class DirectDiscipleSlot(
    val index: Int = 0,
    val discipleId: String? = null,
    val discipleName: String = "",
    val discipleRealm: String = "",
    val discipleSpiritRootColor: String = "#E0E0E0"
) {
    val isActive: Boolean get() = discipleId != null
}

// 种植槽位数据
data class PlantSlotData(
    val index: Int = 0,
    val status: String = "idle", // idle, growing, mature
    val seedId: String? = null,
    val seedName: String = "",
    val startYear: Int = 0,
    val startMonth: Int = 0,
    val growTime: Int = 0, // 生长所需月数
    val expectedYield: Int = 0, // 预期产量
    val harvestAmount: Int = 0,
    val harvestHerbId: String? = null
) {
    val isGrowing: Boolean get() = status == "growing"
    val isFinished: Boolean get() = status == "mature"
    val isIdle: Boolean get() = status == "idle"

    fun isFinished(currentYear: Int, currentMonth: Int): Boolean {
        if (status != "growing") return status == "mature"
        val elapsedMonths = (currentYear - startYear) * 12 + (currentMonth - startMonth)
        return elapsedMonths >= growTime
    }

    fun remainingTime(currentYear: Int, currentMonth: Int): Int {
        if (status != "growing") return 0
        val elapsedMonths = (currentYear - startYear) * 12 + (currentMonth - startMonth)
        return (growTime - elapsedMonths).coerceAtLeast(0)
    }
}

// 商人商品
data class MerchantItem(
    val id: String = "",
    val name: String = "",
    val type: String = "", // equipment, manual, pill, material, seed
    val itemId: String = "",
    val rarity: Int = 1,
    val price: Int = 0,
    val quantity: Int = 1,
    val description: String = "",
    val data: Any? = null,
    val obtainedYear: Int = 0,
    val obtainedMonth: Int = 0
)

// 交易堂物品
data class TradingItem(
    val id: String = "",
    val itemId: String = "",
    val name: String = "",
    val type: String = "", // equipment, manual, pill
    val rarity: Int = 1,
    val price: Int = 0,
    val quantity: Int = 1
)

// 大比物品奖励
data class TournamentItemReward(
    val id: String = "",
    val name: String = "",
    val type: String = "" // equipment, manual, pill, material
)

// 大比奖励
data class TournamentReward(
    val realm: Int = 9,
    val enabled: Boolean = true,
    val spiritStones: Int = 0,
    val championSpiritStones: Int = 0,
    val runnerUpSpiritStones: Int = 0,
    val semifinalSpiritStones: Int = 0,
    val participantSpiritStones: Int = 0,
    val championItems: List<TournamentItemReward> = emptyList(),
    val runnerUpItems: List<TournamentItemReward> = emptyList(),
    val description: String = ""
)

// 游戏设置数据
data class GameSettingsData(
    val soundEnabled: Boolean = true,
    val musicEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val autoSave: Boolean = true,
    val language: String = "zh"
)

// 功法熟练度数据
data class ManualProficiencyData(
    val manualId: String = "",
    val manualName: String = "",
    val proficiency: Double = 0.0,
    val maxProficiency: Int = 100,
    val level: Int = 1,
    val masteryLevel: Int = 0
)

// 矿脉槽位
data class MineSlot(
    val index: Int = 0,
    val discipleId: String? = null,
    val discipleName: String = "",
    val output: Int = 0,
    val efficiency: Double = 1.0,
    val isActive: Boolean = false
)

// 队伍状态
enum class TeamStatus {
    IDLE,
    EXPLORING,
    RETURNING,
    COMPLETED;

    val displayName: String get() = when (this) {
        IDLE -> "待命"
        EXPLORING -> "探索中"
        RETURNING -> "返回中"
        COMPLETED -> "已完成"
    }
}

// 世界宗门
data class WorldSect(
    val id: String = "",
    val name: String = "",
    val level: Int = 1,
    val levelName: String = "小型宗门",
    val x: Float = 0f,
    val y: Float = 0f,
    val distance: Int = 0,
    val isPlayerSect: Boolean = false,
    val discovered: Boolean = false,
    val isKnown: Boolean = false,
    val relation: Int = 0,
    val disciples: Map<Int, Int> = emptyMap(),
    val maxRealm: Int = 9,
    val connectedSectIds: List<String> = emptyList(),
    @Transient val connectedSects: List<WorldSect> = emptyList(),
    val isOccupied: Boolean = false,
    val occupierTeamId: String? = null,
    val occupierTeamName: String = "",
    val mineSlots: List<MineSlot> = emptyList(),
    val occupationTime: Long = 0,
    val isOwned: Boolean = false,
    val expiryYear: Int = 0,
    val expiryMonth: Int = 0,
    val scoutInfo: SectScoutInfo? = null,
    val tradeItems: List<MerchantItem> = emptyList(),
    val tradeLastRefreshYear: Int = 0,
    val lastGiftYear: Int = 0,
    val allianceId: String? = null,
    val allianceStartYear: Int = 0,
    val isRighteous: Boolean = true
)

// 已探索宗门信息
data class ExploredSectInfo(
    val sectId: String = "",
    val sectName: String = "",
    val year: Int = 0,
    val month: Int = 0,
    val discipleCount: Int = 0,
    val maxRealm: Int = 9
)

// 宗门侦查信息
data class SectScoutInfo(
    val sectId: String = "",
    val sectName: String = "",
    val scoutYear: Int = 0,
    val scoutMonth: Int = 0,
    val discipleCount: Int = 0,
    val maxRealm: Int = 9,
    val resources: Map<String, Int> = emptyMap(),
    val isKnown: Boolean = false,
    val disciples: Map<Int, Int> = emptyMap(),
    val expiryYear: Int = 0,
    val expiryMonth: Int = 0
)

// 灵矿槽位
data class SpiritMineSlot(
    val index: Int = 0,
    val discipleId: String? = null,
    val discipleName: String = "",
    val output: Int = 100
) {
    val isActive: Boolean get() = discipleId != null
}

// 藏经阁弟子槽位
data class LibrarySlot(
    val index: Int = 0,
    val discipleId: String? = null,
    val discipleName: String = "",
    val efficiency: Double = 1.5
) {
    val isActive: Boolean get() = discipleId != null
}

data class Alliance(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sectIds: List<String> = emptyList(),
    val startYear: Int = 0,
    val initiatorId: String = "",
    val envoyDiscipleId: String = ""
)

/**
 * AI 宗门间关系
 */
data class SectRelation(
    val sectId1: String,
    val sectId2: String,
    var favor: Int = 30,
    var lastInteractionYear: Int = 0
)

data class SupportTeam(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val sourceSectId: String = "",
    val sourceSectName: String = "",
    val targetSectId: String = "player",
    val disciples: List<String> = emptyList(),
    val size: Int = 0,
    val currentX: Float = 0f,
    val currentY: Float = 0f,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val progress: Float = 0f,
    val status: String = "moving",
    val startYear: Int = 0,
    val startMonth: Int = 0,
    val arrivalYear: Int = 0,
    val arrivalMonth: Int = 0
) {
    val isArrived: Boolean get() = status == "arrived"
    val isMoving: Boolean get() = status == "moving"
}
