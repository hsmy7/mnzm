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

// ── 储物袋条目体系（计划 v2 阶段 2 / T2.1：每旬结算需要） ────────────────

/// EquipmentNurtureData（Kotlin EquipmentNurtureData，字段名一致）
struct EquipmentNurtureData {
    std::string equipmentId;
    int32_t rarity = 0;
    int32_t nurtureLevel = 0;
    double nurtureProgress = 0.0;
};

/// BagStackedData（储物袋堆叠类物品的取回/物化重建补充数据）
struct BagStackedData {
    int32_t minRealm = 0;
    std::string slot;
    std::string manualType;
};

/// ItemEffect（丹药/物品效果——与 Kotlin ItemEffect 字段一一对应）
struct ItemEffect {
    int32_t tier = 0;                     // 丹药品阶（永久属性丹去重）
    double cultivationSpeedPercent = 0.0;
    double skillExpSpeedPercent = 0.0;
    double nurtureSpeedPercent = 0.0;
    double breakthroughChance = 0.0;
    int32_t targetRealm = 0;
    int32_t cultivationAdd = 0;
    int32_t skillExpAdd = 0;
    int32_t nurtureAdd = 0;
    double healMaxHpPercent = 0.0;
    double mpRecoverMaxMpPercent = 0.0;
    int32_t hpAdd = 0;
    int32_t mpAdd = 0;
    int32_t extendLife = 0;
    int32_t physicalAttackAdd = 0;
    int32_t magicAttackAdd = 0;
    int32_t physicalDefenseAdd = 0;
    int32_t magicDefenseAdd = 0;
    int32_t speedAdd = 0;
    double critRateAdd = 0.0;
    double critEffectAdd = 0.0;
    int32_t intelligenceAdd = 0;
    int32_t charmAdd = 0;
    int32_t loyaltyAdd = 0;
    int32_t comprehensionAdd = 0;
    int32_t artifactRefiningAdd = 0;
    int32_t pillRefiningAdd = 0;
    int32_t spiritPlantingAdd = 0;
    int32_t teachingAdd = 0;
    int32_t moralityAdd = 0;
    int32_t miningAdd = 0;
    bool revive = false;
    bool clearAll = false;
    bool isAscension = false;
    int32_t duration = 0;
    bool cannotStack = true;
    int32_t minRealm = 9;
    std::string pillCategory;
    std::string pillType;
};

/// StorageBagItem（弟子储物袋条目；effect/payload 为可选）
struct StorageBagItem {
    std::string itemId;
    std::string itemType;
    std::string name;
    int32_t rarity = 0;
    int32_t quantity = 1;
    int32_t obtainedYear = 1;
    int32_t obtainedMonth = 1;
    std::optional<ItemEffect> effect;                 // ItemEffect?
    std::optional<std::string> grade;                 // String?
    std::optional<int32_t> forgetYear;                // Int?
    std::optional<int32_t> forgetMonth;               // Int?
    std::optional<int32_t> forgetPhase;               // Int?
    std::optional<EquipmentInstance> equipmentInstance;   // 已物化装备实例
    std::optional<BagStackedData> stackedData;            // 堆叠类重建数据
    std::optional<ManualInstance> manualInstance;         // 忘功法实例
};

/// PillEffect（仓库丹药 Pill.effects 嵌套——突破自动服药读取 targetRealm/chance）
struct PillEffect {
    double breakthroughChance = 0.0;
    int32_t targetRealm = 0;
    bool isAscension = false;
    double cultivationSpeedPercent = 0.0;
    double skillExpSpeedPercent = 0.0;
    double nurtureSpeedPercent = 0.0;
    int32_t cultivationAdd = 0;
    int32_t skillExpAdd = 0;
    int32_t nurtureAdd = 0;
    int32_t duration = 3;
    bool cannotStack = true;
    int32_t physicalAttackAdd = 0;
    int32_t magicAttackAdd = 0;
    int32_t physicalDefenseAdd = 0;
    int32_t magicDefenseAdd = 0;
    int32_t hpAdd = 0;
    int32_t mpAdd = 0;
    int32_t speedAdd = 0;
    double critRateAdd = 0.0;
    double critEffectAdd = 0.0;
    int32_t extendLife = 0;
    int32_t intelligenceAdd = 0;
    int32_t charmAdd = 0;
    int32_t loyaltyAdd = 0;
    int32_t comprehensionAdd = 0;
    int32_t artifactRefiningAdd = 0;
    int32_t pillRefiningAdd = 0;
    int32_t spiritPlantingAdd = 0;
    int32_t teachingAdd = 0;
    int32_t moralityAdd = 0;
    int32_t miningAdd = 0;
    double healMaxHpPercent = 0.0;
    double mpRecoverMaxMpPercent = 0.0;
    bool revive = false;
    bool clearAll = false;
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
    PillEffect effects;                   // @Embedded 嵌套效果（T2.1 突破自动服药）
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

