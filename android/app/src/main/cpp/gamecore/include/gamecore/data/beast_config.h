#pragma once

// ============================================================
// beast_config.h — 妖兽战斗配置表（与 Kotlin GameConfig.Beast 等价，
// createBeast 的静态配置面）
//
// 数据来源：core/domain GameConfig.kt object Beast（REALM_STATS + TYPES），
// 逐值复刻——战斗组装（battle assembly）在 C++ 侧构造妖兽 Combatant 时
// 消费本表，与 Kotlin BattleSystem.createBeast 逐位一致。
//
// 口径说明：
//   - realmIndex 0=仙人 … 9=炼气（getRealmStats 越界回退 9 档，Kotlin
//     REALM_STATS[realm] ?: REALM_STATS.getValue(9)）
//   - getType(index) 越界回退 0 号类型（虎妖），Kotlin 同款
//   - 兽表不含随机方差（方差仅地图预生成属性携带——preGenStats 路径，
//     任务战斗不传 preGenStats，走向后兼容基础值分支）
// 禁止 unordered_map 参与业务迭代（确定性红线）
// ============================================================

#include <cstdint>
#include <optional>
#include <string>
#include <tuple>
#include <vector>

#include "gamecore/system/battle_calculator.h"  // CombatSkill / BuffType / SkillType / DamageType

