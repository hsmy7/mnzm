// ============================================================
// disciple_tx.h — 弟子管理 UI 操作事务（装备穿脱/功法学习卸下/任命卸任）
//
// batch-08 第一子批（ui-read-surface §4.1 弟子管理族最大残余域的
// 零 RNG 纯事务子域打样）。等价移植（语义权威 = 各 Kotlin 源文件）：
//  - DiscipleEquipmentService.equipEquipment/unequipEquipment
//    （core/engine/.../domain/disciple/DiscipleEquipmentService.kt——
//     equipEquipmentInTransaction / wearEquipment / unequipEquipmentLogic）
//  - GameEngineManualOps.learnManual/forgetManual（GameEngine 扩展路径 =
//    DiscipleDelegate UI 活路径；DiscipleFacadeImpl 私有同名函数非活路径，
//    其差异面——学习日志/卸下 manualIds 残留——不移植）
//  - DiscipleFacadeImpl.assignDirectDisciple/removeDirectDisciple/
//    assignDiscipleToLibrarySlot/removeDiscipleFromLibrarySlot
//    （槽位数据写段；Gate 注册表/状态同步/Room 回放为 Kotlin 运行态域）
//
// RNG 契约（对拍命门）——**全部六事务零 RNG**：
//  - 校验链（存在性/境界/占用/名额/心法唯一/同名唯一）与写路径（列直写、
//    堆叠 -1/移除、实例铸造/入袋）均无 rng 抽取。
//  - 铸造实例 id：Kotlin UUID.randomUUID() 为非协议随机域（不消费
//    RngManager 分区流），C++ 以确定性自增 nextInstanceId（"gc-inst-N"，
//    recruit_settlement.h 注册表）占位——auto_gear.h/recruit_settlement.h
//    同先例（镜像生成字段，对拍面忽略新增条目 id）。
//  - 失败臂零写入：校验链先行完成后再落写，两段（弟子行 + 背包/仓库段）
//    单事务内要么都成要么都不动（"扣了背包却穿不上"中间态构造上不可能）。
//
// 已知范围边界（对拍约定，auto_gear.h / disciple_purchase.h 同口径）：
//  - lifeEvents（"X岁：装备了Y"等）为 Kotlin 类体属性（非协议字段），
//    C++ 无该列——equip 日志以草稿行进信封（logLine），由 Kotlin native
//    分支回写瞬态列（disciple_purchase PurchaseLogDraft 同族机制）。
//  - DiscipleAssignmentGate 登记/释放、syncSingleDiscipleStatus 状态推导、
//    productionCoordinator Room 回放为 Kotlin 运行态域，native 分支在
//    事务成功后照原序执行。
//  - 长老单值槽任命（ElderManagementUseCase.assignElder/removeElder）为
//    usecase 编排域（releaseDiscipleFromAllSlotsAtomic + checkpoint 联动），
//    本批不下沉（登记 handover §2.37，归 W3）。
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/state/disciple_store.h"      // DiscipleStore 列式存储
#include "gamecore/system/disciple.h"            // kBaseManualSlots
#include "gamecore/system/disciple_stats.h"      // talent/affix effects（名额公式）
#include "gamecore/system/inventory.h"           // 库存原语同源（bag 语义参照）
#include "gamecore/system/pill_system.h"         // classify/canUsePill/buildUsedKeys
#include "gamecore/system/recruit_settlement.h"  // nextInstanceId（确定性实例 id）
#include "gamecore/system/settlement_detail.h"   // settle_util::toIntOrNull
#include "gamecore/system/slot_cleanup.h"        // clearAllSlotsDataOnly