    // ── CombatAttributes（@Embedded 平铺；字段名与 Kotlin 序列化一致） ──
    int32_t baseHp = 120;
    int32_t baseMp = 60;
    int32_t basePhysicalAttack = 12;
    int32_t baseMagicAttack = 12;
    int32_t basePhysicalDefense = 10;
    int32_t baseMagicDefense = 8;
    int32_t baseSpeed = 15;
    int32_t hpVariance = 0;
    int32_t mpVariance = 0;
    int32_t physicalAttackVariance = 0;
    int32_t magicAttackVariance = 0;
    int32_t physicalDefenseVariance = 0;
    int32_t magicDefenseVariance = 0;
    int32_t speedVariance = 0;
    int64_t totalCultivation = 0;
    int32_t breakthroughCount = 0;        // combat.breakthroughCount（历史成功次数）
    int32_t breakthroughFailCount = 0;
    int32_t currentHp = -1;               // -1 = 满血（向后兼容语义）
    int32_t currentMp = -1;

    // ── PillEffects（@Embedded 平铺） ──
    int32_t pillPhysicalAttackBonus = 0;
    int32_t pillMagicAttackBonus = 0;
    int32_t pillPhysicalDefenseBonus = 0;
    int32_t pillMagicDefenseBonus = 0;
    int32_t pillHpBonus = 0;
    int32_t pillMpBonus = 0;
    int32_t pillSpeedBonus = 0;
    double pillCritRateBonus = 0.0;
    double pillCritEffectBonus = 0.0;
    double pillCultivationSpeedBonus = 0.0;
    double pillSkillExpSpeedBonus = 0.0;
    double pillNurtureSpeedBonus = 0.0;
    int32_t pillEffectDuration = 0;
    std::vector<std::string> activePillTypes;   // 生效中丹药 pillType 集合
    std::string activePillCategory;             // 旧字段，仅旧存档兼容

    // ── EquipmentSet（@Embedded 平铺） ──
    std::string weaponId;
    std::string armorId;
    std::string bootsId;
    std::string accessoryId;
    EquipmentNurtureData weaponNurture;
    EquipmentNurtureData armorNurture;
    EquipmentNurtureData bootsNurture;
    EquipmentNurtureData accessoryNurture;
    std::vector<StorageBagItem> storageBagItems;
    int64_t storageBagSpiritStones = 0;
    int32_t spiritStones = 0;             // 弟子随身灵石

    // ── SocialData（@Embedded 平铺；""=null，-1=null 哨兵） ──
    std::string partnerId;
    std::string partnerSectId;
    std::string parentId1;
    std::string parentId2;
    int32_t lastChildYear = 0;
    int32_t childBirthMonth = 0;          // 0 = null 哨兵
    int32_t griefEndYear = -1;            // -1 = null 哨兵（无丧亲期）
    std::string masterId;

    // ── SkillStats（@Embedded 平铺） ──
    int32_t intelligence = 50;
    int32_t charm = 50;
    int32_t loyalty = 50;
    int32_t comprehension = 50;
    int32_t artifactRefining = 50;
    int32_t pillRefining = 50;            // 炼丹技能（SkillStats.pillRefining）
    int32_t spiritPlanting = 50;
    int32_t mining = 50;
    int32_t teaching = 50;
    int32_t morality = 50;
    int32_t aptitude = 50;
    int32_t salaryPaidCount = 0;
    int32_t salaryMissedCount = 0;
    int32_t alchemyLevel = 0;
    int32_t alchemyPromotionCount = 0;
    int32_t forgeLevel = 0;
    int32_t forgePromotionCount = 0;

    // ── UsageTracking（@Embedded 平铺） ──
    std::vector<std::string> usedPermanentPillKeys;    // "tier#field" 去重 key
    std::vector<std::string> usedExtendLifePillTypes;  // 延寿丹按 pillType 去重
    std::vector<std::string> usedFunctionalPillTypes;  // 旧字段，兼容保留
    std::vector<std::string> usedExtendLifePillIds;    // 旧字段，兼容保留
    int32_t recruitedMonth = 0;
    bool hasReviveEffect = false;
    bool hasClearAllEffect = false;

    // 注：lifeEvents 是 Kotlin 类体属性（非序列化），不纳入快照协议
};

// ── 嵌套类型（批次 1 第二子步；字段名与 Kotlin @Serializable 一致） ──

