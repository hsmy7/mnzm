#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/data/equip_set_db.h"
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

// ── 装备加成汇合点（B3：EquipStatResolver 单点结算——Kotlin 逐位移植，
//    DiffEquipmentStatTest/DiffEquipmentSetBonusTest 对拍基准） ─────────

/// 装备加成汇总（Kotlin EquipBonus 同构；全部 double 累加，出口一次截断）
struct EquipBonus {
    double flatAttack = 0.0;
    double flatDefense = 0.0;
    double flatHp = 0.0;
    double pctAttack = 0.0;
    double critRate = 0.0;
    double critDamage = 0.0;
    /// 物理伤害加成（不受灵根 gate）
    double physicalDamageBonus = 0.0;
    /// 金/木/水/火/土伤害加成（gate 前原始值；弟子侧汇总时按灵根折算）
    double metalDamageBonus = 0.0;
    double woodDamageBonus = 0.0;
    double waterDamageBonus = 0.0;
    double fireDamageBonus = 0.0;
    double earthDamageBonus = 0.0;
};

inline EquipBonus operator+(const EquipBonus& a, const EquipBonus& b) {
    EquipBonus r;
    r.flatAttack = a.flatAttack + b.flatAttack;
    r.flatDefense = a.flatDefense + b.flatDefense;
    r.flatHp = a.flatHp + b.flatHp;
    r.pctAttack = a.pctAttack + b.pctAttack;
    r.critRate = a.critRate + b.critRate;
    r.critDamage = a.critDamage + b.critDamage;
    r.physicalDamageBonus = a.physicalDamageBonus + b.physicalDamageBonus;
    r.metalDamageBonus = a.metalDamageBonus + b.metalDamageBonus;
    r.woodDamageBonus = a.woodDamageBonus + b.woodDamageBonus;
    r.waterDamageBonus = a.waterDamageBonus + b.waterDamageBonus;
    r.fireDamageBonus = a.fireDamageBonus + b.fireDamageBonus;
    r.earthDamageBonus = a.earthDamageBonus + b.earthDamageBonus;
    return r;
}

/// 单条词条并入（求和口径，与 Kotlin plusStat 逐位一致）
inline void plusEquipStat(EquipBonus& current, const state::EquipStatValue& sv) {
    if (sv.stat == "ATTACK") current.flatAttack += sv.value;
    else if (sv.stat == "DEFENSE") current.flatDefense += sv.value;
    else if (sv.stat == "HP") current.flatHp += sv.value;
    else if (sv.stat == "CRIT_RATE") current.critRate += sv.value;
    else if (sv.stat == "CRIT_DAMAGE") current.critDamage += sv.value;
    else if (sv.stat == "ATTACK_PCT") current.pctAttack += sv.value;
    else if (sv.stat == "PHYSICAL_DAMAGE_PCT") current.physicalDamageBonus += sv.value;
    // 退役段（MAGIC_DAMAGE_PCT）：禁新产出；旧档残留词条不再并入任何通道
    else if (sv.stat == "MAGIC_DAMAGE_PCT") { /* 退役段 */ }
    else if (sv.stat == "METAL_DAMAGE_PCT") current.metalDamageBonus += sv.value;
    else if (sv.stat == "WOOD_DAMAGE_PCT") current.woodDamageBonus += sv.value;
    else if (sv.stat == "WATER_DAMAGE_PCT") current.waterDamageBonus += sv.value;
    else if (sv.stat == "FIRE_DAMAGE_PCT") current.fireDamageBonus += sv.value;
    else if (sv.stat == "EARTH_DAMAGE_PCT") current.earthDamageBonus += sv.value;
}

