#pragma once

// ============================================================
// 商人域月结下沉（S8 子事件 10：autoBuy 12 月自动购买）
//
// Kotlin AutoBuyService.executeAutoBuy 等价移植（批 11-3）。
//
// RNG 契约：全链零 RNG 抽取（匹配/容量/钱包/入库全部确定性）。MerchantItem
// Converter 的**未知物品回退分支**（批 11-4 S-18 清偿）：Kotlin 用 JVM 全局
// Random（非确定性、非分区）→ C++ 改物品名稳定散列选池（FNV-1a）——不消费
// 任何分区 RNG（损坏数据触达回退也不污染确定性流），跨语言内容本就无法
// 对齐（Kotlin 每次进程不同），C++ 侧确定性自洽。
//
// 已知边界（登记 S-18 剩余面）：
// - 溢出转邮件：C++ addXxx 产出 OverflowDraft 至本地 collector——
//   月结上下文无邮件通道，草稿丢弃（Kotlin 真相源发送）；对拍场景
//   仓库容量充足规避
// - MerchantItem 协议批 11-3 补齐 type/grade 字段（旧 .so 宽松兼容）
// ============================================================

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/data/beast_material_db.h"
#include "gamecore/data/equipment_db.h"
#include "gamecore/data/herb_db.h"
#include "gamecore/data/manual_db.h"
#include "gamecore/data/recipe_db.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/settlement_detail.h"
#include "nlohmann/json.hpp"

