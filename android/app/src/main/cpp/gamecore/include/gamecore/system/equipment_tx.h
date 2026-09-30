// ============================================================
// equipment_tx.h — 装备升级/分解事务（B3，方案 §3.6/§3.9；ActionId
// EQUIP_UPGRADE=1486 / EQUIP_DISMANTLE=1487）
//
// 语义权威 = Kotlin EquipmentUpgradeService + EquipmentLevelSystem（等价移植，
// 逐位对齐）；C++ 真相先行、失败信封 → Kotlin 回退臂重执行同校验链。
//
// ## 升级（upgradeEquipmentTx）
// 每次升 1 级：扣灵石（100 × rarity² × level）+ 兽材（max(1, level/10) 件）；
// 经验由消耗直接折算（升级材料为唯一升级路径）；升级动作完成时判定
// newLevel % 3 == 0 触发一次副词条强化（RngPartition::kEquipment
// nextInt(subStats.size)，上限 MAX_SUB_ROLLS=11）。材料不足/已满级失败
// 且**不扣材料**（校验链先行）。
//
// ## 分解（dismantleEquipmentTx）
// 返还累计升级消耗 × 50%（灵石向下取整 + 兽材向下取整件数）；isLocked 或
// 已穿戴拒分解；弟子储物袋内同件条目一并清除（防分解后取回复活）。
//
// ## 兽材口径（双端同口径）
// 消耗/返还的"兽材" = **任意类目材料合并计数**，按 (rarity 升序, id 升序)
// 逐堆叠扣减/返还（返还铸入 rarity==1 最低档堆叠语义）。
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/state/models.h"
#include "gamecore/system/inventory.h"   // nextInstanceId（返还条目确定性 id）

namespace gamecore::system::equipment_tx {

using gamecore::state::DiscipleStore;
using gamecore::state::EquipmentInstance;
using gamecore::state::GameState;

// ── 等级曲线常量（Kotlin EquipLevelCurve 镜像） ──────────────────

constexpr int32_t kMinLevel = 1;
constexpr int32_t kMaxLevel = 30;            // 30 级封顶（S4）
constexpr int32_t kReinforceInterval = 3;    // 每 3 级一次强化（Lv30 共 10 次）
constexpr int32_t kMaxSubRolls = 11;         // 单条副词条最大强化次数
constexpr double kDismantleRefundRatio = 0.5;
constexpr int64_t kBaseSpiritStoneCost = 100;   // 100 × rarity² × level
constexpr int32_t kBaseExpCost = 100;           // 100 × level × rarityMul
constexpr int32_t kBeastMaterialDivisor = 10;   // max(1, level / 10)

/// 经验曲线品阶倍率（rarity 1..6；声明序禁重排——改序会改变升级阈值）
inline double rarityExpMultiplier(int32_t rarity) {
    static constexpr double kMultipliers[] = {1.0, 1.5, 2.0, 3.0, 4.5, 6.0};
    const int idx = std::min(std::max(rarity - 1, 0), 5);
    return kMultipliers[idx];
}

inline bool isMaxLevel(int32_t level) { return level >= kMaxLevel; }

/// 升到 level+1 级所需经验（100 × level × rarityMul，Kotlin .toInt() 截断）
inline int32_t expRequired(int32_t level, int32_t rarity) {
    return static_cast<int32_t>(
        static_cast<double>(kBaseExpCost * level) * rarityExpMultiplier(rarity));
}

/// 升级消耗（灵石，level → level+1）
inline int64_t spiritStonesCost(int32_t level, int32_t rarity) {
    return kBaseSpiritStoneCost *
           static_cast<int64_t>(rarity) * static_cast<int64_t>(rarity) *
           static_cast<int64_t>(level);
}

/// 升级消耗（兽材件数，level → level+1）
inline int32_t beastMaterialCost(int32_t level) {
    const int32_t raw =
        static_cast<int32_t>(static_cast<double>(level) / kBeastMaterialDivisor);
    return std::max(1, raw);
}

/// 从 Lv1 升到 toLevel 的累计灵石消耗（分解返还口径与升级消耗同源）
inline int64_t totalSpiritStonesCost(int32_t rarity, int32_t toLevel) {
    int64_t sum = 0;
    const int32_t cap = std::min(toLevel, kMaxLevel);
    for (int32_t level = kMinLevel; level < cap; ++level) {
        sum += spiritStonesCost(level, rarity);
    }
    return sum;
}

/// 从 Lv1 升到 toLevel 的累计兽材消耗（件）
inline int32_t totalBeastMaterialCost(int32_t toLevel) {
    int32_t sum = 0;
    const int32_t cap = std::min(toLevel, kMaxLevel);
    for (int32_t level = kMinLevel; level < cap; ++level) {
        sum += beastMaterialCost(level);
    }
    return sum;
}

/// 分解返还（累计消耗 × 50%，向下取整）：灵石 + 兽材件数
inline std::pair<int64_t, int32_t> dismantleRefund(int32_t rarity,
                                                   int32_t level) {
    const int64_t stones = static_cast<int64_t>(
        static_cast<double>(totalSpiritStonesCost(rarity, level)) *
        kDismantleRefundRatio);
    const int32_t beasts = static_cast<int32_t>(
        static_cast<double>(totalBeastMaterialCost(level)) *
        kDismantleRefundRatio);
    return {stones, beasts};
}

/// 经验累计推进（Kotlin EquipmentLevelSystem.advance 逐位移植）：
/// 按节点顺序逐级判定；满级后 exp 恒 0、溢出不保留
inline std::pair<int32_t, int32_t> levelAdvance(int32_t level, int32_t exp,
                                                int32_t expGain, int32_t rarity) {
    int32_t lv = std::min(std::max(level, kMinLevel), kMaxLevel);
    int32_t cur = isMaxLevel(lv)
        ? 0
        : std::min(std::max(exp, 0), expRequired(lv, rarity) - 1);
    int32_t remain = isMaxLevel(lv) ? 0 : expGain;
    while (remain > 0 && !isMaxLevel(lv)) {
        const int32_t need = expRequired(lv, rarity);
        if (cur + remain >= need) {
            remain -= need - cur;
            lv += 1;
            cur = 0;
            if (isMaxLevel(lv)) return {lv, 0};
        } else {
            cur += remain;
            remain = 0;
        }
    }
    return {lv, cur};
}

/// 新等级是否触发副词条强化节点（升级动作完成时判定 newLevel % 3 == 0）
inline bool triggersReinforcement(int32_t newLevel) {
    return newLevel >= kMinLevel && newLevel <= kMaxLevel &&
           newLevel % kReinforceInterval == 0;
}

// ── 事务信封（对齐 DiscipleTxResult 风格；errorType 与 Kotlin
//    AppError.Domain.Disciple 分型同名：NotFound / SlotInvalid） ────────

struct EquipmentTxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
    int32_t newLevel = 0;      // 升级成功后的等级（信封附带回传）
    int64_t stonesDelta = 0;   // 灵石变动量（升级为负/分解为正；日志面）
    int32_t beastsDelta = 0;   // 兽材变动量（同上）
};