/// 单实例 totalBonus（Kotlin EquipmentInstance.totalBonus = growth.affix.totalBonus(level)：
/// 主词条 × 等级成长 + 副词条 × 强化次数，加法序 主→副 一致）
inline void appendInstanceTotalBonus(EquipBonus& bonus, const EquipmentInstance& inst) {
    const state::EquipAffixSet& affix = inst.growth.affix;
    // 主词条：mainStatFinal = value × (1 + 0.10 × (level-1))
    const double mult = 1.0 + 0.10 * (static_cast<double>(inst.growth.level) - 1.0);
    plusEquipStat(bonus,
        state::EquipStatValue{affix.mainStat.stat, affix.mainStat.value * mult});
    // 副词条：subStats[i].value × subRolls[i]（缺省 1）
    for (std::size_t i = 0; i < affix.subStats.size(); ++i) {
        const int32_t rolls = i < affix.subRolls.size() ? affix.subRolls[i] : 1;
        plusEquipStat(bonus,
            state::EquipStatValue{affix.subStats[i].stat,
                                  affix.subStats[i].value * rolls});
    }
}

/// 套装档位（按 setId 统计件数，2/4/6 达档即生效、可越级不叠加——
/// 穿满 6 件三档同时生效；同类百分比相加 0.2-7。Kotlin resolveSetBonus 逐位移植，
/// 迭代序 = C++ 侧按 equipmentSetDefs 声明序收集 setId（Kotlin groupingBy 保序
/// 为首次出现序——两者对同一六件集合同序））
inline EquipBonus resolveSetBonus(const std::vector<const EquipmentInstance*>& equipped) {
    EquipBonus bonus;
    // 按 setId 首次出现序统计件数（与 Kotlin groupingBy.eachCount 序一致）
    std::vector<std::pair<std::string, int32_t>> countBySet;
    for (const EquipmentInstance* inst : equipped) {
        if (inst == nullptr || inst->setId.empty()) continue;
        bool found = false;
        for (auto& kv : countBySet) {
            if (kv.first == inst->setId) { ++kv.second; found = true; break; }
        }
        if (!found) countBySet.push_back({inst->setId, 1});
    }
    for (const auto& kv : countBySet) {
        const gamecore::data::EquipmentSetDef* def = nullptr;
        for (const auto& s : gamecore::data::equipmentSetDefs()) {
            if (s.id == kv.first) { def = &s; break; }
        }
        if (def == nullptr) continue;
        // activeBonuses(count)：count>=2 加 bonus2、>=4 加 bonus4、>=6 加 bonus6（声明序）
        const std::vector<gamecore::data::EquipStatValueDef>* const tiers[] = {
            &def->bonus2, &def->bonus4, &def->bonus6};
        const bool active[] = {kv.second >= 2, kv.second >= 4, kv.second >= 6};
        for (int t = 0; t < 3; ++t) {
            if (!active[t]) continue;
            for (const auto& sv : *tiers[t]) {
                plusEquipStat(bonus, state::EquipStatValue{sv.stat, sv.value});
            }
        }
    }
    return bonus;
}

/// 解析六件已装备实例 + 套装档位 → EquipBonus（Kotlin EquipStatResolver.resolve 逐位移植；
/// equipped = 六槽位 id 顺序的实例指针，空槽传 nullptr）
inline EquipBonus resolveEquipBonus(
        const std::vector<const EquipmentInstance*>& equipped) {
    EquipBonus bonus;
    for (const EquipmentInstance* inst : equipped) {
        if (inst == nullptr) continue;
        appendInstanceTotalBonus(bonus, *inst);
    }
    bonus = bonus + resolveSetBonus(equipped);
    return bonus;
}

/// Kotlin Double.toInt() 语义（向零截断；与 static_cast 一致，显式命名表意）
inline int32_t kotlinToInt(double v) { return static_cast<int32_t>(v); }