/// GarrisonSlot（宗门驻防槽位；Kotlin GarrisonSlot）
struct GarrisonSlot {
    int32_t index = 0;
    std::string discipleId;
    std::string discipleName;
    std::string discipleRealm;
    std::string discipleSpiritRootColor = "#E0E0E0";
    std::string portraitRes;
};

/// BattleTeamSlot（战斗队伍槽位；Kotlin BattleTeamSlot；slotType 存 name）
struct BattleTeamSlot {
    int32_t index = 0;
    std::string discipleId;
    std::string discipleName;
    std::string discipleRealm;
    std::string slotType = "DISCIPLE";  // BattleSlotType.name
    bool isAlive = true;
};

/// BattleTeam（战斗队伍；Kotlin BattleTeam）
struct BattleTeam {
    std::string id;
    std::string name = "战斗队伍";
    int32_t teamNumber = 0;
    std::vector<BattleTeamSlot> slots;
    bool isAtSect = true;
    float currentX = 0.0f;
    float currentY = 0.0f;
    float targetX = 0.0f;
    float targetY = 0.0f;
    std::string status = "idle";
    std::string targetSectId;
    std::string originSectId;
    std::vector<std::string> route;
    int32_t currentRouteIndex = 0;
    float moveProgress = 0.0f;
    bool isOccupying = false;
    std::string occupiedSectId;
    bool isReturning = false;
};

/// WarehouseGarrisonSlot（仓库驻守槽位；Kotlin WarehouseGarrisonSlot）
struct WarehouseGarrisonSlot {
    std::string buildingInstanceId;
    std::string discipleId;
    std::string discipleName;
    std::string sectId;
    int32_t slotIndex = 0;
};

/// CaveExplorationTeam（洞府探索队；Kotlin CaveExplorationTeam；
/// status 存 CaveExplorationStatus.name）
struct CaveExplorationTeam {
    std::string id;
    std::string caveId;
    std::string caveName;
    std::vector<std::string> memberIds;
    std::vector<std::string> memberNames;
    int32_t startYear = 1;
    int32_t startMonth = 1;
    int32_t duration = 1;
    std::string status = "TRAVELING";  // CaveExplorationStatus.name
    float startX = 2000.0f;
    float startY = 1750.0f;
    float targetX = 0.0f;
    float targetY = 0.0f;
    float currentX = 2000.0f;
    float currentY = 1750.0f;
    float moveProgress = 0.0f;
};

/// ActiveMission 精简版（批 4-5 槽位清理协议内部用）：
/// Kotlin ActiveMission 依赖 MissionTemplate/MissionRewardConfig 等重模型，
/// 清理仅需 id + 成员两列表——C++ 侧只做成员过滤，完整模型由 Kotlin 保留。
struct ActiveMissionLite {
    std::string id;
    std::vector<std::string> discipleIds;
    std::vector<std::string> discipleNames;
};

/// MailAttachment（邮件附件；Kotlin MailAttachment）
struct MailAttachment {
    std::string type;
    std::string name;
    int32_t quantity = 0;
    int32_t rarity = 0;
    std::optional<std::string> itemId;              // String?（null = 无）
    std::map<std::string, std::string> extra;       // Map<String, String>
};

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

/// RoadData（石板道路——Kotlin RoadData，2026-08-31 状态模型迁移批次）
/// 玩家只负责放置，邻接位掩码/形态由 road_system.h 纯函数推导；
/// 本字段仅做状态承载（C++ 无道路结算逻辑，settle 不修改），
/// 随导入/反向回导与 Kotlin 双向一致（dirty_tracker 字段级自动 diff）。
struct RoadData {
    int32_t gridX = 0;
    int32_t gridY = 0;
    int32_t bitMask = 0;        // 邻接位掩码（上1右2下4左8；0=无邻居）
    std::string roadType = "SINGLE";  // RoadTileType 名（Kotlin RoadTileType.name）
};

/// MerchantItem（旅行商人/玩家上架商品）
struct MerchantItem {
    std::string id;
    std::string name;
    std::string type;             // equipment/manual/pill/material/herb/seed/spiritstone
                                  //（批 11-3 补齐——Kotlin @ProtoNumber(3)）
    std::string itemId;
    int32_t rarity = 0;
    int64_t price = 0;
    int32_t quantity = 0;
    std::string description;
    int32_t obtainedYear = 0;
    int32_t obtainedMonth = 0;
    std::optional<std::string> grade;   // String?（批 11-3 补齐——Kotlin @ProtoNumber(11)；
                                        // 丹药品质 displayName，null=中品）
};

