@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
@Entity(
    tableName = "game_data",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["slot_id"], unique = true)
    ]
)
data class GameData(
    @ColumnInfo(name = "id")
    var id: String = "",

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    var sectName: String = "青云宗",
    var currentSlot: Int = 1,

    // 游戏时间
    var gameYear: Int = 1,
    var gameMonth: Int = 1,
    var gameDay: Int = 1,

    // 游戏状态
    var isGameStarted: Boolean = false,
    var gameSpeed: Int = 1,

    // 资源
    var spiritStones: Long = 1000,
    var spiritHerbs: Int = 0,
    var sectCultivation: Double = 0.0,

    // 自动存档设置（月数，0为停止）
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

    // 弟子招募（存储完整弟子对象，仅包含可招募但未正式招募的弟子）
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
    var lastSaveTime: Long = 0L,

    // 长老槽位
    var elderSlots: ElderSlots = ElderSlots(),

    // 灵矿槽位（独立3个）
    var spiritMineSlots: List<SpiritMineSlot> = emptyList(),

    // 藏经阁弟子槽位（独立3个）
    var librarySlots: List<LibrarySlot> = emptyList(),

    // 炼器槽位
    var forgeSlots: List<BuildingSlot> = emptyList(),

    // 炼丹槽位
    var alchemySlots: List<AlchemySlot> = emptyList(),

    // 统一生产槽位（新架构）
    var productionSlots: List<ProductionSlot> = emptyList(),

    // 结盟关系
    var alliances: List<Alliance> = emptyList(),

    // AI 宗门间关系
    var sectRelations: List<SectRelation> = emptyList(),

    // 玩家最大结盟数量
    var playerAllianceSlots: Int = 3,

    // 宗门政策
    var sectPolicies: SectPolicies = SectPolicies(),

    // 战斗队伍（宗门地址上只能存在一支）
    var battleTeam: BattleTeam? = null,

    // AI战斗队伍
    var aiBattleTeams: List<AIBattleTeam> = emptyList(),

    // 已使用的兑换码列表（使用 LinkedHashSet 去重 + 上限保护）
    var usedRedeemCodes: List<String> = emptyList(),

    // 玩家保护机制：AI宗门100年内不会攻击玩家宗门（若玩家主动攻击则解除）
    var playerProtectionEnabled: Boolean = true,
    var playerProtectionStartYear: Int = 1,
    var playerHasAttackedAI: Boolean = false,

    // 任务阁系统
    var activeMissions: List<ActiveMission> = emptyList(),
    var availableMissions: List<Mission> = emptyList(),

    // 外门大比系统
    var pendingCompetitionResults: List<CompetitionRankResult> = emptyList(),
    var lastCompetitionYear: Int = 0
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

    // ==================== 组合子状态聚合属性 ====================
    // 以下属性提供按领域分组的数据访问，可用于批量操作或局部 copy。
    // 现有代码可继续直接访问各字段（如 gameData.forgeSlots），完全兼容。

    /** 世界地图与外交状态聚合 */
    val worldMap: WorldMapState get() = WorldMapState(
        worldMapSects = worldMapSects,
        exploredSects = exploredSects,
        scoutInfo = scoutInfo,
        sectRelations = sectRelations
    )

    /** 建筑与槽位状态聚合 */
    val buildings: BuildingState get() = BuildingState(
        herbGardenPlantSlots = herbGardenPlantSlots,
        forgeSlots = forgeSlots,
        alchemySlots = alchemySlots,
        productionSlots = productionSlots,
        spiritMineSlots = spiritMineSlots,
        librarySlots = librarySlots
    )

    /** 经济与交易状态聚合 */
    val economy: EconomicState get() = EconomicState(
        travelingMerchantItems = travelingMerchantItems,
        merchantLastRefreshYear = merchantLastRefreshYear,
        merchantRefreshCount = merchantRefreshCount,
        playerListedItems = playerListedItems
    )

    /** 宗门组织架构状态聚合 */
    val organization: SectOrganizationState get() = SectOrganizationState(
        elderSlots = elderSlots,
        alliances = alliances,
        battleTeam = battleTeam,
        aiBattleTeams = aiBattleTeams,
        sectPolicies = sectPolicies,
        activeMissions = activeMissions,
        availableMissions = availableMissions,
        usedRedeemCodes = usedRedeemCodes
    )

    /** 探索与弟子管理状态聚合 */
    val exploration: ExplorationState get() = ExplorationState(
        recruitList = recruitList,
        lastRecruitYear = lastRecruitYear,
        cultivatorCaves = cultivatorCaves,
        caveExplorationTeams = caveExplorationTeams,
        aiCaveTeams = aiCaveTeams,
        unlockedDungeons = unlockedDungeons,
        unlockedRecipes = unlockedRecipes,
        unlockedManuals = unlockedManuals,
        manualProficiencies = manualProficiencies,
        pendingCompetitionResults = pendingCompetitionResults,
        lastCompetitionYear = lastCompetitionYear
    )

    /**
     * 从子状态创建副本，用于批量更新某个领域的多个字段。
     * 示例: gd.withWorldMap(gd.worldMap.copy(worldMapSects = newSects))
     */
    fun withWorldMap(state: WorldMapState): GameData = this.copy(
        worldMapSects = state.worldMapSects,
        exploredSects = state.exploredSects,
        scoutInfo = state.scoutInfo,
        sectRelations = state.sectRelations
    )

    fun withBuildings(state: BuildingState): GameData = this.copy(
        herbGardenPlantSlots = state.herbGardenPlantSlots,
        forgeSlots = state.forgeSlots,
        alchemySlots = state.alchemySlots,
        productionSlots = state.productionSlots,
        spiritMineSlots = state.spiritMineSlots,
        librarySlots = state.librarySlots
    )

    fun withEconomy(state: EconomicState): GameData = this.copy(
        travelingMerchantItems = state.travelingMerchantItems,
        merchantLastRefreshYear = state.merchantLastRefreshYear,
        merchantRefreshCount = state.merchantRefreshCount,
        playerListedItems = state.playerListedItems
    )

    fun withOrganization(state: SectOrganizationState): GameData = this.copy(
        elderSlots = state.elderSlots,
        alliances = state.alliances,
        battleTeam = state.battleTeam,
        aiBattleTeams = state.aiBattleTeams,
        sectPolicies = state.sectPolicies,
        activeMissions = state.activeMissions,
        availableMissions = state.availableMissions,
        usedRedeemCodes = state.usedRedeemCodes
    )

    fun withExploration(state: ExplorationState): GameData = this.copy(
        recruitList = state.recruitList,
        lastRecruitYear = state.lastRecruitYear,
        cultivatorCaves = state.cultivatorCaves,
        caveExplorationTeams = state.caveExplorationTeams,
        aiCaveTeams = state.aiCaveTeams,
        unlockedDungeons = state.unlockedDungeons,
        unlockedRecipes = state.unlockedRecipes,
        unlockedManuals = state.unlockedManuals,
        manualProficiencies = state.manualProficiencies,
        pendingCompetitionResults = state.pendingCompetitionResults,
        lastCompetitionYear = state.lastCompetitionYear
    )

    companion object {
        const val MAX_REDEEM_CODES = 500
    }
}

