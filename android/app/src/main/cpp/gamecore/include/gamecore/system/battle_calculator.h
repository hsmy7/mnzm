// ============================================================
// battle_calculator.h — 战斗计算管线
//
// 等价复刻 Kotlin `BattleCalculator`（core/engine/src/main/java/com/
// xianxia/sect/core/util/BattleCalculator.kt 982 行）的**计算管线**（非
// AI 决策层——selectSkill/selectTarget 归 battle_ai.h）：
//   - 战斗域模型：BuffType/CombatBuff/CombatSkill/Combatant（effective*
//     计算属性）/PhysiqueCombatFactors/AffixCombatEffects
//   - buildDamageZones（物理/魔法/增伤分桶 + 减伤 + 体质/词条/境界因子）
//   - calculateCombatantDamage 全链（斩杀前置 → 闪避 → 暴击 → 波动 →
//     分桶注入 → 段数钳制；RNG 消费序：闪避 1 + 暴击 1 + 波动 1，与
//     Kotlin 逐位一致）
//   - calculateDamage（CombatantStats 接口版，测试入口）
//   - estimateDamage（确定性伤害估算，无 RNG——AI 决策用）
//   - processDotEffects/dotRealmFactor、executeSupportSkill/
//     computeHealAmounts/buildSkillBuffs、updateCombatantCooldowns/
//     updateCombatantBuffsOnly、calculateDamageShare/calculateLinkedDamage
//
// 与 Kotlin 语义对齐要点（逐条对照源码）：
//   - RNG 走 BATTLE 分区（rng.getRng(RngPartition::kBattle)）
//   - effective*：buff 分桶求和 → (base × (1 + boost - reduce)).toInt()
//     .coerceAtLeast(0)；effectiveCritRate 为 (critRate + boost - reduce)
//     .coerceAtLeast(0.0)；effectiveMaxHp/Mp 无 coerceAtLeast
//   - toInt() 截断（static_cast<int32_t>）；coerceIn/coerceAtLeast 显式
//   - 多段伤害 Long 乘法防 Int 溢出（coerceIn [MIN_DAMAGE, Int.MAX]）
//   - BuffType 以枚举承载（与 Kotlin 枚举 name 字符串对应）
//
// 注意：generateBattleMessage（Kotlin BattleCalculator.generateBattleMessage）
// 与 BattleDescriptionGenerator 不值得 C++ 化（子代理评估结论）——
// 战斗日志/描述保留 Kotlin 调用方层，对拍 diff 面排除 message。
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <tuple>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/battle.h"