namespace gamecore::system::disciple_tx {

namespace detail {

using gamecore::state::Disciple;
using gamecore::state::DiscipleStore;
using gamecore::state::EquipmentInstance;
using gamecore::state::EquipmentStack;
using gamecore::state::GameState;
using gamecore::state::ManualInstance;
using gamecore::state::ManualStack;
using gamecore::state::StorageBagItem;
namespace settle_util = gamecore::system::settle_util;

// ── W4-A w3-01 事务族新增类型（仓库四表 + 血炼进度 + 效果面）──
using gamecore::state::BloodRefinementProgress;
using gamecore::state::Herb;
using gamecore::state::ItemEffect;
using gamecore::state::Material;
using gamecore::state::Pill;
using gamecore::state::Seed;

// ── 线性查找（仓库/实例表规模小，与 Kotlin StackableItemStore.get 同义）──

inline EquipmentStack* findEquipmentStack(GameState& state, const std::string& id) {
    for (auto& s : state.equipmentStacks) {
        if (s.id == id) return &s;
    }
    return nullptr;
}

inline EquipmentInstance* findEquipmentInstance(GameState& state, const std::string& id) {
    for (auto& e : state.equipmentInstances) {
        if (e.id == id) return &e;
    }
    return nullptr;
}

inline ManualStack* findManualStack(GameState& state, const std::string& id) {
    for (auto& s : state.manualStacks) {
        if (s.id == id) return &s;
    }
    return nullptr;
}

inline ManualInstance* findManualInstance(GameState& state, const std::string& id) {
    for (auto& m : state.manualInstances) {
        if (m.id == id) return &m;
    }
    return nullptr;
}

// ── 储物袋入袋（Kotlin StorageBagUtils.increaseItemQuantity 等价）────────
// itemId 匹配（袋内唯一键）→ 数量合并 + payload 升级（新非空覆盖旧）；
// 未命中追加。**容量无上限**（Kotlin 手动穿卸路径契约"永不失败"——与
// inventory.h addToDiscipleBag 的 P2-8 容量门不同族：那是 C++ AUTHORITATIVE
// 自动装配路径治理门，本函数对齐 Kotlin 手动路径零门语义）。
inline void bagIncreaseItemQuantity(std::vector<StorageBagItem>& bag,
                                    StorageBagItem entry) {
    for (auto& existing : bag) {
        if (existing.itemId != entry.itemId) continue;
        existing.quantity += entry.quantity;
        if (entry.equipmentInstance.has_value()) {
            existing.equipmentInstance = entry.equipmentInstance;
        }
        if (entry.stackedData.has_value()) existing.stackedData = entry.stackedData;
        if (entry.manualInstance.has_value()) existing.manualInstance = entry.manualInstance;
        return;
    }
    bag.push_back(std::move(entry));
}

// ── 装备槽位列读写（auto_gear.h detail::equipSlotId/setEquipSlot 同源，
//    本头文件独立提供避免 ECS 依赖引入）──────────────────────────────

inline const std::string& equipSlotId(const DiscipleStore& ds, std::size_t row,
                                      const std::string& slot) {
    if (slot == "WEAPON") return ds.weaponIds[row];
    if (slot == "ARMOR") return ds.armorIds[row];
    if (slot == "BOOTS") return ds.bootsIds[row];
    if (slot == "ACCESSORY") return ds.accessoryIds[row];
    static const std::string kEmpty;
    return kEmpty;
}

inline void setEquipSlot(DiscipleStore& ds, std::size_t row, const std::string& slot,
                         const std::string& instanceId) {
    if (slot == "WEAPON") ds.weaponIds[row] = instanceId;
    else if (slot == "ARMOR") ds.armorIds[row] = instanceId;
    else if (slot == "BOOTS") ds.bootsIds[row] = instanceId;
    else if (slot == "ACCESSORY") ds.accessoryIds[row] = instanceId;
}

inline bool isEquipSlotName(const std::string& slot) {
    return slot == "WEAPON" || slot == "ARMOR" || slot == "BOOTS" ||
           slot == "ACCESSORY";
}

// ── 堆叠 → 实例铸造（auto_gear.h detail::instanceFromStack/manualInstanceFromStack
//    同源——Kotlin StackableItem.toInstance 字段面；slotId/nurtureLevel 为
//    Room 列/迁移字段不复制，与既有对拍口径一致）────────────────────────

inline EquipmentInstance equipmentInstanceFromStack(const EquipmentStack& s,
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

/// 功法槽位上限（auto_gear.h detail::maxManualSlotsFor 同源；
/// Kotlin DiscipleStatCalculator.getMaxManualSlots = 6 + manualSlot 效果和）
inline int32_t maxManualSlotsFor(const Disciple& d) {
    const auto merged = gamecore::stats::mergeEffects(
        gamecore::stats::talentEffectsFor(d.talentIds),
        gamecore::stats::affixEffectsFor(d.affixIds));
    const auto it = merged.find("manualSlot");
    return gamecore::disciple::kBaseManualSlots +
           (it != merged.end() ? static_cast<int32_t>(it->second) : 0);
}

/// 仓库堆叠扣减一本（quantity>1 → -1，否则整条移除；consumeManualStackForLearn
/// 等价——learnManual 与 autoLearnForDisciple 共用语义。
/// id 先拷贝自保：调用方可能传堆叠元素成员的引用，erase 会使别名悬垂）
inline void deductManualStack(std::vector<ManualStack>& stacks, const std::string& stackId) {
    const std::string id = stackId;
    for (auto& s : stacks) {
        if (s.id != id) continue;
        if (s.quantity > 1) {
            s.quantity -= 1;
        } else {
            stacks.erase(std::remove_if(stacks.begin(), stacks.end(),
                [&](const ManualStack& x) { return x.id == id; }),
                stacks.end());
        }
        return;
    }
}

inline void deductEquipmentStack(std::vector<EquipmentStack>& stacks,
                                 const std::string& stackId) {
    const std::string id = stackId;
    for (auto& s : stacks) {
        if (s.id != id) continue;
        if (s.quantity > 1) {
            s.quantity -= 1;
        } else {
            stacks.erase(std::remove_if(stacks.begin(), stacks.end(),
                [&](const EquipmentStack& x) { return x.id == id; }),
                stacks.end());
        }
        return;
    }
}

// ── 卸下内部实现（DiscipleEquipmentService.unequipEquipmentLogic 等价）────
// @return true = 槽位已清（实例入袋 + 实例表移除；实例缺失仅清槽——
//         Kotlin 同分支"槽位清空移到入仓成功后，失败时保留槽位不悬空"）
inline bool unequipInternal(GameState& state, DiscipleStore& ds,
                            std::size_t row, const std::string& equipmentId) {
    const std::string slots[] = {"weapon", "armor", "boots", "accessory"};
    const std::string* slotToClear = nullptr;
    if (ds.weaponIds[row] == equipmentId) slotToClear = &slots[0];
    else if (ds.armorIds[row] == equipmentId) slotToClear = &slots[1];
    else if (ds.bootsIds[row] == equipmentId) slotToClear = &slots[2];
    else if (ds.accessoryIds[row] == equipmentId) slotToClear = &slots[3];
    if (slotToClear == nullptr) return false;

    EquipmentInstance* eq = findEquipmentInstance(state, equipmentId);
    if (eq != nullptr) {
        // 卸下实例铸造入袋（Kotlin 手动路径容量无上限，永不失败）
        StorageBagItem entry;
        entry.itemId = equipmentId;
        entry.itemType = "equipment_instance";
        entry.name = eq->name;
        entry.rarity = eq->rarity;
        entry.quantity = 1;
        entry.obtainedYear = state.gameData.gameYear;
        entry.obtainedMonth = state.gameData.gameMonth;
        entry.equipmentInstance = *eq;
        bagIncreaseItemQuantity(ds.storageBagItems[row], std::move(entry));
        // 实例入袋后从实例表删除（防双持有不变量——S6 袋物化同族边界）
        auto& inst = state.equipmentInstances;
        inst.erase(std::remove_if(inst.begin(), inst.end(),
                                  [&](const EquipmentInstance& x) {
                                      return x.id == equipmentId;
                                  }),
                   inst.end());
    }
    // 实例缺失（损坏态）：Kotlin 同分支仅清槽（日志告警为 Kotlin 运行态）
    if (*slotToClear == "weapon") ds.weaponIds[row].clear();
    else if (*slotToClear == "armor") ds.armorIds[row].clear();
    else if (*slotToClear == "boots") ds.bootsIds[row].clear();
    else ds.accessoryIds[row].clear();
    return true;
}

/// 11 类槽位清理（clearAllSlotsDataOnly 的 GameState 打包/回写壳；
/// includeResidence=false——工作分配保留住所语义，与 Kotlin
/// DiscipleSlotCleanup.clearAllSlots 默认参一致）
inline void clearAllDiscipleSlots(GameState& state, const std::string& discipleId) {
    gamecore::system::SlotCleanupInput in;
    in.spiritMineSlots = state.gameData.spiritMineSlots;
    in.librarySlots = state.gameData.librarySlots;
    in.elderSlots = state.gameData.elderSlots;
    in.residenceSlots = state.gameData.residenceSlots;
    in.activeBloodRefinements = state.gameData.activeBloodRefinements;
    in.patrolSlots = state.gameData.patrolSlots;
    in.warehouseGarrisons = state.gameData.warehouseGarrisons;
    in.battleTeams = state.gameData.battleTeams;
    in.worldMapSects = state.gameData.worldMapSects;
    in.productionSlots = state.gameData.productionSlots;
    in.caveExplorationTeams = state.gameData.caveExplorationTeams;
    // S5 起完整 ActiveMission——清理 op 协议仍为 Lite（month_settlement.h 同壳）
    in.activeMissions =
        gamecore::system::toMissionLiteList(state.gameData.activeMissions);
    const auto out =
        gamecore::system::clearAllSlotsDataOnly(in, discipleId, /*includeResidence=*/false);
    state.gameData.spiritMineSlots = out.spiritMineSlots;
    state.gameData.librarySlots = out.librarySlots;
    state.gameData.elderSlots = out.elderSlots;
    state.gameData.residenceSlots = out.residenceSlots;
    state.gameData.activeBloodRefinements = out.activeBloodRefinements;
    state.gameData.patrolSlots = out.patrolSlots;
    state.gameData.warehouseGarrisons = out.warehouseGarrisons;
    state.gameData.battleTeams = out.battleTeams;
    state.gameData.worldMapSects = out.worldMapSects;
    state.gameData.productionSlots = out.productionSlots;
    state.gameData.caveExplorationTeams = out.caveExplorationTeams;
    state.gameData.activeMissions =
        gamecore::system::mergeMissionLiteList(state.gameData.activeMissions,
                                               out.activeMissions);
}

// ── 亲传槽列表访问（DiscipleFacadeImpl getElderSlotOccupant/replaceElderSlot
//    的 7 类分发；未知类型 = Kotlin else 分支 no-op/空 occupant）───────────

inline std::vector<gamecore::state::DirectDiscipleSlot>* directSlotList(
    gamecore::state::ElderSlots& slots, const std::string& elderSlotType) {
    if (elderSlotType == "herbGarden") return &slots.herbGardenDisciples;
    if (elderSlotType == "alchemy") return &slots.alchemyDisciples;
    if (elderSlotType == "forge") return &slots.forgeDisciples;
    if (elderSlotType == "preaching") return &slots.preachingMasters;
    if (elderSlotType == "lawEnforcement") return &slots.lawEnforcementDisciples;
    if (elderSlotType == "qingyunPreaching") return &slots.qingyunPreachingMasters;
    if (elderSlotType == "spiritMineDeacon") return &slots.spiritMineDeaconDisciples;
    return nullptr;
}

}  // namespace detail

// 事务函数作用域的 using 声明（batch-09 协调性最小补全——原仅限于
// detail 内可见，detail 外的 inline 事务函数非限定名 GameState/DiscipleStore/
// EquipmentStack/EquipmentInstance/ManualStack/ManualInstance/StorageBagItem
// 无法解析，任何包含序下均无法编译；与 detail 内声明同源，纯加法无语义变更）
using gamecore::state::Disciple;
using gamecore::state::DiscipleStore;
using gamecore::state::EquipmentInstance;
using gamecore::state::EquipmentStack;
using gamecore::state::GameState;
using gamecore::state::ManualInstance;
using gamecore::state::ManualStack;
using gamecore::state::StorageBagItem;
namespace settle_util = gamecore::system::settle_util;

// ── W4-A w3-01 事务函数作用域补全（同上款口径：仓库四表 + 血炼进度）──
using gamecore::state::BloodRefinementProgress;
using gamecore::state::Herb;
using gamecore::state::Material;
using gamecore::state::Pill;
using gamecore::state::Seed;

// ── 结果信封（Kotlin DomainResult.Failure 分型 + 运行态草稿）────────────

/// 事务结果基型：errorType 与 Kotlin AppError.Domain.Disciple 分型同名
///（NotFound/AlreadyEquipped/RealmTooLow/SlotInvalid），失败信封经
/// execute_dispatch fail(code) 回传 → Kotlin 回退原路径重执行校验链
///（双实现并行契约，production.h 同模式）
struct DiscipleTxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
    // 偷盗判定钩子回执（仅 facade 丹药链生效路径填装；执法域不下沉）
    bool theftCandidate = false;
    int32_t moralityAfter = 0;
};

/// 穿装结果：附 equip 日志草稿（Kotlin lifeEvents 瞬态列回写；
/// 空串 = 无日志——与 Kotlin recordEquipLog 写点对齐，成功必有日志）
struct EquipResult {
    DiscipleTxResult base;
    std::string logLine;   // "${age}岁：装备了X / 将旧装备替换为X"
};

/// 任命结果：附事务前目标槽 occupant（Kotlin clearAllSlots 后捕获语义——
/// 若上任弟子原已在目标槽，clear 后捕获为空串；gate.release 由 Kotlin 分支执行）
struct AssignSlotResult {
    DiscipleTxResult base;
    std::string oldOccupantId;
};

/// 卸任结果：附被卸任者 id（空串 = 槽原本无人；gate.release 由 Kotlin 分支执行）
struct UnassignSlotResult {
    DiscipleTxResult base;
    std::string removedDiscipleId;
};

// ── 事务 1：装备穿戴（DiscipleEquipmentService.equipEquipmentInTransaction）──
//
// 校验链（逐字对齐 Kotlin 判定序）：弟子存在 → 装备存在（堆叠/实例双轨道
// 查找，双缺 NotFound）→ 实例已穿戴 AlreadyEquipped → 境界 RealmTooLow →
// 旧装备卸下失败 SlotInvalid。写段：仓库堆叠 -1/移除 + 实例铸造入表 +
// 弟子槽位列写（堆叠轨道）；或实例置位 + 槽位列写（实例轨道）。
inline EquipResult equipTransaction(GameState& state, const std::string& discipleId,
                                    const std::string& equipmentId) {
    EquipResult out;
    DiscipleStore& ds = state.disciples;

    // 1. 弟子存在（Kotlin toIntOrNull + ids.contains）
    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value() || !ds.contains(discipleId)) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子或装备不存在 " + discipleId;
        return out;
    }
    const std::size_t row = *ds.rowOf(discipleId);

    // 2. 装备双轨道查找（堆叠优先——Kotlin equipmentStacks.get 先于
    //    equipmentInstances.get）
    EquipmentStack* stack = detail::findEquipmentStack(state, equipmentId);
    EquipmentInstance* inst = detail::findEquipmentInstance(state, equipmentId);
    if (stack == nullptr && inst == nullptr) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子或装备不存在 " + discipleId;
        return out;
    }

    // 3. 占用/境界校验（实例轨道检查已穿戴；堆叠轨道天然未穿戴）
    const int32_t realm = ds.realms[row];
    if (inst != nullptr) {
        if (inst->isEquipped) {
            out.base.errorType = "AlreadyEquipped";
            out.base.message = "装备已穿戴 " + inst->slot;
            return out;
        }
        if (realm > inst->minRealm) {
            out.base.errorType = "RealmTooLow";
            out.base.message = "境界不足 需要" + std::to_string(inst->minRealm);
            return out;
        }
    } else if (realm > stack->minRealm) {
        out.base.errorType = "RealmTooLow";
        out.base.message = "境界不足 需要" + std::to_string(stack->minRealm);
        return out;
    }

    // 4. 槽位与名称（equipName 堆叠优先——Kotlin recordEquipLog 字段序）
    const std::string slot = inst != nullptr ? inst->slot : stack->slot;
    if (!detail::isEquipSlotName(slot)) {
        // Kotlin EquipmentSlot 为四值枚举，本臂协议不可达（损坏镜像行防御）
        out.base.errorType = "SlotInvalid";
        out.base.message = "无法确定装备槽位";
        return out;
    }
    const std::string equipName = stack != nullptr ? stack->name : inst->name;

    // 5. 旧装备卸下（失败中止穿戴——此时零写入）。卸下会 erase 实例表，
    //    悬垂纪律（auto_gear.h 同款）：穿装所需字段先拷贝，写段重查指针
    const std::string& oldEquipId = detail::equipSlotId(ds, row, slot);
    std::string oldEquipIdCopy = oldEquipId;
    std::string instIdCopy;
    if (inst != nullptr) instIdCopy = inst->id;
    std::string stackIdCopy;
    if (stack != nullptr) stackIdCopy = stack->id;
    if (!oldEquipIdCopy.empty()) {
        if (!detail::unequipInternal(state, ds, row, oldEquipIdCopy)) {
            out.base.errorType = "SlotInvalid";
            out.base.message = "卸下旧装备失败 " + oldEquipIdCopy;
            return out;
        }
    }

    // 6. 穿戴新装备（wearEquipment 等价：堆叠扣减/实例置位 + 槽位列写）
    std::string wornId;
    if (stack != nullptr) {
        EquipmentInstance worn = detail::equipmentInstanceFromStack(*stack, discipleId);
        wornId = worn.id;
        state.equipmentInstances.push_back(std::move(worn));
        detail::deductEquipmentStack(state.equipmentStacks, stackIdCopy);
    } else {
        wornId = instIdCopy;
        EquipmentInstance* wornInst = detail::findEquipmentInstance(state, instIdCopy);
        wornInst->isEquipped = true;
        wornInst->ownerId = discipleId;
    }
    detail::setEquipSlot(ds, row, slot, wornId);

    // 7. 装备日志草稿（recordEquipLog 等价——oldName 在卸下/穿戴**之后**查
    //    实例表，Kotlin 活路径恒落"旧装备"兜底；Kotlin native 分支回写瞬态列）
    const int32_t age = ds.ages[row];
    const EquipmentInstance* oldAfter = oldEquipIdCopy.empty()
        ? nullptr
        : detail::findEquipmentInstance(state, oldEquipIdCopy);
    const std::string oldName = oldAfter != nullptr ? oldAfter->name : "旧装备";
    out.base.ok = true;
    out.logLine = oldEquipIdCopy.empty()
        ? std::to_string(age) + "岁：装备了" + equipName
        : std::to_string(age) + "岁：将" + oldName + "替换为" + equipName;
    return out;
}

