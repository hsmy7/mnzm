#pragma once

// ============================================================
// 任务域月结下沉（S8 子事件 14：processMissionRefresh）
//
// Kotlin CultivationEventMissionOps.processMissionRefreshIfDue →
// MissionSystem.processMonthlyRefresh 等价移植。
//
// 语义要点（逐条对齐 Kotlin 源码）：
//   - 刷新门：month % REFRESH_INTERVAL_MONTHS(3) == 0 才刷新
//   - 刷新数：refreshCount = rng.nextInt(MAX_REFRESH_COUNT(6) + 1)
//   - 模板抽取：buildWeightedPool（24 模板按 difficulty.spawnChance
//     累积权重）→ weightedRandom（roll = nextDouble() * totalWeight，
//     首个累积权重 > roll 的模板）
//   - createMission：name = difficulty.displayName + template.displayName；
//     rewards = createTier1~4RewardConfig 四级回退（SIMPLE→tier1、
//     NORMAL→tier2、HARD→tier3、FORBIDDEN→tier4）
//   - cleanedMissions：刷新月清空旧列表（month%3==0 → emptyList()），
//     非刷新月保留旧列表；最终 = afterClean + newMissions
//   - Mission.id：Kotlin UUID.randomUUID()（镜像生成字段，对拍排除；
//     C++ 确定性自增——inventory.h generateNewId 同款契约）
//
// RNG 契约：仅消费 MISSION 分区（RngPartition::kMission）。
// 对拍命门：任务刷新位于子事件 14 位（附庸后、秘境前），其 MISSION 抽取
// 必须与 Kotlin 月变编排的相对序完全一致。
//
// 边界（登记）：
//   - Mission 完整模型（rewards/触发战斗等）仅刷新生成所需字段入协议；
//     任务完成结算属子事件 5（mission_completion.h），不在本文件范围
// ============================================================

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"

