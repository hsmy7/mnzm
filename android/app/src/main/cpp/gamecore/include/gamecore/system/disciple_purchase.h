#pragma once

// ============================================================
// 弟子智能购买月结下沉（S8 子事件 12：DisciplePurchaseService）
//
// Kotlin DisciplePurchaseService.executePurchase(year, month, state)
// 等价移植（批 12-1）。全链零战斗依赖（纯数据变换 + SYSTEM 分区
// shuffled 洗牌）。
//
// 语义要点（逐条对齐 Kotlin 源码）：
//   - 收集存活 + 有资金的弟子上下文（realm/四槽装备 id/manualIds/
//     totalFunds = 储物袋灵石 + 随身灵石）
//   - 决策优先级：功法 → 装备 → 丹药；每弟子每月类别上限
//     （功法 2 / 装备 2 / 丹药 10）；A 组（空槽位优先）B 组（升级）
//     shuffled(purchaseRng)（SYSTEM 分区 Fisher-Yates）
//   - 仓库门控：hasWarehouseStock（未锁定 + name/rarity/quantity>=1；
//     丹药按 grade.displayName 匹配 item.grade ?: "中品"）
//   - 扣减：deductWarehouseStack（exact itemId 优先 → 回退
//     name+rarity 未锁定首堆叠），入弟子储物袋（StorageBagItem +
//     stackedData / pill 内嵌 effect/grade）
//   - 灵石：先扣储物袋再扣随身；宗门灵石 += price
//   - 购买日志（lifeEvents）为 Kotlin Disciple 类体属性（@Ignore
//     非序列化、不进快照协议）——C++ 侧无该列，登记 S 系列（S-20）
//
// RNG 契约：仅消费 SYSTEM 分区（shuffled Fisher-Yates——Kotlin
// shuffled(Random) 从后往前 nextInt(i+1)）；空列表/单元素不消费。
// 对拍命门：购买子事件位于子事件 12 位（灵矿后、附庸前），其
// SYSTEM 抽取必须与 Kotlin 月变编排的相对序完全一致。
// ============================================================

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/merchant_settlement.h"