namespace gamecore::battle {

/// 伤害波动（DeterministicRng 版——对拍通道用；battle.h 的 RngManager 版
/// 内部取 kBattle 分区，本版直接消费传入实例，语义同 Kotlin
/// calculateDamageVariance(rng: DeterministicRng)）
inline double calculateDamageVariance(rng::DeterministicRng& rng) {
    const double variancePercent =
        rng.nextDouble() * kDamageVariancePercent * 2.0 - kDamageVariancePercent;
    const double roundedVariancePercent =
        static_cast<double>(static_cast<int32_t>(variancePercent * 10.0)) / 10.0;
    return 1.0 + roundedVariancePercent / 100.0;
}

// ============================================================
// 战斗域模型（Kotlin BattleModels.kt / CombatSkill.kt / GameConfig 枚举）
// ============================================================

/// BuffType 枚举（Kotlin GameConfig.BuffType——顺序与 name 对应）
enum class BuffType : int32_t {
    kHpBoost = 0,
    kMpBoost,
    kSpeedBoost,
    kPhysicalAttackBoost,
    kMagicAttackBoost,
    kPhysicalDefenseBoost,
    kMagicDefenseBoost,
    kCritRateBoost,
    kPhysicalAttackReduce,
    kMagicAttackReduce,
    kPhysicalDefenseReduce,
    kMagicDefenseReduce,
    kSpeedReduce,
    kCritRateReduce,
    kPoison,
    kBurn,
    kStun,
    kFreeze,
    kSilence,
    kTaunt,
    kDamageBoost,
    kDamageReduction,
    kShield,
    kDamageShare,
    kDamageLink,
    kTurnAdvance,
};

/// BuffType name 字符串 → 枚举（对拍 JSON 解析用）；未知回退 kHpBoost
inline BuffType buffTypeFromName(const std::string& name) {
    static const std::map<std::string, BuffType> kMap = {
        {"HP_BOOST", BuffType::kHpBoost},
        {"MP_BOOST", BuffType::kMpBoost},
        {"SPEED_BOOST", BuffType::kSpeedBoost},
        {"PHYSICAL_ATTACK_BOOST", BuffType::kPhysicalAttackBoost},
        {"MAGIC_ATTACK_BOOST", BuffType::kMagicAttackBoost},
        {"PHYSICAL_DEFENSE_BOOST", BuffType::kPhysicalDefenseBoost},
        {"MAGIC_DEFENSE_BOOST", BuffType::kMagicDefenseBoost},
        {"CRIT_RATE_BOOST", BuffType::kCritRateBoost},
        {"PHYSICAL_ATTACK_REDUCE", BuffType::kPhysicalAttackReduce},
        {"MAGIC_ATTACK_REDUCE", BuffType::kMagicAttackReduce},
        {"PHYSICAL_DEFENSE_REDUCE", BuffType::kPhysicalDefenseReduce},
        {"MAGIC_DEFENSE_REDUCE", BuffType::kMagicDefenseReduce},
        {"SPEED_REDUCE", BuffType::kSpeedReduce},
        {"CRIT_RATE_REDUCE", BuffType::kCritRateReduce},
        {"POISON", BuffType::kPoison},
        {"BURN", BuffType::kBurn},
        {"STUN", BuffType::kStun},
        {"FREEZE", BuffType::kFreeze},
        {"SILENCE", BuffType::kSilence},
        {"TAUNT", BuffType::kTaunt},
        {"DAMAGE_BOOST", BuffType::kDamageBoost},
        {"DAMAGE_REDUCTION", BuffType::kDamageReduction},
        {"SHIELD", BuffType::kShield},
        {"DAMAGE_SHARE", BuffType::kDamageShare},
        {"DAMAGE_LINK", BuffType::kDamageLink},
        {"TURN_ADVANCE", BuffType::kTurnAdvance},
    };
    const auto it = kMap.find(name);
    return it == kMap.end() ? BuffType::kHpBoost : it->second;
}

/// BuffType → name 字符串（对拍 JSON 输出，Kotlin 枚举 name 对应）
inline std::string buffTypeName(BuffType t) {
    switch (t) {
        case BuffType::kHpBoost: return "HP_BOOST";
        case BuffType::kMpBoost: return "MP_BOOST";
        case BuffType::kSpeedBoost: return "SPEED_BOOST";
        case BuffType::kPhysicalAttackBoost: return "PHYSICAL_ATTACK_BOOST";
        case BuffType::kMagicAttackBoost: return "MAGIC_ATTACK_BOOST";
        case BuffType::kPhysicalDefenseBoost: return "PHYSICAL_DEFENSE_BOOST";
        case BuffType::kMagicDefenseBoost: return "MAGIC_DEFENSE_BOOST";
        case BuffType::kCritRateBoost: return "CRIT_RATE_BOOST";
        case BuffType::kPhysicalAttackReduce: return "PHYSICAL_ATTACK_REDUCE";
        case BuffType::kMagicAttackReduce: return "MAGIC_ATTACK_REDUCE";
        case BuffType::kPhysicalDefenseReduce: return "PHYSICAL_DEFENSE_REDUCE";
        case BuffType::kMagicDefenseReduce: return "MAGIC_DEFENSE_REDUCE";
        case BuffType::kSpeedReduce: return "SPEED_REDUCE";
        case BuffType::kCritRateReduce: return "CRIT_RATE_REDUCE";
        case BuffType::kPoison: return "POISON";
        case BuffType::kBurn: return "BURN";
        case BuffType::kStun: return "STUN";
        case BuffType::kFreeze: return "FREEZE";
        case BuffType::kSilence: return "SILENCE";
        case BuffType::kTaunt: return "TAUNT";
        case BuffType::kDamageBoost: return "DAMAGE_BOOST";
        case BuffType::kDamageReduction: return "DAMAGE_REDUCTION";
        case BuffType::kShield: return "SHIELD";
        case BuffType::kDamageShare: return "DAMAGE_SHARE";
        case BuffType::kDamageLink: return "DAMAGE_LINK";
        case BuffType::kTurnAdvance: return "TURN_ADVANCE";
    }
    return "HP_BOOST";
}

/// isDebuff（Kotlin BuffType.isDebuff 集合）
inline bool isDebuff(BuffType t) {
    switch (t) {
        case BuffType::kPhysicalAttackReduce:
        case BuffType::kMagicAttackReduce:
        case BuffType::kPhysicalDefenseReduce:
        case BuffType::kMagicDefenseReduce:
        case BuffType::kSpeedReduce:
        case BuffType::kCritRateReduce:
        case BuffType::kPoison:
        case BuffType::kBurn:
        case BuffType::kStun:
        case BuffType::kFreeze:
        case BuffType::kSilence:
        case BuffType::kTaunt:
        case BuffType::kDamageLink:
            return true;
        default:
            return false;
    }
}

/// 战斗 Buff（Kotlin CombatBuff）
struct CombatBuff {
    BuffType type = BuffType::kHpBoost;
    double value = 0.0;
    int32_t remainingDuration = 0;
    int32_t sourceRealm = 9;
    int32_t sourceRealmLayer = 0;
};

/// 技能类型（Kotlin SkillType）
enum class SkillType : int32_t { kAttack = 0, kSupport = 1 };
/// 伤害类型（Kotlin DamageType）
enum class DamageType : int32_t { kPhysical = 0, kMagic = 1 };
/// 治疗类型（Kotlin HealType）
enum class HealType : int32_t { kHp = 0, kMp = 1 };
/// 阵营（Kotlin CombatantSide）
enum class CombatantSide : int32_t { kAttacker = 0, kDefender = 1 };

/// 战斗技能（Kotlin CombatSkill）
struct CombatSkill {
    std::string name;
    SkillType skillType = SkillType::kAttack;
    DamageType damageType = DamageType::kPhysical;
    double damageMultiplier = 1.0;
    int32_t mpCost = 0;
    int32_t cooldown = 0;
    int32_t hits = 1;
    double healPercent = 0.0;
    int32_t healFixed = 0;
    HealType healType = HealType::kHp;
    std::optional<BuffType> buffType;  // BuffType?
    double buffValue = 0.0;
    int32_t buffDuration = 0;
    // buffs：List<Triple<BuffType, Double, Int>>
    std::vector<std::tuple<BuffType, double, int32_t>> buffs;
    int32_t currentCooldown = 0;
    bool isAoe = false;
    std::string targetScope = "self";
    double shieldPercent = 0.0;
    double turnAdvancePercent = 0.0;
    double damageSharePercent = 0.0;
    double damageLinkPercent = 0.0;
};

/// 体质战斗乘算因子（Kotlin PhysiqueCombatFactors）
struct PhysiqueCombatFactors {
    double damageAmplification = 0.0;
    double critDamageBonus = 0.0;
    double damageReduction = 0.0;
    double defenseBonus = 0.0;
};

/// 词条战斗乘算因子（Kotlin AffixCombatEffects 战斗四字段）
struct AffixCombatEffects {
    double damageAmplification = 0.0;
    double critDamageBonus = 0.0;
    double damageReduction = 0.0;
    double defenseBonus = 0.0;
};

/// 战斗单位（Kotlin Combatant——含 effective* 计算属性）
struct Combatant {
    std::string id;
    std::string name;
    CombatantSide side = CombatantSide::kDefender;
    int32_t hp = 0;
    int32_t maxHp = 0;
    int32_t mp = 0;
    int32_t maxMp = 0;
    int32_t physicalAttack = 0;
    int32_t magicAttack = 0;
    int32_t physicalDefense = 0;
    int32_t magicDefense = 0;
    int32_t speed = 0;
    double critRate = 0.05;
    std::vector<CombatSkill> skills;
    std::vector<CombatBuff> buffs;
    int32_t realm = 9;
    int32_t realmLayer = 0;
    std::string element;
    bool isBeast = false;
    PhysiqueCombatFactors physique;
    AffixCombatEffects affix;