namespace gamecore::system::mission_settle {

using gamecore::state::Mission;
using gamecore::state::MissionRewardConfig;

/// 刷新间隔月数（MissionSystem.REFRESH_INTERVAL_MONTHS）
constexpr int32_t kRefreshIntervalMonths = 3;
/// 刷新数量上限（MissionSystem.MAX_REFRESH_COUNT）
constexpr int32_t kMaxRefreshCount = 6;

// ── 难度属性（Kotlin MissionDifficulty 枚举属性） ───────────────────

/// spawnChance（MissionDifficulty.spawnChance）
inline double difficultySpawnChance(const std::string& difficulty) {
    if (difficulty == gamecore::state::MissionDifficulty::kSimple) return 0.25;
    if (difficulty == gamecore::state::MissionDifficulty::kNormal) return 0.12;
    if (difficulty == gamecore::state::MissionDifficulty::kHard) return 0.03;
    return 0.005;   // FORBIDDEN
}

/// displayName（MissionDifficulty.displayName）
inline std::string difficultyDisplayName(const std::string& difficulty) {
    if (difficulty == gamecore::state::MissionDifficulty::kSimple) return "简单";
    if (difficulty == gamecore::state::MissionDifficulty::kNormal) return "普通";
    if (difficulty == gamecore::state::MissionDifficulty::kHard) return "困难";
    return "禁忌";   // FORBIDDEN
}

// ── 任务模板属性（Kotlin MissionTemplate 枚举属性） ────────────────

/// displayName（MissionTemplate.displayName）
inline std::string templateDisplayName(const std::string& t) {
    using N = gamecore::state::MissionTemplateName;
    if (t == N::kEscortCaravan) return "护送商队";
    if (t == N::kPatrolTerritory) return "巡查领地";
    if (t == N::kDeliverSupplies) return "运送物资";
    if (t == N::kSuppressLowBeasts) return "镇压低阶妖兽";
    if (t == N::kClearBandits) return "清缴山匪";
    if (t == N::kExploreAbandonedMine) return "探索废弃矿洞";
    if (t == N::kEscortSpiritCaravan) return "护送灵石商队";
    if (t == N::kInvestigateAnomaly) return "调查灵气异常";
    if (t == N::kDeliverPills) return "运送珍贵丹药";
    if (t == N::kSuppressJindanBeasts) return "镇压金丹妖兽群";
    if (t == N::kDestroyMagicOutpost) return "剿灭魔修哨站";
    if (t == N::kExploreAncientCave) return "探索古修士洞府";
    if (t == N::kEscortImmortalEnvoy) return "护送仙宗使者";
    if (t == N::kRepairAncientFormation) return "修复上古阵法";
    if (t == N::kSearchMissingElder) return "搜寻失踪长老";
    if (t == N::kSuppressHuashenBeastKing) return "镇压化神妖王";
    if (t == N::kDestroyMagicBranch) return "剿灭魔道分舵";
    if (t == N::kExploreAncientBattlefield) return "探索上古战场";
    if (t == N::kEscortRelicArtifact) return "护送远古遗迹出土灵物";
    if (t == N::kSealSpatialRift) return "封印空间裂隙";
    if (t == N::kSearchSecretRealmClue) return "搜寻远古秘境线索";
    if (t == N::kSuppressAncientFiend) return "镇压合体期上古凶兽";
    if (t == N::kDestroyMagicHeadquarters) return "剿灭魔道总坛外围";
    return "探索仙魔古战场核心";   // EXPLORE_CORE_BATTLEFIELD
}

/// description（MissionTemplate.description）
inline std::string templateDescription(const std::string& t) {
    using N = gamecore::state::MissionTemplateName;
    if (t == N::kEscortCaravan) return "护送凡人商队穿越安全区域，商队支付护送酬劳";
    if (t == N::kPatrolTerritory) return "巡查宗门周边领地，清理路障、标记危险区域，获得宗门津贴";
    if (t == N::kDeliverSupplies) return "将宗门采集的普通矿石运送到附近城镇，换取灵石";
    if (t == N::kSuppressLowBeasts) return "4-10只炼气到筑基期妖兽骚扰村庄，前往镇压";
    if (t == N::kClearBandits) return "4-8名炼气到筑基期山匪盘踞要道，劫掠过往行人";
    if (t == N::kExploreAbandonedMine) return "探索废弃灵矿洞搜寻残余矿石，洞内可能有妖兽栖息";
    if (t == N::kEscortSpiritCaravan) return "护送载有灵石的商队前往邻城，商队支付高额护送酬劳";
    if (t == N::kInvestigateAnomaly) return "调查某地灵气异常波动原因，记录数据提交宗门获得研究津贴";
    if (t == N::kDeliverPills) return "将宗门炼制的丹药运送到盟友宗门，盟友支付运费和谢礼";
    if (t == N::kSuppressJindanBeasts) return "4-10只金丹到元婴期妖兽作乱，袭击凡人城镇";
    if (t == N::kDestroyMagicOutpost) return "4-8名金丹到元婴期魔修建立前哨据点，威胁宗门周边安全";
    if (t == N::kExploreAncientCave) return "发现古修士洞府，搜寻遗留物品，可能触发洞府守护禁制";
    if (t == N::kEscortImmortalEnvoy) return "护送其他仙宗重要使者穿越险地，仙宗支付护送酬劳";
    if (t == N::kRepairAncientFormation) return "修复宗门领地内的上古守护阵法，宗门发放修缮津贴";
    if (t == N::kSearchMissingElder) return "搜寻宗门在外失踪的长老下落，找到线索获得悬赏";
    if (t == N::kSuppressHuashenBeastKing) return "4-10只化神到炼虚期妖王率领兽潮攻城，前往镇压";
    if (t == N::kDestroyMagicBranch) return "4-8名化神到炼虚期魔修坐镇魔道分舵，威胁方圆千里";
    if (t == N::kExploreAncientBattlefield) return "探索上古仙魔战场遗迹搜寻遗留物资，可能遭遇游荡的战魂";
    if (t == N::kEscortRelicArtifact) return "护送从远古遗迹出土的灵物前往安全地点，委托方支付高额酬劳";
    if (t == N::kSealSpatialRift) return "封印宗门领地上空出现的空间裂隙，宗门发放封印津贴和材料补偿";
    if (t == N::kSearchSecretRealmClue) return "搜寻传说中的远古秘境入口线索，找到线索获得宗门悬赏";
    if (t == N::kSuppressAncientFiend) return "4-10只合体到大乘期上古凶兽挣脱封印，四处肆虐";
    if (t == N::kDestroyMagicHeadquarters) return "4-8名合体到大乘期魔修长老镇守魔道总坛外围防线";
    return "探索仙魔古战场最核心区域搜寻遗留物资，九死一生";   // EXPLORE_CORE_BATTLEFIELD
}

/// difficulty（MissionTemplate.difficulty）
inline std::string templateDifficulty(const std::string& t) {
    using N = gamecore::state::MissionTemplateName;
    if (t == N::kEscortCaravan || t == N::kPatrolTerritory ||
        t == N::kDeliverSupplies || t == N::kSuppressLowBeasts ||
        t == N::kClearBandits || t == N::kExploreAbandonedMine) {
        return gamecore::state::MissionDifficulty::kSimple;
    }
    if (t == N::kEscortSpiritCaravan || t == N::kInvestigateAnomaly ||
        t == N::kDeliverPills || t == N::kSuppressJindanBeasts ||
        t == N::kDestroyMagicOutpost || t == N::kExploreAncientCave) {
        return gamecore::state::MissionDifficulty::kNormal;
    }
    if (t == N::kEscortImmortalEnvoy || t == N::kRepairAncientFormation ||
        t == N::kSearchMissingElder || t == N::kSuppressHuashenBeastKing ||
        t == N::kDestroyMagicBranch || t == N::kExploreAncientBattlefield) {
        return gamecore::state::MissionDifficulty::kHard;
    }
    return gamecore::state::MissionDifficulty::kForbidden;
}

/// missionType（MissionTemplate.missionType）
inline std::string templateMissionType(const std::string& t) {
    using N = gamecore::state::MissionTemplateName;
    // NO_COMBAT：护送/巡查/运送/调查/修复/搜寻（12 个）
    if (t == N::kEscortCaravan || t == N::kPatrolTerritory ||
        t == N::kDeliverSupplies || t == N::kEscortSpiritCaravan ||
        t == N::kInvestigateAnomaly || t == N::kDeliverPills ||
        t == N::kEscortImmortalEnvoy || t == N::kRepairAncientFormation ||
        t == N::kSearchMissingElder || t == N::kEscortRelicArtifact ||
        t == N::kSealSpatialRift || t == N::kSearchSecretRealmClue) {
        return gamecore::state::MissionTypeName::kNoCombat;
    }
    // COMBAT_REQUIRED：镇压/清缴/剿灭（8 个）
    if (t == N::kSuppressLowBeasts || t == N::kClearBandits ||
        t == N::kSuppressJindanBeasts || t == N::kDestroyMagicOutpost ||
        t == N::kSuppressHuashenBeastKing || t == N::kDestroyMagicBranch ||
        t == N::kSuppressAncientFiend || t == N::kDestroyMagicHeadquarters) {
        return gamecore::state::MissionTypeName::kCombatRequired;
    }
    // COMBAT_RANDOM：探索（4 个）
    return gamecore::state::MissionTypeName::kCombatRandom;
}

/// triggerChance（MissionTemplate.triggerChance）
inline double templateTriggerChance(const std::string& t) {
    using N = gamecore::state::MissionTemplateName;
    if (t == N::kExploreAbandonedMine) return 0.40;
    if (t == N::kExploreAncientCave) return 0.50;
    if (t == N::kExploreAncientBattlefield) return 0.60;
    if (t == N::kExploreCoreBattlefield) return 0.70;
    return 0.0;
}

/// enemyType（MissionTemplate.enemyType）
inline std::string templateEnemyType(const std::string& t) {
    using N = gamecore::state::MissionTemplateName;
    if (t == N::kSuppressLowBeasts || t == N::kSuppressJindanBeasts ||
        t == N::kSuppressHuashenBeastKing || t == N::kSuppressAncientFiend ||
        t == N::kExploreAbandonedMine) {
        return gamecore::state::EnemyTypeName::kBeast;
    }
    if (t == N::kClearBandits || t == N::kDestroyMagicOutpost ||
        t == N::kDestroyMagicBranch || t == N::kDestroyMagicHeadquarters ||
        t == N::kExploreAncientCave || t == N::kExploreAncientBattlefield) {
        return gamecore::state::EnemyTypeName::kHuman;
    }
    // EXPLORE_CORE_BATTLEFIELD → BEAST；其余 → BEAST
    return gamecore::state::EnemyTypeName::kBeast;
}

/// duration（MissionTemplate.duration）
inline int32_t templateDuration(const std::string& t) {
    using N = gamecore::state::MissionTemplateName;
    if (t == N::kEscortCaravan || t == N::kPatrolTerritory ||
        t == N::kDeliverSupplies) return 3;
    if (t == N::kSuppressLowBeasts || t == N::kClearBandits ||
        t == N::kExploreAbandonedMine) return 4;
    if (t == N::kEscortSpiritCaravan || t == N::kInvestigateAnomaly ||
        t == N::kDeliverPills) return 7;
    if (t == N::kSuppressJindanBeasts || t == N::kDestroyMagicOutpost ||
        t == N::kExploreAncientCave) return 8;
    if (t == N::kEscortImmortalEnvoy || t == N::kRepairAncientFormation ||
        t == N::kSearchMissingElder) return 36;
    if (t == N::kSuppressHuashenBeastKing || t == N::kDestroyMagicBranch ||
        t == N::kExploreAncientBattlefield) return 40;
    if (t == N::kEscortRelicArtifact || t == N::kSealSpatialRift ||
        t == N::kSearchSecretRealmClue) return 58;
    return 64;   // SUPPRESS_ANCIENT_FIEND/DESTROY_MAGIC_HEADQUARTERS/EXPLORE_CORE_BATTLEFIELD
}

/// requiredMemberCount（MissionTemplate.requiredMemberCount 恒 6）
inline int32_t templateRequiredMemberCount(const std::string&) { return 6; }

// ── 奖励配置（Kotlin MissionSystem.createTier1~4RewardConfig） ─────

inline MissionRewardConfig createRewardConfig(const std::string& template_);

/// 低阶任务奖励配置（createTier1RewardConfig）
inline MissionRewardConfig createTier1RewardConfig(const std::string& template_) {
    using N = gamecore::state::MissionTemplateName;
    MissionRewardConfig c;
    if (template_ == N::kEscortCaravan) {
        c.spiritStones = 600;
    } else if (template_ == N::kPatrolTerritory) {
        c.spiritStones = 300;
        c.materialCountMin = 5; c.materialCountMax = 10;
        c.materialMinRarity = 1; c.materialMaxRarity = 1;
    } else if (template_ == N::kDeliverSupplies) {
        c.spiritStones = 400;
        c.pillCountMin = 1; c.pillCountMax = 2;
        c.pillMinRarity = 1; c.pillMaxRarity = 1;
    } else if (template_ == N::kSuppressLowBeasts) {
        c.spiritStones = 400;
        c.materialCountMin = 10; c.materialCountMax = 15;
        c.materialMinRarity = 1; c.materialMaxRarity = 1;
    } else if (template_ == N::kClearBandits) {
        c.spiritStones = 500;
        c.materialCountMin = 8; c.materialCountMax = 12;
        c.materialMinRarity = 1; c.materialMaxRarity = 1;
        c.equipmentChance = 0.3;
        c.equipmentMinRarity = 1; c.equipmentMaxRarity = 1;
    } else if (template_ == N::kExploreAbandonedMine) {
        c.baseSpiritStones = 200; c.spiritStones = 500;
        c.baseMaterialCountMin = 3; c.baseMaterialCountMax = 5;
        c.baseMaterialMinRarity = 1; c.baseMaterialMaxRarity = 1;
        c.materialCountMin = 10; c.materialCountMax = 15;
        c.materialMinRarity = 1; c.materialMaxRarity = 2;
    }
    return c;
}

/// 中阶任务奖励配置（createTier2RewardConfig）
inline MissionRewardConfig createTier2RewardConfig(const std::string& template_) {
    using N = gamecore::state::MissionTemplateName;
    MissionRewardConfig c;
    if (template_ == N::kEscortSpiritCaravan) {
        c.spiritStones = 1500;
    } else if (template_ == N::kInvestigateAnomaly) {
        c.spiritStones = 800;
        c.materialCountMin = 8; c.materialCountMax = 15;
        c.materialMinRarity = 2; c.materialMaxRarity = 2;
    } else if (template_ == N::kDeliverPills) {
        c.spiritStones = 1000;
        c.pillCountMin = 1; c.pillCountMax = 2;
        c.pillMinRarity = 2; c.pillMaxRarity = 2;
    } else if (template_ == N::kSuppressJindanBeasts) {
        c.spiritStones = 1200;
        c.materialCountMin = 15; c.materialCountMax = 25;
        c.materialMinRarity = 2; c.materialMaxRarity = 3;
    } else if (template_ == N::kDestroyMagicOutpost) {
        c.spiritStones = 1500;
        c.materialCountMin = 12; c.materialCountMax = 20;
        c.materialMinRarity = 2; c.materialMaxRarity = 3;
        c.equipmentChance = 0.3;
        c.equipmentMinRarity = 2; c.equipmentMaxRarity = 3;
    } else if (template_ == N::kExploreAncientCave) {
        c.baseSpiritStones = 600; c.spiritStones = 1800;
        c.baseMaterialCountMin = 5; c.baseMaterialCountMax = 10;
        c.baseMaterialMinRarity = 2; c.baseMaterialMaxRarity = 2;
        c.materialCountMin = 18; c.materialCountMax = 28;
        c.materialMinRarity = 2; c.materialMaxRarity = 3;
        c.manualChance = 0.3;
        c.manualMinRarity = 2; c.manualMaxRarity = 3;
    }
    return c;
}

/// 高阶任务奖励配置（createTier3RewardConfig）
inline MissionRewardConfig createTier3RewardConfig(const std::string& template_) {
    using N = gamecore::state::MissionTemplateName;
    MissionRewardConfig c;
    if (template_ == N::kEscortImmortalEnvoy) {
        c.spiritStones = 40000;
    } else if (template_ == N::kRepairAncientFormation) {
        c.spiritStones = 20000;
        c.materialCountMin = 10; c.materialCountMax = 20;
        c.materialMinRarity = 4; c.materialMaxRarity = 5;
    } else if (template_ == N::kSearchMissingElder) {
        c.spiritStones = 25000;
        c.pillCountMin = 1; c.pillCountMax = 2;
        c.pillMinRarity = 4; c.pillMaxRarity = 4;
    } else if (template_ == N::kSuppressHuashenBeastKing) {
        c.spiritStones = 30000;
        c.materialCountMin = 20; c.materialCountMax = 35;
        c.materialMinRarity = 4; c.materialMaxRarity = 5;
        c.equipmentChance = 0.3;
        c.equipmentMinRarity = 4; c.equipmentMaxRarity = 5;
    } else if (template_ == N::kDestroyMagicBranch) {
        c.spiritStones = 40000;
        c.materialCountMin = 18; c.materialCountMax = 30;
        c.materialMinRarity = 4; c.materialMaxRarity = 5;
        c.equipmentChance = 0.3;
        c.equipmentMinRarity = 4; c.equipmentMaxRarity = 5;
    } else if (template_ == N::kExploreAncientBattlefield) {
        c.baseSpiritStones = 12500; c.spiritStones = 50000;
        c.baseMaterialCountMin = 8; c.baseMaterialCountMax = 15;
        c.baseMaterialMinRarity = 4; c.baseMaterialMaxRarity = 4;
        c.materialCountMin = 25; c.materialCountMax = 40;
        c.materialMinRarity = 4; c.materialMaxRarity = 5;
        c.manualChance = 0.3;
        c.manualMinRarity = 4; c.manualMaxRarity = 5;
    }
    return c;
}

/// 顶阶任务奖励配置（createTier4RewardConfig）
inline MissionRewardConfig createTier4RewardConfig(const std::string& template_) {
    using N = gamecore::state::MissionTemplateName;
    MissionRewardConfig c;
    if (template_ == N::kEscortRelicArtifact) {
        c.spiritStones = 200000;
    } else if (template_ == N::kSealSpatialRift) {
        c.spiritStones = 100000;
        c.materialCountMin = 15; c.materialCountMax = 25;
        c.materialMinRarity = 5; c.materialMaxRarity = 6;
    } else if (template_ == N::kSearchSecretRealmClue) {
        c.spiritStones = 150000;
        c.pillCountMin = 1; c.pillCountMax = 2;
        c.pillMinRarity = 5; c.pillMaxRarity = 5;
    } else if (template_ == N::kSuppressAncientFiend) {
        c.spiritStones = 150000;
        c.materialCountMin = 25; c.materialCountMax = 45;
        c.materialMinRarity = 5; c.materialMaxRarity = 6;
        c.equipmentChance = 0.3;
        c.equipmentMinRarity = 5; c.equipmentMaxRarity = 6;
    } else if (template_ == N::kDestroyMagicHeadquarters) {
        c.spiritStones = 200000;
        c.materialCountMin = 22; c.materialCountMax = 38;
        c.materialMinRarity = 5; c.materialMaxRarity = 6;
        c.equipmentChance = 0.3;
        c.equipmentMinRarity = 5; c.equipmentMaxRarity = 6;
    } else if (template_ == N::kExploreCoreBattlefield) {
        c.baseSpiritStones = 60000; c.spiritStones = 250000;
        c.baseMaterialCountMin = 10; c.baseMaterialCountMax = 18;
        c.baseMaterialMinRarity = 5; c.baseMaterialMaxRarity = 5;
        c.materialCountMin = 30; c.materialCountMax = 50;
        c.materialMinRarity = 5; c.materialMaxRarity = 6;
        c.manualChance = 0.3;
        c.manualMinRarity = 5; c.manualMaxRarity = 6;
    }
    return c;
}

/// 奖励配置创建（createRewardConfig：四级回退）
inline MissionRewardConfig createRewardConfig(const std::string& template_) {
    MissionRewardConfig c = createTier1RewardConfig(template_);
    if (c.spiritStones > 0 || c.materialCountMin > 0 || c.pillCountMin > 0 ||
        c.baseSpiritStones > 0) {
        return c;
    }
    c = createTier2RewardConfig(template_);
    if (c.spiritStones > 0 || c.materialCountMin > 0 || c.pillCountMin > 0 ||
        c.baseSpiritStones > 0) {
        return c;
    }
    c = createTier3RewardConfig(template_);
    if (c.spiritStones > 0 || c.materialCountMin > 0 || c.pillCountMin > 0 ||
        c.baseSpiritStones > 0) {
        return c;
    }
    c = createTier4RewardConfig(template_);
    return c;
}

// ── 任务创建（MissionSystem.createMission） ─────────────────────────

/// 创建任务（Kotlin createMission；id 为镜像生成字段——确定性自增）
inline Mission createMission(const std::string& template_, int32_t year,
                             int32_t month, const std::string& missionId) {
    const std::string difficulty = templateDifficulty(template_);
    Mission m;
    m.id = missionId;
    m.template_ = template_;
    m.name = difficultyDisplayName(difficulty) + templateDisplayName(template_);
    m.description = templateDescription(template_);
    m.difficulty = difficulty;
    m.duration = templateDuration(template_);
    m.rewards = createRewardConfig(template_);
    m.missionType = templateMissionType(template_);
    m.enemyType = templateEnemyType(template_);
    m.triggerChance = templateTriggerChance(template_);
    m.createdYear = year;
    m.createdMonth = month;
    return m;
}

// ── 模板加权池（MissionSystem.buildWeightedPool / weightedRandom） ──

/// 24 个模板（MissionTemplate.entries 声明序 = Kotlin 枚举序）
inline const std::vector<std::string>& missionTemplateEntries() {
    using N = gamecore::state::MissionTemplateName;
    static const std::vector<std::string> kEntries = {
        N::kEscortCaravan, N::kPatrolTerritory, N::kDeliverSupplies,
        N::kSuppressLowBeasts, N::kClearBandits, N::kExploreAbandonedMine,
        N::kEscortSpiritCaravan, N::kInvestigateAnomaly, N::kDeliverPills,
        N::kSuppressJindanBeasts, N::kDestroyMagicOutpost, N::kExploreAncientCave,
        N::kEscortImmortalEnvoy, N::kRepairAncientFormation, N::kSearchMissingElder,
        N::kSuppressHuashenBeastKing, N::kDestroyMagicBranch, N::kExploreAncientBattlefield,
        N::kEscortRelicArtifact, N::kSealSpatialRift, N::kSearchSecretRealmClue,
        N::kSuppressAncientFiend, N::kDestroyMagicHeadquarters, N::kExploreCoreBattlefield,
    };
    return kEntries;
}

/// 加权池（累积权重；Kotlin buildWeightedPool）
inline std::vector<std::pair<std::string, double>> buildWeightedPool() {
    std::vector<std::pair<std::string, double>> pool;
    double cumulative = 0.0;
    for (const auto& t : missionTemplateEntries()) {
        cumulative += difficultySpawnChance(templateDifficulty(t));
        pool.emplace_back(t, cumulative);
    }
    return pool;
}

/// 加权抽取（Kotlin weightedRandom：roll = nextDouble() * totalWeight，
/// 首个累积权重 > roll 的模板；totalWeight<=0 时均匀 nextInt 兜底）
inline std::string weightedRandom(const std::vector<std::pair<std::string, double>>& pool,
                                  gamecore::rng::DeterministicRng& rng) {
    const double totalWeight = pool.empty() ? 0.0 : pool.back().second;
    if (totalWeight <= 0.0) {
        const auto& entries = missionTemplateEntries();
        return entries[static_cast<std::size_t>(rng.nextInt(
            static_cast<int32_t>(entries.size())))];
    }
    const double roll = rng.nextDouble() * totalWeight;
    for (const auto& [t, cumulative] : pool) {
        if (roll < cumulative) return t;
    }
    return pool.back().first;   // Kotlin first{} 全不命中兜底（float 精度）
}

// ── 月度刷新（MissionSystem.processMonthlyRefresh 等价） ────────────

/// 空任务列表（刷新月 afterClean = emptyList() 的 Kotlin 语义锚点；
/// 静态实例保证引用稳定）
inline const std::vector<Mission>& emptyMissions() {
    static const std::vector<Mission> kEmpty;
    return kEmpty;
}

/// 执行月度任务刷新（Kotlin processMonthlyRefresh）。
/// @param existingMissions 现有可用任务
/// @param currentYear / currentMonth 当前年月
/// @param rng MISSION 分区 PRNG（刷新月消费 nextInt + 每任务 2 次 nextDouble）
/// @return 新任务列表 + 清理后列表（Kotlin MonthlyRefreshResult 语义）
struct MissionRefreshResult {
    std::vector<Mission> newMissions;
    std::vector<Mission> cleanedMissions;
};

inline MissionRefreshResult processMonthlyRefresh(
    const std::vector<Mission>& existingMissions, int32_t currentYear,
    int32_t currentMonth, gamecore::rng::DeterministicRng& rng) {
    MissionRefreshResult out;

    if (currentMonth % kRefreshIntervalMonths == 0) {
        const int32_t refreshCount = rng.nextInt(kMaxRefreshCount + 1);
        const auto pool = buildWeightedPool();
        for (int32_t i = 0; i < refreshCount; ++i) {
            const std::string template_ = weightedRandom(pool, rng);
            out.newMissions.push_back(createMission(
                template_, currentYear, currentMonth,
                "gc-mission-" + std::to_string(out.newMissions.size() + 1)));
        }
    }

    const std::vector<Mission>& afterClean =
        (currentMonth % kRefreshIntervalMonths == 0)
            ? emptyMissions()   // 刷新月：旧列表清空（Kotlin emptyList()）
            : existingMissions;

    out.cleanedMissions = afterClean;
    out.cleanedMissions.insert(out.cleanedMissions.end(),
                               out.newMissions.begin(), out.newMissions.end());
    return out;
}

// ── 主入口（processMissionRefreshIfDue → processMissionRefresh） ────

/// 任务刷新子事件（S8 子事件 14；month % 3 == 0 才刷新）
inline void processMissionRefresh(gamecore::state::GameState& state,
                                  gamecore::rng::RngManager& rng) {
    const int32_t month = state.gameData.gameMonth;
    if (month % kRefreshIntervalMonths != 0) return;
    auto& missionRng = rng.getRng(gamecore::rng::RngPartition::kMission);
    const auto result = processMonthlyRefresh(
        state.gameData.availableMissions, state.gameData.gameYear, month,
        missionRng);
    state.gameData.availableMissions = result.cleanedMissions;
}

}  // namespace gamecore::system::mission_settle