namespace gamecore::system::disciple_purchase {

using gamecore::state::BagStackedData;
using gamecore::state::EquipmentStack;
using gamecore::state::GameState;
using gamecore::state::ItemEffect;
using gamecore::state::ManualStack;
using gamecore::state::MerchantItem;
using gamecore::state::Pill;
using gamecore::state::PillEffect;
using gamecore::state::StorageBagItem;

/// 每弟子每月功法购买上限（DisciplePurchaseService.MAX_MANUAL_PURCHASES）
constexpr int32_t kMaxManualPurchases = 2;
/// 每弟子每月装备购买上限（DisciplePurchaseService.MAX_EQUIPMENT_PURCHASES）
constexpr int32_t kMaxEquipmentPurchases = 2;
/// 每弟子每月丹药购买上限（DisciplePurchaseService.MAX_PILL_PURCHASES）
constexpr int32_t kMaxPillPurchases = 10;
/// 灵石最低保留比例（DisciplePurchaseService.SAVING_RATIO）
constexpr double kSavingRatio = 0.3;
/// 功法槽位估算上限（DisciplePurchaseService.ESTIMATED_MAX_MANUAL_SLOTS）
constexpr int32_t kEstimatedMaxManualSlots = 4;

/// 物品类型常量（Kotlin DiscipleConstants.ITEM_TYPE_*）
inline constexpr const char* kItemTypeManual = "manual";
inline constexpr const char* kItemTypeEquipment = "equipment";
inline constexpr const char* kItemTypePill = "pill";
inline constexpr const char* kItemTypeEquipmentStack = "equipment_stack";
inline constexpr const char* kItemTypeManualStack = "manual_stack";

/// 小写化（Kotlin String.lowercase(Locale.ROOT)；类型分发对齐）
inline std::string toLowerAscii(const std::string& s) {
    std::string out = s;
    std::transform(out.begin(), out.end(), out.begin(),
                   [](unsigned char c) {
                       return static_cast<char>(std::tolower(c));
                   });
    return out;
}

/// 弟子购买上下文（Kotlin DisciplePurchaseContext；id 用 DiscipleStore 行索引）
struct DisciplePurchaseContext {
    std::size_t row = 0;
    int32_t realm = 9;
    std::string weaponId;
    std::string armorId;
    std::string bootsId;
    std::string accessoryId;
    std::vector<std::string> manualIds;
    int64_t totalFunds = 0;
};

/// 购买决策（Kotlin PurchaseEntry）
struct PurchaseEntry {
    std::size_t discipleRow = 0;
    const MerchantItem* item = nullptr;
    std::string itemType;
};

// ── 境界判定（GameConfig.Realm 等价） ──────────────────────────────

/// 品阶最低境界（GameConfig.Realm.getMinRealmForRarity；merchant_settlement 同源）
inline int32_t minRealmForRarity(int32_t rarity) {
    return merchant_settle::minRealmForRarity(rarity);
}

/// 弟子是否满足境界要求（GameConfig.Realm.meetsRealmRequirement：
/// discipleRealm <= minRealm）
inline bool meetsRealmRequirement(int32_t discipleRealm, int32_t minRealm) {
    return discipleRealm <= minRealm;
}

/// canUseItem：通过品阶推算 minRealm 而非依赖物品自身字段
inline bool canUseItem(int32_t discipleRealm, int32_t itemRarity) {
    return meetsRealmRequirement(discipleRealm, minRealmForRarity(itemRarity));
}

// ── 预算/需求判定（DisciplePurchaseService companion 纯函数） ──────

/// 可用采购预算（calculateBudget：保留 max(30% 基础储备, 最高价 20% 目标储备)）
inline int64_t calculateBudget(int64_t totalFunds, int64_t highestNeededPrice) {
    if (totalFunds <= 0) return 0;
    const int64_t baseReserve =
        static_cast<int64_t>(static_cast<double>(totalFunds) * kSavingRatio);
    const int64_t targetReserve = (highestNeededPrice > 0)
        ? static_cast<int64_t>(static_cast<double>(highestNeededPrice) * 0.2)
        : 0;
    const int64_t reserve = std::max(baseReserve, targetReserve);
    const int64_t budget = totalFunds - reserve;
    return budget > 0 ? budget : 0;
}

/// 可购买物品中的最高价格（calculateHighestNeededPrice：只算境界允许的）
inline int64_t calculateHighestNeededPrice(
    const std::vector<const MerchantItem*>& items, int32_t discipleRealm) {
    int64_t maxPrice = 0;
    for (const auto* item : items) {
        if (item == nullptr) continue;
        if (canUseItem(discipleRealm, item->rarity) && item->price > maxPrice) {
            maxPrice = item->price;
        }
    }
    return maxPrice;
}

/// 是否需要某本功法（needsManual：未学过 或 候选品阶更高可替换）
inline bool needsManual(const std::vector<std::string>& learnedNames,
                        const std::string& manualName, int32_t candidateRarity,
                        int32_t bestLearnedRarity) {
    if (manualName.empty()) return false;
    const bool learned = std::find(learnedNames.begin(), learnedNames.end(),
                                   manualName) != learnedNames.end();
    if (!learned) return true;
    return candidateRarity > bestLearnedRarity;
}

/// 是否需要某个装备槽位的装备（needsEquipmentSlot：槽位空 或 品阶更高）
inline bool needsEquipmentSlot(const std::string& currentEquipId,
                               int32_t candidateRarity, int32_t currentRarity) {
    if (currentEquipId.empty()) return true;
    return candidateRarity > currentRarity;
}

// ── 收集弟子（collectDisciples：存活 + 有资金） ────────────────────

inline std::vector<DisciplePurchaseContext> collectDisciples(
    const gamecore::state::DiscipleStore& ds) {
    std::vector<DisciplePurchaseContext> out;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] == 0) continue;
        const int64_t totalFunds = ds.storageBagSpiritStones[row] +
                                   static_cast<int64_t>(ds.spiritStones[row]);
        if (totalFunds <= 0) continue;
        DisciplePurchaseContext ctx;
        ctx.row = row;
        ctx.realm = ds.realms[row];
        ctx.weaponId = ds.weaponIds[row];
        ctx.armorId = ds.armorIds[row];
        ctx.bootsId = ds.bootsIds[row];
        ctx.accessoryId = ds.accessoryIds[row];
        ctx.manualIds = ds.manualIds[row];
        ctx.totalFunds = totalFunds;
        out.push_back(std::move(ctx));
    }
    return out;
}