/// AutoBuyEntry（自动购买条目——Kotlin AutoBuyEntry；批 11-3 补齐，
/// 唯一键 "$itemName:$itemType:$rarity" 去重匹配）
struct AutoBuyEntry {
    std::string itemName;
    std::string itemType;
    int32_t rarity = 0;
};

// ── 任务域模型（批 12-2：S8 子事件 14 任务刷新下沉） ──────────────
// 枚举按 name-string 约定承载（Kotlin @Serializable 枚举经 kotlinx JSON
// 序列化为 name），与 EquipmentSlot 等既有枚举约定一致。

/// MissionDifficulty（任务难度；name-string）
struct MissionDifficulty {
    static constexpr const char* kSimple = "SIMPLE";
    static constexpr const char* kNormal = "NORMAL";
    static constexpr const char* kHard = "HARD";
    static constexpr const char* kForbidden = "FORBIDDEN";
};

/// MissionType（任务类型；name-string）
struct MissionTypeName {
    static constexpr const char* kNoCombat = "NO_COMBAT";
    static constexpr const char* kCombatRequired = "COMBAT_REQUIRED";
    static constexpr const char* kCombatRandom = "COMBAT_RANDOM";
};

/// EnemyType（敌人类型；name-string）
struct EnemyTypeName {
    static constexpr const char* kBeast = "BEAST";
    static constexpr const char* kHuman = "HUMAN";
};

/// MissionTemplate（24 个任务模板枚举；name-string，对应 Kotlin MissionTemplate）
struct MissionTemplateName {
    // 低阶（SIMPLE）
    static constexpr const char* kEscortCaravan = "ESCORT_CARAVAN";
    static constexpr const char* kPatrolTerritory = "PATROL_TERRITORY";
    static constexpr const char* kDeliverSupplies = "DELIVER_SUPPLIES";
    static constexpr const char* kSuppressLowBeasts = "SUPPRESS_LOW_BEASTS";
    static constexpr const char* kClearBandits = "CLEAR_BANDITS";
    static constexpr const char* kExploreAbandonedMine = "EXPLORE_ABANDONED_MINE";
    // 中阶（NORMAL）
    static constexpr const char* kEscortSpiritCaravan = "ESCORT_SPIRIT_CARAVAN";
    static constexpr const char* kInvestigateAnomaly = "INVESTIGATE_ANOMALY";
    static constexpr const char* kDeliverPills = "DELIVER_PILLS";
    static constexpr const char* kSuppressJindanBeasts = "SUPPRESS_JINDAN_BEASTS";
    static constexpr const char* kDestroyMagicOutpost = "DESTROY_MAGIC_OUTPOST";
    static constexpr const char* kExploreAncientCave = "EXPLORE_ANCIENT_CAVE";
    // 高阶（HARD）
    static constexpr const char* kEscortImmortalEnvoy = "ESCORT_IMMORTAL_ENVOY";
    static constexpr const char* kRepairAncientFormation = "REPAIR_ANCIENT_FORMATION";
    static constexpr const char* kSearchMissingElder = "SEARCH_MISSING_ELDER";
    static constexpr const char* kSuppressHuashenBeastKing = "SUPPRESS_HUASHEN_BEAST_KING";
    static constexpr const char* kDestroyMagicBranch = "DESTROY_MAGIC_BRANCH";
    static constexpr const char* kExploreAncientBattlefield = "EXPLORE_ANCIENT_BATTLEFIELD";
    // 顶阶（FORBIDDEN）
    static constexpr const char* kEscortRelicArtifact = "ESCORT_RELIC_ARTIFACT";
    static constexpr const char* kSealSpatialRift = "SEAL_SPATIAL_RIFT";
    static constexpr const char* kSearchSecretRealmClue = "SEARCH_SECRET_REALM_CLUE";
    static constexpr const char* kSuppressAncientFiend = "SUPPRESS_ANCIENT_FIEND";
    static constexpr const char* kDestroyMagicHeadquarters = "DESTROY_MAGIC_HEADQUARTERS";
    static constexpr const char* kExploreCoreBattlefield = "EXPLORE_CORE_BATTLEFIELD";
};

/// MissionRewardConfig（任务奖励配置；Kotlin MissionRewardConfig 21 字段）
struct MissionRewardConfig {
    int32_t spiritStones = 0;
    int32_t spiritStonesMax = 0;
    int32_t materialCountMin = 0;
    int32_t materialCountMax = 0;
    int32_t materialMinRarity = 1;
    int32_t materialMaxRarity = 2;
    int32_t pillCountMin = 0;
    int32_t pillCountMax = 0;
    int32_t pillMinRarity = 1;
    int32_t pillMaxRarity = 1;
    double equipmentChance = 0.0;
    int32_t equipmentMinRarity = 1;
    int32_t equipmentMaxRarity = 1;
    double manualChance = 0.0;
    int32_t manualMinRarity = 1;
    int32_t manualMaxRarity = 1;
    int32_t baseSpiritStones = 0;
    int32_t baseMaterialCountMin = 0;
    int32_t baseMaterialCountMax = 0;
    int32_t baseMaterialMinRarity = 1;
    int32_t baseMaterialMaxRarity = 1;
};

