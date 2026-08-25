#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/disciple.h"

// ============================================================
// 战斗计算（Kotlin→C++ 迁移批次 6a）
//
// 等价移植 Kotlin BattleCalculator 的**纯公式**部分：
//   - 乘区法最终伤害（calculateFinalDamage：攻防减伤 + 暴击 + 体质/词条/
//     境界压制独立乘算因子 + 波动）
//   - 伤害波动（calculateDamageVariance：±20% 抖动，1 位小数）
//   - 闪避概率（calculateDodgeChance：速度差/总速度 × modifier）
//   - 跨境界压制因子（calculateRealmGapFactors：小层增伤/减伤 + 大境界增伤，
//     Long 中间运算防溢出，safeRealm/safeLayer 篡改钳制）
//   - 斩杀判定（checkInstantKill）
//   - 护盾吸收（calculateShieldAbsorption）
//   - DoT 处理（processDotEffects 单目标）
//   - 技能冷却更新（updateCombatantCooldowns）
//
// 与 Kotlin 语义对齐要点：
//   - toInt() 截断；coerceIn/coerceAtLeast/coerceAtMost 显式实现
//   - roundToInt = std::round（Kotlin roundToInt 四舍五入）
//   - 境界：0=仙人 … 9=炼气（数值越小境界越高）
//   - 波动四舍五入到 1 位小数：round(variancePercent * 10) / 10
// ============================================================
namespace gamecore::battle {

// ── 战斗常量（Kotlin GameConfig.Battle）────────────────────────

constexpr double kCritBaseMultiplier = 0.5;      // CRIT_BASE_MULTIPLIER
constexpr double kMaxDodgeChance = 0.5;          // MAX_DODGE_CHANCE
constexpr double kMaxSkillDodgeChance = 0.3;     // MAX_SKILL_DODGE_CHANCE
constexpr double kDefenseConstant = 500.0;       // DEFENSE_CONSTANT
constexpr double kDamageVariancePercent = 20.0;  // DAMAGE_VARIANCE_PERCENT
constexpr int32_t kMinDamage = 1;                // MIN_DAMAGE
constexpr int32_t kLayersPerRealm = 9;           // LAYERS_PER_REALM（所有境界 9 层）

constexpr double kRealmGapDamageBonusPerLayer = 0.30;   // 每高 1 小层 +30%
constexpr double kRealmGapDamageReductionPerLayer = 0.30;  // 每高 1 小层 -30%（封顶 100%）
constexpr int32_t kInstantKillGap = 1;           // 高 1 个大境界斩杀
constexpr double kRealmGapDamageBonusPerMajorRealm = 1.0;  // 每高 1 大境界 +100%

// ── 状态类型（对应 Kotlin Combatant 核心字段）───────────────────

/// 战斗单位状态（Combatant 精简版；Buff 由外部系统管理）
struct CombatantStats {
    int32_t physicalAttack = 0;
    int32_t magicAttack = 0;
    int32_t physicalDefense = 0;
    int32_t magicDefense = 0;
    int32_t speed = 0;
    double critRate = 0.05;
    int32_t realm = 9;       // 0=仙人 … 9=炼气
    int32_t realmLayer = 1;  // 1~9
    int32_t maxHp = 0;
    int32_t maxMp = 0;
    int32_t hp = 0;
    int32_t mp = 0;
};

// ── 伤害乘区（对应 Kotlin DamageZones）──────────────────────────

struct DamageZones {
    double attackBuffs = 0.0;                // 按攻击类型注入的攻防 Buff
    double physicalAttackBuffs = 0.0;        // 物理攻击 Buff 分桶
    double magicAttackBuffs = 0.0;           // 魔法攻击 Buff 分桶
    double damageAmplification = 0.0;        // 增伤乘区
    double damageReduction = 0.0;            // 减伤乘区
    double physiqueDamageAmplification = 0.0;  // 体质增伤（独立乘算）
    double physiqueCritDamageBonus = 0.0;      // 体质暴伤（独立乘算，仅暴击）
    double physiqueDamageReduction = 0.0;      // 体质减伤（独立乘算）
    double physiqueDefenseBonus = 0.0;         // 体质防御加成（独立乘算）
    double affixDamageAmplification = 0.0;     // 词条增伤（独立乘算）
    double affixCritDamageBonus = 0.0;         // 词条暴伤（独立乘算，仅暴击）
    double affixDamageReduction = 0.0;         // 词条减伤（独立乘算）
    double affixDefenseBonus = 0.0;            // 词条防御加成（独立乘算）
    double realmGapDamageAmplification = 0.0;  // 境界压制增伤（独立乘算）
    double realmGapDamageReduction = 0.0;      // 境界压制减伤（独立乘算）
    double majorRealmDamageAmplification = 0.0; // 大境界增伤（独立乘算）
};

/// 伤害结果（对应 Kotlin DamageResult）
struct DamageResult {
    int32_t damage = 0;
    bool isCrit = false;
    bool isPhysical = true;
    bool isDodged = false;
    bool isInstantKill = false;
    int32_t hits = 1;
};

// ── 辅助（clamp/round）─────────────────────────────────────────

inline double clamp(double v, double lo, double hi) {
    return std::max(lo, std::min(hi, v));
}
inline int32_t clamp(int32_t v, int32_t lo, int32_t hi) {
    return std::max(lo, std::min(hi, v));
}
inline int32_t roundToInt(double v) { return static_cast<int32_t>(std::round(v)); }

/// 小层境界安全钳制（1~9）：0/越界回退合法层数（Kotlin safeLayer）
inline int32_t safeLayer(int32_t layer) {
    return clamp(layer, 1, kLayersPerRealm);
}

/// 大境界安全钳制（0~9）：负值/越界回退合法域（Kotlin safeRealm）
inline int32_t safeRealm(int32_t realm) {
    return clamp(realm, 0, 9);
}

// ── 伤害波动 ───────────────────────────────────────────────────

/// 伤害波动（Kotlin calculateDamageVariance：±20%，1 位小数）
inline double calculateDamageVariance(rng::RngManager& rng) {
    const double variancePercent =
        rng.getRng(rng::RngPartition::kBattle).nextDouble() *
            kDamageVariancePercent * 2.0 - kDamageVariancePercent;
    const double roundedVariancePercent =
        static_cast<double>(static_cast<int32_t>(variancePercent * 10.0)) / 10.0;
    return 1.0 + roundedVariancePercent / 100.0;
}

// ── 跨境界压制因子 ─────────────────────────────────────────────

/// 境界压制三因子（Kotlin RealmGapFactors）
struct RealmGapFactors {
    double damageAmplification = 0.0;
    double damageReduction = 0.0;
    double majorRealmDamageAmplification = 0.0;
};

/// 跨境界压制因子（Kotlin calculateRealmGapFactors）
inline RealmGapFactors calculateRealmGapFactors(
    int32_t attackerRealm, int32_t attackerLayer,
    int32_t defenderRealm, int32_t defenderLayer) {
    const int32_t attackerRealmSafe = safeRealm(attackerRealm);
    const int32_t defenderRealmSafe = safeRealm(defenderRealm);
    // Long 中间运算防 Int 溢出回绕
    const int64_t majorGap =
        static_cast<int64_t>(defenderRealmSafe) - static_cast<int64_t>(attackerRealmSafe);
    const int64_t layerGap = majorGap * kLayersPerRealm +
        (static_cast<int64_t>(safeLayer(attackerLayer)) - static_cast<int64_t>(safeLayer(defenderLayer)));

    RealmGapFactors out;
    // 攻击方境界更高（layerGap > 0）：每层 +30% 增伤（不封顶）
    out.damageAmplification =
        (layerGap > 0) ? kRealmGapDamageBonusPerLayer * static_cast<double>(layerGap) : 0.0;
    // 防守方境界更高（layerGap < 0）：每层 -30% 减伤（封顶 100%）
    out.damageReduction =
        (layerGap < 0) ? std::min(1.0, kRealmGapDamageReductionPerLayer * static_cast<double>(-layerGap)) : 0.0;
    // 大境界加成仅增伤方向（攻击方每高 1 大境界 +100%）；配置负值钳制 0
    out.majorRealmDamageAmplification =
        (majorGap > 0) ? std::max(0.0, kRealmGapDamageBonusPerMajorRealm * static_cast<double>(majorGap)) : 0.0;
    return out;
}

/// 跨境界斩杀判定（Kotlin checkInstantKill）
inline bool checkInstantKill(int32_t attackerRealm, int32_t defenderRealm,
                             int32_t attackerLayer, int32_t defenderLayer) {
    const int64_t gap =
        (static_cast<int64_t>(safeRealm(defenderRealm)) - static_cast<int64_t>(safeRealm(attackerRealm))) *
            kLayersPerRealm +
        (static_cast<int64_t>(safeLayer(attackerLayer)) - static_cast<int64_t>(safeLayer(defenderLayer)));
    return gap > static_cast<int64_t>(kInstantKillGap) * kLayersPerRealm;
}

// ── 乘区法最终伤害 ─────────────────────────────────────────────

/// 乘区法最终伤害（Kotlin calculateFinalDamage）
inline int32_t calculateFinalDamage(int32_t rawAttack, int32_t defense,
                                    double skillMultiplier,
                                    const DamageZones& zones,
                                    bool isCrit, double variance) {
    const double effectiveAttack = rawAttack * (1.0 + zones.attackBuffs);
    // 防御乘区：(1 - 体质防御) × (1 - 词条防御)，独立乘算
    const double effectiveDefense = defense *
        std::max(1.0 - zones.physiqueDefenseBonus, 0.0) *
        std::max(1.0 - zones.affixDefenseBonus, 0.0);
    const double reduction =
        effectiveDefense / (effectiveDefense + kDefenseConstant);
    const double preCritDamage =
        effectiveAttack * skillMultiplier * (1.0 - reduction);
    const double critMult = isCrit ? (1.0 + kCritBaseMultiplier) : 1.0;
    const double physiqueCritMult = isCrit ? (1.0 + zones.physiqueCritDamageBonus) : 1.0;
    const double affixCritMult = isCrit ? (1.0 + zones.affixCritDamageBonus) : 1.0;
    const double result =
        preCritDamage * critMult * physiqueCritMult * affixCritMult
        * (1.0 + zones.damageAmplification)
        * (1.0 + zones.physiqueDamageAmplification)
        * (1.0 + zones.affixDamageAmplification)
        * (1.0 + zones.realmGapDamageAmplification)
        * (1.0 + zones.majorRealmDamageAmplification)
        * (1.0 - zones.damageReduction)
        * (1.0 - zones.physiqueDamageReduction)
        * (1.0 - zones.affixDamageReduction)
        * (1.0 - zones.realmGapDamageReduction)
        * variance;
    return std::max(static_cast<int32_t>(result), kMinDamage);
}

// ── 闪避 ───────────────────────────────────────────────────────

/// 闪避概率（Kotlin calculateDodgeChance）
inline double calculateDodgeChance(int32_t attackerSpeed, int32_t defenderSpeed,
                                   double modifier) {
    const double speedDiff = attackerSpeed - defenderSpeed;
    const int32_t totalSpeed = std::max(attackerSpeed + defenderSpeed, 1);
    return clamp(speedDiff / static_cast<double>(totalSpeed) * modifier,
                 0.0, kMaxDodgeChance);
}

// ── 护盾吸收 ───────────────────────────────────────────────────

/// 护盾吸收结果（Kotlin ShieldResult）
struct ShieldResult {
    int32_t absorbed = 0;
    int32_t remainingDamage = 0;
    double remainingShield = 0.0;
    bool hasShield = false;
    double shieldValue = 0.0;  // 被消耗护盾的原始 value（余量写回匹配用）
};

/// 单护盾吸收（取最大 value 的活跃护盾；Kotlin calculateShieldAbsorption）
/// @param maxShieldValue 当前最大护盾比例（0~1；调用方从 Buff 列表提取）
/// @param shieldActive 是否有活跃护盾
inline ShieldResult calculateShieldAbsorption(int32_t maxHp,
                                              double maxShieldValue,
                                              bool shieldActive,
                                              int32_t incomingDamage) {
    ShieldResult out;
    if (!shieldActive) {
        out.remainingDamage = incomingDamage;
        return out;
    }
    const double safeValue = clamp(maxShieldValue, 0.0, 1.0);
    const int32_t shieldValue =
        std::max(static_cast<int32_t>(maxHp * safeValue), 0);
    out.absorbed = std::min(shieldValue, incomingDamage);
    out.remainingDamage = incomingDamage - out.absorbed;
    out.remainingShield = std::max(shieldValue - out.absorbed, 0);
    out.hasShield = true;
    out.shieldValue = maxShieldValue;
    return out;
}

// ── DoT（持续伤害）─────────────────────────────────────────────

/// 单目标 DoT 结算（Kotlin processDotEffects 单 combatant 部分）
/// @param dotDamage 累计 DoT 伤害（调用方已按 Buff 累加；含境界因子）
/// @return 结算后 HP（clamp 到 [0, maxHp] 由调用方处理）
inline int32_t applyDotDamage(int32_t currentHp, int64_t dotDamage) {
    if (dotDamage <= 0) return currentHp;
    const int32_t clamped = clamp(static_cast<int32_t>(
        clamp(static_cast<double>(dotDamage),
              static_cast<double>(kMinDamage),
              static_cast<double>(INT32_MAX))), kMinDamage, INT32_MAX);
    return std::max(currentHp - clamped, 0);
}

// ── 技能冷却 ───────────────────────────────────────────────────

/// 技能冷却更新（Kotlin updateCombatantCooldowns 的冷却部分）
/// @param currentCooldowns 各技能当前冷却（按技能名）
/// @param usedSkillName 使用的技能名（置为其冷却上限）
/// @param cooldownOfUsed 使用技能的冷却上限
/// @return 更新后的冷却列表（与输入同序）
inline std::vector<int32_t> updateCooldowns(
    const std::vector<std::string>& skillNames,
    const std::vector<int32_t>& currentCooldowns,
    const std::string& usedSkillName, int32_t cooldownOfUsed) {
    std::vector<int32_t> out;
    out.reserve(currentCooldowns.size());
    for (size_t i = 0; i < currentCooldowns.size(); ++i) {
        if (skillNames[i] == usedSkillName) {
            out.push_back(cooldownOfUsed);
        } else {
            out.push_back(std::max(currentCooldowns[i] - 1, 0));
        }
    }
    return out;
}

}  // namespace gamecore::battle