    bool isDead() const { return hp <= 0; }
    double hpPercent() const { return maxHp > 0 ? static_cast<double>(hp) / maxHp : 0.0; }
    double mpPercent() const { return maxMp > 0 ? static_cast<double>(mp) / maxMp : 0.0; }
    bool hasControlEffect() const {
        for (const auto& b : buffs) {
            if (b.type == BuffType::kStun || b.type == BuffType::kFreeze) return true;
        }
        return false;
    }

    /// buff 分桶求和（Kotlin buffs.filter{type==t}.sumOf{value}）
    double buffSum(BuffType t) const {
        double sum = 0.0;
        for (const auto& b : buffs) {
            if (b.type == t) sum += b.value;
        }
        return sum;
    }

    int32_t effectivePhysicalAttack() const {
        const double v = physicalAttack *
            (1.0 + buffSum(BuffType::kPhysicalAttackBoost) -
             buffSum(BuffType::kPhysicalAttackReduce));
        return std::max(0, static_cast<int32_t>(v));
    }
    int32_t effectiveMagicAttack() const {
        const double v = magicAttack *
            (1.0 + buffSum(BuffType::kMagicAttackBoost) -
             buffSum(BuffType::kMagicAttackReduce));
        return std::max(0, static_cast<int32_t>(v));
    }
    int32_t effectivePhysicalDefense() const {
        const double v = physicalDefense *
            (1.0 + buffSum(BuffType::kPhysicalDefenseBoost) -
             buffSum(BuffType::kPhysicalDefenseReduce));
        return std::max(0, static_cast<int32_t>(v));
    }
    int32_t effectiveMagicDefense() const {
        const double v = magicDefense *
            (1.0 + buffSum(BuffType::kMagicDefenseBoost) -
             buffSum(BuffType::kMagicDefenseReduce));
        return std::max(0, static_cast<int32_t>(v));
    }
    double effectiveCritRate() const {
        return std::max(0.0, critRate + buffSum(BuffType::kCritRateBoost) -
                                 buffSum(BuffType::kCritRateReduce));
    }
    int32_t effectiveSpeed() const {
        const double v = speed *
            (1.0 + buffSum(BuffType::kSpeedBoost) - buffSum(BuffType::kSpeedReduce));
        return std::max(0, static_cast<int32_t>(v));
    }
    int32_t effectiveMaxHp() const {
        return static_cast<int32_t>(maxHp * (1.0 + buffSum(BuffType::kHpBoost)));
    }
    int32_t effectiveMaxMp() const {
        return static_cast<int32_t>(maxMp * (1.0 + buffSum(BuffType::kMpBoost)));
    }
};

// ============================================================
// buildDamageZones（Kotlin BattleCalculator.buildDamageZones）
// ============================================================

/// 从 Combatant 的 Buff 列表构建战斗乘区（单遍历分桶求和——物理/魔法
/// 互不干扰 + 增伤桶；防守方 DAMAGE_REDUCTION 求和 + 境界三因子）
inline DamageZones buildDamageZones(const Combatant& attacker,
                                    const Combatant* defender = nullptr,
                                    double extraAmplification = 0.0) {
    double physBoost = 0.0, physReduce = 0.0, magBoost = 0.0, magReduce = 0.0;
    double dmgBoost = 0.0;
    for (const auto& buff : attacker.buffs) {
        switch (buff.type) {
            case BuffType::kPhysicalAttackBoost: physBoost += buff.value; break;
            case BuffType::kPhysicalAttackReduce: physReduce += buff.value; break;
            case BuffType::kMagicAttackBoost: magBoost += buff.value; break;
            case BuffType::kMagicAttackReduce: magReduce += buff.value; break;
            case BuffType::kDamageBoost: dmgBoost += buff.value; break;
            default: break;
        }
    }
    double dmgReduce = 0.0;
    if (defender) {
        for (const auto& buff : defender->buffs) {
            if (buff.type == BuffType::kDamageReduction) dmgReduce += buff.value;
        }
    }
    // 境界压制因子（Kotlin realmGapFactorsOf）
    DamageZones zones;
    zones.physicalAttackBuffs = physBoost - physReduce;
    zones.magicAttackBuffs = magBoost - magReduce;
    zones.damageAmplification = dmgBoost + extraAmplification;
    zones.damageReduction = dmgReduce;
    zones.physiqueDamageAmplification = attacker.physique.damageAmplification;
    zones.physiqueCritDamageBonus = attacker.physique.critDamageBonus;
    zones.physiqueDamageReduction = defender ? defender->physique.damageReduction : 0.0;
    zones.physiqueDefenseBonus = defender ? defender->physique.defenseBonus : 0.0;
    zones.affixDamageAmplification = attacker.affix.damageAmplification;
    zones.affixCritDamageBonus = attacker.affix.critDamageBonus;
    zones.affixDamageReduction = defender ? defender->affix.damageReduction : 0.0;
    zones.affixDefenseBonus = defender ? defender->affix.defenseBonus : 0.0;
    if (defender) {
        const auto realmGap = calculateRealmGapFactors(
            attacker.realm, attacker.realmLayer, defender->realm, defender->realmLayer);
        zones.realmGapDamageAmplification = realmGap.damageAmplification;
        zones.realmGapDamageReduction = realmGap.damageReduction;
        zones.majorRealmDamageAmplification = realmGap.majorRealmDamageAmplification;
    }
    return zones;
}

// ============================================================
// 闪避（Combatant 版，Kotlin calculateCombatantDodgeChance）
// ============================================================

/// 闪避概率（Kotlin calculateCombatantDodgeChance：effectiveSpeed 差/总和
/// × modifier，钳制 [0, maxDodgeChance]）
inline double calculateCombatantDodgeChance(const Combatant& attacker,
                                            const Combatant& defender,
                                            double modifier,
                                            double maxDodgeChance = kMaxDodgeChance) {
    const int32_t speedDiff = attacker.effectiveSpeed() - defender.effectiveSpeed();
    const int32_t totalSpeed = std::max(1, attacker.effectiveSpeed() + defender.effectiveSpeed());
    return clamp(speedDiff / static_cast<double>(totalSpeed) * modifier, 0.0, maxDodgeChance);
}

// ============================================================
// calculateCombatantDamage 全链（Kotlin calculateCombatantDamage +
// tryInstantKill + tryDodge + computeDamagePipeline）
// ============================================================

/// 斩杀前置检查（Kotlin tryInstantKill）：触发斩杀跳过全部伤害计算，无 RNG 消耗
inline std::optional<DamageResult> tryInstantKill(const Combatant& attacker,
                                                  const Combatant& defender,
                                                  const CombatSkill* skill,
                                                  bool enableInstantKill) {
    if (!enableInstantKill ||
        !checkInstantKill(attacker.realm, defender.realm, attacker.realmLayer,
                          defender.realmLayer)) {
        return std::nullopt;
    }
    const bool isPhysical =
        skill ? skill->damageType == DamageType::kPhysical
              : attacker.physicalAttack >= attacker.magicAttack;
    DamageResult r;
    r.damage = std::max(0, defender.maxHp);  // T-C2：maxHp 篡改钳制
    r.isCrit = false;
    r.isPhysical = isPhysical;
    r.isDodged = false;
    r.isInstantKill = true;
    r.hits = skill ? skill->hits : 1;
    return r;
}

/// 闪避判定（Kotlin tryDodge）：抽数位置保持（斩杀之后、暴击之前）
inline std::optional<DamageResult> tryDodge(const Combatant& attacker,
                                            const Combatant& defender,
                                            const CombatSkill* skill,
                                            bool isSkillAttack,
                                            rng::DeterministicRng& rng) {
    const double dodgeModifier = isSkillAttack ? 0.3 : 0.5;
    const double maxDodgeChance = isSkillAttack ? kMaxSkillDodgeChance : kMaxDodgeChance;
    const double dodgeChance =
        calculateCombatantDodgeChance(attacker, defender, dodgeModifier, maxDodgeChance);
    if (rng.nextDouble() >= dodgeChance) return std::nullopt;
    DamageResult r;
    r.damage = 0;
    r.isCrit = false;
    r.isPhysical = isSkillAttack
        ? (skill ? skill->damageType == DamageType::kPhysical : true)
        : attacker.physicalAttack >= attacker.magicAttack;
    r.isDodged = true;
    r.hits = skill ? skill->hits : 1;
    return r;
}

/// 正常伤害管线（Kotlin computeDamagePipeline）：暴击抽数 → 波动抽数 →
/// 分桶注入 → 段数钳制
inline DamageResult computeDamagePipeline(const Combatant& attacker,
                                          const Combatant& defender,
                                          const CombatSkill* skill,
                                          double damageModifier,
                                          const DamageZones* zones,
                                          bool isSkillAttack,
                                          rng::DeterministicRng& rng) {
    const bool isPhysical = isSkillAttack
        ? (skill ? skill->damageType == DamageType::kPhysical : true)
        : attacker.physicalAttack >= attacker.magicAttack;
    const int32_t attack =
        isPhysical ? attacker.physicalAttack : attacker.magicAttack;
    const int32_t defense =
        isPhysical ? defender.effectivePhysicalDefense() : defender.effectiveMagicDefense();

    const bool isCrit = rng.nextDouble() < attacker.effectiveCritRate();
    const double skillMultiplier = skill ? skill->damageMultiplier : 1.0;
    const double variance = calculateDamageVariance(rng);

    DamageZones baseZones = zones ? *zones : buildDamageZones(attacker, &defender);
    // 攻击 Buff 按攻击类型注入分桶 + damageModifier 注入增伤乘区
    baseZones.attackBuffs = baseZones.attackBuffs +
        (isPhysical ? baseZones.physicalAttackBuffs : baseZones.magicAttackBuffs);
    baseZones.damageAmplification = baseZones.damageAmplification + (damageModifier - 1.0);

    // 多段伤害：单段 × 段数（Long 防溢出；hits 篡改钳制 1）
    const int32_t safeHits = std::max(1, skill ? skill->hits : 1);
    const int64_t total = static_cast<int64_t>(calculateFinalDamage(
        attack, defense, skillMultiplier, baseZones, isCrit, variance)) * safeHits;
    const int64_t clamped =
        std::clamp(total, static_cast<int64_t>(kMinDamage), static_cast<int64_t>(INT32_MAX));

    DamageResult r;
    r.damage = static_cast<int32_t>(clamped);
    r.isCrit = isCrit;
    r.isPhysical = isPhysical;
    r.isDodged = false;
    r.hits = skill ? skill->hits : 1;
    return r;
}

/// 战斗伤害主入口（Kotlin calculateCombatantDamage）：
/// 斩杀前置 → 闪避 → 正常管线；RNG 消费序逐位一致（闪避 1 + 暴击 1 + 波动 1）
inline DamageResult calculateCombatantDamage(rng::DeterministicRng& rng,
                                             const Combatant& attacker,
                                             const Combatant& defender,
                                             const CombatSkill* skill = nullptr,
                                             double damageModifier = 1.0,
                                             const DamageZones* zones = nullptr,
                                             bool enableInstantKill = false) {
    const bool isSkillAttack = skill != nullptr;
    if (auto kill = tryInstantKill(attacker, defender, skill, enableInstantKill)) {
        return *kill;
    }
    if (auto dodge = tryDodge(attacker, defender, skill, isSkillAttack, rng)) {
        return *dodge;
    }
    return computeDamagePipeline(attacker, defender, skill, damageModifier, zones,
                                 isSkillAttack, rng);
}

// ============================================================
// calculateDamage（CombatantStats 接口版，测试入口；Kotlin 同名函数）
// ============================================================

/// 基础伤害计算入口（CombatantStats 接口，无 buffs 字段；默认 zones 为空，
/// 境界三因子以加法注入 zones——Kotlin 同语义，仅测试路径）
inline DamageResult calculateDamage(rng::DeterministicRng& rng,
                                    const CombatantStats& attacker,
                                    const CombatantStats& defender,
                                    double skillDamageMultiplier = 1.0,
                                    std::optional<bool> isPhysicalAttack = std::nullopt,
                                    const std::string* skillName = nullptr,
                                    int32_t skillHits = 1,
                                    double dodgeChanceModifier = 0.5,
                                    const DamageZones& zones = DamageZones()) {
    const double dodgeChance =
        calculateDodgeChance(attacker.speed, defender.speed, dodgeChanceModifier);
    if (rng.nextDouble() < dodgeChance) {
        DamageResult r;
        r.damage = 0;
        r.isCrit = false;
        r.isPhysical = isPhysicalAttack.value_or(true);
        r.isDodged = true;
        r.hits = skillHits;
        return r;
    }

    const bool usePhysical = isPhysicalAttack.value_or(
        attacker.physicalAttack >= attacker.magicAttack);
    const int32_t attack = usePhysical ? attacker.physicalAttack : attacker.magicAttack;
    const int32_t defense = usePhysical ? defender.physicalDefense : defender.magicDefense;

    // CombatantStats.effectiveCritRate 默认实现返回基础 critRate（Kotlin 接口默认值）
    const bool isCrit = rng.nextDouble() < attacker.critRate;
    // 境界因子独立乘算，加法注入 zones（Kotlin zones.copy 同语义）
    const auto realmGap = calculateRealmGapFactors(
        attacker.realm, attacker.realmLayer, defender.realm, defender.realmLayer);
    DamageZones zonesWithRealmGap = zones;
    zonesWithRealmGap.realmGapDamageAmplification += realmGap.damageAmplification;
    zonesWithRealmGap.realmGapDamageReduction += realmGap.damageReduction;
    zonesWithRealmGap.majorRealmDamageAmplification += realmGap.majorRealmDamageAmplification;

    const double variance = calculateDamageVariance(rng);
    const int32_t finalDamage = calculateFinalDamage(
        attack, defense, skillDamageMultiplier, zonesWithRealmGap, isCrit, variance);

    DamageResult r;
    r.damage = finalDamage;
    r.isCrit = isCrit;
    r.isPhysical = usePhysical;
    r.isDodged = false;
    r.hits = skillHits;
    return r;
}

// ============================================================
// estimateDamage（Kotlin estimateDamage：确定性估算，无 RNG——AI 决策用）
// ============================================================

/// 期望伤害估算：期望暴击（avgCritMult）+ 无闪避无波动
inline int32_t estimateDamage(const Combatant& attacker, const Combatant& defender,
                              const CombatSkill& skill, const DamageZones* zones = nullptr,
                              double damageModifier = 1.0) {
    const bool isPhysical = skill.damageType == DamageType::kPhysical;
    const int32_t atk = isPhysical ? attacker.physicalAttack : attacker.magicAttack;
    const int32_t def =
        isPhysical ? defender.effectivePhysicalDefense() : defender.effectiveMagicDefense();

    DamageZones baseZones = zones ? *zones : buildDamageZones(attacker, &defender);
    baseZones.attackBuffs = baseZones.attackBuffs +
        (isPhysical ? baseZones.physicalAttackBuffs : baseZones.magicAttackBuffs);
    baseZones.damageAmplification = baseZones.damageAmplification + (damageModifier - 1.0);

    // 期望暴击：avgCritMult = (1-p) + p × (1+基础暴伤) × (1+体质暴伤) × (1+词条暴伤)
    const double buffCritMult = 1.0 + kCritBaseMultiplier;
    const double physiqueCritMult = 1.0 + baseZones.physiqueCritDamageBonus;
    const double affixCritMult = 1.0 + baseZones.affixCritDamageBonus;
    const double critRate = clamp(attacker.effectiveCritRate(), 0.0, 1.0);
    const double avgCritMult =
        (1.0 - critRate) * 1.0 + critRate * buffCritMult * physiqueCritMult * affixCritMult;

    const double penFactor =
        std::max(0.0, 1.0 - baseZones.physiqueDefenseBonus) *
        std::max(0.0, 1.0 - baseZones.affixDefenseBonus);
    const double effectiveDef = def * penFactor;
    const double reduction = effectiveDef / (effectiveDef + kDefenseConstant);

    const double preCritDmg = static_cast<double>(atk) * (1.0 + baseZones.attackBuffs) *
        skill.damageMultiplier * (1.0 - reduction);
    const double rawDmg = preCritDmg * avgCritMult *
        (1.0 + baseZones.damageAmplification) *
        (1.0 + baseZones.physiqueDamageAmplification) *
        (1.0 + baseZones.affixDamageAmplification) *
        (1.0 + baseZones.realmGapDamageAmplification) *
        (1.0 + baseZones.majorRealmDamageAmplification) *
        (1.0 - baseZones.damageReduction) *
        (1.0 - baseZones.physiqueDamageReduction) *
        (1.0 - baseZones.affixDamageReduction) *
        (1.0 - baseZones.realmGapDamageReduction) * skill.hits;
    return std::max(kMinDamage, static_cast<int32_t>(rawDmg));
}

// ============================================================
// DoT（Kotlin processDotEffects + dotRealmFactor）
// ============================================================

/// 单 DoT 结算结果（Kotlin DotResult）
struct DotResult {
    std::string combatantId;
    int32_t damage = 0;
    int32_t newHp = 0;
};

/// DoT 境界压制倍率 = (1+小层增伤) × (1+大境界增伤) × (1-减伤)
inline double dotRealmFactor(const CombatBuff& buff, const Combatant& defender) {
    const auto factors = calculateRealmGapFactors(
        buff.sourceRealm, buff.sourceRealmLayer, defender.realm, defender.realmLayer);
    return (1.0 + factors.damageAmplification) *
        (1.0 + factors.majorRealmDamageAmplification) *
        (1.0 - factors.damageReduction);
}

/// 全体 DoT 结算（Kotlin processDotEffects）：POISON/BURN 各自累加（Long
/// 防溢出），伤害 = maxHp × value × dotRealmFactor；仅伤害 > 0 产出结果
inline std::vector<DotResult> processDotEffects(const std::vector<Combatant>& combatants) {
    std::vector<DotResult> results;
    for (const auto& combatant : combatants) {
        int64_t dotDamage = 0;
        for (const auto& buff : combatant.buffs) {
            if ((buff.type == BuffType::kPoison || buff.type == BuffType::kBurn) &&
                buff.remainingDuration > 0) {
                dotDamage += static_cast<int64_t>(
                    combatant.maxHp * buff.value * dotRealmFactor(buff, combatant));
            }
        }
        const int64_t clamped =
            std::clamp(dotDamage, static_cast<int64_t>(kMinDamage),
                       static_cast<int64_t>(INT32_MAX));
        const int32_t dotDamageFinal = static_cast<int32_t>(clamped);
        if (dotDamageFinal > 0) {
            DotResult r;
            r.combatantId = combatant.id;
            r.damage = dotDamageFinal;
            r.newHp = std::max(0, combatant.hp - dotDamageFinal);
            results.push_back(r);
        }
    }
    return results;
}

// ============================================================
// 辅助技能（Kotlin executeSupportSkill + computeHealAmounts +
// buildSkillBuffs）
// ============================================================

/// 辅助技能结果（Kotlin SupportResult）
struct SupportResult {
    int32_t healAmount = 0;
    std::vector<std::string> healedIds;
    std::map<std::string, std::vector<CombatBuff>> teamBuffs;
    double turnAdvancePercent = 0.0;
    HealType healType = HealType::kHp;
};

/// 治疗量计算（Kotlin computeHealAmounts：百分比 + 固定值）
inline void computeHealAmounts(const Combatant& caster, const CombatSkill& skill,
                               int32_t& healAmount, int32_t& healFixedAmount) {
    healAmount = 0;
    healFixedAmount = 0;
    if (skill.healPercent > 0) {
        healAmount = (skill.healType == HealType::kMp)
            ? static_cast<int32_t>(caster.maxMp * skill.healPercent)
            : static_cast<int32_t>(caster.maxHp * skill.healPercent);
    }
    if (skill.healFixed > 0) {
        healFixedAmount = skill.healFixed;
    }
}

/// 团队 BUFF 构建（Kotlin buildSkillBuffs：护盾/伤害分摊/旧单 BUFF/多 BUFF）
inline std::map<std::string, std::vector<CombatBuff>> buildSkillBuffs(
    const CombatSkill& skill, const std::vector<Combatant>& targets,
    int32_t sourceRealm, int32_t sourceRealmLayer) {
    std::map<std::string, std::vector<CombatBuff>> teamBuffs;
    auto addToAll = [&](const CombatBuff& buff) {
        for (const auto& member : targets) {
            auto& list = teamBuffs[member.id];
            list.push_back(buff);
        }
    };
    if (skill.shieldPercent > 0 && skill.buffDuration > 0) {
        CombatBuff shieldBuff;
        shieldBuff.type = BuffType::kShield;
        shieldBuff.value = skill.shieldPercent;
        shieldBuff.remainingDuration = skill.buffDuration;
        shieldBuff.sourceRealm = sourceRealm;
        shieldBuff.sourceRealmLayer = sourceRealmLayer;
        addToAll(shieldBuff);
    }
    if (skill.damageSharePercent > 0 && skill.buffDuration > 0) {
        CombatBuff shareBuff;
        shareBuff.type = BuffType::kDamageShare;
        shareBuff.value = skill.damageSharePercent;
        shareBuff.remainingDuration = skill.buffDuration;
        shareBuff.sourceRealm = sourceRealm;
        shareBuff.sourceRealmLayer = sourceRealmLayer;
        addToAll(shareBuff);
    }
    if (skill.buffType.has_value() && skill.buffDuration > 0) {
        CombatBuff buff;
        buff.type = *skill.buffType;
        buff.value = skill.buffValue;
        buff.remainingDuration = skill.buffDuration;
        buff.sourceRealm = sourceRealm;
        buff.sourceRealmLayer = sourceRealmLayer;
        addToAll(buff);
    }
    for (const auto& [buffType, buffValue, buffDuration] : skill.buffs) {
        CombatBuff buff;
        buff.type = buffType;
        buff.value = buffValue;
        buff.remainingDuration = buffDuration;
        buff.sourceRealm = sourceRealm;
        buff.sourceRealmLayer = sourceRealmLayer;
        addToAll(buff);
    }
    return teamBuffs;
}

/// 辅助技能执行（Kotlin executeSupportSkill）
inline SupportResult executeSupportSkill(const Combatant& caster,
                                         const std::vector<Combatant>& allies,
                                         const CombatSkill& skill) {
    // targets 语义（Kotlin when）："team"→allies / "ally"→空（调用方解析）/
    // 其他（self）→仅施放者
    std::vector<Combatant> targets;
    if (skill.targetScope == "team") {
        targets = allies;
    } else if (skill.targetScope == "ally") {
        targets = {};
    } else {
        targets = {caster};
    }

    int32_t healAmount = 0, healFixedAmount = 0;
    computeHealAmounts(caster, skill, healAmount, healFixedAmount);
    const int32_t totalHeal = healAmount + healFixedAmount;
    std::vector<std::string> healedIds;
    if (totalHeal > 0) {
        for (const auto& t : targets) healedIds.push_back(t.id);
    }
    const auto teamBuffs =
        buildSkillBuffs(skill, targets, caster.realm, caster.realmLayer);
    SupportResult r;
    r.healAmount = totalHeal;
    r.healedIds = healedIds;
    r.teamBuffs = teamBuffs;
    r.turnAdvancePercent = skill.turnAdvancePercent;
    r.healType = skill.healType;
    return r;
}

// ============================================================
// 冷却与 Buff 衰减（Kotlin updateCombatantCooldowns +
// updateCombatantBuffsOnly）
// ============================================================

/// 技能冷却更新 + Buff 衰减 + MP 扣减（Kotlin updateCombatantCooldowns）
inline Combatant updateCombatantCooldowns(Combatant combatant,
                                          const CombatSkill& usedSkill) {
    for (auto& skill : combatant.skills) {
        if (skill.name == usedSkill.name) {
            skill.currentCooldown = skill.cooldown;
        } else {
            skill.currentCooldown = std::max(0, skill.currentCooldown - 1);
        }
    }
    std::vector<CombatBuff> existingBuffs;
    for (auto buff : combatant.buffs) {
        buff.remainingDuration = std::max(0, buff.remainingDuration - 1);
        if (buff.remainingDuration > 0) existingBuffs.push_back(buff);
    }
    combatant.mp = combatant.mp - usedSkill.mpCost;
    combatant.buffs = existingBuffs;
    return combatant;
}

/// 仅 Buff 衰减（Kotlin updateCombatantBuffsOnly）
inline Combatant updateCombatantBuffsOnly(Combatant combatant) {
    std::vector<CombatBuff> newBuffs;
    for (auto buff : combatant.buffs) {
        buff.remainingDuration = std::max(0, buff.remainingDuration - 1);
        if (buff.remainingDuration > 0) newBuffs.push_back(buff);
    }
    combatant.buffs = newBuffs;
    return combatant;
}

// ============================================================
// 伤害分摊 / 伤害链接（Kotlin calculateDamageShare / calculateLinkedDamage）
// ============================================================

/// 伤害分摊（Kotlin calculateDamageShare：队友带 DAMAGE_SHARE 者按比例分担）
inline std::map<std::string, int32_t> calculateDamageShare(
    const std::string& targetId, CombatantSide targetSide, int32_t incomingDamage,
    const std::vector<Combatant>& team, const std::vector<Combatant>& beasts) {
    std::map<std::string, int32_t> extraDamage;
    const auto& allies =
        (targetSide == CombatantSide::kDefender) ? team : beasts;
    for (const auto& ally : allies) {
        if (ally.id == targetId || ally.isDead()) continue;
        bool hasShare = false;
        double shareValue = 0.0;
        for (const auto& b : ally.buffs) {
            if (b.type == BuffType::kDamageShare && b.remainingDuration > 0) {
                hasShare = true;
                shareValue = b.value;
                break;
            }
        }
        if (!hasShare) continue;
        const int32_t shareDamage = static_cast<int32_t>(incomingDamage * shareValue);
        extraDamage[ally.id] = (extraDamage.count(ally.id) ? extraDamage[ally.id] : 0) +
            shareDamage;
    }
    return extraDamage;
}

/// 伤害链接（Kotlin calculateLinkedDamage：敌方带 DAMAGE_LINK 者承受比例
/// 伤害；仅第一个命中敌人）
inline std::map<std::string, int32_t> calculateLinkedDamage(
    const Combatant& attacker, const Combatant& target, int32_t damage,
    const std::vector<Combatant>& beasts, const std::vector<Combatant>& team) {
    std::map<std::string, int32_t> linkedDamage;
    const auto& enemies =
        (attacker.side == CombatantSide::kDefender) ? beasts : team;
    for (const auto& enemy : enemies) {
        if (enemy.id == target.id || enemy.isDead()) continue;
        bool hasLink = false;
        double linkValue = 0.0;
        for (const auto& b : enemy.buffs) {
            if (b.type == BuffType::kDamageLink && b.remainingDuration > 0) {
                hasLink = true;
                linkValue = b.value;
                break;
            }
        }
        if (!hasLink) continue;
        linkedDamage[enemy.id] = std::max(1, static_cast<int32_t>(damage * linkValue));
        break;  // Only one enemy can be linked at a time
    }
    return linkedDamage;
}

}  // namespace gamecore::battle