/// Mission（任务；Kotlin Mission @Serializable 全字段）
struct Mission {
    std::string id;
    std::string template_;       // MissionTemplate.name（template 为 C++ 关键字，用 template_）
    std::string name;
    std::string description;
    std::string difficulty;      // MissionDifficulty.name
    int32_t duration = 0;
    MissionRewardConfig rewards;
    std::string missionType;     // MissionType.name
    std::string enemyType;       // EnemyType.name
    double triggerChance = 0.0;
    int32_t createdYear = 1;
    int32_t createdMonth = 1;
};

/// Alliance（结盟关系）
struct Alliance {
    std::string id;
    std::vector<std::string> sectIds;
    int32_t startYear = 0;
    std::string initiatorId;
};

/// VassalContract（附属契约；批 10-4 修正为 Kotlin GameDataAlliance.VassalContract
/// 真实形状——原占位结构 index/discipleId/… 系批 4-5 补齐模型时误植 GarrisonSlot
/// 形状，因既有对拍场景从不填充该字段而休眠未暴露）
struct VassalContract {
    std::string vassalSectId;
    int32_t establishedYear = 0;
    int32_t lastTributeYear = 0;
};

/// SectRelation（AI 宗门间关系）
struct SectRelation {
    std::string sectId1;
    std::string sectId2;
    int32_t favor = 0;
    int32_t lastInteractionYear = 0;
    int32_t noGiftYears = 0;
    bool acquainted = false;   // 批 10-4 补齐（Kotlin SectRelation.acquainted）
};

/// SectBattleRecord（宗门战报；批 10-4 附庸脱离近 3 年计数消费）
struct SectBattleRecord {
    int32_t year = 0;
    std::string type;          // SectBattleType.name（CONQUEST/LOST_SECT/BATTLE_WIN/BATTLE_LOSS）
};

// ── 宗门详情域（批 10-1：S8 侦察过期清理子事件协议扩容）──────────

/// MineSlot（矿脉槽位）
struct MineSlot {
    int32_t index = 0;
    std::string discipleId;
    std::string discipleName;
    int32_t output = 0;
    double efficiency = 1.0;
    bool isActive = false;
};

/// WarehouseItem（宗门仓库物品）
struct WarehouseItem {
    std::string itemId;
    std::string itemName;
    std::string itemType;
    int32_t rarity = 1;
    int32_t quantity = 1;
};

/// SectWarehouse（宗门仓库）
struct SectWarehouse {
    std::vector<WarehouseItem> items;
    int64_t spiritStones = 0;
    int64_t midGradeSpiritStones = 0;
    int64_t highGradeSpiritStones = 0;
};

/// SectScoutInfo（宗门侦查信息；disciples/resources 为 kotlinx Map 键字符串化）
struct SectScoutInfo {
    std::string sectId;
    std::string sectName;
    int32_t scoutYear = 0;
    int32_t scoutMonth = 0;
    int32_t discipleCount = 0;
    int32_t maxRealm = 9;
    std::map<std::string, int32_t> resources;   // Map<String, Int>
    bool isKnown = false;
    std::map<std::string, int32_t> disciples;   // Map<Int, Int>
    int32_t expiryYear = 0;
    int32_t expiryMonth = 0;
};

/// SectDetail（宗门详情；giftPreference = GiftPreferenceType.name）
struct SectDetail {
    std::string sectId;
    std::vector<MineSlot> mineSlots;
    int64_t occupationTime = 0;
    bool isOwned = false;
    int32_t expiryYear = 0;
    int32_t expiryMonth = 0;
    SectScoutInfo scoutInfo;
    std::vector<MerchantItem> tradeItems;
    int32_t tradeLastRefreshYear = 0;
    int32_t lastGiftYear = 0;
    SectWarehouse warehouse;
    std::string giftPreference = "NONE";        // GiftPreferenceType.name（Kotlin 默认 NONE）
    std::string portraitRes;
};

/// WorldSect（世界地图宗门）
struct WorldSect {
    std::string id;                         // T2.2 补齐（Kotlin @ProtoNumber(1)；gameOverCheck 占领判定需要）
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
    std::vector<GarrisonSlot> garrisonSlots;   // 玩家宗门驻防槽位（批 4-5）
};