namespace gamecore::system::merchant_settle {

using gamecore::state::AutoBuyEntry;
using gamecore::state::EquipmentStack;
using gamecore::state::GameState;
using gamecore::state::Herb;
using gamecore::state::ManualStack;
using gamecore::state::Material;
using gamecore::state::MerchantItem;
using gamecore::state::Pill;
using gamecore::state::Seed;
namespace settle_util = gamecore::system::settle_util;

/// 实例/堆叠 id 生成（确定性自增——Kotlin UUID，语义等价，同 inventory.h）
inline std::string nextItemId() {
    static uint64_t counter = 0;
    return "gc-merch-" + std::to_string(++counter);
}

/// 品阶最低境界（GameConfig.Realm.getMinRealmForRarity）
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

/// Kotlin 双精度最短字符串（经 nlohmann 数值序列化对齐 Kotlin
/// Double.toString：整数值带 ".0"；0.2 → "0.2"）
inline std::string kotlinDoubleString(double v) {
    return nlohmann::json(v).dump();
}

/// 自动购买条目匹配（AutoBuyService.matches：name/type/rarity 全等）
inline bool matches(const AutoBuyEntry& entry, const MerchantItem& item) {
    return item.name == entry.itemName && item.type == entry.itemType &&
           item.rarity == entry.rarity;
}

/// 可购买数量（AutoBuyService.calculateBuyQuantity：
/// 灵石/价格 取整，与商人库存取小；价格 <=0 时按库存全买）
inline int32_t calculateBuyQuantity(int64_t spiritStones, int64_t price,
                                    int32_t merchantQuantity) {
    if (merchantQuantity <= 0) return 0;
    int32_t maxAffordable;
    if (price > 0) {
        const int64_t q = spiritStones / price;
        maxAffordable = (q > INT32_MAX) ? INT32_MAX : static_cast<int32_t>(q);
    } else {
        maxAffordable = merchantQuantity;
    }
    return std::min(merchantQuantity, std::max(maxAffordable, 0));
}

// ── MerchantItemConverter 模板路径（Kotlin 等价；未知名走回退分支） ──

/// 稳定名称散列（FNV-1a，跨编译器/平台稳定——libc++/libstdc++ 一致性）
inline std::size_t stableNameHash(const std::string& s) {
    std::size_t h = 2166136261u;
    for (unsigned char c : s) {
        h ^= c;
        h *= 16777619u;
    }
    return h;
}

/// 回退分支确定性选择（S-18 清偿，批 11-4）：Kotlin 用 JVM 全局 Random
///（非确定性、非分区）→ C++ 改为**物品名稳定散列选池**——不消费任何分区
/// RNG（损坏数据触达回退也不污染确定性流），跨语言内容本就无法对齐
///（Kotlin 每次进程不同），C++ 侧确定性自洽
template <typename T>
inline const T& fallbackPick(const std::vector<T>& templates,
                             const std::string& name, int32_t rarity) {
    std::vector<const T*> pool;
    for (const auto& t : templates) {
        if (t.rarity == rarity) pool.push_back(&t);
    }
    return pool.empty() ? templates[0] : *pool[stableNameHash(name) % pool.size()];
}

/// 装备转换（模板命中 → 模板字段；未知 → generateRandom(rarity) 回退）
inline EquipmentStack toEquipment(const MerchantItem& item) {
    const auto& templates = gamecore::data::equipmentTemplates();
    const auto it = std::find_if(templates.begin(), templates.end(),
        [&](const auto& t) { return t.name == item.name; });
    if (it != templates.end()) {
        EquipmentStack s;
        s.id = nextItemId();
        s.name = it->name;
        s.slot = it->slot;
        s.rarity = item.rarity;
        s.physicalAttack = it->physicalAttack;
        s.magicAttack = it->magicAttack;
        s.physicalDefense = it->physicalDefense;
        s.magicDefense = it->magicDefense;
        s.speed = it->speed;
        s.hp = it->hp;
        s.mp = it->mp;
        // 对齐 Kotlin MerchantItemConverter.toEquipment 模板分支：critChance
        // 保持默认 0（Kotlin 该分支遗漏模板 critChance——预存行为，对拍逐位一致）
        s.description = it->description;
        s.minRealm = minRealmForRarity(item.rarity);
        return s;
    }
    // 回退分支（S-18：物品名稳定散列选池，零分区 RNG 消耗）
    const auto& chosen = fallbackPick(templates, item.name, item.rarity);
    EquipmentStack s;
    s.id = nextItemId();
    s.name = chosen.name;
    s.slot = chosen.slot;
    s.rarity = item.rarity;
    s.physicalAttack = chosen.physicalAttack;
    s.magicAttack = chosen.magicAttack;
    s.physicalDefense = chosen.physicalDefense;
    s.magicDefense = chosen.magicDefense;
    s.speed = chosen.speed;
    s.hp = chosen.hp;
    s.mp = chosen.mp;
    s.critChance = chosen.critChance;
    s.description = chosen.description;
    s.minRealm = minRealmForRarity(item.rarity);
    return s;
}

/// 功法转换（模板命中 → 模板字段；未知 → generateRandom(rarity) 回退）
inline ManualStack toManual(const MerchantItem& item) {
    const auto& templates = gamecore::data::manualTemplates();
    const auto it = std::find_if(templates.begin(), templates.end(),
        [&](const auto& t) { return t.name == item.name; });
    if (it != templates.end()) {
        ManualStack m;
        m.id = nextItemId();
        m.name = it->name;
        m.rarity = item.rarity;
        m.description = it->description;
        m.type = it->type;
        m.stats = it->stats;
        m.skillName = it->skillName;
        m.skillDescription = it->skillDescription;
        m.skillType = it->skillType;
        m.skillDamageType = it->skillDamageType;
        m.skillHits = it->skillHits;
        m.skillDamageMultiplier = it->skillDamageMultiplier;
        m.skillCooldown = it->skillCooldown;
        m.skillMpCost = it->skillMpCost;
        m.skillHealPercent = it->skillHealPercent;
        m.skillHealType = it->skillHealType;
        // 对齐 Kotlin MerchantItemConverter.toManual 模板分支：skillHealFixed/
        // skillShieldPercent/skillTurnAdvancePercent/skillDamageSharePercent/
        // skillDamageLinkPercent 保持默认 0（Kotlin 该分支遗漏——预存行为）
        m.skillBuffType = it->skillBuffType;
        m.skillBuffValue = it->skillBuffValue;
        m.skillBuffDuration = it->skillBuffDuration;
        std::string buffsJson;
        for (std::size_t i = 0; i < it->skillBuffs.size(); ++i) {
            if (i > 0) buffsJson += "|";
            buffsJson += it->skillBuffs[i].type + "," +
                         kotlinDoubleString(it->skillBuffs[i].value) + "," +
                         std::to_string(it->skillBuffs[i].duration);
        }
        m.skillBuffsJson = std::move(buffsJson);
        m.skillIsAoe = it->skillIsAoe;
        m.skillTargetScope = it->skillTargetScope;
        m.minRealm = minRealmForRarity(item.rarity);
        m.quantity = 1;
        return m;
    }
    // 回退分支（S-18）
    // 回退分支（S-18：物品名稳定散列选池，零分区 RNG 消耗）
    const auto& chosen = fallbackPick(templates, item.name, item.rarity);
    ManualStack m;
    m.id = nextItemId();
    m.name = chosen.name;
    m.rarity = item.rarity;
    m.description = chosen.description;
    m.type = chosen.type;
    m.stats = chosen.stats;
    m.skillName = chosen.skillName;
    m.skillDescription = chosen.skillDescription;
    m.skillType = chosen.skillType;
    m.skillDamageType = chosen.skillDamageType;
    m.skillHits = chosen.skillHits;
    m.skillDamageMultiplier = chosen.skillDamageMultiplier;
    m.skillCooldown = chosen.skillCooldown;
    m.skillMpCost = chosen.skillMpCost;
    m.skillHealPercent = chosen.skillHealPercent;
    m.skillHealFixed = chosen.skillHealFixed;
    m.skillHealType = chosen.skillHealType;
    m.skillBuffType = chosen.skillBuffType;
    m.skillBuffValue = chosen.skillBuffValue;
    m.skillBuffDuration = chosen.skillBuffDuration;
    std::string buffsJson;
    for (std::size_t i = 0; i < chosen.skillBuffs.size(); ++i) {
        if (i > 0) buffsJson += "|";
        buffsJson += chosen.skillBuffs[i].type + "," +
                     kotlinDoubleString(chosen.skillBuffs[i].value) + "," +
                     std::to_string(chosen.skillBuffs[i].duration);
    }
    m.skillBuffsJson = std::move(buffsJson);
    m.skillIsAoe = chosen.skillIsAoe;
    m.skillTargetScope = chosen.skillTargetScope;
    m.minRealm = minRealmForRarity(item.rarity);
    m.quantity = 1;
    return m;
}

/// 丹药品阶显示名 → PillGrade.name（Kotlin displayName 映射：未知 → MEDIUM）
inline std::string gradeNameFromDisplay(const std::optional<std::string>& grade) {
    if (!grade.has_value()) return "MEDIUM";
    if (*grade == "下品") return "LOW";
    if (*grade == "中品") return "MEDIUM";
    if (*grade == "上品") return "HIGH";
    return "MEDIUM";
}

/// 丹药转换（PillRecipeDatabase.getRecipeByNameAndGrade → getRecipeByName 回退；
/// 未命中 → 回退分支）
inline Pill toPill(const MerchantItem& item) {
    const std::string gradeName = gradeNameFromDisplay(item.grade);
    std::string gradeLower = gradeName;
    std::transform(gradeLower.begin(), gradeLower.end(), gradeLower.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    const auto& recipes = gamecore::data::pillRecipes();
    const gamecore::data::PillRecipeTemplate* tpl = nullptr;
    for (const auto& r : recipes) {
        if (r.name == item.name && r.grade == gradeLower) { tpl = &r; break; }
    }
    if (tpl == nullptr) {
        for (const auto& r : recipes) {
            if (r.name == item.name) { tpl = &r; break; }
        }
    }
    if (tpl != nullptr) {
        Pill p;
        p.id = nextItemId();
        p.name = tpl->name;
        p.rarity = item.rarity;
        p.quantity = 1;
        p.description = tpl->description;
        p.category = tpl->category;
        p.grade = gradeName;
        p.pillType = tpl->pillType;
        p.effects.breakthroughChance = tpl->breakthroughChance;
        p.effects.targetRealm = tpl->targetRealm;
        p.effects.cultivationSpeedPercent = tpl->cultivationSpeedPercent;
        p.effects.duration = tpl->duration;
        p.effects.cultivationAdd = tpl->cultivationAdd;
        p.effects.skillExpAdd = tpl->skillExpAdd;
        p.effects.nurtureAdd = tpl->nurtureAdd;
        p.effects.extendLife = tpl->extendLife;
        p.effects.physicalAttackAdd = tpl->physicalAttackAdd;
        p.effects.magicAttackAdd = tpl->magicAttackAdd;
        p.effects.physicalDefenseAdd = tpl->physicalDefenseAdd;
        p.effects.magicDefenseAdd = tpl->magicDefenseAdd;
        p.effects.hpAdd = tpl->hpAdd;
        p.effects.mpAdd = tpl->mpAdd;
        p.effects.speedAdd = tpl->speedAdd;
        p.effects.critRateAdd = tpl->critRateAdd;
        p.effects.critEffectAdd = tpl->critEffectAdd;
        p.effects.intelligenceAdd = tpl->intelligenceAdd;
        p.effects.charmAdd = tpl->charmAdd;
        p.effects.loyaltyAdd = tpl->loyaltyAdd;
        p.effects.comprehensionAdd = tpl->comprehensionAdd;
        p.effects.artifactRefiningAdd = tpl->artifactRefiningAdd;
        p.effects.pillRefiningAdd = tpl->pillRefiningAdd;
        p.effects.spiritPlantingAdd = tpl->spiritPlantingAdd;
        p.effects.teachingAdd = tpl->teachingAdd;
        p.effects.moralityAdd = tpl->moralityAdd;
        p.minRealm = minRealmForRarity(item.rarity);
        return p;
    }
    // 回退分支（S-18：物品名稳定散列选池，零分区 RNG 消耗）
    const auto& chosen = fallbackPick(recipes, item.name, item.rarity);
    Pill p;
    p.id = nextItemId();
    p.name = chosen.name;
    p.rarity = item.rarity;
    p.quantity = 1;
    p.description = chosen.description;
    p.category = chosen.category;
    p.grade = gradeName;
    p.pillType = chosen.pillType;
    p.effects.breakthroughChance = chosen.breakthroughChance;
    p.effects.targetRealm = chosen.targetRealm;
    p.effects.cultivationSpeedPercent = chosen.cultivationSpeedPercent;
    p.effects.duration = chosen.duration;
    p.effects.cultivationAdd = chosen.cultivationAdd;
    p.effects.skillExpAdd = chosen.skillExpAdd;
    p.effects.nurtureAdd = chosen.nurtureAdd;
    p.effects.extendLife = chosen.extendLife;
    p.effects.physicalAttackAdd = chosen.physicalAttackAdd;
    p.effects.magicAttackAdd = chosen.magicAttackAdd;
    p.effects.physicalDefenseAdd = chosen.physicalDefenseAdd;
    p.effects.magicDefenseAdd = chosen.magicDefenseAdd;
    p.effects.hpAdd = chosen.hpAdd;
    p.effects.mpAdd = chosen.mpAdd;
    p.effects.speedAdd = chosen.speedAdd;
    p.effects.critRateAdd = chosen.critRateAdd;
    p.effects.critEffectAdd = chosen.critEffectAdd;
    p.effects.intelligenceAdd = chosen.intelligenceAdd;
    p.effects.charmAdd = chosen.charmAdd;
    p.effects.loyaltyAdd = chosen.loyaltyAdd;
    p.effects.comprehensionAdd = chosen.comprehensionAdd;
    p.effects.artifactRefiningAdd = chosen.artifactRefiningAdd;
    p.effects.pillRefiningAdd = chosen.pillRefiningAdd;
    p.effects.spiritPlantingAdd = chosen.spiritPlantingAdd;
    p.effects.teachingAdd = chosen.teachingAdd;
    p.effects.moralityAdd = chosen.moralityAdd;
    p.minRealm = minRealmForRarity(item.rarity);
    return p;
}

/// 材料转换（BeastMaterialDatabase.getMaterialByName；未知 → 回退）
inline Material toMaterial(const MerchantItem& item) {
    const auto& templates = gamecore::data::beastMaterialTemplates();
    const auto it = std::find_if(templates.begin(), templates.end(),
        [&](const auto& t) { return t.name == item.name; });
    if (it != templates.end()) {
        Material m;
        m.id = nextItemId();
        m.name = it->name;
        m.rarity = item.rarity;
        m.quantity = 1;
        m.description = it->description;
        m.category = it->category;
        return m;
    }
    // 回退分支（S-18：物品名稳定散列选池，零分区 RNG 消耗）
    const auto& chosen = fallbackPick(templates, item.name, item.rarity);
    Material m;
    m.id = nextItemId();
    m.name = chosen.name;
    m.rarity = item.rarity;
    m.quantity = 1;
    m.description = chosen.description;
    m.category = chosen.category;
    return m;
}

/// 灵草转换（HerbDatabase.getHerbByName；未知 → 回退）
inline Herb toHerb(const MerchantItem& item) {
    const auto& templates = gamecore::data::herbTemplates();
    const auto it = std::find_if(templates.begin(), templates.end(),
        [&](const auto& t) { return t.name == item.name; });
    if (it != templates.end()) {
        Herb h;
        h.id = nextItemId();
        h.name = it->name;
        h.rarity = item.rarity;
        h.description = it->description;
        h.category = it->category;
        h.quantity = 1;
        return h;
    }
    // 回退分支（S-18）
    // 回退分支（S-18：物品名稳定散列选池，零分区 RNG 消耗）
    const auto& chosen = fallbackPick(templates, item.name, item.rarity);
    Herb h;
    h.id = nextItemId();
    h.name = chosen.name;
    h.rarity = item.rarity;
    h.description = chosen.description;
    h.category = chosen.category;
    h.quantity = 1;
    return h;
}

/// 种子转换（HerbDatabase.getSeedByName；未知 → 回退）
inline Seed toSeed(const MerchantItem& item) {
    const auto& templates = gamecore::data::seedTemplates();
    const auto it = std::find_if(templates.begin(), templates.end(),
        [&](const auto& t) { return t.name == item.name; });
    if (it != templates.end()) {
        Seed s;
        s.id = nextItemId();
        s.name = it->name;
        s.rarity = item.rarity;
        s.description = it->description;
        s.growTime = it->growTime;
        s.yield = it->yield;
        s.quantity = 1;
        return s;
    }
    // 回退分支（S-18：物品名稳定散列选池，零分区 RNG 消耗）
    const auto& chosen = fallbackPick(templates, item.name, item.rarity);
    Seed s;
    s.id = nextItemId();
    s.name = chosen.name;
    s.rarity = item.rarity;
    s.description = chosen.description;
    s.growTime = chosen.growTime;
    s.yield = chosen.yield;
    s.quantity = 1;
    return s;
}

// ── 容量检查（InventorySystem.canAddXxx 等价） ─────────────────────

inline int32_t maxStackForType(const std::string& type) {
    return gamecore::system::getMaxStackSize(type);
}

/// 事务内总槽位余量（canAddItemInTransaction：computeSlotCount < computeMaxSlots）
inline bool canAddItemInTransaction(const GameState& state) {
    return gamecore::system::computeSlotCount(state) <
           gamecore::system::computeMaxSlots(state);
}

/// 装备堆叠合并余量（canAddEquipment：同键堆叠未满 或 有总槽位）
inline bool canAddEquipment(const GameState& state, const std::string& name,
                            int32_t rarity, const std::string& slot) {
    const int32_t maxStack = maxStackForType("equipment_stack");
    int32_t totalFree = 0;
    for (const auto& s : state.equipmentStacks) {
        if (s.name == name && s.rarity == rarity && s.slot == slot) {
            totalFree += maxStack - s.quantity;
        }
    }
    return totalFree > 0 || canAddItemInTransaction(state);
}

inline bool canAddManual(const GameState& state, const std::string& name,
                         int32_t rarity, const std::string& type) {
    const int32_t maxStack = maxStackForType("manual_stack");
    int32_t totalFree = 0;
    for (const auto& s : state.manualStacks) {
        if (s.name == name && s.rarity == rarity && s.type == type) {
            totalFree += maxStack - s.quantity;
        }
    }
    return totalFree > 0 || canAddItemInTransaction(state);
}

inline bool canAddPill(const GameState& state, const std::string& name,
                       int32_t rarity, const std::string& category,
                       const std::string& grade) {
    const int32_t maxStack = maxStackForType("pill");
    int32_t totalFree = 0;
    for (const auto& s : state.pills) {
        if (s.name == name && s.rarity == rarity &&
            s.category == category && s.grade == grade) {
            totalFree += maxStack - s.quantity;
        }
    }
    return totalFree > 0 || canAddItemInTransaction(state);
}

inline bool canAddMaterial(const GameState& state, const std::string& name,
                           int32_t rarity, const std::string& category) {
    const int32_t maxStack = maxStackForType("material");
    int32_t totalFree = 0;
    for (const auto& s : state.materials) {
        if (s.name == name && s.rarity == rarity && s.category == category) {
            totalFree += maxStack - s.quantity;
        }
    }
    return totalFree > 0 || canAddItemInTransaction(state);
}

inline bool canAddHerb(const GameState& state, const std::string& name,
                       int32_t rarity, const std::string& category) {
    const int32_t maxStack = maxStackForType("herb");
    int32_t totalFree = 0;
    for (const auto& s : state.herbs) {
        if (s.name == name && s.rarity == rarity && s.category == category) {
            totalFree += maxStack - s.quantity;
        }
    }
    return totalFree > 0 || canAddItemInTransaction(state);
}

inline bool canAddSeed(const GameState& state, const std::string& name,
                       int32_t rarity, int32_t growTime) {
    const int32_t maxStack = maxStackForType("seed");
    int32_t totalFree = 0;
    for (const auto& s : state.seeds) {
        if (s.name == name && s.rarity == rarity && s.growTime == growTime) {
            totalFree += maxStack - s.quantity;
        }
    }
    return totalFree > 0 || canAddItemInTransaction(state);
}

/// 自动购买仓库预检（AutoBuyService.canAddToWarehouse：
/// 先总槽位，再按类型合并空间）
inline bool canAddToWarehouse(const GameState& state, const MerchantItem& item,
                              const EquipmentStack& eq, const ManualStack& mn,
                              const Pill& pill, const Material& mt,
                              const Herb& hb, const Seed& sd) {
    if (!canAddItemInTransaction(state)) return false;
    const std::string& type = item.type;
    if (type == "equipment") return canAddEquipment(state, eq.name, eq.rarity, eq.slot);
    if (type == "manual") return canAddManual(state, mn.name, mn.rarity, mn.type);
    if (type == "pill") return canAddPill(state, pill.name, pill.rarity, pill.category, pill.grade);
    if (type == "material") return canAddMaterial(state, mt.name, mt.rarity, mt.category);
    if (type == "herb") return canAddHerb(state, hb.name, hb.rarity, hb.category);
    if (type == "seed") return canAddSeed(state, sd.name, sd.rarity, sd.growTime);
    if (type == "spiritstone") return true;
    return false;
}

// ── 主流程：executeAutoBuy（Kotlin AutoBuyService.executeAutoBuy 等价） ──

/// 12 月自动购买（仅当月调用；全链零 RNG——含未知名回退的确定性散列选池，
/// S-18 已清偿；overflow 草稿本地收集丢弃——Kotlin 真相源发送溢出邮件）
inline void executeAutoBuy(GameState& state) {
    auto& gd = state.gameData;
    if (gd.autoBuyList.empty()) return;
    if (gd.travelingMerchantItems.empty()) return;

    // 逐条处理（复制商人列表——修改在本地完成，末尾整体写回）
    std::vector<MerchantItem> newMerchantItems = gd.travelingMerchantItems;

    for (const auto& entry : gd.autoBuyList) {
        // 首个匹配（indexOfFirst 语义）
        std::size_t matchIdx = newMerchantItems.size();
        for (std::size_t i = 0; i < newMerchantItems.size(); ++i) {
            if (matches(entry, newMerchantItems[i])) { matchIdx = i; break; }
        }
        if (matchIdx >= newMerchantItems.size()) continue;

        const MerchantItem merchantItem = newMerchantItems[matchIdx];
        if (merchantItem.quantity <= 0) continue;

        // 转换物品（模板路径；未知名走确定性散列回退——S-18 零分区 RNG 消耗）
        EquipmentStack eq;
        ManualStack mn;
        Pill pill;
        Material mt;
        Herb hb;
        Seed sd;
        bool converted = true;
        const std::string& type = merchantItem.type;
        if (type == "equipment") { eq = toEquipment(merchantItem); }
        else if (type == "manual") { mn = toManual(merchantItem); }
        else if (type == "pill") { pill = toPill(merchantItem); }
        else if (type == "material") { mt = toMaterial(merchantItem); }
        else if (type == "herb") { hb = toHerb(merchantItem); }
        else if (type == "seed") { sd = toSeed(merchantItem); }
        else if (type != "spiritstone") { converted = false; }
        if (!converted) continue;

        // 仓库容量检查
        if (!canAddToWarehouse(state, merchantItem, eq, mn, pill, mt, hb, sd)) continue;

        // 可买数量
        const int32_t buyQty = calculateBuyQuantity(
            gd.spiritStones, merchantItem.price, merchantItem.quantity);
        if (buyQty <= 0) continue;
        const int64_t cost = merchantItem.price * buyQty;

        // 钱包扣除（LOW/Purchase/MerchantTrade；失败跳过）
        const auto deduct = gamecore::system::SpiritStoneWallet::deduct(
            gd, cost, gamecore::system::SpiritStoneGrade::LOW,
            "Purchase", "MerchantTrade");
        if (deduct.status != gamecore::system::DeductStatus::kSuccess) continue;

        // 减少商人库存
        const int32_t remaining = merchantItem.quantity - buyQty;
        if (remaining <= 0) {
            newMerchantItems.erase(newMerchantItems.begin() +
                                   static_cast<std::ptrdiff_t>(matchIdx));
        } else {
            newMerchantItems[matchIdx].quantity = remaining;
        }

        // 入库（overflow 草稿本地收集——自动类路径溢出不抑制，Kotlin 真相源
        // 发邮件；C++ 月结上下文无邮件通道，草稿丢弃——S-18 边界）
        gamecore::system::OverflowMailCollector overflowMail;
        if (type == "equipment") {
            eq.quantity = buyQty;
            gamecore::system::addEquipmentStack(state, eq, overflowMail, "merchant", false);
        } else if (type == "manual") {
            mn.quantity = buyQty;
            gamecore::system::addManualStack(state, mn, overflowMail, "merchant", false);
        } else if (type == "pill") {
            pill.quantity = buyQty;
            gamecore::system::addPill(state, pill, overflowMail, "merchant", false);
        } else if (type == "material") {
            mt.quantity = buyQty;
            gamecore::system::addMaterial(state, mt, overflowMail, "merchant", false);
        } else if (type == "herb") {
            hb.quantity = buyQty;
            gamecore::system::addHerb(state, hb, overflowMail, "merchant", false);
        } else if (type == "seed") {
            sd.quantity = buyQty;
            gamecore::system::addSeed(state, sd, overflowMail, "merchant", false);
        } else if (type == "spiritstone") {
            if (merchantItem.name == "中品灵石") {
                gd.midGradeSpiritStones += buyQty;
            } else if (merchantItem.name == "上品灵石") {
                gd.highGradeSpiritStones += buyQty;
            }
        }
    }

    gd.travelingMerchantItems = std::move(newMerchantItems);
}

}  // namespace gamecore::system::merchant_settle