// ── 事务 2：装备卸下（unequipEquipment + unequipEquipmentLogic 等价）────────
//
// 校验链：弟子存在 → 四槽位穿戴匹配（未穿戴 SlotInvalid）。写段：实例入袋
// + 实例表移除 + 槽位列清（实例缺失损坏态仅清槽，与 Kotlin 同分支）。
inline DiscipleTxResult unequipTransaction(GameState& state,
                                           const std::string& discipleId,
                                           const std::string& equipmentId) {
    DiscipleTxResult out;
    DiscipleStore& ds = state.disciples;
    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value() || !ds.contains(discipleId)) {
        out.errorType = "NotFound";
        out.message = "弟子不存在 " + discipleId;
        return out;
    }
    const std::size_t row = *ds.rowOf(discipleId);
    const bool isEquipped = ds.weaponIds[row] == equipmentId ||
                            ds.armorIds[row] == equipmentId ||
                            ds.bootsIds[row] == equipmentId ||
                            ds.accessoryIds[row] == equipmentId;
    if (!isEquipped) {
        out.errorType = "SlotInvalid";
        out.message = "装备未穿戴在弟子身上";
        return out;
    }
    out.ok = detail::unequipInternal(state, ds, row, equipmentId);
    if (!out.ok) {
        out.errorType = "SlotInvalid";
        out.message = "装备未穿戴在弟子身上";
    }
    return out;
}

// ── 事务 3：功法学习（GameEngineManualOps.learnManual 等价）────────────────
//
// 校验链（canLearnManualFromStack 判定序）：堆叠存在 → 弟子存在 → 境界 →
// 名额（6+manualSlot）→ 心法唯一 → 同名唯一。写段：堆叠 -1/移除 + 实例铸造
// + manualIds 追加 + currentHp/Mp 增量（rawHp>=0 且增益为正才加）。
// 静默守卫（堆叠缺失/弟子缺失）在 Kotlin 为 silent return——C++ 以失败信封
// 返回，Kotlin native 分支回退原路径重执行同义静默（双实现并行契约）。
inline DiscipleTxResult learnManualTransaction(GameState& state,
                                               const std::string& discipleId,
                                               const std::string& stackId) {
    DiscipleTxResult out;
    DiscipleStore& ds = state.disciples;

    ManualStack* stack = detail::findManualStack(state, stackId);
    if (stack == nullptr) {
        out.errorType = "NotFound";
        out.message = "功法堆叠不存在 " + stackId;
        return out;
    }
    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value() || !ds.contains(discipleId)) {
        out.errorType = "NotFound";
        out.message = "弟子不存在 " + discipleId;
        return out;
    }
    const std::size_t row = *ds.rowOf(discipleId);
    const Disciple d = ds.materialize(row);

    // 资格守卫（Kotlin 判定序：境界 → 名额 → 心法唯一 → 同名唯一）
    if (d.realm > stack->minRealm) {
        out.errorType = "RealmTooLow";
        out.message = "境界不足";
        return out;
    }
    const int32_t maxSlots = detail::maxManualSlotsFor(d);
    if (static_cast<int32_t>(ds.manualIds[row].size()) >= maxSlots) {
        out.errorType = "SlotsFull";
        out.message = "功法名额已满";
        return out;
    }
    auto learnedOf = [&](const std::string& mid) -> const ManualInstance* {
        return detail::findManualInstance(state, mid);
    };
    if (stack->type == "MIND") {
        for (const std::string& mid : ds.manualIds[row]) {
            const ManualInstance* mn = learnedOf(mid);
            if (mn != nullptr && mn->type == "MIND") {
                out.errorType = "MindDuplicate";
                out.message = "已修习心法";
                return out;
            }
        }
    }
    for (const std::string& mid : ds.manualIds[row]) {
        const ManualInstance* mn = learnedOf(mid);
        if (mn != nullptr && mn->name == stack->name) {
            out.errorType = "NameDuplicate";
            out.message = "已修习同名功法";
            return out;
        }
    }

    // 写段：实例铸造 → 堆叠消耗 → manualIds/HP/MP（applyLearnedManualSnapshot）。
    // 铸造先行 + id 拷贝：整摞消耗的 erase 会使 stack 指针悬垂（悬垂纪律）
    const ManualStack stackCopy = *stack;
    ManualInstance inst = detail::manualInstanceFromStack(stackCopy, discipleId);
    const std::string newInstanceId = inst.id;
    state.manualInstances.push_back(std::move(inst));
    detail::deductManualStack(state.manualStacks, stackCopy.id);
    ds.manualIds[row].push_back(newInstanceId);
    const auto& stats = stackCopy.stats;
    auto statOf = [&](const char* a, const char* b) -> int32_t {
        const auto it = stats.find(a);
        if (it != stats.end()) return it->second;
        const auto it2 = stats.find(b);
        return it2 != stats.end() ? it2->second : 0;
    };
    const int32_t hpDelta = statOf("hp", "maxHp");
    const int32_t mpDelta = statOf("mp", "maxMp");
    if (ds.currentHps[row] >= 0 && hpDelta > 0) ds.currentHps[row] += hpDelta;
    if (ds.currentMps[row] >= 0 && mpDelta > 0) ds.currentMps[row] += mpDelta;
    out.ok = true;
    return out;
}

// ── 事务 4：功法卸下/遗忘（GameEngineManualOps.forgetManual 等价）──────────
//
// 写段：实例铸造入袋 + manualIds 移除 + 实例表移除（防双持有）+ 熟练度清理
//（manualProficiencies 过滤，空则删键——clearRemovedManualProficiencies）。
inline DiscipleTxResult unlearnManualTransaction(GameState& state,
                                                 const std::string& discipleId,
                                                 const std::string& instanceId) {
    DiscipleTxResult out;
    DiscipleStore& ds = state.disciples;

    ManualInstance* inst = detail::findManualInstance(state, instanceId);
    if (inst == nullptr) {
        out.errorType = "NotFound";
        out.message = "功法实例不存在 " + instanceId;
        return out;
    }
    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value() || !ds.contains(discipleId)) {
        out.errorType = "NotFound";
        out.message = "弟子不存在 " + discipleId;
        return out;
    }
    const std::size_t row = *ds.rowOf(discipleId);

    // D-03：遗忘实例直接铸造入袋（容量无上限，永不失败）
    StorageBagItem entry;
    entry.itemId = instanceId;
    entry.itemType = "manual_instance";
    entry.name = inst->name;
    entry.rarity = inst->rarity;
    entry.quantity = 1;
    entry.obtainedYear = state.gameData.gameYear;
    entry.obtainedMonth = state.gameData.gameMonth;
    entry.manualInstance = *inst;
    detail::bagIncreaseItemQuantity(ds.storageBagItems[row], std::move(entry));

    // manualIds 移除 + 实例表移除（防双持有不变量）
    auto& mids = ds.manualIds[row];
    mids.erase(std::remove(mids.begin(), mids.end(), instanceId), mids.end());
    auto& minst = state.manualInstances;
    minst.erase(std::remove_if(minst.begin(), minst.end(),
                               [&](const ManualInstance& x) {
                                   return x.id == instanceId;
                               }),
                minst.end());

    // 熟练度清理（Kotlin gameData.manualProficiencies 同名键过滤）
    auto& profMap = state.gameData.manualProficiencies;
    const auto pit = profMap.find(discipleId);
    if (pit != profMap.end()) {
        std::vector<gamecore::state::ManualProficiencyData> kept;
        for (const auto& p : pit->second) {
            if (p.manualId != instanceId) kept.push_back(p);
        }
        if (kept.empty()) profMap.erase(pit);
        else pit->second = std::move(kept);
    }
    out.ok = true;
    return out;
}

// ── 槽位族（DiscipleFacadeImpl 任命/卸任数据写段）────────────────────────

/// 槽位族：亲传槽（7 类 DirectDiscipleSlot 列表）/ 藏经阁槽
enum class SlotFamily { kElderDirect, kLibrary };