/// ResidenceSlot（住所槽位——批 13-3 修正为 Kotlin 真实形状：
/// buildingInstanceId/slotIndex/discipleId/discipleName；原 sectId 系误植
/// 冗余字段（Kotlin 无），删除对齐协议）
struct ResidenceSlot {
    std::string buildingInstanceId;
    int32_t slotIndex = 0;
    std::string discipleId;
    std::string discipleName;
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
    int32_t completionPhase = 1;
};

/// PatrolConfig（巡视塔配置）
struct PatrolConfig {
    std::vector<int32_t> targetRealms;   // Set<Int> → JSON 数组
    int32_t maxBeastCount = 1;
    bool requireFullStatus = true;
};

/// PatrolSlot（巡视槽位——Kotlin PatrolSlot）
struct PatrolSlot {
    int32_t index = 0;
    std::string discipleId;
    std::string discipleName;
    std::string discipleRealm;
    std::string portraitRes;
    std::string buildingInstanceId;
};

// ── SecretRealm（远古秘境）状态机（批次 1 剩余）────────────────────────

/// SecretRealmState（远古秘境地图实例；id 为空 = 当前不存在）
struct SecretRealmState {
    std::string id;
    std::string name = "远古秘境";
    float x = 0.0f;
    float y = 0.0f;
    int32_t spawnYear = 1;
    int32_t spawnMonth = 1;
    int32_t spriteIndex = 0;
};

/// SecretRealmMemberState（探索队伍成员动态状态）
struct SecretRealmMemberState {
    std::string discipleId;
    std::string name;
    std::string portraitRes;
    int32_t realm = 9;
    std::string realmName;
    int32_t currentHp = -1;      // -1 = 满血（与 Disciple.combat.currentHp 语义一致）
    bool isDying = false;        // 重伤濒死（保命一次）
    bool isDead = false;         // 已永久死亡
    int32_t maxHp = 0;           // 战斗口径最大生命值；0 = 未知
};

/// SecretRealmOption（事件选项）
struct SecretRealmOption {
    std::string label;
    std::string description;
    int32_t staminaCost = 1;
};

/// SecretRealmRewardItem（奖励物品描述，结算时才实例化入仓）
struct SecretRealmRewardItem {
    std::string type;            // equipment / manual / pill / material / herb / seed
    std::string itemId;
    std::string name;
    int32_t rarity = 1;
    int32_t quantity = 1;
};

/// SecretRealmAIMember（AI 宗门队伍成员）
struct SecretRealmAIMember {
    std::string discipleId;
    std::string name;
    std::string portraitRes;
    int32_t realm = 9;
};

/// SecretRealmEventParams（妖兽事件参数，读档一致性关键）
struct SecretRealmEventParams {
    std::string beastTypeName;
    int32_t beastRealm = 9;
    int32_t beastCount = 1;
    bool ambushSucceeded = false;   // 偷袭成功：初始血量 -10%
    int32_t beastLayer = 1;         // 妖兽层数 1..9
    int32_t lostItemCount = 0;      // 战斗失败丢失件数
    int64_t spiritStones = 0;
    std::vector<SecretRealmRewardItem> itemRewards;
    std::string aiSectId;           // AI 宗门遭遇
    std::string aiSectName;
    int32_t aiSectLevel = 0;        // 0小型/1中型/2大型/3顶级
    std::vector<SecretRealmAIMember> aiMembers;
};

/// SecretRealmEventRecord（探索事件记录——整个事件序列化，读档可继续）
struct SecretRealmEventRecord {
    std::string eventType;          // SecretRealmEventType.name
    std::string title;
    std::string description;
    std::vector<SecretRealmOption> options;
    int32_t chosenOptionIndex = -1; // -1 = 未选择（进行中）
    std::string resultText;
    SecretRealmEventParams params;
    int32_t absoluteMonth = 0;
};

/// SecretRealmBackpack（探索背包——暂存探索所得，结束统一入宗门仓库）
struct SecretRealmBackpack {
    int64_t spiritStones = 0;
    std::vector<EquipmentStack> equipment;
    std::vector<ManualStack> manuals;
    std::vector<Pill> pills;
    std::vector<Material> materials;
    std::vector<Herb> herbs;
    std::vector<Seed> seeds;
};

/// SecretRealmExplorationSession（完整持久化，支撑断线续玩）
struct SecretRealmExplorationSession {
    std::string secretRealmId;
    std::vector<SecretRealmMemberState> members;
    int32_t stamina = 20;
    SecretRealmBackpack backpack;
    std::optional<SecretRealmEventRecord> currentEvent;   // 最近未完成事件
    std::vector<SecretRealmEventRecord> eventHistory;     // 已完成事件序列
    int32_t startYear = 1;
    int32_t startMonth = 1;
    std::string resultMessage;      // 上个事件结算结果描述
};

