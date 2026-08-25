#pragma once

#include <algorithm>
#include <cstdint>
#include <functional>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/state/models.h"

// ============================================================
// 库存系统（Kotlin→C++ 迁移批次 4）
//
// 等价移植 Kotlin StackableItemStore + InventorySystem 的**纯逻辑**部分：
//   - StackKey 合并键（name/rarity/slot/category/grade/growTime）
//   - StackableItemStore.add 多堆叠合并 + 分块创建 + Partial/Failure 语义
//   - InventorySystem addXxx/removeXxx/canAddXxx（槽位预算 = 仓库容量 - 其他类型）
//   - 溢出转邮件（C++ 侧产出 OverflowDraft 记录，Kotlin 侧落库——批次 9 对接）
//
// 与 Kotlin 语义对齐要点（StackableItemStore 文档）：
//   - 合并时**遍历所有匹配堆叠**（最近使用优先，promoteKey 移至首部）
//   - 仅在所有匹配堆叠填满后才分块创建新堆叠
//   - 槽位不足：已合并过 → Partial(overflow=剩余)；零合并且无空槽 → Failure(Full)
//   - 分块创建：首个分块保留原 id（兼容语义），后续分块必须生成新 id（防 REPLACE 去重丢失）
//   - maxSlots 惰性求值（每类型上限 = computeMaxSlots() - 其他类型数量）
// ============================================================
namespace gamecore::system {

// ── 库存错误（对应 AppError.Domain.Inventory）────────────────────

enum class InventoryErrorType {
    kNone,
    kFull,          // 仓库已满
    kNotFound,      // 物品不存在
    kInvalidName,   // 名称无效
    kInvalidRarity, // 稀有度无效
    kInvalidQuantity, // 数量无效
    kLocked,        // 已锁定
    kInsufficient,  // 数量不足
    kDuplicateId,   // ID 重复
};

struct InventoryError {
    InventoryErrorType type = InventoryErrorType::kNone;
    std::string itemId;
    int32_t value = 0;      // InvalidRarity/InvalidQuantity 的数值
    int32_t need = 0;       // Insufficient 所需
    int32_t have = 0;       // Insufficient 现有
};

// ── 库存结果（对应 DomainResult：Success/Partial/Failure）─────────

enum class InventoryStatus {
    kSuccess,
    kPartial,
    kFailure,
};

template <typename T>
struct InventoryResult {
    InventoryStatus status = InventoryStatus::kFailure;
    T data{};              // Success/Partial 的 data（最后一次合并的堆叠）
    int32_t overflow = 0;  // Partial 的溢出量
    InventoryError error;

    bool isSuccess() const {
        return status == InventoryStatus::kSuccess || status == InventoryStatus::kPartial;
    }
};

/// void 特化（remove 等无数据操作）
template <>
struct InventoryResult<void> {
    InventoryStatus status = InventoryStatus::kFailure;
    int32_t overflow = 0;  // 统一字段（void 不使用）
    InventoryError error;

