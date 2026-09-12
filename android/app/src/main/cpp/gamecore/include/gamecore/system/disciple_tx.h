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
#include "gamecore/system/disciple.h"            // kBaseManualSlots
#include "gamecore/system/disciple_stats.h"      // talent/affix effects（名额公式）
#include "gamecore/system/inventory.h"           // 库存原语同源（bag 语义参照）
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

// ── 结果信封（Kotlin DomainResult.Failure 分型 + 运行态草稿）────────────

/// 事务结果基型：errorType 与 Kotlin AppError.Domain.Disciple 分型同名
///（NotFound/AlreadyEquipped/RealmTooLow/SlotInvalid），失败信封经
/// execute_dispatch fail(code) 回传 → Kotlin 回退原路径重执行校验链
///（双实现并行契约，production.h 同模式）
struct DiscipleTxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
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

}  // namespace gamecore::system::disciple_tx