// ── 事务 5：任命（assignDirectDisciple / assignDiscipleToLibrarySlot）──────
//
// 写段：clearAllSlots（11 类槽位，includeResidence=false—— disciples 有效时，
// Kotlin 判定序）→ 目标槽 occupant 捕获（clear **之后**——上任弟子原占目标槽
// 时空串）→ 槽位覆写（亲传槽扩容补空 + sectId=activeSectId；藏经阁扩容补空）。
// 未知 elderSlotType = Kotlin else 分支：仅 clearAllSlots、无槽位写、照常成功。
// 弟子不存在：Kotlin 跳过 clearAllSlots 但照常写槽/成功（UseCase 层预校验
// 存在/存活）——逐字对齐。
inline AssignSlotResult assignSlotTransaction(
    GameState& state, SlotFamily family, const std::string& elderSlotType,
    int32_t slotIndex, const std::string& discipleId, const std::string& discipleName,
    const std::string& discipleRealm, const std::string& spiritRootColor) {
    AssignSlotResult out;
    auto& gd = state.gameData;

    // 1. 释放旧槽位（弟子 id 有效才清——Kotlin toIntOrNull + ids.contains 门）
    const auto intId = settle_util::toIntOrNull(discipleId);
    const bool validDisciple = intId.has_value() && state.disciples.contains(discipleId);
    if (validDisciple) {
        detail::clearAllDiscipleSlots(state, discipleId);
    }

    if (family == SlotFamily::kElderDirect) {
        // 2. 目标槽旧 occupant 捕获（clearAllSlots 后、覆写前——原列表）
        gamecore::state::DirectDiscipleSlot* target = nullptr;
        if (std::vector<gamecore::state::DirectDiscipleSlot>* list =
                detail::directSlotList(gd.elderSlots, elderSlotType)) {
            if (slotIndex >= 0 &&
                slotIndex < static_cast<int32_t>(list->size())) {
                out.oldOccupantId = (*list)[slotIndex].discipleId;
            }
            // 3. 扩容补空 + 覆写（replaceElderSlot/growElderSlotList 等价：
            //    占位槽 = Kotlin DirectDiscipleSlot() 默认值，index 恒 0）
            while (static_cast<int32_t>(list->size()) <= slotIndex) {
                list->push_back(gamecore::state::DirectDiscipleSlot());
            }
            target = &(*list)[slotIndex];
            gamecore::state::DirectDiscipleSlot slot;
            slot.index = slotIndex;
            slot.discipleId = discipleId;
            slot.discipleName = discipleName;
            slot.discipleRealm = discipleRealm;
            slot.discipleSpiritRootColor = spiritRootColor;
            slot.sectId = gd.activeSectId;
            *target = slot;
        }
        // 未知类型（list == nullptr）：Kotlin else → 无槽位写、照常成功
    } else {
        // 藏经阁：occupant 捕获（扩容前）→ 扩容补空 → 覆写
        if (slotIndex >= 0 && slotIndex < static_cast<int32_t>(gd.librarySlots.size())) {
            out.oldOccupantId = gd.librarySlots[slotIndex].discipleId;
        }
        while (static_cast<int32_t>(gd.librarySlots.size()) <= slotIndex) {
            gamecore::state::LibrarySlot slot;
            slot.index = static_cast<int32_t>(gd.librarySlots.size());
            gd.librarySlots.push_back(slot);
        }
        gamecore::state::LibrarySlot slot;
        slot.index = slotIndex;
        slot.discipleId = discipleId;
        slot.discipleName = discipleName;
        gd.librarySlots[slotIndex] = slot;
    }
    out.base.ok = true;
    return out;
}

// ── 事务 6：卸任（removeDirectDisciple / removeDiscipleFromLibrarySlot）────
//
// 亲传槽：单槽重置（resetDirectSlotAt——越界原样返回；未知类型 no-op）。
// 藏经阁：越界 no-op（Kotlin bounds 早退），单槽重置。
inline UnassignSlotResult unassignSlotTransaction(GameState& state, SlotFamily family,
                                                  const std::string& elderSlotType,
                                                  int32_t slotIndex) {
    UnassignSlotResult out;
    auto& gd = state.gameData;

    if (family == SlotFamily::kElderDirect) {
        if (std::vector<gamecore::state::DirectDiscipleSlot>* list =
                detail::directSlotList(gd.elderSlots, elderSlotType)) {
            if (slotIndex >= 0 && slotIndex < static_cast<int32_t>(list->size())) {
                out.removedDiscipleId = (*list)[slotIndex].discipleId;
                gamecore::state::DirectDiscipleSlot empty;
                empty.index = slotIndex;
                (*list)[slotIndex] = empty;
            }
        }
    } else {
        if (slotIndex < 0 || slotIndex >= static_cast<int32_t>(gd.librarySlots.size())) {
            out.base.ok = true;  // Kotlin bounds 早退 = 静默成功
            return out;
        }
        out.removedDiscipleId = gd.librarySlots[slotIndex].discipleId;
        gamecore::state::LibrarySlot empty;
        empty.index = slotIndex;
        gd.librarySlots[slotIndex] = empty;
    }
    out.base.ok = true;
    return out;
}

// ═══════════════════════════════════════════════════════════════════
// W4-A·w3-01 弟子操作面事务（ActionId 1740–1748）
//
// 语义权威 = 各 Kotlin 源文件（等价移植，逐字对齐）：
//  - GameEngineCoordination.renameDisciple / changeDiscipleTypeAtomic
//  - DiscipleDelegate.toggleFollowDisciple（statusData["followed"] 段）
//  - DiscipleFacadeImpl战斗Ops2.rewardPill/rewardMaterial/rewardHerb/
//    rewardSeed/usePill + DiscipleFacadeImpl.applyPillEffectsToDisciple
//    （**facade 丹药链**——与 auto-use 链 pill_system.h 的既知口径差异，
//      本节逐字采用 facade 口径：
//      ① 修为直加**无上限 clamp**（facade applyCultivationAddEffect）；
//      ② 治疗 maxHp 取 **baseHps 基列**（facade applyHealEffect），
//         mpRecover 不适用（facade 链无此分支）；
//      ③ 战斗/速率生效时清零旧 cultivationSpeedBonus 组件列 + checkpoint；
//      ④ 道德触发偷盗判定钩子**不下沉**（执法系统域，phase_settlement.h
//         同边界）——信封回传 moralityAfter，由 Kotlin native 分支原序判定）
//  - GameEngineManualOps.replaceManual（2026-09-15 核查新增稳态写者）
//  - GameEngineBloodRefinementOps.startBloodRefinementAtomic（血炼启动）
//  - DiscipleStatusService.syncAll/syncSingle（派生列唯一计算方 = C++）
//
// 零 RNG：全部事务无随机抽取（canUsePill/derive 为纯判定）。
// 失败臂零写入：校验链先行（Kotlin 事务 throw 回滚 ⇒ C++ 校验先行等价）。
// ═══════════════════════════════════════════════════════════════════

