#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/pill_system.h"   // decreaseItemQuantity（袋扣减复用）
#include "gamecore/system/settlement_detail.h"

// ============================================================
// 师徒智能赠送（突破后触发，社交系统）
//
// 等价移植 Kotlin RelativeGiftHandler（突破后师徒赠送，社交系统）：
//   - 师徒查找：正向（师父）+ 反向一次遍历（徒弟，行序），
//     插序 = RNG 抽取序（LinkedHashSet 语义）
//   - 关系分类：师父 > 徒弟（优先级）
//   - 赠送概率：师父 0.40 / 徒弟 0.30（与 Kotlin companion 常量同源）
//   - 物品选取：装备空槽（槽位+境界匹配，品阶最高）> 功法空槽（境界+
//     未学会，品阶最高）> 突破丹（目标境界匹配，加成最高）> 其他丹药
//     （品阶最高）> 材料/草药/种子（品阶最高）；maxByOrNull 首个最大值
//   - 转移：赠送者袋 decreaseItemQuantity(-1) → 接收者袋
//     increaseItemQuantity(+1，同 itemId 合并数量/payload 升级)
//
// RNG 契约：每师徒恰好一次 SYSTEM nextDouble（概率判定，先于选品），
// 外层序 = 突破候选 ids 序、内层序 = 师徒插序——与 Kotlin 逐位一致。
//
// 范围边界：赠送成功后的 lifeEvents 记录（"X岁：从Y处获得Z"）为 Kotlin
// 运行态字段（@Ignore 不序列化、不在快照协议），C++ 显式丢弃——与
// overflowMail（月结灵田溢出邮件）同一边界口径。
//
// 空串/null 口径：C++ 关系列（masterId）空串 == Kotlin null
//（DiscipleSerializer ifEmpty{null} 归一化，镜像往返后 ComponentTable 存
// null）——`!empty() && ==` 守卫与 Kotlin `!= null && ==` 逐位等价。
// ============================================================
namespace gamecore::system::relative_gift {

/// 赠送概率（Kotlin RelativeGiftHandler companion，与 GameConfigData
/// RelativeGiftSection 默认值同步——Kotlin 侧亦为编译期常量）
constexpr double kMasterGiftProb = 0.40;
constexpr double kApprenticeGiftProb = 0.30;

/// 赠送者储物袋最少保留物品数（防清空）
constexpr std::size_t kMinBagItemsToKeep = 1;

/// 默认功法槽上限（Kotlin DEFAULT_MAX_MANUAL_SLOTS；天赋加槽极少见，
/// 低估仅导致功法不赠送，接收者自动学习兜底）
constexpr std::size_t kDefaultMaxManualSlots = 6;

/// 关系类型（Kotlin GiftRelationshipType，分类优先级即枚举序）
enum class GiftRelationshipType {
    kMaster,
    kApprentice,
};

inline double giftProbability(GiftRelationshipType type) {
    switch (type) {
        case GiftRelationshipType::kMaster: return kMasterGiftProb;
        case GiftRelationshipType::kApprentice: return kApprenticeGiftProb;
    }
    return kApprenticeGiftProb;
}

/// 赠送结果（Kotlin GiftResult；Success 携带的 itemId/name 仅用于
/// lifeEvents 日志——C++ 无该协议字段，成功与否由调用方忽略详情）
enum class GiftResult { kSuccess, kBagTooSmall, kBagEmpty, kNoSuitableItem };

namespace detail {

using gamecore::state::DiscipleColumn;
using gamecore::state::DiscipleStore;
using gamecore::state::EquipmentStack;
using gamecore::state::GameState;
using gamecore::state::ManualInstance;
using gamecore::state::ManualStack;
using gamecore::state::StorageBagItem;

/// id → 行（Kotlin ComponentTable.getOrNull 口径：缺席 = 空串/null 列）
inline std::map<int32_t, std::size_t> rowIndex(const state::DiscipleStore& ds) {
    std::map<int32_t, std::size_t> m;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        const auto id = settle_util::toIntOrNull(ds.ids[row]);
        if (id.has_value()) m.emplace(*id, row);
    }
    return m;
}

/// 存活且非本人（Kotlin isAlive(id, selfId)）
inline bool isAliveOther(const state::DiscipleStore& ds, std::size_t row,
                         int32_t selfId) {
    const auto id = settle_util::toIntOrNull(ds.ids[row]);
    return id.has_value() && *id != selfId && ds.isAlive[row] == 1;
}

/// 储物袋追加（StorageBagUtils.increaseItemQuantity：同 itemId 合并数量，
/// 新 payload 非空时升级旧条目）
inline std::vector<state::StorageBagItem> increaseItemQuantity(
        std::vector<state::StorageBagItem> items, const state::StorageBagItem& item) {
    const auto it = std::find_if(items.begin(), items.end(),
        [&](const state::StorageBagItem& i) { return i.itemId == item.itemId; });
    if (it == items.end()) {
        items.push_back(item);
        return items;
    }
    it->quantity += item.quantity;
    if (item.equipmentInstance.has_value()) {
        it->equipmentInstance = item.equipmentInstance;
    }
    if (item.stackedData.has_value()) {
        it->stackedData = item.stackedData;
    }
    if (item.manualInstance.has_value()) {
        it->manualInstance = item.manualInstance;
    }
    return items;
}

/// 接收者空闲装备槽（Kotlin getEmptyEquipmentSlots；isNullOrEmpty == empty）
inline std::set<std::string> emptyEquipmentSlots(const state::DiscipleStore& ds,
                                                 std::size_t row) {
    std::set<std::string> slots;
    if (ds.weaponIds[row].empty()) slots.insert("WEAPON");
    if (ds.armorIds[row].empty()) slots.insert("ARMOR");
    if (ds.bootsIds[row].empty()) slots.insert("BOOTS");
    if (ds.accessoryIds[row].empty()) slots.insert("ACCESSORY");
    return slots;
}

/// 接收者功法槽是否有空位（manualIds.size < 6）
inline bool manualSlotAvailable(const state::DiscipleStore& ds, std::size_t row) {
    return ds.manualIds[row].size() < kDefaultMaxManualSlots;
}

/// 接收者已学功法名集合（state.manualInstances 按 id 查名，缺失跳过）
inline std::set<std::string> learnedManualNames(
        const state::GameState& state, const std::vector<std::string>& manualIds) {
    std::set<std::string> names;
    for (const auto& mid : manualIds) {
        for (const auto& inst : state.manualInstances) {
            if (inst.id == mid) {
                names.insert(inst.name);
                break;
            }
        }
    }
    return names;
}

}  // namespace detail

