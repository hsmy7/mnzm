package com.xianxia.sect.core.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.state.SettlementStrategy
import com.xianxia.sect.core.state.Strategy
import com.xianxia.sect.core.util.TimeProgressUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
@Entity(
    tableName = "game_data",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["slot_id"], unique = true),
        Index(value = ["lastSaveTime"]),
        Index(value = ["gameYear", "gameMonth"]),
        Index(value = ["sectName"]),
        Index(value = ["spiritStones"]),
        Index(value = ["isGameStarted"])
    ]
)
data class GameData(
    @ColumnInfo(name = "id")
    @SettlementStrategy(Strategy.USE_SHADOW)
    var id: String = "",

    @ColumnInfo(name = "slot_id")
    @SettlementStrategy(Strategy.USE_SHADOW)
    var slotId: Int = 0,

    @SettlementStrategy(Strategy.USE_SHADOW)
    var sectName: String = "青云宗",
    @SettlementStrategy(Strategy.USE_SHADOW)
    var currentSlot: Int = 1,

    // 游戏时间（tick已推进，shadow也同步推进，保留oldState安全）
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var gameYear: Int = 1,
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var gameMonth: Int = 1,
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var gamePhase: Int = 0,  // 0=上旬, 1=中旬, 2=下旬

    // 游戏状态
    @SettlementStrategy(Strategy.USE_SHADOW)
    var isGameStarted: Boolean = false,
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var gameSpeed: Int = 1,

    // 资源
    @SettlementStrategy(Strategy.DELTA)
    var spiritStones: Long = 1000,
    @SettlementStrategy(Strategy.USE_SHADOW)
    var spiritHerbs: Int = 0,
    @SettlementStrategy(Strategy.USE_SHADOW)
    var sectCultivation: Double = 0.0,

    // 自动存档设置（月数，0为停止）
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoSaveIntervalMonths: Int = 3,

    // 月俸配置
    @SettlementStrategy(Strategy.PRESERVE_OLD)
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
    @SettlementStrategy(Strategy.PRESERVE_OLD)
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
    @SettlementStrategy(Strategy.CUSTOM)
    var worldMapSects: List<WorldSect> = emptyList(),

    // 宗门详情（重型交互数据，按需访问）
    @SettlementStrategy(Strategy.CUSTOM)
    var sectDetails: Map<String, SectDetail> = emptyMap(),

    @SettlementStrategy(Strategy.CUSTOM)
    var aiSectDisciples: Map<String, List<Disciple>> = emptyMap(),

    // 已探索宗门信息
    @SettlementStrategy(Strategy.USE_SHADOW)
    var exploredSects: Map<String, ExploredSectInfo> = emptyMap(),

    // 宗门侦查信息
    @SettlementStrategy(Strategy.USE_SHADOW)
    var scoutInfo: Map<String, SectScoutInfo> = emptyMap(),

    @SettlementStrategy(Strategy.CUSTOM)
    var manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),

    // 旅行商人
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var travelingMerchantItems: List<MerchantItem> = emptyList(),
    @SettlementStrategy(Strategy.USE_SHADOW)
    var merchantLastRefreshYear: Int = 0,
    @SettlementStrategy(Strategy.USE_SHADOW)
    var merchantRefreshCount: Int = 0,

    // 玩家上架商品
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var playerListedItems: List<MerchantItem> = emptyList(),

    // 弟子招募（存储完整弟子对象，仅包含可招募但未正式招募的弟子）
    @SettlementStrategy(Strategy.THREE_WAY_ID)
    var recruitList: List<Disciple> = emptyList(),
    @SettlementStrategy(Strategy.USE_SHADOW)
    var lastRecruitYear: Int = 0,

    // 世界关卡（妖兽+洞府统一池子）
    @SettlementStrategy(Strategy.CUSTOM)
    var worldLevels: List<WorldLevel> = emptyList(),

    // 修士洞府（保留兼容）
    @SettlementStrategy(Strategy.USE_SHADOW)
    var cultivatorCaves: List<CultivatorCave> = emptyList(),

    // 洞府探索队伍（保留兼容）
    @SettlementStrategy(Strategy.USE_SHADOW)
    var caveExplorationTeams: List<CaveExplorationTeam> = emptyList(),

    // AI洞府探索队伍（保留兼容）
    @SettlementStrategy(Strategy.USE_SHADOW)
    var aiCaveTeams: List<AICaveTeam> = emptyList(),

    // 解锁的副本
    // unlockedDungeons removed — replaced by world level system

    // 解锁的配方
    @SettlementStrategy(Strategy.USE_SHADOW)
    var unlockedRecipes: List<String> = emptyList(),

    // 解锁的功法
    @SettlementStrategy(Strategy.USE_SHADOW)
    var unlockedManuals: List<String> = emptyList(),

    // 最后保存时间（仅用于存档列表显示，不用于离线时间差计算。游戏无离线进度机制）
    @SettlementStrategy(Strategy.USE_SHADOW)
    var lastSaveTime: Long = 0L,

    // 长老槽位
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var elderSlots: ElderSlots = ElderSlots(),

    // 灵矿槽位
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var spiritMineSlots: List<SpiritMineSlot> = emptyList(),
    @SettlementStrategy(Strategy.USE_SHADOW)
    var spiritMineExpansions: Int = 0,

    // 藏经阁弟子槽位（独立3个）
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var librarySlots: List<LibrarySlot> = emptyList(),

    @Deprecated(
        message = "生产槽位数据已迁移到 ProductionSlotRepository，请使用 GameEngine.productionSlots 或 Repository API 读写",
        replaceWith = ReplaceWith("使用 ProductionSlotRepository.updateSlot() / getSlots() 或 GameEngine.productionSlots")
    )
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var productionSlots: List<ProductionSlot> = emptyList(),

    // 已放置建筑（网格坐标）
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var placedBuildings: List<GridBuildingData> = emptyList(),

    // 灵田种植状态
    @SettlementStrategy(Strategy.CUSTOM)
    var spiritFieldPlants: List<SpiritFieldPlant> = emptyList(),

    // 当前活跃宗门ID（"" = 主宗门）
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var activeSectId: String = "",

    // 住所槽位
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var residenceSlots: List<ResidenceSlot> = emptyList(),

    // 仓库驻守槽位
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var warehouseGarrisons: List<WarehouseGarrisonSlot> = emptyList(),

    // 巡视楼
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var patrolSlots: List<PatrolSlot> = emptyList(),
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var patrolConfig: PatrolConfig = PatrolConfig(),
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var patrolConfigs: List<PatrolConfig> = emptyList(),

    // 结盟关系
    @SettlementStrategy(Strategy.THREE_WAY_ID)
    var alliances: List<Alliance> = emptyList(),

    // AI 宗门间关系
    @SettlementStrategy(Strategy.CUSTOM)
    var sectRelations: List<SectRelation> = emptyList(),

    // 玩家最大结盟数量
    @SettlementStrategy(Strategy.USE_SHADOW)
    var playerAllianceSlots: Int = 3,

    // 宗门政策
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var sectPolicies: SectPolicies = SectPolicies(),

    // 战斗队伍（支持多队伍）
    // battleTeam 保留用于 Room schema 兼容旧存档，逻辑层使用 battleTeams
    @SettlementStrategy(Strategy.USE_SHADOW)
    var battleTeam: BattleTeam? = null,

    @Ignore
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var battleTeams: List<BattleTeam> = emptyList(),

    // 已使用的队伍编号（用于解散后编号复用）
    @Ignore
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var usedTeamNumbers: List<Int> = emptyList(),

    // AI战斗队伍
    @SettlementStrategy(Strategy.USE_SHADOW)
    var aiBattleTeams: List<AIBattleTeam> = emptyList(),

    // 已使用的兑换码列表（使用 LinkedHashSet 去重 + 上限保护）
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var usedRedeemCodes: List<String> = emptyList(),

    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var claimedMailIds: List<String> = emptyList(),

    // 玩家保护机制：AI宗门100年内不会攻击玩家宗门（若玩家主动攻击则解除）
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var playerProtectionEnabled: Boolean = true,
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var playerProtectionStartYear: Int = 1,
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var playerHasAttackedAI: Boolean = false,

    // 任务阁系统
    @SettlementStrategy(Strategy.THREE_WAY_ID)
    var activeMissions: List<ActiveMission> = emptyList(),
    @SettlementStrategy(Strategy.USE_SHADOW)
    var availableMissions: List<Mission> = emptyList(),

    // 秘境智能战斗：开启后遭遇妖兽时根据队伍状态决定是否战斗
    // smartBattleEnabled removed — replaced by world level system

    // 自动招募灵根筛选（始终运行，1=单灵根, 2=双灵根, 3=三灵根, 4=四灵根, 5=五灵根）
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoRecruitSpiritRootFilter: Set<Int> = emptySet(),

    // 道侣管理：禁止结婚的灵根数量（1=单灵根, 2=双灵根, 3=三灵根, 4=四灵根, 5=五灵根）
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var daoCompanionBannedRootCounts: Set<Int> = emptySet(),

    // 道侣管理：结婚需玩家同意
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var daoCompanionConsentRequired: Boolean = false,

    // 巡视楼战斗后展示结算弹窗
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var patrolBattleResultPopup: Boolean = false,

    // 弟子管理：突破自动使用仓库丹药
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var breakthroughAutoPillFocused: Boolean = false,
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var breakthroughAutoPillRootCounts: Set<Int> = emptySet(),
    // 弟子管理：自动装备仓库装备
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoEquipFromWarehouseFocused: Boolean = false,
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoEquipFromWarehouseRootCounts: Set<Int> = emptySet(),
    // 弟子管理：自动学习仓库功法
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoLearnFromWarehouseFocused: Boolean = false,
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoLearnFromWarehouseRootCounts: Set<Int> = emptySet(),

    @SettlementStrategy(Strategy.USE_SHADOW)
    var isGameOver: Boolean = false,

    // 血炼系统：弟子已完成的材料ID列表（discipleId → materialId list）
    @SettlementStrategy(Strategy.CUSTOM)
    @ColumnInfo(defaultValue = "{}")
    var bloodRefinements: Map<String, List<String>> = emptyMap(),

    // 血炼系统：进行中的洗炼（buildingInstanceId → BloodRefinementProgress）
    @SettlementStrategy(Strategy.CUSTOM)
    @ColumnInfo(defaultValue = "{}")
    var activeBloodRefinements: Map<String, BloodRefinementProgress> = emptyMap()
) {
    val displayTime: String get() = "第${gameYear}年${gameMonth}月${GamePhase.fromValue(gamePhase).displayName}"

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

    /** 世界地图与外交状态聚合 */
    val worldMap: WorldMapState get() = WorldMapState(
        worldMapSects = worldMapSects,
        exploredSects = exploredSects,
        scoutInfo = scoutInfo,
        sectRelations = sectRelations
    )

    /** 建筑与槽位状态聚合 */
    val buildings: BuildingState get() = BuildingState(
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
        battleTeams = battleTeams,
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
        unlockedRecipes = unlockedRecipes,
        unlockedManuals = unlockedManuals,
        manualProficiencies = manualProficiencies,
        worldLevels = worldLevels
    )

    /**
     * 从子状态创建副本，用于批量更新某个领域的多个字段。
     * 示例: gd.withWorldMap(gd.worldMap.copy(worldMapSects = newSects))
     *
     * 注意: WorldMapState 不包含 sectDetails，调用此方法不会覆盖 sectDetails。
     * 如需同步更新 sectDetails（如 scoutInfo），请直接使用 copy()。
     */
    fun withWorldMap(state: WorldMapState): GameData = this.copy(
        worldMapSects = state.worldMapSects,
        exploredSects = state.exploredSects,
        scoutInfo = state.scoutInfo,
        sectRelations = state.sectRelations
    )

    fun withBuildings(state: BuildingState): GameData = this.copy(
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
        battleTeams = state.battleTeams,
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
        // unlockedDungeons removed
        unlockedRecipes = state.unlockedRecipes,
        unlockedManuals = state.unlockedManuals,
        manualProficiencies = state.manualProficiencies,
        worldLevels = state.worldLevels
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
    val manualResearch: Boolean = false,
    val autoPlant: Boolean = false,
    val autoAlchemy: Boolean = false,
    val autoForge: Boolean = false,
    // 自动分配：focused = 已关注, rootCounts = 灵根数量筛选, threshold = 属性门槛
    val autoMineFocused: Boolean = false,
    val autoMineRootCounts: List<Int> = emptyList(),
    val autoMineThreshold: Int = 1,
    val autoPlantFocused: Boolean = false,
    val autoPlantRootCounts: List<Int> = emptyList(),
    val autoPlantThreshold: Int = 1,
    val autoAlchemyFocused: Boolean = false,
    val autoAlchemyRootCounts: List<Int> = emptyList(),
    val autoAlchemyThreshold: Int = 1,
    val autoForgeFocused: Boolean = false,
    val autoForgeRootCounts: List<Int> = emptyList(),
    val autoForgeThreshold: Int = 1
)

// 血炼进度数据
@Serializable
data class BloodRefinementProgress(
    val discipleId: String = "",
    val discipleName: String = "",
    val materialId: String = "",
    val materialName: String = "",
    val startYear: Int = 0,
    val startMonth: Int = 0,
    val durationMonths: Int = 0,
    val selectedStat: String = "",    // "speed"/"hp"/"physicalAttack"/"magicAttack"/"physicalDefense"/"magicDefense"
    val bonusPercent: Double = 0.0
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
        ).flatten().mapNotNull { it.discipleId.ifEmpty { null } }

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
    val discipleSpiritRootColor: String = "#E0E0E0",
    val sectId: String = ""
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
    @Deprecated("Use expectedYield instead. Kept for serialization compatibility.")
    val harvestAmount: Int = 0,
    @Deprecated("Use seedId to derive herbId via HerbDatabase. Kept for serialization compatibility.")
    val harvestHerbId: String = ""
) {
    val isGrowing: Boolean get() = status == "growing"
    val isIdle: Boolean get() = status == "idle"

    fun isFinished(currentYear: Int, currentMonth: Int): Boolean {
        if (status != "growing") return status == "mature"
        return TimeProgressUtil.isTimeElapsed(startYear, startMonth, growTime, currentYear, currentMonth)
    }

    fun remainingTime(currentYear: Int, currentMonth: Int): Int {
        if (status != "growing") return 0
        return TimeProgressUtil.calculateRemainingMonths(startYear, startMonth, growTime, currentYear, currentMonth)
    }

    companion object {
        const val MAX_AI_DISCIPLES_PER_SECT = 1000
    }
}

