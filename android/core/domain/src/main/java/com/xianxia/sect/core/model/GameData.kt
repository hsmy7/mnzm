package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.state.SettlementStrategy
import com.xianxia.sect.core.state.Strategy
import com.xianxia.sect.core.state.BattleResultUIData
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked

/**
 * 邮件领取记录——物品发放后持久化到 GameData，随存档保存。
 * 替换旧的 claimedMailIds: List<String>，增加 claimedAt 时间戳和 source 来源。
 */
@Keep
@Serializable
data class MailClaimRecord(
    @ProtoNumber(1) val mailId: String,
    @ProtoNumber(2) val claimedAt: Long = 0L,
    @ProtoNumber(3) val source: String = "builtin"
)

/**
 * 宗门等级每周奖励领取记录。
 * 使用现实时间戳判断 7 天间隔。
 */
@Keep
@Serializable
data class SectLevelClaimRecord(
    @ProtoNumber(1) val level: Int,
    @ProtoNumber(2) val claimedAtEpochMs: Long = 0L  // System.currentTimeMillis()
)

/**
 * 年度报告——每年结束时由年变快照生成，展示灵石/生产/弟子等年度统计数据。
 * 保留最近 [GameConfig.Logs.MAX_YEARLY_REPORTS] 条。
 */
@Keep
@Serializable
data class YearlyReport(
    @ProtoNumber(1) val year: Int,
    // 灵石汇总
    @ProtoNumber(2) val totalIncome: Long = 0L,
    @ProtoNumber(3) val totalExpenditure: Long = 0L,
    @ProtoNumber(4) val incomeBySource: Map<String, Long> = emptyMap(),
    @ProtoNumber(5) val expenditureByReason: Map<String, Long> = emptyMap(),
    // 生产产出汇总（用于汇总卡片展示）
    @ProtoNumber(6) val forgeCompleted: Int = 0,
    @ProtoNumber(7) val alchemyCompleted: Int = 0,
    @ProtoNumber(8) val herbsHarvested: Int = 0,
    // 装备获取（key: "forge:3"=锻造3品, "battle:4"=战斗4品等）
    @ProtoNumber(9) val equipmentBySource: Map<String, Int> = emptyMap(),
    // 丹药获取（key: "alchemy:HIGH"=炼丹极品等）
    @ProtoNumber(10) val pillBySource: Map<String, Int> = emptyMap(),
    // 草药获取（key: "spirit_field"=灵田, "exploration"=探索等）
    @ProtoNumber(11) val herbBySource: Map<String, Int> = emptyMap(),
    // 弟子变动
    @ProtoNumber(12) val newDisciples: Int = 0,
    @ProtoNumber(13) val deceasedDisciples: Int = 0,
    @ProtoNumber(14) val desertedDisciples: Int = 0
)