/// 师徒查找（Kotlin findRelatives）：正向（师父，O(1) 查表）+ 反向一次
/// 遍历（徒弟，行序）；返回插序 = 抽取序。
/// @param idx id→行索引（调用方构建一次，供主入口各段共用）
inline std::vector<int32_t> findRelatives(
        const state::DiscipleStore& ds, int32_t discipleId,
        const std::map<int32_t, std::size_t>& idx) {
    std::vector<int32_t> seen;
    const auto selfIt = idx.find(discipleId);
    if (selfIt == idx.end()) return seen;
    const std::size_t selfRow = selfIt->second;
    const std::string myIdStr = ds.ids[selfRow];

    const auto tryAdd = [&](const std::string& idStr) {
        if (idStr.empty()) return;
        const auto pid = settle_util::toIntOrNull(idStr);
        if (!pid.has_value()) return;
        const auto it = idx.find(*pid);
        if (it == idx.end()) return;
        if (detail::isAliveOther(ds, it->second, discipleId) &&
            std::find(seen.begin(), seen.end(), *pid) == seen.end()) {
            seen.push_back(*pid);
        }
    };

    // 正向：师父
    tryAdd(ds.masterIds[selfRow]);

    // 反向：一次遍历收徒弟（行序 == 抽取序；本人/已亡/已收录跳过）
    for (std::size_t row = 0; row < ds.size(); ++row) {
        const auto id = settle_util::toIntOrNull(ds.ids[row]);
        if (!id.has_value() || *id == discipleId) continue;
        if (ds.isAlive[row] == 0) continue;
        if (std::find(seen.begin(), seen.end(), *id) != seen.end()) continue;
        if (ds.masterIds[row] == myIdStr) seen.push_back(*id);
    }
    return seen;
}