/// SecretRealmAITeam（AI 宗门探索队伍——仅派遣占位）
struct SecretRealmAITeam {
    std::string id;
    std::string sectId;
    std::string sectName;
    std::vector<SecretRealmAIMember> members;
    int32_t sectLevel = 0;
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
    int32_t beastSpeed = 0;
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

// ── 批次 1 剩余：低频嵌套类型（与 Kotlin @Serializable 字段名一致） ──

/// BloodRefinementProgress（血炼进行中）
struct BloodRefinementProgress {
    std::string discipleId;
    std::string discipleName;
    std::string materialId;
    std::string materialName;
    int32_t startYear = 0;
    int32_t startMonth = 0;
    int32_t durationMonths = 0;
    std::string selectedStat;
    double bonusPercent = 0.0;
};

/// BloodRefinementBonusTotal（血炼累计——单利旧格式，存档兼容）
struct BloodRefinementBonusTotal {
    std::string discipleId;
    int32_t hpBonus = 0;
    int32_t physicalAttackBonus = 0;
    int32_t magicAttackBonus = 0;
    int32_t physicalDefenseBonus = 0;
    int32_t magicDefenseBonus = 0;
    int32_t speedBonus = 0;
};

/// BloodRefinementPctTotal（血炼累计——百分比乘区格式）
struct BloodRefinementPctTotal {
    std::string discipleId;
    double hpBonusPct = 0.0;
    double physicalAttackBonusPct = 0.0;
    double magicAttackBonusPct = 0.0;
    double physicalDefenseBonusPct = 0.0;
    double magicDefenseBonusPct = 0.0;
    double speedBonusPct = 0.0;
};

/// ManualProficiencyData（功法熟练度）
struct ManualProficiencyData {
    std::string manualId;
    std::string manualName;
    double proficiency = 0.0;
    int32_t maxProficiency = 100;
    int32_t level = 1;
    int32_t masteryLevel = 0;
};

/// SpiritMineSlot（矿脉槽位——Kotlin SpiritMineSlot）
struct SpiritMineSlot {
    int32_t index = 0;
    std::string discipleId;
    std::string discipleName;
    int32_t output = 100;
    std::string sectId;
    int32_t consecutiveMiningMonths = 0;
    std::string buildingInstanceId;
};

/// LibrarySlot（藏经阁槽位——Kotlin LibrarySlot）
struct LibrarySlot {
    int32_t index = 0;
    std::string buildingInstanceId;
    std::string discipleId;
    std::string discipleName;
};

/// GameEventRecord（消息栏游戏事件记录——Kotlin GameEventRecord）
/// 注：timestamp 为现实墙钟（Kotlin 默认 System.currentTimeMillis()），
/// 对拍不比较该字段（Clock 注入边界）。
struct GameEventRecord {
    int64_t timestamp = 0;
    int32_t year = 1;
    int32_t month = 1;
    int32_t phase = 0;
    std::string category = "SECT";        // "WORLD" / "SECT"
    std::string eventType;
    std::string summary;
    std::string relatedEntityId;
    std::string relatedEntityName;
    int64_t sequenceId = 0;
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
    // 批次 1 剩余：远古秘境状态机（玩法状态，结算不参与）
    SecretRealmState secretRealmState;
    SecretRealmExplorationSession secretRealmSession;
    std::vector<SecretRealmAITeam> secretRealmAITeams;
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
    std::vector<AutoBuyEntry> autoBuyList;   // 批 11-3：自动购买条目（Kotlin EconomicState）
    std::vector<Disciple> recruitList;
    std::vector<WorldLevel> worldLevels;
    ElderSlots elderSlots;
    std::vector<ProductionSlot> productionSlots;
    std::vector<GridBuildingData> placedBuildings;
    // ── 2026-08-31：石板道路状态迁移批次（Kotlin GameData.roads；C++ 仅承载状态，
    //    放置/拼接逻辑在 Kotlin RoadFacade + C++ road_system.h 纯函数，settle 不修改）
    std::vector<RoadData> roads;
    std::vector<SpiritFieldPlant> spiritFieldPlants;
    std::vector<ResidenceSlot> residenceSlots;
    PatrolConfig patrolConfig;
    std::vector<PatrolConfig> patrolConfigs;
    std::vector<Alliance> alliances;
    std::vector<VassalContract> vassalContracts;
    std::vector<SectRelation> sectRelations;
    // 批 10-4：宗门战报（附庸脱离近 3 年计数消费）
    std::vector<SectBattleRecord> sectBattleRecords;
    std::map<std::string, SectDetail> sectDetails;      // Map<String, SectDetail>（批 10-1）
    std::map<std::string, SectScoutInfo> scoutInfo;     // Map<String, SectScoutInfo>（批 10-1）
    SectPolicies sectPolicies;
    std::vector<MailClaimRecord> mailRecords;
    std::vector<SectLevelClaimRecord> sectLevelClaimRecords;
    std::map<std::string, std::vector<std::string>> bloodRefinements;
    std::vector<YearlyReport> yearlyReports;
    std::vector<PendingTraitAdd> pendingTraitAdds;
    // ── 批次 1 剩余：低频嵌套类型字段 ──
    std::map<std::string, std::vector<ManualProficiencyData>> manualProficiencies;
    std::vector<SpiritMineSlot> spiritMineSlots;
    std::map<std::string, BloodRefinementBonusTotal> bloodRefinementBonusTotals;
    std::map<std::string, BloodRefinementPctTotal> bloodRefinementPctTotals;
    std::map<std::string, BloodRefinementProgress> activeBloodRefinements;
    std::vector<PatrolSlot> patrolSlots;
    // ── T2.1 每旬结算依赖字段 ──
    std::vector<LibrarySlot> librarySlots;             // 藏经阁槽位（熟练度加成）
    std::vector<GameEventRecord> gameEventRecords;     // 消息栏事件（突破记录）
    // ── 批 4-5：槽位清理补充模型（宽松 from_json 默认空，旧档兼容） ──
    std::vector<BattleTeam> battleTeams;
    std::vector<WarehouseGarrisonSlot> warehouseGarrisons;
    std::vector<CaveExplorationTeam> caveExplorationTeams;
    std::vector<ActiveMissionLite> activeMissions;
    // ── 批 12-2：任务域（S8 子事件 14 任务刷新下沉） ──
    std::vector<Mission> availableMissions;   // Kotlin GameData.availableMissions
};

}  // namespace gamecore::state

