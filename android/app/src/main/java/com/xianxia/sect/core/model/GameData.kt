package com.xianxia.sect.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xianxia.sect.core.GameConfig

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
    var sectPolicies: SectPolicies = SectPolicies(),

    // 战斗队伍（宗门地址上只能存在一支）
    var battleTeam: BattleTeam? = null,

    // AI战斗队伍
    var aiBattleTeams: List<AIBattleTeam> = emptyList(),

    // 已使用的兑换码列表
    var usedRedeemCodes: List<String> = emptyList(),

    // 玩家保护机制：AI宗门100年内不会攻击玩家宗门（若玩家主动攻击则解除）
    var playerProtectionEnabled: Boolean = true,
    var playerProtectionStartYear: Int = 1,
    var playerHasAttackedAI: Boolean = false
) {
    val displayTime: String get() = "第${gameYear}年${gameMonth}月"

    val isPlayerProtected: Boolean get() {
        if (!playerProtectionEnabled) return false
        if (playerHasAttackedAI) return false
        val elapsedYears = (gameYear - playerProtectionStartYear).coerceAtLeast(0)
        return elapsedYears < GameConfig.PlayerProtection.PROTECTION_YEARS
    }

    val playerProtectionRemainingYears: Int get() {
        if (!playerProtectionEnabled || playerHasAttackedAI) return 0
        val elapsedYears = (gameYear - playerProtectionStartYear).coerceAtLeast(0)
        return (GameConfig.PlayerProtection.PROTECTION_YEARS - elapsedYears).coerceAtLeast(0)
    }
}

// 宗门政策数据
data class SectPolicies(
    val spiritMineBoost: Boolean = false,
    val enhancedSecurity: Boolean = false,
    val alchemyIncentive: Boolean = false,
    val forgeIncentive: Boolean = false,
    val herbCultivation: Boolean = false,
    val cultivationSubsidy: Boolean = false,
    val manualResearch: Boolean = false
)

// 长老槽位数据
data class ElderSlots(
    val viceSectMaster: String? = null,
    val herbGardenElder: String? = null,
    val alchemyElder: String? = null,
    val forgeElder: String? = null,
    val libraryElder: String? = null,
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
    val recruitDisciples: List<DirectDiscipleSlot> = emptyList(),
    // 内门弟子槽位（天工峰8个）
    val forgeInnerDisciples: List<DirectDiscipleSlot> = emptyList(),
    // 内门弟子槽位（丹鼎殿8个）
    val alchemyInnerDisciples: List<DirectDiscipleSlot> = emptyList(),
    // 内门弟子槽位（灵药宛8个）
    val herbGardenInnerDisciples: List<DirectDiscipleSlot> = emptyList(),
    // 灵药宛储备弟子
    val herbGardenReserveDisciples: List<DirectDiscipleSlot> = emptyList(),
    // 丹鼎殿储备弟子
    val alchemyReserveDisciples: List<DirectDiscipleSlot> = emptyList(),
    // 天工峰储备弟子
    val forgeReserveDisciples: List<DirectDiscipleSlot> = emptyList(),
    // 灵矿执事槽位（灵矿场2个）
    val spiritMineDeaconDisciples: List<DirectDiscipleSlot> = emptyList()
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
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsedMonths = yearDiff * 12 + monthDiff
        return elapsedMonths >= growTime
    }

    fun remainingTime(currentYear: Int, currentMonth: Int): Int {
        if (status != "growing") return 0
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsedMonths = yearDiff * 12 + monthDiff
        return (growTime - elapsedMonths.toInt()).coerceAtLeast(0)
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
    val isRighteous: Boolean = true,
    val aiDisciples: List<AISectDisciple> = emptyList(),
    val isPlayerOccupied: Boolean = false,
    val occupierBattleTeamId: String? = null,
    val isUnderAttack: Boolean = false,
    val attackerSectId: String? = null,
    val occupierSectId: String? = null,
    val warehouse: SectWarehouse = SectWarehouse()
) {
    val discipleCountByRealm: Map<Int, Int> get() {
        if (aiDisciples.isEmpty()) return disciples
        val result = mutableMapOf<Int, Int>()
        for (realm in 0..9) {
            result[realm] = 0
        }
        aiDisciples.filter { it.isAlive }.forEach { disciple ->
            result[disciple.realm] = (result[disciple.realm] ?: 0) + 1
        }
        return result
    }
}