// ── 决策计数（countPurchases） ─────────────────────────────────────

inline int32_t countPurchases(const std::vector<PurchaseEntry>& decisions,
                              std::size_t discipleRow,
                              const std::string& itemType) {
    int32_t count = 0;
    for (const auto& d : decisions) {
        if (d.discipleRow == discipleRow && d.itemType == itemType) ++count;
    }
    return count;
}

// ── shuffled（Kotlin Iterable.shuffled(rng: DeterministicRng)：C-11 登记） ─

/// 洗牌 = Kotlin `map { it to rng.nextInt() }.sortedBy { it.second }`——
/// **每元素恰 1 次无参 nextInt() 随机键 + 稳定排序**（非 Fisher-Yates！）。
/// 空列表零消费；单元素也消费 1 次（Kotlin map 急切求值）。
template <typename T>
inline void shuffled(std::vector<T>& items, gamecore::rng::DeterministicRng& rng) {
    std::vector<std::pair<int32_t, std::size_t>> keys;
    keys.reserve(items.size());
    for (std::size_t i = 0; i < items.size(); ++i) {
        keys.emplace_back(rng.nextInt(), i);
    }
    std::stable_sort(keys.begin(), keys.end(),
                     [](const auto& a, const auto& b) { return a.first < b.first; });
    std::vector<T> out;
    out.reserve(items.size());
    for (const auto& [key, idx] : keys) {
        out.push_back(std::move(items[idx]));
    }
    items = std::move(out);
}

// ── 功法购买决策（processManualPurchases） ─────────────────────────

inline void processManualPurchases(
    const std::vector<MerchantItem>& listedItems,
    const std::vector<DisciplePurchaseContext>& allDisciples,
    const std::vector<gamecore::state::ManualInstance>& manualInstances,
    std::vector<PurchaseEntry>& decisions,
    gamecore::rng::DeterministicRng& rng) {
    for (const auto& item : listedItems) {
        if (item.type != kItemTypeManual) continue;

        std::vector<const DisciplePurchaseContext*> interested;
        for (const auto& ctx : allDisciples) {
            if (!canUseItem(ctx.realm, item.rarity)) continue;
            if (countPurchases(decisions, ctx.row, kItemTypeManual) >=
                kMaxManualPurchases) {
                continue;
            }
            const std::vector<const MerchantItem*> single{&item};
            const int64_t highestNeeded =
                calculateHighestNeededPrice(single, ctx.realm);
            const int64_t budget = calculateBudget(ctx.totalFunds, highestNeeded);
            if (budget < item.price) continue;

            std::vector<std::string> learnedNames;
            int32_t bestRarity = 0;
            for (const auto& mi : manualInstances) {
                const bool owned = std::find(ctx.manualIds.begin(),
                                             ctx.manualIds.end(),
                                             mi.id) != ctx.manualIds.end();
                if (!owned) continue;
                learnedNames.push_back(mi.name);
                if (mi.name == item.name && mi.rarity > bestRarity) {
                    bestRarity = mi.rarity;
                }
            }
            if (needsManual(learnedNames, item.name, item.rarity, bestRarity)) {
                interested.push_back(&ctx);
            }
        }
        if (interested.empty()) continue;

        // A 组空槽位优先，B 组满槽升级（各 shuffled 一次）
        std::vector<const DisciplePurchaseContext*> groupA;
        std::vector<const DisciplePurchaseContext*> groupB;
        for (const auto* ctx : interested) {
            if (static_cast<int32_t>(ctx->manualIds.size()) <
                kEstimatedMaxManualSlots) {
                groupA.push_back(ctx);
            } else {
                groupB.push_back(ctx);
            }
        }
        shuffled(groupA, rng);
        shuffled(groupB, rng);

        // Kotlin：`for (ctx in (groupA + groupB))` 单循环——先 A 后 B，
        // 找到第一个满足类别上限的即 add + break（每 item 至多 1 个决策）
        std::vector<const DisciplePurchaseContext*> merged;
        merged.reserve(groupA.size() + groupB.size());
        merged.insert(merged.end(), groupA.begin(), groupA.end());
        merged.insert(merged.end(), groupB.begin(), groupB.end());
        for (const auto* ctx : merged) {
            if (countPurchases(decisions, ctx->row, kItemTypeManual) >=
                kMaxManualPurchases) {
                continue;
            }
            PurchaseEntry e;
            e.discipleRow = ctx->row;
            e.item = &item;
            e.itemType = kItemTypeManual;
            decisions.push_back(std::move(e));
            break;
        }
    }
}