// 灵田种植数据
@Serializable
data class SpiritFieldPlant(
    val buildingInstanceId: String,
    val seedId: String = "",
    val seedName: String = "",
    val growTime: Int = 0,
    val expectedYield: Int = 0,
    val plantYear: Int = 0,
    val plantMonth: Int = 0,
    val sectId: String = ""
)

// 商人商品
@Serializable
data class MerchantItem(
    val id: String = "",
    val name: String = "",
    val type: String = "", // equipment, manual, pill, material, seed
    val itemId: String = "",
    val rarity: Int = 1,
    val price: Long = 0L,
    val quantity: Int = 1,
    val description: String = "",
    val obtainedYear: Int = 0,
    val obtainedMonth: Int = 0,
    val grade: String? = null
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

data class WorldMapRenderData(
    val worldMapSects: List<WorldSect> = emptyList(),
    val cultivatorCaves: List<CultivatorCave> = emptyList(),
    val worldLevels: List<WorldLevel> = emptyList()
)

// 世界宗门（轻量核心数据，用于地图渲染和游戏逻辑）
@Immutable
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
    val isRighteous: Boolean = true,
    val isPlayerOccupied: Boolean = false,
    val occupierBattleTeamId: String = "",
    val isUnderAttack: Boolean = false,
    val attackerSectId: String = "",
    val occupierSectId: String = "",
    val allianceId: String = "",
    val allianceStartYear: Int = 0,
    val garrisonSlots: List<GarrisonSlot> = buildList {
        repeat(10) { index ->
            add(GarrisonSlot(index = index))
        }
    }
)

