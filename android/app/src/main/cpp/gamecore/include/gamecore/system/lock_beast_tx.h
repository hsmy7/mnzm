// ============================================================
// lock_beast_tx.h — 妖兽视图锁定 + 设置项字段补丁 UI 操作面事务（batch-23）
//
// 来源：ui-read-surface §4.1 残余域写者审计（实测结论，见 handover §2.55）。
// 本头承两处 **AUTHORITATIVE 稳态 Kotlin 直改写者**（非回退臂）：
//
// 一、妖兽视图锁定（GameEngine.lockBeastView / unlockBeastView，:319/:324）
//     调用面 = BeastAttackDelegate（妖兽详情弹窗开/关事件）→
//     `lockedBeastIds`（NativeGameState **顶层段**，Kotlin @Transient——
//     S-15 独立全量段，§2.21 补齐增量通道）。该字段是 AUTHORITATIVE 月结
//     「锁定妖兽不被 AI 攻击」判据（month_settlement.h）的输入，UI 直改
//     期间只能靠反向通道回导才生效（增量窗口内失效——§2.21.2 曾因此补段）。
//
// 二、设置项字段补丁（**设置项域稳态写者穷尽审计结果**）：
//     · SettingsDelegate :21/:25/:29/:33/:73/:83 —— patrolBattleResultPopup /
//       autoSellMidGradeForPurchase / autoSellHighGradeForPurchase /
//       showAllAvailableDisciples / soundEnabled / musicEnabled
//     · AutoAssignDelegate :18/:25/:83/:92/:101/:109/:118 ——
//       daoCompanionBannedRootCounts / daoCompanionConsentRequired /
//       breakthroughAutoPill{Focused,RootCounts} /
//       autoEquipFromWarehouse{Focused,RootCounts} /
//       autoLearnFromWarehouse{Focused,RootCounts} / prisonerSpiritRootFilter
//     · InventoryDelegate :157/:177 —— autoRecruitSpiritRootFilter /
//       autoRejectSpiritRootFilter
//     全部 17 字段均为 gameData 序列化面内标量/Int 集（已核对 models.h +
//     json_codec.cpp 双侧在位），**无 RNG、无 checkpoint 副效应**——
//     证据：AutoAssignDelegate 的自动分配策略族（sectPolicies）走独立入口
//     `batchUpdateAutoAssignAndGuide`（batch-18 已下沉 boundary_tx.h），
//     属 policy 域而非 settings 域，本头不重复承接。
//
// **设置项域采"字段名 → 值"通用补丁**（单 ActionId 覆盖全 17 字段）：
// 逐字段扩 ActionId 会为单次赋值耗掉一个操作码并制造 17 个近似动作；
// 通用补丁以**未知字段名 → UnknownSettingField 失败信封**守住边界
// （失败零写入 → Kotlin 回退臂重执行原路径，双实现并行契约）。
//
// RNG 契约（对拍命门）：**全链零抽取**——签名级不接受 rng::RngManager&，
// 无 nextInt/nextDouble 调用面。由 GTest「全分区 rngStates 快照差分」+
// 「双运行全状态 JSON 逐位一致」双重证明。
//
// 失败零写入：判定链（未知字段/类型不符）先于全部写段——先全量解析校验，
// 全部通过后才进入写段；任一失败 → ok=false + errorType + 零状态变更。
// ============================================================
#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <string>
#include <variant>
#include <vector>

#include "gamecore/state/models.h"