    bool isSuccess() const {
        return status == InventoryStatus::kSuccess || status == InventoryStatus::kPartial;
    }
};

// ── 物品通用访问（模板适配：id/quantity/isLocked/withQuantity/withNewId）──

template <typename T>
inline std::string itemId(const T& item) { return item.id; }
template <typename T>
inline int32_t itemQuantity(const T& item) { return item.quantity; }
template <typename T>
inline bool itemLocked(const T& item) { return item.isLocked; }

// ── StackKey（合并键：parts 拼接，与 Kotlin StackKey.of 语义对应）──

using StackKey = std::string;

inline StackKey makeStackKey(std::initializer_list<std::string> parts) {
    std::string out;
    for (const auto& p : parts) {
        if (!out.empty()) out.push_back('\x1F');
        out += p;
    }
    return out;
}

/// 各类型合并键（与 Kotlin StackKeys 一一对应）
inline StackKey equipmentKey(const state::EquipmentStack& it) {
    return makeStackKey({it.name, std::to_string(it.rarity), it.slot});
}
inline StackKey manualKey(const state::ManualStack& it) {
    return makeStackKey({it.name, std::to_string(it.rarity), it.type});
}
inline StackKey pillKey(const state::Pill& it) {
    return makeStackKey({it.name, std::to_string(it.rarity), it.category, it.grade});
}
inline StackKey materialKey(const state::Material& it) {
    return makeStackKey({it.name, std::to_string(it.rarity), it.category});
}
inline StackKey herbKey(const state::Herb& it) {
    return makeStackKey({it.name, std::to_string(it.rarity), it.category});
}
inline StackKey seedKey(const state::Seed& it) {
    return makeStackKey({it.name, std::to_string(it.rarity), std::to_string(it.growTime)});
}
inline StackKey storageBagKey(const state::StorageBag& it) {
    return makeStackKey({std::to_string(it.rarity)});
}

// ── 物品 setter（withQuantity / withNewId 等价；copy-on-write 返回新值）──

inline state::EquipmentStack withQuantity(const state::EquipmentStack& it, int32_t q) {
    state::EquipmentStack out = it;
    out.quantity = q;
    return out;
}
inline state::EquipmentStack withNewId(const state::EquipmentStack& it, const std::string& id) {
    state::EquipmentStack out = it;
    out.id = id;
    return out;
}
inline state::ManualStack withQuantity(const state::ManualStack& it, int32_t q) {
    state::ManualStack out = it;
    out.quantity = q;
    return out;
}
inline state::ManualStack withNewId(const state::ManualStack& it, const std::string& id) {
    state::ManualStack out = it;
    out.id = id;
    return out;
}
inline state::Pill withQuantity(const state::Pill& it, int32_t q) {
    state::Pill out = it;
    out.quantity = q;
    return out;
}
inline state::Pill withNewId(const state::Pill& it, const std::string& id) {
    state::Pill out = it;
    out.id = id;
    return out;
}
inline state::Material withQuantity(const state::Material& it, int32_t q) {
    state::Material out = it;
    out.quantity = q;
    return out;
}
inline state::Material withNewId(const state::Material& it, const std::string& id) {
    state::Material out = it;
    out.id = id;
    return out;
}
inline state::Herb withQuantity(const state::Herb& it, int32_t q) {
    state::Herb out = it;
    out.quantity = q;
    return out;
}
inline state::Herb withNewId(const state::Herb& it, const std::string& id) {
    state::Herb out = it;
    out.id = id;
    return out;
}
inline state::Seed withQuantity(const state::Seed& it, int32_t q) {
    state::Seed out = it;
    out.quantity = q;
    return out;
}
inline state::Seed withNewId(const state::Seed& it, const std::string& id) {
    state::Seed out = it;
    out.id = id;
    return out;
}
inline state::StorageBag withQuantity(const state::StorageBag& it, int32_t q) {
    state::StorageBag out = it;
    out.quantity = q;
    return out;
}
inline state::StorageBag withNewId(const state::StorageBag& it, const std::string& id) {
    state::StorageBag out = it;
    out.id = id;
    return out;
}

// ── 可堆叠物品仓库（StackableItemStore<T> 等价）────────────────────
//
// 泛型约束（鸭子类型）：T 需提供 id/quantity/isLocked 字段 + 上面定义的
// itemId/itemQuantity/itemLocked/withQuantity/withNewId 适配。
template <typename T>
class StackableItemStore {
public:
    using KeyFn = std::function<StackKey(const T&)>;

    StackableItemStore(std::vector<T> initialItems, KeyFn keyFn, int32_t maxStack,
                       std::function<int32_t()> maxSlots)
        : items_(std::move(initialItems)), keyFn_(std::move(keyFn)),
          maxStack_(maxStack), maxSlots_(std::move(maxSlots)) {
        rebuildKeyIndex();
    }

    const std::vector<T>& all() const { return items_; }
    std::vector<T>& all() { return items_; }
    int32_t size() const { return static_cast<int32_t>(items_.size()); }
    int32_t slotCount() const { return size(); }

    const T* get(const std::string& id) const {
        for (const auto& it : items_) if (it.id == id) return &it;
        return nullptr;
    }

    /// 添加物品（Kotlin StackableItemStore.add 等价）
    InventoryResult<T> add(const T& item, bool merge = true) {
        // 守卫：数量非法或 maxStack<=0
        if (item.quantity <= 0 || maxStack_ <= 0) {
            InventoryResult<T> r;
            r.status = InventoryStatus::kFailure;
            r.error.type = InventoryErrorType::kInvalidQuantity;
            r.error.value = item.quantity;
            return r;
        }
        const StackKey key = keyFn_(item);
        if (merge) {
            const auto outcome = mergeIntoExistingStacks(item, key);
            if (outcome.completed) {
                InventoryResult<T> r;
                r.status = InventoryStatus::kSuccess;
                r.data = *outcome.completed;
                return r;
            }
            if (outcome.remaining <= 0) {
                InventoryResult<T> r;
                r.status = InventoryStatus::kSuccess;
                r.data = item;
                return r;
            }
            return createNewStacksOrPartial(item, key, outcome.remaining, outcome.mergedAny);
        }
        return createNewStacksOrPartial(item, key, item.quantity, false);
    }

