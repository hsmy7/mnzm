// ============================================================
// secret_realm_session.h — 秘境探索交互会话域
//
// 等价复刻 Kotlin SecretRealmService 交互会话域（startSession/chooseOption/
// endSession/processYearlySpawn）+ BagItemReconstructor 袋条目重建 +
// InventorySystem.materializeDiscipleBagAndMarkDead 死亡袋物化。
// 秘境纯逻辑原语（事件生成/遗迹结算/妖兽 loot/体力/位置/派遣）在
// secret_realm.h，战斗组装/执行在 mission_completion.h +
// battle::executeBattle（对拍锁定）。
//
// 转发模式（设计要点①）：SECRET_REALM_START/CHOOSE/END/
// YEARLY_SPAWN 四个 ActionId 经 nativeExecute + tryExecuteNative 交互域转发，
// Kotlin 回退保留（双实现并行契约）。
//
// 设计要点落位：
//   ② chooseOption 信封回传战斗终态（BattleResult 状态段）+ rounds 动作序列；
//      battleLogs 经 recordPlayerBattle 在 Kotlin 重建（展示通道非协议）。
//   ③ 战报 message 文本 = Kotlin 确定性摘要（BattleExecutionRouter.rebuildAction
//      先例——原 BattleDescriptionGenerator 为 JVM Random 随机措辞不进 C++）；
//      resultText（结算文本）为确定性字面量，C++ 直出与 Kotlin 回退臂逐字一致。
//   ④ 遗迹秘宝候选模板 = C++ 侧六类模板源（buildTypeCandidates：equipment/
//      manual/pill/material/herb/seed 主表保序 rarity 过滤 == Kotlin registry
//      getByRarity 语义——模板表同源生成，顺序一致即 RNG 选取一致）。
//   ⑤ 死亡 aftermath：本文件袋物化（materializeDiscipleBagAndMarkDead 等价——
//      实例/堆叠重建入仓 + 实例表删除 + markDead）+ 溢出邮件草稿收集；
//      gate 释放/邮件发送/丧亲哀伤等平台效应保留 Kotlin（信封回传
//      releasedMemberIds/deadIds/溢出草稿）。
//
// RNG 契约（对拍命门，SECRET_REALM 分区）：
//   - startSession：generateBeastEvent 内部消费（1×nextDouble 根数 + 4×nextInt
//     洗牌 + 妖兽 roll——secret_realm.h 同源）
//   - chooseOption 妖兽分支：远离 1×nextDouble（察觉判定）/ 偷袭 1×nextDouble
//     （成功判定）→ buildBeastPreGenStats 内部消费（属性方差）→ 战斗执行
//     BATTLE 分区（battle::executeBattle 与 Kotlin executeBattleWithTimeout 同区）
//     → 胜利 rollBeastLoot / 失败 applyLootLoss（SECRET_REALM）
//   - 遗迹/方向/AI 避让：resolveSecretRealmRuinsExplore / rollNextEvent /
//     零消费——secret_realm.h 原语语义不变
//   - AI 交战：无 preGen（PvP 满血）→ 战斗 BATTLE → 胜利 generateRuinsTreasure /
//     失败 applyLootLoss（SECRET_REALM）
//   - id 生成字段（EquipmentStack.id 等）：Kotlin UUID 非确定性镜像字段——
//     C++ 侧 nextItemId 计数器占位（新生儿/世界关卡 id 契约同族）
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/data/beast_config.h"
#include "gamecore/data/beast_material_db.h"
#include "gamecore/data/equipment_db.h"
#include "gamecore/data/herb_db.h"
#include "gamecore/data/manual_db.h"
#include "gamecore/data/recipe_db.h"
#include "gamecore/system/ai_sect_ops.h"        // aiPrepareDisciplesForBattle
#include "gamecore/system/battle_execution.h"   // battle::executeBattle
#include "gamecore/system/death_handler.h"      // markDead
#include "gamecore/system/economy.h"            // SpiritStoneWallet
#include "gamecore/system/inventory.h"          // addXxx + OverflowMailCollector + nextItemId
#include "gamecore/system/mission_completion.h" // discipleToCombatant
#include "gamecore/system/secret_realm.h"       // 原语 + 常量 + SecretRealmTypeCandidates
#include "gamecore/system/settlement_detail.h"  // recordGameEvent

