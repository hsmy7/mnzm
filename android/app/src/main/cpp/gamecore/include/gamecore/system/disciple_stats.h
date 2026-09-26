#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/instance_buckets.h"
#include "gamecore/system/star_zone.h"

// ============================================================
// 弟子列直读属性计算（每旬结算）
//
// 等价移植 Kotlin DiscipleStatCalculator 的每旬结算路径**纯公式**部分：
//   - computeBaseHpMp / getMaxHpMpColumn（HP/MP 恢复上限）
//   - calculateCultivationPerPhaseColumn（修炼速率 5 乘区：资源/社交/状态/临时/星级）
//   - getBreakthroughChance（突破概率乘区，含长老悟性/丹药）
//   - EquipmentInstance.getFinalStats（孕养乘区后的装备面板）
//
// 与 Kotlin 语义对齐要点：
//   - roundToInt = std::round 后转 int32（Kotlin roundToInt 四舍五入；
//     负数场景 Kotlin roundToInt(-0.5)=0，std::round(-0.5)=-1 → 差异由
//     上游保证非负输入（所有属性乘区 ≥0），见 safeLayerMult/safeVarianceMultiplier）
//   - toInt() 截断 = static_cast<int32_t>（向零截断）
//   - 无 std::unordered_map 参与业务迭代（确定性铁律）
// ============================================================
namespace gamecore::stats {

using gamecore::state::Disciple;
using gamecore::state::EquipmentInstance;
using gamecore::state::ManualInstance;
using gamecore::state::ManualProficiencyData;

// R1.3 第二步：装备/功法实例查找 = owner 行索引桶视图
namespace instance_bucket = gamecore::system::instance_bucket;

// ── 常量（Kotlin GameConfig / DiscipleStatCalculator） ────────────────

constexpr double kPhaseHpMpRecoveryRate = 0.2;       // Cultivation.PHASE_HP_MP_RECOVERY_RATE
constexpr int32_t kElderSkillBaseline = 80;          // PolicyConfig.ELDER_SKILL_BASELINE
constexpr int32_t kElderBonusDivisor = 4;            // PolicyConfig.ELDER_BONUS_DIVISOR
constexpr int32_t kElderBreakthroughMaxSteps = 10;   // PolicyConfig.ELDER_BREAKTHROUGH_MAX_STEPS
constexpr double kElderBonusPerStep = 0.01;          // ELDER_BONUS_PER_STEP
constexpr int32_t kSkillMax = 200;                   // Disciple.SKILL_MAX
constexpr int64_t kMaxEventLogs = 200;               // Logs.MAX_EVENT_LOGS

/// 住所建筑修炼加成系数（Cultivation.BUILDING_BONUSES，按 displayName 查表）
inline double buildingCultivationBonus(const std::string& displayName) {
    static const std::map<std::string, double> kBonuses = {
        {"中级单人住所", 1.40},
        {"初级单人住所", 1.20},
        {"初级多人住所", 1.10},
    };
    const auto it = kBonuses.find(displayName);
    return (it != kBonuses.end()) ? it->second : 1.0;
}

/// 政策修炼加成汇总（修行津贴 realm>5 +15% / 苦修令 +25% / 松弛管理 -10%）
inline double policyCultivationBonus(int32_t realm,
                                     const state::SectPolicies& policies) {
    double total = 0.0;
    if (policies.cultivationSubsidy && realm > 5) total += 0.15;
    if (policies.asceticTraining) total += 0.25;
    if (policies.relaxedMgmt) total -= 0.10;
    return total;
}

// ── clamp/round 辅助 ────────────────────────────────────────────────

inline int32_t roundToInt(double v) {
    // 属性乘区结果恒非负（safeLayerMult/safeVarianceMultiplier 钳制），
    // 与 Kotlin roundToInt 在非负域逐位一致
    return static_cast<int32_t>(std::round(v));
}

// ── 基础 HP/MP（computeBaseHpMp） ───────────────────────────────────

/// maxHp/maxMp 基础值核心（map 版 computeBaseHpMp 委托本函数）：
/// 基础 × 方差乘区 × 层数乘区
inline void computeBaseHpMpResolved(
        int32_t realm, int32_t realmLayer, int32_t hpVariance,
        int32_t mpVariance, int32_t& outMaxHp, int32_t& outMaxMp) {
    const auto& rc = gamecore::disciple::realmConfig(realm);
    const double layerMult =
        gamecore::disciple::safeLayerMult(realmLayer);
    const double hpVar = gamecore::disciple::safeVarianceMultiplier(hpVariance);
    const double mpVar = gamecore::disciple::safeVarianceMultiplier(mpVariance);
    outMaxHp = roundToInt(rc.baseHp * hpVar * layerMult);
    outMaxMp = roundToInt(rc.baseMp * mpVar * layerMult);
}

/// maxHp/maxMp 基础值：基础 × 方差乘区 × 层数乘区
inline void computeBaseHpMp(int32_t realm, int32_t realmLayer,
                            int32_t hpVariance, int32_t mpVariance,
                            int32_t& outMaxHp, int32_t& outMaxMp) {
    computeBaseHpMpResolved(realm, realmLayer, hpVariance, mpVariance,
                            outMaxHp, outMaxMp);
}

inline double effectValue(const std::map<std::string, double>& effects,
                          const char* key) {
    const auto it = effects.find(key);
    return (it != effects.end()) ? it->second : 0.0;
}

// ── 装备最终属性（EquipmentInstance.getFinalStats） ─────────────────

struct EquipmentStats {
    int32_t physicalAttack = 0;
    int32_t magicAttack = 0;
    int32_t physicalDefense = 0;
    int32_t magicDefense = 0;
    int32_t speed = 0;
    int32_t hp = 0;
    int32_t mp = 0;
};

/// 孕养乘区：level≤0 → 1.0；否则 (1 + L(L+1)/2 × 3/325).coerceAtMost(4.0)
inline double nurtureMultiplier(int32_t nurtureLevel) {
    if (nurtureLevel <= 0) return 1.0;
    constexpr int32_t kMaxNurtureLevel = 25;
    const double level = static_cast<double>(
        std::min(nurtureLevel, kMaxNurtureLevel));
    const double totalBonus = level * (level + 1.0) / 2.0 * (3.0 / 325.0);
    const double mult = 1.0 + totalBonus;
    return (mult > 4.0) ? 4.0 : mult;
}

/// 装备最终属性（getFinalStats：各字段 × 孕养乘区后 toInt 截断）
inline EquipmentStats equipmentFinalStats(const EquipmentInstance& eq) {
    const double m = nurtureMultiplier(eq.nurtureLevel);
    EquipmentStats s;
    s.physicalAttack = static_cast<int32_t>(eq.physicalAttack * m);
    s.magicAttack = static_cast<int32_t>(eq.magicAttack * m);
    s.physicalDefense = static_cast<int32_t>(eq.physicalDefense * m);
    s.magicDefense = static_cast<int32_t>(eq.magicDefense * m);
    s.speed = static_cast<int32_t>(eq.speed * m);
    s.hp = static_cast<int32_t>(eq.hp * m);
    s.mp = static_cast<int32_t>(eq.mp * m);
    return s;
}

// ── 功法熟练度加成 ──────────────────────────────────────────────────

/// 熟练度等级加成（ManualProficiencySystem.MasteryLevel.bonus：
/// 入门 1.5 / 小成 2.0 / 大成 3.0 / 圆满 4.0）
inline double masteryBonusFromProficiencyLevel(int32_t masteryLevel) {
    switch (masteryLevel) {
        case 1: return 2.0;   // SMALL_SUCCESS
        case 2: return 3.0;   // GREAT_SUCCESS
        case 3: return 4.0;   // PERFECTION
        default: return 1.5;  // NOVICE
    }
}

/// 按熟练度值求等级（MasteryLevel.fromProficiency）
inline int32_t masteryLevelFromProficiency(double proficiency) {
    if (proficiency >= 30000.0) return 3;
    if (proficiency >= 10000.0) return 2;
    if (proficiency >= 1000.0) return 1;
    return 0;
}

// ── 列直读 maxHp/maxMp（getMaxHpMpColumn） ──────────────────────────

/// 单功法 hp/mp 面板值 × 熟练度加成后截断（getMaxHpMpColumn 功法段）
inline int32_t manualStatWithMastery(const std::map<std::string, int32_t>& stats,
                                     const char* primaryKey,
                                     const char* fallbackKey,
                                     double bonus) {
    int32_t value = 0;
    const auto p = stats.find(primaryKey);
    if (p != stats.end()) {
        value = p->second;
    } else {
        const auto f = stats.find(fallbackKey);
        if (f != stats.end()) value = f->second;
    }
    return static_cast<int32_t>(value * bonus);
}

/// 装备段求和（桶查找版：owner 行索引桶逐槽 find——R1.3 第二步，
/// 步骤入口映射不再物化 id 键全量深拷贝 map；加法序 = weapon → armor →
/// boots → accessory 与 map 版逐位一致）
inline void accumulateEquipmentHpMp(
        const instance_bucket::EquipmentInstanceBuckets& equipmentBuckets,
        std::size_t ownerRow, const std::string& weaponId,
        const std::string& armorId, const std::string& bootsId,
        const std::string& accessoryId, int32_t& outMaxHp, int32_t& outMaxMp) {
    for (const std::string* eqId :
         {&weaponId, &armorId, &bootsId, &accessoryId}) {
        if (eqId->empty()) continue;
        const state::EquipmentInstance* eq =
            equipmentBuckets.find(ownerRow, *eqId);
        if (eq == nullptr) continue;
        const auto fs = equipmentFinalStats(*eq);
        outMaxHp += fs.hp;
        outMaxMp += fs.mp;
    }
}

/// 功法段求和（桶查找版；加法序 = manualIds 序与 map 版逐位一致）
inline void accumulateManualHpMp(
        const instance_bucket::ManualInstanceBuckets& manualBuckets,
        std::size_t ownerRow,
        const std::vector<std::string>& manualIds,
        const std::map<std::string, std::vector<ManualProficiencyData>>& proficiencies,
        const std::string& discipleId,
        int32_t& outMaxHp, int32_t& outMaxMp) {
    const auto profListIt = proficiencies.find(discipleId);
    for (const std::string& manualId : manualIds) {
        const state::ManualInstance* manual =
            manualBuckets.find(ownerRow, manualId);
        if (manual == nullptr) continue;
        int32_t masteryLevel = 0;
        if (profListIt != proficiencies.end()) {
            for (const auto& p : profListIt->second) {
                if (p.manualId == manualId) { masteryLevel = p.masteryLevel; break; }
            }
        }
        const double bonus = masteryBonusFromProficiencyLevel(masteryLevel);
        outMaxHp += manualStatWithMastery(manual->stats, "hp", "maxHp", bonus);
        outMaxMp += manualStatWithMastery(manual->stats, "mp", "maxMp", bonus);
    }
}

/// 列直读 maxHp/maxMp（DiscipleStatCalculator.getMaxHpMpColumn 数学等价）。
/// 输入直接取自 C++ Disciple 平铺字段（对应 Kotlin DiscipleTables 列直读）；
/// 装备/功法段经 owner 行索引桶查找（R1.3 第二步——ownerRow = 弟子在
/// DiscipleStore 的行号，工作副本场景由调用方传入原行号，其四槽/已学
/// 功法 id 与桶键一致）。
inline void getMaxHpMp(
        const Disciple& d, std::size_t ownerRow,
        const instance_bucket::EquipmentInstanceBuckets& equipmentBuckets,
        const instance_bucket::ManualInstanceBuckets& manualBuckets,
        const std::map<std::string, std::vector<ManualProficiencyData>>& proficiencies,
        int32_t& outMaxHp, int32_t& outMaxMp) {
    computeBaseHpMpResolved(d.realm, d.realmLayer, d.hpVariance, d.mpVariance,
                            outMaxHp, outMaxMp);
    accumulateEquipmentHpMp(equipmentBuckets, ownerRow, d.weaponId, d.armorId,
                            d.bootsId, d.accessoryId, outMaxHp, outMaxMp);
    accumulateManualHpMp(manualBuckets, ownerRow, d.manualIds, proficiencies,
                         d.id, outMaxHp, outMaxMp);
    if (d.pillEffectDuration > 0) {
        outMaxHp += d.pillHpBonus;
        outMaxMp += d.pillMpBonus;
    }
}

/// 列直读 maxHp/maxMp（DiscipleStore SoA 版，热路径用——
/// 每旬恢复/候选筛选取代逐弟子物化；语义与 Disciple& 版逐位一致）
inline void getMaxHpMp(
        const state::DiscipleStore& ds, std::size_t row,
        const instance_bucket::EquipmentInstanceBuckets& equipmentBuckets,
        const instance_bucket::ManualInstanceBuckets& manualBuckets,
        const std::map<std::string, std::vector<ManualProficiencyData>>& proficiencies,
        int32_t& outMaxHp, int32_t& outMaxMp) {
    computeBaseHpMpResolved(ds.realms[row], ds.realmLayers[row],
                            ds.hpVariances[row], ds.mpVariances[row],
                            outMaxHp, outMaxMp);
    accumulateEquipmentHpMp(equipmentBuckets, row, ds.weaponIds[row],
                            ds.armorIds[row], ds.bootsIds[row],
                            ds.accessoryIds[row], outMaxHp, outMaxMp);
    accumulateManualHpMp(manualBuckets, row, ds.manualIds[row], proficiencies,
                         ds.ids[row], outMaxHp, outMaxMp);
    if (ds.pillEffectDurations[row] > 0) {
        outMaxHp += ds.pillHpBonuses[row];
        outMaxMp += ds.pillMpBonuses[row];
    }
}

// ── 基础悟性（getBaseStats().comprehension，突破概率用） ─────────────

/// 基础悟性 = comprehension 本体。
inline int32_t baseComprehension(const Disciple& d) {
    return d.comprehension;
}

/// 基础悟性（DiscipleStore 行版，突破概率长老读取用）
inline int32_t baseComprehension(const state::DiscipleStore& ds,
                                 std::size_t row) {
    return ds.comprehensions[row];
}

// ── 基础智力（getBaseStats().intelligence，执法堂捕获率用）──

/// 基础智力 = intelligence 本体。
inline int32_t baseIntelligence(const Disciple& d) {
    return d.intelligence;
}

/// 基础智力（DiscipleStore 行版）
inline int32_t baseIntelligence(const state::DiscipleStore& ds,
                                std::size_t row) {
    return ds.intelligences[row];
}

/// 完整基础属性（Kotlin DiscipleStatCalculator.getBaseStats(disciple)）。
inline ::gamecore::disciple::DiscipleStats baseStats(const Disciple& d) {
    ::gamecore::disciple::BaseStatsInput in;
    in.realm = d.realm;
    in.realmLayer = d.realmLayer;
    in.hpVariance = d.hpVariance;
    in.mpVariance = d.mpVariance;
    in.physicalAttackVariance = d.physicalAttackVariance;
    in.magicAttackVariance = d.magicAttackVariance;
    in.physicalDefenseVariance = d.physicalDefenseVariance;
    in.magicDefenseVariance = d.magicDefenseVariance;
    in.speedVariance = d.speedVariance;
    in.intelligence = d.intelligence;
    in.charm = d.charm;
    in.comprehension = d.comprehension;
    in.teaching = d.teaching;
    in.morality = d.morality;
    in.mining = d.mining;
    in.spiritPlanting = d.spiritPlanting;
    in.artifactRefining = d.artifactRefining;
    in.pillRefining = d.pillRefining;
    return ::gamecore::disciple::computeBaseStats(in);
}

// ── S5：战斗装配属性（Kotlin getFinalStats 域） ─────────────────────

/// 熟练度等级加成（Kotlin ManualProficiencySystem.MasteryLevel.fromLevel(level).bonus；
/// 未知等级回退 NOVICE 1.5——与 fromLevel 的 find{it.level==level} ?: NOVICE 一致）
inline double masteryLevelBonus(int32_t masteryLevel) {
    switch (masteryLevel) {
        case 0: return 1.5;   // NOVICE 入门
        case 1: return 2.0;   // SMALL_SUCCESS 小成
        case 2: return 3.0;   // GREAT_SUCCESS 大成
        case 3: return 4.0;   // PERFECTION 圆满
        default: return 1.5;
    }
}

/// 战斗装配最终属性（Kotlin computeFinalStats 等价——基础 + 装备 + 功法
/// （熟练度乘区）+ 丹药加成；critRate 分累加，各项与 Kotlin 逐位一致）。
inline ::gamecore::disciple::DiscipleStats finalStats(
        const Disciple& d,
        const std::map<std::string, EquipmentInstance>& equipmentMap,
        const std::map<std::string, ManualInstance>& manualMap,
        const std::map<std::string, ManualProficiencyData>& discipleProficiencies) {
    ::gamecore::disciple::DiscipleStats total = baseStats(d);
    double totalCritRate = total.critRate;

    // 装备（equipId 顺序 = Kotlin listOfNotNull(weapon,armor,boots,accessory)）
    for (const std::string& eqId :
         {d.weaponId, d.armorId, d.bootsId, d.accessoryId}) {
        if (eqId.empty()) continue;
        const auto it = equipmentMap.find(eqId);
        if (it == equipmentMap.end()) continue;
        const EquipmentStats fs = equipmentFinalStats(it->second);
        total.maxHp += fs.hp;
        total.hp += fs.hp;
        total.maxMp += fs.mp;
        total.mp += fs.mp;
        total.physicalAttack += fs.physicalAttack;
        total.magicAttack += fs.magicAttack;
        total.physicalDefense += fs.physicalDefense;
        total.magicDefense += fs.magicDefense;
        total.speed += fs.speed;
        totalCritRate += it->second.critChance;
    }

    // 功法（Kotlin manualIds.forEach；stats["hp"] ?: stats["maxHp"] 兜底口径）
    for (const std::string& manualId : d.manualIds) {
        const auto it = manualMap.find(manualId);
        if (it == manualMap.end()) continue;
        const ManualInstance& manual = it->second;
        int32_t masteryLevel = 0;
        const auto profIt = discipleProficiencies.find(manualId);
        if (profIt != discipleProficiencies.end()) {
            masteryLevel = profIt->second.masteryLevel;
        }
        const double masteryBonus = masteryLevelBonus(masteryLevel);
        const auto statOf = [&](const char* primary, const char* fallback) -> int32_t {
            auto s = manual.stats.find(primary);
            if (s != manual.stats.end()) return s->second;
            s = manual.stats.find(fallback);
            if (s != manual.stats.end()) return s->second;
            return 0;
        };
        const int32_t hpValue = statOf("hp", "maxHp");
        const int32_t mpValue = statOf("mp", "maxMp");
        total.maxHp += static_cast<int32_t>(hpValue * masteryBonus);
        total.hp += static_cast<int32_t>(hpValue * masteryBonus);
        total.maxMp += static_cast<int32_t>(mpValue * masteryBonus);
        total.mp += static_cast<int32_t>(mpValue * masteryBonus);
        total.physicalAttack += static_cast<int32_t>(
            static_cast<double>(statOf("physicalAttack", "")) * masteryBonus);
        total.magicAttack += static_cast<int32_t>(
            static_cast<double>(statOf("magicAttack", "")) * masteryBonus);
        total.physicalDefense += static_cast<int32_t>(
            static_cast<double>(statOf("physicalDefense", "")) * masteryBonus);
        total.magicDefense += static_cast<int32_t>(
            static_cast<double>(statOf("magicDefense", "")) * masteryBonus);
        total.speed += static_cast<int32_t>(
            static_cast<double>(statOf("speed", "")) * masteryBonus);
        totalCritRate += (static_cast<double>(statOf("critRate", "")) * masteryBonus) / 100.0;
    }

    // 丹药（Kotlin hasPillEffect 分支——pillCritRateBonus 双计：面板加成 + critRate 累加）
    if (d.pillEffectDuration > 0) {
        total.maxHp += d.pillHpBonus;
        total.hp += d.pillHpBonus;
        total.maxMp += d.pillMpBonus;
        total.mp += d.pillMpBonus;
        total.physicalAttack += d.pillPhysicalAttackBonus;
        total.magicAttack += d.pillMagicAttackBonus;
        total.physicalDefense += d.pillPhysicalDefenseBonus;
        total.magicDefense += d.pillMagicDefenseBonus;
        total.speed += d.pillSpeedBonus;
        totalCritRate += d.pillCritRateBonus;
    }

    total.critRate = totalCritRate;
    return total;
}

// ── 修炼速率乘区（calculateCultivationPerPhaseColumn） ───────────────

/// 每旬修炼速率输入（调用方预计算的社交/建筑/政策分量）
struct CultivationRateInput {
    double buildingBonus = 1.0;              // 住所建筑系数（1.0=无）
    double preachingElderBonus = 0.0;        // 讲道长老加成（外+内合计）
    double preachingMastersBonus = 0.0;      // 讲道师兄加成（外+内合计）
};

/// 功法段速率加成（桶查找版共享段——R1.3 第二步；加法序 = manualIds 序、
/// 每条 speedPct × bonus / 100 与 map 版逐位一致）
inline double accumulateManualCultivationSpeed(
        const instance_bucket::ManualInstanceBuckets& manualBuckets,
        std::size_t ownerRow,
        const std::vector<std::string>& manualIds,
        const std::map<std::string, std::vector<ManualProficiencyData>>& proficiencies,
        const std::string& discipleId) {
    double resourceBonus = 0.0;
    const auto profListIt = proficiencies.find(discipleId);
    for (const std::string& manualId : manualIds) {
        const state::ManualInstance* manual =
            manualBuckets.find(ownerRow, manualId);
        if (manual == nullptr) continue;
        int32_t masteryLevel = 0;
        if (profListIt != proficiencies.end()) {
            for (const auto& p : profListIt->second) {
                if (p.manualId == manualId) { masteryLevel = p.masteryLevel; break; }
            }
        }
        const double bonus = masteryBonusFromProficiencyLevel(masteryLevel);
        double speedPct = 0.0;
        const auto s = manual->stats.find("cultivationSpeedPercent");
        if (s != manual->stats.end()) speedPct = static_cast<double>(s->second);
        resourceBonus += speedPct * bonus / 100.0;
    }
    return resourceBonus;
}

/// 每旬修炼速率（5 乘区连乘，下限 1.0；与 Kotlin 列直读版数学等价）。
/// 乘区序固定为 资源→社交→状态→临时→星级（浮点乘法不可交换，双端必须同序）。
/// ownerRow = 弟子在 DiscipleStore 的行号（工作副本场景——突破后境界
/// 已推进而商店行未写回——桶寻址用，数值面全部取自工作副本 d）
inline double calculateCultivationPerPhaseColumn(
        const Disciple& d, std::size_t ownerRow,
        const state::GameData& gd,
        const instance_bucket::ManualInstanceBuckets& manualBuckets,
        const std::map<std::string, std::vector<ManualProficiencyData>>& proficiencies,
        const CultivationRateInput& extra) {
    // 灵根数量（spiritRootTypes 按 "," 切分；空串按 1 兜底，与列版 ?: 1 一致）
    int32_t rootCount = 1;
    if (!d.spiritRootType.empty()) rootCount = 1;
    for (char c : d.spiritRootType) {
        if (c == ',') ++rootCount;
    }

    // ── 资源乘区：建筑 + 功法（熟练度加成） ──
    double resourceBonus = extra.buildingBonus - 1.0;
    resourceBonus += accumulateManualCultivationSpeed(
        manualBuckets, ownerRow, d.manualIds, proficiencies, d.id);

    // ── 社交乘区：讲道长老 + 讲道师兄 ──
    const double socialBonus =
        extra.preachingElderBonus + extra.preachingMastersBonus;

    // ── 状态乘区：政策 ──
    const double statusBonus =
        policyCultivationBonus(d.realm, gd.sectPolicies);

    // ── 临时乘区：丹药持续加速（pillEffects 体系） ──
    double temporaryBonus = 0.0;
    if (d.pillEffectDuration > 0 && d.pillCultivationSpeedBonus > 0.0) {
        temporaryBonus += d.pillCultivationSpeedBonus;
    }

    // ── 星级乘区：抽卡解锁角色的星级加成（口径 A，1★ 基线 ⇒ 存量旧弟子恒 0） ──
    const double starBonus =
        gamecore::system::cultivationStarBonus(gd, d.templateId);

    const int32_t clampedRoots = std::max(rootCount, 1);
    const double base =
        gamecore::disciple::realmSpeedPerPhase(d.realm) /
        static_cast<double>(clampedRoots);
    return gamecore::disciple::coerceAtLeast(
        base * (1.0 + resourceBonus) *
               (1.0 + socialBonus) * (1.0 + statusBonus) *
               (1.0 + temporaryBonus) * (1.0 + starBonus),
        gamecore::disciple::kMinCultivationPerPhase);
}

/// 每旬修炼速率（DiscipleStore SoA 版，热路径用；
/// 语义与 Disciple& 版逐位一致——字段改列直读）
inline double calculateCultivationPerPhaseColumn(
        const state::DiscipleStore& ds, std::size_t row,
        const state::GameData& gd,
        const instance_bucket::ManualInstanceBuckets& manualBuckets,
        const std::map<std::string, std::vector<ManualProficiencyData>>& proficiencies,
        const CultivationRateInput& extra) {
    // 灵根数量（spiritRootTypes 按 "," 切分；空串按 1 兜底）
    int32_t rootCount = 1;
    if (!ds.spiritRootTypes[row].empty()) rootCount = 1;
    for (char c : ds.spiritRootTypes[row]) {
        if (c == ',') ++rootCount;
    }

    // ── 资源乘区：建筑 + 功法（熟练度加成） ──
    double resourceBonus = extra.buildingBonus - 1.0;
    resourceBonus += accumulateManualCultivationSpeed(
        manualBuckets, row, ds.manualIds[row], proficiencies, ds.ids[row]);

    // ── 社交乘区：讲道长老 + 讲道师兄 ──
    const double socialBonus =
        extra.preachingElderBonus + extra.preachingMastersBonus;

    // ── 状态乘区：政策 ──
    const double statusBonus =
        policyCultivationBonus(ds.realms[row], gd.sectPolicies);

    // ── 临时乘区：丹药持续加速（pillEffects 体系） ──
    double temporaryBonus = 0.0;
    if (ds.pillEffectDurations[row] > 0 && ds.pillCultivationSpeedBonuses[row] > 0.0) {
        temporaryBonus += ds.pillCultivationSpeedBonuses[row];
    }

    // ── 星级乘区：抽卡解锁角色的星级加成（口径 A，1★ 基线 ⇒ 存量旧弟子恒 0） ──
    const double starBonus =
        gamecore::system::cultivationStarBonus(gd, ds.templateIds[row]);

    const int32_t clampedRoots = std::max(rootCount, 1);
    const double base =
        gamecore::disciple::realmSpeedPerPhase(ds.realms[row]) /
        static_cast<double>(clampedRoots);
    return gamecore::disciple::coerceAtLeast(
        base * (1.0 + resourceBonus) *
               (1.0 + socialBonus) * (1.0 + statusBonus) *
               (1.0 + temporaryBonus) * (1.0 + starBonus),
        gamecore::disciple::kMinCultivationPerPhase);
}

// ── 突破概率（getBreakthroughChance 完整版） ─────────────────────────

/// 悟性突破率加成（80 基准每 4 点 +1%，最多 +10%；负值归零）
inline double comprehensionBreakthroughBonus(int32_t comprehension) {
    if (comprehension < kElderSkillBaseline) return 0.0;
    const int32_t steps =
        (comprehension - kElderSkillBaseline) / kElderBonusDivisor;
    return static_cast<double>(std::min(steps, kElderBreakthroughMaxSteps)) *
           kElderBonusPerStep;
}

/// 突破概率输入（长老悟性等由调用方从状态提取）
struct BreakthroughChanceInput {
    int32_t innerElderComprehension = 0;
    int32_t outerElderComprehension = 0;
    double pillBonus = 0.0;                  // 突破丹加成
    double adBonus = 0.0;                    // 广告扁平加成
    double innerElderPositionBonus = 0.0;    // 内门长老职务乘算因子
    double outerElderPositionBonus = 0.0;    // 外门长老职务乘算因子
};

/// 最终突破概率（baseZone × (1+elder+self) + adFlat，clamp [0,1]）
inline double calculateBreakthroughChance(const Disciple& d,
                                          const BreakthroughChanceInput& in) {
    if (d.realm < 0) return 0.0;
    const int32_t rootCount = d.spiritRootType.empty()
        ? 1
        : static_cast<int32_t>(std::count(
              d.spiritRootType.begin(), d.spiritRootType.end(), ',') + 1);
    const double baseZone = gamecore::disciple::getBreakthroughChance(
        d.realm, rootCount, d.realmLayer);
    const double innerBonus = comprehensionBreakthroughBonus(in.innerElderComprehension) *
        (1.0 + in.innerElderPositionBonus);
    const double outerBonus = comprehensionBreakthroughBonus(in.outerElderComprehension) *
        (1.0 + in.outerElderPositionBonus);
    const double elderGuidance = innerBonus + outerBonus;
    const double selfBonus = in.pillBonus +
        comprehensionBreakthroughBonus(baseComprehension(d));
    const double positiveMult = 1.0 + elderGuidance + selfBonus;
    const double base = baseZone * positiveMult;
    const double result = base + in.adBonus;
    return gamecore::disciple::coerceIn(result, 0.0, 1.0);
}

}  // namespace gamecore::stats