    /// 移除指定数量（Kotlin StackableItemStore.remove 等价）
    InventoryResult<void> remove(const std::string& id, int32_t count = 1) {
        InventoryResult<void> r;
        if (count <= 0) {
            r.status = InventoryStatus::kFailure;
            r.error.type = InventoryErrorType::kInvalidQuantity;
            r.error.value = count;
            return r;
        }
        auto it = std::find_if(items_.begin(), items_.end(),
                               [&](const T& x) { return x.id == id; });
        if (it == items_.end()) {
            r.status = InventoryStatus::kFailure;
            r.error.type = InventoryErrorType::kNotFound;
            r.error.itemId = id;
            return r;
        }
        if (it->isLocked && count > 0) {
            r.status = InventoryStatus::kFailure;
            r.error.type = InventoryErrorType::kLocked;
            r.error.itemId = id;
            return r;
        }
        if (count > it->quantity) {
            r.status = InventoryStatus::kFailure;
            r.error.type = InventoryErrorType::kInsufficient;
            r.error.itemId = id;
            r.error.need = count;
            r.error.have = it->quantity;
            return r;
        }
        const int32_t remaining = it->quantity - count;
        if (remaining <= 0) {
            const StackKey key = keyFn_(*it);
            removeFromKeyIndex(key, id);
            items_.erase(it);
        } else {
            *it = withQuantity(*it, remaining);
        }
        r.status = InventoryStatus::kSuccess;
        return r;
    }

    /// 全量替换并重建索引
    void replaceAll(const std::vector<T>& items) {
        items_ = items;
        rebuildKeyIndex();
    }

private:
    struct MergeOutcome {
        int32_t remaining = 0;
        bool mergedAny = false;
        const T* completed = nullptr;   // 全部合并完成时的最后一个堆叠
    };

    MergeOutcome mergeIntoExistingStacks(const T& item, const StackKey& key) {
        MergeOutcome out;
        const auto it = keyIndex_.find(key);
        if (it == keyIndex_.end()) {
            out.remaining = item.quantity;
            return out;
        }
        int32_t remaining = item.quantity;
        bool mergedAny = false;
        const T* completed = nullptr;
        // 快照遍历（ids 极小，典型 1-3）
        const std::vector<std::string> ids = it->second;
        for (const auto& id : ids) {
            auto sit = std::find_if(items_.begin(), items_.end(),
                                    [&](const T& x) { return x.id == id; });
            if (sit == items_.end()) continue;
            const int32_t space = maxStack_ - sit->quantity;
            if (space <= 0) continue;
            const int32_t addQty = std::min(remaining, space);
            *sit = withQuantity(*sit, sit->quantity + addQty);
            remaining -= addQty;
            mergedAny = true;
            promoteKey(key, id);
            if (remaining <= 0) {
                // completed 指向 items_ 中的元素——注意 promoteKey 不重排 items_，
                // 仅重排 keyIndex_，因此指针仍有效
                completed = &(*sit);
                break;
            }
        }
        out.remaining = remaining;
        out.mergedAny = mergedAny;
        out.completed = completed;
        return out;
    }

    InventoryResult<T> createNewStacksOrPartial(const T& item, const StackKey& key,
                                                int32_t remaining, bool mergedAny) {
        if (size() >= maxSlots_()) {
            return fullSlotResult(key, remaining, mergedAny);
        }
        return createChunksResult(item, key, remaining);
    }

    InventoryResult<T> fullSlotResult(const StackKey& key, int32_t remaining,
                                      bool mergedAny) {
        InventoryResult<T> r;
        const auto it = keyIndex_.find(key);
        if (mergedAny && it != keyIndex_.end() && !it->second.empty()) {
            const std::string& lastId = it->second.back();
            auto sit = std::find_if(items_.begin(), items_.end(),
                                    [&](const T& x) { return x.id == lastId; });
            if (sit != items_.end()) {
                r.status = InventoryStatus::kPartial;
                r.data = *sit;
                r.overflow = remaining;
                return r;
            }
            r.status = InventoryStatus::kFailure;
            r.error.type = InventoryErrorType::kNotFound;
            r.error.itemId = lastId;
            return r;
        }
        r.status = InventoryStatus::kFailure;
        r.error.type = InventoryErrorType::kFull;
        return r;
    }