@Keep
@Serializable
@Entity(
    tableName = "game_data",
    primaryKeys = ["id", "slot_id"],
    indices = [
        Index(value = ["slot_id"], unique = true),
        Index(value = ["lastSaveTime"]),
        Index(value = ["gameYear", "gameMonth"]),
        Index(value = ["sectName"]),
        Index(value = ["spiritStones"])
    ]
)
// 全量存档载体（Room Entity + ProtoBuf + 惰性结算字段聚合），拆分会破坏单事务原子存档
@Suppress("LargeClass")
data class GameData(
    @ColumnInfo(name = "id")
    @SettlementStrategy(Strategy.USE_SHADOW)
    @ProtoNumber(1)
    var id: String = "",

    @ColumnInfo(name = "slot_id")
    @SettlementStrategy(Strategy.USE_SHADOW)
    @kotlinx.serialization.Transient
    var slotId: Int = 0,

    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(2)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var sectName: String = "青云宗",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(3)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var currentSlot: Int = 1,

    // 游戏时间（tick已推进，shadow也同步推进，保留oldState安全）
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(4)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var gameYear: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(5)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var gameMonth: Int = 1,
    @ProtoNumber(6)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var gamePhase: Int = 0,  // 0=上旬, 1=中旬, 2=下旬

    // 游戏状态
    // isGameStarted 已移除：v4.0.43+ 使用 GameLifecycle 枚举纯运行时管理

    // 资源
    // spiritStones 固定表示下品灵石；中品、上品灵石使用新增字段
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(7)
    @ColumnInfo(name = "spiritStones")
    @SettlementStrategy(Strategy.DELTA)
    var spiritStones: Long = 1000,
    @ProtoNumber(94)
    @ColumnInfo(name = "midGradeSpiritStones")
    @SettlementStrategy(Strategy.DELTA)
    var midGradeSpiritStones: Long = 0,
    @ProtoNumber(95)
    @ColumnInfo(name = "highGradeSpiritStones")
    @SettlementStrategy(Strategy.DELTA)
    var highGradeSpiritStones: Long = 0,
    @ProtoNumber(8)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var spiritHerbs: Int = 0,
    @ProtoNumber(96)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var sectCultivation: Double = 0.0,

    // 自动存档间隔（已废弃，为兼容旧存档保留此字段）
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(9)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoSaveIntervalMonths: Int = 3,

    // 年俸配置
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(10)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    @ColumnInfo(name = "monthlySalary")
    var yearlySalary: Map<Int, Int> = mapOf(
        9 to 240,   // 练气
        8 to 720,   // 筑基
        7 to 1200,  // 金丹
        6 to 1920,  // 元婴
        5 to 2640,  // 化神
        4 to 4320,  // 炼虚
        3 to 5280,  // 合体
        2 to 6720,  // 大乘
        1 to 8640,  // 渡劫
        0 to 12000  // 仙人
    ),

    // 年俸发放开关（按境界）
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(11)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    @ColumnInfo(name = "monthlySalaryEnabled")
    var yearlySalaryEnabled: Map<Int, Boolean> = mapOf(
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
    @ProtoNumber(12)
    @SettlementStrategy(Strategy.CUSTOM)
    var worldMapSects: List<WorldSect> = emptyList(),

    // 宗门详情（重型交互数据，按需访问）
    @ProtoNumber(54)
    @SettlementStrategy(Strategy.CUSTOM)
    var sectDetails: Map<String, SectDetail> = emptyMap(),

    @kotlinx.serialization.Transient
    @SettlementStrategy(Strategy.CUSTOM)
    var aiSectDisciples: Map<String, List<Disciple>> = emptyMap(),

    // 已探索宗门信息
    @ProtoNumber(13)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var exploredSects: Map<String, ExploredSectInfo> = emptyMap(),

    // 宗门侦查信息
    @ProtoNumber(14)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var scoutInfo: Map<String, SectScoutInfo> = emptyMap(),

    @ProtoNumber(16)
    @SettlementStrategy(Strategy.CUSTOM)
    var manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),

    // 旅行商人
    @ProtoNumber(17)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var travelingMerchantItems: List<MerchantItem> = emptyList(),
    @ProtoNumber(18)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var merchantLastRefreshYear: Int = 0,
    @ProtoNumber(19)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var merchantRefreshCount: Int = 0,
    /** 手动刷新次数（每30年给1次），初始1次 */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(90)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var merchantRefreshChances: Int = 1,
    /** 上次获得手动刷新次数的游戏年份 */
    @ProtoNumber(92)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var merchantLastRefreshChanceGrantYear: Int = 0,

    // 玩家上架商品
    @ProtoNumber(20)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var playerListedItems: List<MerchantItem> = emptyList(),

    // 商人收购物品列表
    @ProtoNumber(88)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var merchantAcquisitionItems: List<MerchantItem> = emptyList(),

    // 收购刷新年份
    @ProtoNumber(89)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var merchantAcquisitionLastRefreshYear: Int = 0,

    // 自动购买列表
    @ProtoNumber(159)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoBuyList: List<AutoBuyEntry> = emptyList(),

    // 弟子招募（存储完整弟子对象，仅包含可招募但未正式招募的弟子）
    @ProtoNumber(24)
    @SettlementStrategy(Strategy.THREE_WAY_ID)
    var recruitList: List<Disciple> = emptyList(),
    @ProtoNumber(25)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var lastRecruitYear: Int = 0,
    // AI 宗门弟子周期性招募上次触发年份（差值判据；0=从未触发，老档升级后自愈）
    @ProtoNumber(219)
    @ColumnInfo(name = "last_ai_sect_recruit_year", defaultValue = "0")
    @SettlementStrategy(Strategy.USE_SHADOW)
    var lastAiSectRecruitYear: Int = 0,

    // ── 玉符（氪金货币，2026-08-07）──
    // 墙钟货币：按真实前台游玩时长发放（GameConfig.Jade），不占仓库、无品阶、不走 InventorySystem，
    // 与游戏时间（年/月/旬）完全解耦；单日上限次日凌晨 12 点（墙钟午夜）重置。
    // 发放/跨天重置/循环停止/存档快照时由 JadeSymbolService 写入（低频），运行时累计在服务内存态。
    /** 玉符持有数量 */
    @ProtoNumber(220)
    @ColumnInfo(name = "jade_symbols", defaultValue = "0")
    @SettlementStrategy(Strategy.USE_SHADOW)
    var jadeSymbols: Int = 0,
    /** 今日（墙钟日）已通过游玩时长获得的玉符数量 */
    @ProtoNumber(221)
    @ColumnInfo(name = "jade_symbols_today", defaultValue = "0")
    @SettlementStrategy(Strategy.USE_SHADOW)
    var jadeSymbolsToday: Int = 0,
    /** 今日午夜锚点（epoch ms，AdsDelegate.getTodayStartMs 同款判据）；跨天/回拨防御基准 */
    @ProtoNumber(222)
    @ColumnInfo(name = "jade_day_anchor_ms", defaultValue = "0")
    @SettlementStrategy(Strategy.USE_SHADOW)
    var jadeDayAnchorMs: Long = 0L,
    /** 当前 10 分钟周期已累计的游玩毫秒（持久化防闪退，重启后从存档恢复） */
    @ProtoNumber(223)
    @ColumnInfo(name = "jade_accum_ms", defaultValue = "0")
    @SettlementStrategy(Strategy.USE_SHADOW)
    var jadeAccumMs: Long = 0L,

    // 世界关卡（妖兽+洞府统一池子）
    @ProtoNumber(140)
    @SettlementStrategy(Strategy.CUSTOM)
    var worldLevels: List<WorldLevel> = emptyList(),
    /** 上次世界关卡刷新的绝对月份（gameYear*12+gameMonth），读档后保持连续 */
    @ProtoNumber(97)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var worldLevelLastRefreshMonth: Int = 0,
    /** RNG 分区状态快照（partitionId → PCG state），读档后恢复确定性随机序列 */
    @ProtoNumber(98)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var rngStates: Map<Int, Long> = emptyMap(),

    // 修士洞府（保留兼容）
    @ProtoNumber(26)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var cultivatorCaves: List<CultivatorCave> = emptyList(),

    // 洞府探索队伍（保留兼容）
    @ProtoNumber(27)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var caveExplorationTeams: List<CaveExplorationTeam> = emptyList(),

    // AI洞府探索队伍（保留兼容）
    @ProtoNumber(28)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var aiCaveTeams: List<AICaveTeam> = emptyList(),

    // 解锁的副本
    // unlockedDungeons removed — replaced by world level system

    // 解锁的配方
    @ProtoNumber(30)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var unlockedRecipes: List<String> = emptyList(),

    // 解锁的功法
    @ProtoNumber(31)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var unlockedManuals: List<String> = emptyList(),

    // 最后保存时间（仅用于存档列表显示，不用于离线时间差计算。游戏无离线进度机制）
    @ProtoNumber(32)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var lastSaveTime: Long = 0L,

    // 长老槽位
    @ProtoNumber(33)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var elderSlots: ElderSlots = ElderSlots(),

    // 灵矿槽位
    @ProtoNumber(34)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var spiritMineSlots: List<SpiritMineSlot> = emptyList(),
    @ProtoNumber(87)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var spiritMineExpansions: Int = 0,
    /** 灵矿场上次结算的游戏月份（gameYear*12+gameMonth），用于时间戳差分 */
    @ProtoNumber(138)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var spiritMineLastSettledMonth: Int = 0,

    // 藏经阁弟子槽位（独立3个）
    @ProtoNumber(35)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var librarySlots: List<LibrarySlot> = emptyList(),

    @ProtoNumber(52)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var productionSlots: List<ProductionSlot> = emptyList(),

    // 已放置建筑（网格坐标）
    @ProtoNumber(139)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var placedBuildings: List<GridBuildingData> = emptyList(),

    // 灵田种植状态
    @ProtoNumber(141)
    @SettlementStrategy(Strategy.CUSTOM)
    var spiritFieldPlants: List<SpiritFieldPlant> = emptyList(),

    // 当前活跃宗门ID（"" = 主宗门）
    @ProtoNumber(99)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var activeSectId: String = "",

    // 住所槽位
    @ProtoNumber(36)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var residenceSlots: List<ResidenceSlot> = emptyList(),

    // 仓库驻守槽位
    @ProtoNumber(146)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var warehouseGarrisons: List<WarehouseGarrisonSlot> = emptyList(),

    // 巡视楼
    @ProtoNumber(142)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var patrolSlots: List<PatrolSlot> = emptyList(),
    @ProtoNumber(143)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var patrolConfig: PatrolConfig = PatrolConfig(),
    @ProtoNumber(144)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var patrolConfigs: List<PatrolConfig> = emptyList(),
    /** 巡视塔战斗结果缓存（未展示的弹窗数据），持久化避免读档后丢失 */
    @ProtoNumber(145)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var pendingPatrolBattleResults: List<BattleResultUIData> = emptyList(),

    // 结盟关系
    @ProtoNumber(38)
    @SettlementStrategy(Strategy.THREE_WAY_ID)
    var alliances: List<Alliance> = emptyList(),

    // 附属契约：玩家为宗主，AI为附属宗门
    @ProtoNumber(147)
    @SettlementStrategy(Strategy.THREE_WAY_ID)
    var vassalContracts: List<VassalContract> = emptyList(),

    // AI 宗门间关系
    @ProtoNumber(39)
    @SettlementStrategy(Strategy.CUSTOM)
    var sectRelations: List<SectRelation> = emptyList(),

    // AI 宗门妖兽跳过冷却（宗门ID -> 剩余冷却旬数）
    @Ignore
    @kotlinx.serialization.Transient
    @SettlementStrategy(Strategy.USE_SHADOW)
    var aiSectBeastSkipCooldowns: Map<String, Int> = emptyMap(),

    /** AI 宗门妖兽遭遇战目标（beastId → aiSectId）。
     *  当 AI 和玩家同时距离妖兽最近时，AI 暂不进攻，
     *  等玩家行动时触发遭遇战。 */
    @Ignore
    @kotlinx.serialization.Transient
    @SettlementStrategy(Strategy.USE_SHADOW)
    var aiBeastEncounterTargets: Map<String, String> = emptyMap(),

    /** 被玩家锁定的妖兽 ID 集合。
     *  当玩家在世界地图打开妖兽详情弹窗时，该妖兽 ID 被加入此集合，
     *  月度结算中 AI 宗门将跳过对这些妖兽的攻击。
     *  @Ignore 不持久化，读档后自动清空。 */
    @Ignore
    @kotlinx.serialization.Transient
    @SettlementStrategy(Strategy.USE_SHADOW)
    var lockedBeastIds: Set<String> = emptySet(),

    /** AI 宗门妖兽月度直攻目标（beastId → [nearestAiSectId, fartherAiSectId]）。
     *  月度结算第一阶段由 [precomputeTargets] 写入最近的 AI 宗门（最多 2 个），
     *  巡视楼处理后第二阶段的 [processRemainingTargets] 处理剩余未击败妖兽。
     *  空列表表示该妖兽已被 AI 或巡视楼处理。 */
    @Ignore
    @kotlinx.serialization.Transient
    @SettlementStrategy(Strategy.USE_SHADOW)
    var aiSectBeastDirectTargets: Map<String, List<String>> = emptyMap(),

    // 玩家最大结盟数量
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(40)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var playerAllianceSlots: Int = 3,

    // 宗门政策
    @ProtoNumber(42)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var sectPolicies: SectPolicies = SectPolicies(),

    // 广纳门徒上次付费月份（绝对月数 = year*12 + month，用于3年冷却判断）
    @ProtoNumber(93)
    @ColumnInfo(name = "open_recruitment_last_paid_month")
    var openRecruitmentLastPaidMonth: Int = 0,

    // 战斗队伍（支持多队伍）
    // battleTeam 保留用于 Room schema 兼容旧存档，逻辑层使用 battleTeams
    @SettlementStrategy(Strategy.USE_SHADOW)
    @kotlinx.serialization.Transient
    var battleTeam: BattleTeam? = null,

    // 2026-08-05 A3 修复：battleTeams/usedTeamNumbers 原为 @Ignore+@Transient
    // 不落盘——读档后出战队伍全清、DiscipleStatusService"在队中"判定失效；
    // 现持久化（Room 列 + proto 字段），旧档由 battleTeamsInitialized 区分
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(216)
    @ColumnInfo(name = "battle_teams", defaultValue = "")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var battleTeams: List<BattleTeam> = emptyList(),

    // 已使用的队伍编号（用于解散后编号复用）
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(217)
    @ColumnInfo(name = "used_team_numbers", defaultValue = "")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var usedTeamNumbers: List<Int> = emptyList(),

    // 队伍配置是否已初始化——false=旧档（读档走默认队伍初始化），
    // true+空列表=玩家明确清空全部队伍（不再重复初始化）
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(218)
    @ColumnInfo(name = "battle_teams_initialized", defaultValue = "0")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var battleTeamsInitialized: Boolean = false,

    // AI战斗队伍
    @kotlinx.serialization.Transient
    @SettlementStrategy(Strategy.USE_SHADOW)
    var aiBattleTeams: List<AIBattleTeam> = emptyList(),

    // 已使用的兑换码列表（使用 LinkedHashSet 去重 + 上限保护）
    @ProtoNumber(45)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var usedRedeemCodes: List<String> = emptyList(),

    @ProtoNumber(148)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var mailRecords: List<MailClaimRecord> = emptyList(),

    @ProtoNumber(149)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var sectLevelClaimRecords: List<SectLevelClaimRecord> = emptyList(),

    // 存档数据格式版本号，用于迁移旧存档数据
    @ProtoNumber(100)
    @ColumnInfo(name = "save_version", defaultValue = "0")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var saveVersion: Int = 0,

    // 玩家保护机制：AI宗门100年内不会攻击玩家宗门（若玩家主动攻击则解除）
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(46)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var playerProtectionEnabled: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(47)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var playerProtectionStartYear: Int = 1,
    @ProtoNumber(48)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var playerHasAttackedAI: Boolean = false,

    // 任务阁系统
    @ProtoNumber(49)
    @SettlementStrategy(Strategy.THREE_WAY_ID)
    var activeMissions: List<ActiveMission> = emptyList(),
    @ProtoNumber(50)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var availableMissions: List<Mission> = emptyList(),

    // 秘境智能战斗：开启后遭遇妖兽时根据队伍状态决定是否战斗
    // smartBattleEnabled removed — replaced by world level system

    // 自动招募灵根筛选（始终运行，1=单灵根, 2=双灵根, 3=三灵根, 4=四灵根, 5=五灵根）
    @ProtoPacked @ProtoNumber(101)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoRecruitSpiritRootFilter: Set<Int> = emptySet(),

    // 俘虏灵根筛选（1=单灵根, 2=双灵根, 3=三灵根, 4=四灵根, 5=五灵根）
    @ProtoPacked @ProtoNumber(162)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var prisonerSpiritRootFilter: Set<Int> = emptySet(),

    // 本月已招募人数计数（每月重置，上限 GameConfig.Logs.RECRUIT_MONTHLY_LIMIT）
    @ProtoNumber(163)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var recruitCountThisMonth: Int = 0,

    // 自动拒绝灵根筛选（1=单灵根, 2=双灵根, 3=三灵根, 4=四灵根, 5=五灵根）
    @ProtoPacked @ProtoNumber(210)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoRejectSpiritRootFilter: Set<Int> = emptySet(),

    // 物品关注列表（键 = "type:name"，如 "pill:聚气丹"）。纯玩家偏好，无结算参与。
    @ProtoNumber(211)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var watchedItemIds: List<String> = emptyList(),

    // 远古秘境地图实例（id 为空 = 当前不存在）。纯玩法状态，结算不参与。
    @ProtoNumber(212)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    @ColumnInfo(name = "secret_realm_state", defaultValue = "")
    var secretRealmState: SecretRealmState = SecretRealmState(),

    // 远古秘境上次消失年份（冷却判据；0 = 从未出现）
    @ProtoNumber(213)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    @ColumnInfo(name = "secret_realm_cooldown_year", defaultValue = "0")
    var secretRealmCooldownYear: Int = 0,

    // 远古秘境探索会话（members 为空 = 无活跃会话）
    @ProtoNumber(214)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    @ColumnInfo(name = "secret_realm_session", defaultValue = "")
    var secretRealmSession: SecretRealmExplorationSession = SecretRealmExplorationSession(),

    // 秘境中 AI 宗门探索队伍列表
    @ProtoNumber(215)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    @ColumnInfo(name = "secret_realm_ai_teams", defaultValue = "")
    var secretRealmAITeams: List<SecretRealmAITeam> = emptyList(),

    // 道侣管理：禁止结婚的灵根数量（1=单灵根, 2=双灵根, 3=三灵根, 4=四灵根, 5=五灵根）
    @ProtoPacked @ProtoNumber(102)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var daoCompanionBannedRootCounts: Set<Int> = emptySet(),

    // 道侣管理：结婚需玩家同意
    @ProtoNumber(103)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var daoCompanionConsentRequired: Boolean = false,

    // 巡视楼战斗后展示结算弹窗
    @ProtoNumber(104)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var patrolBattleResultPopup: Boolean = false,

    // 灵石自动补差价：消费时下品不足则自动售卖中品补足
    @ProtoNumber(105)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoSellMidGradeForPurchase: Boolean = false,

    // 灵石自动补差价：消费时下品不足则自动售卖上品补足
    @ProtoNumber(106)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoSellHighGradeForPurchase: Boolean = false,

    // 弟子选择界面：显示所有可用弟子（非空闲中，但始终排除思过/任务/战斗中）
    @ProtoNumber(107)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var showAllAvailableDisciples: Boolean = false,

    // 弟子管理：突破自动使用仓库丹药
    @ProtoNumber(108)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var breakthroughAutoPillFocused: Boolean = false,
    @ProtoPacked @ProtoNumber(109)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var breakthroughAutoPillRootCounts: Set<Int> = emptySet(),
    // 弟子管理：自动装备仓库装备
    @ProtoNumber(110)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoEquipFromWarehouseFocused: Boolean = false,
    @ProtoPacked @ProtoNumber(111)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoEquipFromWarehouseRootCounts: Set<Int> = emptySet(),
    // 弟子管理：自动学习仓库功法
    @ProtoNumber(112)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoLearnFromWarehouseFocused: Boolean = false,
    @ProtoPacked @ProtoNumber(113)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var autoLearnFromWarehouseRootCounts: Set<Int> = emptySet(),

    @ProtoNumber(114)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var isGameOver: Boolean = false,

    // 血炼系统：弟子已完成的材料ID列表（discipleId → materialId list）
    @ProtoNumber(115)
    @SettlementStrategy(Strategy.CUSTOM)
    @ColumnInfo(defaultValue = "{}")
    var bloodRefinements: Map<String, List<String>> = emptyMap(),

    // 血炼系统：进行中的洗炼（buildingInstanceId → BloodRefinementProgress）
    @ProtoNumber(150)
    @SettlementStrategy(Strategy.CUSTOM)
    @ColumnInfo(defaultValue = "{}")
    var activeBloodRefinements: Map<String, BloodRefinementProgress> = emptyMap(),

    // 血炼系统：弟子已累计的血炼加成总量（discipleId → BloodRefinementBonusTotal）
    // 用于单利计算基准，防止复利叠加（#8 修复）
    @ProtoNumber(151)
    @SettlementStrategy(Strategy.CUSTOM)
    @ColumnInfo(defaultValue = "{}")
    var bloodRefinementBonusTotals: Map<String, BloodRefinementBonusTotal> = emptyMap(),

    // 血炼系统：弟子血炼百分比累计（discipleId → BloodRefinementPctTotal）
    // 替代旧的绝对值存储。血炼改为乘区百分比后，每次血炼累计材料百分比，
    // 不再写入 DiscipleTables.base* 列。
    @ProtoNumber(152)
    @SettlementStrategy(Strategy.CUSTOM)
    @ColumnInfo(defaultValue = "{}")
    var bloodRefinementPctTotals: Map<String, BloodRefinementPctTotal> = emptyMap(),

    // 天道试炼状态
    @ProtoNumber(153)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    @ColumnInfo(name = "heavenly_trial_state", defaultValue = "{\"highestClearedLevel\":-1,\"levelClearCounts\":[0,0,0,0,0,0,0,0]}")
    var heavenlyTrialState: HeavenlyTrialSaveData = HeavenlyTrialSaveData(),

    // 每日签到状态
    @ProtoNumber(154)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    @ColumnInfo(name = "sign_in_state_json", defaultValue = "{\"claimedDays\":[],\"currentMonth\":0,\"currentYear\":0}")
    var signInState: SignInState = SignInState(),

    // AI宗门攻击个性映射（sectId → personality）
    @ProtoNumber(155)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var aiSectPersonalities: Map<String, AISectPersonality> = emptyMap(),

    // 附庸关系：玩家主宗的宗门ID，"" 表示独立宗门
    @ProtoNumber(116)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var suzerainSectId: String = "",

    // 上一年灵石总收入（用于附庸年贡计算）
    @ProtoNumber(117)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var lastYearSpiritStoneIncome: Long = 0L,

    // 活跃的攻击预警列表
    @ProtoNumber(156)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var activeAttackWarnings: List<AttackWarning> = emptyList(),

    // 已向玩家展示过的预警阶段（"warningId:WAR_DECLARATION"）
    @ProtoNumber(118)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var shownWarningStageIds: List<String> = emptyList(),

    // AI宗门攻击冷却追踪（sectId → 下次可攻击的游戏绝对月份）
    @ProtoNumber(119)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var sectAttackCooldowns: Map<String, Int> = emptyMap(),

    // 玩家宗门战记录（仅宗门攻占/丢失，用于附属决策，统计近3年）
    @ProtoNumber(157)
    @SettlementStrategy(Strategy.USE_SHADOW)
    var sectBattleRecords: List<SectBattleRecord> = emptyList(),

    // 游戏事件记录——消息栏数据，保留近十年事件，上限 MAX_EVENT_LOGS
    @ProtoNumber(91)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var gameEventRecords: List<GameEventRecord> = emptyList(),

    // 引导任务进度（新手引导系统）
    @ProtoPacked @ProtoNumber(120)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var guideClaimedRewardIds: Set<Int> = emptySet(),
    @ProtoNumber(121)
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var guideCounters: Map<String, Long> = emptyMap(),

    // 宗门地图随机种子：新开游戏时随机初始化，不同存档产生不同的地面/装饰物分布
    @ProtoNumber(122)
    @ColumnInfo(name = "map_seed", defaultValue = "0")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var mapSeed: Int = 0,

    // ── 年度报告系统字段（v27 新增） ──────────────────────────────

    @ProtoNumber(123)
    @ColumnInfo(name = "annual_income_by_source")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualIncomeBySource: Map<String, Long> = emptyMap(),

    @ProtoNumber(124)
    @ColumnInfo(name = "annual_expenditure_by_reason")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualExpenditureByReason: Map<String, Long> = emptyMap(),

    @ProtoNumber(125)
    @ColumnInfo(name = "annual_total_income")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualTotalIncome: Long = 0L,

    @ProtoNumber(126)
    @ColumnInfo(name = "annual_total_expenditure")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualTotalExpenditure: Long = 0L,

    @ProtoNumber(127)
    @ColumnInfo(name = "annual_alchemy_count")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualAlchemyCount: Int = 0,

    @ProtoNumber(128)
    @ColumnInfo(name = "annual_forge_count")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualForgeCount: Int = 0,

    @ProtoNumber(129)
    @ColumnInfo(name = "annual_herb_count")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualHerbCount: Int = 0,

    @ProtoNumber(130)
    @ColumnInfo(name = "annual_new_disciples")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualNewDisciples: Int = 0,

    @ProtoNumber(131)
    @ColumnInfo(name = "annual_deceased_disciples")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualDeceasedDisciples: Int = 0,

    @ProtoNumber(132)
    @ColumnInfo(name = "annual_deserted_disciples")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualDesertedDisciples: Int = 0,

    @ProtoNumber(133)
    @ColumnInfo(name = "annual_theft_count")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualTheftCount: Int = 0,

    @ProtoNumber(134)
    @ColumnInfo(name = "theft_judgements_this_month", defaultValue = "0")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var theftJudgementsThisMonth: Int = 0,

    // 年内装备获取按来源+品阶（key: "forge:3"等）
    @ProtoNumber(135)
    @ColumnInfo(name = "annual_equipment_by_source")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualEquipmentBySource: Map<String, Int> = emptyMap(),

    // 年内丹药获取按来源+品阶（key: "alchemy:HIGH"等）
    @ProtoNumber(136)
    @ColumnInfo(name = "annual_pill_by_source")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualPillBySource: Map<String, Int> = emptyMap(),

    // 年内草药获取按来源（key: "spirit_field"等）
    @ProtoNumber(137)
    @ColumnInfo(name = "annual_herb_by_source")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var annualHerbBySource: Map<String, Int> = emptyMap(),

    @ProtoNumber(158)
    @ColumnInfo(name = "yearly_reports")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var yearlyReports: List<YearlyReport> = emptyList(),

    // 音乐/音效开关
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(160)
    @ColumnInfo(defaultValue = "1")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var soundEnabled: Boolean = true,

    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(161)
    @ColumnInfo(defaultValue = "1")
    @SettlementStrategy(Strategy.PRESERVE_OLD)
    var musicEnabled: Boolean = true,
) {
    val displayTime: String get() = "第${gameYear}年${gameMonth}月${GamePhase.fromValue(gamePhase).displayName}"

    /** 切换物品关注状态：已关注则移除，未关注则添加（去重并截断到 [MAX_WATCHED_ITEMS]） */
    fun toggleWatchedItem(key: String): GameData {
        val watched = watchedItemIds.toMutableSet()
        if (!watched.add(key)) watched.remove(key)
        return copy(watchedItemIds = watched.toList().takeLast(MAX_WATCHED_ITEMS))
    }

    /** 按品阶获取灵石数量 */
    fun spiritStoneCount(grade: SpiritStoneGrade): Long = when (grade) {
        SpiritStoneGrade.LOW -> spiritStones
        SpiritStoneGrade.MID -> midGradeSpiritStones
        SpiritStoneGrade.HIGH -> highGradeSpiritStones
    }

    /** 按售卖价折算的总灵石价值（下品等价） */
    fun totalSpiritStonesSellValue(): Long =
        SpiritStoneExchange.totalSellValue(
            spiritStones, midGradeSpiritStones, highGradeSpiritStones
        )

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
        merchantRefreshChances = merchantRefreshChances,
        merchantLastRefreshChanceGrantYear = merchantLastRefreshChanceGrantYear,
        playerListedItems = playerListedItems,
        merchantAcquisitionItems = merchantAcquisitionItems,
        merchantAcquisitionLastRefreshYear = merchantAcquisitionLastRefreshYear,
        autoBuyList = autoBuyList
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
        merchantRefreshChances = state.merchantRefreshChances,
        merchantLastRefreshChanceGrantYear = state.merchantLastRefreshChanceGrantYear,
        playerListedItems = state.playerListedItems,
        merchantAcquisitionItems = state.merchantAcquisitionItems,
        merchantAcquisitionLastRefreshYear = state.merchantAcquisitionLastRefreshYear,
        autoBuyList = state.autoBuyList
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
        const val MAX_WATCHED_ITEMS = 500
    }
}

