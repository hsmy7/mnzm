#pragma once

#include <algorithm>
#include <cstdint>
#include <optional>
#include <set>
#include <string>
#include <vector>

#include "gamecore/data/equipment_db.h"
#include "gamecore/data/manual_db.h"
#include "gamecore/state/models.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/disciple_stats.h"
#include "gamecore/system/pill_system.h"   // decreaseItemQuantity（袋内堆叠扣减）
#include "gamecore/system/recruit_settlement.h"  // nextInstanceId / minRealmForRarity
#include "gamecore/system/settlement_detail.h"

// ============================================================
// 自动装备/学习（B，2026-08-31）
//
// 等价移植 Kotlin CultivationEventProcessor.processAutoFromWarehouse +
// DiscipleEquipmentManager.processAutoEquipFromWarehouse +
// DiscipleManualManager.processAutoLearnFromWarehouse，并扩展：
//   1. 候选源统一：宗门仓库堆叠 + 弟子储物袋条目
//      （equipment_instance / manual_instance 完整实例保真直接装配；
//       equipment_stack / manual_stack 按名查模板重建——模板缺失丢弃，
//       对齐 BagItemReconstructor 语义）
//   2. 更高品阶自动替换（无开关，功能激活即生效）：已装备/已学功法的
//      槽位存在严格更优候选时，先卸旧入袋再装新（旧装备/功法必回
//      储物袋，不丢失；功法替换同步清残留熟练度）
//   3. 统一比较键（降序）：品阶 → 攻击类型匹配 → 孕养等级
//      （严格有序键，防同品阶震荡）
//
// 编排（对齐 Kotlin）：
//   - 资格：autoEquip/autoLearn 各自 focused/rootCounts 判定（或语义）
//   - 弟子排序：followed 降序 → realm 升序 → realmLayer 降序
//   - 每弟子先装备（WEAPON/ARMOR/BOOTS/ACCESSORY 槽序）后学习
//   - 每槽位每旬至多一次装配/替换
//
// 已知范围边界（对拍约定）：
//   - lifeEvents（"X岁：自动装备了Y"）为 Kotlin 类体属性（非协议字段），
//     C++ 侧不记录——与 S-20 同源
//   - 实例 id 为镜像生成字段（Kotlin UUID，C++ 确定性自增占位）
//   - 装备/功法名称与模板查询走 codegen 单一数据源（equipment_db/manual_db）
// ============================================================
namespace gamecore::system {
namespace detail {

using gamecore::state::Disciple;
using gamecore::state::DiscipleStore;
using gamecore::state::EquipmentInstance;
using gamecore::state::EquipmentStack;
using gamecore::state::GameData;
using gamecore::state::GameState;
using gamecore::state::ManualInstance;
using gamecore::state::ManualStack;
using gamecore::state::StorageBagItem;
namespace settle_util = gamecore::system::settle_util;
using settle_util::spiritRootCount;
using settle_util::toIntOrNull;

/// 秘境探索中存活成员 id 集合（与 phase_settlement.h 同源定义；本头文件
/// 独立提供避免循环包含）
inline std::set<int32_t> secretRealmIdsOf(const GameData& gd) {
    std::set<int32_t> ids;
    for (const auto& m : gd.secretRealmSession.members) {
        if (m.isDead) continue;
        const auto id = toIntOrNull(m.discipleId);
        if (id.has_value()) ids.insert(*id);
    }
    return ids;
}

// ── 资格判定（Kotlin qualifiesForSectAutoPublic：focused 优先，回退灵根数） ──

inline bool qualifiesForSectAuto(
        const Disciple& d, bool focused, const std::vector<int32_t>& rootCounts) {
    if (!focused && rootCounts.empty()) return false;
    if (focused) {
        const auto it = d.statusData.find("followed");
        if (it != d.statusData.end() && it->second == "true") return true;
    }
    const int32_t roots = spiritRootCount(d);
    return std::find(rootCounts.begin(), rootCounts.end(), roots) !=
           rootCounts.end();
}

// ── 装备候选（统一：仓库堆叠 或 储物袋条目） ──────────────────────

struct EquipCandidate {
    const EquipmentStack* stack = nullptr;   // 非空 = 仓库源
    const StorageBagItem* bag = nullptr;     // 非空 = 储物袋源
    std::string slot;
    int32_t rarity = 0;
    int32_t minRealm = 0;
    bool hasPhysical = false;
    bool hasMagic = false;
    int32_t nurtureLevel = 0;
    bool isInstance = false;                 // 袋内完整实例（直接装配保真）
    std::string instanceId;                  // isInstance 时的实例 id
    int32_t bagQuantity = 0;                 // 袋条目数量（扣减用）
    std::string stackId;                     // 仓库堆叠 id（扣减用）
};

/// 攻击类型匹配度（Kotlin prefersPhysical 判定：物攻 ≥ 法攻偏好物理）
inline int32_t equipTypeMatch(const Disciple& d, const EquipCandidate& c) {
    const bool prefersPhysical = d.basePhysicalAttack >= d.baseMagicAttack;
    if (prefersPhysical && c.hasPhysical) return 1;
    if (!prefersPhysical && c.hasMagic) return 1;
    return 0;
}

/// 统一比较键（降序）：品阶 → 类型匹配 → 孕养等级（严格有序防震荡）
inline bool equipBetter(
        const Disciple& d, const EquipCandidate& a, const EquipCandidate& b) {
    if (a.rarity != b.rarity) return a.rarity > b.rarity;
    const int32_t ma = equipTypeMatch(d, a);
    const int32_t mb = equipTypeMatch(d, b);
    if (ma != mb) return ma > mb;
    return a.nurtureLevel > b.nurtureLevel;
}

/// 已装备实例 → 候选（替换比较基准）
inline EquipCandidate candidateFromInstance(const EquipmentInstance& eq) {
    EquipCandidate c;
    c.slot = eq.slot;
    c.rarity = eq.rarity;
    c.minRealm = eq.minRealm;
    c.hasPhysical = eq.physicalAttack > 0;
    c.hasMagic = eq.magicAttack > 0;
    c.nurtureLevel = eq.nurtureLevel;
    return c;
}

/// 按名查装备模板（袋内堆叠重建；与 Kotlin EquipmentDatabase.getTemplateByName
/// 同源中性数据 → 名称一致）
inline const gamecore::data::EquipmentTemplate* equipmentTemplateByName(
        const std::string& name) {
    const auto& ts = gamecore::data::equipmentTemplates();
    for (const auto& t : ts) {
        if (t.name == name) return &t;
    }
    return nullptr;
}

/// 从储物袋条目构建装备候选；
/// equipment_stack 需模板重建完整字段（模板缺失 → 丢弃，对齐 BagItemReconstructor null）
inline std::optional<EquipCandidate> equipCandidateFromBag(
        const StorageBagItem& item) {
    EquipCandidate c;
    c.bag = &item;
    if (item.itemType == "equipment_instance" && item.equipmentInstance.has_value()) {
        const auto& eq = *item.equipmentInstance;
        c.slot = eq.slot;
        c.rarity = eq.rarity;
        c.minRealm = eq.minRealm;
        c.hasPhysical = eq.physicalAttack > 0;
        c.hasMagic = eq.magicAttack > 0;
        c.nurtureLevel = eq.nurtureLevel;
        c.isInstance = true;
        c.instanceId = eq.id;
        c.bagQuantity = item.quantity;
        return c;
    }
    if (item.itemType == "equipment_stack" && item.stackedData.has_value()) {
        const auto* tpl = equipmentTemplateByName(item.name);
        if (tpl == nullptr) return std::nullopt;
        c.slot = item.stackedData->slot.empty() ? tpl->slot : item.stackedData->slot;
        c.rarity = tpl->rarity;
        c.minRealm = item.stackedData->minRealm > 0
            ? item.stackedData->minRealm
            : gamecore::system::recruit_settle::minRealmForRarity(tpl->rarity);
        c.hasPhysical = tpl->physicalAttack > 0;
        c.hasMagic = tpl->magicAttack > 0;
        c.bagQuantity = item.quantity;
        return c;
    }
    return std::nullopt;
}

// ── 功法候选（统一：仓库堆叠 或 储物袋条目） ──────────────────────

struct ManualCandidate {
    const ManualStack* stack = nullptr;
    const StorageBagItem* bag = nullptr;
    std::string name;
    std::string type;               // ManualType.name
    int32_t rarity = 0;
    int32_t minRealm = 0;
    bool hasPhysical = false;       // skillDamageType == "physical"
    bool hasMagic = false;          // skillDamageType == "magic"
    bool isInstance = false;
    std::string instanceId;
    int32_t bagQuantity = 0;
    std::string stackId;
};

/// 攻击类型匹配度（功法按 skillDamageType）
inline int32_t manualTypeMatch(const Disciple& d, const ManualCandidate& c) {
    const bool prefersPhysical = d.basePhysicalAttack >= d.baseMagicAttack;
    if (prefersPhysical && c.hasPhysical) return 1;
    if (!prefersPhysical && c.hasMagic) return 1;
    return 0;
}

/// 统一比较键（降序）：品阶 → 类型匹配
inline bool manualBetter(
        const Disciple& d, const ManualCandidate& a, const ManualCandidate& b) {
    if (a.rarity != b.rarity) return a.rarity > b.rarity;
    const int32_t ma = manualTypeMatch(d, a);
    const int32_t mb = manualTypeMatch(d, b);
    return ma > mb;
}

/// 按名查功法模板（袋内堆叠重建；与 Kotlin ManualDatabase.getByName 同源）
inline const gamecore::data::ManualTemplate* manualTemplateByName(
        const std::string& name) {
    const auto& ts = gamecore::data::manualTemplates();
    for (const auto& t : ts) {
        if (t.name == name) return &t;
    }
    return nullptr;
}

/// 从储物袋条目构建功法候选（manual_instance 完整实例 / manual_stack 模板重建）
inline std::optional<ManualCandidate> manualCandidateFromBag(
        const StorageBagItem& item) {
    ManualCandidate c;
    c.bag = &item;
    if (item.itemType == "manual_instance" && item.manualInstance.has_value()) {
        const auto& mn = *item.manualInstance;
        c.name = mn.name;
        c.type = mn.type;
        c.rarity = mn.rarity;
        c.minRealm = mn.minRealm;
        c.hasPhysical = mn.skillDamageType == "physical";
        c.hasMagic = mn.skillDamageType == "magic";
        c.isInstance = true;
        c.instanceId = mn.id;
        c.bagQuantity = item.quantity;
        return c;
    }
    if (item.itemType == "manual_stack" && item.stackedData.has_value()) {
        const auto* tpl = manualTemplateByName(item.name);
        if (tpl == nullptr) return std::nullopt;
        c.name = tpl->name;
        c.type = item.stackedData->manualType.empty() ? tpl->type
                                                      : item.stackedData->manualType;
        c.rarity = tpl->rarity;
        c.minRealm = item.stackedData->minRealm > 0
            ? item.stackedData->minRealm
            : gamecore::system::recruit_settle::minRealmForRarity(tpl->rarity);
        c.hasPhysical = tpl->skillDamageType == "physical";
        c.hasMagic = tpl->skillDamageType == "magic";
        c.bagQuantity = item.quantity;
        return c;
    }
    return std::nullopt;
}

// ── 槽位读写辅助 ──────────────────────────────────────────────────

inline std::string equipSlotId(const Disciple& d, const std::string& slot) {
    if (slot == "WEAPON") return d.weaponId;
    if (slot == "ARMOR") return d.armorId;
    if (slot == "BOOTS") return d.bootsId;
    if (slot == "ACCESSORY") return d.accessoryId;
    return "";
}

inline void setEquipSlot(Disciple& d, const std::string& slot,
                         const std::string& instanceId) {
    if (slot == "WEAPON") d.weaponId = instanceId;
    else if (slot == "ARMOR") d.armorId = instanceId;
    else if (slot == "BOOTS") d.bootsId = instanceId;
    else if (slot == "ACCESSORY") d.accessoryId = instanceId;
}

// ── 装备动作 ──────────────────────────────────────────────────────

/// 仓库堆叠 → 实例（Kotlin EquipmentStack.toInstance：字段复制 + ownerId + isEquipped）
inline EquipmentInstance instanceFromStack(const EquipmentStack& s,
                                           const std::string& ownerId) {
    EquipmentInstance inst;
    inst.id = gamecore::system::recruit_settle::nextInstanceId();
    inst.name = s.name;
    inst.rarity = s.rarity;
    inst.description = s.description;
    inst.slot = s.slot;
    inst.physicalAttack = s.physicalAttack;
    inst.magicAttack = s.magicAttack;
    inst.physicalDefense = s.physicalDefense;
    inst.magicDefense = s.magicDefense;
    inst.speed = s.speed;
    inst.hp = s.hp;
    inst.mp = s.mp;
    inst.critChance = s.critChance;
    inst.minRealm = s.minRealm;
    inst.ownerId = ownerId;
    inst.isEquipped = true;
    return inst;
}

/// 仓库堆叠扣减（-1 或整条移除；作用于调用方持有的仓库快照副本）
inline void deductWarehouseStack(std::vector<EquipmentStack>& stacks,
                                 const std::string& stackId) {
    for (auto& s : stacks) {
        if (s.id != stackId) continue;
        if (s.quantity > 1) {
            s.quantity -= 1;
        } else {
            stacks.erase(std::remove_if(stacks.begin(), stacks.end(),
                [&](const EquipmentStack& x) { return x.id == stackId; }),
                stacks.end());
        }
        return;
    }
}

/// 旧装备卸装入袋（Kotlin depositOldEquipmentToBag：实例入袋 + 从实例表移除 + 清槽）
inline void depositEquippedToBag(Disciple& d, GameState& state,
                                 const EquipmentInstance& old) {
    StorageBagItem entry;
    entry.itemId = old.id;
    entry.itemType = "equipment_instance";
    entry.name = old.name;
    entry.rarity = old.rarity;
    entry.quantity = 1;
    entry.obtainedYear = state.gameData.gameYear;
    entry.obtainedMonth = state.gameData.gameMonth;
    entry.equipmentInstance = old;
    d.storageBagItems.push_back(entry);
    state.equipmentInstances.erase(
        std::remove_if(state.equipmentInstances.begin(),
                       state.equipmentInstances.end(),
                       [&](const EquipmentInstance& x) { return x.id == old.id; }),
        state.equipmentInstances.end());
}

/// 单个槽位自动装配/替换（返回是否发生变更；
/// @param warehouseStacks 仓库堆叠快照副本（就地扣减，主流程末尾统一写回））
inline bool autoEquipSlot(Disciple& d, GameState& state,
                          std::vector<EquipmentStack>& warehouseStacks,
                          const std::string& slot) {
    // 候选收集：仓库堆叠 + 储物袋条目
    std::vector<EquipCandidate> candidates;
    for (const EquipmentStack& s : warehouseStacks) {
        if (s.slot != slot) continue;
        if (d.realm > s.minRealm) continue;          // 境界不符（realm <= minRealm 才可）
        if (s.isLocked) continue;
        if (s.quantity <= 0) continue;
        EquipCandidate c;
        c.stack = &s;
        c.slot = s.slot;
        c.rarity = s.rarity;
        c.minRealm = s.minRealm;
        c.hasPhysical = s.physicalAttack > 0;
        c.hasMagic = s.magicAttack > 0;
        c.stackId = s.id;
        candidates.push_back(c);
    }
    for (const StorageBagItem& item : d.storageBagItems) {
        auto c = equipCandidateFromBag(item);
        if (!c.has_value()) continue;
        if (c->slot != slot) continue;
        if (d.realm > c->minRealm) continue;
        candidates.push_back(*c);
    }
    if (candidates.empty()) return false;

    const EquipCandidate* best = &candidates[0];
    for (std::size_t i = 1; i < candidates.size(); ++i) {
        if (equipBetter(d, candidates[i], *best)) best = &candidates[i];
    }

    const std::string currentId = equipSlotId(d, slot);
    const EquipmentInstance* equipped = nullptr;
    if (!currentId.empty()) {
        for (const EquipmentInstance& eq : state.equipmentInstances) {
            if (eq.id == currentId) { equipped = &eq; break; }
        }
        if (equipped == nullptr) return false;   // 实例缺失（损坏态）→ 跳过
    }

    // 替换判定：槽位已占用且候选不严格更优 → 不动
    if (equipped != nullptr &&
        !equipBetter(d, *best, candidateFromInstance(*equipped))) {
        return false;
    }

    // 装配所需数据先拷贝：depositEquippedToBag 会 push 储物袋条目导致
    // candidates 持有的 bag 指针悬垂（2026-08-31 调试发现的同族 UB）
    const bool fromStack = best->stack != nullptr;
    const bool fromInstance = best->isInstance;
    const std::string actionStackId = best->stackId;
    const std::string actionBagItemId = best->bag ? best->bag->itemId : "";
    const std::string actionBagName = best->bag ? best->bag->name : "";
    const int32_t actionMinRealm = best->minRealm;
    std::optional<EquipmentInstance> actionBagInstance;
    if (fromInstance && best->bag != nullptr &&
        best->bag->equipmentInstance.has_value()) {
        actionBagInstance = *best->bag->equipmentInstance;
    }

    if (equipped != nullptr) {
        depositEquippedToBag(d, state, *equipped);   // 旧装备回袋
    }

    // 装配
    if (fromStack) {
        EquipmentInstance inst = instanceFromStack(*best->stack, d.id);
        state.equipmentInstances.push_back(inst);
        setEquipSlot(d, slot, inst.id);
        deductWarehouseStack(warehouseStacks, actionStackId);
    } else if (fromInstance && actionBagInstance.has_value()) {
        // 袋内完整实例：直接装配——实例可能不在实例表（卸装入袋后已移除，
        // 防双持有不变量），故按袋内条目重建；已在表内则置标记
        EquipmentInstance attached = *actionBagInstance;
        attached.isEquipped = true;
        attached.ownerId = d.id;
        bool found = false;
        for (EquipmentInstance& eq : state.equipmentInstances) {
            if (eq.id == attached.id) {
                eq = attached;
                found = true;
                break;
            }
        }
        if (!found) state.equipmentInstances.push_back(std::move(attached));
        setEquipSlot(d, slot, actionBagInstance->id);
        d.storageBagItems.erase(
            std::remove_if(d.storageBagItems.begin(), d.storageBagItems.end(),
                [&](const StorageBagItem& x) { return x.itemId == actionBagItemId; }),
            d.storageBagItems.end());
    } else {
        // 袋内堆叠：模板重建完整实例（equipCandidateFromBag 已保证模板存在）
        const auto* tpl = equipmentTemplateByName(actionBagName);
        EquipmentInstance inst;
        inst.id = gamecore::system::recruit_settle::nextInstanceId();
        inst.name = tpl->name;
        inst.rarity = tpl->rarity;
        inst.description = tpl->description;
        inst.slot = tpl->slot;
        inst.physicalAttack = tpl->physicalAttack;
        inst.magicAttack = tpl->magicAttack;
        inst.physicalDefense = tpl->physicalDefense;
        inst.magicDefense = tpl->magicDefense;
        inst.speed = tpl->speed;
        inst.hp = tpl->hp;
        inst.mp = tpl->mp;
        inst.critChance = tpl->critChance;
        inst.minRealm = actionMinRealm;
        inst.ownerId = d.id;
        inst.isEquipped = true;
        state.equipmentInstances.push_back(inst);
        setEquipSlot(d, slot, inst.id);
        d.storageBagItems = gamecore::pill::decreaseItemQuantity(
            d.storageBagItems, actionBagItemId, 1);
    }
    return true;
}

// ── 功法动作 ──────────────────────────────────────────────────────

/// 仓库堆叠 → 实例（Kotlin ManualStack.toInstance）
inline ManualInstance manualInstanceFromStack(const ManualStack& s,
                                              const std::string& ownerId) {
    ManualInstance inst;
    inst.id = gamecore::system::recruit_settle::nextInstanceId();
    inst.name = s.name;
    inst.rarity = s.rarity;
    inst.description = s.description;
    inst.type = s.type;
    inst.stats = s.stats;
    inst.skillName = s.skillName;
    inst.skillDescription = s.skillDescription;
    inst.skillType = s.skillType;
    inst.skillDamageType = s.skillDamageType;
    inst.skillHits = s.skillHits;
    inst.skillDamageMultiplier = s.skillDamageMultiplier;
    inst.skillCooldown = s.skillCooldown;
    inst.skillMpCost = s.skillMpCost;
    inst.skillHealPercent = s.skillHealPercent;
    inst.skillHealFixed = s.skillHealFixed;
    inst.skillHealType = s.skillHealType;
    inst.skillBuffType = s.skillBuffType;
    inst.skillBuffValue = s.skillBuffValue;
    inst.skillBuffDuration = s.skillBuffDuration;
    inst.skillBuffsJson = s.skillBuffsJson;
    inst.skillIsAoe = s.skillIsAoe;
    inst.skillTargetScope = s.skillTargetScope;
    inst.skillShieldPercent = s.skillShieldPercent;
    inst.skillTurnAdvancePercent = s.skillTurnAdvancePercent;
    inst.skillDamageSharePercent = s.skillDamageSharePercent;
    inst.skillDamageLinkPercent = s.skillDamageLinkPercent;
    inst.minRealm = s.minRealm;
    inst.ownerId = ownerId;
    inst.isLearned = true;
    return inst;
}

/// 仓库功法堆叠扣减（-1 或整条移除；作用于调用方持有的仓库快照副本）
inline void deductManualWarehouseStack(std::vector<ManualStack>& stacks,
                                       const std::string& stackId) {
    for (auto& s : stacks) {
        if (s.id != stackId) continue;
        if (s.quantity > 1) {
            s.quantity -= 1;
        } else {
            stacks.erase(std::remove_if(stacks.begin(), stacks.end(),
                [&](const ManualStack& x) { return x.id == stackId; }),
                stacks.end());
        }
        return;
    }
}

/// 模板 → skillBuffsJson（Kotlin `skillBuffs.joinToString("|") { "type,value,duration" }`）
inline std::string buffsJsonOf(const gamecore::data::ManualTemplate& tpl) {
    std::string out;
    for (std::size_t i = 0; i < tpl.skillBuffs.size(); ++i) {
        if (i > 0) out += "|";
        out += tpl.skillBuffs[i].type + "," +
               gamecore::system::recruit_settle::kotlinDoubleString(
                   tpl.skillBuffs[i].value) + "," +
               std::to_string(tpl.skillBuffs[i].duration);
    }
    return out;
}

/// 袋内模板重建功法实例（manual_stack；模板可能缺失——缺失时仅名称/品阶/类型保真）
inline ManualInstance manualInstanceFromBagTemplate(
        const StorageBagItem& item, const std::string& ownerId,
        int32_t minRealm) {
    ManualInstance inst;
    const auto* tpl = manualTemplateByName(item.name);
    if (tpl != nullptr) {
        inst.name = tpl->name;
        inst.rarity = tpl->rarity;
        inst.description = tpl->description;
        inst.type = tpl->type;
        inst.stats = tpl->stats;
        inst.skillName = tpl->skillName;
        inst.skillDescription = tpl->skillDescription;
        inst.skillType = tpl->skillType;
        inst.skillDamageType = tpl->skillDamageType;
        inst.skillHits = tpl->skillHits;
        inst.skillDamageMultiplier = tpl->skillDamageMultiplier;
        inst.skillCooldown = tpl->skillCooldown;
        inst.skillMpCost = tpl->skillMpCost;
        inst.skillHealPercent = tpl->skillHealPercent;
        inst.skillHealFixed = tpl->skillHealFixed;
        inst.skillHealType = tpl->skillHealType;
        inst.skillBuffType = tpl->skillBuffType;
        inst.skillBuffValue = tpl->skillBuffValue;
        inst.skillBuffDuration = tpl->skillBuffDuration;
        inst.skillIsAoe = tpl->skillIsAoe;
        inst.skillTargetScope = tpl->skillTargetScope;
        inst.skillShieldPercent = tpl->skillShieldPercent;
        inst.skillTurnAdvancePercent = tpl->skillTurnAdvancePercent;
        inst.skillDamageSharePercent = tpl->skillDamageSharePercent;
        inst.skillDamageLinkPercent = tpl->skillDamageLinkPercent;
        inst.skillBuffsJson = buffsJsonOf(*tpl);
    } else {
        inst.name = item.name;
        inst.rarity = item.rarity;
        inst.type = item.stackedData && !item.stackedData->manualType.empty()
            ? item.stackedData->manualType : "MIND";
    }
    inst.id = gamecore::system::recruit_settle::nextInstanceId();
    inst.minRealm = minRealm;
    inst.ownerId = ownerId;
    inst.isLearned = true;
    return inst;
}

/// 已学功法实例 → 候选（替换比较基准）
inline ManualCandidate manualCandidateFromInstance(const ManualInstance& mn) {
    ManualCandidate c;
    c.name = mn.name;
    c.type = mn.type;
    c.rarity = mn.rarity;
    c.minRealm = mn.minRealm;
    c.hasPhysical = mn.skillDamageType == "physical";
    c.hasMagic = mn.skillDamageType == "magic";
    return c;
}

/// 遗忘功法入袋（Kotlin forgetManual：实例入袋 + 从实例表移除 + manualIds 移除 +
/// 熟练度清理）
inline void forgetManualToBag(Disciple& d, GameState& state,
                              const ManualInstance& old) {
    // 先拷贝 id：后续 state.manualInstances erase 会使引用 old 悬垂（修复
    // 2026-08-31 调试发现的 UB——erase 后读 old.id 为垃圾值致移除错误条目）
    const std::string oldId = old.id;
    StorageBagItem entry;
    entry.itemId = oldId;
    entry.itemType = "manual_instance";
    entry.name = old.name;
    entry.rarity = old.rarity;
    entry.quantity = 1;
    entry.obtainedYear = state.gameData.gameYear;
    entry.obtainedMonth = state.gameData.gameMonth;
    entry.manualInstance = old;
    d.storageBagItems.push_back(entry);
    state.manualInstances.erase(
        std::remove_if(state.manualInstances.begin(), state.manualInstances.end(),
                       [&](const ManualInstance& x) { return x.id == oldId; }),
        state.manualInstances.end());
    d.manualIds.erase(
        std::remove(d.manualIds.begin(), d.manualIds.end(), oldId),
        d.manualIds.end());
    // 熟练度清理（Kotlin clearRemovedManualProficiencies）
    auto& profMap = state.gameData.manualProficiencies;
    const auto pit = profMap.find(d.id);
    if (pit != profMap.end()) {
        std::vector<gamecore::state::ManualProficiencyData> kept;
        for (const auto& p : pit->second) {
            if (p.manualId != old.id) kept.push_back(p);
        }
        if (kept.empty()) profMap.erase(pit);
        else pit->second = std::move(kept);
    }
}

/// 功法槽位上限（Kotlin DiscipleStatCalculator.getMaxManualSlots）
inline int32_t maxManualSlotsFor(const Disciple& d) {
    const auto merged = gamecore::stats::mergeEffects(
        gamecore::stats::talentEffectsFor(d.talentIds),
        gamecore::stats::affixEffectsFor(d.affixIds));
    const auto it = merged.find("manualSlot");
    return gamecore::disciple::kBaseManualSlots +
           (it != merged.end() ? static_cast<int32_t>(it->second) : 0);
}

/// 单个弟子的自动学习/替换（返回是否发生变更；
/// @param warehouseStacks 仓库功法堆叠快照副本（就地扣减，主流程末尾统一写回））
inline bool autoLearnForDisciple(Disciple& d, GameState& state,
                                 std::vector<ManualStack>& warehouseStacks) {
    const int32_t maxSlots = maxManualSlotsFor(d);
    if (maxSlots <= 0) return false;

    // 已学信息（名称去重 + 心法唯一）
    const auto learnedInstance = [&](const std::string& id) -> const ManualInstance* {
        for (const ManualInstance& mn : state.manualInstances) {
            if (mn.id == id) return &mn;
        }
        return nullptr;
    };
    bool hasMind = false;
    for (const std::string& mid : d.manualIds) {
        const auto* mn = learnedInstance(mid);
        if (mn != nullptr && mn->type == "MIND") { hasMind = true; break; }
    }

    std::vector<ManualCandidate> candidates;
    for (const ManualStack& s : warehouseStacks) {
        if (d.realm > s.minRealm) continue;
        if (s.isLocked) continue;
        if (s.quantity <= 0) continue;
        bool dup = false;
        for (const std::string& mid : d.manualIds) {
            const auto* mn = learnedInstance(mid);
            if (mn != nullptr && mn->name == s.name) { dup = true; break; }
        }
        if (dup) continue;
        if (hasMind && s.type == "MIND") continue;
        ManualCandidate c;
        c.stack = &s;
        c.name = s.name;
        c.type = s.type;
        c.rarity = s.rarity;
        c.minRealm = s.minRealm;
        c.hasPhysical = s.skillDamageType == "physical";
        c.hasMagic = s.skillDamageType == "magic";
        c.stackId = s.id;
        candidates.push_back(c);
    }
    for (const StorageBagItem& item : d.storageBagItems) {
        auto c = manualCandidateFromBag(item);
        if (!c.has_value()) continue;
        if (d.realm > c->minRealm) continue;
        bool dup = false;
        for (const std::string& mid : d.manualIds) {
            const auto* mn = learnedInstance(mid);
            if (mn != nullptr && mn->name == c->name) { dup = true; break; }
        }
        if (dup) continue;
        if (hasMind && c->type == "MIND") continue;
        candidates.push_back(*c);
    }
    if (candidates.empty()) return false;

    const ManualCandidate* best = &candidates[0];
    for (std::size_t i = 1; i < candidates.size(); ++i) {
        if (manualBetter(d, candidates[i], *best)) best = &candidates[i];
    }

    // 替换判定：槽位已满且候选不严格优于"最差已学功法" → 不动
    if (static_cast<int32_t>(d.manualIds.size()) >= maxSlots) {
        const ManualInstance* worst = nullptr;
        ManualCandidate worstCand;
        bool hasWorst = false;
        for (const std::string& mid : d.manualIds) {
            const auto* mn = learnedInstance(mid);
            if (mn == nullptr) continue;
            const ManualCandidate cand = manualCandidateFromInstance(*mn);
            // manualBetter(worstCand, cand) 真 = worstCand 更优 → cand 更差 → 收敛最低
            if (!hasWorst || manualBetter(d, worstCand, cand)) {
                worst = mn;
                worstCand = cand;
                hasWorst = true;
            }
        }
        if (!hasWorst) return false;
        if (!manualBetter(d, *best, worstCand)) return false;

        // 装配所需数据先拷贝：forgetManualToBag 会 push 储物袋条目导致
        // candidates 持有的 bag 指针悬垂（2026-08-31 调试发现的同族 UB）
        const bool fromStack = best->stack != nullptr;
        const bool fromInstance = best->isInstance;
        const std::string actionStackId = best->stackId;
        const std::string actionBagItemId = best->bag ? best->bag->itemId : "";
        const std::string actionBagName = best->bag ? best->bag->name : "";
        const int32_t actionMinRealm = best->minRealm;
        std::optional<ManualInstance> actionBagInstance;
        if (fromInstance && best->bag != nullptr &&
            best->bag->manualInstance.has_value()) {
            actionBagInstance = *best->bag->manualInstance;
        }

        forgetManualToBag(d, state, *worst);
        // 槽位已空出，走"学习"（不再判定满槽）
        if (fromStack) {
            ManualInstance inst = manualInstanceFromStack(*best->stack, d.id);
            state.manualInstances.push_back(inst);
            d.manualIds.push_back(inst.id);
            const auto& stats = best->stack->stats;
            const int32_t hpDelta = stats.count("hp")    ? stats.at("hp")
                                  : stats.count("maxHp") ? stats.at("maxHp") : 0;
            const int32_t mpDelta = stats.count("mp")    ? stats.at("mp")
                                  : stats.count("maxMp") ? stats.at("maxMp") : 0;
            if (d.currentHp >= 0 && hpDelta > 0) d.currentHp += hpDelta;
            if (d.currentMp >= 0 && mpDelta > 0) d.currentMp += mpDelta;
            deductManualWarehouseStack(warehouseStacks, actionStackId);
            return true;
        }
        if (fromInstance && actionBagInstance.has_value()) {
            ManualInstance attached = *actionBagInstance;
            attached.isLearned = true;
            attached.ownerId = d.id;
            bool found = false;
            for (ManualInstance& mn : state.manualInstances) {
                if (mn.id == attached.id) { mn = attached; found = true; break; }
            }
            if (!found) state.manualInstances.push_back(std::move(attached));
            d.manualIds.push_back(attached.id);
            d.storageBagItems.erase(
                std::remove_if(d.storageBagItems.begin(), d.storageBagItems.end(),
                    [&](const StorageBagItem& x) { return x.itemId == actionBagItemId; }),
                d.storageBagItems.end());
            return true;
        }
        // 袋内堆叠：模板重建（manualCandidateFromBag 已保证模板存在）
        {
            ManualInstance inst = manualInstanceFromBagTemplate(
                *best->bag, d.id, actionMinRealm);
            state.manualInstances.push_back(inst);
            d.manualIds.push_back(inst.id);
            const auto* tpl = manualTemplateByName(actionBagName);
            const int32_t hpDelta = tpl && tpl->stats.count("hp") ? tpl->stats.at("hp")
                                  : tpl && tpl->stats.count("maxHp") ? tpl->stats.at("maxHp") : 0;
            const int32_t mpDelta = tpl && tpl->stats.count("mp") ? tpl->stats.at("mp")
                                  : tpl && tpl->stats.count("maxMp") ? tpl->stats.at("maxMp") : 0;
            if (d.currentHp >= 0 && hpDelta > 0) d.currentHp += hpDelta;
            if (d.currentMp >= 0 && mpDelta > 0) d.currentMp += mpDelta;
            d.storageBagItems = gamecore::pill::decreaseItemQuantity(
                d.storageBagItems, actionBagItemId, 1);
            return true;
        }
    }

    // 学习
    if (best->stack != nullptr) {
        ManualInstance inst = manualInstanceFromStack(*best->stack, d.id);
        state.manualInstances.push_back(inst);
        d.manualIds.push_back(inst.id);
        // HP/MP 增量（learnManual 语义：rawHp >= 0 且增益为正）
        const auto& stats = best->stack->stats;
        const int32_t hpDelta = stats.count("hp")    ? stats.at("hp")
                              : stats.count("maxHp") ? stats.at("maxHp") : 0;
        const int32_t mpDelta = stats.count("mp")    ? stats.at("mp")
                              : stats.count("maxMp") ? stats.at("maxMp") : 0;
        if (d.currentHp >= 0 && hpDelta > 0) d.currentHp += hpDelta;
        if (d.currentMp >= 0 && mpDelta > 0) d.currentMp += mpDelta;
        deductManualWarehouseStack(warehouseStacks, best->stackId);
    } else if (best->isInstance) {
        // 袋内完整实例：直接装配——实例可能不在实例表（遗忘入袋后已移除，
        // 防双持有不变量），按袋内条目重建；已在表内则置标记
        const auto& bagMn = *best->bag->manualInstance;
        ManualInstance attached = bagMn;
        attached.isLearned = true;
        attached.ownerId = d.id;
        bool found = false;
        for (ManualInstance& mn : state.manualInstances) {
            if (mn.id == bagMn.id) {
                mn = attached;
                found = true;
                break;
            }
        }
        if (!found) state.manualInstances.push_back(std::move(attached));
        d.manualIds.push_back(best->instanceId);
        d.storageBagItems.erase(
            std::remove_if(d.storageBagItems.begin(), d.storageBagItems.end(),
                [&](const StorageBagItem& x) { return x.itemId == best->instanceId; }),
            d.storageBagItems.end());
    } else {
        // 袋内堆叠：模板重建（manualCandidateFromBag 已保证模板存在）
        ManualInstance inst = manualInstanceFromBagTemplate(
            *best->bag, d.id, best->minRealm);
        state.manualInstances.push_back(inst);
        d.manualIds.push_back(inst.id);
        const auto* tpl = manualTemplateByName(best->name);
        const int32_t hpDelta = tpl && tpl->stats.count("hp") ? tpl->stats.at("hp")
                              : tpl && tpl->stats.count("maxHp") ? tpl->stats.at("maxHp") : 0;
        const int32_t mpDelta = tpl && tpl->stats.count("mp") ? tpl->stats.at("mp")
                              : tpl && tpl->stats.count("maxMp") ? tpl->stats.at("maxMp") : 0;
        if (d.currentHp >= 0 && hpDelta > 0) d.currentHp += hpDelta;
        if (d.currentMp >= 0 && mpDelta > 0) d.currentMp += mpDelta;
        d.storageBagItems = gamecore::pill::decreaseItemQuantity(
            d.storageBagItems, best->bag->itemId, 1);
    }
    return true;
}

// ── 主入口：自动从仓库+储物袋装备/学习 ────────────────────────────

/// 自动装备/学习主流程（Kotlin CultivationEventProcessor.processAutoFromWarehouse）。
/// 对齐编排：资格预筛（存活 + 非秘境 + equip/learn 或语义）→ 排序
/// （followed 降序 / realm 升序 / realmLayer 降序）→ 每弟子先装备后学习 →
/// 字段写回（含仓库堆叠快照统一写回）。
inline void processAutoFromWarehouse(GameState& state) {
    const GameData& gd = state.gameData;
    const bool equipFocused = gd.autoEquipFromWarehouseFocused;
    const std::vector<int32_t> equipRootCounts(
        gd.autoEquipFromWarehouseRootCounts.begin(),
        gd.autoEquipFromWarehouseRootCounts.end());
    const bool learnFocused = gd.autoLearnFromWarehouseFocused;
    const std::vector<int32_t> learnRootCounts(
        gd.autoLearnFromWarehouseRootCounts.begin(),
        gd.autoLearnFromWarehouseRootCounts.end());
    const bool hasAutoEquip = equipFocused || !equipRootCounts.empty();
    const bool hasAutoLearn = learnFocused || !learnRootCounts.empty();
    if (!hasAutoEquip && !hasAutoLearn) return;

    DiscipleStore& ds = state.disciples;
    const auto secretIds = secretRealmIdsOf(gd);

    // 资格预筛
    std::vector<std::size_t> rows;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] == 0) continue;
        const auto id = toIntOrNull(ds.ids[row]);
        if (!id.has_value() || secretIds.count(*id)) continue;
        const Disciple d = ds.materialize(row);
        if (!d.isAlive) continue;
        const bool equipOk =
            hasAutoEquip && qualifiesForSectAuto(d, equipFocused, equipRootCounts);
        const bool learnOk =
            hasAutoLearn && qualifiesForSectAuto(d, learnFocused, learnRootCounts);
        if (equipOk || learnOk) rows.push_back(row);
    }
    if (rows.empty()) return;

    // 排序（followed 降序 → realm 升序 → realmLayer 降序；stable 对齐 sortedWith）
    std::stable_sort(rows.begin(), rows.end(), [&](std::size_t a, std::size_t b) {
        const Disciple da = ds.materialize(a);
        const Disciple db = ds.materialize(b);
        const auto followedIt = da.statusData.find("followed");
        const bool fa = followedIt != da.statusData.end() &&
                        followedIt->second == "true";
        const auto followedItB = db.statusData.find("followed");
        const bool fb = followedItB != db.statusData.end() &&
                        followedItB->second == "true";
        if (fa != fb) return fa;
        if (da.realm != db.realm) return da.realm < db.realm;
        return da.realmLayer > db.realmLayer;
    });

    // 仓库快照（循环外读取一次；装配扣减就地更新，末尾统一写回）
    std::vector<EquipmentStack> eqStacks = state.equipmentStacks;
    std::vector<ManualStack> mnStacks = state.manualStacks;

    for (std::size_t row : rows) {
        Disciple d = ds.materialize(row);
        bool changed = false;
        if (hasAutoEquip && qualifiesForSectAuto(d, equipFocused, equipRootCounts)) {
            if (autoEquipSlot(d, state, eqStacks, "WEAPON")) changed = true;
            if (autoEquipSlot(d, state, eqStacks, "ARMOR")) changed = true;
            if (autoEquipSlot(d, state, eqStacks, "BOOTS")) changed = true;
            if (autoEquipSlot(d, state, eqStacks, "ACCESSORY")) changed = true;
        }
        if (hasAutoLearn && qualifiesForSectAuto(d, learnFocused, learnRootCounts)) {
            if (autoLearnForDisciple(d, state, mnStacks)) changed = true;
        }
        if (!changed) continue;
        // 精准字段写回（Kotlin writeAutoWarehouseResults 字段面）
        ds.storageBagItems[row] = d.storageBagItems;
        ds.weaponIds[row] = d.weaponId;
        ds.armorIds[row] = d.armorId;
        ds.bootsIds[row] = d.bootsId;
        ds.accessoryIds[row] = d.accessoryId;
        ds.manualIds[row] = d.manualIds;
        ds.currentHps[row] = d.currentHp;
        ds.currentMps[row] = d.currentMp;
    }
    // 仓库堆叠写回（就地更新的快照 → 状态）
    state.equipmentStacks = std::move(eqStacks);
    state.manualStacks = std::move(mnStacks);
}

}  // namespace detail
}  // namespace gamecore::system