// ── 完整状态快照（GameCore 持有的真相状态） ────────────────────────
// DiscipleStore（SoA 列式存储）定义于 disciple_store.h——此处 Disciple 及
// 全部嵌套类型已完整定义（include guard 防循环；在命名空间外包含避免嵌套）。
#include "gamecore/state/disciple_store.h"

namespace gamecore::state {

struct GameState {
    GameData gameData;
    DiscipleStore disciples;                    // SoA 列式存储（计划 v2 阶段 3）
    // 招募惰性门（Kotlin RecruitService.RecruitLazyState.autoRecruitIdle 等价）：
    // 纯内存运行态，不进 JSON 协议（json_codec 不导出/导入），读档即 false；
    // 重置点（年度招募列表刷新/玩家改筛选/生育/净化）在 Kotlin 侧（月变真相源
    // 切换前），C++ 侧仅月结子事件内部置 true——跨层同步随月变真相源切换批接线
    //（S-16 登记）。Diff 对拍须双侧显式复位。
    bool autoRecruitIdle = false;
    // 批 10-4：AI 宗门弟子池（Kotlin GameData.aiSectDisciples 为 @Transient
    // 重型数据——不进存档序列化，故快照协议置于顶层，与 Kotlin
    // NativeGameState.aiSectDisciples 一一对应；DirtyTracker 仅跟踪 gameData
    // 字段与固定集合清单，本字段不进脏导出——镜像通道零污染，Kotlin 侧
    // 不回写保持权威，反向回导随月变真相源切换批接线（S-15））
    std::map<std::string, std::vector<Disciple>> aiSectDisciples;
    // 批 13-1：AI 宗门妖兽攻击域（Kotlin GameData 同名三字段均为 @Transient——
    // 不进存档序列化，纯运行态；快照协议置于顶层与 NativeGameState 一一对应，
    // 语义同 aiSectDisciples（非空才导出、宽松导入、DirtyTracker 零污染））。
    //   aiSectBeastDirectTargets：妖兽 → 已确认进攻的 AI 宗门 id 列表（≤2，距离升序）
    //   aiSectBeastSkipCooldowns：AI 宗门 id → 跳过进攻的绝对月（年×12+月）
    //   lockedBeastIds：被玩家锁定（弹窗打开中）的妖兽 id 集合（月度结算跳过）
    std::map<std::string, std::vector<std::string>> aiSectBeastDirectTargets;
    std::map<std::string, int32_t> aiSectBeastSkipCooldowns;
    std::vector<std::string> lockedBeastIds;
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