// 宗门政策数据
@Serializable
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
@Serializable
data class ElderSlots(
    val viceSectMaster: String = "",
    val herbGardenElder: String = "",
    val alchemyElder: String = "",
    val forgeElder: String = "",
    val outerElder: String = "",
    val preachingElder: String = "",
    val preachingMasters: List<DirectDiscipleSlot> = emptyList(),
    val lawEnforcementElder: String = "",
    val lawEnforcementDisciples: List<DirectDiscipleSlot> = emptyList(),
    val lawEnforcementReserveDisciples: List<DirectDiscipleSlot> = emptyList(),
    val innerElder: String = "",
    val qingyunPreachingElder: String = "",
    val qingyunPreachingMasters: List<DirectDiscipleSlot> = emptyList(),
    val herbGardenDisciples: List<DirectDiscipleSlot> = emptyList(),
    val alchemyDisciples: List<DirectDiscipleSlot> = emptyList(),
    val forgeDisciples: List<DirectDiscipleSlot> = emptyList(),
    val herbGardenReserveDisciples: List<DirectDiscipleSlot> = emptyList(),
    val alchemyReserveDisciples: List<DirectDiscipleSlot> = emptyList(),
    val forgeReserveDisciples: List<DirectDiscipleSlot> = emptyList(),
    val spiritMineDeaconDisciples: List<DirectDiscipleSlot> = emptyList()
) {
    fun isDiscipleInAnyPosition(discipleId: String): Boolean {
        if (viceSectMaster == discipleId) return true
        
        val allElderIds = listOf(
            herbGardenElder, alchemyElder, forgeElder,
            outerElder, preachingElder, lawEnforcementElder,
            innerElder, qingyunPreachingElder
        )
        if (allElderIds.contains(discipleId)) return true
        
        val allDirectDiscipleIds = listOf(
            herbGardenDisciples, alchemyDisciples, forgeDisciples,
            preachingMasters, lawEnforcementDisciples, lawEnforcementReserveDisciples,
            qingyunPreachingMasters, spiritMineDeaconDisciples,
            alchemyReserveDisciples, herbGardenReserveDisciples, forgeReserveDisciples
        ).flatten().mapNotNull { it.discipleId }
        
        return allDirectDiscipleIds.contains(discipleId)
    }
}