/// 关系分类（Kotlin classifyRelationship）：师父 > 徒弟（首次命中返回；
/// String? == 判等 → C++ 空串恒不等）。查找集只含师徒两类，
/// 未命中师父即按徒弟计。
inline GiftRelationshipType classifyRelationship(
        const state::DiscipleStore& ds, int32_t giverId, int32_t receiverId,
        const std::map<int32_t, std::size_t>& idx) {
    const auto gIt = idx.find(giverId);
    const auto rIt = idx.find(receiverId);
    const std::string gs = std::to_string(giverId);
    const std::string rs = std::to_string(receiverId);
    const std::string empty;
    const std::string& recvMaster = rIt != idx.end() ? ds.masterIds[rIt->second] : empty;
    const std::string& giverMaster = gIt != idx.end() ? ds.masterIds[gIt->second] : empty;

    if (recvMaster == gs) return GiftRelationshipType::kMaster;
    if (giverMaster == rs) return GiftRelationshipType::kApprentice;
    return GiftRelationshipType::kApprentice;
}

/// 按优先级从赠送者储物袋选最佳物品（Kotlin selectBestGift；maxByOrNull
/// 首个最大值语义 = 严格大于才替换）。
inline std::optional<state::StorageBagItem> selectBestGift(
        const std::vector<state::StorageBagItem>& bagItems,
        const state::DiscipleStore& ds, std::size_t receiverRow,
        int32_t receiverRealm, const state::GameState& state) {
    if (bagItems.empty()) return std::nullopt;

    // 堆叠表按 id 索引（仅查寻用途，不影响遍历序）
    std::map<std::string, const state::EquipmentStack*> eqStacks;
    for (const auto& s : state.equipmentStacks) {
        eqStacks.emplace(s.id, &s);
    }
    std::map<std::string, const state::ManualStack*> mnStacks;
    for (const auto& s : state.manualStacks) {
        mnStacks.emplace(s.id, &s);
    }

    // 1. 装备优先（接收者有空槽：槽位匹配 + receiverRealm <= minRealm）
    const auto emptySlots = detail::emptyEquipmentSlots(ds, receiverRow);
    if (!emptySlots.empty()) {
        const state::StorageBagItem* best = nullptr;
        int32_t bestRarity = -1;
        for (const auto& item : bagItems) {
            if (item.itemType != "equipment_stack") continue;
            const auto it = eqStacks.find(item.itemId);
            if (it == eqStacks.end()) continue;
            const auto& stack = *it->second;
            if (emptySlots.count(stack.slot) == 0) continue;
            if (receiverRealm > stack.minRealm) continue;
            if (stack.rarity > bestRarity) {
                bestRarity = stack.rarity;
                best = &item;
            }
        }
        if (best != nullptr) return *best;
    }

    // 2. 功法次优（接收者功法槽未满：境界达标 + 未学会同名功法）
    if (detail::manualSlotAvailable(ds, receiverRow)) {
        const auto learned = detail::learnedManualNames(
            state, ds.manualIds[receiverRow]);
        const state::StorageBagItem* best = nullptr;
        int32_t bestRarity = -1;
        for (const auto& item : bagItems) {
            if (item.itemType != "manual_stack") continue;
            const auto it = mnStacks.find(item.itemId);
            if (it == mnStacks.end()) continue;
            const auto& stack = *it->second;
            if (receiverRealm > stack.minRealm) continue;
            if (learned.count(stack.name) > 0) continue;
            if (stack.rarity > bestRarity) {
                bestRarity = stack.rarity;
                best = &item;
            }
        }
        if (best != nullptr) return *best;
    }

    // 3. 突破丹药（目标境界 == 接收者境界，加成最高）
    {
        const state::StorageBagItem* best = nullptr;
        double bestChance = -1.0;
        for (const auto& item : bagItems) {
            if (item.itemType != "pill") continue;
            if (!item.effect.has_value()) continue;
            if (item.effect->pillType != "breakthrough") continue;
            if (item.effect->targetRealm != receiverRealm) continue;
            if (item.effect->breakthroughChance > bestChance) {
                bestChance = item.effect->breakthroughChance;
                best = &item;
            }
        }
        if (best != nullptr) return *best;
    }

    // 4. 其他丹药（品阶最高）
    {
        const state::StorageBagItem* best = nullptr;
        int32_t bestRarity = -1;
        for (const auto& item : bagItems) {
            if (item.itemType != "pill") continue;
            if (item.rarity > bestRarity) {
                bestRarity = item.rarity;
                best = &item;
            }
        }
        if (best != nullptr) return *best;
    }

    // 5. 材料/草药/种子（品阶最高）
    {
        const state::StorageBagItem* best = nullptr;
        int32_t bestRarity = -1;
        for (const auto& item : bagItems) {
            if (item.itemType != "material" && item.itemType != "herb" &&
                item.itemType != "seed") {
                continue;
            }
            if (item.rarity > bestRarity) {
                bestRarity = item.rarity;
                best = &item;
            }
        }
        if (best != nullptr) return *best;
    }
    return std::nullopt;
}