data class SectWarehouse(
    val items: List<WarehouseItem> = emptyList(),
    val spiritStones: Long = 0
)

data class WarehouseItem(
    val itemId: String = "",
    val itemName: String = "",
    val itemType: String = "",
    val rarity: Int = 1,
    val quantity: Int = 1,
    val itemData: Map<String, Any> = emptyMap()
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
    val aiDisciples: List<AISectDisciple> = emptyList(),
    val size: Int = 0,
    val currentX: Float = 0f,
    val currentY: Float = 0f,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val moveProgress: Float = 0f,
    val status: String = "moving",
    val startYear: Int = 0,
    val startMonth: Int = 0,
    val startDay: Int = 0,
    val duration: Int = 0,
    val arrivalYear: Int = 0,
    val arrivalMonth: Int = 0,
    val arrivalDay: Int = 0,
    val route: List<String> = emptyList(),
    val currentRouteIndex: Int = 0,
    val currentSegmentProgress: Float = 0f
) {
    val progress: Float get() = moveProgress
    val isArrived: Boolean get() = status == "arrived"
    val isMoving: Boolean get() = status == "moving"
    val isStationed: Boolean get() = status == "stationed"
}

data class BattleTeam(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "战斗队伍",
    val slots: List<BattleTeamSlot> = buildList {
        repeat(2) { index ->
            add(BattleTeamSlot(index, slotType = BattleSlotType.ELDER))
        }
        repeat(8) { index ->
            add(BattleTeamSlot(index + 2, slotType = BattleSlotType.DISCIPLE))
        }
    },
    val isAtSect: Boolean = true,
    val currentX: Float = 0f,
    val currentY: Float = 0f,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val status: String = "idle",
    val targetSectId: String? = null,
    val originSectId: String? = null,
    val route: List<String> = emptyList(),
    val currentRouteIndex: Int = 0,
    val moveProgress: Float = 0f,
    val isOccupying: Boolean = false,
    val occupiedSectId: String? = null,
    val isReturning: Boolean = false
) {
    val isFull: Boolean get() = slots.all { it.discipleId != null }
    val memberCount: Int get() = slots.count { it.discipleId != null }
    val isIdle: Boolean get() = status == "idle"
    val isMoving: Boolean get() = status == "moving"
    val isInBattle: Boolean get() = status == "battle"
    val isStationed: Boolean get() = status == "stationed"
    val aliveMemberCount: Int get() = slots.count { it.discipleId != null && it.isAlive }
}

enum class BattleSlotType {
    ELDER,
    DISCIPLE
}

data class BattleTeamSlot(
    val index: Int = 0,
    val discipleId: String? = null,
    val discipleName: String = "",
    val discipleRealm: String = "",
    val slotType: BattleSlotType = BattleSlotType.DISCIPLE,
    val isAlive: Boolean = true
) {
    val isActive: Boolean get() = discipleId != null
}

data class AIBattleTeam(
    val id: String = java.util.UUID.randomUUID().toString(),
    val attackerSectId: String = "",
    val attackerSectName: String = "",
    val defenderSectId: String = "",
    val defenderSectName: String = "",
    val disciples: List<AISectDisciple> = emptyList(),
    val currentX: Float = 0f,
    val currentY: Float = 0f,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val attackerStartX: Float = 0f,
    val attackerStartY: Float = 0f,
    val moveProgress: Float = 0f,
    val status: String = "moving",
    val route: List<String> = emptyList(),
    val currentRouteIndex: Int = 0,
    val startYear: Int = 0,
    val startMonth: Int = 0,
    val isPlayerDefender: Boolean = false
)