namespace gamecore::data {

using gamecore::battle::BuffType;
using gamecore::battle::CombatSkill;
using gamecore::battle::DamageType;
using gamecore::battle::HealType;
using gamecore::battle::SkillType;

/// 妖兽境界基准属性（Kotlin Beast.RealmStats）
struct BeastRealmStats {
    int32_t hp = 0;
    int32_t mp = 0;
    int32_t attack = 0;
    int32_t defense = 0;
    int32_t speed = 0;
};

/// 妖兽技能配置（Kotlin BeastSkillConfig 字段子集——BattleSkillConfig 全
/// 可选字段中任务战斗消费的位面；buffs 多重 buff 仅龙妖"龙威"使用）
struct BeastSkillSpec {
    const char* name;
    double damageMultiplier;
    int32_t cooldown;
    int32_t mpCost;
    SkillType skillType;
    DamageType damageType;
    int32_t hits = 1;
    bool isAoe = false;
    std::optional<BuffType> buffType;     // 单 buff（Kotlin buffType: BuffType?）
    double buffValue = 0.0;
    int32_t buffDuration = 0;
    const char* targetScope = "enemy";
    double healPercent = 0.0;
    double shieldPercent = 0.0;
    double turnAdvancePercent = 0.0;
    // 多重 buff（Kotlin buffs: List<Triple<BuffType, Double, Int>>，仅"龙威"使用）
    std::vector<std::tuple<BuffType, double, int32_t>> buffs;
};

/// 妖兽类型配置（Kotlin BeastTypeConfig）
struct BeastTypeSpec {
    const char* name;      // 类型名（"虎妖"）
    const char* prefix;    // 前缀（"狂暴"——Combatant.name = prefix + name）
    double hpMod;
    double atkMod;
    double defMod;
    double speedMod;
    double lootBonus;
    const char* element;
    const std::vector<BeastSkillSpec>* skills;
};

namespace detail {

/// Beast.REALM_STATS（下标 0..9；与 Kotlin map 字面量逐值一致）
///
/// B16/R6.2 数值外置：本表为**内联默认值兜底**，与数据文件
/// `assets/data/game-data.json` 的 `db.beastRealmStats` 段同源；
/// 由 `gamecore/data/data_inject.h` 初始化期一次性注入。
///
/// 注：beast_config 的 realm 表 / 技能表 / 类型表为**结构性数值**
/// （realm 表按 realm 索引复制、技能表按 8 类 beast 展开）——其真相源
/// 仍在 C++ 侧（见批次残余登记），本批只把表容器改为可注入形态并统一
/// 注入通道，数据文件中对应段为**可选**（缺省即用此处内联值）。
inline BeastRealmStats* beastRealmStatsTable() {
    static BeastRealmStats kStats[10] = {
        {846353, 325553, 75528, 56429, 40068},  // 0
        {406249, 156265, 36254, 27087, 19233},  // 1
        {196374,  75528, 17523, 13091,  9296},  // 2
        { 88029,  33858,  7855,  5869,  4167},  // 3
        { 37243,  14324,  3324,  2483,  1763},  // 4
        { 15236,   5860,  1359,  1016,   722},  // 5
        {  5756,   2214,   514,   384,   272},  // 6
        {  2201,    847,   195,   148,   104},  // 7
        {   847,    326,    76,    57,    41},  // 8
        {   339,    130,    31,    22,    16},  // 9
    };
    return kStats;
}

inline const BeastRealmStats& beastRealmStats(int32_t realm) {
    const BeastRealmStats* kStats = beastRealmStatsTable();
    static const BeastRealmStats kFallback = kStats[9];
    if (realm < 0 || realm > 9) return kFallback;
    return kStats[realm];
}

// ── 各类型技能表（与 Kotlin TYPES 逐技能一致）────────────────────
// B16/R6.2：技能谱系为**逻辑结构**（8 类 beast × 各自技能集），不属外置面。

inline std::vector<BeastSkillSpec>& tigerSkillsMutable() {
    static std::vector<BeastSkillSpec> k = {
        {"猛虎下山", 1.8, 3, 20, SkillType::kAttack, DamageType::kPhysical},
        {"虎爪撕裂", 0.9, 2, 15, SkillType::kAttack, DamageType::kPhysical, 2},
        {"虎啸", 0.7, 4, 30, SkillType::kAttack, DamageType::kPhysical, 1, true},
        {"咆哮", 0.0, 5, 25, SkillType::kSupport, DamageType::kPhysical, 1, false,
         BuffType::kPhysicalAttackBoost, 0.2, 3, "team"},
    };
    return k;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<BeastSkillSpec>& tigerSkills() {
    return tigerSkillsMutable();
}

inline std::vector<BeastSkillSpec>& wolfSkillsMutable() {
    static std::vector<BeastSkillSpec> k = {
        {"狼群撕咬", 0.6, 3, 20, SkillType::kAttack, DamageType::kPhysical, 3},
        {"疾风步", 0.0, 4, 20, SkillType::kSupport, DamageType::kPhysical, 1, false,
         BuffType::kSpeedBoost, 0.3, 3, "self"},
        {"围猎", 0.5, 4, 30, SkillType::kAttack, DamageType::kPhysical, 1, true},
        {"狼王嚎", 0.0, 5, 25, SkillType::kSupport, DamageType::kPhysical, 1, false,
         BuffType::kCritRateBoost, 0.1, 3, "team"},
    };
    return k;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<BeastSkillSpec>& wolfSkills() {
    return wolfSkillsMutable();
}

inline std::vector<BeastSkillSpec>& snakeSkillsMutable() {
    static std::vector<BeastSkillSpec> k = {
        {"毒牙", 1.2, 3, 15, SkillType::kAttack, DamageType::kPhysical, 1, false,
         BuffType::kPoison, 0.08, 2},
        {"毒雾", 0.5, 4, 30, SkillType::kAttack, DamageType::kPhysical, 1, true,
         BuffType::kPoison, 0.04, 2},
        {"缠绕", 0.4, 4, 20, SkillType::kAttack, DamageType::kPhysical, 1, false,
         BuffType::kSpeedReduce, 0.3, 2},
        {"蜕皮新生", 0.0, 5, 25, SkillType::kSupport, DamageType::kPhysical, 1, false,
         std::nullopt, 0.0, 0, "self", 0.25},
    };
    return k;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<BeastSkillSpec>& snakeSkills() {
    return snakeSkillsMutable();
}

inline std::vector<BeastSkillSpec>& bearSkillsMutable() {
    static std::vector<BeastSkillSpec> k = {
        {"震地", 0.6, 4, 30, SkillType::kAttack, DamageType::kPhysical, 1, true},
        {"铁壁", 0.0, 4, 15, SkillType::kSupport, DamageType::kPhysical, 1, false,
         BuffType::kPhysicalDefenseBoost, 0.4, 3, "self"},
        {"熊吼", 0.3, 4, 20, SkillType::kAttack, DamageType::kPhysical, 1, false,
         BuffType::kTaunt, 1.0, 2},
        {"坚韧熊躯", 0.0, 5, 25, SkillType::kSupport, DamageType::kPhysical, 1, false,
         BuffType::kDamageReduction, 0.2, 3, "team"},
    };
    return k;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<BeastSkillSpec>& bearSkills() {
    return bearSkillsMutable();
}

inline std::vector<BeastSkillSpec>& eagleSkillsMutable() {
    static std::vector<BeastSkillSpec> k = {
        {"俯冲", 2.0, 3, 25, SkillType::kAttack, DamageType::kPhysical},
        {"鹰眼", 0.0, 3, 15, SkillType::kSupport, DamageType::kPhysical, 1, false,
         BuffType::kCritRateBoost, 0.2, 3, "self"},
        {"旋风斩", 0.8, 4, 30, SkillType::kAttack, DamageType::kPhysical, 1, true},
        {"天翔一闪", 0.0, 5, 25, SkillType::kSupport, DamageType::kPhysical, 1, false,
         std::nullopt, 0.0, 0, "self", 0.0, 0.0, 1.0},
    };
    return k;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<BeastSkillSpec>& eagleSkills() {
    return eagleSkillsMutable();
}

inline std::vector<BeastSkillSpec>& foxSkillsMutable() {
    static std::vector<BeastSkillSpec> k = {
        {"妖术", 1.5, 3, 20, SkillType::kAttack, DamageType::kMagic, 1, false,
         BuffType::kSilence, 1.0, 1},
        {"狐火", 1.2, 3, 15, SkillType::kAttack, DamageType::kMagic, 1, false,
         BuffType::kBurn, 0.05, 2},
        {"魅惑", 0.5, 4, 20, SkillType::kAttack, DamageType::kMagic, 1, false,
         BuffType::kPhysicalAttackReduce, 0.25, 2},
        {"幻阵", 0.0, 5, 25, SkillType::kSupport, DamageType::kMagic, 1, false,
         BuffType::kDamageBoost, 0.2, 3, "team"},
    };
    return k;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<BeastSkillSpec>& foxSkills() {
    return foxSkillsMutable();
}

inline std::vector<BeastSkillSpec>& dragonSkillsMutable() {
    static std::vector<BeastSkillSpec> k = {
        {"龙息", 0.8, 4, 35, SkillType::kAttack, DamageType::kMagic, 1, true},
        {"龙爪撕裂", 1.6, 3, 20, SkillType::kAttack, DamageType::kPhysical},
        {"龙威", 0.0, 6, 30, SkillType::kSupport, DamageType::kPhysical, 1, false,
         std::nullopt, 0.0, 0, "team", 0.0, 0.0, 0.0,
         {{BuffType::kPhysicalAttackBoost, 0.25, 3}, {BuffType::kMagicAttackBoost, 0.25, 3}}},
        {"龙鳞护体", 0.0, 4, 20, SkillType::kSupport, DamageType::kPhysical, 1, false,
         BuffType::kDamageReduction, 0.2, 3, "self"},
    };
    return k;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<BeastSkillSpec>& dragonSkills() {
    return dragonSkillsMutable();
}

inline std::vector<BeastSkillSpec>& turtleSkillsMutable() {
    static std::vector<BeastSkillSpec> k = {
        {"缩壳", 0.0, 4, 15, SkillType::kSupport, DamageType::kPhysical, 1, false,
         BuffType::kPhysicalDefenseBoost, 0.5, 2, "self"},
        {"水盾", 0.0, 5, 25, SkillType::kSupport, DamageType::kMagic, 1, false,
         BuffType::kMagicDefenseBoost, 0.3, 3, "team"},
        {"激流", 0.5, 4, 25, SkillType::kAttack, DamageType::kPhysical, 1, true},
        {"龟甲术", 0.0, 6, 25, SkillType::kSupport, DamageType::kPhysical, 1, false,
         std::nullopt, 0.0, 0, "team", 0.0, 0.15},
    };
    return k;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<BeastSkillSpec>& turtleSkills() {
    return turtleSkillsMutable();
}

}  // namespace detail

/// 妖兽类型表（Kotlin Beast.TYPES 全 8 类，顺序一致）
inline std::vector<BeastTypeSpec>& beastTypesMutable() {
    static std::vector<BeastTypeSpec> kTypes = {
        {"虎妖", "狂暴", 1.3, 1.4, 0.7, 1.0, 1.1, "metal", &detail::tigerSkills()},
        {"狼妖", "迅捷", 0.6, 1.2, 0.6, 1.5, 1.0, "wood", &detail::wolfSkills()},
        {"蛇妖", "剧毒", 0.7, 1.5, 0.5, 1.1, 1.2, "water", &detail::snakeSkills()},
        {"熊妖", "铁甲", 1.5, 0.5, 1.4, 0.5, 1.1, "earth", &detail::bearSkills()},
        {"鹰妖", "神风", 0.5, 1.3, 0.5, 1.6, 1.3, "metal", &detail::eagleSkills()},
        {"狐妖", "幻魅", 0.7, 1.0, 0.7, 1.4, 1.4, "fire", &detail::foxSkills()},
        {"龙妖", "远古", 1.2, 1.3, 1.1, 1.0, 1.5, "fire", &detail::dragonSkills()},
        {"龟妖", "玄甲", 1.6, 0.4, 1.5, 0.4, 1.0, "water", &detail::turtleSkills()},
    };
    return kTypes;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<BeastTypeSpec>& beastTypes() {
    return beastTypesMutable();
}

/// 按名查找类型（Kotlin `TYPES.find { it.name == beastType } ?: getType(0)`）
inline const BeastTypeSpec* beastTypeByName(const std::string& name) {
    for (const auto& t : beastTypes()) {
        if (name == t.name) return &t;
    }
    return nullptr;
}

}  // namespace gamecore::data