/// 累加器版装备加成应用（Kotlin applyEquipBonusToAccum 逐位移植：
/// flat 四项一次截断直加 → 攻击乘区在装备块内一次乘 → critRate 单列累加）
inline void applyEquipBonusToStats(::gamecore::disciple::DiscipleStats& total,
                                   double& critRateAcc, const EquipBonus& bonus) {
    total.attack += kotlinToInt(bonus.flatAttack);
    total.defense += kotlinToInt(bonus.flatDefense);
    total.maxHp += kotlinToInt(bonus.flatHp);
    total.hp += kotlinToInt(bonus.flatHp);
    const int32_t pctAttack =
        kotlinToInt(static_cast<double>(total.attack) * bonus.pctAttack);
    total.attack += pctAttack;
    critRateAcc += bonus.critRate;
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

/// 装备段求和（桶查找版：owner 行索引桶逐槽 find——R1.3 第二步；
/// B3 六部位（头/身/手/脚/武/腿 = displayOrder）：EquipStatResolver 解析
/// 全部已装备实例（含套装档位）后 flatHp **一次截断**——Kotlin
/// getMaxHpMpColumn「hp += equipBonus.flatHp.toInt()」逐位一致，
/// maxMp 不吃装备加成（Kotlin 列版无 mp 装备项，参数面保留对称））
inline void accumulateEquipmentHpMp(
        const instance_bucket::EquipmentInstanceBuckets& equipmentBuckets,
        std::size_t ownerRow, const std::string& headId,
        const std::string& bodyId, const std::string& handsId,
        const std::string& feetId, const std::string& weaponId,
        const std::string& legsId, int32_t& outMaxHp, int32_t& outMaxMp) {
    std::vector<const state::EquipmentInstance*> equipped;
    equipped.reserve(6);
    for (const std::string* eqId :
         {&headId, &bodyId, &handsId, &feetId, &weaponId, &legsId}) {
        if (eqId->empty()) continue;
        equipped.push_back(equipmentBuckets.find(ownerRow, *eqId));
    }
    if (equipped.empty()) return;
    const EquipBonus bonus = resolveEquipBonus(equipped);
    outMaxHp += kotlinToInt(bonus.flatHp);
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
    accumulateEquipmentHpMp(equipmentBuckets, ownerRow, d.headId, d.bodyId,
                            d.handsId, d.feetId, d.weaponId, d.legsId,
                            outMaxHp, outMaxMp);
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
    accumulateEquipmentHpMp(equipmentBuckets, row, ds.headIds[row],
                            ds.bodyIds[row], ds.handsIds[row], ds.feetIds[row],
                            ds.weaponIds[row], ds.legsIds[row],
                            outMaxHp, outMaxMp);
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
    in.attackVariance = d.attackVariance;
    in.defenseVariance = d.defenseVariance;
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
/// @param outEquipBonus 非空时回传装备/套装加成（critDamage/类型通道供
///        Combatant 装配消费——D3 接线 + 物法分桶，面板列不展示）
inline ::gamecore::disciple::DiscipleStats finalStats(
        const Disciple& d,
        const std::map<std::string, EquipmentInstance>& equipmentMap,
        const std::map<std::string, ManualInstance>& manualMap,
        const std::map<std::string, ManualProficiencyData>& discipleProficiencies,
        EquipBonus* outEquipBonus = nullptr) {
    ::gamecore::disciple::DiscipleStats total = baseStats(d);
    double totalCritRate = total.critRate;

    // 装备（B3：六槽位 id 顺序 头/身/手/脚/武/腿 = Kotlin equippedItemIds；
    // EquipStatResolver 单点结算 + applyEquipBonusToAccum 乘区口径）
    {
        std::vector<const EquipmentInstance*> equipped;
        equipped.reserve(6);
        for (const std::string* eqId :
             {&d.headId, &d.bodyId, &d.handsId, &d.feetId, &d.weaponId, &d.legsId}) {
            if (eqId->empty()) continue;
            const auto it = equipmentMap.find(*eqId);
            if (it == equipmentMap.end()) continue;
            equipped.push_back(&it->second);
        }
        const EquipBonus equipBonus = resolveEquipBonus(equipped);
        applyEquipBonusToStats(total, totalCritRate, equipBonus);
        if (outEquipBonus != nullptr) *outEquipBonus = equipBonus;
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
        // 功法保留物法双列数据（Q2），结算层各自 round 后相加（与 Kotlin 同式）
        total.attack += static_cast<int32_t>(
            static_cast<double>(statOf("physicalAttack", "")) * masteryBonus) +
            static_cast<int32_t>(
            static_cast<double>(statOf("magicAttack", "")) * masteryBonus);
        total.defense += static_cast<int32_t>(
            static_cast<double>(statOf("physicalDefense", "")) * masteryBonus) +
            static_cast<int32_t>(
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
        total.attack += d.pillAttackBonus;
        total.defense += d.pillDefenseBonus;
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