// ── 装备购买决策（processEquipmentPurchases） ──────────────────────

inline std::string equipIdBySlot(const DisciplePurchaseContext& ctx,
                                 const std::string& slot) {
    if (slot == "WEAPON") return ctx.weaponId;
    if (slot == "ARMOR") return ctx.armorId;
    if (slot == "BOOTS") return ctx.bootsId;
    if (slot == "ACCESSORY") return ctx.accessoryId;
    return std::string();
}

inline void processEquipmentPurchases(
    const std::vector<MerchantItem>& listedItems,
    const std::vector<DisciplePurchaseContext>& allDisciples,
    const std::vector<gamecore::state::EquipmentInstance>& equipmentInstances,
    std::vector<PurchaseEntry>& decisions,
    gamecore::rng::DeterministicRng& rng) {
    for (const auto& item : listedItems) {
        if (item.type != kItemTypeEquipment) continue;
        const gamecore::state::EquipmentStack eq =
            merchant_settle::toEquipment(item);

        std::vector<const DisciplePurchaseContext*> interested;
        for (const auto& ctx : allDisciples) {
            if (!canUseItem(ctx.realm, item.rarity)) continue;
            if (countPurchases(decisions, ctx.row, kItemTypeEquipment) >=
                kMaxEquipmentPurchases) {
                continue;
            }
            const std::vector<const MerchantItem*> single{&item};
            const int64_t highestNeeded =
                calculateHighestNeededPrice(single, ctx.realm);
            const int64_t budget = calculateBudget(ctx.totalFunds, highestNeeded);
            if (budget < item.price) continue;

            const std::string currentEquipId = equipIdBySlot(ctx, eq.slot);
            int32_t currentRarity = 0;
            if (!currentEquipId.empty()) {
                for (const auto& ei : equipmentInstances) {
                    if (ei.id == currentEquipId) {
                        currentRarity = ei.rarity;
                        break;
                    }
                }
            }
            if (needsEquipmentSlot(currentEquipId, item.rarity, currentRarity)) {
                interested.push_back(&ctx);
            }
        }
        if (interested.empty()) continue;

        // A 组槽位为空优先，B 组升级
        std::vector<const DisciplePurchaseContext*> groupA;
        std::vector<const DisciplePurchaseContext*> groupB;
        for (const auto* ctx : interested) {
            if (equipIdBySlot(*ctx, eq.slot).empty()) {
                groupA.push_back(ctx);
            } else {
                groupB.push_back(ctx);
            }
        }
        shuffled(groupA, rng);
        shuffled(groupB, rng);

        // Kotlin：`for (ctx in (groupA + groupB))` 单循环——先 A 后 B，
        // 找到第一个满足类别上限的即 add + break（每 item 至多 1 个决策）
        std::vector<const DisciplePurchaseContext*> merged;
        merged.reserve(groupA.size() + groupB.size());
        merged.insert(merged.end(), groupA.begin(), groupA.end());
        merged.insert(merged.end(), groupB.begin(), groupB.end());
        for (const auto* ctx : merged) {
            if (countPurchases(decisions, ctx->row, kItemTypeEquipment) >=
                kMaxEquipmentPurchases) {
                continue;
            }
            PurchaseEntry e;
            e.discipleRow = ctx->row;
            e.item = &item;
            e.itemType = kItemTypeEquipment;
            decisions.push_back(std::move(e));
            break;
        }
    }
}

