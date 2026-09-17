#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/data/trait_db.h"
#include "gamecore/state/models.h"
#include "gamecore/system/disciple.h"

// ============================================================
// 弟子列直读属性计算（每旬结算）
//
// 等价移植 Kotlin DiscipleStatCalculator 的每旬结算路径**纯公式**部分：
//   - computeBaseHpMp / getMaxHpMpColumn（HP/MP 恢复上限）
//   - calculateCultivationPerPhaseColumn（修炼速率 5 乘区）
//   - getBreakthroughChance（突破概率乘区，含长老悟性/魂力/丹药/师徒）
//   - calculateBreakthroughLifespanGain（大境界寿命增益）
//   - EquipmentInstance.getFinalStats（孕养乘区后的装备面板）
//
// 与 Kotlin 语义对齐要点：
//   - roundToInt = std::round 后转 int32（Kotlin roundToInt 四舍五入；
//     负数场景 Kotlin roundToInt(-0.5)=0，std::round(-0.5)=-1 → 差异由
//     上游保证非负输入（所有属性乘区 ≥0），见 safeLayerMult/safeVarianceMultiplier）
//   - toInt() 截断 = static_cast<int32_t>（向零截断）
//   - 天赋/词条/体质效果聚合（talentEffectsFor/affixEffectsFor/
//     physiqueCultivationBonusFor）从 data/trait_db.h（204 条，
//     双端守卫已证与 Kotlin Registry 一致）逐字段读取，语义权威 =
//     Kotlin TalentDatabase.calculateTalentEffects /
//     AffixDatabase.calculateAffixEffects / PhysiqueDatabase.
//     aggregatePhysiqueEffects（DiscipleStatCalculator 委托的同一实现）
//   - 无 std::unordered_map 参与业务迭代（确定性铁律）
// ============================================================
namespace gamecore::stats {

using gamecore::state::BloodRefinementPctTotal;
using gamecore::state::Disciple;
using gamecore::state::EquipmentInstance;
using gamecore::state::ManualInstance;
using gamecore::state::ManualProficiencyData;

// ── 常量（Kotlin GameConfig / DiscipleStatCalculator） ────────────────

constexpr double kPhaseHpMpRecoveryRate = 0.2;       // Cultivation.PHASE_HP_MP_RECOVERY_RATE
constexpr double kMaxBloodRefinementPct = 10.0;      // MAX_BLOOD_REFINEMENT_PCT
constexpr int32_t kElderSkillBaseline = 80;          // PolicyConfig.ELDER_SKILL_BASELINE
constexpr int32_t kElderBonusDivisor = 4;            // PolicyConfig.ELDER_BONUS_DIVISOR
constexpr int32_t kElderBreakthroughMaxSteps = 10;   // PolicyConfig.ELDER_BREAKTHROUGH_MAX_STEPS
constexpr double kElderBonusPerStep = 0.01;          // ELDER_BONUS_PER_STEP
constexpr int32_t kSkillMax = 200;                   // Disciple.SKILL_MAX
constexpr int32_t kMaxLoyalty = 100;                 // Disciple.MAX_LOYALTY
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

inline double safeBrPct(double pct) {
    // Kotlin: pct.coerceIn(0, MAX).takeIf { isFinite } ?: 0 —— NaN/Inf 归零，
    // coerceIn(NaN) 结果为 NaN（coerceAtMost(10.0).coerceAtLeast(0.0) 对 NaN
    // 返回上界比较实现相关）；Kotlin coerceIn 用 minOf/maxOf：maxOf(NaN,0)=NaN、
    // minOf(NaN,10)=NaN → takeIf{isFinite} 过滤为 null → 0。
    if (!std::isfinite(pct)) return 0.0;
    if (pct < 0.0) return 0.0;
    return (pct > kMaxBloodRefinementPct) ? kMaxBloodRefinementPct : pct;
}

// ── 天赋/词条/体质效果聚合（data/trait_db.h → 效果集） ────

/// 累加单条模板 effects 到聚合表（同 key 相加；与 Kotlin
/// `effects[key] = (effects[key] ?: 0.0) + value` 逐位一致）
inline void accumulateEffects(std::map<std::string, double>& effects,
                              const std::map<std::string, double>& tplEffects) {
    for (const auto& [key, value] : tplEffects) {
        effects[key] += value;
    }
}

/// 天赋效果聚合（TalentDatabase.calculateTalentEffects 等价实现）：
/// 按 id 列表逐条查 talent_db 表累加 effects；未知 id 跳过（与 Kotlin
/// `talents[id] ?: return@forEach` 一致），重复 id 各计一次。
inline std::map<std::string, double> talentEffectsFor(
        const std::vector<std::string>& talentIds) {
    std::map<std::string, double> effects;
    for (const std::string& id : talentIds) {
        const auto tpl = gamecore::data::talentById(id);
        if (!tpl.has_value()) continue;
        accumulateEffects(effects, tpl->effects);
    }
    return effects;
}

/// 词条效果聚合（AffixDatabase.calculateAffixEffects 等价实现）：
/// 按 id 列表逐条查 affix_db 表累加 effects；未知 id 跳过。
inline std::map<std::string, double> affixEffectsFor(
        const std::vector<std::string>& affixIds) {
    std::map<std::string, double> effects;
    for (const std::string& id : affixIds) {
        const auto tpl = gamecore::data::affixById(id);
        if (!tpl.has_value()) continue;
        accumulateEffects(effects, tpl->effects);
    }
    return effects;
}

/// 体质效果聚合全量（PhysiqueDatabase.aggregatePhysiqueEffects 等价签名：
/// 五个独立乘算因子分量各自求和）
struct PhysiqueEffectsAggregate {
    double cultivationSpeedBonus = 0.0;
    double damageAmplification = 0.0;
    double damageReduction = 0.0;
    double critDamageBonus = 0.0;
    double defenseBonus = 0.0;
};

/// 体质效果聚合（按 id 列表查 physique_db 表五分量求和；未知 id 跳过）
inline PhysiqueEffectsAggregate physiqueEffectsFor(
        const std::vector<std::string>& physiqueIds) {
    PhysiqueEffectsAggregate out;
    for (const std::string& id : physiqueIds) {
        const auto tpl = gamecore::data::physiqueById(id);
        if (!tpl.has_value()) continue;
        out.cultivationSpeedBonus += tpl->cultivationSpeedBonus;
        out.damageAmplification += tpl->damageAmplification;
        out.damageReduction += tpl->damageReduction;
        out.critDamageBonus += tpl->critDamageBonus;
        out.defenseBonus += tpl->defenseBonus;
    }
    return out;
}

/// 体质修炼速度加成聚合（aggregatePhysiqueEffects 的 cultivationSpeedBonus
/// 分量等价签名，修炼速率资质乘区专用）
inline double physiqueCultivationBonusFor(
        const std::vector<std::string>& physiqueIds) {
    return physiqueEffectsFor(physiqueIds).cultivationSpeedBonus;
}

/// 合并天赋+词条效果 map（mergeEffects：同 key 相加）
inline std::map<std::string, double> mergeEffects(
        const std::map<std::string, double>& talents,
        const std::map<std::string, double>& affixes) {
    std::map<std::string, double> merged = talents;
    for (const auto& [k, v] : affixes) merged[k] += v;
    return merged;
}

inline double effectValue(const std::map<std::string, double>& effects,
                          const char* key) {
    const auto it = effects.find(key);
    return (it != effects.end()) ? it->second : 0.0;
}

/// 天赋+词条 maxHp/maxMp 两键效果和直算（getMaxHpMp 热路径专用——免
/// talentEffectsFor/affixEffectsFor/mergeEffects 三次中间 map 物化）。
/// 与 map 版逐位一致：单键加法序 = 天赋 id 序 → 词条 id 序，每模板单键
/// 至多累加一次（与 accumulateEffects 同序），初值同为 0.0。
inline void hpMpEffectsFor(const std::vector<std::string>& talentIds,
                           const std::vector<std::string>& affixIds,
                           double& outMaxHpEffect, double& outMaxMpEffect) {
    outMaxHpEffect = 0.0;
    outMaxMpEffect = 0.0;
    for (const std::string& id : talentIds) {
        const auto tpl = gamecore::data::talentById(id);
        if (!tpl.has_value()) continue;
        const auto hp = tpl->effects.find("maxHp");
        if (hp != tpl->effects.end()) outMaxHpEffect += hp->second;
        const auto mp = tpl->effects.find("maxMp");
        if (mp != tpl->effects.end()) outMaxMpEffect += mp->second;
    }
    for (const std::string& id : affixIds) {
        const auto tpl = gamecore::data::affixById(id);
        if (!tpl.has_value()) continue;
        const auto hp = tpl->effects.find("maxHp");
        if (hp != tpl->effects.end()) outMaxHpEffect += hp->second;
        const auto mp = tpl->effects.find("maxMp");
        if (mp != tpl->effects.end()) outMaxMpEffect += mp->second;
    }
}

// ── 基础 HP/MP（computeBaseHpMp） ───────────────────────────────────

/// maxHp/maxMp 基础值核心（效果和已解析为两键标量——getMaxHpMp 热路径
/// 免 effects map 物化；map 版 computeBaseHpMp 委托本函数）：
/// 基础 × 方差乘区 × 层数乘区 × (1 + 天赋% + 血炼%)
inline void computeBaseHpMpResolved(
        int32_t realm, int32_t realmLayer, int32_t hpVariance,
        int32_t mpVariance, double maxHpEffect, double maxMpEffect,
        const BloodRefinementPctTotal* bloodRefinementPct,
        int32_t& outMaxHp, int32_t& outMaxMp) {
    const auto& rc = gamecore::disciple::realmConfig(realm);
    const double layerMult =
        gamecore::disciple::safeLayerMult(realmLayer);
    const double hpBonus = maxHpEffect +
        safeBrPct(bloodRefinementPct ? bloodRefinementPct->hpBonusPct : 0.0);
    const double mpBonus = maxMpEffect;
    const double hpVar = gamecore::disciple::safeVarianceMultiplier(hpVariance);
    const double mpVar = gamecore::disciple::safeVarianceMultiplier(mpVariance);
    outMaxHp = roundToInt(rc.baseHp * hpVar * layerMult * (1.0 + hpBonus));
    outMaxMp = roundToInt(rc.baseMp * mpVar * layerMult * (1.0 + mpBonus));
}

/// maxHp/maxMp 基础值：基础 × 方差乘区 × 层数乘区 × (1 + 天赋% + 血炼%)
inline void computeBaseHpMp(int32_t realm, int32_t realmLayer,
                            int32_t hpVariance, int32_t mpVariance,
                            const std::map<std::string, double>& talentEffects,
                            const BloodRefinementPctTotal* bloodRefinementPct,
                            int32_t& outMaxHp, int32_t& outMaxMp) {
    computeBaseHpMpResolved(realm, realmLayer, hpVariance, mpVariance,
                            effectValue(talentEffects, "maxHp"),
                            effectValue(talentEffects, "maxMp"),
                            bloodRefinementPct, outMaxHp, outMaxMp);
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

/// 列直读 maxHp/maxMp（DiscipleStatCalculator.getMaxHpMpColumn 数学等价）。
/// 输入直接取自 C++ Disciple 平铺字段（对应 Kotlin DiscipleTables 列直读）；
/// 血炼累计（bloodRefinementPctTotals[id]）由调用方查表传入。
inline void getMaxHpMp(
        const Disciple& d,
        const BloodRefinementPctTotal* bloodRefinementPct,
        const std::map<std::string, EquipmentInstance>& equipmentMap,
        const std::map<std::string, ManualInstance>& manualMap,
        const std::map<std::string, std::vector<ManualProficiencyData>>& proficiencies,
        int32_t& outMaxHp, int32_t& outMaxMp) {
    double maxHpEffect = 0.0, maxMpEffect = 0.0;
    hpMpEffectsFor(d.talentIds, d.affixIds, maxHpEffect, maxMpEffect);
    computeBaseHpMpResolved(d.realm, d.realmLayer, d.hpVariance, d.mpVariance,
                            maxHpEffect, maxMpEffect, bloodRefinementPct,
                            outMaxHp, outMaxMp);
    for (const std::string& eqId : {d.weaponId, d.armorId, d.bootsId, d.accessoryId}) {
        if (eqId.empty()) continue;
        const auto it = equipmentMap.find(eqId);
        if (it == equipmentMap.end()) continue;
        const auto fs = equipmentFinalStats(it->second);
        outMaxHp += fs.hp;
        outMaxMp += fs.mp;
    }
    const auto profListIt = proficiencies.find(d.id);
    for (const std::string& manualId : d.manualIds) {
        const auto it = manualMap.find(manualId);
        if (it == manualMap.end()) continue;
        const ManualInstance& manual = it->second;
        int32_t masteryLevel = 0;
        if (profListIt != proficiencies.end()) {
            for (const auto& p : profListIt->second) {
                if (p.manualId == manualId) { masteryLevel = p.masteryLevel; break; }
            }
        }
        const double bonus = masteryBonusFromProficiencyLevel(masteryLevel);
        outMaxHp += manualStatWithMastery(manual.stats, "hp", "maxHp", bonus);
        outMaxMp += manualStatWithMastery(manual.stats, "mp", "maxMp", bonus);
    }
    if (d.pillEffectDuration > 0) {
        outMaxHp += d.pillHpBonus;
        outMaxMp += d.pillMpBonus;
    }
}

/// 列直读 maxHp/maxMp（DiscipleStore SoA 版，热路径用——
/// 每旬恢复/候选筛选取代逐弟子物化；语义与 Disciple& 版逐位一致）
inline void getMaxHpMp(
        const state::DiscipleStore& ds, std::size_t row,
        const BloodRefinementPctTotal* bloodRefinementPct,
        const std::map<std::string, EquipmentInstance>& equipmentMap,
        const std::map<std::string, ManualInstance>& manualMap,
        const std::map<std::string, std::vector<ManualProficiencyData>>& proficiencies,
        int32_t& outMaxHp, int32_t& outMaxMp) {
    double maxHpEffect = 0.0, maxMpEffect = 0.0;
    hpMpEffectsFor(ds.talentIds[row], ds.affixIds[row], maxHpEffect,
                   maxMpEffect);
    computeBaseHpMpResolved(ds.realms[row], ds.realmLayers[row],
                            ds.hpVariances[row], ds.mpVariances[row],
                            maxHpEffect, maxMpEffect, bloodRefinementPct,
                            outMaxHp, outMaxMp);
    for (const std::string& eqId :
         {ds.weaponIds[row], ds.armorIds[row], ds.bootsIds[row], ds.accessoryIds[row]}) {
        if (eqId.empty()) continue;
        const auto it = equipmentMap.find(eqId);
        if (it == equipmentMap.end()) continue;
        const auto fs = equipmentFinalStats(it->second);
        outMaxHp += fs.hp;
        outMaxMp += fs.mp;
    }
    const auto profListIt = proficiencies.find(ds.ids[row]);
    for (const std::string& manualId : ds.manualIds[row]) {
        const auto it = manualMap.find(manualId);
        if (it == manualMap.end()) continue;
        const ManualInstance& manual = it->second;
        int32_t masteryLevel = 0;
        if (profListIt != proficiencies.end()) {
            for (const auto& p : profListIt->second) {
                if (p.manualId == manualId) { masteryLevel = p.masteryLevel; break; }
            }
        }
        const double bonus = masteryBonusFromProficiencyLevel(masteryLevel);
        outMaxHp += manualStatWithMastery(manual.stats, "hp", "maxHp", bonus);
        outMaxMp += manualStatWithMastery(manual.stats, "mp", "maxMp", bonus);
    }
    if (ds.pillEffectDurations[row] > 0) {
        outMaxHp += ds.pillHpBonuses[row];
        outMaxMp += ds.pillMpBonuses[row];
    }
}

// ── 基础悟性（getBaseStats().comprehension，突破概率用） ─────────────

/// 基础悟性 = skills.comprehension + 合并（天赋+词条）comprehensionFlat 截断。
/// Kotlin 权威口径：getBaseStats().comprehension 经 getMergedEffects
/// （天赋+词条同 key 相加后取 "comprehensionFlat" toInt()）——词条表含
/// comprehensionFlat 键（r1-r3_aff_base_comp / neg_aff_base），必须并入；
/// 修复 t2-1-review:77 潜伏分叉（原 C++ 只聚合天赋 flat）。
inline int32_t baseComprehension(const Disciple& d) {
    const auto effects = mergeEffects(
        talentEffectsFor(d.talentIds), affixEffectsFor(d.affixIds));
    return d.comprehension +
           static_cast<int32_t>(effectValue(effects, "comprehensionFlat"));
}

/// 基础悟性（DiscipleStore 行版，突破概率长老读取用）
inline int32_t baseComprehension(const state::DiscipleStore& ds,
                                 std::size_t row) {
    const auto effects = mergeEffects(
        talentEffectsFor(ds.talentIds[row]), affixEffectsFor(ds.affixIds[row]));
    return ds.comprehensions[row] +
           static_cast<int32_t>(effectValue(effects, "comprehensionFlat"));
}

// ── 基础智力（getBaseStats().intelligence，执法堂捕获率用）──

/// 基础智力 = skills.intelligence + 合并（天赋+词条）intelligenceFlat 截断。
/// Kotlin 权威口径与 baseComprehension 同构（getMergedEffects 同 key 相加后
/// 取 "intelligenceFlat" toInt()）。
inline int32_t baseIntelligence(const Disciple& d) {
    const auto effects = mergeEffects(
        talentEffectsFor(d.talentIds), affixEffectsFor(d.affixIds));
    return d.intelligence +
           static_cast<int32_t>(effectValue(effects, "intelligenceFlat"));
}

/// 基础智力（DiscipleStore 行版）
inline int32_t baseIntelligence(const state::DiscipleStore& ds,
                                std::size_t row) {
    const auto effects = mergeEffects(
        talentEffectsFor(ds.talentIds[row]), affixEffectsFor(ds.affixIds[row]));
    return ds.intelligences[row] +
           static_cast<int32_t>(effectValue(effects, "intelligenceFlat"));
}

/// 完整基础属性（Kotlin DiscipleStatCalculator.getBaseStats(disciple)——
/// 血炼百分比参数缺省 null，乘区全零）。偷盗域消费
/// morality/speed/intelligence/loyalty 四字段。
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
    in.loyalty = d.loyalty;
    in.comprehension = d.comprehension;
    in.aptitude = d.aptitude;
    in.teaching = d.teaching;
    in.morality = d.morality;
    in.mining = d.mining;
    in.spiritPlanting = d.spiritPlanting;
    in.artifactRefining = d.artifactRefining;
    in.pillRefining = d.pillRefining;
    in.talentEffects = mergeEffects(
        talentEffectsFor(d.talentIds), affixEffectsFor(d.affixIds));
    return ::gamecore::disciple::computeBaseStats(in);
}

// ── S5：战斗装配属性（Kotlin getFinalStats 域） ─────────────────────

/// 血炼百分比感知基础属性（Kotlin getBaseStats(disciple, bloodRefinementPct)
/// 等价——血炼乘区与天赋同乘区加算，safeBrPct 防护在 computeBaseStats 内）。
inline ::gamecore::disciple::DiscipleStats baseStatsWithBr(
        const Disciple& d, const BloodRefinementPctTotal* bloodRefinementPct) {
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
    in.loyalty = d.loyalty;
    in.comprehension = d.comprehension;
    in.aptitude = d.aptitude;
    in.teaching = d.teaching;
    in.morality = d.morality;
    in.mining = d.mining;
    in.spiritPlanting = d.spiritPlanting;
    in.artifactRefining = d.artifactRefining;
    in.pillRefining = d.pillRefining;
    in.talentEffects = mergeEffects(
        talentEffectsFor(d.talentIds), affixEffectsFor(d.affixIds));
    if (bloodRefinementPct != nullptr) {
        in.bloodHpBonusPct = bloodRefinementPct->hpBonusPct;
        in.bloodPhysicalAttackBonusPct = bloodRefinementPct->physicalAttackBonusPct;
        in.bloodMagicAttackBonusPct = bloodRefinementPct->magicAttackBonusPct;
        in.bloodPhysicalDefenseBonusPct = bloodRefinementPct->physicalDefenseBonusPct;
        in.bloodMagicDefenseBonusPct = bloodRefinementPct->magicDefenseBonusPct;
        in.bloodSpeedBonusPct = bloodRefinementPct->speedBonusPct;
    }
    return ::gamecore::disciple::computeBaseStats(in);
}

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
        const std::map<std::string, ManualProficiencyData>& discipleProficiencies,
        const BloodRefinementPctTotal* bloodRefinementPct) {
    ::gamecore::disciple::DiscipleStats total =
        baseStatsWithBr(d, bloodRefinementPct);
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

// ── 职务加成（getPositionEffectBonus，执法堂捕获率用）──────

/// 统计弟子（天赋+词条）中指定 slotType 的 PositionBonus 总和。
/// Kotlin 权威口径：天赋取非负面且 positionBonus.slotType 匹配者求和 +
/// 词条（AffixDatabase.aggregatePositionBonus）同口径求和。
inline double positionEffectBonus(const std::vector<std::string>& talentIds,
                                  const std::vector<std::string>& affixIds,
                                  const std::string& slotType) {
    double bonus = 0.0;
    for (const auto& id : talentIds) {
        if (auto t = data::talentById(id)) {
            if (!t->isNegative && t->positionBonus &&
                t->positionBonus->slotType == slotType) {
                bonus += t->positionBonus->effectBonus;
            }
        }
    }
    for (const auto& id : affixIds) {
        if (auto a = data::affixById(id)) {
            if (a->positionBonus && a->positionBonus->slotType == slotType) {
                bonus += a->positionBonus->effectBonus;
            }
        }
    }
    return bonus;
}

inline double positionEffectBonus(const state::DiscipleStore& ds,
                                  std::size_t row,
                                  const std::string& slotType) {
    return positionEffectBonus(ds.talentIds[row], ds.affixIds[row], slotType);
}

// ── 修炼速率乘区（calculateCultivationPerPhaseColumn） ───────────────

/// 每旬修炼速率输入（调用方预计算的社交/建筑/政策分量）
struct CultivationRateInput {
    double buildingBonus = 1.0;              // 住所建筑系数（1.0=无）
    double preachingElderBonus = 0.0;        // 讲道长老加成（外+内合计）
    double preachingMastersBonus = 0.0;      // 讲道师兄加成（外+内合计）
    double parentCultivationBonus = 0.0;     // 父母灵根加成
    double masterDiscipleBonus = 0.0;        // 师徒加成
};

/// 每旬修炼速率（5 乘区连乘，下限 1.0；与 Kotlin 列直读版数学等价）
inline double calculateCultivationPerPhaseColumn(
        const Disciple& d,
        const state::GameData& gd,
        const std::map<std::string, ManualInstance>& manualMap,
        const std::map<std::string, std::vector<ManualProficiencyData>>& proficiencies,
        const CultivationRateInput& extra) {
    // 灵根数量（spiritRootTypes 按 "," 切分；空串按 1 兜底，与列版 ?: 1 一致）
    int32_t rootCount = 1;
    if (!d.spiritRootType.empty()) rootCount = 1;
    for (char c : d.spiritRootType) {
        if (c == ',') ++rootCount;
    }

    // ── 资质乘区：天赋 + 体质 + 资质属性 ──
    const auto effects = mergeEffects(
        talentEffectsFor(d.talentIds), affixEffectsFor(d.affixIds));
    double aptitudeBonus = effectValue(effects, "cultivationSpeed") +
        physiqueCultivationBonusFor(d.physiqueIds) +
        gamecore::disciple::aptitudeCultivationBonus(d.aptitude);

    // ── 资源乘区：建筑 + 功法（熟练度加成） ──
    double resourceBonus = extra.buildingBonus - 1.0;
    const auto profListIt = proficiencies.find(d.id);
    for (const std::string& manualId : d.manualIds) {
        const auto it = manualMap.find(manualId);
        if (it == manualMap.end()) continue;
        const ManualInstance& manual = it->second;
        int32_t masteryLevel = 0;
        if (profListIt != proficiencies.end()) {
            for (const auto& p : profListIt->second) {
                if (p.manualId == manualId) { masteryLevel = p.masteryLevel; break; }
            }
        }
        const double bonus = masteryBonusFromProficiencyLevel(masteryLevel);
        double speedPct = 0.0;
        const auto s = manual.stats.find("cultivationSpeedPercent");
        if (s != manual.stats.end()) speedPct = static_cast<double>(s->second);
        resourceBonus += speedPct * bonus / 100.0;
    }

    // ── 社交乘区：讲道 + 师徒 + 父母 ──
    const double socialBonus = extra.preachingElderBonus +
        extra.preachingMastersBonus + extra.parentCultivationBonus +
        extra.masterDiscipleBonus;

    // ── 状态乘区：政策 - 丧亲 - 寿命 ──
    const bool hasGrief = d.griefEndYear >= 0;
    const double griefPenalty = hasGrief &&
        gd.gameYear < d.griefEndYear
        ? gamecore::disciple::kGriefCultivationPenalty : 0.0;
    const double lifespanPenalty =
        gamecore::disciple::calculateLifespanCultivationPenalty(d.age, d.lifespan);
    const double statusBonus =
        policyCultivationBonus(d.realm, gd.sectPolicies) -
        griefPenalty - lifespanPenalty;

    // ── 临时乘区：丹药持续加速（pillEffects 体系） ──
    double temporaryBonus = 0.0;
    if (d.pillEffectDuration > 0 && d.pillCultivationSpeedBonus > 0.0) {
        temporaryBonus += d.pillCultivationSpeedBonus;
    }

    const int32_t clampedRoots = std::max(rootCount, 1);
    const double base =
        gamecore::disciple::realmSpeedPerPhase(d.realm) /
        static_cast<double>(clampedRoots);
    return gamecore::disciple::coerceAtLeast(
        base * (1.0 + aptitudeBonus) * (1.0 + resourceBonus) *
               (1.0 + socialBonus) * (1.0 + statusBonus) *
               (1.0 + temporaryBonus),
        gamecore::disciple::kMinCultivationPerPhase);
}

/// 每旬修炼速率（DiscipleStore SoA 版，热路径用；
/// 语义与 Disciple& 版逐位一致——字段改列直读）
inline double calculateCultivationPerPhaseColumn(
        const state::DiscipleStore& ds, std::size_t row,
        const state::GameData& gd,
        const std::map<std::string, ManualInstance>& manualMap,
        const std::map<std::string, std::vector<ManualProficiencyData>>& proficiencies,
        const CultivationRateInput& extra) {
    // 灵根数量（spiritRootTypes 按 "," 切分；空串按 1 兜底）
    int32_t rootCount = 1;
    if (!ds.spiritRootTypes[row].empty()) rootCount = 1;
    for (char c : ds.spiritRootTypes[row]) {
        if (c == ',') ++rootCount;
    }

    // ── 资质乘区：天赋 + 体质 + 资质属性 ──
    const auto effects = mergeEffects(
        talentEffectsFor(ds.talentIds[row]), affixEffectsFor(ds.affixIds[row]));
    double aptitudeBonus = effectValue(effects, "cultivationSpeed") +
        physiqueCultivationBonusFor(ds.physiqueIds[row]) +
        gamecore::disciple::aptitudeCultivationBonus(ds.aptitudes[row]);

    // ── 资源乘区：建筑 + 功法（熟练度加成） ──
    double resourceBonus = extra.buildingBonus - 1.0;
    const auto profListIt = proficiencies.find(ds.ids[row]);
    for (const std::string& manualId : ds.manualIds[row]) {
        const auto it = manualMap.find(manualId);
        if (it == manualMap.end()) continue;
        const ManualInstance& manual = it->second;
        int32_t masteryLevel = 0;
        if (profListIt != proficiencies.end()) {
            for (const auto& p : profListIt->second) {
                if (p.manualId == manualId) { masteryLevel = p.masteryLevel; break; }
            }
        }
        const double bonus = masteryBonusFromProficiencyLevel(masteryLevel);
        double speedPct = 0.0;
        const auto s = manual.stats.find("cultivationSpeedPercent");
        if (s != manual.stats.end()) speedPct = static_cast<double>(s->second);
        resourceBonus += speedPct * bonus / 100.0;
    }

    // ── 社交乘区：讲道 + 师徒 + 父母 ──
    const double socialBonus = extra.preachingElderBonus +
        extra.preachingMastersBonus + extra.parentCultivationBonus +
        extra.masterDiscipleBonus;

    // ── 状态乘区：政策 - 丧亲 - 寿命 ──
    const int32_t griefEndYear = ds.griefEndYears[row];
    const double griefPenalty = griefEndYear >= 0 &&
        gd.gameYear < griefEndYear
        ? gamecore::disciple::kGriefCultivationPenalty : 0.0;
    const double lifespanPenalty =
        gamecore::disciple::calculateLifespanCultivationPenalty(
            ds.ages[row], ds.lifespans[row]);

    const double statusBonus =
        policyCultivationBonus(ds.realms[row], gd.sectPolicies) -
        griefPenalty - lifespanPenalty;

    // ── 临时乘区：丹药持续加速（pillEffects 体系） ──
    double temporaryBonus = 0.0;
    if (ds.pillEffectDurations[row] > 0 && ds.pillCultivationSpeedBonuses[row] > 0.0) {
        temporaryBonus += ds.pillCultivationSpeedBonuses[row];
    }

    const int32_t clampedRoots = std::max(rootCount, 1);
    const double base =
        gamecore::disciple::realmSpeedPerPhase(ds.realms[row]) /
        static_cast<double>(clampedRoots);
    return gamecore::disciple::coerceAtLeast(
        base * (1.0 + aptitudeBonus) * (1.0 + resourceBonus) *
               (1.0 + socialBonus) * (1.0 + statusBonus) *
               (1.0 + temporaryBonus),
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
    double griefBreakthroughPenalty = 0.0;   // 丧亲惩罚
    double masterDiscipleBonus = 0.0;        // 师徒加成
    double innerElderPositionBonus = 0.0;    // 内门长老职务乘算因子
    double outerElderPositionBonus = 0.0;    // 外门长老职务乘算因子
};

/// 最终突破概率（baseZone × (1+elder+self) × (1-penalty) + adFlat，clamp [0,1]）
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
    const double soulPowerBonus =
        gamecore::disciple::soulPowerBreakthroughBonus(d.soulPower);
    const double lifespanPenalty =
        gamecore::disciple::calculateLifespanBreakthroughPenalty(d.age, d.lifespan);
    const double elderGuidance = innerBonus + outerBonus;
    const double selfBonus = in.pillBonus + soulPowerBonus +
        in.masterDiscipleBonus +
        comprehensionBreakthroughBonus(baseComprehension(d));
    const double positiveMult = 1.0 + elderGuidance + selfBonus;
    const double penaltyMult =
        std::max(1.0 - (in.griefBreakthroughPenalty + lifespanPenalty), 0.0);
    const double base = baseZone * positiveMult * penaltyMult;
    const double result = base + in.adBonus;
    return gamecore::disciple::coerceIn(result, 0.0, 1.0);
}

/// 大境界突破寿命增益（境界基准 + (天赋+词条) 寿命百分比 × 基准 截断）
inline int32_t calculateBreakthroughLifespanGain(
        int32_t newRealm,
        const std::vector<std::string>& talentIds,
        const std::vector<std::string>& affixIds) {
    const int32_t baseGain = gamecore::disciple::lifespanGainForRealm(newRealm);
    const double lifespanTalentBonus =
        effectValue(talentEffectsFor(talentIds), "lifespan") +
        effectValue(affixEffectsFor(affixIds), "lifespan");
    if (lifespanTalentBonus != 0.0) {
        return baseGain + static_cast<int32_t>(baseGain * lifespanTalentBonus);
    }
    return baseGain;
}

}  // namespace gamecore::stats