    InventoryResult<T> createChunksResult(const T& item, const StackKey& key,
                                          int32_t remaining) {
        InventoryResult<T> r;
        int32_t left = remaining;
        int chunkIndex = 0;
        while (left > 0 && size() < maxSlots_()) {
            const int32_t chunk = std::min(left, maxStack_);
            T base = withQuantity(item, chunk);
            T newItem;
            if (chunkIndex == 0 && get(base.id) == nullptr) {
                newItem = base;  // 首个分块保留原 id（兼容既有语义）
            } else {
                newItem = withNewId(base, generateNewId());
            }
            items_.push_back(newItem);
            addToKeyIndex(key, newItem.id);
            left -= chunk;
            chunkIndex++;
        }
        if (left > 0) {
            const auto it = keyIndex_.find(key);
            if (it != keyIndex_.end() && !it->second.empty()) {
                const std::string& lastId = it->second.back();
                auto sit = std::find_if(items_.begin(), items_.end(),
                                        [&](const T& x) { return x.id == lastId; });
                if (sit != items_.end()) {
                    r.status = InventoryStatus::kPartial;
                    r.data = *sit;
                    r.overflow = left;
                    return r;
                }
            }
            r.status = InventoryStatus::kFailure;
            r.error.type = InventoryErrorType::kFull;
            return r;
        }
        r.status = InventoryStatus::kSuccess;
        r.data = item;
        return r;
    }

    void promoteKey(const StackKey& key, const std::string& id) {
        auto it = keyIndex_.find(key);
        if (it != keyIndex_.end()) {
            auto& list = it->second;
            const auto pos = std::find(list.begin(), list.end(), id);
            if (pos != list.end() && pos != list.begin()) {
                list.erase(pos);
                list.insert(list.begin(), id);
            } else if (pos == list.end()) {
                list.insert(list.begin(), id);
            }
        } else {
            keyIndex_[key] = {id};
        }
    }

    void removeFromKeyIndex(const StackKey& key, const std::string& id) {
        auto it = keyIndex_.find(key);
        if (it != keyIndex_.end()) {
            auto& list = it->second;
            const auto pos = std::find(list.begin(), list.end(), id);
            if (pos != list.end()) list.erase(pos);
            if (list.empty()) keyIndex_.erase(it);
        }
    }

    void addToKeyIndex(const StackKey& key, const std::string& id) {
        keyIndex_[key].push_back(id);
    }

    void rebuildKeyIndex() {
        keyIndex_.clear();
        for (const auto& it : items_) {
            keyIndex_[keyFn_(it)].push_back(it.id);
        }
    }

    /// 新堆叠 id 生成（确定性自增——Kotlin 用 UUID，语义等价：仅保证唯一，
    /// id 不参与合并键/业务逻辑，存档恢复时以 Kotlin 镜像为准）
    static std::string generateNewId() {
        static uint64_t counter = 0;
        return "gc-stack-" + std::to_string(++counter);
    }