namespace gamecore::system::lock_beast_tx {

using gamecore::state::GameData;
using gamecore::state::GameState;

// ── 结果信封（失败零写入；failure → Kotlin 回退原路径重执行校验链）──────

struct TxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

/// 妖兽锁定结果：changed = 集合是否发生变化（幂等调用返回 changed=false）
struct BeastLockOutcome {
    TxResult base;
    bool changed = false;
    int32_t lockedCount = 0;
};

/// 设置补丁结果：changed = 任一字段是否实际发生变化（同值写入幂等）
struct SettingsOutcome {
    TxResult base;
    bool changed = false;
    int32_t appliedFields = 0;
};

/// 设置字段值载体：bool（开关）/ Int 集（灵根数白名单类字段）
using SettingValue = std::variant<bool, std::vector<int32_t>>;

/// 字段补丁输入：字段名 → 值（Kotlin 侧按需只放本次实际改动的字段）
using SettingPatch = std::map<std::string, SettingValue>;

namespace detail {

/// 妖兽 id 是否已在锁定集中（std::find——集合规模为弹窗数量级，
/// 且 lockedBeastIds 为保序 vector，与 Kotlin `Set<String>` 的成员判定语义等价）
inline bool containsBeast(const std::vector<std::string>& locked,
                          const std::string& beastId) {
    return std::find(locked.begin(), locked.end(), beastId) != locked.end();
}

/// Int 集语义比较（Kotlin `Set<Int>` ==：与元素序无关）——先排序副本再比较。
/// 入参须已去重（调用方在解析阶段归一化，见 normalizePatch）。
inline bool sameIntSet(const std::vector<int32_t>& lhs,
                       const std::vector<int32_t>& rhs) {
    if (lhs.size() != rhs.size()) return false;
    std::vector<int32_t> a = lhs;
    std::vector<int32_t> b = rhs;
    std::sort(a.begin(), a.end());
    std::sort(b.begin(), b.end());
    return a == b;
}

/// 去重 + 保序（Kotlin `Set` 构造语义：C++ 侧用保序 vector 复刻，
/// 与既有 lockedBeastIds 同口径——**不引入 unordered_set**，保证导出逐位稳定）
inline std::vector<int32_t> dedupPreserveOrder(const std::vector<int32_t>& values) {
    std::vector<int32_t> out;
    out.reserve(values.size());
    for (const int32_t v : values) {
        if (std::find(out.begin(), out.end(), v) == out.end()) out.push_back(v);
    }
    return out;
}

/// 补丁归一化（**唯一归一化点**）：Int 集字段去重——Kotlin 侧值为 `Set<Int>`
/// 天然无重复，C++ 收线后与 Kotlin 语义一致（"比较"与"写入"必须用同一份
/// 归一化值；若只在此处不动而对原始输入比较，会出现 valueOnlyDiffers
/// 判定与写入结果分叉——重复元素场景下 changed=false 却不写，测试实测暴露）。
inline SettingPatch normalizePatch(const SettingPatch& patch) {
    SettingPatch out;
    for (const auto& [field, value] : patch) {
        if (const auto* asInts = std::get_if<std::vector<int32_t>>(&value)) {
            out[field] = dedupPreserveOrder(*asInts);
        } else {
            out[field] = value;
        }
    }
    return out;
}

/// 设置字段应用器：返回 true = 字段名已知（含"值未变无需写入"）；
/// false = 未知字段（调用方转 UnknownSettingField 失败信封，零写入）。
///
/// 字段清单 = 设置项域稳态写者穷尽审计结果（见文件头）；新增 UI 设置入口时
/// 必须同步扩本表，否则该入口 native 臂收到失败信封并回退 Kotlin 原路径
/// （行为正确但失去下沉收益——守卫测试 `SettingsPatchFieldCoverageTest`
/// 以 Kotlin 侧字段清单为锚点断言两表一致）。
inline bool applySetting(GameData& data, const std::string& field,
                         const SettingValue& value) {
    const bool* asBool = std::get_if<bool>(&value);
    const std::vector<int32_t>* asInts = std::get_if<std::vector<int32_t>>(&value);

    // ── 布尔开关字段（11）────────────────────────────────────────────
    if (asBool != nullptr) {
        const bool v = *asBool;
        if (field == "soundEnabled") { data.soundEnabled = v; return true; }
        if (field == "musicEnabled") { data.musicEnabled = v; return true; }
        if (field == "patrolBattleResultPopup") {
            data.patrolBattleResultPopup = v; return true;
        }
        if (field == "autoSellMidGradeForPurchase") {
            data.autoSellMidGradeForPurchase = v; return true;
        }
        if (field == "autoSellHighGradeForPurchase") {
            data.autoSellHighGradeForPurchase = v; return true;
        }
        if (field == "showAllAvailableDisciples") {
            data.showAllAvailableDisciples = v; return true;
        }
        if (field == "daoCompanionConsentRequired") {
            data.daoCompanionConsentRequired = v; return true;
        }
        if (field == "breakthroughAutoPillFocused") {
            data.breakthroughAutoPillFocused = v; return true;
        }
        if (field == "autoEquipFromWarehouseFocused") {
            data.autoEquipFromWarehouseFocused = v; return true;
        }
        if (field == "autoLearnFromWarehouseFocused") {
            data.autoLearnFromWarehouseFocused = v; return true;
        }
        return false;
    }

    // ── Int 集字段（7：灵根数白名单/过滤器）──────────────────────────
    if (asInts != nullptr) {
        const std::vector<int32_t>& v = *asInts;
        if (field == "autoRecruitSpiritRootFilter") {
            data.autoRecruitSpiritRootFilter = v; return true;
        }
        if (field == "autoRejectSpiritRootFilter") {
            data.autoRejectSpiritRootFilter = v; return true;
        }
        if (field == "prisonerSpiritRootFilter") {
            data.prisonerSpiritRootFilter = v; return true;
        }
        if (field == "breakthroughAutoPillRootCounts") {
            data.breakthroughAutoPillRootCounts = v; return true;
        }
        if (field == "autoEquipFromWarehouseRootCounts") {
            data.autoEquipFromWarehouseRootCounts = v; return true;
        }
        if (field == "autoLearnFromWarehouseRootCounts") {
            data.autoLearnFromWarehouseRootCounts = v; return true;
        }
        if (field == "daoCompanionBannedRootCounts") {
            data.daoCompanionBannedRootCounts = v; return true;
        }
        return false;
    }
    return false;
}

/// 字段名 + 值类型是否被本事务支持（**纯探测，零写入**）。
///
/// 与 [applySetting] / [isSettingUnchanged] 共用同一字段清单——三处必须同步
/// （守卫测试 `SettingsPatchFieldCoverageTest` 以 Kotlin 侧清单为锚点断言）。
inline bool isKnownSettingField(const std::string& field,
                                const SettingValue& value) {
    if (std::holds_alternative<bool>(value)) {
        return field == "soundEnabled" || field == "musicEnabled" ||
               field == "patrolBattleResultPopup" ||
               field == "autoSellMidGradeForPurchase" ||
               field == "autoSellHighGradeForPurchase" ||
               field == "showAllAvailableDisciples" ||
               field == "daoCompanionConsentRequired" ||
               field == "breakthroughAutoPillFocused" ||
               field == "autoEquipFromWarehouseFocused" ||
               field == "autoLearnFromWarehouseFocused";
    }
    if (std::holds_alternative<std::vector<int32_t>>(value)) {
        return field == "autoRecruitSpiritRootFilter" ||
               field == "autoRejectSpiritRootFilter" ||
               field == "prisonerSpiritRootFilter" ||
               field == "breakthroughAutoPillRootCounts" ||
               field == "autoEquipFromWarehouseRootCounts" ||
               field == "autoLearnFromWarehouseRootCounts" ||
               field == "daoCompanionBannedRootCounts";
    }
    return false;
}

/// 字段当前值是否与目标值一致（同值 → 不写入；集合字段按集合语义比较）
inline bool isSettingUnchanged(const GameData& data, const std::string& field,
                               const SettingValue& value) {    const bool* asBool = std::get_if<bool>(&value);
    if (asBool != nullptr) {
        const bool v = *asBool;
        if (field == "soundEnabled") return data.soundEnabled == v;
        if (field == "musicEnabled") return data.musicEnabled == v;
        if (field == "patrolBattleResultPopup") return data.patrolBattleResultPopup == v;
        if (field == "autoSellMidGradeForPurchase") {
            return data.autoSellMidGradeForPurchase == v;
        }
        if (field == "autoSellHighGradeForPurchase") {
            return data.autoSellHighGradeForPurchase == v;
        }
        if (field == "showAllAvailableDisciples") {
            return data.showAllAvailableDisciples == v;
        }
        if (field == "daoCompanionConsentRequired") {
            return data.daoCompanionConsentRequired == v;
        }
        if (field == "breakthroughAutoPillFocused") {
            return data.breakthroughAutoPillFocused == v;
        }
        if (field == "autoEquipFromWarehouseFocused") {
            return data.autoEquipFromWarehouseFocused == v;
        }
        if (field == "autoLearnFromWarehouseFocused") {
            return data.autoLearnFromWarehouseFocused == v;
        }
        return false;
    }
    const std::vector<int32_t>* asInts = std::get_if<std::vector<int32_t>>(&value);
    if (asInts != nullptr) {
        if (field == "autoRecruitSpiritRootFilter") {
            return sameIntSet(data.autoRecruitSpiritRootFilter, *asInts);
        }
        if (field == "autoRejectSpiritRootFilter") {
            return sameIntSet(data.autoRejectSpiritRootFilter, *asInts);
        }
        if (field == "prisonerSpiritRootFilter") {
            return sameIntSet(data.prisonerSpiritRootFilter, *asInts);
        }
        if (field == "breakthroughAutoPillRootCounts") {
            return sameIntSet(data.breakthroughAutoPillRootCounts, *asInts);
        }
        if (field == "autoEquipFromWarehouseRootCounts") {
            return sameIntSet(data.autoEquipFromWarehouseRootCounts, *asInts);
        }
        if (field == "autoLearnFromWarehouseRootCounts") {
            return sameIntSet(data.autoLearnFromWarehouseRootCounts, *asInts);
        }
        if (field == "daoCompanionBannedRootCounts") {
            return sameIntSet(data.daoCompanionBannedRootCounts, *asInts);
        }
        return false;
    }
    return false;
}

}  // namespace detail

// ── 事务 1：妖兽视图锁定 / 解锁（GameEngine.lockBeastView / unlockBeastView）──
//
// Kotlin 源语义逐字对齐：
//   lockBeastView(beastId)   → lockedBeastIds = lockedBeastIds + beastId
//   unlockBeastView(beastId) → if (beastId.isEmpty()) return
//                              lockedBeastIds = lockedBeastIds - beastId
//
// **空 id 语义逐字保留**：解锁分支的空 id 早退归 Kotlin 调用侧
// （`unlockBeast` 在委托前已 `if (beastId.isEmpty()) return`）；C++ 侧对空 id
// 一律视为幂等无操作（added/removed 均不命中）——两臂结果一致。
//
// 集合语义：Kotlin `Set` 的 + / - ——重复锁定幂等、解锁不存在的 id 幂等。
// C++ 侧用保序 vector + std::find 精确复刻（协议序列化面为数组，保序保证
// exportState 逐位稳定）。
//
// **落点**：`lockedBeastIds` 是 NativeGameState **顶层**字段（与 Kotlin
// `GameData.lockedBeastIds` 的 @Transient 语义一一对应），故取
// `state.lockedBeastIds` 而非 `state.gameData.lockedBeastIds`
// （models.h:1463 同 aiSectDisciples 族）。
inline BeastLockOutcome lockBeastViewTx(GameState& state,
                                        const std::string& beastId,
                                        bool locked) {
    BeastLockOutcome out;
    out.base.ok = true;
    auto& lockedIds = state.lockedBeastIds;
    out.lockedCount = static_cast<int32_t>(lockedIds.size());

    if (beastId.empty()) return out;  // 幂等无操作（调用侧已拦截，防御兜底）

    const bool present = detail::containsBeast(lockedIds, beastId);
    if (locked) {
        if (present) return out;  // 重复锁定幂等（Set 语义）
        lockedIds.push_back(beastId);
    } else {
        if (!present) return out;  // 解锁不存在的 id 幂等
        lockedIds.erase(
            std::remove(lockedIds.begin(), lockedIds.end(), beastId),
            lockedIds.end());
    }
    out.changed = true;
    out.lockedCount = static_cast<int32_t>(lockedIds.size());
    return out;
}

// ── 事务 2：设置项字段补丁（SettingsDelegate + AutoAssignDelegate + InventoryDelegate）──
//
// Kotlin 源语义（逐入口）：`updateGameData { it.copy(field = value) }`——
// 即**字段级覆盖写**，各字段相互独立。本事务逐字段复刻，并保留
// "值未变则不写"的等价语义（changed 判定按字段当前值比较；集合字段按
// `Set<Int>` 集合语义比较，与 Kotlin data class 的 equals 同源）。
//
// 判定链先行（失败零写入）：① 空补丁 → 幂等成功（appliedFields=0）；
// ② 全量解析阶段逐字段判"已知？类型匹配？"——任一未知/类型不符即
//    UnknownSettingField/TypeMismatch 失败且**不触碰任何字段**；
// ③ 全部通过后进入写段。
//
// 注：`daoCompanionConsentRequired=false` 在 Kotlin 侧另触发
// `clearPendingMarriageProposals()`（pendingMarriageProposals 为 Kotlin
// 运行态，不入 C++ 状态）——该段由 Kotlin 调用方在 native 成功后照原序执行
// （事务外残差，与巡逻/住所族的 gate 残差同口径）。
inline SettingsOutcome updateSettingsTx(GameState& state,
                                        const SettingPatch& patch) {
    SettingsOutcome out;
    out.base.ok = true;
    if (patch.empty()) return out;

    auto& data = state.gameData;

    // ── ② 解析校验阶段（零写入）────────────────────────────────────
    for (const auto& [field, value] : patch) {
        // 未知字段 / 值类型不符 → 失败零写入（Kotlin 回退臂重执行原路径）
        if (!detail::isKnownSettingField(field, value)) {
            out.base.ok = false;
            out.base.errorType = "UnknownSettingField";
            out.base.message = "未知设置项字段 " + field;
            return out;
        }
    }

    // ── ②' 归一化（唯一归一化点：Int 集去重，比较与写入共用同一份值）──
    const SettingPatch normalized = detail::normalizePatch(patch);

    // ── ③ 写段（校验已全通过）──────────────────────────────────────
    for (const auto& [field, value] : normalized) {
        if (detail::isSettingUnchanged(data, field, value)) {
            ++out.appliedFields;  // 同值：幂等无变化（Kotlin copy 不产生差异）
            continue;
        }
        detail::applySetting(data, field, value);
        out.changed = true;
        ++out.appliedFields;
    }
    return out;
}

}  // namespace gamecore::system::lock_beast_tx