namespace detail {

inline EquipmentInstance* findInstance(GameState& state,
                                       const std::string& equipmentId) {
    for (auto& e : state.equipmentInstances) {
        if (e.id == equipmentId) return &e;
    }
    return nullptr;
}

/// 兽材扣减计划（Kotlin planBeastMaterialDeduct 逐位移植）：全类目合并计数，
/// 按 (rarity 升序, id 升序) 逐堆叠扣；不足返 false（不落任何改动）
inline bool planBeastMaterialDeduct(GameState& state, int32_t need,
                                    std::vector<std::pair<std::string, int32_t>>& plan) {
    std::vector<gamecore::state::Material*> stacks;
    stacks.reserve(state.materials.size());
    for (auto& m : state.materials) stacks.push_back(&m);
    std::sort(stacks.begin(), stacks.end(),
              [](const gamecore::state::Material* a,
                 const gamecore::state::Material* b) {
                  if (a->rarity != b->rarity) return a->rarity < b->rarity;
                  return a->id < b->id;
              });
    plan.clear();
    int32_t remain = need;
    for (gamecore::state::Material* stack : stacks) {
        if (remain <= 0) break;
        const int32_t take = std::min(stack->quantity, remain);
        plan.emplace_back(stack->id, take);
        remain -= take;
    }
    return remain <= 0;
}

inline void applyBeastMaterialDeduct(GameState& state,
                                     const std::vector<std::pair<std::string, int32_t>>& plan) {
    for (const auto& [id, take] : plan) {
        for (auto it = state.materials.begin(); it != state.materials.end(); ++it) {
            if (it->id != id) continue;
            const int32_t newQty = it->quantity - take;
            if (newQty <= 0) {
                state.materials.erase(it);
            } else {
                it->quantity = newQty;
            }
            break;
        }
    }
}

/// 兽材返还：按最低稀有度兽材堆叠铸入（rarity==1 首条 +count；无则新建
/// "凡兽材" 条目——id 为镜像生成字段，确定性自增占位与 Kotlin UUID 对拍面互忽略）
inline void grantBeastMaterials(GameState& state, int32_t count) {
    if (count <= 0) return;
    for (auto& m : state.materials) {
        if (m.rarity == 1) {
            m.quantity += count;
            return;
        }
    }
    gamecore::state::Material material;
    material.id = gamecore::system::nextInstanceId();
    material.name = "凡兽材";
    material.rarity = 1;
    material.category = "BEAST_HIDE";
    material.quantity = count;
    material.description = "装备分解返还的兽材";
    state.materials.push_back(std::move(material));
}

/// 清除全部弟子储物袋内该装备条目（分解防复活；Kotlin stripBagEntry）
inline void stripBagEntry(GameState& state, const std::string& equipmentId) {
    DiscipleStore& ds = state.disciples;
    for (std::size_t row = 0; row < ds.ids.size(); ++row) {
        auto& bag = ds.storageBagItems[row];
        if (std::none_of(bag.begin(), bag.end(),
                [&](const gamecore::state::StorageBagItem& x) {
                    return x.itemId == equipmentId;
                })) {
            continue;
        }
        bag.erase(std::remove_if(bag.begin(), bag.end(),
                      [&](const gamecore::state::StorageBagItem& x) {
                          return x.itemId == equipmentId;
                      }),
            bag.end());
    }
}

}  // namespace detail