namespace detail {

/// Pill → ItemEffect（DisciplePillManager.pillToItemEffect 逐字段等价；
/// disciple_purchase.h 有同名映射，但为避免把 merchant/rng/ecs 传递引入
/// 本头（batch-09 include-order 纪律），此处做只读复制。tier = pill.rarity
/// ——Kotlin "rarity 直接映射为品阶"）
inline gamecore::state::ItemEffect facadeItemEffect(const Pill& pill) {
    gamecore::state::ItemEffect e;
    e.tier = pill.rarity;
    const gamecore::state::PillEffect& f = pill.effects;
    e.cultivationSpeedPercent = f.cultivationSpeedPercent;
    e.skillExpSpeedPercent = f.skillExpSpeedPercent;
    e.nurtureSpeedPercent = f.nurtureSpeedPercent;
    e.breakthroughChance = f.breakthroughChance;
    e.targetRealm = f.targetRealm;
    e.cultivationAdd = f.cultivationAdd;
    e.skillExpAdd = f.skillExpAdd;
    e.nurtureAdd = f.nurtureAdd;
    e.healMaxHpPercent = f.healMaxHpPercent;
    e.mpRecoverMaxMpPercent = f.mpRecoverMaxMpPercent;
    e.hpAdd = f.hpAdd;
    e.mpAdd = f.mpAdd;
    e.extendLife = f.extendLife;
    e.physicalAttackAdd = f.physicalAttackAdd;
    e.magicAttackAdd = f.magicAttackAdd;
    e.physicalDefenseAdd = f.physicalDefenseAdd;
    e.magicDefenseAdd = f.magicDefenseAdd;
    e.speedAdd = f.speedAdd;
    e.critRateAdd = f.critRateAdd;
    e.critEffectAdd = f.critEffectAdd;
    e.intelligenceAdd = f.intelligenceAdd;
    e.charmAdd = f.charmAdd;
    e.loyaltyAdd = f.loyaltyAdd;
    e.comprehensionAdd = f.comprehensionAdd;
    e.artifactRefiningAdd = f.artifactRefiningAdd;
    e.pillRefiningAdd = f.pillRefiningAdd;
    e.spiritPlantingAdd = f.spiritPlantingAdd;
    e.teachingAdd = f.teachingAdd;
    e.moralityAdd = f.moralityAdd;
    e.miningAdd = f.miningAdd;
    e.revive = f.revive;
    e.clearAll = f.clearAll;
    e.isAscension = f.isAscension;
    e.duration = f.duration;
    e.cannotStack = f.cannotStack;
    e.minRealm = pill.minRealm;
    e.pillCategory = pill.category;
    e.pillType = pill.pillType;
    return e;
}

/// PillGrade.name → displayName（Kotlin PillGrade.displayName：LOW 下品 /
/// MEDIUM 中品 / HIGH 上品；未知按中品防御）
inline const char* gradeDisplayName(const std::string& grade) {
    if (grade == "LOW") return "下品";
    if (grade == "HIGH") return "上品";
    return "中品";
}

inline bool containsValue(const std::vector<std::string>& list, const std::string& v) {
    return std::find(list.begin(), list.end(), v) != list.end();
}

/// 仓库堆叠扣减（按 id；quantity==n → 移除，否则 -n。pills/materials/herbs/
/// seeds 四表同构——Kotlin XxxStore.remove/update 同义）
template <typename T>
inline void deductStackById(std::vector<T>& store, const std::string& id,
                            int32_t quantity) {
    for (auto it = store.begin(); it != store.end(); ++it) {
        if (it->id != id) continue;
        if (it->quantity == quantity) {
            store.erase(it);
        } else {
            it->quantity -= quantity;
        }
        return;
    }
}

template <typename T>
inline T* findStackById(std::vector<T>& store, const std::string& id) {
    for (auto& s : store) {
        if (s.id == id) return &s;
    }
    return nullptr;
}

/// 偷盗判定钩子回执（执法域不下沉——阈值 MORALITY_THRESHOLD 是 config 值，
/// C++ 不持有）：Kotlin native 分支在 theftCandidate 且 moralityAfter 低于
/// 阈值时原序执行 processSingleDiscipleTheft（facade 事务内判定等价回执驱动）
struct FacadePillOutcome {
    int32_t moralityAfter = 0;
    bool baseAttrApplied = false;  // 偷盗判定钩子的前置分支（applyBaseAttrEffects）
};

/// facade 丹药链（applyPillEffectsToDisciple 逐分支等价；列级直写，
/// 不经 materialize——与 Kotlin discipleTables 直写同构）。
inline FacadePillOutcome applyFacadePillEffects(GameState& state, std::size_t row,
                                                const Pill& pill) {
    DiscipleStore& ds = state.disciples;
    const gamecore::state::PillEffect& effect = pill.effects;
    const gamecore::state::ItemEffect ie = facadeItemEffect(pill);

    // ① 修炼值（facade：直加无 clamp）
    if (effect.cultivationAdd > 0) ds.cultivations[row] += effect.cultivationAdd;

    // ② 功法经验（全部熟练度 +add，上限 10000）
    if (effect.skillExpAdd > 0) {
        for (auto& kv : ds.manualMasteries[row]) {
            kv.second = std::min(kv.second + effect.skillExpAdd,
                                 static_cast<int32_t>(10000));
        }
    }

    // ③ 延寿（lifespan += + pillType 去重登记）
    if (effect.extendLife > 0) {
        ds.lifespans[row] += effect.extendLife;
        if (!pill.pillType.empty() &&
            !containsValue(ds.usedExtendLifePillTypes[row], pill.pillType)) {
            ds.usedExtendLifePillTypes[row].push_back(pill.pillType);
        }
    }

    // ④ 永久基础属性（boundedAdd 0..200 / loyalty 0..100；mining 0..200）
    const auto boundedAdd = [](int32_t v, int32_t add, int32_t max) {
        return std::clamp(v + add, 0, max);
    };
    FacadePillOutcome outcome;
    if (gamecore::pill::hasAnyBaseAttrAdd(ie)) {
        outcome.baseAttrApplied = true;
        constexpr int32_t kSkillCap = 200;      // GameConfig.Disciple.SKILL_MAX
        constexpr int32_t kLoyaltyCap = 100;    // GameConfig.Disciple.MAX_LOYALTY
        ds.intelligences[row] = boundedAdd(ds.intelligences[row], effect.intelligenceAdd, kSkillCap);
        ds.charms[row] = boundedAdd(ds.charms[row], effect.charmAdd, kSkillCap);
        ds.loyalties[row] = boundedAdd(ds.loyalties[row], effect.loyaltyAdd, kLoyaltyCap);
        ds.comprehensions[row] = boundedAdd(ds.comprehensions[row], effect.comprehensionAdd, kSkillCap);
        ds.artifactRefinings[row] = boundedAdd(ds.artifactRefinings[row], effect.artifactRefiningAdd, kSkillCap);
        ds.pillRefinings[row] = boundedAdd(ds.pillRefinings[row], effect.pillRefiningAdd, kSkillCap);
        ds.spiritPlantings[row] = boundedAdd(ds.spiritPlantings[row], effect.spiritPlantingAdd, kSkillCap);
        ds.teachings[row] = boundedAdd(ds.teachings[row], effect.teachingAdd, kSkillCap);
        ds.moralities[row] = boundedAdd(ds.moralities[row], effect.moralityAdd, kSkillCap);
        // 偷盗判定钩子不下沉（见节首注释④）：moralityAfter 经信封回传
        ds.minings[row] = boundedAdd(ds.minings[row], effect.miningAdd, kSkillCap);
        // 使用登记（canUsePill 已保证无既有 key ⇒ 去重追加与 Kotlin usedKeys+keys 等价）
        for (const auto& k : gamecore::pill::buildUsedKeys(ie, ie.tier)) {
            if (!containsValue(ds.usedPermanentPillKeys[row], k)) {
                ds.usedPermanentPillKeys[row].push_back(k);
            }
        }
    }

    // ⑤ 战斗/速率持续加成（整体覆写 + 时长取最大 + SUSTAINED/TEMP 登记）
    const bool hasBattleOrSpeed = gamecore::pill::hasAnyBattleAttrAdd(ie) ||
        effect.cultivationSpeedPercent > 0 || effect.skillExpSpeedPercent > 0 ||
        effect.nurtureSpeedPercent > 0;
    if (hasBattleOrSpeed) {
        ds.pillPhysicalAttackBonuses[row] = effect.physicalAttackAdd;
        ds.pillMagicAttackBonuses[row] = effect.magicAttackAdd;
        ds.pillPhysicalDefenseBonuses[row] = effect.physicalDefenseAdd;
        ds.pillMagicDefenseBonuses[row] = effect.magicDefenseAdd;
        ds.pillHpBonuses[row] = effect.hpAdd;
        ds.pillMpBonuses[row] = effect.mpAdd;
        ds.pillSpeedBonuses[row] = effect.speedAdd;
        ds.pillCritRateBonuses[row] = effect.critRateAdd;
        ds.pillCritEffectBonuses[row] = effect.critEffectAdd;
        ds.pillCultivationSpeedBonuses[row] = effect.cultivationSpeedPercent;
        ds.pillSkillExpSpeedBonuses[row] = effect.skillExpSpeedPercent;
        ds.pillNurtureSpeedBonuses[row] = effect.nurtureSpeedPercent;
        // 以旬为单位（facade：不再 *30）
        ds.pillEffectDurations[row] = effect.duration > 0
            ? std::max(ds.pillEffectDurations[row], effect.duration)
            : ds.pillEffectDurations[row];
        const gamecore::pill::PillRule rule = gamecore::pill::classify(ie);
        const bool stackingRule = rule == gamecore::pill::PillRule::kSustainedSpeed ||
            rule == gamecore::pill::PillRule::kTemporaryBattle;
        if (stackingRule && !pill.pillType.empty() &&
            !containsValue(ds.activePillTypes[row], pill.pillType)) {
            ds.activePillTypes[row].push_back(pill.pillType);
        }
        // 旧 cultivationSpeed 组件列清零（残留数据自愈——facade 同款）
        ds.cultivationSpeedBonuses[row] = 0.0;
        ds.cultivationSpeedDurations[row] = 0;
        // 速率变化点同步 checkpoint（facade：速率列被改时必须同步，否则
        // getEffectiveCultivation 用旧速率推导）
        if (effect.cultivationSpeedPercent > 0 || effect.skillExpSpeedPercent > 0 ||
            effect.nurtureSpeedPercent > 0) {
            ds.cultivationCheckpoints[row] = ds.cultivations[row];
            ds.cultivationCheckpointGameMonths[row] =
                state.gameData.gameYear * 12 + state.gameData.gameMonth;
        }
    }

    // ⑥ 治疗（facade：maxHp = baseHps 基列；无 MP 恢复分支）
    if (effect.healMaxHpPercent > 0) {
        const int32_t maxHp = ds.baseHps[row];
        const int32_t currentHp = ds.currentHps[row] < 0 ? maxHp : ds.currentHps[row];
        const int32_t healAmount = std::max(
            static_cast<int32_t>(static_cast<double>(maxHp) * effect.healMaxHpPercent), 1);
        ds.currentHps[row] = std::min(currentHp + healAmount, maxHp);
    }

    // ⑦ 清除所有临时效果
    if (effect.clearAll) {
        ds.pillPhysicalAttackBonuses[row] = 0;
        ds.pillMagicAttackBonuses[row] = 0;
        ds.pillPhysicalDefenseBonuses[row] = 0;
        ds.pillMagicDefenseBonuses[row] = 0;
        ds.pillHpBonuses[row] = 0;
        ds.pillMpBonuses[row] = 0;
        ds.pillSpeedBonuses[row] = 0;
        ds.pillEffectDurations[row] = 0;
        ds.pillCritRateBonuses[row] = 0.0;
        ds.pillCritEffectBonuses[row] = 0.0;
        ds.pillCultivationSpeedBonuses[row] = 0.0;
        ds.pillSkillExpSpeedBonuses[row] = 0.0;
        ds.pillNurtureSpeedBonuses[row] = 0.0;
        ds.activePillCategories[row] = "";
        ds.activePillTypes[row].clear();
    }
    outcome.moralityAfter = ds.moralities[row];
    return outcome;
}

/// 丹药入袋条目（rewardPill 的 StorageBagItem 构造段等价）
inline StorageBagItem pillBagItem(const Pill& pill, int32_t quantity) {
    StorageBagItem entry;
    entry.itemId = pill.id;
    entry.itemType = "pill";
    entry.name = pill.name;
    entry.rarity = pill.rarity;
    entry.quantity = quantity;
    entry.obtainedYear = 0;  // 由调用方回填（gameYear/gameMonth）
    entry.obtainedMonth = 0;
    entry.effect = facadeItemEffect(pill);
    entry.grade = gradeDisplayName(pill.grade);
    entry.stackedData = gamecore::state::BagStackedData();
    return entry;
}

// ── 状态派生（DiscipleStatusService 纯函数族等价；派生列唯一计算方）────

/// 槽位归属标志（SlotFlags 等价）
struct SlotFlags {
    bool inGarrison = false;
    bool inWarehouseGarrison = false;
    bool inTeam = false;
    bool inSecretRealm = false;
    bool lawEnforcing = false;
    bool preaching = false;
    bool deaconing = false;
    bool managing = false;
    bool studying = false;
    bool mining = false;
    bool patrolling = false;
    bool alchemy = false;
    bool forge = false;
    bool spiritPlanting = false;
};

/// 状态名（DiscipleStatus.name——statuses 列即 name 字符串）
constexpr const char* kStatusIdle = "IDLE";
constexpr const char* kStatusDead = "DEAD";
constexpr const char* kStatusOnMission = "ON_MISSION";
constexpr const char* kStatusReflecting = "REFLECTING";
constexpr const char* kStatusRefining = "REFINING";

/// deriveDiscipleStatus（优先级序与 Kotlin 表逐项一致——状态推导契约，
/// 顺序不可变）：死亡 → 活跃任务 → 受保护（REFLECTING/REFINING）→
/// 秘境 → 仓库驻守 → 据点驻守 → 队伍 → 执法 → 传道 → 执事 → 管理 →
/// 学习 → 采矿 → 巡视 → 炼丹 → 锻造 → 灵植 → 空闲
inline const char* deriveDiscipleStatus(bool isAlive, const std::string& currentStatus,
                                        const SlotFlags& f, bool hasActiveMission) {
    if (!isAlive) return kStatusDead;
    if (hasActiveMission) return kStatusOnMission;
    if (currentStatus == kStatusReflecting) return kStatusReflecting;
    if (currentStatus == kStatusRefining) return kStatusRefining;
    if (f.inSecretRealm) return "SECRET_REALM";
    if (f.inWarehouseGarrison) return "WAREHOUSE_GARRISON";
    if (f.inGarrison) return "GARRISONING";
    if (f.inTeam) return "IN_TEAM";
    if (f.lawEnforcing) return "LAW_ENFORCING";
    if (f.preaching) return "PREACHING";
    if (f.deaconing) return "DEACONING";
    if (f.managing) return "MANAGING";
    if (f.studying) return "STUDYING";
    if (f.mining) return "MINING";
    if (f.patrolling) return "PATROLLING";
    if (f.alchemy) return "ALCHEMY";
    if (f.forge) return "FORGE";
    if (f.spiritPlanting) return "SPIRIT_PLANTING";
    return kStatusIdle;
}

/// buildSlotFlagsFor（单弟子 14 flag 全量扫描版——Kotlin 纯函数同构）
inline SlotFlags buildSlotFlags(const gamecore::state::GameData& gd,
                                const std::string& discipleId) {
    SlotFlags f;
    // 队伍/秘境/驻守（buildTeamFlags）
    for (const auto& team : gd.caveExplorationTeams) {
        const bool active = team.status == "TRAVELING" || team.status == "EXPLORING";
        if (active && containsValue(team.memberIds, discipleId)) f.inTeam = true;
    }
    if (gd.secretRealmState.id.empty() == false) {
        for (const auto& m : gd.secretRealmSession.members) {
            if (m.discipleId == discipleId && !m.isDead) f.inSecretRealm = true;
        }
    }
    for (const auto& sect : gd.worldMapSects) {
        if (!sect.isPlayerSect) continue;
        for (const auto& slot : sect.garrisonSlots) {
            if (slot.discipleId == discipleId) f.inGarrison = true;
        }
    }
    for (const auto& g : gd.warehouseGarrisons) {
        if (g.discipleId == discipleId) f.inWarehouseGarrison = true;
    }
    for (const auto& t : gd.battleTeams) {
        for (const auto& s : t.slots) {
            if (s.discipleId == discipleId) f.inTeam = true;
        }
    }
    // 执法/传道/执事/管理（buildOfficerFlags + buildManagingFlag）
    const auto& es = gd.elderSlots;
    if (es.lawEnforcementElder == discipleId) f.lawEnforcing = true;
    for (const auto& s : es.lawEnforcementDisciples) {
        if (s.discipleId == discipleId) f.lawEnforcing = true;
    }
    if (es.preachingElder == discipleId || es.qingyunPreachingElder == discipleId) {
        f.preaching = true;
    }
    for (const auto& s : es.preachingMasters) {
        if (s.discipleId == discipleId) f.preaching = true;
    }
    for (const auto& s : es.qingyunPreachingMasters) {
        if (s.discipleId == discipleId) f.preaching = true;
    }
    for (const auto& s : es.spiritMineDeaconDisciples) {
        if (s.discipleId == discipleId) f.deaconing = true;
    }
    if (es.viceSectMaster == discipleId || es.outerElder == discipleId ||
        es.innerElder == discipleId || es.forgeElder == discipleId ||
        es.alchemyElder == discipleId || es.herbGardenElder == discipleId ||
        es.recruitingElder == discipleId) {
        f.managing = true;
    }
    for (const auto& s : es.herbGardenDisciples) {
        if (s.discipleId == discipleId) f.managing = true;
    }
    for (const auto& s : es.alchemyDisciples) {
        if (s.discipleId == discipleId) f.managing = true;
    }
    for (const auto& s : es.forgeDisciples) {
        if (s.discipleId == discipleId) f.managing = true;
    }
    // 学习/采矿/巡视/炼丹/锻造/灵植（buildProductionFlags）
    for (const auto& s : gd.librarySlots) {
        if (s.discipleId == discipleId) f.studying = true;
    }
    for (const auto& s : gd.spiritMineSlots) {
        if (s.discipleId == discipleId) f.mining = true;
    }
    for (const auto& s : gd.patrolSlots) {
        if (s.discipleId == discipleId) f.patrolling = true;
    }
    for (const auto& s : gd.productionSlots) {
        if (s.assignedDiscipleId.value_or("") == discipleId) {
            if (s.buildingId == "alchemy") f.alchemy = true;
            else if (s.buildingId == "forge") f.forge = true;
            else if (s.buildingId == "herbGarden") f.spiritPlanting = true;
        }
    }
    return f;
}

/// resolvePositionName（MANAGING 职位名解析——ElderSlots.resolvePositionName
/// 等价：长老职位在前、灵植/炼丹/锻造弟子在后；无职位返回空串，调用方以
/// MANAGING_FALLBACK 兜底）
inline std::string resolvePositionName(const gamecore::state::ElderSlots& es,
                                       const std::string& discipleId) {
    if (discipleId.empty()) return "";
    // formatSlotTypeName（10 长老槽 when 序——优先级即此序）
    if (es.viceSectMaster == discipleId) return "副宗主";
    if (es.herbGardenElder == discipleId) return "灵田长老";
    if (es.alchemyElder == discipleId) return "炼丹长老";
    if (es.forgeElder == discipleId) return "炼器长老";
    if (es.outerElder == discipleId) return "外门长老";
    if (es.innerElder == discipleId) return "内门长老";
    if (es.recruitingElder == discipleId) return "纳徒长老";
    if (es.preachingElder == discipleId) return "传道长老";
    if (es.qingyunPreachingElder == discipleId) return "青云传道长老";
    if (es.lawEnforcementElder == discipleId) return "执法长老";
    for (const auto& s : es.herbGardenDisciples) {
        if (s.discipleId == discipleId) return "灵植弟子";
    }
    for (const auto& s : es.alchemyDisciples) {
        if (s.discipleId == discipleId) return "炼丹弟子";
    }
    for (const auto& s : es.forgeDisciples) {
        if (s.discipleId == discipleId) return "锻造弟子";
    }
    return "";
}

constexpr const char* kManagingFallback = "管理中";
constexpr const char* kPositionNameKey = "positionName";

/// 单弟子状态派生 + 写入（syncStatusFromIndex 循环体等价）：状态变更才写
/// statuses 列；positionName 仅 MANAGING 写入（无职位以"管理中"兜底）/
/// 非 MANAGING 定向删除 key（保留血炼 buildingId 等他域 key——禁止整体覆写）
inline void deriveAndWriteDiscipleStatus(GameState& state, std::size_t row) {
    DiscipleStore& ds = state.disciples;
    if (ds.isAlive[row] != 1) return;
    const std::string discipleId = ds.ids[row];
    const std::string currentStatus = ds.statuses[row];
    // 活跃任务（activeMissions 成员含该弟子）
    bool hasActiveMission = false;
    for (const auto& mission : state.gameData.activeMissions) {
        if (containsValue(mission.discipleIds, discipleId)) {
            hasActiveMission = true;
            break;
        }
    }
    const SlotFlags flags = buildSlotFlags(state.gameData, discipleId);
    const char* newStatus =
        deriveDiscipleStatus(true, currentStatus, flags, hasActiveMission);
    if (currentStatus != newStatus) ds.statuses[row] = newStatus;
    // positionName 派生（writePositionName）
    auto& sd = ds.statusData[row];
    if (std::string(newStatus) == "MANAGING") {
        const std::string resolved = resolvePositionName(state.gameData.elderSlots, discipleId);
        const std::string value = resolved.empty() ? std::string(kManagingFallback) : resolved;
        const auto it = sd.find(kPositionNameKey);
        if (it == sd.end() || it->second != value) sd[kPositionNameKey] = value;
    } else {
        sd.erase(kPositionNameKey);
    }
}

/// fixInvalidMiningSlots（syncAll 前置自愈：灵矿槽引用不存在弟子 → 清空）
inline void fixInvalidMiningSlots(GameState& state) {
    DiscipleStore& ds = state.disciples;
    bool hasInvalid = false;
    for (const auto& slot : state.gameData.spiritMineSlots) {
        if (!slot.discipleId.empty() && !ds.contains(slot.discipleId)) {
            hasInvalid = true;
            break;
        }
    }
    if (!hasInvalid) return;
    for (auto& slot : state.gameData.spiritMineSlots) {
        if (!slot.discipleId.empty() && !ds.contains(slot.discipleId)) {
            slot.discipleId = "";
            slot.discipleName = "";
        }
    }
}

}  // namespace detail