/// 尝试赠送（Kotlin tryGiveGift）：袋空/仅保留数/无合适物品 → 失败；
/// 成功 = 赠送者袋 -1 → 接收者袋 +1（原地写回 DiscipleStore 列）。
inline GiftResult tryGiveGift(state::GameState& state,
                              const std::map<int32_t, std::size_t>& idx,
                              int32_t giverId, int32_t receiverId,
                              int32_t receiverRealm) {
    state::DiscipleStore& ds = state.disciples;
    const auto giverIt = idx.find(giverId);
    if (giverIt == idx.end()) return GiftResult::kBagEmpty;   // 袋列缺失
    const std::size_t giverRow = giverIt->second;
    const std::vector<state::StorageBagItem> giverBag = ds.storageBagItems[giverRow];
    if (giverBag.empty()) return GiftResult::kBagEmpty;
    if (giverBag.size() <= kMinBagItemsToKeep) return GiftResult::kBagTooSmall;

    const auto receiverIt = idx.find(receiverId);
    if (receiverIt == idx.end()) return GiftResult::kNoSuitableItem;
    const std::size_t receiverRow = receiverIt->second;
    const auto selected = selectBestGift(giverBag, ds, receiverRow,
                                         receiverRealm, state);
    if (!selected.has_value()) return GiftResult::kNoSuitableItem;

    // 从赠送者储物袋移除（quantity-1，=0 整条移除）
    ds.storageBagItems[giverRow] = pill::decreaseItemQuantity(
        giverBag, selected->itemId, 1);
    ds.markCol(gamecore::state::DiscipleColumn::StorageBagItems, giverRow);  // R2 列级写屏障
    // 添加到接收者储物袋（quantity=1 合并追加）
     state::StorageBagItem gift = *selected;
    gift.quantity = 1;
    ds.storageBagItems[receiverRow] = detail::increaseItemQuantity(
        ds.storageBagItems[receiverRow], gift);
    ds.markCol(gamecore::state::DiscipleColumn::StorageBagItems, receiverRow);  // R2 列级写屏障
    return GiftResult::kSuccess;
}

/// 突破后师徒赠送主入口（Kotlin processGiftsForBreakthrough）。
/// @param discipleId 突破弟子 id（tables 已写入突破后状态）
/// @param rngSystem  SYSTEM 分区 RNG（每师徒恰好一次 nextDouble，
///                   先于选品，序 = findRelatives 插序）
inline void processGiftsForBreakthrough(state::GameState& state,
                                        int32_t discipleId,
                                        rng::DeterministicRng& rngSystem) {
    state::DiscipleStore& ds = state.disciples;
    const auto idx = detail::rowIndex(ds);
    const std::vector<int32_t> mastersAndApprentices =
        findRelatives(ds, discipleId, idx);
    if (mastersAndApprentices.empty()) return;

    const auto selfIt = idx.find(discipleId);
    if (selfIt == idx.end()) return;
    const int32_t receiverRealm = ds.realms[selfIt->second];

    for (const int32_t giverId : mastersAndApprentices) {
        const auto relationship =
            classifyRelationship(ds, giverId, discipleId, idx);
        const double probability = giftProbability(relationship);
        if (rngSystem.nextDouble() >= probability) continue;
        // 成功后的 lifeEvents 日志（"X岁：从Y处获得Z"）为 Kotlin 运行态
        // 字段，不在快照协议——C++ 显式丢弃（见文件头范围边界）
        (void)tryGiveGift(state, idx, giverId, discipleId, receiverRealm);
    }
}

}  // namespace gamecore::system::relative_gift