// ── 丹药购买决策（processPillPurchases：rarity 降序） ──────────────

inline void processPillPurchases(
    const std::vector<MerchantItem>& listedItems,
    const std::vector<DisciplePurchaseContext>& allDisciples,
    std::vector<PurchaseEntry>& decisions,
    gamecore::rng::DeterministicRng& rng) {
    std::vector<const MerchantItem*> pillItems;
    for (const auto& item : listedItems) {
        if (item.type == kItemTypePill) pillItems.push_back(&item);
    }
    std::stable_sort(pillItems.begin(), pillItems.end(),
        [](const MerchantItem* a, const MerchantItem* b) {
            return a->rarity > b->rarity;
        });

    for (const auto* item : pillItems) {
        std::vector<const DisciplePurchaseContext*> interested;
        for (const auto& ctx : allDisciples) {
            if (!canUseItem(ctx.realm, item->rarity)) continue;
            if (countPurchases(decisions, ctx.row, kItemTypePill) >=
                kMaxPillPurchases) {
                continue;
            }
            const int64_t budget = calculateBudget(ctx.totalFunds, 0);
            if (budget >= item->price) interested.push_back(&ctx);
        }
        shuffled(interested, rng);

        for (const auto* ctx : interested) {
            if (countPurchases(decisions, ctx->row, kItemTypePill) >=
                kMaxPillPurchases) {
                continue;
            }
            PurchaseEntry e;
            e.discipleRow = ctx->row;
            e.item = item;
            e.itemType = kItemTypePill;
            decisions.push_back(std::move(e));
            break;
        }
    }
}

// ── 决策构建（buildPurchaseDecisions：功法 → 装备 → 丹药） ────────

inline std::vector<PurchaseEntry> buildPurchaseDecisions(
    const std::vector<MerchantItem>& listedItems,
    const std::vector<DisciplePurchaseContext>& allDisciples,
    const std::vector<gamecore::state::EquipmentInstance>& equipmentInstances,
    const std::vector<gamecore::state::ManualInstance>& manualInstances,
    gamecore::rng::DeterministicRng& rng) {
    std::vector<PurchaseEntry> decisions;
    processManualPurchases(listedItems, allDisciples, manualInstances,
                           decisions, rng);
    processEquipmentPurchases(listedItems, allDisciples, equipmentInstances,
                              decisions, rng);
    processPillPurchases(listedItems, allDisciples, decisions, rng);
    return decisions;
}

// ── 仓库库存检查（hasWarehouseStock） ─────────────────────────────

/// 丹药品阶 name → displayName（Kotlin PillGrade.displayName）
inline std::string gradeDisplayName(const std::string& gradeName) {
    if (gradeName == "LOW") return "下品";
    if (gradeName == "HIGH") return "上品";
    return "中品";
}

inline bool hasWarehouseStock(const GameState& state, const MerchantItem& item) {
    // Kotlin hasWarehouseStock：item.type.lowercase(Locale.ROOT) 分发
    const std::string type = toLowerAscii(item.type);
    if (type == kItemTypeEquipment) {
        for (const auto& s : state.equipmentStacks) {
            if (!s.isLocked && s.name == item.name && s.rarity == item.rarity &&
                s.quantity >= 1) {
                return true;
            }
        }
    } else if (type == kItemTypeManual) {
        for (const auto& s : state.manualStacks) {
            if (!s.isLocked && s.name == item.name && s.rarity == item.rarity &&
                s.quantity >= 1) {
                return true;
            }
        }
    } else if (type == kItemTypePill) {
        // 丹药按 grade.displayName 匹配 item.grade ?: "中品"
        const std::string wantGrade = item.grade.has_value()
            ? *item.grade : std::string("中品");
        for (const auto& p : state.pills) {
            if (!p.isLocked && p.name == item.name && p.rarity == item.rarity &&
                gradeDisplayName(p.grade) == wantGrade && p.quantity >= 1) {
                return true;
            }
        }
    }
    return false;
}