// ── 事务 7：改名（GameEngineCoordination.renameDisciple 等价，1740）────────
//
// 写段：names 行写 + 招募列表同人净化（按**改名前**身份 isSamePerson 过滤——
// 改名破坏 5 字段签名匹配，不净化则残留双胞胎可被重复招募）。
inline DiscipleTxResult renameDiscipleTx(GameState& state,
                                         const std::string& discipleId,
                                         const std::string& newName) {
    DiscipleTxResult out;
    DiscipleStore& ds = state.disciples;
    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value() || !ds.contains(discipleId)) {
        out.errorType = "NotFound";
        out.message = "弟子不存在 " + discipleId;
        return out;
    }
    const std::size_t row = *ds.rowOf(discipleId);
    const Disciple before = ds.materialize(row);
    ds.names[row] = newName;
    auto& recruitList = state.gameData.recruitList;
    std::vector<Disciple> kept;
    kept.reserve(recruitList.size());
    for (const auto& candidate : recruitList) {
        // recruit_settle::isSamePerson = Kotlin RecruitIntegrity.isSamePerson
        if (!gamecore::system::recruit_settle::isSamePerson(candidate, before)) {
            kept.push_back(candidate);
        }
    }
    if (kept.size() != recruitList.size()) recruitList = std::move(kept);
    out.ok = true;
    return out;
}