    std::vector<T> items_;
    KeyFn keyFn_;
    int32_t maxStack_ = 0;
    std::function<int32_t()> maxSlots_;
    std::map<StackKey, std::vector<std::string>> keyIndex_;
};

// ── 仓库容量（Kotlin GameConfig.Warehouse + computeMaxSlots 等价）──

/// 仓库基础容量（GameConfig.Warehouse.BASE_CAPACITY 默认值）
constexpr int32_t kWarehouseBaseCapacity = 50;
/// 每栋仓库建筑增加容量（CAPACITY_PER_BUILDING 默认值）
constexpr int32_t kWarehouseCapacityPerBuilding = 75;

/// Int 回绕加法（Kotlin Int 溢出回绕语义；文档第 4 节确定性保真）
inline int32_t wrapAdd(int32_t a, int32_t b) {
    return static_cast<int32_t>(static_cast<uint32_t>(a) + static_cast<uint32_t>(b));
}

/// 计算最大槽位数（placedBuildings 中 displayName == "仓库" 计数）
inline int32_t computeMaxSlots(const state::GameState& state) {
    int32_t warehouseCount = 0;
    for (const auto& b : state.gameData.placedBuildings) {
        if (b.displayName == "仓库") warehouseCount++;
    }
    return kWarehouseBaseCapacity + warehouseCount * kWarehouseCapacityPerBuilding;
}

/// 当前已用槽位数（equipmentStacks + manualStacks + pills + materials + herbs + seeds）
inline int32_t computeSlotCount(const state::GameState& state) {
    return static_cast<int32_t>(
        state.equipmentStacks.size() + state.manualStacks.size() + state.pills.size() +
        state.materials.size() + state.herbs.size() + state.seeds.size());
}

// ── 溢出邮件草稿（C++ 侧产出，Kotlin OverflowMailSender 落库）──────

struct OverflowDraft {
    int32_t slotId = 1;
    std::string source;      // withTrackingSource 来源（battle/forge/alchemy/...）
    std::string itemType;    // equipment/manual/pill/material/herb/seed/storageBag
    std::string itemName;
    std::string itemId;      // 模板 id（精确还原；空串 = 按稀有度随机生成）
    int32_t rarity = 0;
    int32_t quantity = 0;
};

/// 溢出邮件草稿收集器（单线程契约；批次 9 由桥层导出给 Kotlin 落库）
class OverflowMailCollector {
public:
    void add(OverflowDraft draft) { drafts_.push_back(std::move(draft)); }

    std::vector<OverflowDraft> takeAll() {
        std::vector<OverflowDraft> out = std::move(drafts_);
        drafts_.clear();
        return out;
    }