// 亲传弟子槽位数据
@Serializable
data class DirectDiscipleSlot(
    val index: Int = 0,
    val discipleId: String = "",
    val discipleName: String = "",
    val discipleRealm: String = "",
    val discipleSpiritRootColor: String = "#E0E0E0"
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

// 种植槽位数据
@Serializable
data class PlantSlotData(
    val index: Int = 0,
    val status: String = "idle",
    val seedId: String = "",
    val seedName: String = "",
    val startYear: Int = 0,
    val startMonth: Int = 0,
    val growTime: Int = 0,
    val expectedYield: Int = 0,
    val harvestAmount: Int = 0,
    val harvestHerbId: String = ""
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

    companion object {
        const val MAX_AI_DISCIPLES_PER_SECT = 1000
    }
}

// 商人商品
@Serializable
data class MerchantItem(
    val id: String = "",
    val name: String = "",
    val type: String = "", // equipment, manual, pill, material, seed
    val itemId: String = "",
    val rarity: Int = 1,
    val price: Int = 0,
    val quantity: Int = 1,
    val description: String = "",
    val obtainedYear: Int = 0,
    val obtainedMonth: Int = 0
)

// 游戏设置数据
@Serializable
data class GameSettingsData(
    val soundEnabled: Boolean = true,
    val musicEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val autoSave: Boolean = true,
    val language: String = "zh"
)

// 功法熟练度数据
@Serializable
data class ManualProficiencyData(
    val manualId: String = "",
    val manualName: String = "",
    val proficiency: Double = 0.0,
    val maxProficiency: Int = 100,
    val level: Int = 1,
    val masteryLevel: Int = 0
)

// 矿脉槽位
@Serializable
data class MineSlot(
    val index: Int = 0,
    val discipleId: String = "",
    val discipleName: String = "",
    val output: Int = 0,
    val efficiency: Double = 1.0,
    val isActive: Boolean = false
)

// 队伍状态
@Serializable
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
@Serializable
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
    val occupierTeamId: String = "",
    val occupierTeamName: String = "",
    val mineSlots: List<MineSlot> = emptyList(),
    val occupationTime: Long = 0,
    val isOwned: Boolean = false,
    val expiryYear: Int = 0,
    val expiryMonth: Int = 0,
    val scoutInfo: SectScoutInfo = SectScoutInfo(),
    val tradeItems: List<MerchantItem> = emptyList(),
    val tradeLastRefreshYear: Int = 0,
    val lastGiftYear: Int = 0,
    val allianceId: String = "",
    val allianceStartYear: Int = 0,
    val isRighteous: Boolean = true,
    val aiDisciples: List<Disciple> = emptyList(),
    val isPlayerOccupied: Boolean = false,
    val occupierBattleTeamId: String = "",
    val isUnderAttack: Boolean = false,
    val attackerSectId: String = "",
    val occupierSectId: String = "",
    val warehouse: SectWarehouse = SectWarehouse(),
    val giftPreference: GiftPreferenceType = GiftPreferenceType.NONE
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

@Serializable
data class SectWarehouse(
    val items: List<WarehouseItem> = emptyList(),
    val spiritStones: Long = 0
)

@Serializable
data class WarehouseItem(
    val itemId: String = "",
    val itemName: String = "",
    val itemType: String = "",
    val rarity: Int = 1,
    val quantity: Int = 1
)

// 已探索宗门信息
@Serializable
data class ExploredSectInfo(
    val sectId: String = "",
    val sectName: String = "",
    val year: Int = 0,
    val month: Int = 0,
    val duration: Int = 0,
    val memberIds: List<String> = emptyList(),
    val memberNames: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    val rewards: List<String> = emptyList(),
    val battleCount: Int = 0,
    val casualties: Int = 0,
    val discipleCount: Int = 0,
    val maxRealm: Int = 9
)

// 宗门侦查信息
@Serializable
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
@Serializable
data class SpiritMineSlot(
    val index: Int = 0,
    val discipleId: String = "",
    val discipleName: String = "",
    val output: Int = 100
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

@Serializable
data class LibrarySlot(
    val index: Int = 0,
    val discipleId: String = "",
    val discipleName: String = ""
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

@Serializable
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
@Serializable
data class SectRelation(
    val sectId1: String,
    val sectId2: String,
    var favor: Int = GameConfig.WorldMap.INITIAL_SECT_FAVOR,
    var lastInteractionYear: Int = 0,
    var noGiftYears: Int = 0
)

@Serializable
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
    val targetSectId: String = "",
    val originSectId: String = "",
    val route: List<String> = emptyList(),
    val currentRouteIndex: Int = 0,
    val moveProgress: Float = 0f,
    val isOccupying: Boolean = false,
    val occupiedSectId: String = "",
    val isReturning: Boolean = false
) {
    val isFull: Boolean get() = slots.all { it.discipleId.isNotEmpty() }
    val memberCount: Int get() = slots.count { it.discipleId.isNotEmpty() }
    val isIdle: Boolean get() = status == "idle"
    val isMoving: Boolean get() = status == "moving"
    val isInBattle: Boolean get() = status == "battle"
    val isStationed: Boolean get() = status == "stationed"
    val aliveMemberCount: Int get() = slots.count { it.discipleId.isNotEmpty() && it.isAlive }
}

@Serializable
enum class BattleSlotType {
    ELDER,
    DISCIPLE
}

@Serializable
data class BattleTeamSlot(
    val index: Int = 0,
    val discipleId: String = "",
    val discipleName: String = "",
    val discipleRealm: String = "",
    val slotType: BattleSlotType = BattleSlotType.DISCIPLE,
    val isAlive: Boolean = true
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

@Serializable
data class AIBattleTeam(
    val id: String = java.util.UUID.randomUUID().toString(),
    val attackerSectId: String = "",
    val attackerSectName: String = "",
    val defenderSectId: String = "",
    val defenderSectName: String = "",
    val disciples: List<Disciple> = emptyList(),
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

@Serializable
data class CompetitionRankResult(
    val discipleId: String = "",
    val rank: Int = 0
)