// ── 事务 8：类型直改（changeDiscipleTypeAtomic 数据段，1741）──────────────
//
// 写段：discipleTypes 行写。状态推导（syncSingleDiscipleStatus）由 Kotlin
// 调用方照原序执行（与 Kotlin 事务序一致——该调用在事务外）。
inline DiscipleTxResult changeDiscipleTypeTx(GameState& state,
                                             const std::string& discipleId,
                                             const std::string& newType) {
    DiscipleTxResult out;
    DiscipleStore& ds = state.disciples;
    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value() || !ds.contains(discipleId)) {
        out.errorType = "NotFound";
        out.message = "弟子不存在 " + discipleId;
        return out;
    }
    ds.discipleTypes[*ds.rowOf(discipleId)] = newType;
    out.ok = true;
    return out;
}

// ── 事务 9：关注切换（DiscipleDelegate.toggleFollowDisciple 数据段，1742）──
//
// 写段：statusData["followed"] 翻转（"true" ⇄ 移除）。
struct ToggleFollowResult {
    DiscipleTxResult base;
    bool followedAfter = false;
};
inline ToggleFollowResult toggleFollowTx(GameState& state,
                                         const std::string& discipleId) {
    ToggleFollowResult out;
    DiscipleStore& ds = state.disciples;
    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value() || !ds.contains(discipleId)) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子不存在 " + discipleId;
        return out;
    }
    auto& sd = ds.statusData[*ds.rowOf(discipleId)];
    if (sd["followed"] == "true") {
        sd.erase("followed");
        out.followedAfter = false;
    } else {
        sd["followed"] = "true";
        out.followedAfter = true;
    }
    out.base.ok = true;
    return out;
}

// ── 事务 10：赏赐物品（rewardPill/rewardMaterial/rewardHerb/rewardSeed
//    四路合一，1743）────────────────────────────────────────────────────
//
// 静默守卫（Kotlin silent return）→ 失败信封（Kotlin 回退臂重执行同义静默）：
//  - pill：丹药不存在 / 数量不足 / 弟子非法 → 静默；可服用 ⇒ 扣仓库 + 生效，
//    不可服用 ⇒ 扣仓库 + 入袋（facade 丹药链语义权威见节首）。
//  - material/herb/seed：先校验弟子存在（无效 id 时仓库不被扣减——物品不消失）
//    → 物品存在/未锁定/数量合法 → 扣仓库 + 入袋。
// @param itemType "pill"|"material"|"herb"|"seed"
// @param displayName/displayRarity 赏赐条目名/稀有度（material/herb/seed 袋
//        条目取自 RewardSelectedItem——Kotlin item.name/item.rarity；pill 袋
//        条目取自丹药实体本身，此参忽略）
inline DiscipleTxResult rewardItemTx(GameState& state, const std::string& discipleId,
                                     const std::string& itemType,
                                     const std::string& itemId, int32_t quantity,
                                     const std::string& displayName,
                                     int32_t displayRarity) {
    DiscipleTxResult out;
    DiscipleStore& ds = state.disciples;
    const auto intId = settle_util::toIntOrNull(discipleId);
    const bool validDisciple = intId.has_value() && ds.contains(discipleId);

    if (itemType == "pill") {
        Pill* pill = detail::findStackById(state.pills, itemId);
        if (pill == nullptr || pill->quantity < quantity) {
            out.errorType = "NotFound";
            out.message = "丹药不存在或数量不足 " + itemId;
            return out;
        }
        if (!validDisciple) {
            out.errorType = "NotFound";
            out.message = "弟子不存在 " + discipleId;
            return out;
        }
        const std::size_t row = *ds.rowOf(discipleId);
        const auto ie = detail::facadeItemEffect(*pill);
        const bool canUse =
            gamecore::pill::canUsePill(ds.materialize(row), ie);
        // 先扣仓库（Kotlin 同序——canUse 判定不回滚扣减）
        detail::deductStackById(state.pills, itemId, quantity);
        if (canUse) {
            const auto outcome = detail::applyFacadePillEffects(state, row, *pill);
            out.moralityAfter = outcome.moralityAfter;
            out.theftCandidate = outcome.baseAttrApplied;
        } else {
            StorageBagItem entry = detail::pillBagItem(*pill, quantity);
            entry.obtainedYear = state.gameData.gameYear;
            entry.obtainedMonth = state.gameData.gameMonth;
            detail::bagIncreaseItemQuantity(ds.storageBagItems[row], std::move(entry));
        }
    } else if (itemType == "material" || itemType == "herb" || itemType == "seed") {
        if (!validDisciple) {
            out.errorType = "NotFound";
            out.message = "弟子不存在 " + discipleId;
            return out;
        }
        const std::size_t row = *ds.rowOf(discipleId);
        auto deductAndBag = [&](auto& store, const char* bagItemType) -> bool {
            auto* stack = detail::findStackById(store, itemId);
            if (stack == nullptr || stack->isLocked || quantity < 1 ||
                quantity > stack->quantity) {
                return false;
            }
            detail::deductStackById(store, itemId, quantity);
            StorageBagItem entry;
            entry.itemId = itemId;
            entry.itemType = bagItemType;
            entry.name = displayName;
            entry.rarity = displayRarity;
            entry.quantity = quantity;
            entry.obtainedYear = state.gameData.gameYear;
            entry.obtainedMonth = state.gameData.gameMonth;
            entry.stackedData = gamecore::state::BagStackedData();
            detail::bagIncreaseItemQuantity(ds.storageBagItems[row], std::move(entry));
            return true;
        };
        bool done = false;
        if (itemType == "material") done = deductAndBag(state.materials, "material");
        else if (itemType == "herb") done = deductAndBag(state.herbs, "herb");
        else done = deductAndBag(state.seeds, "seed");
        if (!done) {
            out.errorType = "NotFound";
            out.message = "物品不存在/已锁定/数量不足 " + itemId;
            return out;
        }
    } else {
        out.errorType = "ItemTypeInvalid";
        out.message = "未知赏赐类型 " + itemType;
        return out;
    }
    out.ok = true;
    return out;
}

// ── 事务 11：服药（DiscipleFacadeImpl.usePill 等价，1744）────────────────
//
// 静默守卫：丹药不存在/数量≤0/弟子非法/资格不符（canUsePill）→ 全部静默
// 不写（Kotlin silent return）。写段：扣仓库 + facade 丹药链 + 服药日志草稿。
struct UsePillResult {
    DiscipleTxResult base;
    std::string logLine;     // "X岁：服用了Y"（Kotlin lifeEvents 瞬态列回写）
    int32_t moralityAfter = 0;  // 偷盗判定钩子判定输入（Kotlin 按阈值原序判定）
};
inline UsePillResult usePillTx(GameState& state, const std::string& discipleId,
                               const std::string& pillId) {
    UsePillResult out;
    DiscipleStore& ds = state.disciples;
    Pill* pill = detail::findStackById(state.pills, pillId);
    if (pill == nullptr || pill->quantity <= 0) {
        out.base.errorType = "NotFound";
        out.base.message = "丹药不存在 " + pillId;
        return out;
    }
    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value() || !ds.contains(discipleId)) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子不存在 " + discipleId;
        return out;
    }
    const std::size_t row = *ds.rowOf(discipleId);
    const auto ie = detail::facadeItemEffect(*pill);
    if (!gamecore::pill::canUsePill(ds.materialize(row), ie)) {
        out.base.errorType = "CannotUse";
        out.base.message = "当前不可服用 " + pill->name;
        return out;
    }
    detail::deductStackById(state.pills, pillId, 1);
    const auto outcome = detail::applyFacadePillEffects(state, row, *pill);
    out.moralityAfter = outcome.moralityAfter;
    out.base.theftCandidate = outcome.baseAttrApplied;
    out.logLine = std::to_string(ds.ages[row]) + "岁：服用了" + pill->name;
    out.base.ok = true;
    return out;
}

