#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/core/types.h"

// ============================================================
// 游戏状态模型（Kotlin→C++ 迁移批次 1）
//
// 与 Kotlin core/domain/model 的 @Serializable 模型**字段名一一对应**
// （JSON 快照协议要求字段名完全一致，nlohmann ↔ kotlinx 互操作）。
//
// 覆盖范围（批次 1 第一子步）：
//   - GameData：全部标量 + 简单集合字段（嵌套对象如 worldMapSects/
//     productionSlots/elderSlots 等留待后续子步，宽松 from_json 忽略、
//     to_json 不输出——随子系统迁移逐步补齐）
//   - Disciple：全部标量 + 简单集合字段（combat/pillEffects/equipment/
//     social/skills/usage 嵌套留待后续）
//   - 物品类：EquipmentStack/Instance、ManualStack/Instance、Pill、
//     Material、Herb、Seed、StorageBag 核心字段（字段扁平，与 Kotlin
//     data class 顶层字段一致，不使用嵌套 base 结构）
//
// 枚举约定：Kotlin 枚举经 kotlinx JSON 序列化为 name 字符串（如
// EquipmentSlot.WEAPON → "WEAPON"），C++ 侧暂以 std::string 承载，
// 后续批次再建强类型映射。
// ============================================================
namespace gamecore::state {

// ── 物品（字段扁平，对应 Kotlin data class 顶层字段；可空 String? 用
// std::optional 保留 null 语义；嵌套如 Pill.effects(PillEffect) 留待后续子步） ─

struct EquipmentStack {
    std::string id;
    int32_t slotId = 0;
    std::string name;
    int32_t rarity = 1;
    std::string description;
    std::string slot = "WEAPON";          // EquipmentSlot.name
    int32_t physicalAttack = 0;
    int32_t magicAttack = 0;
    int32_t physicalDefense = 0;
    int32_t magicDefense = 0;
    int32_t speed = 0;
    int32_t hp = 0;
    int32_t mp = 0;
    double critChance = 0.0;
    int32_t minRealm = 9;
    int32_t quantity = 1;
    bool isLocked = false;
};

struct EquipmentInstance {
    std::string id;
    int32_t slotId = 0;
    std::string name;
    int32_t rarity = 1;
    std::string description;
    std::string slot = "WEAPON";
    int32_t physicalAttack = 0;
    int32_t magicAttack = 0;
    int32_t physicalDefense = 0;
    int32_t magicDefense = 0;
    int32_t speed = 0;
    int32_t hp = 0;
    int32_t mp = 0;
    double critChance = 0.0;
    int32_t nurtureLevel = 0;
    double nurtureProgress = 0.0;
    int32_t minRealm = 9;
    std::optional<std::string> ownerId;   // String? → nullopt = null
    bool isEquipped = false;
};

// 功法公共字段（ManualStack 与 ManualInstance 共享；Kotlin 字段名一致）
struct ManualBase {
    std::string id;
    int32_t slotId = 0;
    std::string name;
    int32_t rarity = 1;
    std::string description;
    std::string type = "MIND";            // ManualType.name
    std::map<std::string, int32_t> stats;
    std::optional<std::string> skillName;         // String?
    std::optional<std::string> skillDescription;  // String?
    std::string skillType;
    std::string skillDamageType;
    int32_t skillHits = 1;
    double skillDamageMultiplier = 0.0;
    int32_t skillCooldown = 0;
    int32_t skillMpCost = 0;
    double skillHealPercent = 0.0;
    int32_t skillHealFixed = 0;
    std::string skillHealType;
    std::optional<std::string> skillBuffType;     // String?
    double skillBuffValue = 0.0;
    int32_t skillBuffDuration = 0;
    std::string skillBuffsJson;
    bool skillIsAoe = false;
    std::string skillTargetScope;
    double skillShieldPercent = 0.0;
    double skillTurnAdvancePercent = 0.0;
    double skillDamageSharePercent = 0.0;
    double skillDamageLinkPercent = 0.0;
    int32_t minRealm = 9;
};

struct ManualStack : ManualBase {
    int32_t quantity = 1;
    bool isLocked = false;
};

struct ManualInstance : ManualBase {
    std::optional<std::string> ownerId;   // String?
    bool isLearned = false;
};

struct Pill {
    std::string id;
    int32_t slotId = 0;
    std::string name;
    int32_t rarity = 1;
    std::string description;
    std::string category = "CULTIVATION"; // PillCategory.name
    std::string grade = "MEDIUM";         // PillGrade.name
    std::string pillType;
    // effects: PillEffect 嵌套——后续子步补充（宽松忽略）
    int32_t minRealm = 9;
    int32_t quantity = 1;
    bool isLocked = false;
};

struct Material {
    std::string id;
    int32_t slotId = 0;
    std::string name;
    int32_t rarity = 1;
    std::string description;
    std::string category = "BEAST_HIDE";  // MaterialCategory.name
    int32_t quantity = 1;
    bool isLocked = false;
};

struct Herb {
    std::string id;
    int32_t slotId = 0;
    std::string name;
    int32_t rarity = 1;
    std::string description;
    std::string category;
    int32_t quantity = 1;
    bool isLocked = false;
};

struct Seed {
    std::string id;
    int32_t slotId = 0;
    std::string name;
    int32_t rarity = 1;
    std::string description;
    int32_t growTime = 3;
    int32_t yield = 1;
    int32_t quantity = 1;
    bool isLocked = false;
};

struct StorageBag {
    std::string id;
    int32_t slotId = 0;
    std::string name;
    int32_t rarity = 1;
    std::string description;
    int32_t quantity = 1;
    bool isLocked = false;
};

// ── 弟子（Disciple） ────────────────────────────────────────────────
// 注：Kotlin 使用自定义序列化器（DiscipleSerializer），显式排除 slotId
// （Room 复合主键，非游戏字段）——快照协议同样不含 slotId
struct Disciple {
    std::string id;
    std::string name;
    std::string surname;
    int32_t realm = 9;
    int32_t realmLayer = 1;
    double cultivation = 0.0;
    double cultivationCheckpoint = 0.0;
    int32_t cultivationCheckpointGameMonth = 0;
    std::string spiritRootType = "metal";
    int32_t age = 16;
    int32_t lifespan = 80;
    bool isAlive = true;
    std::string gender = "male";
    std::string portraitRes;
    std::vector<std::string> manualIds;
    std::vector<std::string> talentIds;
    std::vector<std::string> physiqueIds;
    std::vector<std::string> affixIds;
    std::map<std::string, int32_t> manualMasteries;
    std::string status = "IDLE";          // DiscipleStatus.name
    std::map<std::string, std::string> statusData;
    double cultivationSpeedBonus = 0.0;
    int32_t cultivationSpeedDuration = 0;
    std::string discipleType = "outer";
    int32_t soulPower = 0;
    int32_t cultivationCompletionMonth = 0;
    int32_t cultivationCompletionPhase = 1;
    int32_t manualCompletionMonth = 0;
    int32_t manualCompletionPhase = 1;
    int32_t equipmentNurturingCompletionMonth = 0;
    int32_t equipmentNurturingCompletionPhase = 1;
    // 注：lifeEvents / monthlyUsedPillIds 是 Kotlin Disciple 的**类体属性**
    // （非 @Serializable 构造参数，不参与 JSON 序列化）——不纳入快照协议
};

// ── 嵌套类型（批次 1 第二子步；字段名与 Kotlin @Serializable 一致） ──

/// DirectDiscipleSlot（亲传弟子槽位）
struct DirectDiscipleSlot {
    int32_t index = 0;
    std::string discipleId;
    std::string discipleName;
    std::string discipleRealm;
    std::string discipleSpiritRootColor = "#E0E0E0";
    std::string sectId;
};

/// ElderSlots（长老槽位）
struct ElderSlots {
    std::string viceSectMaster;
    std::string herbGardenElder;
    std::string alchemyElder;
    std::string forgeElder;
    std::string outerElder;
    std::string preachingElder;
    std::vector<DirectDiscipleSlot> preachingMasters;
    std::string lawEnforcementElder;
    std::vector<DirectDiscipleSlot> lawEnforcementDisciples;
    std::string innerElder;
    std::string qingyunPreachingElder;
    std::vector<DirectDiscipleSlot> qingyunPreachingMasters;
    std::vector<DirectDiscipleSlot> herbGardenDisciples;
    std::vector<DirectDiscipleSlot> alchemyDisciples;
    std::vector<DirectDiscipleSlot> forgeDisciples;
    std::vector<DirectDiscipleSlot> spiritMineDeaconDisciples;
    std::string recruitingElder;
};

/// SectPolicies（宗门政策）
struct SectPolicies {
    bool spiritMineBoost = false;
    bool enhancedSecurity = false;
    bool alchemyIncentive = false;
    bool forgeIncentive = false;
    bool herbCultivation = false;
    bool cultivationSubsidy = false;
    bool manualResearch = false;
    bool autoPlant = false;
    bool autoAlchemy = false;
    bool autoForge = false;
    bool autoMineFocused = false;
    std::vector<int32_t> autoMineRootCounts;
    int32_t autoMineThreshold = 1;
    bool autoPlantFocused = false;
    std::vector<int32_t> autoPlantRootCounts;
    int32_t autoPlantThreshold = 1;
    bool autoAlchemyFocused = false;
    std::vector<int32_t> autoAlchemyRootCounts;
    int32_t autoAlchemyThreshold = 1;
    bool autoForgeFocused = false;
    std::vector<int32_t> autoForgeRootCounts;
    int32_t autoForgeThreshold = 1;
    bool autoSingleResidenceFocused = false;
    std::vector<int32_t> autoSingleResidenceRootCounts;
    int32_t autoSingleResidenceThreshold = 1;
    bool autoMultiResidenceFocused = false;
    std::vector<int32_t> autoMultiResidenceRootCounts;
    int32_t autoMultiResidenceThreshold = 1;
    bool openRecruitment = false;
    bool asceticTraining = false;
    bool curfew = false;
    bool rewardPunish = false;
    bool strictTraining = false;
    bool relaxedMgmt = false;
    bool spiritSpring = false;
    bool frugality = false;
    bool moralEducation = false;
    bool benevolentGovernance = false;
};

/// ProductionSlot（生产槽位）
/// 注：slotId / requiredMaterials 为 @Transient（不参与序列化）——快照协议不含
struct ProductionSlot {
    std::string id;
    int32_t slotIndex = 0;
    std::string buildingType;             // BuildingType.name
    std::string buildingId;
    std::string status;                   // ProductionSlotStatus.name
    std::optional<std::string> recipeId;  // String?（NullableStringAsEmpty：JSON 中 ""=null）
    std::string recipeName;
    int32_t startYear = 0;
    int32_t startMonth = 0;
    int32_t duration = 0;
    int32_t baseDuration = 0;
    std::optional<std::string> assignedDiscipleId;  // String?
    std::string assignedDiscipleName;
    double successRate = 0.0;
    std::optional<std::string> outputItemId;  // String?
    std::string outputItemName;
    int32_t outputItemRarity = 0;
    std::string outputItemSlot;
    int32_t expectedYield = 0;
    bool autoRestartEnabled = false;
    int32_t completionMonth = 0;
    int32_t completionPhase = 0;
};

/// GridBuildingData（已放置建筑）
struct GridBuildingData {
    std::string buildingId;
    std::string displayName;
    int32_t gridX = 0;
    int32_t gridY = 0;
    int32_t width = 0;
    int32_t height = 0;
    std::string instanceId;
};

/// MerchantItem（旅行商人/玩家上架商品）
struct MerchantItem {
    std::string id;
    std::string name;
    std::string itemId;
    int32_t rarity = 0;
    int64_t price = 0;
    int32_t quantity = 0;
    std::string description;
    int32_t obtainedYear = 0;
    int32_t obtainedMonth = 0;
};

/// Alliance（结盟关系）
struct Alliance {
    std::string id;
    std::vector<std::string> sectIds;
    int32_t startYear = 0;
    std::string initiatorId;
};

/// VassalContract（附属契约）
struct VassalContract {
    int32_t index = 0;
    std::string discipleId;
    std::string discipleName;
    std::string discipleRealm;
    std::string discipleSpiritRootColor;
};

/// SectRelation（AI 宗门间关系）
struct SectRelation {
    std::string sectId1;
    std::string sectId2;
    int32_t favor = 0;
    int32_t lastInteractionYear = 0;
    int32_t noGiftYears = 0;
};

/// WorldSect（世界地图宗门）
struct WorldSect {
    std::string name;
    int32_t level = 0;
    std::string levelName;
    float x = 0.0f;
    float y = 0.0f;
    int32_t distance = 0;
    bool isPlayerSect = false;
    bool discovered = false;
    bool isKnown = false;
    int32_t relation = 0;
    std::map<int32_t, int32_t> disciples;   // Map<Int, Int>
    int32_t maxRealm = 0;
    bool isOccupied = false;
    std::string occupierTeamId;
    std::string occupierTeamName;
    std::string allianceId;
    int32_t allianceStartYear = 0;
    bool isRighteous = false;
    bool isPlayerOccupied = false;
    bool isUnderAttack = false;
    std::string attackerSectId;
    std::string occupierSectId;
};

/// ResidenceSlot（住所槽位）
struct ResidenceSlot {
    std::string buildingInstanceId;
    std::string discipleId;
    std::string discipleName;
    std::string sectId;
};

/// SpiritFieldPlant（灵田种植状态）
struct SpiritFieldPlant {
    std::string buildingInstanceId;
    std::string seedId;
    std::string seedName;
    int32_t growTime = 0;
    int32_t expectedYield = 0;
    int32_t plantYear = 0;
    int32_t plantMonth = 0;
    std::string sectId;
    int32_t completionMonth = 0;
};

/// PatrolConfig（巡视塔配置）
struct PatrolConfig {
    std::vector<int32_t> targetRealms;   // Set<Int> → JSON 数组
    int32_t maxBeastCount = 1;
};

/// WorldLevel（世界关卡：妖兽/洞府）
struct WorldLevel {
    std::string id;
    std::string type;                     // LevelType.name
    std::optional<int32_t> beastType;     // Int?
    int32_t realm = 0;
    int32_t realmLayer = 0;
    std::string beastName;
    std::string guardianName;
    std::string caveName;
    float x = 0.0f;
    float y = 0.0f;
    int32_t spawnYear = 0;
    int32_t spawnMonth = 0;
    int32_t expiryYear = 0;
    int32_t expiryMonth = 0;
    int32_t count = 0;
    int32_t caveImageIndex = 0;
    bool defeated = false;
    int32_t beastMaxHp = 0;
    int32_t beastMaxMp = 0;
    int32_t beastPhysicalAttack = 0;
    int32_t beastMagicAttack = 0;
    int32_t beastPhysicalDefense = 0;
    int32_t beastMagicDefense = 0;
};

// ── GameData.kt 内定义的轻量记录类型 ────────────────────────────────

/// MailClaimRecord（邮件领取记录）
struct MailClaimRecord {
    std::string mailId;
    int64_t claimedAt = 0;
    std::string source = "builtin";
};

/// SectLevelClaimRecord（宗门等级每周奖励领取记录）
struct SectLevelClaimRecord {
    int32_t level = 0;
    int64_t claimedAtEpochMs = 0;
};

/// YearlyReport（年度报告）
struct YearlyReport {
    int32_t year = 0;
    int64_t totalIncome = 0;
    int64_t totalExpenditure = 0;
    std::map<std::string, int64_t> incomeBySource;
    std::map<std::string, int64_t> expenditureByReason;
    int32_t forgeCompleted = 0;
    int32_t alchemyCompleted = 0;
    int32_t herbsHarvested = 0;
    std::map<std::string, int32_t> equipmentBySource;
    std::map<std::string, int32_t> pillBySource;
    std::map<std::string, int32_t> herbBySource;
    int32_t newDisciples = 0;
    int32_t deceasedDisciples = 0;
    int32_t desertedDisciples = 0;
};

/// PendingTraitAdd（天赋/体质/词条已刷新未确认产物）
struct PendingTraitAdd {
    std::string discipleId;
    std::string type;
    std::string traitId;
};

// ── GameData（核心标量 + 简单集合字段） ─────────────────────────────
struct GameData {
    // 基础/时间
    std::string id;
    std::string sectName = "青云宗";
    int32_t currentSlot = 1;
    int32_t gameYear = 1;
    int32_t gameMonth = 1;
    int32_t gamePhase = 0;
    // 资源
    int64_t spiritStones = 1000;
    int64_t midGradeSpiritStones = 0;
    int64_t highGradeSpiritStones = 0;
    int32_t spiritHerbs = 0;
    double sectCultivation = 0.0;
    // 存档/设置
    int32_t autoSaveIntervalMonths = 3;
    std::map<int32_t, int32_t> yearlySalary;
    std::map<int32_t, bool> yearlySalaryEnabled;
    std::string activeSectId;
    // 商人
    int32_t merchantLastRefreshYear = 0;
    int32_t merchantRefreshCount = 0;
    int32_t merchantRefreshChances = 1;
    int32_t merchantLastRefreshChanceGrantYear = 0;
    // 招募
    int32_t lastRecruitYear = 0;
    int32_t lastAiSectRecruitYear = 0;
    int32_t recruitCountThisMonth = 0;
    // 玉符（墙钟货币）
    int32_t jadeSymbols = 0;
    int32_t jadeSymbolsToday = 0;
    int64_t jadeDayAnchorMs = 0;
    int64_t jadeAccumMs = 0;
    // 世界/关卡
    int32_t worldLevelLastRefreshMonth = 0;
    std::map<int32_t, int64_t> rngStates;   // partitionId → PCG state
    // 解锁
    std::vector<std::string> unlockedRecipes;
    std::vector<std::string> unlockedManuals;
    // 槽位扩展计数
    int32_t spiritMineExpansions = 0;
    int32_t spiritMineLastSettledMonth = 0;
    // 队伍
    std::vector<int32_t> usedTeamNumbers;
    bool battleTeamsInitialized = false;
    // 存档
    int64_t lastSaveTime = 0;
    int32_t saveVersion = 0;
    // 玩家保护
    bool playerProtectionEnabled = true;
    int32_t playerProtectionStartYear = 1;
    bool playerHasAttackedAI = false;
    // 宗门
    int32_t playerAllianceSlots = 3;
    int32_t openRecruitmentLastPaidMonth = 0;
    // 滤网（Set<Int> → JSON 数组）
    std::vector<int32_t> autoRecruitSpiritRootFilter;
    std::vector<int32_t> prisonerSpiritRootFilter;
    std::vector<int32_t> autoRejectSpiritRootFilter;
    std::vector<int32_t> breakthroughAutoPillRootCounts;
    std::vector<int32_t> autoEquipFromWarehouseRootCounts;
    std::vector<int32_t> autoLearnFromWarehouseRootCounts;
    std::vector<int32_t> daoCompanionBannedRootCounts;
    std::vector<int32_t> guideClaimedRewardIds;
    // 设置开关
    bool daoCompanionConsentRequired = false;
    bool patrolBattleResultPopup = false;
    bool autoSellMidGradeForPurchase = false;
    bool autoSellHighGradeForPurchase = false;
    bool showAllAvailableDisciples = false;
    bool breakthroughAutoPillFocused = false;
    bool autoEquipFromWarehouseFocused = false;
    bool autoLearnFromWarehouseFocused = false;
    bool isGameOver = false;
    bool soundEnabled = true;
    bool musicEnabled = true;
    // 兑换码/关注/预警
    std::vector<std::string> usedRedeemCodes;
    std::vector<std::string> watchedItemIds;
    std::vector<std::string> shownWarningStageIds;
    // 秘境
    int32_t secretRealmCooldownYear = 0;
    // 附庸
    std::string suzerainSectId;
    int64_t lastYearSpiritStoneIncome = 0;
    // 地图
    int32_t mapSeed = 0;
    // 冷却/统计（Map）
    std::map<std::string, int32_t> sectAttackCooldowns;
    std::map<std::string, int64_t> guideCounters;
    // 年度报告
    std::map<std::string, int64_t> annualIncomeBySource;
    std::map<std::string, int64_t> annualExpenditureByReason;
    int64_t annualTotalIncome = 0;
    int64_t annualTotalExpenditure = 0;
    int32_t annualAlchemyCount = 0;
    int32_t annualForgeCount = 0;
    int32_t annualHerbCount = 0;
    int32_t annualNewDisciples = 0;
    int32_t annualDeceasedDisciples = 0;
    int32_t annualDesertedDisciples = 0;
    int32_t annualTheftCount = 0;
    int32_t theftJudgementsThisMonth = 0;
    std::map<std::string, int32_t> annualEquipmentBySource;
    std::map<std::string, int32_t> annualPillBySource;
    std::map<std::string, int32_t> annualHerbBySource;
    // ── 嵌套对象字段（批次 1 第二子步） ──
    std::vector<WorldSect> worldMapSects;
    std::vector<MerchantItem> travelingMerchantItems;
    std::vector<MerchantItem> playerListedItems;
    std::vector<MerchantItem> merchantAcquisitionItems;
    std::vector<Disciple> recruitList;
    std::vector<WorldLevel> worldLevels;
    ElderSlots elderSlots;
    std::vector<ProductionSlot> productionSlots;
    std::vector<GridBuildingData> placedBuildings;
    std::vector<SpiritFieldPlant> spiritFieldPlants;
    std::vector<ResidenceSlot> residenceSlots;
    PatrolConfig patrolConfig;
    std::vector<PatrolConfig> patrolConfigs;
    std::vector<Alliance> alliances;
    std::vector<VassalContract> vassalContracts;
    std::vector<SectRelation> sectRelations;
    SectPolicies sectPolicies;
    std::vector<MailClaimRecord> mailRecords;
    std::vector<SectLevelClaimRecord> sectLevelClaimRecords;
    std::map<std::string, std::vector<std::string>> bloodRefinements;
    std::vector<YearlyReport> yearlyReports;
    std::vector<PendingTraitAdd> pendingTraitAdds;
};

// ── 完整状态快照（GameCore 持有的真相状态） ────────────────────────
struct GameState {
    GameData gameData;
    std::vector<Disciple> disciples;
    std::vector<EquipmentStack> equipmentStacks;
    std::vector<EquipmentInstance> equipmentInstances;
    std::vector<ManualStack> manualStacks;
    std::vector<ManualInstance> manualInstances;
    std::vector<Pill> pills;
    std::vector<Material> materials;
    std::vector<Herb> herbs;
    std::vector<Seed> seeds;
    std::vector<StorageBag> storageBags;
};

}  // namespace gamecore::state