/// 升级一件装备 1 级（材料校验 → 扣材料 → 推进 → 强化节点）。
/// @param equipRng RngPartition::kEquipment 分区流（强化节点抽取）
inline EquipmentTxResult upgradeEquipmentTx(GameState& state,
                                            rng::DeterministicRng& equipRng,
                                            const std::string& equipmentId) {
    EquipmentTxResult out;
    EquipmentInstance* inst = detail::findInstance(state, equipmentId);
    if (inst == nullptr) {
        out.errorType = "NotFound";
        out.message = "弟子或装备不存在 " + equipmentId;
        return out;
    }
    if (isMaxLevel(inst->level())) {
        out.errorType = "SlotInvalid";
        out.message = "装备已满级";
        return out;
    }

    const int64_t stoneCost = spiritStonesCost(inst->level(), inst->rarity());
    const int32_t materialCost = beastMaterialCost(inst->level());

    // 材料校验（先验后扣，失败不扣材料）
    if (state.gameData.spiritStones < stoneCost) {
        out.errorType = "SlotInvalid";
        out.message = "灵石不足（需 " + std::to_string(stoneCost) + "）";
        return out;
    }
    std::vector<std::pair<std::string, int32_t>> plan;
    if (!detail::planBeastMaterialDeduct(state, materialCost, plan)) {
        out.errorType = "SlotInvalid";
        out.message = "兽材不足（需 " + std::to_string(materialCost) + " 件）";
        return out;
    }

    // 扣材料
    state.gameData.spiritStones -= stoneCost;
    detail::applyBeastMaterialDeduct(state, plan);

    // 经验推进：升级材料消耗直接折算为该级经验（满足即升级，节点逐级判定）
    const int32_t expGain = expRequired(inst->level(), inst->rarity());
    const auto [newLevel, newExp] =
        levelAdvance(inst->level(), inst->growth.exp, expGain, inst->rarity());

    // 强化节点：升级动作完成时判定（Lv3/6/…/30 共 10 次；满级后不再触发）
    if (triggersReinforcement(newLevel) && !inst->growth.affix.subStats.empty()) {
        auto& rolls = inst->growth.affix.subRolls;
        const int32_t index = equipRng.nextInt(
            static_cast<int32_t>(inst->growth.affix.subStats.size()));
        while (static_cast<int32_t>(rolls.size()) <= index) rolls.push_back(1);
        rolls[static_cast<std::size_t>(index)] =
            std::min(rolls[static_cast<std::size_t>(index)] + 1, kMaxSubRolls);
    }

    inst->growth.level = newLevel;
    inst->growth.exp = newExp;
    out.ok = true;
    out.newLevel = newLevel;
    out.stonesDelta = -stoneCost;
    out.beastsDelta = -materialCost;
    return out;
}

/// 分解一件装备（返还 50% 累计消耗；锁/已穿戴拒绝）
inline EquipmentTxResult dismantleEquipmentTx(GameState& state,
                                              const std::string& equipmentId) {
    EquipmentTxResult out;
    EquipmentInstance* inst = detail::findInstance(state, equipmentId);
    if (inst == nullptr) {
        out.errorType = "NotFound";
        out.message = "弟子或装备不存在 " + equipmentId;
        return out;
    }
    if (inst->meta.isLocked) {
        out.errorType = "SlotInvalid";
        out.message = "已锁定的装备不可分解";
        return out;
    }
    if (inst->isEquipped) {
        out.errorType = "SlotInvalid";
        out.message = "已穿戴的装备须先卸下再分解";
        return out;
    }

    const auto [stones, beasts] = dismantleRefund(inst->rarity(), inst->level());
    state.gameData.spiritStones += stones;
    detail::grantBeastMaterials(state, beasts);
    // 弟子储物袋内的同件条目一并清除（防分解后取回复活）
    detail::stripBagEntry(state, equipmentId);
    auto& instances = state.equipmentInstances;
    instances.erase(std::remove_if(instances.begin(), instances.end(),
                        [&](const EquipmentInstance& x) {
                            return x.id == equipmentId;
                        }),
        instances.end());
    out.ok = true;
    out.stonesDelta = stones;
    out.beastsDelta = beasts;
    return out;
}

}  // namespace gamecore::system::equipment_tx