// ── 事务 12：功法替换（GameEngineManualOps.replaceManual 等价，1745）──────
//
// 校验链（Kotlin 判定序）：旧实例存在 → 新堆叠存在 → 弟子存在 → 数量≥1 →
// 境界 → 心法互斥（新为心法且旧非心法时其余功法不得有心法）→ 同名唯一。
// 写段：新堆叠 -1/移除 + 实例铸造（确定性占位 id）+ 熟练度清理 + manualIds
// 换血 + 旧实例入袋（D-03）+ 旧实例表移除 + 替换日志草稿。
struct ReplaceManualResult {
    DiscipleTxResult base;
    std::string logLine;  // "X岁：将功法A替换为B"（lifeEvents 瞬态列回写）
};
inline ReplaceManualResult replaceManualTx(GameState& state,
                                           const std::string& discipleId,
                                           const std::string& oldInstanceId,
                                           const std::string& newStackId) {
    ReplaceManualResult out;
    DiscipleStore& ds = state.disciples;
    ManualInstance* oldInstance = detail::findManualInstance(state, oldInstanceId);
    if (oldInstance == nullptr) {
        out.base.errorType = "NotFound";
        out.base.message = "旧功法实例不存在 " + oldInstanceId;
        return out;
    }
    ManualStack* newStack = detail::findManualStack(state, newStackId);
    if (newStack == nullptr) {
        out.base.errorType = "NotFound";
        out.base.message = "功法堆叠不存在 " + newStackId;
        return out;
    }
    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value() || !ds.contains(discipleId)) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子不存在 " + discipleId;
        return out;
    }
    const std::size_t row = *ds.rowOf(discipleId);
    if (newStack->quantity < 1) {
        out.base.errorType = "NotFound";
        out.base.message = "功法堆叠数量不足";
        return out;
    }
    if (ds.realms[row] > newStack->minRealm) {
        out.base.errorType = "RealmTooLow";
        out.base.message = "境界不足";
        return out;
    }
    // 心法互斥：新为心法 && 旧非心法 ⇒ 其余已学不得有心法
    //（同族守卫——旧实例本身排除在判定外，Kotlin filter 同序）
    if (newStack->type == "MIND" && oldInstance->type != "MIND") {
        for (const std::string& mid : ds.manualIds[row]) {
            if (mid == oldInstanceId) continue;
            const ManualInstance* mn = detail::findManualInstance(state, mid);
            if (mn != nullptr && mn->type == "MIND") {
                out.base.errorType = "MindDuplicate";
                out.base.message = "已修习心法";
                return out;
            }
        }
    }
    // 同名唯一（旧实例排除）
    for (const std::string& mid : ds.manualIds[row]) {
        if (mid == oldInstanceId) continue;
        const ManualInstance* mn = detail::findManualInstance(state, mid);
        if (mn != nullptr && mn->name == newStack->name) {
            out.base.errorType = "NameDuplicate";
            out.base.message = "已修习同名功法";
            return out;
        }
    }

    // 写段（悬垂纪律：先拷贝后 erase——整摞消耗/实例表移除会使指针悬垂）
    const ManualStack stackCopy = *newStack;
    const ManualInstance oldCopy = *oldInstance;
    const std::string oldIdCopy = oldInstanceId;
    // 新堆叠扣减（quantity==1 → 整摞移除，否则 -1；deductManualStack 语义）
    detail::deductManualStack(state.manualStacks, stackCopy.id);
    // 实例铸造（确定性占位 id——batch-08 契约，对拍面忽略新增条目 id）
    ManualInstance newInstance =
        detail::manualInstanceFromStack(stackCopy, discipleId);
    newInstance.isLearned = true;
    const std::string newInstanceId = newInstance.id;
    state.manualInstances.push_back(std::move(newInstance));
    // 熟练度清理（旧实例 key 过滤，空则删键）
    auto& profMap = state.gameData.manualProficiencies;
    const auto pit = profMap.find(discipleId);
    if (pit != profMap.end()) {
        std::vector<gamecore::state::ManualProficiencyData> kept;
        for (const auto& p : pit->second) {
            if (p.manualId != oldIdCopy) kept.push_back(p);
        }
        if (kept.empty()) profMap.erase(pit);
        else pit->second = std::move(kept);
    }
    // manualIds 换血 + 旧实例入袋（D-03）
    auto& mids = ds.manualIds[row];
    mids.erase(std::remove(mids.begin(), mids.end(), oldIdCopy), mids.end());
    mids.push_back(newInstanceId);
    StorageBagItem entry;
    entry.itemId = oldIdCopy;
    entry.itemType = "manual_instance";
    entry.name = oldCopy.name;
    entry.rarity = oldCopy.rarity;
    entry.quantity = 1;
    entry.obtainedYear = state.gameData.gameYear;
    entry.obtainedMonth = state.gameData.gameMonth;
    entry.manualInstance = oldCopy;
    detail::bagIncreaseItemQuantity(ds.storageBagItems[row], std::move(entry));
    // 旧实例表移除（防双持有）
    auto& minst = state.manualInstances;
    minst.erase(std::remove_if(minst.begin(), minst.end(),
                               [&](const ManualInstance& x) {
                                   return x.id == oldIdCopy;
                               }),
                minst.end());
    out.logLine = std::to_string(ds.ages[row]) + "岁：将功法" + oldCopy.name +
                  "替换为" + stackCopy.name;
    out.base.ok = true;
    return out;
}

// ── 事务 13：血炼启动（GameEngineBloodRefinementOps.startBloodRefinementAtomic
//    等价，1746）────────────────────────────────────────────────────────
//
// 校验链（Kotlin 同序；事务内 throw ⇒ C++ 校验先行，失败零写入等价）：
// 灵石>0 → 材料>0 → 配置>0 → 弟子 id 合法 → 灵石足 → 材料足（跨堆叠、未锁定）
// → 血炼池排他 → 弟子排他。写段：材料消耗 + 11 类槽位清理（includeResidence=
// false 默认参）+ 灵石扣除 + 进度写入 + REFINING 状态 + statusData 覆写。
// Gate 释放 + Room 生产槽清理为 Kotlin 运行态残差（native 成功后原序）。
struct BloodRefinementStartParams {
    std::string materialName;
    int32_t materialRarity = 0;
    int32_t materialCount = 0;
    std::string buildingInstanceId;
    int64_t requiredSpiritStones = 0;
    std::string discipleId;
    std::string discipleName;
    std::string materialId;
    std::string selectedStat;
    double bonusPercent = 0.0;
    int32_t durationMonths = 0;
};
inline DiscipleTxResult startBloodRefinementTx(GameState& state,
                                               const BloodRefinementStartParams& in) {
    DiscipleTxResult out;
    // 1. 参数域校验（Kotlin withEngineContext 前置四连）
    if (in.requiredSpiritStones <= 0) {
        out.errorType = "InvalidStones";
        out.message = "灵石消耗必须为正数";
        return out;
    }
    if (in.materialCount <= 0) {
        out.errorType = "InvalidMaterial";
        out.message = "材料消耗必须为正数";
        return out;
    }
    if (in.durationMonths <= 0 || in.bonusPercent <= 0.0) {
        out.errorType = "InvalidConfig";
        out.message = "血炼配置异常（duration/bonus）";
        return out;
    }
    if (!settle_util::toIntOrNull(in.discipleId).has_value()) {
        out.errorType = "InvalidDiscipleId";
        out.message = "非法弟子ID";
        return out;
    }
    DiscipleStore& ds = state.disciples;
    // 2. 灵石足额
    if (state.gameData.spiritStones < in.requiredSpiritStones) {
        out.errorType = "StonesInsufficient";
        out.message = "灵石不足: 需要 " + std::to_string(in.requiredSpiritStones) +
                      ", 当前 " + std::to_string(state.gameData.spiritStones);
        return out;
    }
    // 3. 材料足额（name+rarity 匹配、未锁定、跨堆叠；不足 → 失败）
    {
        int32_t remaining = in.materialCount;
        for (const auto& mat : state.materials) {
            if (remaining <= 0) break;
            if (mat.name != in.materialName || mat.rarity != in.materialRarity ||
                mat.isLocked) {
                continue;
            }
            remaining -= std::min(remaining, mat.quantity);
        }
        if (remaining > 0) {
            out.errorType = "MaterialInsufficient";
            out.message = "兽血材料不足: 缺少 " + std::to_string(remaining) + " 份 " +
                          in.materialName;
            return out;
        }
    }
    // 4. 排他性（血炼池内 + 弟子在其它池）
    if (state.gameData.activeBloodRefinements.count(in.buildingInstanceId) != 0) {
        out.errorType = "BuildingOccupied";
        out.message = "该血炼池已有进行中的血炼";
        return out;
    }
    for (const auto& kv : state.gameData.activeBloodRefinements) {
        if (kv.second.discipleId == in.discipleId) {
            out.errorType = "DiscipleOccupied";
            out.message = "该弟子已在其他血炼池中";
            return out;
        }
    }
    // ── 写段（校验链全部通过后才落写）──
    // 材料消耗（跨堆叠）
    {
        int32_t remaining = in.materialCount;
        for (auto& mat : state.materials) {
            if (remaining <= 0) break;
            if (mat.name != in.materialName || mat.rarity != in.materialRarity ||
                mat.isLocked) {
                continue;
            }
            const int32_t take = std::min(remaining, mat.quantity);
            mat.quantity -= take;
            remaining -= take;
        }
        state.materials.erase(
            std::remove_if(state.materials.begin(), state.materials.end(),
                           [](const Material& m) { return m.quantity <= 0; }),
            state.materials.end());
    }
    // 槽位清理（clearAllSlotsDataOnly 默认参 includeResidence=false）
    detail::clearAllDiscipleSlots(state, in.discipleId);
    // 灵石扣除 + 进度写入
    gamecore::state::BloodRefinementProgress progress;
    progress.discipleId = in.discipleId;
    progress.discipleName = in.discipleName;
    progress.materialId = in.materialId;
    progress.selectedStat = in.selectedStat;
    progress.bonusPercent = in.bonusPercent;
    progress.durationMonths = in.durationMonths;
    progress.startYear = state.gameData.gameYear;
    progress.startMonth = state.gameData.gameMonth;
    state.gameData.spiritStones -= in.requiredSpiritStones;
    state.gameData.activeBloodRefinements[in.buildingInstanceId] = progress;
    // REFINING 状态 + statusData 整体覆写（Kotlin 同款：mapOf("buildingId" to …)）
    const auto intId = settle_util::toIntOrNull(in.discipleId);
    if (intId.has_value() && ds.contains(in.discipleId)) {
        const std::size_t row = *ds.rowOf(in.discipleId);
        ds.statuses[row] = detail::kStatusRefining;
        ds.statusData[row] = std::map<std::string, std::string>{
            {"buildingId", in.buildingInstanceId}};
    }
    out.ok = true;
    return out;
}

// ── 事务 14：弟子状态派生同步（DiscipleStatusService，1747/1748）──────────
//
// 1747 单弟子 / 1748 全量（含 fixInvalidMiningSlots 前置自愈）。派生列唯一
// 计算方 = C++（ADR 盲区 2：槽位/派生列双算会漂移）；Kotlin 侧降级回退臂
// 保留同语义（flag OFF / native 不可用时）。
struct SyncStatusResult {
    DiscipleTxResult base;
    std::string statusAfter;  // 单弟子：派生后状态名
    int32_t syncedCount = 0;  // 全量：同步弟子数
};
inline SyncStatusResult syncDiscipleStatusTx(GameState& state,
                                             const std::string& discipleId) {
    SyncStatusResult out;
    DiscipleStore& ds = state.disciples;
    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value() || !ds.contains(discipleId)) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子不存在 " + discipleId;
        return out;
    }
    const std::size_t row = *ds.rowOf(discipleId);
    detail::deriveAndWriteDiscipleStatus(state, row);
    out.statusAfter = ds.statuses[row];
    out.syncedCount = 1;
    out.base.ok = true;
    return out;
}
inline SyncStatusResult syncAllDiscipleStatusesTx(GameState& state) {
    SyncStatusResult out;
    DiscipleStore& ds = state.disciples;
    detail::fixInvalidMiningSlots(state);
    for (std::size_t row = 0; row < ds.ids.size(); ++row) {
        detail::deriveAndWriteDiscipleStatus(state, row);
        ++out.syncedCount;
    }
    out.base.ok = true;
    return out;
}

}  // namespace gamecore::system::disciple_tx