    const std::vector<OverflowDraft>& all() const { return drafts_; }
    bool empty() const { return drafts_.empty(); }

private:
    std::vector<OverflowDraft> drafts_;
};

// ── InventorySystem 等价（addXxx/removeXxx/canAdd 纯逻辑）─────────

/// 各类型最大堆叠（Kotlin InventoryConfig.typeSpecificStackLimits）
inline int32_t getMaxStackSize(const std::string& type) {
    if (type == "pill") return 999;
    if (type == "material") return 9999;
    if (type == "herb") return 9999;
    if (type == "seed") return 9999;
    if (type == "manual_stack") return 999;
    if (type == "equipment_stack") return 999;
    return 9999;  // 默认 maxStackSize（storageBag 等）
}

/// 校验可堆叠物品参数（Kotlin validateStackableItem）
template <typename T>
inline InventoryResult<T> validateStackableItem(const T& item) {
    InventoryResult<T> r;
    if (item.name.empty()) {
        r.status = InventoryStatus::kFailure;
        r.error.type = InventoryErrorType::kInvalidName;
        return r;
    }
    if (item.rarity < 1 || item.rarity > 6) {
        r.status = InventoryStatus::kFailure;
        r.error.type = InventoryErrorType::kInvalidRarity;
        r.error.value = item.rarity;
        return r;
    }
    if (item.quantity <= 0) {
        r.status = InventoryStatus::kFailure;
        r.error.type = InventoryErrorType::kInvalidQuantity;
        r.error.value = item.quantity;
        return r;
    }
    return r;  // status 保持 kFailure 占位——调用方仅在 error.type==kNone 时视为通过
}

/// 校验结果是否通过（error.type == kNone）
template <typename T>
inline bool validationPassed(const InventoryResult<T>& r) {
    return r.error.type == InventoryErrorType::kNone;
}

/// 溢出收尾（Kotlin handleOverflowResult 等价：Partial/Failure(Full) → 邮件草稿）
template <typename T>
inline void handleOverflow(const InventoryResult<T>& result, const std::string& itemType,
                           const T& item, OverflowMailCollector& overflowMail,
                           const std::string& source, bool overflowMailSuppressed) {
    if (overflowMailSuppressed) return;
    int32_t overflowQty = 0;
    if (result.status == InventoryStatus::kPartial) {
        overflowQty = result.overflow;
    } else if (result.status == InventoryStatus::kFailure &&
               result.error.type == InventoryErrorType::kFull) {
        overflowQty = item.quantity;
    } else {
        return;
    }
    if (overflowQty <= 0) return;
    OverflowDraft draft;
    draft.source = source;
    draft.itemType = itemType;
    draft.itemName = item.name;
    draft.itemId = "";  // 模板 id 解析（resolveOverflowItemId）批次 2 注册表就绪后接线
    draft.rarity = item.rarity;
    draft.quantity = overflowQty;
    overflowMail.add(std::move(draft));
}

/// 添加装备堆叠（Kotlin addEquipmentStack 等价；含年度报告来源追踪）
inline InventoryResult<state::EquipmentStack> addEquipmentStack(
    state::GameState& state, const state::EquipmentStack& item,
    OverflowMailCollector& overflowMail, const std::string& trackingSource,
    bool overflowMailSuppressed) {
    auto validation = validateStackableItem(item);
    if (!validationPassed(validation)) return validation;

    const int32_t otherTypes = static_cast<int32_t>(
        state.manualStacks.size() + state.pills.size() + state.materials.size() +
        state.herbs.size() + state.seeds.size());
    StackableItemStore<state::EquipmentStack> store(
        state.equipmentStacks, equipmentKey, getMaxStackSize("equipment_stack"),
        [&]() { return computeMaxSlots(state) - otherTypes; });
    auto result = store.add(item);
    state.equipmentStacks = store.all();
    if (result.status == InventoryStatus::kSuccess || result.status == InventoryStatus::kPartial) {
        const int32_t actualAdded =
            (result.status == InventoryStatus::kSuccess) ? item.quantity
                                                         : item.quantity - result.overflow;
        const std::string srcKey = trackingSource + ":" + std::to_string(item.rarity);
        state.gameData.annualEquipmentBySource[srcKey] =
            wrapAdd(state.gameData.annualEquipmentBySource[srcKey], actualAdded);
    }
    handleOverflow(result, "equipment", item, overflowMail, trackingSource,
                   overflowMailSuppressed);
    return result;
}

/// 添加功法堆叠（Kotlin addManualStack 等价）
inline InventoryResult<state::ManualStack> addManualStack(
    state::GameState& state, const state::ManualStack& item,
    OverflowMailCollector& overflowMail, const std::string& trackingSource,
    bool overflowMailSuppressed, bool merge = true) {
    auto validation = validateStackableItem(item);
    if (!validationPassed(validation)) return validation;

    const int32_t otherTypes = static_cast<int32_t>(
        state.equipmentStacks.size() + state.pills.size() + state.materials.size() +
        state.herbs.size() + state.seeds.size());
    StackableItemStore<state::ManualStack> store(
        state.manualStacks, manualKey, getMaxStackSize("manual_stack"),
        [&]() { return computeMaxSlots(state) - otherTypes; });
    auto result = store.add(item, merge);
    state.manualStacks = store.all();
    handleOverflow(result, "manual", item, overflowMail, trackingSource,
                   overflowMailSuppressed);
    return result;
}

/// 添加丹药（Kotlin addPill 等价；含年度丹药来源追踪）
inline InventoryResult<state::Pill> addPill(
    state::GameState& state, const state::Pill& item,
    OverflowMailCollector& overflowMail, const std::string& trackingSource,
    bool overflowMailSuppressed, bool merge = true) {
    auto validation = validateStackableItem(item);
    if (!validationPassed(validation)) return validation;

    const int32_t otherTypes = static_cast<int32_t>(
        state.equipmentStacks.size() + state.manualStacks.size() + state.materials.size() +
        state.herbs.size() + state.seeds.size());
    StackableItemStore<state::Pill> store(
        state.pills, pillKey, getMaxStackSize("pill"),
        [&]() { return computeMaxSlots(state) - otherTypes; });
    auto result = store.add(item, merge);
    state.pills = store.all();
    if (result.status == InventoryStatus::kSuccess || result.status == InventoryStatus::kPartial) {
        const int32_t actualAdded =
            (result.status == InventoryStatus::kSuccess) ? item.quantity
                                                         : item.quantity - result.overflow;
        const std::string gradeName = item.grade.empty() ? "LOW" : item.grade;
        const std::string srcKey = trackingSource + ":" + gradeName;
        state.gameData.annualPillBySource[srcKey] =
            wrapAdd(state.gameData.annualPillBySource[srcKey], actualAdded);
    }
    handleOverflow(result, "pill", item, overflowMail, trackingSource,
                   overflowMailSuppressed);
    return result;
}

/// 添加材料（Kotlin addMaterial 等价）
inline InventoryResult<state::Material> addMaterial(
    state::GameState& state, const state::Material& item,
    OverflowMailCollector& overflowMail, const std::string& trackingSource,
    bool overflowMailSuppressed, bool merge = true) {
    auto validation = validateStackableItem(item);
    if (!validationPassed(validation)) return validation;

    const int32_t otherTypes = static_cast<int32_t>(
        state.equipmentStacks.size() + state.manualStacks.size() + state.pills.size() +
        state.herbs.size() + state.seeds.size());
    StackableItemStore<state::Material> store(
        state.materials, materialKey, getMaxStackSize("material"),
        [&]() { return computeMaxSlots(state) - otherTypes; });
    auto result = store.add(item, merge);
    state.materials = store.all();
    handleOverflow(result, "material", item, overflowMail, trackingSource,
                   overflowMailSuppressed);
    return result;
}

/// 添加草药（Kotlin addHerb 等价；含年度草药来源追踪）
inline InventoryResult<state::Herb> addHerb(
    state::GameState& state, const state::Herb& item,
    OverflowMailCollector& overflowMail, const std::string& trackingSource,
    bool overflowMailSuppressed, bool merge = true) {
    auto validation = validateStackableItem(item);
    if (!validationPassed(validation)) return validation;

    const int32_t otherTypes = static_cast<int32_t>(
        state.equipmentStacks.size() + state.manualStacks.size() + state.pills.size() +
        state.materials.size() + state.seeds.size());
    StackableItemStore<state::Herb> store(
        state.herbs, herbKey, getMaxStackSize("herb"),
        [&]() { return computeMaxSlots(state) - otherTypes; });
    auto result = store.add(item, merge);
    state.herbs = store.all();
    if (result.status == InventoryStatus::kSuccess || result.status == InventoryStatus::kPartial) {
        const int32_t actualAdded =
            (result.status == InventoryStatus::kSuccess) ? item.quantity
                                                         : item.quantity - result.overflow;
        state.gameData.annualHerbBySource[trackingSource] =
            wrapAdd(state.gameData.annualHerbBySource[trackingSource], actualAdded);
    }
    handleOverflow(result, "herb", item, overflowMail, trackingSource,
                   overflowMailSuppressed);
    return result;
}

/// 添加种子（Kotlin addSeed 等价）
inline InventoryResult<state::Seed> addSeed(
    state::GameState& state, const state::Seed& item,
    OverflowMailCollector& overflowMail, const std::string& trackingSource,
    bool overflowMailSuppressed, bool merge = true) {
    auto validation = validateStackableItem(item);
    if (!validationPassed(validation)) return validation;

    const int32_t otherTypes = static_cast<int32_t>(
        state.equipmentStacks.size() + state.manualStacks.size() + state.pills.size() +
        state.materials.size() + state.herbs.size());
    StackableItemStore<state::Seed> store(
        state.seeds, seedKey, getMaxStackSize("seed"),
        [&]() { return computeMaxSlots(state) - otherTypes; });
    auto result = store.add(item, merge);
    state.seeds = store.all();
    handleOverflow(result, "seed", item, overflowMail, trackingSource,
                   overflowMailSuppressed);
    return result;
}

/// 添加储物袋（Kotlin addStorageBag 等价；不占仓库槽位预算，独立 64 槽预算）
inline InventoryResult<state::StorageBag> addStorageBag(
    state::GameState& state, const state::StorageBag& item,
    OverflowMailCollector& overflowMail, const std::string& trackingSource,
    bool overflowMailSuppressed) {
    if (item.quantity <= 0) {
        InventoryResult<state::StorageBag> r;
        r.status = InventoryStatus::kFailure;
        r.error.type = InventoryErrorType::kInvalidQuantity;
        r.error.value = item.quantity;
        return r;
    }
    constexpr int32_t kStorageBagSlotBudget = 64;
    StackableItemStore<state::StorageBag> store(
        state.storageBags, storageBagKey, getMaxStackSize("storageBag"),
        [&]() { return kStorageBagSlotBudget; });
    auto result = store.add(item);
    state.storageBags = store.all();
    handleOverflow(result, "storageBag", item, overflowMail, trackingSource,
                   overflowMailSuppressed);
    return result;
}

/// 移除装备（Kotlin removeEquipment 等价）
inline bool removeEquipment(state::GameState& state, const std::string& id,
                            int32_t quantity = 1, bool bypassLock = false) {
    if (quantity <= 0) return false;
    auto it = std::find_if(state.equipmentStacks.begin(), state.equipmentStacks.end(),
                           [&](const state::EquipmentStack& x) { return x.id == id; });
    if (it == state.equipmentStacks.end()) return false;
    if (!bypassLock && it->isLocked) return false;
    if (it->quantity < quantity) return false;
    const int32_t newQty = it->quantity - quantity;
    if (newQty == 0) {
        state.equipmentStacks.erase(it);
    } else {
        *it = withQuantity(*it, newQty);
    }
    return true;
}

/// 移除功法（Kotlin removeManual 等价）
inline bool removeManual(state::GameState& state, const std::string& id,
                         int32_t quantity = 1, bool bypassLock = false) {
    if (quantity <= 0) return false;
    auto it = std::find_if(state.manualStacks.begin(), state.manualStacks.end(),
                           [&](const state::ManualStack& x) { return x.id == id; });
    if (it == state.manualStacks.end()) return false;
    if (!bypassLock && it->isLocked) return false;
    if (it->quantity < quantity) return false;
    const int32_t newQty = it->quantity - quantity;
    if (newQty == 0) {
        state.manualStacks.erase(it);
    } else {
        *it = withQuantity(*it, newQty);
    }
    return true;
}

/// 移除丹药（Kotlin removePill 等价）
inline bool removePill(state::GameState& state, const std::string& id,
                       int32_t quantity = 1, bool bypassLock = false) {
    if (quantity <= 0) return false;
    auto it = std::find_if(state.pills.begin(), state.pills.end(),
                           [&](const state::Pill& x) { return x.id == id; });
    if (it == state.pills.end()) return false;
    if (!bypassLock && it->isLocked) return false;
    if (it->quantity < quantity) return false;
    const int32_t newQty = it->quantity - quantity;
    if (newQty == 0) {
        state.pills.erase(it);
    } else {
        *it = withQuantity(*it, newQty);
    }
    return true;
}

/// 移除材料（Kotlin removeMaterial 等价）
inline bool removeMaterial(state::GameState& state, const std::string& id,
                           int32_t quantity = 1, bool bypassLock = false) {
    if (quantity <= 0) return false;
    auto it = std::find_if(state.materials.begin(), state.materials.end(),
                           [&](const state::Material& x) { return x.id == id; });
    if (it == state.materials.end()) return false;
    if (!bypassLock && it->isLocked) return false;
    if (it->quantity < quantity) return false;
    const int32_t newQty = it->quantity - quantity;
    if (newQty == 0) {
        state.materials.erase(it);
    } else {
        *it = withQuantity(*it, newQty);
    }
    return true;
}

/// 移除草药（Kotlin removeHerb 等价）
inline bool removeHerb(state::GameState& state, const std::string& id,
                       int32_t quantity = 1, bool bypassLock = false) {
    if (quantity <= 0) return false;
    auto it = std::find_if(state.herbs.begin(), state.herbs.end(),
                           [&](const state::Herb& x) { return x.id == id; });
    if (it == state.herbs.end()) return false;
    if (!bypassLock && it->isLocked) return false;
    if (it->quantity < quantity) return false;
    const int32_t newQty = it->quantity - quantity;
    if (newQty == 0) {
        state.herbs.erase(it);
    } else {
        *it = withQuantity(*it, newQty);
    }
    return true;
}

/// 移除种子（Kotlin removeSeed 等价）
inline bool removeSeed(state::GameState& state, const std::string& id,
                       int32_t quantity = 1, bool bypassLock = false) {
    if (quantity <= 0) return false;
    auto it = std::find_if(state.seeds.begin(), state.seeds.end(),
                           [&](const state::Seed& x) { return x.id == id; });
    if (it == state.seeds.end()) return false;
    if (!bypassLock && it->isLocked) return false;
    if (it->quantity < quantity) return false;
    const int32_t newQty = it->quantity - quantity;
    if (newQty == 0) {
        state.seeds.erase(it);
    } else {
        *it = withQuantity(*it, newQty);
    }
    return true;
}

/// 是否有空余槽位（Kotlin canAddItemInTransaction 等价）
inline bool canAddItem(const state::GameState& state) {
    return computeSlotCount(state) < computeMaxSlots(state);
}

/// 是否可以添加 count 件物品（Kotlin canAddItems 等价）
inline bool canAddItems(const state::GameState& state, int32_t count) {
    return computeSlotCount(state) + count <= computeMaxSlots(state);
}

}  // namespace gamecore::system
