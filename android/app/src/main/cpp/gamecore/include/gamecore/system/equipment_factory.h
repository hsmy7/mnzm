// ============================================================
// equipment_factory.h — 装备唯一产出入口（B3，方案 §3.8；Kotlin
// EquipmentFactory / EquipMainStatPool / EquipAffixPool 逐位移植）
//
// 全部产出链（锻造/商店/掉落/秘境/试炼/任务/邮件/兑换码/外交/AI）一律经
// create() 生成实例：词条 roll（主词条部位池 → 3 副词条不放回）全部走
// RngPartition::kEquipment（抽取序：①主词条 ②副词条 ③等级强化，禁改）。
//
// 品阶境界约束（0.2-5 拍板，单点实现）：产出品阶不得高于弟子当前境界
// 可穿戴的最高品阶，由 maxWearableRarity 单点钳制（S17）。
//
// 对拍纪律：rollMainStat = 部位池 nextInt(size) 索引抽取；rollSubStats =
// 剩余池权重前缀和 + nextInt(total) 定点整数定位、抽中即移除（不放回 3 条）；
// 两池声明序禁重排（重排改变同种子抽取序列，破坏跨端对拍）。
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/data/equip_affix_db.h"
#include "gamecore/data/equip_main_stat_db.h"
#include "gamecore/data/equipment_db.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/state/models.h"
#include "gamecore/system/inventory.h"   // nextInstanceId（实例 id 确定性自增占位）