namespace gamecore::system::sr_session {

using gamecore::state::Disciple;
using gamecore::state::DiscipleStore;
using gamecore::state::GameState;
using gamecore::state::SecretRealmBackpack;
using gamecore::system::SecretRealmTypeCandidates;

// ── 结束原因名（Kotlin SecretRealmEndReason.name）────────────────
inline constexpr const char* kEndExplorerEnd = "EXPLORER_END";
inline constexpr const char* kEndExhausted = "EXHAUSTED";
inline constexpr const char* kEndWipeout = "WIPEOUT";
inline constexpr const char* kEndExpired = "EXPIRED";

namespace detail {

// ── 六类模板候选源（设计要点④）──────────────────────────────────
// Kotlin SecretRealmEventGenerator 候选语义：getByRarity(rarity) 过滤主表保序，
// 仅取 (id, name)。C++ 模板表与 Kotlin registry 同源生成（gen-templates.mjs），
// 主表顺序一致 → 同 rarity 池顺序一致 → pickTemplate（nextInt(pool.size)）同选。
inline SecretRealmTypeCandidates buildTypeCandidates() {
    SecretRealmTypeCandidates out;
    const auto put = [&out](const std::string& type, int32_t rarity,
                            const std::string& id, const std::string& name) {
        out[type][rarity].emplace_back(id, name);
    };
    for (const auto& t : gamecore::data::equipmentTemplates()) {
        put("equipment", t.rarity, t.id, t.name);
    }
    for (const auto& t : gamecore::data::manualTemplates()) {
        put("manual", t.rarity, t.id, t.name);
    }
    for (const auto& t : gamecore::data::detail::pillTemplates()) {
        put("pill", t.rarity, t.id, t.name);
    }
    for (const auto& t : gamecore::data::beastMaterialTemplates()) {
        put("material", t.rarity, t.id, t.name);
    }
    for (const auto& t : gamecore::data::herbTemplates()) {
        put("herb", t.rarity, t.id, t.name);
    }
    for (const auto& t : gamecore::data::seedTemplates()) {
        put("seed", t.rarity, t.id, t.name);
    }
    return out;
}

// ── 秘宝描述符实例化（Kotlin SecretRealmRuinsResolver.instantiateRuinsRewards）──
// 模板 id 查表；篡改档未知 id 静默跳过。实例 id 为 Kotlin UUID 镜像字段——
// C++ 侧 nextItemId 计数器占位（对拍 diff 面排除，新生儿 id 契约同族）。

inline const gamecore::data::EquipmentTemplate* equipmentTemplateById(
    const std::string& id) {
    for (const auto& t : gamecore::data::equipmentTemplates()) {
        if (t.id == id) return &t;
    }
    return nullptr;
}

inline const gamecore::data::HerbTemplate* herbTemplateById(const std::string& id) {
    for (const auto& t : gamecore::data::herbTemplates()) {
        if (t.id == id) return &t;
    }
    return nullptr;
}

inline const gamecore::data::SeedTemplate* seedTemplateById(const std::string& id) {
    for (const auto& t : gamecore::data::seedTemplates()) {
        if (t.id == id) return &t;
    }
    return nullptr;
}

inline state::SecretRealmBackpack instantiateRewards(
    const std::vector<state::SecretRealmRewardItem>& rewards,
    state::SecretRealmBackpack backpack) {
    for (const auto& item : rewards) {
        if (item.type == "equipment") {
            const auto* tpl = equipmentTemplateById(item.itemId);
            if (tpl == nullptr) continue;
            state::EquipmentStack s;
            s.id = nextItemId("gc-equip-stack");
            s.name = tpl->name;
            s.rarity = tpl->rarity;
            s.description = tpl->description;
            s.slot = tpl->slot;
            s.physicalAttack = tpl->physicalAttack;
            s.magicAttack = tpl->magicAttack;
            s.physicalDefense = tpl->physicalDefense;
            s.magicDefense = tpl->magicDefense;
            s.speed = tpl->speed;
            s.hp = tpl->hp;
            s.mp = tpl->mp;
            s.critChance = tpl->critChance;
            s.minRealm = mission_settle::detail::realmMinForRarity(tpl->rarity);
            s.quantity = 1;
            backpack.equipment.push_back(std::move(s));
        } else if (item.type == "manual") {
            const auto* tpl = gamecore::data::manualById(item.itemId);
            if (tpl == nullptr) continue;
            state::ManualStack s;
            s.id = nextItemId("gc-manual-stack");
            s.name = tpl->name;
            s.rarity = tpl->rarity;
            s.description = tpl->description;
            s.type = tpl->type;
            s.stats = tpl->stats;
            s.skillName = tpl->skillName.empty()
                              ? std::nullopt
                              : std::optional<std::string>(tpl->skillName);
            s.skillDescription = tpl->skillDescription.empty()
                                     ? std::nullopt
                                     : std::optional<std::string>(tpl->skillDescription);
            s.skillType = tpl->skillType;
            s.skillDamageType = tpl->skillDamageType;
            s.skillHits = tpl->skillHits;
            s.skillDamageMultiplier = tpl->skillDamageMultiplier;
            s.skillCooldown = tpl->skillCooldown;
            s.skillMpCost = tpl->skillMpCost;
            s.quantity = 1;
            backpack.manuals.push_back(std::move(s));
        } else if (item.type == "pill") {
            const auto tpl = gamecore::data::detail::pillTemplateById(item.itemId);
            if (!tpl.has_value()) continue;
            state::Pill p;
            p.id = nextItemId("gc-pill");
            p.name = tpl->name;
            p.rarity = tpl->rarity;
            p.description = tpl->description;
            p.category = tpl->category;
            // Kotlin PillTemplate.grade 直接携带；C++ 模板 grade 由 id 后缀
            //（_low/_medium/_high）反推——production.h producePill 同口径
            const std::string& tplId = tpl->id;
            if (tplId.size() >= 4 && tplId.compare(tplId.size() - 4, 4, "_low") == 0) {
                p.grade = "LOW";
            } else if (tplId.size() >= 7 &&
                       tplId.compare(tplId.size() - 7, 7, "_medium") == 0) {
                p.grade = "MEDIUM";
            } else {
                p.grade = "HIGH";
            }
            p.pillType = tpl->pillType;
            p.effects.breakthroughChance = tpl->breakthroughChance;
            p.effects.targetRealm = tpl->targetRealm;
            p.effects.isAscension = tpl->isAscension;
            p.effects.cultivationSpeedPercent = tpl->cultivationSpeedPercent;
            p.effects.skillExpSpeedPercent = tpl->skillExpSpeedPercent;
            p.effects.nurtureSpeedPercent = tpl->nurtureSpeedPercent;
            p.effects.cultivationAdd = tpl->cultivationAdd;
            p.effects.skillExpAdd = tpl->skillExpAdd;
            p.effects.nurtureAdd = tpl->nurtureAdd;
            p.effects.duration = tpl->duration;
            p.effects.cannotStack = tpl->cannotStack;
            p.effects.physicalAttackAdd = tpl->physicalAttackAdd;
            p.effects.magicAttackAdd = tpl->magicAttackAdd;
            p.effects.physicalDefenseAdd = tpl->physicalDefenseAdd;
            p.effects.magicDefenseAdd = tpl->magicDefenseAdd;
            p.effects.hpAdd = tpl->hpAdd;
            p.effects.mpAdd = tpl->mpAdd;
            p.effects.speedAdd = tpl->speedAdd;
            p.effects.critRateAdd = tpl->critRateAdd;
            p.effects.critEffectAdd = tpl->critEffectAdd;
            p.effects.extendLife = tpl->extendLife;
            p.effects.intelligenceAdd = tpl->intelligenceAdd;
            p.effects.charmAdd = tpl->charmAdd;
            p.effects.loyaltyAdd = tpl->loyaltyAdd;
            p.effects.comprehensionAdd = tpl->comprehensionAdd;
            p.effects.artifactRefiningAdd = tpl->artifactRefiningAdd;
            p.effects.pillRefiningAdd = tpl->pillRefiningAdd;
            p.effects.spiritPlantingAdd = tpl->spiritPlantingAdd;
            p.effects.teachingAdd = tpl->teachingAdd;
            p.effects.moralityAdd = tpl->moralityAdd;
            p.effects.miningAdd = tpl->miningAdd;
            p.minRealm = tpl->minRealm;
            p.quantity = 1;
            backpack.pills.push_back(std::move(p));
        } else if (item.type == "material") {
            const auto* tpl = gamecore::data::beastMaterialById(item.itemId);
            state::Material m;
            m.id = nextItemId("gc-material");
            m.name = tpl != nullptr ? tpl->name : item.name;
            m.rarity = item.rarity;
            m.description = tpl != nullptr ? tpl->description : "";
            m.category = tpl != nullptr ? tpl->category : "BEAST_HIDE";
            m.quantity = 1;
            backpack.materials.push_back(std::move(m));
        } else if (item.type == "herb") {
            const auto* tpl = herbTemplateById(item.itemId);
            if (tpl == nullptr) continue;
            state::Herb h;
            h.id = nextItemId("gc-herb");
            h.name = tpl->name;
            h.rarity = tpl->rarity;
            h.description = tpl->description;
            h.category = tpl->category;
            h.quantity = std::max(item.quantity, 1);
            backpack.herbs.push_back(std::move(h));
        } else if (item.type == "seed") {
            const auto* tpl = seedTemplateById(item.itemId);
            if (tpl == nullptr) continue;
            state::Seed s;
            s.id = nextItemId("gc-seed");
            s.name = tpl->name;
            s.rarity = tpl->rarity;
            s.description = tpl->description;
            s.growTime = tpl->growTime;
            s.yield = tpl->yield;
            s.quantity = std::max(item.quantity, 1);
            backpack.seeds.push_back(std::move(s));
        }
        // 未知类型原样跳过（Kotlin else -> backpack）
    }
    return backpack;
}

// ── 袋条目物化（Kotlin BagItemReconstructor + InventorySystem 物化段）──────

/// EquipmentInstance → EquipmentStack（Kotlin instance.toStack(quantity=1)——
/// 实例字段直拷，id 新分配）
inline state::EquipmentStack equipmentInstanceToStack(
    const state::EquipmentInstance& inst) {
    state::EquipmentStack s;
    s.id = nextItemId("gc-equip-stack");
    s.slotId = inst.slotId;
    s.name = inst.name;
    s.rarity = inst.rarity;
    s.description = inst.description;
    s.slot = inst.slot;
    s.physicalAttack = inst.physicalAttack;
    s.magicAttack = inst.magicAttack;
    s.physicalDefense = inst.physicalDefense;
    s.magicDefense = inst.magicDefense;
    s.speed = inst.speed;
    s.hp = inst.hp;
    s.mp = inst.mp;
    s.critChance = inst.critChance;
    s.minRealm = inst.minRealm;
    s.quantity = 1;
    return s;
}

/// ManualInstance → ManualStack（Kotlin instance.toStack 同义）
inline state::ManualStack manualInstanceToStack(const state::ManualInstance& inst) {
    state::ManualStack s;
    static_cast<state::ManualBase&>(s) = static_cast<const state::ManualBase&>(inst);
    s.id = nextItemId("gc-manual-stack");
    s.quantity = 1;
    s.isLocked = false;
    return s;
}

/// 堆叠条目模板重建（Kotlin BagItemReconstructor.reconstruct——按 name 查模板
/// 补齐 stats/category 等库内字段；查不到返回 false 随丢弃路径）。
/// 成功时 item 写入对应类型的堆叠载体（reconstructed.outStack 系列）。
inline bool reconstructStackedItem(const state::StorageBagItem& item,
                                   state::EquipmentStack* outEquipment,
                                   state::ManualStack* outManual,
                                   state::Pill* outPill,
                                   state::Herb* outHerb,
                                   state::Seed* outSeed,
                                   state::Material* outMaterial) {
    if (item.quantity <= 0) return false;   // 篡改防御：非法数量拒绝物化
    const std::string type = item.itemType;
    auto toLower = [](std::string v) {
        std::transform(v.begin(), v.end(), v.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        return v;
    };
    const std::string t = toLower(type);
    if (t == "equipment" || t == "equipment_stack") {
        if (outEquipment == nullptr) return false;
        const gamecore::data::EquipmentTemplate* tpl = nullptr;
        for (const auto& cand : gamecore::data::equipmentTemplates()) {
            if (cand.name == item.name) { tpl = &cand; break; }
        }
        if (tpl == nullptr) return false;
        state::EquipmentStack s;
        s.id = nextItemId("gc-equip-stack");
        s.name = tpl->name;
        s.slot = tpl->slot;
        s.rarity = tpl->rarity;
        s.physicalAttack = tpl->physicalAttack;
        s.magicAttack = tpl->magicAttack;
        s.physicalDefense = tpl->physicalDefense;
        s.magicDefense = tpl->magicDefense;
        s.speed = tpl->speed;
        s.hp = tpl->hp;
        s.mp = tpl->mp;
        s.description = tpl->description;
        s.minRealm = item.stackedData.has_value() && item.stackedData->minRealm > 0
                         ? item.stackedData->minRealm
                         : mission_settle::detail::realmMinForRarity(tpl->rarity);
        s.quantity = std::max(item.quantity, 1);
        *outEquipment = std::move(s);
        return true;
    }
    if (t == "manual" || t == "manual_stack") {
        if (outManual == nullptr) return false;
        const gamecore::data::ManualTemplate* tpl = nullptr;
        for (const auto& cand : gamecore::data::manualTemplates()) {
            if (cand.name == item.name) { tpl = &cand; break; }
        }
        if (tpl == nullptr) return false;
        state::ManualStack s;
        s.id = nextItemId("gc-manual-stack");
        s.name = tpl->name;
        s.rarity = tpl->rarity;
        s.description = tpl->description;
        s.type = tpl->type;
        s.stats = tpl->stats;
        s.skillName = tpl->skillName.empty()
                          ? std::nullopt
                          : std::optional<std::string>(tpl->skillName);
        s.skillDescription = tpl->skillDescription.empty()
                                 ? std::nullopt
                                 : std::optional<std::string>(tpl->skillDescription);
        s.skillType = tpl->skillType;
        s.skillDamageType = tpl->skillDamageType;
        s.skillHits = tpl->skillHits;
        s.skillDamageMultiplier = tpl->skillDamageMultiplier;
        s.skillCooldown = tpl->skillCooldown;
        s.skillMpCost = tpl->skillMpCost;
        s.quantity = std::max(item.quantity, 1);
        *outManual = std::move(s);
        return true;
    }
    if (t == "pill") {
        if (outPill == nullptr) return false;
        std::optional<gamecore::data::detail::PillTemplateSpec> tpl =
            gamecore::data::detail::pillTemplateById(item.itemId);
        if (!tpl.has_value()) {
            for (const auto& cand : gamecore::data::detail::pillTemplates()) {
                if (cand.name == item.name) { tpl = cand; break; }
            }
        }
        if (!tpl.has_value()) return false;
        state::Pill p;
        p.id = nextItemId("gc-pill");
        p.name = tpl->name;
        p.rarity = tpl->rarity;
        p.description = tpl->description;
        p.category = tpl->category;
        const std::string& tplId = tpl->id;
        if (tplId.size() >= 4 && tplId.compare(tplId.size() - 4, 4, "_low") == 0) {
            p.grade = "LOW";
        } else if (tplId.size() >= 7 &&
                   tplId.compare(tplId.size() - 7, 7, "_medium") == 0) {
            p.grade = "MEDIUM";
        } else {
            p.grade = "HIGH";
        }
        p.pillType = tpl->pillType;
        p.effects.breakthroughChance = tpl->breakthroughChance;
        p.effects.targetRealm = tpl->targetRealm;
        p.effects.isAscension = tpl->isAscension;
        p.effects.cultivationSpeedPercent = tpl->cultivationSpeedPercent;
        p.effects.skillExpSpeedPercent = tpl->skillExpSpeedPercent;
        p.effects.nurtureSpeedPercent = tpl->nurtureSpeedPercent;
        p.effects.cultivationAdd = tpl->cultivationAdd;
        p.effects.skillExpAdd = tpl->skillExpAdd;
        p.effects.nurtureAdd = tpl->nurtureAdd;
        p.effects.duration = tpl->duration;
        p.effects.cannotStack = tpl->cannotStack;
        p.effects.physicalAttackAdd = tpl->physicalAttackAdd;
        p.effects.magicAttackAdd = tpl->magicAttackAdd;
        p.effects.physicalDefenseAdd = tpl->physicalDefenseAdd;
        p.effects.magicDefenseAdd = tpl->magicDefenseAdd;
        p.effects.hpAdd = tpl->hpAdd;
        p.effects.mpAdd = tpl->mpAdd;
        p.effects.speedAdd = tpl->speedAdd;
        p.effects.critRateAdd = tpl->critRateAdd;
        p.effects.critEffectAdd = tpl->critEffectAdd;
        p.effects.extendLife = tpl->extendLife;
        p.effects.intelligenceAdd = tpl->intelligenceAdd;
        p.effects.charmAdd = tpl->charmAdd;
        p.effects.loyaltyAdd = tpl->loyaltyAdd;
        p.effects.comprehensionAdd = tpl->comprehensionAdd;
        p.effects.artifactRefiningAdd = tpl->artifactRefiningAdd;
        p.effects.pillRefiningAdd = tpl->pillRefiningAdd;
        p.effects.spiritPlantingAdd = tpl->spiritPlantingAdd;
        p.effects.teachingAdd = tpl->teachingAdd;
        p.effects.moralityAdd = tpl->moralityAdd;
        p.effects.miningAdd = tpl->miningAdd;
        p.minRealm = tpl->minRealm;
        p.quantity = std::max(item.quantity, 1);
        *outPill = std::move(p);
        return true;
    }
    if (t == "herb") {
        if (outHerb == nullptr) return false;
        const gamecore::data::HerbTemplate* tpl = nullptr;
        for (const auto& cand : gamecore::data::herbTemplates()) {
            if (cand.name == item.name) { tpl = &cand; break; }
        }
        state::Herb h;
        h.id = nextItemId("gc-herb");
        h.name = item.name;
        h.rarity = item.rarity;
        h.description = tpl != nullptr ? tpl->description : "";
        h.category = tpl != nullptr ? tpl->category : "";
        h.quantity = std::max(item.quantity, 1);
        *outHerb = std::move(h);
        return true;
    }
    if (t == "seed") {
        if (outSeed == nullptr) return false;
        const gamecore::data::SeedTemplate* tpl = nullptr;
        for (const auto& cand : gamecore::data::seedTemplates()) {
            if (cand.name == item.name) { tpl = &cand; break; }
        }
        state::Seed s;
        s.id = nextItemId("gc-seed");
        s.name = item.name;
        s.rarity = item.rarity;
        s.description = tpl != nullptr ? tpl->description : "";
        s.growTime = tpl != nullptr ? tpl->growTime : 0;
        s.yield = tpl != nullptr ? tpl->yield : 0;
        s.quantity = std::max(item.quantity, 1);
        *outSeed = std::move(s);
        return true;
    }
    if (t == "material") {
        if (outMaterial == nullptr) return false;
        const gamecore::data::BeastMaterialTemplate* tpl = nullptr;
        for (const auto& cand : gamecore::data::beastMaterialTemplates()) {
            if (cand.name == item.name) { tpl = &cand; break; }
        }
        state::Material m;
        m.id = nextItemId("gc-material");
        m.name = item.name;
        m.rarity = item.rarity;
        m.description = tpl != nullptr ? tpl->description : "";
        m.category = tpl != nullptr ? tpl->category : "BEAST_HIDE";
        m.quantity = std::max(item.quantity, 1);
        *outMaterial = std::move(m);
        return true;
    }
    return false;
}

/// 弟子死亡袋物化 + markDead（Kotlin InventorySystem.
/// materializeDiscipleBagAndMarkDead 等价）：袋条目 → 实例物化（实例表删除防
/// 双持有）/ 堆叠模板重建 → 入仓（溢出自动转邮件——items 不丢）→ 清袋 → 死亡
/// 三字段 + 年度计数。弟子不存在 → 静默跳过。
inline void materializeDiscipleBagAndMarkDead(
    GameState& state, const std::string& discipleId, int32_t deathYear,
    gamecore::system::OverflowMailCollector& overflowMail) {
    DiscipleStore& ds = state.disciples;
    const auto rowOpt = ds.rowOf(discipleId);
    if (!rowOpt.has_value()) return;
    const std::size_t row = *rowOpt;
    const bool wasAlive = ds.isAlive[row] == 1;
    if (!ds.storageBagItems[row].empty()) {
        std::vector<state::StorageBagItem> remaining;
        for (auto& item : ds.storageBagItems[row]) {
            bool materialized = false;
            if (item.equipmentInstance.has_value()) {
                const auto stack = equipmentInstanceToStack(*item.equipmentInstance);
                const auto r = gamecore::system::addEquipmentStack(
                    state, stack, overflowMail, "disciple_death",
                    /*overflowMailSuppressed=*/false);
                // Success/Partial 入仓完成；Failure(Full) 时 handleOverflow 已把
                // 物品转邮件——实例删除防双持有语义一致
                const bool completed =
                    r.status != gamecore::system::InventoryStatus::kFailure ||
                    r.overflow >= 1;
                if (completed) {
                    auto& eq = state.equipmentInstances;
                    eq.erase(std::remove_if(eq.begin(), eq.end(),
                                            [&](const state::EquipmentInstance& e) {
                                                return e.id ==
                                                       item.equipmentInstance->id;
                                            }),
                             eq.end());
                    materialized = true;
                }
            } else if (item.manualInstance.has_value()) {
                const auto stack = manualInstanceToStack(*item.manualInstance);
                const auto r = gamecore::system::addManualStack(
                    state, stack, overflowMail, "disciple_death",
                    /*overflowMailSuppressed=*/false);
                const bool completed =
                    r.status != gamecore::system::InventoryStatus::kFailure ||
                    r.overflow >= 1;
                if (completed) {
                    auto& mn = state.manualInstances;
                    mn.erase(std::remove_if(mn.begin(), mn.end(),
                                            [&](const state::ManualInstance& m) {
                                                return m.id ==
                                                       item.manualInstance->id;
                                            }),
                             mn.end());
                    materialized = true;
                }
            } else if (item.stackedData.has_value()) {
                state::EquipmentStack eqs;
                state::ManualStack mns;
                state::Pill ps;
                state::Herb hs;
                state::Seed ss;
                state::Material ms;
                if (reconstructStackedItem(item, &eqs, &mns, &ps, &hs, &ss, &ms)) {
                    const std::string t = [&] {
                        std::string v = item.itemType;
                        std::transform(v.begin(), v.end(), v.begin(),
                                       [](unsigned char c) {
                                           return static_cast<char>(std::tolower(c));
                                       });
                        return v;
                    }();
                    if (t == "equipment" || t == "equipment_stack") {
                        const auto r = gamecore::system::addEquipmentStack(
                            state, eqs, overflowMail, "disciple_death", false);
                        materialized =
                            r.status != gamecore::system::InventoryStatus::kFailure;
                    } else if (t == "manual" || t == "manual_stack") {
                        const auto r = gamecore::system::addManualStack(
                            state, mns, overflowMail, "disciple_death", false);
                        materialized =
                            r.status != gamecore::system::InventoryStatus::kFailure;
                    } else if (t == "pill") {
                        const auto r = gamecore::system::addPill(
                            state, ps, overflowMail, "disciple_death", false);
                        materialized =
                            r.status != gamecore::system::InventoryStatus::kFailure;
                    } else if (t == "herb") {
                        const auto r = gamecore::system::addHerb(
                            state, hs, overflowMail, "disciple_death", false);
                        materialized =
                            r.status != gamecore::system::InventoryStatus::kFailure;
                    } else if (t == "seed") {
                        const auto r = gamecore::system::addSeed(
                            state, ss, overflowMail, "disciple_death", false);
                        materialized =
                            r.status != gamecore::system::InventoryStatus::kFailure;
                    } else if (t == "material") {
                        const auto r = gamecore::system::addMaterial(
                            state, ms, overflowMail, "disciple_death", false);
                        materialized =
                            r.status != gamecore::system::InventoryStatus::kFailure;
                    }
                }
            }
            // 未物化条目（payload 空）或物化失败：随弟子删除（Kotlin 同义——
            // 失败保留实例属实例轨道；堆叠轨道幂等清袋不重试）
            (void)materialized;
        }
        ds.storageBagItems[row].clear();
    }
    gamecore::system::markDead(ds, discipleId, deathYear,
                               state.gameData.annualDeceasedDisciples);
    (void)wasAlive;  // C++ markDead 无条件计数——战斗死亡路径 wasAlive 恒真，
                     // 与 Kotlin wasAlive 显式 +1 等价（见文件头）
}

}  // namespace detail

// ── 选择结算载体（信封状态段——Kotlin SecretRealmChoiceResult 对应物）────

/// 会话内战斗执行结果（Kotlin SecretRealmBattleOutcome 对应物）
struct SecretRealmBattleOutcome {
    bool hasBattle = false;
    bool victory = false;
    gamecore::battle::BattleResult battle;   // 终态 + rounds（展示通道由 Kotlin 重建）
    std::string battleType;                  // "PVE" / "PVP"（recordPlayerBattle 用）
    std::string defenderName;                // 战报标题段（"狂暴虎妖 × 2"/"X探索队伍"）
    std::string details;                     // 战报 details（确定性部分）
    int32_t beastsDefeated = 0;
    int32_t teamCasualties = 0;              // 新增濒死/死亡数（战报口径）
    state::SecretRealmBackpack backpack;
    state::SecretRealmEventParams params;
    std::vector<state::SecretRealmMemberState> members;
    std::set<std::string> deadIds;
    std::string resultText;
};

/// 选择结算载体（Kotlin SecretRealmBeastChoiceResolution）
struct SecretRealmChoiceResolution {
    std::string resultText;
    bool enteredCombat = false;
    std::optional<SecretRealmBattleOutcome> battle;
    state::SecretRealmBackpack backpack;
    std::vector<state::SecretRealmMemberState> members;
    std::set<std::string> deadIds;
    state::SecretRealmEventParams params;
    state::SecretRealmEventRecord nextEvent;
};

/// chooseOption 总出口（Kotlin SecretRealmChoiceResult）
struct SecretRealmChoiceOutcome {
    bool ok = false;                 // false = 校验错误（errorText 携带文案）
    std::string errorText;
    bool sessionEnded = false;
    std::string message;             // 结束 → 覆没/耗尽文案；继续 → resultText
    std::set<std::string> releasedMemberIds;   // 会话结束 gate 释放面（平台效应草稿）
    // 战斗死亡袋物化溢出邮件草稿（平台效应——Kotlin 经同一解析/投递通道发送）
    std::vector<gamecore::system::OverflowDraft> overflowDrafts;
    SecretRealmChoiceResolution resolution;    // enteredCombat/victory/deadIds 供 UI
};

namespace detail {

/// 妖兽战斗体组装（Kotlin createBeast preGenStats 分支——类型按名查表 +
/// resolveBeastStats 钳制；S8 aiCreateBattle preGen 分支的按名泛化版）
inline gamecore::battle::Combatant secretRealmBeast(
    int32_t index, const std::string& beastTypeName, int32_t beastRealm,
    const gamecore::system::SecretRealmBeastPreGenStats& pre) {
    const auto& types = gamecore::data::beastTypes();
    const gamecore::data::BeastTypeSpec* type = &types.front();
    for (const auto& t : types) {
        if (beastTypeName == t.name) { type = &t; break; }
    }
    const int32_t realmIndex = std::min(std::max(beastRealm, 0), 9);
    gamecore::battle::Combatant b;
    b.id = "beast_" + std::to_string(index);
    b.name = std::string(type->prefix) + type->name;
    b.side = gamecore::battle::CombatantSide::kAttacker;
    b.hp = std::min(std::max(pre.maxHp, 1), 10000000);
    b.maxHp = b.hp;
    b.mp = std::max(pre.maxMp, 0);
    b.maxMp = b.mp;
    b.physicalAttack = std::max(pre.physicalAttack, 0);
    b.magicAttack = std::max(pre.magicAttack, 0);
    b.physicalDefense = std::max(pre.physicalDefense, 0);
    b.magicDefense = std::max(pre.magicDefense, 0);
    b.speed = std::max(pre.speed, 0);
    b.critRate = 0.05 + realmIndex * 0.01;
    b.realm = realmIndex;
    b.realmLayer = pre.realmLayer;
    b.element = type->element;
    b.isBeast = true;
    for (const auto& sc : *type->skills) {
        gamecore::battle::CombatSkill s;
        s.name = sc.name;
        s.skillType = sc.skillType;
        s.damageType = sc.damageType;
        s.damageMultiplier = sc.damageMultiplier;
        s.mpCost = sc.mpCost;
        s.cooldown = sc.cooldown;
        s.hits = sc.hits;
        s.healPercent = sc.healPercent;
        s.buffType = sc.buffType;
        s.buffValue = sc.buffValue;
        s.buffDuration = sc.buffDuration;
        s.buffs = sc.buffs;
        s.isAoe = sc.isAoe;
        s.targetScope = sc.targetScope;
        s.shieldPercent = sc.shieldPercent;
        s.turnAdvancePercent = sc.turnAdvancePercent;
        b.skills.push_back(s);
    }
    return b;
}

/// 熟练度嵌套 map（Kotlin manualProficiencies.mapValues { associateBy manualId }；
/// associateBy 对重复 manualId 后写覆盖——S5 executeMissionBattle 同口径）
inline std::map<std::string, std::map<std::string, gamecore::state::ManualProficiencyData>>
proficienciesByDisciple(const GameState& state) {
    std::map<std::string, std::map<std::string, gamecore::state::ManualProficiencyData>>
        proficiencies;
    for (const auto& [discipleId, list] : state.gameData.manualProficiencies) {
        std::map<std::string, gamecore::state::ManualProficiencyData> byManualId;
        for (const auto& p : list) {
            byManualId[p.manualId] = p;
        }
        proficiencies.emplace(discipleId, std::move(byManualId));
    }
    return proficiencies;
}

/// 战斗成员写回（Kotlin writeBackBattleMembers）：幸存者 HP 写表（战斗口径
/// maxHp 钳制）；首次阵亡 → 重伤濒死；濒死再阵亡 → 永久死亡（袋物化 +
/// markDead——设计要点⑤状态段）。
struct WriteBackResult {
    std::vector<state::SecretRealmMemberState> members;
    std::set<std::string> deadIds;
    int32_t teamCasualties = 0;   // 新增濒死/死亡 - 原有濒死（Kotlin teamCasualties 口径）
};

inline WriteBackResult writeBackBattleMembers(GameState& state,
                       const std::vector<state::SecretRealmMemberState>& members,
                       const gamecore::battle::BattleResult& result,
                       gamecore::system::OverflowMailCollector& overflowMail) {
    DiscipleStore& ds = state.disciples;
    const int32_t year = state.gameData.gameYear;
    std::map<std::string, std::pair<int32_t, int32_t>> hpMap;   // id → (hp, maxHp)
    for (const auto& c : result.team) {
        hpMap[c.id] = {c.hp, c.maxHp};
    }
    std::set<std::string> survivorIds;
    for (const auto& c : result.team) {
        if (!c.isDead()) survivorIds.insert(c.id);
    }
    std::set<std::string> deadIds;
    const int32_t dyingBefore = static_cast<int32_t>(std::count_if(
        members.begin(), members.end(),
        [](const state::SecretRealmMemberState& m) { return m.isDying || m.isDead; }));
    std::vector<state::SecretRealmMemberState> newMembers;
    newMembers.reserve(members.size());
    for (const auto& ms : members) {
        if (ms.isDead) { newMembers.push_back(ms); continue; }
        const auto hit = hpMap.find(ms.discipleId);
        if (hit == hpMap.end()) { newMembers.push_back(ms); continue; }
        const int32_t hp = hit->second.first;
        const int32_t maxHp = hit->second.second;
        if (survivorIds.count(ms.discipleId) > 0) {
            const int32_t clamped = std::min(std::max(hp, 0), maxHp);
            const auto rowOpt = ds.rowOf(ms.discipleId);
            if (rowOpt.has_value()) ds.currentHps[*rowOpt] = clamped;
            state::SecretRealmMemberState out = ms;
            out.currentHp = clamped >= maxHp ? -1 : clamped;
            out.maxHp = maxHp;
            newMembers.push_back(std::move(out));
        } else if (ms.isDying) {
            materializeDiscipleBagAndMarkDead(state, ms.discipleId, year,
                                              overflowMail);
            deadIds.insert(ms.discipleId);
            state::SecretRealmMemberState out = ms;
            out.isDead = true;
            newMembers.push_back(std::move(out));
        } else {
            const auto rowOpt = ds.rowOf(ms.discipleId);
            if (rowOpt.has_value()) ds.currentHps[*rowOpt] = 1;
            state::SecretRealmMemberState out = ms;
            out.isDying = true;
            out.currentHp = 1;
            out.maxHp = maxHp;
            newMembers.push_back(std::move(out));
        }
    }
    WriteBackResult out;
    out.members = std::move(newMembers);
    out.deadIds = std::move(deadIds);
    out.teamCasualties =
        static_cast<int32_t>(std::count_if(
            out.members.begin(), out.members.end(),
            [](const state::SecretRealmMemberState& m) { return m.isDying || m.isDead; })) -
        dyingBefore;
    return out;
}

/// 妖兽战斗全链（Kotlin runBeastBattle）：组装 → 执行 → 写回 → 战报状态段 →
/// 奖励/损失。无战力（存活成员全不可战）→ 不战而败。
inline SecretRealmBattleOutcome runBeastBattle(
    GameState& state, const state::SecretRealmExplorationSession& session,
    const state::SecretRealmEventParams& eventParams, rng::RngManager& rng,
    gamecore::system::OverflowMailCollector& overflowMail) {
    // 篡改档防御：妖兽数量 clamp
    state::SecretRealmEventParams safeParams = eventParams;
    safeParams.beastCount = std::min(
        std::max(safeParams.beastCount, secret_realm_cfg::kBeastCountMin),
        secret_realm_cfg::kBeastCountMax);

    // 组装：存活成员（濒死 1 血）+ preGen 妖兽 ×count
    DiscipleStore& ds = state.disciples;
    const auto& gd = state.gameData;
    std::vector<Disciple> combatDisciples;
    std::map<std::string, gamecore::state::EquipmentInstance> equipmentMap;
    for (const auto& inst : state.equipmentInstances) equipmentMap[inst.id] = inst;
    std::map<std::string, gamecore::state::ManualInstance> manualMap;
    for (const auto& inst : state.manualInstances) manualMap[inst.id] = inst;
    for (const auto& ms : session.members) {
        if (ms.isDead) continue;
        const auto rowOpt = ds.rowOf(ms.discipleId);
        if (!rowOpt.has_value() || ds.isAlive[*rowOpt] != 1) continue;
        Disciple d = ds.materialize(*rowOpt);
        if (ms.isDying) d.currentHp = 1;
        combatDisciples.push_back(std::move(d));
    }

    SecretRealmBattleOutcome outcome;
    if (combatDisciples.empty()) {
        outcome.backpack = session.backpack;
        outcome.members = session.members;
        outcome.params = safeParams;
        outcome.resultText = "队伍已无战力，战斗不战而败";
        return outcome;
    }

    const auto pre = gamecore::system::buildSecretRealmBeastPreGenStats(
        rng, safeParams.beastRealm, safeParams.beastTypeName,
        safeParams.ambushSucceeded, safeParams.beastLayer);
    gamecore::battle::BattleState battle;
    battle.maxTurns = gamecore::battle::kMaxTurns;
    const auto proficiencies = proficienciesByDisciple(state);
    for (const auto& d : combatDisciples) {
        const auto brIt = gd.bloodRefinementPctTotals.find(d.id);
        battle.team.push_back(mission_settle::detail::discipleToCombatant(
            d, equipmentMap, manualMap, proficiencies,
            brIt == gd.bloodRefinementPctTotals.end() ? nullptr : &brIt->second));
    }
    for (int32_t i = 1; i <= safeParams.beastCount; ++i) {
        battle.beasts.push_back(
            secretRealmBeast(i, safeParams.beastTypeName, safeParams.beastRealm, pre));
    }
    auto& battleRng = rng.getRng(rng::RngPartition::kBattle);
    outcome.battle = gamecore::battle::executeBattle(battle, 1.0, battleRng, -1, nullptr);
    outcome.hasBattle = true;
    outcome.victory = outcome.battle.winner == gamecore::battle::BattleWinner::kTeam;

    const auto written =
        writeBackBattleMembers(state, session.members, outcome.battle, overflowMail);
    outcome.members = written.members;
    outcome.deadIds = written.deadIds;
    outcome.teamCasualties = written.teamCasualties;

    // 战报标题段（妖兽显示名 = prefix + name）
    const auto& types = gamecore::data::beastTypes();
    std::string beastName = safeParams.beastTypeName;
    for (const auto& t : types) {
        if (safeParams.beastTypeName == t.name) {
            beastName = std::string(t.prefix) + t.name;
            break;
        }
    }
    outcome.battleType = "PVE";
    outcome.defenderName = beastName + " × " + std::to_string(safeParams.beastCount);
    outcome.details = outcome.victory
                          ? "秘境探索：击败了" + beastName + " × " +
                                std::to_string(safeParams.beastCount)
                          : "秘境探索：被" + beastName + "击败";
    outcome.beastsDefeated =
        outcome.victory
            ? safeParams.beastCount
            : static_cast<int32_t>(std::count_if(
                  outcome.battle.beasts.begin(), outcome.battle.beasts.end(),
                  [](const gamecore::battle::Combatant& c) { return c.isDead(); }));

    // 奖励/损失（Kotlin settleBattleRewards）
    if (outcome.victory) {
        const auto loot = gamecore::system::rollSecretRealmBeastLoot(
            rng, safeParams.beastTypeName, safeParams.beastRealm,
            safeParams.beastCount);
        for (const auto& item : loot) {
            const auto* tpl = gamecore::data::beastMaterialById(item.itemId);
            state::Material m;
            m.id = nextItemId("gc-material");
            m.name = item.name;
            m.rarity = item.rarity;
            m.description = tpl != nullptr ? tpl->description : "";
            m.category = tpl != nullptr ? tpl->category : "BEAST_HIDE";
            m.quantity = 1;
            outcome.backpack.materials.push_back(std::move(m));
        }
        const int64_t stoneReward = outcome.battle.rewards.count("spiritStones")
                                        ? outcome.battle.rewards.at("spiritStones")
                                        : 0;
        outcome.backpack.spiritStones += stoneReward;
        outcome.params = safeParams;
        outcome.params.itemRewards = loot;
        outcome.params.spiritStones = stoneReward;
        outcome.resultText =
            loot.empty() && stoneReward <= 0
                ? "战斗结束！你方击退了" + std::to_string(safeParams.beastCount) +
                      "只" + beastName
                : "战斗结束！你方击退了" + std::to_string(safeParams.beastCount) +
                      "只" + beastName + "，获得材料 ×" +
                      std::to_string(loot.size()) + "、灵石 " +
                      std::to_string(stoneReward);
    } else {
        const auto loss =
            gamecore::system::applySecretRealmLootLoss(session.backpack, rng);
        outcome.backpack = loss.backpack;
        outcome.params = safeParams;
        outcome.params.lostItemCount = loss.lostItemCount;
        std::string lostParts;
        if (loss.lostItemCount > 0) {
            lostParts += "物品 ×" + std::to_string(loss.lostItemCount);
        }
        if (loss.lostSpiritStones > 0) {
            if (!lostParts.empty()) lostParts += "、";
            lostParts += "灵石 " + std::to_string(loss.lostSpiritStones);
        }
        outcome.resultText =
            "战斗结束！你方不敌妖兽，仓促撤退，" +
            (lostParts.empty() ? std::string("所幸所得未受损失") : "丢失了" + lostParts);
    }
    return outcome;
}

/// AI 宗门遭遇 PvP 战斗（Kotlin runAISectBattle → settleAiEncounterBattle）：
/// 存活过滤 → 无战力直通 → PvP（我方 DEFENDER 现血 vs AI ATTACKER 满血）→
/// 写回 → 胜利按宗门等级奖励 + 击败标记 / 失败损失。
inline SecretRealmBattleOutcome runAISectBattle(
    GameState& state, const state::SecretRealmExplorationSession& session,
    const state::SecretRealmEventParams& eventParams, rng::RngManager& rng,
    gamecore::system::OverflowMailCollector& overflowMail) {
    SecretRealmBattleOutcome outcome;
    const std::string sectName =
        eventParams.aiSectName.empty() ? "对方" : eventParams.aiSectName;

    // 对方存活成员过滤（按 aiSectDisciples 现况）
    std::vector<Disciple> aiDisciples;
    const auto sectIt = state.aiSectDisciples.find(eventParams.aiSectId);
    if (sectIt != state.aiSectDisciples.end()) {
        for (const auto& m : eventParams.aiMembers) {
            for (const auto& d : sectIt->second) {
                if (d.id == m.discipleId && d.isAlive) {
                    aiDisciples.push_back(d);
                    break;
                }
            }
        }
    }
    if (aiDisciples.empty()) {
        outcome.backpack = session.backpack;
        outcome.members = session.members;
        outcome.params = eventParams;
        outcome.resultText = "对方的探索队伍已无力应战，你方绕过继续前行";
        return outcome;
    }

    // 我方战力组装
    DiscipleStore& ds = state.disciples;
    const auto& gd = state.gameData;
    std::vector<Disciple> combatDisciples;
    std::map<std::string, gamecore::state::EquipmentInstance> equipmentMap;
    for (const auto& inst : state.equipmentInstances) equipmentMap[inst.id] = inst;
    std::map<std::string, gamecore::state::ManualInstance> manualMap;
    for (const auto& inst : state.manualInstances) manualMap[inst.id] = inst;
    for (const auto& ms : session.members) {
        if (ms.isDead) continue;
        const auto rowOpt = ds.rowOf(ms.discipleId);
        if (!rowOpt.has_value() || ds.isAlive[*rowOpt] != 1) continue;
        Disciple d = ds.materialize(*rowOpt);
        if (ms.isDying) d.currentHp = 1;
        combatDisciples.push_back(std::move(d));
    }
    if (combatDisciples.empty()) {
        outcome.backpack = session.backpack;
        outcome.members = session.members;
        outcome.params = eventParams;
        outcome.resultText = "队伍已无战力，战斗不战而败";
        return outcome;
    }

    // PvP：AI 侧 prepareDisciplesForBattle（模拟装备/功法，满血 ATTACKER）
    const auto prepared = ai_ops::aiPrepareDisciplesForBattle(aiDisciples);
    gamecore::battle::BattleState battle;
    battle.maxTurns = gamecore::battle::kMaxTurns;
    const auto proficiencies = proficienciesByDisciple(state);
    for (const auto& d : combatDisciples) {
        const auto brIt = gd.bloodRefinementPctTotals.find(d.id);
        battle.team.push_back(mission_settle::detail::discipleToCombatant(
            d, equipmentMap, manualMap, proficiencies,
            brIt == gd.bloodRefinementPctTotals.end() ? nullptr : &brIt->second));
    }
    for (const auto& d : prepared.disciples) {
        const auto& eq = prepared.equipmentMapByDisciple.at(d.id);
        gamecore::battle::Combatant c = mission_settle::detail::discipleToCombatant(
            d, eq, prepared.manualMap, prepared.proficiencies, nullptr);
        c.hp = c.maxHp;
        c.mp = c.maxMp;
        c.side = gamecore::battle::CombatantSide::kAttacker;
        battle.beasts.push_back(std::move(c));
    }
    auto& battleRng = rng.getRng(rng::RngPartition::kBattle);
    outcome.battle = gamecore::battle::executeBattle(battle, 1.0, battleRng, -1, nullptr);
    outcome.hasBattle = true;
    outcome.victory = outcome.battle.winner == gamecore::battle::BattleWinner::kTeam;

    const auto written =
        writeBackBattleMembers(state, session.members, outcome.battle, overflowMail);
    outcome.members = written.members;
    outcome.deadIds = written.deadIds;
    outcome.teamCasualties = written.teamCasualties;

    outcome.battleType = "PVP";
    outcome.defenderName = sectName + "探索队伍";
    outcome.details = outcome.victory
                          ? "秘境探索：击败了" + sectName + "的探索队伍"
                          : "秘境探索：被" + sectName + "的探索队伍击败";
    outcome.beastsDefeated = static_cast<int32_t>(std::count_if(
        outcome.battle.beasts.begin(), outcome.battle.beasts.end(),
        [](const gamecore::battle::Combatant& c) { return c.isDead(); }));

    // 奖励/损失（Kotlin settleAISectBattleRewards）
    if (outcome.victory) {
        // 宗门等级 → 品阶区间（AI_REWARD_RARITY_RANGES = [1..2, 2..3, 3..4, 4..5]）
        static const std::pair<int32_t, int32_t> kRarityRanges[4] = {
            {1, 2}, {2, 3}, {3, 4}, {4, 5}};
        const std::pair<int32_t, int32_t>& rarityRange =
            kRarityRanges[std::min(std::max(eventParams.aiSectLevel, 0), 3)];
        const auto rewards = gamecore::system::generateSecretRealmRuinsTreasure(
            rng, 1, 15, rarityRange.first, rarityRange.second,
            buildTypeCandidates());
        outcome.backpack = instantiateRewards(rewards, session.backpack);
        outcome.params = eventParams;
        outcome.params.itemRewards = rewards;
        outcome.resultText =
            rewards.empty()
                ? "战斗结束！你方击败了" + sectName + "的探索队伍"
                : "战斗结束！你方击败了" + sectName + "的探索队伍，缴获物品 ×" +
                      std::to_string(rewards.size());
        // 击败标记（Kotlin markAiTeamDefeated）：战死 AI 置 DEAD + 移除队伍
        std::set<std::string> aiDead;
        for (const auto& c : outcome.battle.beasts) {
            if (c.isDead()) aiDead.insert(c.id);
        }
        if (!aiDead.empty()) {
            auto it = state.aiSectDisciples.find(eventParams.aiSectId);
            if (it != state.aiSectDisciples.end()) {
                for (auto& d : it->second) {
                    if (aiDead.count(d.id) > 0) {
                        d.isAlive = false;
                        d.status = "DEAD";
                        d.deathYear = state.gameData.gameYear;  // 尸体保留窗口基准
                    }
                }
            }
        }
        auto& teams = state.gameData.secretRealmAITeams;
        teams.erase(std::remove_if(teams.begin(), teams.end(),
                                   [&](const state::SecretRealmAITeam& t) {
                                       return t.sectId == eventParams.aiSectId;
                                   }),
                    teams.end());
    } else {
        const auto loss =
            gamecore::system::applySecretRealmLootLoss(session.backpack, rng);
        outcome.backpack = loss.backpack;
        outcome.params = eventParams;
        outcome.params.lostItemCount = loss.lostItemCount;
        std::string lostParts;
        if (loss.lostItemCount > 0) {
            lostParts += "物品 ×" + std::to_string(loss.lostItemCount);
        }
        if (loss.lostSpiritStones > 0) {
            if (!lostParts.empty()) lostParts += "、";
            lostParts += "灵石 " + std::to_string(loss.lostSpiritStones);
        }
        outcome.resultText =
            "战斗结束！你方不敌" + sectName + "的探索队伍，仓促撤退，" +
            (lostParts.empty() ? std::string("所幸所得未受损失") : "丢失了" + lostParts);
    }
    return outcome;
}

/// 空地休整（Kotlin applyRestRecovery）：存活成员恢复 maxHp×40%（战斗口径
/// maxHp 优先）；濒死脱离濒死并写回弟子表；只增不减。
inline std::pair<std::vector<state::SecretRealmMemberState>, std::string>
restRecovery(GameState& state, const state::SecretRealmExplorationSession& session) {
    DiscipleStore& ds = state.disciples;
    std::vector<state::SecretRealmMemberState> newMembers;
    newMembers.reserve(session.members.size());
    for (const auto& ms : session.members) {
        if (ms.isDead) { newMembers.push_back(ms); continue; }
        const auto rowOpt = ds.rowOf(ms.discipleId);
        if (!rowOpt.has_value() || ds.isAlive[*rowOpt] != 1) {
            newMembers.push_back(ms);
            continue;
        }
        // 基础口径 maxHp（表中装配值）——战斗口径 maxHp 优先（战斗写回维护）
        const Disciple d = ds.materialize(*rowOpt);
        const int32_t baseMaxHp = stats::baseStats(d).maxHp;
        if (baseMaxHp <= 0) { newMembers.push_back(ms); continue; }
        const int32_t maxHp = ms.maxHp > 0 ? ms.maxHp : baseMaxHp;
        const int32_t curHp = (ms.isDying && ms.currentHp < 0) ? 1 : ms.currentHp;
        if (curHp < 0) { newMembers.push_back(ms); continue; }
        const int32_t safeCur = std::min(curHp, maxHp);
        const int32_t heal = static_cast<int32_t>(
            static_cast<double>(maxHp) * secret_realm_cfg::kRestRecoveryRatio);
        const int32_t newHp = static_cast<int32_t>(std::min<int64_t>(
            static_cast<int64_t>(safeCur) + heal, static_cast<int64_t>(maxHp)));
        ds.currentHps[*rowOpt] = std::max(newHp, ds.currentHps[*rowOpt]);
        state::SecretRealmMemberState out = ms;
        out.currentHp = newHp >= maxHp ? -1 : newHp;
        out.isDying = false;
        newMembers.push_back(std::move(out));
    }
    return {std::move(newMembers), "你方原地休整，全队恢复生命状态"};
}

}  // namespace detail

// ── 出发探索（Kotlin SecretRealmService.startSession）──────────────

/// 出发校验结果（Kotlin DomainResult 对应物——错误分类 + 文案逐字一致）
struct SecretRealmStartResult {
    bool ok = false;
    std::string errorType;   // "NotFound" / "InvalidState" / "InvalidInput"
    std::string message;
};

namespace detail {

/// 事件类型（Kotlin SecretRealmEventType）
enum class SecretRealmEventType {
    kBeastEncounter,
    kRestArea,
    kRuinExplore,
    kRuinResult,
    kDirectionChoice,
    kAiSectEncounter,
};

/// 事件类型字符串解析（Kotlin resolveEventType——篡改档非法值/旧档 BRIDGE
/// 回退方向事件分支）
inline SecretRealmEventType resolveSecretRealmEventType(const std::string& name) {
    if (name == gamecore::system::secret_realm_type::kBeastEncounter) return SecretRealmEventType::kBeastEncounter;
    if (name == gamecore::system::secret_realm_type::kRestArea) return SecretRealmEventType::kRestArea;
    if (name == gamecore::system::secret_realm_type::kRuinExplore) return SecretRealmEventType::kRuinExplore;
    if (name == gamecore::system::secret_realm_type::kRuinResult) return SecretRealmEventType::kRuinResult;
    if (name == gamecore::system::secret_realm_type::kAiSectEncounter) return SecretRealmEventType::kAiSectEncounter;
    return SecretRealmEventType::kDirectionChoice;   // 含 kDirectionChoice 与非法值
}

/// 战斗结果 → 分支结算载体（Kotlin toResolution；结算后进入探索方向事件，
/// resultText 成为方向事件描述前缀）
inline SecretRealmChoiceResolution toResolution(SecretRealmBattleOutcome outcome) {
    SecretRealmChoiceResolution r;
    r.resultText = outcome.resultText;
    r.enteredCombat = true;
    r.battle = std::move(outcome);
    r.backpack = r.battle->backpack;
    r.members = r.battle->members;
    r.deadIds = r.battle->deadIds;
    r.params = r.battle->params;
    r.nextEvent = gamecore::system::generateSecretRealmDirectionEvent(r.resultText);
    return r;
}

/// 无战斗分支结算载体（成员不变，携带会话背包防清空）
inline SecretRealmChoiceResolution directionResolution(
    const std::string& resultText,
    const state::SecretRealmExplorationSession& session) {
    SecretRealmChoiceResolution r;
    r.resultText = resultText;
    r.members = session.members;
    r.backpack = session.backpack;
    r.nextEvent = gamecore::system::generateSecretRealmDirectionEvent(resultText);
    return r;
}

/// 遗迹结算转换（C++ resolveSecretRealmRuins* 原语 → 选择载体 + 秘宝描述符
/// 实例化入背包——Kotlin resolveRuinsExplore 内 instantiateRuinsRewards 段）
inline SecretRealmChoiceResolution ruinsResolution(
    gamecore::system::SecretRealmResolution resolved) {
    SecretRealmChoiceResolution r;
    r.resultText = resolved.resultText;
    r.members = resolved.members;
    r.params = resolved.params;
    r.backpack = instantiateRewards(resolved.params.itemRewards, resolved.backpack);
    r.nextEvent = resolved.nextEvent;
    return r;
}

/// 弟子境界显示名（Kotlin Disciple.realmName 计算属性）
inline std::string discipleRealmName(const Disciple& d) {
    if (d.age < 5 || d.realmLayer == 0) return "无境界";
    if (d.realm == 0) return realmName(d.realm);
    return realmName(d.realm) + std::to_string(d.realmLayer) + "层";
}

}  // namespace detail

inline SecretRealmStartResult startSession(GameState& state,
                                           const std::vector<std::string>& memberIds,
                                           rng::RngManager& rng) {
    SecretRealmStartResult r;
    auto& data = state.gameData;
    if (data.secretRealmState.id.empty()) {
        r.errorType = "NotFound";
        r.message = "远古秘境已消失";
        return r;
    }
    if (!data.secretRealmSession.members.empty()) {
        r.errorType = "InvalidState";
        r.message = "探索队伍已在秘境中";
        return r;
    }
    if (static_cast<int32_t>(memberIds.size()) != secret_realm_cfg::kTeamSize ||
        std::set<std::string>(memberIds.begin(), memberIds.end()).size() !=
            memberIds.size()) {
        r.errorType = "InvalidInput";
        r.message = "需要 4 名不同的弟子组成探索队伍";
        return r;
    }
    DiscipleStore& ds = state.disciples;
    std::vector<Disciple> selected;
    selected.reserve(memberIds.size());
    for (const auto& id : memberIds) {
        const auto rowOpt = ds.rowOf(id);
        if (!rowOpt.has_value()) {
            r.errorType = "NotFound";
            r.message = "弟子不存在";
            return r;
        }
        selected.push_back(ds.materialize(*rowOpt));
    }
    // 存活校验（死亡弟子不可出发）；无 IDLE 校验（换岗语义——Kotlin 同）
    for (const auto& d : selected) {
        if (!d.isAlive) {
            r.errorType = "InvalidInput";
            r.message = "弟子「" + d.name + "」已死亡";
            return r;
        }
    }

    auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
    // playerAvgRealm = realm 平均 toInt（Kotlin average().toInt() 截断）
    int64_t realmSum = 0;
    for (const auto& d : selected) realmSum += d.realm;
    const int32_t playerAvgRealm =
        static_cast<int32_t>(realmSum / static_cast<int64_t>(selected.size()));
    const auto event =
        gamecore::system::generateSecretRealmBeastEvent(rng, playerAvgRealm);

    state::SecretRealmExplorationSession session;
    session.secretRealmId = data.secretRealmState.id;
    for (const auto& d : selected) {
        state::SecretRealmMemberState ms;
        ms.discipleId = d.id;
        ms.name = d.name;
        ms.portraitRes = d.portraitRes;
        ms.realm = d.realm;
        ms.realmName = detail::discipleRealmName(d);
        // 初始参考值（未战斗，基础口径）；战斗后由写回维护为战斗口径
        ms.maxHp = stats::baseStats(d).maxHp;
        session.members.push_back(std::move(ms));
    }
    session.stamina = secret_realm_cfg::kStaminaMax;
    session.currentEvent = event;
    session.startYear = data.gameYear;
    session.startMonth = data.gameMonth;
    data.secretRealmSession = std::move(session);
    r.ok = true;
    return r;
}

// ── 选择选项（Kotlin SecretRealmService.chooseOption）─────────────

/// 前置声明（chooseOption 会话结束分支调用；定义见下）
std::set<std::string> endSession(GameState& state, const char* reason,
                                 gamecore::system::OverflowMailCollector& overflowMail);

inline SecretRealmChoiceOutcome chooseOption(GameState& state, int32_t optionIndex,
                                             rng::RngManager& rng) {
    SecretRealmChoiceOutcome out;
    auto& data = state.gameData;
    state::SecretRealmExplorationSession& session = data.secretRealmSession;
    const auto& eventOpt = session.currentEvent;

    // validateChoice（顺序与 Kotlin 一致）
    if (session.members.empty()) {
        out.errorText = "探索会话不存在";
        return out;
    }
    if (!eventOpt.has_value()) {
        out.errorText = "当前无进行中的事件";
        return out;
    }
    const state::SecretRealmEventRecord& event = *eventOpt;
    if (event.chosenOptionIndex != -1) {
        out.errorText = "事件已处理，请勿重复选择";
        return out;
    }
    if (optionIndex < 0 ||
        optionIndex >= static_cast<int32_t>(event.options.size())) {
        out.errorText = "无效的选项";
        return out;
    }
    // 篡改档防御：0 体力白嫖 / 体力不足高费选项
    if (session.stamina <= 0) {
        out.errorText = "体力已耗尽，探索结束";
        return out;
    }
    const int32_t optionCost = event.options[static_cast<std::size_t>(optionIndex)]
                                   .staminaCost;
    if (session.stamina < optionCost) {
        out.errorText = "体力不足，无法选择该选项";
        return out;
    }

    const int32_t newStamina = gamecore::system::secretRealmStaminaAfterChoice(
        session, event, optionIndex);
    const auto eventType = detail::resolveSecretRealmEventType(
        event.eventType);

    gamecore::system::OverflowMailCollector overflowMail;
    SecretRealmChoiceResolution resolution;
    const std::string sectName =
        event.params.aiSectName.empty() ? "对方" : event.params.aiSectName;

    switch (eventType) {
        case detail::SecretRealmEventType::kBeastEncounter: {
            if (optionIndex == 0) {
                // ① 远离妖兽：30% 被察觉 → 战斗；否则成功远离（1×nextDouble）
                auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
                if (sr.nextDouble() < secret_realm_cfg::kFleeDetectChance) {
                    resolution = detail::toResolution(
                        detail::runBeastBattle(state, session, event.params, rng,
                                               overflowMail));
                } else {
                    resolution = detail::directionResolution(
                        "你方悄然绕行，成功避开了妖兽的注意", session);
                }
            } else if (optionIndex == 1) {
                // ② 发起战斗
                resolution = detail::toResolution(
                    detail::runBeastBattle(state, session, event.params, rng,
                                           overflowMail));
            } else {
                // ③ 偷袭：50% 成功（妖兽血量 -10%）；失败被察觉（1×nextDouble）
                auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
                const bool ambushSucceeded =
                    sr.nextDouble() >= secret_realm_cfg::kAmbushDetectChance;
                auto params = event.params;
                params.ambushSucceeded = ambushSucceeded;
                resolution = detail::toResolution(
                    detail::runBeastBattle(state, session, params, rng, overflowMail));
            }
            break;
        }
        case detail::SecretRealmEventType::kRestArea: {
            if (optionIndex == 0) {
                auto [newMembers, resultText] = detail::restRecovery(state, session);
                resolution.resultText = resultText;
                resolution.members = newMembers;
                resolution.backpack = session.backpack;   // 休整不改背包（防空覆盖）
                resolution.nextEvent =
                    gamecore::system::generateSecretRealmDirectionEvent(resultText);
            } else {
                resolution =
                    detail::directionResolution("你方不做停留，继续探索", session);
            }
            break;
        }
        case detail::SecretRealmEventType::kRuinExplore: {
            resolution = detail::ruinsResolution(
                gamecore::system::resolveSecretRealmRuinsExplore(
                    optionIndex, session.members, session.backpack, rng,
                    detail::buildTypeCandidates()));
            break;
        }
        case detail::SecretRealmEventType::kRuinResult: {
            resolution = detail::ruinsResolution(
                gamecore::system::resolveSecretRealmRuinsResult(
                    session.members, session.backpack, event));
            break;
        }
        case detail::SecretRealmEventType::kDirectionChoice: {
            // 方向纯过渡：不触碰概率配置；rollNextEvent 四分段在方向选择时消费
            const char* directionName =
                optionIndex == 0 ? "左路" : optionIndex == 1 ? "中路" : "右路";
            const std::string resultText =
                std::string("你方沿") + directionName + "继续前行";
            resolution.resultText = resultText;
            resolution.members = session.members;
            resolution.backpack = session.backpack;   // 方向不改背包（防空覆盖）
            resolution.nextEvent = gamecore::system::rollSecretRealmNextEvent(
                rng, gamecore::system::secretRealmPlayerAvgRealm(session.members),
                state.gameData.secretRealmAITeams);
            break;
        }
        case detail::SecretRealmEventType::kAiSectEncounter: {
            if (optionIndex == 0 || optionIndex >= 2) {
                // ①③ 避让必成功（零 RNG）；篡改档超出 0/1/2 按右路（Kotlin 同）
                const bool left = optionIndex == 0;
                resolution = detail::directionResolution(
                    left ? "你方悄然向左避让，与" + sectName + "的探索队伍擦肩而过"
                         : "你方悄然向右避让，与" + sectName + "的探索队伍擦肩而过",
                    session);
            } else {
                resolution = detail::toResolution(
                    detail::runAISectBattle(state, session, event.params, rng,
                                            overflowMail));
            }
            break;
        }
    }

    const bool allDead =
        !resolution.members.empty() &&
        std::all_of(resolution.members.begin(), resolution.members.end(),
                    [](const state::SecretRealmMemberState& m) { return m.isDead; });
    const bool sessionEnded = allDead || newStamina <= 0;

    // 会话合并（Kotlin updatedSession）
    state::SecretRealmEventRecord markedEvent = event;
    markedEvent.chosenOptionIndex = optionIndex;
    markedEvent.resultText = resolution.resultText;
    markedEvent.params = resolution.params;
    session.stamina = newStamina;
    session.members = resolution.members;
    session.backpack = resolution.backpack;
    session.currentEvent = resolution.nextEvent;
    session.eventHistory.push_back(std::move(markedEvent));
    session.resultMessage = resolution.resultText;

    out.ok = true;
    out.overflowDrafts = overflowMail.takeAll();
    out.resolution = std::move(resolution);
    if (sessionEnded) {
        const char* reason = allDead ? kEndWipeout : kEndExhausted;
        out.releasedMemberIds = endSession(state, reason, overflowMail);
        out.sessionEnded = true;
        out.message = allDead ? "队伍全军覆没！" : "体力耗尽，被传送出秘境";
    } else {
        out.message = out.resolution.resultText;
    }
    return out;
}

// ── 结束会话（Kotlin endSession + settleBackpack）────────────────

/// 结束会话：背包结算（灵石入钱包 + 六类物品入仓、溢出转邮件草稿）→ 清空
/// 秘境（cooldownYear = 当前年）→ 事件。返回 gate 释放面（平台效应草稿——
/// 邮件经信封回传 Kotlin 发送）。
inline std::set<std::string> endSession(
    GameState& state, const char* reason,
    gamecore::system::OverflowMailCollector& overflowMail) {
    auto& data = state.gameData;
    if (data.secretRealmSession.members.empty() && data.secretRealmState.id.empty()) {
        return {};
    }
    if (!data.secretRealmSession.members.empty()) {
        // settleBackpack：灵石入钱包（LOW/SecretRealm 来源）+ 物品入仓
        //（trackingSource = "secret_realm"；溢出自动转邮件草稿——边界：
        // 草稿由调用方经信封回传 Kotlin 发送）
        auto& backpack = data.secretRealmSession.backpack;
        if (backpack.spiritStones > 0) {
            SpiritStoneWallet::add(data, backpack.spiritStones,
                                   SpiritStoneGrade::LOW, "SecretRealm");
        }
        for (auto& item : backpack.equipment) {
            if (item.quantity <= 0) continue;
            gamecore::system::addEquipmentStack(state, item, overflowMail,
                                                "secret_realm", false);
        }
        for (auto& item : backpack.manuals) {
            if (item.quantity <= 0) continue;
            gamecore::system::addManualStack(state, item, overflowMail,
                                             "secret_realm", false);
        }
        for (auto& item : backpack.pills) {
            if (item.quantity <= 0) continue;
            gamecore::system::addPill(state, item, overflowMail, "secret_realm",
                                      false);
        }
        for (auto& item : backpack.materials) {
            if (item.quantity <= 0) continue;
            gamecore::system::addMaterial(state, item, overflowMail,
                                          "secret_realm", false);
        }
        for (auto& item : backpack.herbs) {
            if (item.quantity <= 0) continue;
            gamecore::system::addHerb(state, item, overflowMail, "secret_realm",
                                      false);
        }
        for (auto& item : backpack.seeds) {
            if (item.quantity <= 0) continue;
            gamecore::system::addSeed(state, item, overflowMail, "secret_realm",
                                      false);
        }
    }
    // 基于结算后的最新 gameData 清空秘境（settleBackpack 内部写入灵石/年度
    // 统计——用旧快照 copy 会覆盖）
    std::set<std::string> memberIds;
    for (const auto& m : data.secretRealmSession.members) {
        memberIds.insert(m.discipleId);
    }
    const int32_t year = state.gameData.gameYear;
    data.secretRealmState = state::SecretRealmState{};
    data.secretRealmCooldownYear = year;
    data.secretRealmSession = state::SecretRealmExplorationSession{};
    data.secretRealmAITeams.clear();
    settle_util::recordGameEvent(
        state, "SECT", "SECRET_REALM",
        std::string(reason) == kEndExplorerEnd
            ? "远古秘境已关闭，探索队伍带着收获返回了宗门"
            : std::string(reason) == kEndExhausted
                  ? "探索队伍体力耗尽，被传送出了远古秘境"
                  : std::string(reason) == kEndWipeout
                        ? "探索队伍全军覆没，远古秘境随之消散"
                        : "远古秘境现世期满，已自动关闭，探索所得已通过邮件送回");
    return memberIds;
}

// 注：年变现世（Kotlin SecretRealmService.processYearlySpawn）已在
// runYearSettlement 原生下沉（processAncientSecretRealmSpawn——判据/位置/精灵
// 变体逐位同源）——AUTHORITATIVE 模式年变钩子即真相源，独立 ActionId 冗余不注册
//（死导出纪律；设计要点①的 YEARLY_SPAWN 项就此勘误）。

}  // namespace gamecore::system::sr_session