// ── 仓库堆叠扣减（deductWarehouseStack / findDeductibleStack） ─────

/// 查找可扣减堆叠（Kotlin findDeductibleStack：exact itemId → 回退
/// 第一个未锁定 name+rarity 匹配）。返回迭代器或 end()。
template <typename T>
inline typename std::vector<T>::iterator findDeductibleStack(
    std::vector<T>& store, const std::string& itemId,
    const std::string& name, int32_t rarity) {
    auto it = std::find_if(store.begin(), store.end(),
        [&](const T& x) { return x.id == itemId; });
    if (it != store.end() && !it->isLocked && it->quantity >= 1) return it;
    return std::find_if(store.begin(), store.end(),
        [&](const T& x) {
            return !x.isLocked && x.name == name && x.rarity == rarity &&
                   x.quantity >= 1;
        });
}

/// 从仓库堆叠扣减 1 个（返回被扣减堆叠的拷贝；找不到返回 nullopt）
template <typename T>
inline std::optional<T> deductWarehouseStack(
    std::vector<T>& store, const std::string& itemId,
    const std::string& name, int32_t rarity) {
    auto it = findDeductibleStack(store, itemId, name, rarity);
    if (it == store.end()) return std::nullopt;
    T candidate = *it;
    const int32_t newQty = it->quantity - 1;
    if (newQty <= 0) {
        store.erase(it);
    } else {
        it->quantity = newQty;
    }
    return candidate;
}

// ── 入袋（appendPillBagItem） ─────────────────────────────────────

/// Pill → ItemEffect（Kotlin pillToItemEffect 逐字段映射）
inline ItemEffect pillToItemEffect(const Pill& pill) {
    const PillEffect& e = pill.effects;
    ItemEffect out;
    out.tier = pill.rarity;
    out.cultivationSpeedPercent = e.cultivationSpeedPercent;
    out.skillExpSpeedPercent = e.skillExpSpeedPercent;
    out.nurtureSpeedPercent = e.nurtureSpeedPercent;
    out.breakthroughChance = e.breakthroughChance;
    out.targetRealm = e.targetRealm;
    out.cultivationAdd = e.cultivationAdd;
    out.skillExpAdd = e.skillExpAdd;
    out.nurtureAdd = e.nurtureAdd;
    out.healMaxHpPercent = e.healMaxHpPercent;
    out.mpRecoverMaxMpPercent = e.mpRecoverMaxMpPercent;
    out.hpAdd = e.hpAdd;
    out.mpAdd = e.mpAdd;
    out.extendLife = e.extendLife;
    out.physicalAttackAdd = e.physicalAttackAdd;
    out.magicAttackAdd = e.magicAttackAdd;
    out.physicalDefenseAdd = e.physicalDefenseAdd;
    out.magicDefenseAdd = e.magicDefenseAdd;
    out.speedAdd = e.speedAdd;
    out.critRateAdd = e.critRateAdd;
    out.critEffectAdd = e.critEffectAdd;
    out.intelligenceAdd = e.intelligenceAdd;
    out.charmAdd = e.charmAdd;
    out.loyaltyAdd = e.loyaltyAdd;
    out.comprehensionAdd = e.comprehensionAdd;
    out.artifactRefiningAdd = e.artifactRefiningAdd;
    out.pillRefiningAdd = e.pillRefiningAdd;
    out.spiritPlantingAdd = e.spiritPlantingAdd;
    out.teachingAdd = e.teachingAdd;
    out.moralityAdd = e.moralityAdd;
    out.miningAdd = e.miningAdd;
    out.revive = e.revive;
    out.clearAll = e.clearAll;
    out.isAscension = e.isAscension;
    out.duration = e.duration;
    out.cannotStack = e.cannotStack;
    out.minRealm = pill.minRealm;
    out.pillCategory = pill.category;
    out.pillType = pill.pillType;
    return out;
}