namespace gamecore::system::equipment_factory {

using gamecore::state::EquipAffixSet;
using gamecore::state::EquipGrowth;
using gamecore::state::EquipInstanceMeta;
using gamecore::state::EquipStatValue;
using gamecore::state::EquipmentInstance;

/// 品阶门槛（merchant_settle 同表：1..6 → 9/7/6/5/4/2；本头独立提供避免
/// include-order 引入）
inline int32_t minRealmForRarity(int32_t rarity) {
    switch (rarity) {
        case 1: return 9;
        case 2: return 7;
        case 3: return 6;
        case 4: return 5;
        case 5: return 4;
        case 6: return 2;
        default: return 9;
    }
}

/// Kotlin GameConfig.Realm.meetsRealmRequirement：discipleRealm <= minRealm
inline bool meetsRealmRequirement(int32_t discipleRealm, int32_t minRealm) {
    return discipleRealm <= minRealm;
}

/// 弟子当前境界可穿戴的最高品阶（realm 值越小境界越高）
inline int32_t maxWearableRarity(int32_t discipleRealm) {
    for (int32_t rarity = 6; rarity >= 1; --rarity) {
        if (meetsRealmRequirement(discipleRealm, minRealmForRarity(rarity))) {
            return rarity;
        }
    }
    return 1;
}

// ── 主词条池（equip_main_stat_db.h 注入表的消费面） ──────────────

/// 主词条品阶基数（暴击伤害 = 暴击率同档 × 2，避免第二张表）；非法品阶收敛最高档
inline double mainStatBaseValue(const std::string& stat, int32_t rarity) {
    const auto& rows = gamecore::data::mainStatBase();
    // 缺行回退 CRIT_RATE 行（Kotlin RARITY_BASE[stat] ?: CRIT_RATE 表 同口径）
    const std::string lookup =
        stat == "ATTACK" || stat == "DEFENSE" || stat == "HP" || stat == "CRIT_RATE"
            ? stat
            : std::string("CRIT_RATE");
    for (const auto& row : rows) {
        if (row.stat == lookup) {
            const int idx = std::min(std::max(rarity - 1, 0), 5);
            const double base = row.values[idx];
            const double scale = (stat == "CRIT_DAMAGE") ? 2.0 : 1.0;
            return base * scale;
        }
    }
    return 0.0;
}

/// 抽取主词条（部位池内等权 nextInt(size)——声明序 = 抽取序，禁重排）
inline std::string rollMainStat(const std::string& part,
                                rng::DeterministicRng& rng) {
    for (const auto& pool : gamecore::data::mainStatPools()) {
        if (pool.part == part) {
            return pool.stats[static_cast<std::size_t>(
                rng.nextInt(static_cast<int32_t>(pool.stats.size())))];
        }
    }
    return "ATTACK";   // 池缺失（协议不可达）：ATTACK 兜底
}

/// 部位数值系数（生存向 1.00 / 均衡 0.95 / 输出向 1.15）
inline double partCoefficient(const std::string& part) {
    for (const auto& pool : gamecore::data::mainStatPools()) {
        if (pool.part == part) return pool.coefficient;
    }
    return 1.0;
}

/// 主词条完整词条值：品阶基数 × 部位系数（等级成长在读取侧乘 EquipLevelCurve）
inline EquipStatValue mainStatValue(const std::string& stat,
                                    const std::string& part, int32_t rarity) {
    return EquipStatValue{stat,
                          mainStatBaseValue(stat, rarity) * partCoefficient(part)};
}

// ── 副词条池（equip_affix_db.h 注入表的消费面） ──────────────────

/// 每件装备副词条条数
constexpr int32_t kSubStatCount = 3;

/// 按权重不放回抽取 3 条副词条（初始强化次数恒 1）：剩余池权重前缀和 +
/// nextInt(total) 定点整数定位，抽中即移除（与 Kotlin rollSubStats 逐位一致）
inline std::vector<EquipStatValue> rollSubStats(int32_t rarity,
                                                rng::DeterministicRng& rng) {
    std::vector<gamecore::data::EquipAffixDef> remaining =
        gamecore::data::equipAffixes();
    std::vector<EquipStatValue> out;
    out.reserve(kSubStatCount);
    for (int32_t i = 0; i < kSubStatCount; ++i) {
        if (remaining.empty()) break;
        int32_t total = 0;
        for (const auto& def : remaining) total += def.weight;
        int32_t roll = rng.nextInt(total);
        const gamecore::data::EquipAffixDef* picked = &remaining.back();
        for (const auto& def : remaining) {
            if (roll < def.weight) {
                picked = &def;
                break;
            }
            roll -= def.weight;
        }
        const gamecore::data::EquipAffixDef pickedCopy = *picked;
        remaining.erase(std::remove_if(remaining.begin(), remaining.end(),
                            [&](const gamecore::data::EquipAffixDef& d) {
                                return d.stat == pickedCopy.stat;
                            }),
                        remaining.end());
        const int idx = std::min(std::max(rarity - 1, 0), 5);
        out.push_back(
            EquipStatValue{pickedCopy.stat, pickedCopy.tierValues[idx]});
    }
    return out;
}

// ── 产出入口 ─────────────────────────────────────────────────────

/// 部件查找（{setId}/{part} 必须在 12 部件表内；未命中返回 nullptr——
/// 调用方跳过该件产出，对应 Kotlin requirePiece error 臂的显式失败面）
inline const gamecore::data::SetPieceTemplate* findPiece(
        const std::string& setId, const std::string& part) {
    for (const auto& piece : gamecore::data::setPieceTemplates()) {
        if (piece.setId == setId && piece.part == part) return &piece;
    }
    return nullptr;
}

/// 品阶境界钳制：rarity 高于可穿上限时收敛到可穿最高档
inline int32_t clampRarity(int32_t rarity, int32_t discipleRealm) {
    const int32_t requested = std::min(std::max(rarity, 1), 6);
    return std::min(requested, maxWearableRarity(discipleRealm));
}

/// 生成一件装备实例（未命中部件返回 nullopt；实例 id = 确定性自增占位
/// ——Kotlin UUID 为非协议随机域，对拍面忽略新增条目 id）
// 「不设限」境界哨兵（值越小境界越高：顶境可穿全部品阶）——禁用 INT32_MAX
// 充当「不设限」（本口径下它是最低境界，会把全部产出钳到 T1；B3 测试实证后修正，
// 与 Kotlin EquipmentFactory.REALM_UNRESTRICTED 对齐）
inline constexpr int32_t kRealmUnrestricted = 1;

inline std::optional<EquipmentInstance> create(const std::string& setId,
                                               const std::string& part,
                                               int32_t rarity,
                                               rng::DeterministicRng& rng,
                                               int32_t discipleRealm = kRealmUnrestricted) {
    const auto* piece = findPiece(setId, part);
    if (piece == nullptr) return std::nullopt;
    const int32_t effectiveRarity = clampRarity(rarity, discipleRealm);
    const std::string mainStat = rollMainStat(part, rng);
    const EquipStatValue mainValue = mainStatValue(mainStat, part, effectiveRarity);
    const std::vector<EquipStatValue> subStats = rollSubStats(effectiveRarity, rng);

    EquipmentInstance inst;
    // 实例 id = 确定性自增占位（addEquipmentInstance 校验面拒绝空 id；
    // Kotlin UUID 为非协议随机域，对拍面忽略新增条目 id）
    inst.id = gamecore::system::nextInstanceId();
    inst.name = piece->name;
    inst.setId = piece->setId;
    inst.part = piece->part;
    inst.growth.affix.mainStat = mainValue;
    inst.growth.affix.subStats = subStats;
    inst.growth.affix.subRolls.assign(subStats.size(), 1);
    inst.meta.rarity = effectiveRarity;
    inst.meta.minRealm = minRealmForRarity(effectiveRarity);
    inst.meta.description = piece->description;
    return inst;
}

/// 按境界分层权重抽取产出品阶（0.2-5：低境界偏低品阶；权重沿用旧
/// generateRarity 分层语义——nextDouble 阈值 0.5/0.75/0.9/0.97/0.99）
inline int32_t pickRarity(int32_t minRarity, int32_t discipleRealm,
                          rng::DeterministicRng& rng) {
    const int32_t realmMax = maxWearableRarity(discipleRealm);
    const int32_t min = std::min(std::max(minRarity, 1), realmMax);
    const double roll = rng.nextDouble();
    int32_t offset;
    if (roll < 0.5) offset = 0;
    else if (roll < 0.75) offset = 1;
    else if (roll < 0.9) offset = 2;
    else if (roll < 0.97) offset = 3;
    else if (roll < 0.99) offset = 4;
    else offset = 5;
    return std::min(min + offset, realmMax);
}

/// 随机部件（同套装内六部位等权；产出链无部位偏好场景用）
inline std::string pickPart(const std::string& setId,
                            rng::DeterministicRng& rng) {
    std::vector<const gamecore::data::SetPieceTemplate*> parts;
    for (const auto& piece : gamecore::data::setPieceTemplates()) {
        if (piece.setId == setId) parts.push_back(&piece);
    }
    if (parts.empty()) return "HEAD";
    return parts[static_cast<std::size_t>(
                    rng.nextInt(static_cast<int32_t>(parts.size())))]
        ->part;
}

}  // namespace gamecore::system::equipment_factory
