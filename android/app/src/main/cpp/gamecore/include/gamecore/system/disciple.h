#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

// ============================================================
// 弟子属性计算（Kotlin→C++ 迁移批次 5）
//
// 等价移植 Kotlin DiscipleStatCalculator 的**纯公式**部分：
//   - 基础属性乘区法（computeBaseStats：境界基值 × 方差乘区 × 层数乘区 × (1+天赋%+血炼%)）
//   - 修炼速度乘区（calculateCultivationPerPhase：5 乘区连乘 + 下限 1.0）
//   - 突破概率乘区（calculateBreakthroughChance：baseZone × (1+指导+自身) × (1-惩罚) + adFlat）
//   - 寿命将尽惩罚、魂力加成、师徒加成、父母灵根加成、丧亲判定、亲属关系
//
// 与 Kotlin 语义对齐要点：
//   - roundToInt = std::round 后转 int（Kotlin roundToInt 四舍五入）
//   - coerceIn/coerceAtLeast/coerceAtMost 显式实现
//   - 境界索引：0=仙人 … 9=炼气（数值越小境界越高）
//   - 无 std::unordered_map 参与业务迭代（确定性）
// ============================================================
namespace gamecore::disciple {

// ── 基础常量（与 Kotlin GameConfig/DiscipleStatCalculator 对齐）─────

constexpr double kLayerMultiplier = 0.1;         // LAYER_MULTIPLIER
constexpr double kBaseCritRate = 0.05;           // BASE_CRIT_RATE
constexpr double kMinCultivationPerPhase = 1.0;  // MIN_CULTIVATION_PER_PHASE
constexpr int32_t kBaseManualSlots = 6;          // BASE_MANUAL_SLOTS
constexpr int32_t kSoulPowerDivisor = 20;        // SOUL_POWER_DIVISOR
constexpr int32_t kSoulPowerMaxSteps = 5;        // SOUL_POWER_MAX_STEPS

constexpr double kAptitudeBaseline = 80.0;       // APTITUDE_BASELINE
constexpr double kAptitudeBonusPerPoint = 0.01;  // APTITUDE_BONUS_PER_POINT
constexpr double kAptitudeMaxBonus = 0.40;       // APTITUDE_MAX_BONUS

constexpr double kGriefCultivationPenalty = 0.50;    // GRIEF_CULTIVATION_SPEED_PENALTY
constexpr double kGriefBreakthroughPenalty = 0.20;   // GRIEF_BREAKTHROUGH_CHANCE_PENALTY
constexpr double kMasterCultBonusPerGap = 0.05;      // MASTER_DISCIPLE_CULTIVATION_BONUS_PER_GAP
constexpr double kMasterBreakBonusPerGap = 0.03;     // MASTER_DISCIPLE_BREAKTHROUGH_BONUS_PER_GAP

constexpr double kLifespanPenaltyThreshold = 0.20;         // LIFESPAN_PENALTY_THRESHOLD
constexpr double kLifespanCultPenaltyPerPct = 0.05;        // LIFESPAN_CULTIVATION_PENALTY_PER_PCT
constexpr double kLifespanBreakPenaltyPerPct = 0.02;       // LIFESPAN_BREAKTHROUGH_PENALTY_PER_PCT

constexpr double kMaxBloodRefinementPct = 10.0;  // MAX_BLOOD_REFINEMENT_PCT

/// 境界配置（Kotlin GameConfig.Realm.CONFIGS）
struct RealmConfig {
    int32_t realm = 9;
    std::string name;
    int32_t cultivationBase = 0;
    int32_t breakthroughBase = 0;
    int32_t maxAge = 80;
    int32_t maxLayers = 9;
    int32_t baseHp = 0;
    int32_t baseMp = 0;
    int32_t basePhysicalAttack = 0;
    int32_t baseMagicAttack = 0;
    int32_t basePhysicalDefense = 0;
    int32_t baseMagicDefense = 0;
    int32_t baseSpeed = 0;
};

/// 境界配置表（与 GameConfig.Realm.CONFIGS 同源）
inline const std::vector<RealmConfig>& realmConfigs() {
    static const std::vector<RealmConfig> kConfigs = {
        {9, "炼气", 98, 10, 80, 9, 203, 78, 16, 16, 13, 10, 15},
        {8, "筑基", 390, 30, 120, 9, 507, 195, 39, 39, 33, 26, 38},
        {7, "金丹", 1560, 50, 200, 9, 1318, 507, 101, 101, 85, 68, 98},
        {6, "元婴", 5850, 80, 300, 9, 3448, 1326, 265, 265, 221, 177, 255},
        {5, "化神", 19500, 110, 500, 9, 9126, 3510, 702, 702, 585, 468, 675},
        {4, "炼虚", 58500, 180, 800, 9, 22308, 8580, 1716, 1716, 1430, 1144, 1650},
        {3, "合体", 195000, 220, 1500, 9, 52728, 20280, 4056, 4056, 3380, 2704, 3900},
        {2, "大乘", 585000, 280, 2500, 9, 117624, 45240, 9048, 9048, 7540, 6032, 8700},
        {1, "渡劫", 1950000, 360, 4000, 9, 243360, 93600, 18720, 18720, 15600, 12480, 18000},
        {0, "仙人", 5850000, 500, 9999, 9, 507000, 195000, 39000, 39000, 32500, 26000, 37500},
    };
    return kConfigs;
}

/// 按境界取配置（未知境界回退炼气 9）
inline const RealmConfig& realmConfig(int32_t realm) {
    for (const auto& c : realmConfigs()) {
        if (c.realm == realm) return c;
    }
    return realmConfigs().front();  // realm 9 炼气（表首）
}

/// 单灵根每旬修炼速度（Kotlin GameConfig.Cultivation.REALM_SPEED_PER_PHASE）
inline double realmSpeedPerPhase(int32_t realm) {
    static const std::map<int32_t, double> kSpeed = {
        {9, 19.0}, {8, 26.0}, {7, 43.0}, {6, 70.0}, {5, 109.0},
        {4, 212.0}, {3, 330.0}, {2, 533.0}, {1, 826.0}, {0, 1120.0},
    };
    const auto it = kSpeed.find(realm);
    return (it != kSpeed.end()) ? it->second : 19.0;
}

/// 突破概率表（Kotlin GameConfig.Realm.BREAKTHROUGH_CHANCES：realm → rootCount → prob）
inline double breakthroughChance(int32_t realm, int32_t rootCount) {
    static const std::map<std::pair<int32_t, int32_t>, double> kChances = {
        {{9, 1}, 0.90}, {{9, 2}, 0.70}, {{9, 3}, 0.60}, {{9, 4}, 0.40}, {{9, 5}, 0.30},
        {{8, 1}, 0.80}, {{8, 2}, 0.60}, {{8, 3}, 0.50}, {{8, 4}, 0.30}, {{8, 5}, 0.20},
        {{7, 1}, 0.60}, {{7, 2}, 0.40}, {{7, 3}, 0.30}, {{7, 4}, 0.10}, {{7, 5}, 0.00},
        {{6, 1}, 0.42}, {{6, 2}, 0.22}, {{6, 3}, 0.12}, {{6, 4}, 0.00}, {{6, 5}, 0.00},
        {{5, 1}, 0.34}, {{5, 2}, 0.14}, {{5, 3}, 0.04}, {{5, 4}, 0.00}, {{5, 5}, 0.00},
        {{4, 1}, 0.26}, {{4, 2}, 0.06}, {{4, 3}, 0.00}, {{4, 4}, 0.00}, {{4, 5}, 0.00},
        {{3, 1}, 0.16}, {{3, 2}, 0.00}, {{3, 3}, 0.00}, {{3, 4}, 0.00}, {{3, 5}, 0.00},
        {{2, 1}, 0.12}, {{2, 2}, 0.00}, {{2, 3}, 0.00}, {{2, 4}, 0.00}, {{2, 5}, 0.00},
        {{1, 1}, 0.06}, {{1, 2}, 0.00}, {{1, 3}, 0.00}, {{1, 4}, 0.00}, {{1, 5}, 0.00},
        {{0, 1}, 0.02}, {{0, 2}, 0.00}, {{0, 3}, 0.00}, {{0, 4}, 0.00}, {{0, 5}, 0.00},
    };
    const auto it = kChances.find({realm, rootCount});
    return (it != kChances.end()) ? it->second : 0.0;
}

// ── clamp/round 辅助（Kotlin coerceIn/coerceAtLeast/coerceAtMost/roundToInt）──

inline double coerceIn(double v, double lo, double hi) {
    return std::max(lo, std::min(hi, v));
}
inline double coerceAtLeast(double v, double lo) { return std::max(lo, v); }
inline double coerceAtMost(double v, double hi) { return std::min(hi, v); }
inline int32_t coerceIn(int32_t v, int32_t lo, int32_t hi) {
    return std::max(lo, std::min(hi, v));
}
inline int32_t roundToInt(double v) { return static_cast<int32_t>(std::round(v)); }

// ── 基础属性计算 ────────────────────────────────────────────────

/// 层数乘区（realmLayer 篡改防御：钳制非负）
inline double safeLayerMult(int32_t realmLayer) {
    return coerceAtLeast(1.0 + (realmLayer - 1) * kLayerMultiplier, 0.0);
}

/// 方差乘区（负方差钳制非负）
inline double safeVarianceMultiplier(int32_t variance) {
    return coerceAtLeast(1.0 + variance / 100.0, 0.0);
}

/// 血炼百分比乘区防御（负数/NaN/Infinity 归零，上界 10.0）
inline double safeBrPct(double pct) {
    if (!std::isfinite(pct)) return 0.0;
    return coerceIn(pct, 0.0, kMaxBloodRefinementPct);
}

/// 资质修炼速度加成（80 基准每点 +1%，最多 +40%；低于基准归零）
inline double aptitudeCultivationBonus(int32_t aptitude) {
    return coerceAtMost(
        static_cast<double>(std::max(aptitude - 80, 0)) * kAptitudeBonusPerPoint,
        kAptitudeMaxBonus);
}

/// 属性计算输入（对应 Kotlin VarianceInputs/SkillInputs 收拢）
struct BaseStatsInput {
    int32_t realm = 9;
    int32_t realmLayer = 1;
    int32_t hpVariance = 0;
    int32_t mpVariance = 0;
    int32_t physicalAttackVariance = 0;
    int32_t magicAttackVariance = 0;
    int32_t physicalDefenseVariance = 0;
    int32_t magicDefenseVariance = 0;
    int32_t speedVariance = 0;
    // 技能（skill 属性）
    int32_t intelligence = 0;
    int32_t charm = 0;
    int32_t loyalty = 0;
    int32_t comprehension = 0;
    int32_t aptitude = 50;
    int32_t teaching = 0;
    int32_t morality = 0;
    int32_t mining = 0;
    int32_t spiritPlanting = 0;
    int32_t artifactRefining = 0;
    int32_t pillRefining = 0;
    // 天赋/词条效果（key → 百分比或扁平值）
    std::map<std::string, double> talentEffects;
    // 血炼百分比累计（nullopt = 无血炼）
    double bloodHpBonusPct = 0.0;
    double bloodPhysicalAttackBonusPct = 0.0;
    double bloodMagicAttackBonusPct = 0.0;
    double bloodPhysicalDefenseBonusPct = 0.0;
    double bloodMagicDefenseBonusPct = 0.0;
    double bloodSpeedBonusPct = 0.0;
};

/// 最终属性（对应 Kotlin DiscipleStats）
struct DiscipleStats {
    int32_t hp = 0;
    int32_t maxHp = 0;
    int32_t mp = 0;
    int32_t maxMp = 0;
    int32_t physicalAttack = 0;
    int32_t magicAttack = 0;
    int32_t physicalDefense = 0;
    int32_t magicDefense = 0;
    int32_t speed = 0;
    double critRate = kBaseCritRate;
    int32_t intelligence = 0;
    int32_t charm = 0;
    int32_t loyalty = 0;
    int32_t comprehension = 0;
    int32_t aptitude = 0;
    int32_t teaching = 0;
    int32_t morality = 0;
    int32_t mining = 0;
    int32_t spiritPlanting = 0;
    int32_t artifactRefining = 0;
    int32_t pillRefining = 0;
};

/// 从 effects map 取值（缺失返回 0）
inline double effectValue(const std::map<std::string, double>& effects,
                          const std::string& key) {
    const auto it = effects.find(key);
    return (it != effects.end()) ? it->second : 0.0;
}

/// 基础属性乘区法计算（Kotlin computeBaseStats 等价）
inline DiscipleStats computeBaseStats(const BaseStatsInput& in) {
    const auto& rc = realmConfig(in.realm);
    const double layerMult = safeLayerMult(in.realmLayer);

    const double hpBonus = effectValue(in.talentEffects, "maxHp") +
                           safeBrPct(in.bloodHpBonusPct);
    const double mpBonus = effectValue(in.talentEffects, "maxMp");
    const double attackBonus = effectValue(in.talentEffects, "physicalAttack") +
                               safeBrPct(in.bloodPhysicalAttackBonusPct);
    const double magicAttackBonus = effectValue(in.talentEffects, "magicAttack") +
                                    safeBrPct(in.bloodMagicAttackBonusPct);
    const double defenseBonus = effectValue(in.talentEffects, "physicalDefense") +
                                safeBrPct(in.bloodPhysicalDefenseBonusPct);
    const double magicDefenseBonus = effectValue(in.talentEffects, "magicDefense") +
                                     safeBrPct(in.bloodMagicDefenseBonusPct);
    const double speedBonus = effectValue(in.talentEffects, "speed") +
                              safeBrPct(in.bloodSpeedBonusPct);
    const double critBonus = effectValue(in.talentEffects, "critRate");

    DiscipleStats s;
    const double hpVar = safeVarianceMultiplier(in.hpVariance);
    const double mpVar = safeVarianceMultiplier(in.mpVariance);
    s.maxHp = roundToInt(rc.baseHp * hpVar * layerMult * (1.0 + hpBonus));
    s.maxMp = roundToInt(rc.baseMp * mpVar * layerMult * (1.0 + mpBonus));
    s.hp = s.maxHp;
    s.mp = s.maxMp;
    s.physicalAttack = roundToInt(
        rc.basePhysicalAttack * safeVarianceMultiplier(in.physicalAttackVariance) *
        layerMult * (1.0 + attackBonus));
    s.magicAttack = roundToInt(
        rc.baseMagicAttack * safeVarianceMultiplier(in.magicAttackVariance) *
        layerMult * (1.0 + magicAttackBonus));
    s.physicalDefense = roundToInt(
        rc.basePhysicalDefense * safeVarianceMultiplier(in.physicalDefenseVariance) *
        layerMult * (1.0 + defenseBonus));
    s.magicDefense = roundToInt(
        rc.baseMagicDefense * safeVarianceMultiplier(in.magicDefenseVariance) *
        layerMult * (1.0 + magicDefenseBonus));
    s.speed = roundToInt(
        rc.baseSpeed * safeVarianceMultiplier(in.speedVariance) *
        layerMult * (1.0 + speedBonus));
    s.critRate = kBaseCritRate + critBonus;
    s.intelligence = in.intelligence + static_cast<int32_t>(effectValue(in.talentEffects, "intelligenceFlat"));
    s.charm = in.charm + static_cast<int32_t>(effectValue(in.talentEffects, "charmFlat"));
    s.loyalty = in.loyalty + static_cast<int32_t>(effectValue(in.talentEffects, "loyaltyFlat"));
    s.comprehension = in.comprehension + static_cast<int32_t>(effectValue(in.talentEffects, "comprehensionFlat"));
    s.aptitude = in.aptitude;
    s.teaching = in.teaching + static_cast<int32_t>(effectValue(in.talentEffects, "teachingFlat"));
    s.morality = in.morality + static_cast<int32_t>(effectValue(in.talentEffects, "moralityFlat"));
    s.mining = in.mining + static_cast<int32_t>(effectValue(in.talentEffects, "miningFlat"));
    s.spiritPlanting = in.spiritPlanting + static_cast<int32_t>(effectValue(in.talentEffects, "spiritPlantingFlat"));
    s.artifactRefining = in.artifactRefining + static_cast<int32_t>(effectValue(in.talentEffects, "artifactRefiningFlat"));
    s.pillRefining = in.pillRefining + static_cast<int32_t>(effectValue(in.talentEffects, "pillRefiningFlat"));
    return s;
}

// ── 修炼速度乘区 ────────────────────────────────────────────────

/// 修炼乘区输入（对应 Kotlin CultivationSpeedZones）
struct CultivationSpeedZones {
    double aptitudeBonus = 0.0;
    double resourceBonus = 0.0;
    double socialBonus = 0.0;
    double statusBonus = 0.0;
    double temporaryBonus = 0.0;
};

/// 每旬修炼值（Kotlin calculateCultivationPerPhase 等价）
inline double calculateCultivationPerPhase(int32_t realm, int32_t spiritRootCount,
                                           const CultivationSpeedZones& zones) {
    const int32_t rootCount = std::max(spiritRootCount, 1);
    const double base = realmSpeedPerPhase(realm) / static_cast<double>(rootCount);
    return coerceAtLeast(base
                             * (1.0 + zones.aptitudeBonus)
                             * (1.0 + zones.resourceBonus)
                             * (1.0 + zones.socialBonus)
                             * (1.0 + zones.statusBonus)
                             * (1.0 + zones.temporaryBonus),
                         kMinCultivationPerPhase);
}

// ── 突破概率乘区 ────────────────────────────────────────────────

/// 突破乘区（对应 Kotlin BreakthroughZones）
struct BreakthroughZones {
    double baseZone = 0.0;
    double elderGuidance = 0.0;
    double selfBonus = 0.0;
    double statusPenalty = 0.0;
    double adFlatBonus = 0.0;
};

/// 魂力突破加成（Kotlin getSoulPowerBreakthroughBonus）
inline double soulPowerBreakthroughBonus(int32_t soulPower) {
    return static_cast<double>(std::min(soulPower / kSoulPowerDivisor,
                                        kSoulPowerMaxSteps)) / 100.0;
}

/// 最终突破概率（Kotlin calculateBreakthroughChance 等价）
inline double calculateBreakthroughChance(const BreakthroughZones& zones) {
    const double positiveMult = 1.0 + zones.elderGuidance + zones.selfBonus;
    const double penaltyMult = coerceAtLeast(1.0 - zones.statusPenalty, 0.0);
    const double base = zones.baseZone * positiveMult * penaltyMult;
    return coerceIn(base + zones.adFlatBonus, 0.0, 1.0);
}

/// 境界+灵根+层数基础突破概率（Kotlin GameConfig.Realm.getBreakthroughChance）
inline double getBreakthroughChance(int32_t realm, int32_t rootCount,
                                    int32_t realmLayer) {
    if (realmLayer <= 0) return 0.0;
    const int32_t clampedRootCount = coerceIn(rootCount, 1, 5);
    const double currentProb = breakthroughChance(realm, clampedRootCount);
    const double nextRealmProb = breakthroughChance(realm - 1, clampedRootCount);
    const int32_t maxLayers = realmConfig(realm).maxLayers;
    if (maxLayers <= 1 || realmLayer == 1) return currentProb;
    if (realmLayer >= maxLayers) return nextRealmProb;
    const double progress = static_cast<double>(realmLayer - 1) /
                            static_cast<double>(maxLayers - 1);
    const double rawProb = currentProb + (nextRealmProb - currentProb) * progress;
    return std::round(rawProb * 100.0) / 100.0;
}

// ── 寿命/魂力/师徒/父母/丧亲 ────────────────────────────────────

/// 剩余寿命百分比（0.0~1.0；lifespan<=0 返回 1.0 无惩罚）
inline double calculateLifespanRemainingPercent(int32_t age, int32_t lifespan) {
    if (lifespan <= 0) return 1.0;
    return static_cast<double>(std::max(lifespan - age, 0)) /
           static_cast<double>(lifespan);
}

/// 寿命将尽对修炼速度的惩罚（剩余 <20% 时每百分点 -5%）
inline double calculateLifespanCultivationPenalty(int32_t age, int32_t lifespan) {
    const double remaining = calculateLifespanRemainingPercent(age, lifespan);
    if (remaining >= kLifespanPenaltyThreshold) return 0.0;
    const double deficitPercent = (kLifespanPenaltyThreshold - remaining) * 100.0;
    return deficitPercent * kLifespanCultPenaltyPerPct;
}

/// 寿命将尽对突破率的惩罚（剩余 <20% 时每百分点 -2%）
inline double calculateLifespanBreakthroughPenalty(int32_t age, int32_t lifespan) {
    const double remaining = calculateLifespanRemainingPercent(age, lifespan);
    if (remaining >= kLifespanPenaltyThreshold) return 0.0;
    const double deficitPercent = (kLifespanPenaltyThreshold - remaining) * 100.0;
    return deficitPercent * kLifespanBreakPenaltyPerPct;
}

/// 师徒大境界差（"隔整境界才算"：gap = disciple - master - 1，下限 0）
inline int32_t getMasterDiscipleRealmGap(int32_t discipleRealm, int32_t masterRealm) {
    return std::max(discipleRealm - masterRealm - 1, 0);
}

/// 师徒修炼速度加成
inline double getMasterDiscipleCultivationBonus(int32_t discipleRealm,
                                                int32_t masterRealm) {
    return getMasterDiscipleRealmGap(discipleRealm, masterRealm) *
           kMasterCultBonusPerGap;
}

/// 师徒突破率加成
inline double getMasterDiscipleBreakthroughBonus(int32_t discipleRealm,
                                                 int32_t masterRealm) {
    return getMasterDiscipleRealmGap(discipleRealm, masterRealm) *
           kMasterBreakBonusPerGap;
}

/// 父母灵根数量 → 子嗣修炼加成（单 +10% … 五 -10%）
inline double getParentSpiritRootBonus(int32_t spiritRootCount) {
    switch (spiritRootCount) {
        case 1: return 0.10;
        case 2: return 0.05;
        case 3: return 0.0;
        case 4: return -0.05;
        case 5: return -0.10;
        default: return 0.0;
    }
}

/// 是否处于丧亲悲痛期（griefEndYear 非空且 currentYear < griefEndYear）
inline bool isGrieving(int32_t griefEndYear, bool hasGrief, int32_t currentYear) {
    return hasGrief && currentYear < griefEndYear;
}

/// 境界寿命增益（Kotlin lifespanGainForRealm：realm 0→10000 … 8→50）
inline int32_t lifespanGainForRealm(int32_t realm) {
    switch (realm) {
        case 8: return 50;
        case 7: return 100;
        case 6: return 200;
        case 5: return 400;
        case 4: return 800;
        case 3: return 1500;
        case 2: return 3000;
        case 1: return 5000;
        case 0: return 10000;
        default: return 0;
    }
}

}  // namespace gamecore::disciple