/// 丹药入袋：扣仓库丹药堆叠 + 内嵌 effect/grade（字段特殊，不适用通用路径）
inline bool appendPillBagItem(GameState& state, const MerchantItem& item,
                              std::size_t discipleRow, int32_t year,
                              int32_t month) {
    const auto pill = deductWarehouseStack(state.pills, item.itemId, item.name,
                                           item.rarity);
    if (!pill.has_value()) return false;
    StorageBagItem bagItem;
    bagItem.itemId = pill->id;
    bagItem.itemType = kItemTypePill;
    bagItem.name = item.name;
    bagItem.rarity = item.rarity;
    bagItem.quantity = 1;
    bagItem.effect = pillToItemEffect(merchant_settle::toPill(item));
    bagItem.grade = item.grade.has_value() ? *item.grade
                                           : std::string("中品");
    bagItem.obtainedYear = year;
    bagItem.obtainedMonth = month;
    bagItem.stackedData = BagStackedData();
    state.disciples.storageBagItems[discipleRow].push_back(std::move(bagItem));
    return true;
}

// ── 灵石扣减（deductSpiritStones：优先储物袋再随身） ──────────────

inline void deductSpiritStones(GameState& state, std::size_t discipleRow,
                               int64_t price) {
    auto& ds = state.disciples;
    int64_t bagStones = ds.storageBagSpiritStones[discipleRow];
    int64_t pocketStones = static_cast<int64_t>(ds.spiritStones[discipleRow]);
    int64_t remaining = price;

    const int64_t bagDeduction =
        remaining < bagStones ? remaining : bagStones;
    bagStones -= bagDeduction;
    remaining -= bagDeduction;
    const int64_t pocketDeduction =
        remaining < pocketStones ? remaining : pocketStones;
    pocketStones -= pocketDeduction;

    ds.storageBagSpiritStones[discipleRow] = bagStones;
    ds.spiritStones[discipleRow] =
        static_cast<int32_t>(pocketStones);
}

// ── 入袋入口（addToWarehouseAndBag：按 item.type 分发） ───────────

inline bool addToWarehouseAndBag(GameState& state, const MerchantItem& item,
                                 std::size_t discipleRow, int32_t year,
                                 int32_t month) {
    // Kotlin addToWarehouseAndBag：item.type.lowercase(Locale.ROOT) 分发
    const std::string type = toLowerAscii(item.type);
    if (type == kItemTypeEquipment) {
        // 装备：minRealm/slot 取仓库堆叠元数据
        const auto stack = deductWarehouseStack(
            state.equipmentStacks, item.itemId, item.name, item.rarity);
        if (!stack.has_value()) return false;
        BagStackedData sd;
        sd.minRealm = stack->minRealm;
        sd.slot = stack->slot;
        StorageBagItem bagItem;
        bagItem.itemId = stack->id;
        bagItem.itemType = kItemTypeEquipmentStack;
        bagItem.name = item.name;
        bagItem.rarity = item.rarity;
        bagItem.quantity = 1;
        bagItem.obtainedYear = year;
        bagItem.obtainedMonth = month;
        bagItem.stackedData = sd;
        state.disciples.storageBagItems[discipleRow].push_back(
            std::move(bagItem));
        return true;
    }
    if (type == kItemTypeManual) {
        const auto stack = deductWarehouseStack(
            state.manualStacks, item.itemId, item.name, item.rarity);
        if (!stack.has_value()) return false;
        BagStackedData sd;
        sd.minRealm = stack->minRealm;
        sd.manualType = stack->type;
        StorageBagItem bagItem;
        bagItem.itemId = stack->id;
        bagItem.itemType = kItemTypeManualStack;
        bagItem.name = item.name;
        bagItem.rarity = item.rarity;
        bagItem.quantity = 1;
        bagItem.obtainedYear = year;
        bagItem.obtainedMonth = month;
        bagItem.stackedData = sd;
        state.disciples.storageBagItems[discipleRow].push_back(
            std::move(bagItem));
        return true;
    }
    if (type == kItemTypePill) {
        return appendPillBagItem(state, item, discipleRow, year, month);
    }
    return false;
}