@Serializable
data class SectDetail(
    val sectId: String = "",
    val mineSlots: List<MineSlot> = emptyList(),
    val occupationTime: Long = 0,
    val isOwned: Boolean = false,
    val expiryYear: Int = 0,
    val expiryMonth: Int = 0,
    val scoutInfo: SectScoutInfo = SectScoutInfo(),
    val tradeItems: List<MerchantItem> = emptyList(),
    val tradeLastRefreshYear: Int = 0,
    val lastGiftYear: Int = 0,
    val warehouse: SectWarehouse = SectWarehouse(),
    val giftPreference: GiftPreferenceType = GiftPreferenceType.NONE
)

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
    val output: Int = 100,
    val sectId: String = ""
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

@Serializable
data class ResidenceSlot(
    val buildingInstanceId: String = "",
    val slotIndex: Int = 0,
    val discipleId: String = "",
    val discipleName: String = ""
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

@Serializable
data class WarehouseGarrisonSlot(
    val buildingInstanceId: String = "",
    val discipleId: String = "",
    val discipleName: String = "",
    val sectId: String = ""
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
data class GarrisonSlot(
    val index: Int = 0,
    val discipleId: String = "",
    val discipleName: String = "",
    val discipleRealm: String = "",
    val discipleSpiritRootColor: String = "#E0E0E0",
    val portraitRes: String = ""
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

@Serializable
data class BattleTeam(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "战斗队伍",
    val teamNumber: Int = 0,
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
)

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
)

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
    val isPlayerDefender: Boolean = false,
    val isGarrison: Boolean = false,
    val garrisonSectId: String = "",
    val garrisonSectName: String = ""
)