// ── 购买决策应用（applyPurchaseDecisions） ─────────────────────────

/// S-20：弟子智能购买日志草稿（Kotlin DiscipleTables.lifeEvents 为类体属性
/// @Ignore 非协议字段——C++ 无该列，购买发生时记录草稿，Kotlin 侧写瞬态列；
/// 日志格式 "${age}岁：购买了${itemName}"，与 Kotlin applyPurchaseDecisions
/// 购买点逐条对齐）。定义于本属主文件（applyPurchaseDecisions 内部填充），
/// month_settlement.h 的 MonthSettlementResult 引用它。
struct PurchaseLogDraft {
    std::string discipleId;                // Kotlin Disciple.id（String）
    std::string itemName;
    int32_t age = 0;                       // 购买时年龄
};

inline void applyPurchaseDecisions(GameState& state,
                                   const std::vector<PurchaseEntry>& decisions,
                                   int32_t year, int32_t month,
                                   std::vector<PurchaseLogDraft>* purchaseLogs) {
    for (const auto& decision : decisions) {
        const MerchantItem& item = *decision.item;
        const std::size_t dRow = decision.discipleRow;

        // 仓库无库存 → 跳过（listing 保留，等补货后再购）
        if (!hasWarehouseStock(state, item)) continue;
        // 设计要求：先扣仓库再扣灵石，仓库失败则跳过购买
        if (!addToWarehouseAndBag(state, item, dRow, year, month)) continue;
        deductSpiritStones(state, dRow, item.price);
        // 弟子支付的灵石计入宗门仓库
        state.gameData.spiritStones += item.price;
        // S-20：购买日志草稿（Kotlin lifeEvents 瞬态列——C++ 无该列，
        // 记录草稿由 Kotlin 残留执行器写回；格式 "${age}岁：购买了${name}"）
        if (purchaseLogs != nullptr) {
            PurchaseLogDraft log;
            log.discipleId = state.disciples.ids[dRow];
            log.itemName = item.name;
            log.age = state.disciples.ages[dRow];
            purchaseLogs->push_back(std::move(log));
        }
    }
}

// ── 主入口（executePurchase 等价） ────────────────────────────────

/// 执行弟子智能购买（S8 子事件 12；零 RNG 主路径之外的 SYSTEM 洗牌）。
/// @param purchaseLogs S-20 草稿（可为 null）：实际购买发生时逐条追加
///   （discipleId/itemName/age），随 nativeSettleMonth 信封回传 Kotlin。
inline void processDisciplePurchase(GameState& state,
                                    gamecore::rng::RngManager& rng,
                                    std::vector<PurchaseLogDraft>* purchaseLogs = nullptr) {
    const std::vector<MerchantItem>& listedItems = state.gameData.playerListedItems;
    if (listedItems.empty()) return;

    const std::vector<DisciplePurchaseContext> allDisciples =
        collectDisciples(state.disciples);
    if (allDisciples.empty()) return;

    auto& rngSystem = rng.getRng(gamecore::rng::RngPartition::kSystem);
    const std::vector<PurchaseEntry> decisions = buildPurchaseDecisions(
        listedItems, allDisciples, state.equipmentInstances,
        state.manualInstances, rngSystem);
    if (decisions.empty()) return;

    applyPurchaseDecisions(state, decisions, state.gameData.gameYear,
                           state.gameData.gameMonth, purchaseLogs);
}

}  // namespace gamecore::system::disciple_purchase
