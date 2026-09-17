#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/ecs/disciple_component.h"  // syncDiscipleEntities（E1 保序桥接）
#include "gamecore/ecs/job_system.h"
#include "gamecore/ecs/system.h"
#include "gamecore/system/auto_gear.h"
#include "gamecore/system/breakthrough.h"
#include "gamecore/system/cultivation.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/disciple_stats.h"
#include "gamecore/system/month_settlement.h"  // judgeSingleTheftCandidate（S2 偷盗钩子复用；无回环依赖）
#include "gamecore/system/pill_system.h"
#include "gamecore/system/nurture_constants.h"  // 熟练度/孕养常量（detail 域）
#include "gamecore/system/relative_gift.h"     // 亲属智能赠送（S1 突破下沉批）
#include "gamecore/system/settlement_detail.h"

// ============================================================
// 每旬弟子结算
//
// 等价移植 Kotlin GameEngineCore.checkBreakthroughsAndPills 的每旬结算循环，
// 注册进 SettlementEngine::onPhaseSettle 钩子（见 game_core.cpp initialize）。
//
// 六步结算（与 Kotlin 现行顺序逐位一致）——对每个存活且非秘境探索中的弟子：
//   1. HP/MP 恢复            ← CultivationService.recoverHpMpSingleColumn
//   2. 修炼累积（≥1e8 跳过）  ← accumulateCultivationPerPhase
//   3. 功法熟练度（批量暂存）  ← processManualProficiencySingle
//   4. 装备孕养（批量暂存）    ← processEquipmentNurtureSingle
// 全弟子循环后：
//   5. 批量提交熟练度 + 单次重建装备列表
//   6. 自动丹药补服           ← processAutoPillsRealtime（DisciplePillManager）
//   7. 突破检测               ← processBreakthroughs（DiscipleBreakthroughHandler）
//
// RNG 对拍命门：整个结算只有突破路径消耗 RNG——BREAKTHROUGH 分区
// nextDouble()，按 ids 顺序、每次尝试恰好一次。C++ 以相同顺序经
// rng::RngManager 复现（弟子向量顺序 == Kotlin ids 列表顺序，均源自同一
// JSON 数组）。恢复/修炼/熟练度/孕养/丹药服用零 RNG。
//
// 已知范围边界：
//   - 循环首步 processAutoFromWarehouseRealtime（自动装备/学习）：
//     仓库 + 储物袋候选 + 更高品阶替换（auto_gear.h）；
//   - 丹药写回中的偷盗判定钩子（道德<阈值 → 执法堂 SYSTEM RNG）：
//     复用 month_settlement.h 偷盗链 judgeSingleTheftCandidate（Kotlin
//     事务内版等价）；
//   - 突破后的亲属赠送（SYSTEM RNG）：relative_gift.h（Kotlin
//     RelativeGiftHandler 等价移植；lifeEvents 日志为 Kotlin 运行态字段，
//     C++ 显式丢弃）。
// ============================================================
namespace gamecore::system {

/// 每旬修炼累积跳过门限（Kotlin checkBreakthroughsAndPills 内联字面量 1e8：
/// 凡界最大修为约 2e7，≥1e8 视为异常满值直接跳过）
constexpr double kCultivationSkipThreshold = 1e8;

/// HP/MP 恢复乘区总和（RecoveryZones：base 1.0 + 建筑/丹药/境界乘区均为预留 0）
constexpr double kRecoveryZoneTotal = 1.0;

// 熟练度/孕养常量上移 nurture_constants.h（ai_sect_ops.h 共用）

/// 消息栏事件上限（GameConfig.Logs.MAX_EVENT_LOGS）
constexpr std::size_t kMaxEventLogs = 200;

namespace detail {

using gamecore::state::Disciple;
using gamecore::state::DiscipleStore;
using gamecore::state::EquipmentInstance;
using gamecore::state::GameData;
using gamecore::state::GameState;
using gamecore::state::ManualInstance;
using gamecore::state::ManualProficiencyData;
using gamecore::state::StorageBagItem;

// ── 共享索引构建 ────────────────────────────────────────────────────
// 注：toIntOrNull/containsString/indexById/spiritRootCount/kotlinCharLength/
//     isBlankString 已抽取至 settlement_detail.h（与月变钩子共享单一定义），
//     经下方别名以原名使用
namespace settle_util = gamecore::system::settle_util;
// nurtureMaxLevel/expRequiredForLevelUp 由 nurture_constants.h 直接定义于本
// detail 域（gamecore::system::detail）——无需 using
using settle_util::containsString;
using settle_util::indexById;
using settle_util::isBlankString;
using settle_util::kotlinCharLength;
using settle_util::spiritRootCount;
using settle_util::toIntOrNull;

// R1.3 第二步：装备/功法实例映射 = owner 行索引桶视图（原 associateBy id
// 全量深拷贝 map equipmentMapOf/manualMapOf 退役）
namespace inst_bucket = gamecore::system::instance_bucket;
using inst_bucket::EquipmentInstanceBuckets;
using inst_bucket::ManualInstanceBuckets;

/// 秘境探索中存活成员 id 集合（GameData.secretRealmMemberIds）
inline std::set<int32_t> secretRealmMemberIds(const GameData& gd) {
    std::set<int32_t> ids;
    for (const auto& m : gd.secretRealmSession.members) {
        if (m.isDead) continue;
        const auto id = toIntOrNull(m.discipleId);
        if (id.has_value()) ids.insert(*id);
    }
    return ids;
}

/// 血炼累计查找（bloodRefinementPctTotals[id]）
inline const state::BloodRefinementPctTotal* findBloodRefinementPct(
        const GameData& gd, const std::string& discipleId) {
    const auto it = gd.bloodRefinementPctTotals.find(discipleId);
    return (it != gd.bloodRefinementPctTotals.end()) ? &it->second : nullptr;
}

/// 含血炼口径最终 maxHp/maxMp（DiscipleStore SoA 版，热路径用；
/// battleWritebackMaxHpMp 数学等价：getFinalStats 的 maxHp/maxMp 与
/// getMaxHpMpColumn 共用同一基础公式）
inline void finalMaxHpMp(const DiscipleStore& ds, std::size_t row,
                         const GameData& gd,
                         const EquipmentInstanceBuckets& eqBuckets,
                         const ManualInstanceBuckets& mnBuckets,
                         int32_t& outMaxHp, int32_t& outMaxMp) {
    stats::getMaxHpMp(ds, row, findBloodRefinementPct(gd, ds.ids[row]),
                      eqBuckets, mnBuckets, gd.manualProficiencies, outMaxHp,
                      outMaxMp);
}

/// HP/MP 是否均已满（isDiscipleFullHpMp；负值视为满）。
/// 装备/功法**桶视图**由**步骤入口**构建一次传入：突破候选筛选（步骤 7）
/// 在核心批次（步骤 1-5 含孕养提交 applyEquipmentUpdates）之后执行，入口
/// 桶已含当旬最新 nurtureLevel——"当旬最新"语义对齐 Kotlin
/// battleWritebackMaxHpMp 的当前 state 现场口径；步骤 7 全程只读
/// equipmentInstances/manualInstances（attemptAutoPill 只写 pills/储物袋），
/// 入口桶与逐实体现场重建逐位一致。
inline bool isFullHpMp(const Disciple& d, std::size_t ownerRow,
                       const GameData& gd,
                       const EquipmentInstanceBuckets& eqBuckets,
                       const ManualInstanceBuckets& mnBuckets) {
    int32_t maxHp = 0, maxMp = 0;
    stats::getMaxHpMp(d, ownerRow, findBloodRefinementPct(gd, d.id), eqBuckets,
                      mnBuckets, gd.manualProficiencies, maxHp, maxMp);
    const int32_t hp = d.currentHp < 0 ? maxHp : d.currentHp;
    const int32_t mp = d.currentMp < 0 ? maxMp : d.currentMp;
    return hp >= maxHp && mp >= maxMp;
}

/// HP/MP 是否均已满（DiscipleStore SoA 版，候选筛选用；桶同上由步骤入口传入）
inline bool isFullHpMp(const DiscipleStore& ds, std::size_t row,
                       const GameData& gd,
                       const EquipmentInstanceBuckets& eqBuckets,
                       const ManualInstanceBuckets& mnBuckets) {
    int32_t maxHp = 0, maxMp = 0;
    stats::getMaxHpMp(ds, row, findBloodRefinementPct(gd, ds.ids[row]),
                      eqBuckets, mnBuckets, gd.manualProficiencies, maxHp,
                      maxMp);
    const int32_t hp = ds.currentHps[row] < 0 ? maxHp : ds.currentHps[row];
    const int32_t mp = ds.currentMps[row] < 0 ? maxMp : ds.currentMps[row];
    return hp >= maxHp && mp >= maxMp;
}

// ── 步骤 1：HP/MP 恢复（recoverHpMpSingleColumn 数学等价） ──────────

/// 恢复量 = maxValue × 0.2 × 1.0 × phases，toInt 截断后至少 1
inline int32_t recoveryAmount(int32_t maxValue, double multiplier) {
    const double total =
        static_cast<double>(maxValue) * stats::kPhaseHpMpRecoveryRate *
        kRecoveryZoneTotal * multiplier;
    return std::max(static_cast<int32_t>(total), 1);
}

/// HP/MP 恢复（DiscipleStore SoA 版，热路径用——列直读直写）
inline void recoverHpMp(DiscipleStore& ds, std::size_t row, const GameData& gd,
                        const EquipmentInstanceBuckets& eqBuckets,
                        const ManualInstanceBuckets& mnBuckets) {
    const int32_t curHp = ds.currentHps[row];
    const int32_t curMp = ds.currentMps[row];
    if (curHp < 0 && curMp < 0) return;   // 特殊状态（负值=满）整体跳过

    int32_t maxHp = 0, maxMp = 0;
    finalMaxHpMp(ds, row, gd, eqBuckets, mnBuckets, maxHp, maxMp);

    // 满血提前退出（负值视为满，语义与对象版一致）
    const int32_t effHp = curHp < 0 ? maxHp : curHp;
    const int32_t effMp = curMp < 0 ? maxMp : curMp;
    if (effHp >= maxHp && effMp >= maxMp) return;

    const double multiplier = 1.0;   // phasesToSettle = 1
    if (curHp >= 0) ds.currentHps[row] = std::min(curHp + recoveryAmount(maxHp, multiplier), maxHp);
    if (curMp >= 0) ds.currentMps[row] = std::min(curMp + recoveryAmount(maxMp, multiplier), maxMp);
}

// ── 步骤 2：修炼累积（accumulateCultivationPerPhase） ───────────────

/// 有效教学值 = 基础教学 + teachingFlat 天赋加成截断（getEffectiveTeaching；
/// teachingFlat 经 stats::talentEffectsFor 查 talent_db 聚合）
inline int32_t effectiveTeaching(const Disciple& elder) {
    const auto effects = stats::talentEffectsFor(elder.talentIds);
    return elder.teaching +
           static_cast<int32_t>(stats::effectValue(effects, "teachingFlat"));
}

/// 有效教学值（DiscipleStore 行版，列访问）
inline int32_t effectiveTeaching(const DiscipleStore& ds, std::size_t row) {
    const auto effects = stats::talentEffectsFor(ds.talentIds[row]);
    return ds.teachings[row] +
           static_cast<int32_t>(stats::effectValue(effects, "teachingFlat"));
}

/// 住所建筑修炼系数（数值 id 版：R1.3 dense 索引——调用方从数值 id 列
/// 直读传入，免字符串重解析；查找语义与字符串版逐位一致）
inline double residenceBuildingBonus(const GameData& gd,
                                     std::optional<int32_t> idOpt) {
    if (!idOpt.has_value()) return 1.0;
    for (const auto& slot : gd.residenceSlots) {
        const auto rid = toIntOrNull(slot.discipleId);
        if (!rid.has_value() || *rid != *idOpt) continue;
        for (const auto& b : gd.placedBuildings) {
            if (b.instanceId == slot.buildingInstanceId) {
                return stats::buildingCultivationBonus(b.displayName);
            }
        }
        return 1.0;   // 有槽无建筑 → 无加成
    }
    return 1.0;
}

/// 住所建筑修炼系数（calculateBuildingCultivationBonus 列直读版：
/// 首匹配槽位 → 匹配建筑 displayName 查表；无槽/无建筑 → 1.0）
inline double residenceBuildingBonus(const GameData& gd,
                                     const std::string& discipleIdStr) {
    return residenceBuildingBonus(gd, toIntOrNull(discipleIdStr));
}

/// 讲道长老/师兄加成（calculatePreachingBonusesColumn；inner=false 外门 / true 青云内门）。
/// DiscipleStore SoA 版：长老/师兄经 id→行查列直读。
inline void preachingBonuses(
        const GameState& state, const std::map<int32_t, std::size_t>& idx,
        int32_t discipleRealm, const std::string& discipleType, bool inner,
        double& elderOut, double& mastersOut) {
    elderOut = 0.0;
    mastersOut = 0.0;
    const std::string& targetType = inner ? "inner" : "outer";
    if (discipleType != targetType) return;
    const auto& slots = state.gameData.elderSlots;
    const DiscipleStore& ds = state.disciples;

    // 长老/师兄行查找（names.contains 校验 + isAlive 存活校验）
    const auto elderRow = [&](const std::string& elderId)
            -> std::optional<std::size_t> {
        const auto id = toIntOrNull(elderId);
        if (!id.has_value()) return std::nullopt;
        const auto it = idx.find(*id);
        if (it == idx.end()) return std::nullopt;
        if (ds.isAlive[it->second] == 0) return std::nullopt;
        return it->second;
    };

    // 长老加成：教学 ≥80 且弟子境界不低于长老 → ((教学-80)×0.0025).cap(0.10) × (1+职务加成)
    const auto elder = elderRow(inner ? slots.qingyunPreachingElder
                                      : slots.preachingElder);
    if (elder.has_value()) {
        const int32_t teaching = effectiveTeaching(ds, *elder);
        if (discipleRealm >= ds.realms[*elder] && teaching >= 80) {
            const double base = gamecore::disciple::coerceAtMost(
                (teaching - 80) * 0.0025, 0.10);
            // 职务加成 PositionBonus：此处置 1.0 未乘入
            elderOut = base * 1.0;
        }
    }

    // 师兄加成：教学 ≥60 且弟子境界不低于师兄 → ((教学-60)×0.001).cap(0.05) 累加
    const auto& masters = inner ? slots.qingyunPreachingMasters
                                : slots.preachingMasters;
    for (const auto& slot : masters) {
        const auto master = elderRow(slot.discipleId);
        if (!master.has_value()) continue;
        const int32_t teaching = effectiveTeaching(ds, *master);
        if (discipleRealm >= ds.realms[*master] && teaching >= 60) {
            mastersOut += gamecore::disciple::coerceAtMost(
                (teaching - 60) * 0.001, 0.05);
        }
    }
}

/// 父母灵根加成（calculateParentBonusColumn；DiscipleStore 列直读版）
inline double parentBonusFor(const GameState& state,
                             const std::map<int32_t, std::size_t>& idx,
                             const std::string& parentId) {
    const auto id = toIntOrNull(parentId);
    if (!id.has_value()) return 0.0;
    const auto it = idx.find(*id);
    if (it == idx.end()) return 0.0;
    const DiscipleStore& ds = state.disciples;
    if (ds.isAlive[it->second] == 0) return 0.0;
    return gamecore::disciple::getParentSpiritRootBonus(
        spiritRootCount(ds.spiritRootTypes[it->second]));
}

/// 师徒修炼加成（calculateMasterDiscipleBonusColumn：师父存活按大境界差加成；
/// DiscipleStore 列直读版——masterId 与弟子境界由调用方列直读传入）
inline double masterBonusFor(const GameState& state,
                             const std::map<int32_t, std::size_t>& idx,
                             const std::string& masterId,
                             int32_t discipleRealm) {
    const auto mid = toIntOrNull(masterId);
    if (!mid.has_value()) return 0.0;
    const auto it = idx.find(*mid);
    if (it == idx.end()) return 0.0;
    const DiscipleStore& ds = state.disciples;
    if (ds.isAlive[it->second] == 0) return 0.0;
    return gamecore::disciple::getMasterDiscipleCultivationBonus(
        discipleRealm, ds.realms[it->second]);
}

/// 步骤 2：单弟子每旬修炼累积（速率计算 + 上限钳制；不更新检查点——
/// checkpoint 只在速率变化点同步）。DiscipleStore SoA 版（热路径）：
/// 全链路列直读直写，零对象物化。
inline void accumulateCultivation(
        GameState& state, std::size_t row,
        const std::map<int32_t, std::size_t>& idx,
        const ManualInstanceBuckets& mnBuckets) {
    DiscipleStore& ds = state.disciples;
    const GameData& gd = state.gameData;
    const int32_t realm = ds.realms[row];
    const int32_t realmLayer = ds.realmLayers[row];
    const double cultivation = ds.cultivations[row];
    const double maxCultivation = computeMaxCultivation(
        realm, realmLayer, cultivation);
    if (cultivation >= maxCultivation) return;

    stats::CultivationRateInput extra;
    extra.buildingBonus = residenceBuildingBonus(gd, ds.numericIdAt(row));

    double wenDaoElder = 0.0, wenDaoMasters = 0.0;
    preachingBonuses(state, idx, realm, ds.discipleTypes[row], false,
                     wenDaoElder, wenDaoMasters);
    double qingyunElder = 0.0, qingyunMasters = 0.0;
    preachingBonuses(state, idx, realm, ds.discipleTypes[row], true,
                     qingyunElder, qingyunMasters);
    extra.preachingElderBonus = wenDaoElder + qingyunElder;
    extra.preachingMastersBonus = wenDaoMasters + qingyunMasters;
    extra.parentCultivationBonus =
        parentBonusFor(state, idx, ds.parentId1s[row]) +
        parentBonusFor(state, idx, ds.parentId2s[row]);
    extra.masterDiscipleBonus =
        masterBonusFor(state, idx, ds.masterIds[row], realm);

    const double rate = stats::calculateCultivationPerPhaseColumn(
        ds, row, gd, mnBuckets, gd.manualProficiencies, extra);
    if (rate <= 0.0) return;
    ds.cultivations[row] = gamecore::disciple::coerceAtMost(
        cultivation + rate, maxCultivation);
}

// ── 步骤 3：功法熟练度（批量暂存 + 单次提交） ────────────────────────

using PendingProficiencies =
    std::map<std::string, std::optional<std::vector<ManualProficiencyData>>>;

/// 单功法条目熟练度结算（accumulateProficiencyForManual）；返回是否变化
inline bool accumulateProficiencyForManual(
        std::vector<ManualProficiencyData>& profList,
        const std::string& manualId, const ManualInstance& manual,
        double gain) {
    const auto it = std::find_if(profList.begin(), profList.end(),
        [&](const ManualProficiencyData& p) { return p.manualId == manualId; });
    const double cap = static_cast<double>(kMaxProficiency);
    if (it != profList.end()) {
        const double newProf =
            gamecore::disciple::coerceAtMost(it->proficiency + gain, cap);
        if (newProf != it->proficiency) {
            it->proficiency = newProf;
            it->masteryLevel = stats::masteryLevelFromProficiency(newProf);
            return true;
        }
        return false;
    }
    ManualProficiencyData entry;
    entry.manualId = manualId;
    entry.manualName = manual.name;
    entry.proficiency = gamecore::disciple::coerceAtMost(gain, cap);
    entry.maxProficiency = kMaxProficiency;
    entry.masteryLevel = stats::masteryLevelFromProficiency(entry.proficiency);
    profList.push_back(entry);
    return true;
}

/// 步骤 3：单弟子熟练度增长（批量模式：只暂存到 pending，不写 state；
/// pending 显式条目优先于旧值，防同周期双倍增长。DiscipleStore 行版——
/// 原对象版无调用方，随桶迁移删除）
inline void processManualProficiency(
        const GameData& gd, const DiscipleStore& ds, std::size_t row,
        const ManualInstanceBuckets& mnBuckets,
        bool inLibrary, PendingProficiencies& pending) {
    const std::vector<std::string>& manualIds = ds.manualIds[row];
    const std::string& id = ds.ids[row];
    if (manualIds.empty()) return;
    const double libraryBonus =
        inLibrary ? kLibraryProficiencyBonusRate : 0.0;
    const double profGain = kBaseProficiencyRate * (1.0 + libraryBonus) *
                            kMsPerPhase1x / 1000.0;
    if (profGain <= 0.0) return;

    std::vector<ManualProficiencyData> profList;
    const auto pit = pending.find(id);
    if (pit != pending.end()) {
        if (pit->second.has_value()) profList = *pit->second;
    } else {
        const auto git = gd.manualProficiencies.find(id);
        if (git != gd.manualProficiencies.end()) profList = git->second;
    }

    bool changed = false;
    for (const std::string& manualId : manualIds) {
        const state::ManualInstance* manual = mnBuckets.find(row, manualId);
        if (manual == nullptr) continue;
        if (accumulateProficiencyForManual(profList, manualId, *manual,
                                           profGain)) {
            changed = true;
        }
    }
    // 清理已替换/遗忘功法的残留条目（防僵尸条目累积）
    const auto newEnd = std::remove_if(profList.begin(), profList.end(),
        [&](const ManualProficiencyData& p) {
            return !containsString(manualIds, p.manualId);
        });
    if (newEnd != profList.end()) {
        profList.erase(newEnd, profList.end());
        changed = true;
    }

    if (changed) {
        pending[id] = profList.empty()
            ? std::nullopt
            : std::optional<std::vector<ManualProficiencyData>>(profList);
    }
}

/// 批量提交熟练度（commitManualProficiencies：null 条目移除，其余覆盖）
inline void commitManualProficiencies(
        GameData& gd, const PendingProficiencies& pending) {
    if (pending.empty()) return;
    for (const auto& kv : pending) {
        if (!kv.second.has_value()) {
            gd.manualProficiencies.erase(kv.first);
        } else {
            gd.manualProficiencies[kv.first] = *kv.second;
        }
    }
}

// ── 步骤 4：装备孕养（批量暂存 + 单次重建） ──────────────────────────

/// 孕养经验应用（updateNurtureExp）；返回是否变化
inline bool applyNurtureExp(EquipmentInstance& eq, double gain) {
    const int32_t maxLevel = nurtureMaxLevel(eq.rarity);
    if (eq.nurtureLevel >= maxLevel) return false;
    const double newProgress = eq.nurtureProgress + gain;
    const double required = expRequiredForLevelUp(eq.nurtureLevel, eq.rarity);
    if (newProgress >= required) {
        eq.nurtureLevel = std::min(eq.nurtureLevel + 1, maxLevel);
        eq.nurtureProgress =
            (eq.nurtureLevel >= maxLevel) ? 0.0 : newProgress - required;
    } else {
        eq.nurtureProgress = newProgress;
    }
    return true;
}

/// 孕养度丹应用：N 点均分到已装备装备实例
/// （向下取整，余数给第一件；满级装备跳过——该件增益不累积）。
/// 无装备实例时零效果（丹药照常扣除，与 Kotlin 镜像一致）。
inline void applyNurtureEffect(state::GameState& state, Disciple& d,
                               int32_t nurtureAdd) {
    if (nurtureAdd <= 0) return;
    std::vector<std::string> equippedIds;
    if (!d.weaponId.empty()) equippedIds.push_back(d.weaponId);
    if (!d.armorId.empty()) equippedIds.push_back(d.armorId);
    if (!d.bootsId.empty()) equippedIds.push_back(d.bootsId);
    if (!d.accessoryId.empty()) equippedIds.push_back(d.accessoryId);
    if (equippedIds.empty()) return;
    const int32_t per = nurtureAdd / static_cast<int32_t>(equippedIds.size());
    const int32_t remainder =
        nurtureAdd % static_cast<int32_t>(equippedIds.size());
    for (std::size_t i = 0; i < equippedIds.size(); ++i) {
        const int32_t gain = per + (i == 0 ? remainder : 0);
        if (gain <= 0) continue;
        for (state::EquipmentInstance& eq : state.equipmentInstances) {
            if (eq.id != equippedIds[i]) continue;
            applyNurtureExp(eq, static_cast<double>(gain));
            break;
        }
    }
}

/// 步骤 4：单弟子四槽孕养增长（批量模式：从入口桶视图读原值，
/// 更新累积到 updates——与 Kotlin settleNurtureInPlace 读写面一致；
/// DiscipleStore 行版——原对象版无调用方，随桶迁移删除）
inline void processEquipmentNurture(
        const DiscipleStore& ds, std::size_t row,
        const EquipmentInstanceBuckets& eqBuckets,
        std::map<std::string, EquipmentInstance>& updates) {
    for (const std::string& eqId :
         {ds.weaponIds[row], ds.armorIds[row], ds.bootsIds[row], ds.accessoryIds[row]}) {
        if (eqId.empty()) continue;
        const state::EquipmentInstance* shared = eqBuckets.find(row, eqId);
        if (shared == nullptr) continue;
        EquipmentInstance working = *shared;
        if (applyNurtureExp(working, kNurtureGainPerPhase)) {
            updates[eqId] = working;
        }
    }
}

/// 单次重建装备实例列表（applyEquipmentUpdates：保持原序按 id 覆盖）
inline void applyEquipmentUpdates(
        GameState& state, const std::map<std::string, EquipmentInstance>& updates) {
    if (updates.empty()) return;
    for (auto& eq : state.equipmentInstances) {
        const auto it = updates.find(eq.id);
        if (it != updates.end()) eq = it->second;
    }
}

// ── 步骤 6：自动丹药补服（processAutoPillsRealtime） ─────────────────

/// 丹药服用排序优先级（classify → priority）
inline int32_t rulePriorityOf(const StorageBagItem& item) {
    return pill::rulePriority(pill::classify(*item.effect));
}

/// 指纹检测：储物袋中是否有可服用的非突破丹药（hasUsablePills）
inline bool hasUsablePills(const Disciple& d) {
    if (d.storageBagItems.empty()) return false;
    for (const StorageBagItem& item : d.storageBagItems) {
        if (item.itemType != "pill") continue;
        if (!item.effect.has_value()) continue;
        switch (pill::classify(*item.effect)) {
            case pill::PillRule::kBreakthrough:
                continue;   // 突破丹由突破处理器内联消费
            case pill::PillRule::kTemporaryBattle:
                continue;   // C2：战斗临时丹不自动服用（保留手动/战前结算）
            case pill::PillRule::kPermanentBaseAttr: {
                bool allUsed = true;
                for (const auto& k :
                     pill::buildUsedKeys(*item.effect, item.effect->tier)) {
                    if (!containsString(d.usedPermanentPillKeys, k)) {
                        allUsed = false;
                        break;
                    }
                }
                if (!allUsed) return true;
                continue;
            }
            case pill::PillRule::kPermanentLife:
                if (!containsString(d.usedExtendLifePillTypes,
                                    item.effect->pillType)) return true;
                continue;
            default: {
                // C1：满血/满蓝治疗丹指纹排除（与 canUsePill 同源口径）
                if (pill::healGatingBlocked(d, *item.effect)) continue;
                return true;
            }
        }
    }
    return false;
}

/// 自动服用主流程（DisciplePillManager.processAutoUsePills；返回是否实际服用。
/// 排序：规则优先级降序 + 稀有度降序的稳定排序；突破丹被排除。
/// 战斗临时丹（kTemporaryBattle）不自动服用；
/// 满修为不浪费修为丹、全功法满级不浪费功法经验丹）
inline bool autoUsePills(Disciple& d, state::GameState& state) {
    std::vector<const StorageBagItem*> pillItems;
    for (const StorageBagItem& item : d.storageBagItems) {
        if (item.itemType != "pill") continue;
        if (!item.effect.has_value()) continue;
        if (pill::classify(*item.effect) == pill::PillRule::kBreakthrough) continue;
        if (pill::classify(*item.effect) == pill::PillRule::kTemporaryBattle) continue;
        pillItems.push_back(&item);
    }
    if (pillItems.empty()) return false;
    std::stable_sort(pillItems.begin(), pillItems.end(),
        [](const StorageBagItem* a, const StorageBagItem* b) {
            const int32_t pa = rulePriorityOf(*a);
            const int32_t pb = rulePriorityOf(*b);
            if (pa != pb) return pa > pb;          // priority 降序
            return a->rarity > b->rarity;          // rarity 降序
        });

    Disciple working = d;
    bool used = false;
    for (const StorageBagItem* item : pillItems) {
        // canUsePill 以"已应用前序丹药的最新弟子状态"判定（Kotlin updatedDisciple 链）
        if (!pill::canUsePill(working, *item->effect)) continue;
        const state::ItemEffect& e = *item->effect;
        // C3：满修为/全功法满级时不浪费修为丹/功法经验丹
        if (e.cultivationAdd > 0) {
            const double maxCult = computeMaxCultivation(
                working.realm, working.realmLayer, working.cultivation);
            if (working.cultivation >= maxCult) continue;
        }
        if (e.skillExpAdd > 0) {
            bool skip = working.manualMasteries.empty();
            if (!skip) {
                skip = true;
                for (const auto& kv : working.manualMasteries) {
                    if (kv.second < 10000) { skip = false; break; }
                }
            }
            if (skip) continue;
        }
        // A2：孕养度丹均分至已装备装备实例（nurtureAdd>0 才生效）
        if (e.nurtureAdd > 0) applyNurtureEffect(state, working, e.nurtureAdd);
        pill::applyToDisciple(working, *item);
        working.storageBagItems = pill::decreaseItemQuantity(
            working.storageBagItems, item->itemId, 1);
        used = true;
    }
    if (used) d = working;
    return used;
}

/// 丹药结果字段级写回（writePillResultToTables 的精确字段面；
/// 偷盗判定钩子属执法系统未下沉——见文件头范围边界注释）
inline void writePillResult(Disciple& d, const Disciple& r, GameData& gd) {
    d.storageBagItems = r.storageBagItems;
    d.cultivation = r.cultivation;
    d.manualMasteries = r.manualMasteries;
    // 修复语义：修炼速度加成统一收敛于 pillEffects 体系，
    // 写回时清零旧 cultivationSpeedBonus 组件列（残留数据自愈）
    d.cultivationSpeedBonus = 0.0;
    d.cultivationSpeedDuration = 0;
    d.lifespan = r.lifespan;
    // 技能字段（永久属性丹）
    d.intelligence = r.intelligence;
    d.charm = r.charm;
    d.loyalty = r.loyalty;
    d.comprehension = r.comprehension;
    d.artifactRefining = r.artifactRefining;
    d.pillRefining = r.pillRefining;
    d.spiritPlanting = r.spiritPlanting;
    d.teaching = r.teaching;
    d.morality = r.morality;
    d.mining = r.mining;
    // PillEffects 字段
    d.pillPhysicalAttackBonus = r.pillPhysicalAttackBonus;
    d.pillMagicAttackBonus = r.pillMagicAttackBonus;
    d.pillPhysicalDefenseBonus = r.pillPhysicalDefenseBonus;
    d.pillMagicDefenseBonus = r.pillMagicDefenseBonus;
    d.pillHpBonus = r.pillHpBonus;
    d.pillMpBonus = r.pillMpBonus;
    d.pillSpeedBonus = r.pillSpeedBonus;
    d.pillCritRateBonus = r.pillCritRateBonus;
    d.pillCritEffectBonus = r.pillCritEffectBonus;
    d.pillCultivationSpeedBonus = r.pillCultivationSpeedBonus;
    d.pillSkillExpSpeedBonus = r.pillSkillExpSpeedBonus;
    d.pillNurtureSpeedBonus = r.pillNurtureSpeedBonus;
    d.pillEffectDuration = r.pillEffectDuration;
    d.activePillTypes = r.activePillTypes;
    // 使用追踪
    d.usedPermanentPillKeys = r.usedPermanentPillKeys;
    d.usedExtendLifePillTypes = r.usedExtendLifePillTypes;
    // HP/MP（治疗丹）
    d.currentHp = r.currentHp;
    d.currentMp = r.currentMp;

    // Checkpoint：丹药可能改变修炼速率（持续加速/瞬间增长），同步检查点
    // （checkpointDisciple 读当前 cultivations 列值 → 已写回的新修为）
    d.cultivationCheckpoint = d.cultivation;
    d.cultivationCheckpointGameMonth = gd.gameYear * 12 + gd.gameMonth;
}

/// 步骤 6 主流程：遍历存活非秘境弟子，自动补服储物袋丹药。
/// DiscipleStore SoA 版：逐弟子物化工作副本 → 服用 → 原位写回
///（仅实际服用时写回）。丹药写回含偷盗钩子——
/// 服用后道德 < 阈值 → judgeSingleTheftCandidate（SYSTEM RNG，与 Kotlin
/// writePillResultToTables 道德变化后即时触发逐位一致）。
/// 迭代序：id 快照序（== Kotlin tables.ids）——偷盗后叛逃可能按 id 移除
/// 行（原位 erase 使行号漂移），每弟子经 rowOf 现查行号。
/// id 快照经 syncDiscipleEntities + View<DiscipleRef>
/// 行序构建（快照序 == 行序 == Kotlin ids 序，语义逐位不变；快照后行移除
/// 仍由 rowOf 现查兜底）。
inline void processAutoPills(GameState& state,
                             const std::set<int32_t>& secretIds,
                             rng::RngManager& rng, ecs::World& world) {
    DiscipleStore& ds = state.disciples;
    const int32_t currentMonth =
        state.gameData.gameYear * 12 + state.gameData.gameMonth;
    auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    ecs::syncDiscipleEntities(world, ds.size());
    std::vector<int32_t> idSnapshot;
    ecs::View<ecs::DiscipleRef> view(world.registry());
    view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
        const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
        if (ds.isAlive[row] == 0) return;
        const auto id = ds.numericIdAt(row);
        if (!id.has_value() || secretIds.count(*id)) return;
        idSnapshot.push_back(*id);
    });
    for (const int32_t id : idSnapshot) {
        const auto rowOpt = ds.rowOfNumber(id);
        if (!rowOpt.has_value()) continue;   // 前序钩子已移除（叛逃）
        const std::size_t row = *rowOpt;

        Disciple d = ds.materialize(row);
        if (!hasUsablePills(d)) continue;
        Disciple working = d;
        if (!autoUsePills(working, state)) continue;   // result.disciple == disciple → 跳过
        writePillResult(d, working, state.gameData);
        ds.upsertDisciple(d);                   // 原位写回（保序）
        // 偷盗判定钩子（写回后道德判定；Kotlin 事务内版全链等价）
        if (ds.moralities[row] < lawMoralityThreshold()) {
            judgeSingleTheftCandidate(state, id, currentMonth, rngSystem, world);
        }
    }
}

// ── 步骤 7：突破检测（processBreakthroughs / performBreakthrough） ───

/// 结算入口长老悟性 committed 视图（R1.2 去物化：原全量 D 弟子物化快照
/// committedDisciples 的唯一消费点 = breakthroughChanceInput 的长老悟性
/// 读取——内/外门长老位 ≤2 名弟子。本函数按 elderSlots 数值 id 行扫描，
/// 以 SoA 列直算 baseComprehension 捕获**入口时点**值，零物化——
/// 每旬 D 次深拷贝 → 0 次，剩余物化仅突破命中候选的工作副本）。
///
/// 与原全量快照逐位一致：
/// - 键命中 = 该数值 id 在**结算入口**已存在（同数值 id 多行保留首行，
///   与原 emplace 首写语义一致）；
/// - 值 = 入口时点 comprehensions/talentIds/affixIds 列的 baseComprehension
///   （与物化快照同列同序计算，逐位一致）；
/// - 结算步骤间 elderSlots 无重指派（任命属 UI 事务不入结算；偷盗后叛逃
///   仅**清空**叛逃长老槽位——消费点读空 id 提前返回，不入本表查询），
///   步骤 7 读到的非空长老 id 与入口一致；
/// - 入口后新出现/非数值 id 的长老 → 不入表，消费点回退 live 列
///   （与原快照缺失路径一致，idx 于步骤 7 现查）。
inline std::map<int32_t, int32_t> committedElderComprehensionOf(
        const GameState& state) {
    std::map<int32_t, int32_t> out;
    const DiscipleStore& ds = state.disciples;
    const auto capture = [&](const std::string& elderId) {
        const auto eid = toIntOrNull(elderId);
        if (!eid.has_value() || out.count(*eid) > 0) return;
        for (std::size_t i = 0; i < ds.size(); ++i) {
            const auto id = ds.numericIdAt(i);
            if (!id.has_value() || *id != *eid) continue;
            out.emplace(*eid, stats::baseComprehension(ds, i));
            return;   // 首行即止（同数值 id 保留首行 == emplace 首写）
        }
    };
    capture(state.gameData.elderSlots.innerElder);
    capture(state.gameData.elderSlots.outerElder);
    return out;
}

/// 突破概率输入组装（tryBreakthrough 的长老悟性/职务/广告/丧亲/师徒提取）。
/// @param committedElderComprehension 结算入口长老悟性 committed 视图
///        （id → 入口时点基础悟性——对齐 Kotlin tryBreakthrough:282 经
///        stateStore.disciples.value（事务前已提交视图，按 id 关联）读取
///        长老悟性；id 键控使偷盗叛逃等行移除后仍正确关联；视图缺失
///        （入口后新出现）回退 live 列）；存活/境界条件判断仍用 live 状态
inline stats::BreakthroughChanceInput breakthroughChanceInput(
        const Disciple& d, const GameState& state,
        const std::map<int32_t, std::size_t>& idx,
        const std::map<int32_t, int32_t>& committedElderComprehension,
        double pillBonus) {
    stats::BreakthroughChanceInput in;
    in.pillBonus = pillBonus;   // 突破丹概率加成（attemptAutoPill 返回值）
    const GameData& gd = state.gameData;
    const auto& slots = gd.elderSlots;

    // 内/外门长老悟性（仅对应弟子类型生效）：存活与境界门槛按 live 列判定，
    // 悟性数值/职务加成取**结算入口已提交视图**的长老对象（getBaseStats()
    // .comprehension 口径）；职务加成 PositionBonus 此处恒返回 0（未乘入），
    // 聚合口径见 stats::positionEffectBonus
    const auto elderEntry = [&](const std::string& elderId,
                                const char* requiredType)
            -> std::pair<int32_t, double> {   // (comprehension, positionBonus)
        if (d.discipleType != requiredType || elderId.empty()) return {0, 0.0};
        const auto eid = toIntOrNull(elderId);
        if (!eid.has_value()) return {0, 0.0};
        const auto it = idx.find(*eid);
        if (it == idx.end()) return {0, 0.0};
        const DiscipleStore& ds = state.disciples;
        if (ds.isAlive[it->second] == 0 || d.realm < ds.realms[it->second]) {
            return {0, 0.0};
        }
        // Kotlin: allDisciples[elderId]?.getBaseStats()?.comprehension
        //         ?: tables.comprehensions[elderId] —— 快照优先、live 列兜底
        const auto cit = committedElderComprehension.find(*eid);
        if (cit != committedElderComprehension.end()) {
            return {cit->second, 0.0};
        }
        return {stats::baseComprehension(ds, it->second), 0.0};
    };

    const auto innerPair = elderEntry(slots.innerElder, "inner");
    const auto outerPair = elderEntry(slots.outerElder, "outer");
    in.innerElderComprehension = innerPair.first;
    in.outerElderComprehension = outerPair.first;
    in.innerElderPositionBonus = innerPair.second;
    in.outerElderPositionBonus = outerPair.second;

    // 广告扁平加成（仅对下一次尝试有效；statusData["adBreakthroughBonus"]；
    // toDoubleOrNull 全串校验语义——部分解析失败视为无加成）
    const auto adIt = d.statusData.find("adBreakthroughBonus");
    if (adIt != d.statusData.end()) {
        try {
            std::size_t parsed = 0;
            const double v = std::stod(adIt->second, &parsed);
            if (parsed == adIt->second.size()) in.adBonus = v;
        } catch (...) {}
    }

    // 丧亲惩罚（GRIEF_BREAKTHROUGH_CHANCE_PENALTY = 0.20）
    if (d.griefEndYear >= 0 && gd.gameYear < d.griefEndYear) {
        in.griefBreakthroughPenalty =
            gamecore::disciple::kGriefBreakthroughPenalty;
    }

    // 师徒加成（师父存活按大境界差）
    const auto mid = toIntOrNull(d.masterId);
    if (mid.has_value()) {
        const auto it = idx.find(*mid);
        const DiscipleStore& ds = state.disciples;
        if (it != idx.end() && ds.isAlive[it->second] != 0) {
            in.masterDiscipleBonus =
                gamecore::disciple::getMasterDiscipleBreakthroughBonus(
                    d.realm, ds.realms[it->second]);
        }
    }
    return in;
}

/// 消息栏事件记录（recordGameEvent SECT/BREAKTHROUGH；timestamp 为现实墙钟
/// Clock 注入口，Kotlin 默认 System.currentTimeMillis()——对拍不比较该字段；
/// 追加序号 max+1 溢出回 1；takeLast(MAX_EVENT_LOGS) 裁剪语义保留；
/// 守卫与 MutableGameState.recordGameEvent 逐条对齐：blank/长度上限）
inline void recordGameEvent(GameState& state, const Disciple& after,
                            const std::string& newRealmName) {
    const std::string summary =
        "弟子" + after.name + "突破至" + newRealmName + "！";
    // Kotlin 守卫（MutableGameState.kt:119-123）：summary/eventType blank 拒绝；
    // 四字段长度上限（Kotlin String.length 口径）
    if (isBlankString(summary) || isBlankString("breakthrough")) return;
    if (kotlinCharLength(summary) > 200) return;
    if (kotlinCharLength("breakthrough") > 50) return;
    if (kotlinCharLength(after.id) > 50) return;
    if (kotlinCharLength(after.name) > 50) return;

    state::GameEventRecord event;
    event.year = state.gameData.gameYear;
    event.month = state.gameData.gameMonth;
    event.phase = state.gameData.gamePhase;
    event.category = "SECT";
    event.eventType = "breakthrough";
    event.summary = summary;
    event.relatedEntityId = after.id;
    event.relatedEntityName = after.name;
    int64_t maxSeq = 0;
    for (const auto& r : state.gameData.gameEventRecords) {
        maxSeq = std::max(maxSeq, r.sequenceId);
    }
    event.sequenceId = (maxSeq >= INT64_MAX - 1) ? 1 : maxSeq + 1;
    auto& records = state.gameData.gameEventRecords;
    records.push_back(event);
    if (records.size() > kMaxEventLogs) {
        records.erase(records.begin(),
                      records.end() - static_cast<std::ptrdiff_t>(kMaxEventLogs));
    }
}

/// 突破成功应用（applyBreakthroughSuccess：修为清零 + 层数/大境界推进 +
/// 大境界寿命增益）
inline void applyBreakthroughSuccess(Disciple& d) {
    d.cultivation = 0.0;
    const int32_t oldRealm = d.realm;
    const auto& rc = gamecore::disciple::realmConfig(d.realm);
    if (d.realmLayer < rc.maxLayers) {
        d.realmLayer += 1;
    } else {
        d.realm -= 1;
        d.realmLayer = 1;
    }
    if (d.realm != oldRealm) {
        d.lifespan += stats::calculateBreakthroughLifespanGain(
            d.realm, d.talentIds, d.affixIds);
    }
}

/// 突破失败应用（applyBreakthroughFailure：修为清零 + HP/MP × 10% 至少 1；
/// curHp/currentMp 负数取基础口径 maxHp/maxMp = getBaseStats()，无装备段）
inline void applyBreakthroughFailure(Disciple& d) {
    const auto effects = stats::mergeEffects(stats::talentEffectsFor(d.talentIds),
                                             stats::affixEffectsFor(d.affixIds));
    int32_t maxHp = 0, maxMp = 0;
    stats::computeBaseHpMp(d.realm, d.realmLayer, d.hpVariance, d.mpVariance,
                           effects, nullptr, maxHp, maxMp);
    const int32_t curHp = d.currentHp < 0 ? maxHp : d.currentHp;
    const int32_t curMp = d.currentMp < 0 ? maxMp : d.currentMp;
    d.cultivation = 0.0;
    d.currentHp = std::max(
        static_cast<int32_t>(static_cast<double>(curHp) *
                             kBreakthroughFailureHpMpRatio), 1);
    d.currentMp = std::max(
        static_cast<int32_t>(static_cast<double>(curMp) *
                             kBreakthroughFailureHpMpRatio), 1);
}

/// 自动服用突破丹（attemptAutoPill）：仓库优先 → 储物袋兜底。
/// @param pillTargetRealm 丹药目标境界（满层大境界突破取 realm-1）
/// @return (突破率加成, 是否修改了弟子储物袋)；两处 maxByOrNull 均
///         取"首个最大值"（Kotlin maxByOrNull 语义，严格大于才替换）。
/// 消耗语义：**逐颗扣减**——仓库堆叠 quantity>1
/// 减一保留、=1 整条移除（对齐 LootCalculator 的 update+filterInPlace
/// 消费模式）；储物袋经 decreaseItemQuantity 减一（禁止整叠删除，
/// 否则一次服用会丢失整堆数量）。
inline std::pair<double, bool> attemptAutoPill(
        Disciple& d, int32_t pillTargetRealm, GameState& state) {
    const GameData& gd = state.gameData;
    const bool autoFocused = gd.breakthroughAutoPillFocused;
    const auto& autoRootCounts = gd.breakthroughAutoPillRootCounts;
    if (!autoFocused && autoRootCounts.empty()) return {0.0, false};

    const auto followedIt = d.statusData.find("followed");
    const bool followed =
        (followedIt != d.statusData.end() && followedIt->second == "true");
    const int32_t roots = spiritRootCount(d);
    const bool qualifies =
        (autoFocused && followed) ||
        std::find(autoRootCounts.begin(), autoRootCounts.end(), roots) !=
            autoRootCounts.end();
    if (!qualifies) return {0.0, false};

    // 仓库突破丹优先
    const state::Pill* best = nullptr;
    double bestChance = -1.0;
    std::size_t bestIndex = 0;
    for (std::size_t i = 0; i < state.pills.size(); ++i) {
        const auto& p = state.pills[i];
        if (p.pillType != "breakthrough") continue;
        if (p.effects.targetRealm != pillTargetRealm) continue;
        if (p.effects.breakthroughChance > bestChance) {
            bestChance = p.effects.breakthroughChance;
            best = &p;
            bestIndex = i;
        }
    }
    if (best != nullptr) {
        // 逐颗扣减：quantity>1 减一保留，=1 整条移除
        if (state.pills[bestIndex].quantity > 1) {
            state.pills[bestIndex].quantity -= 1;
        } else {
            state.pills.erase(state.pills.begin() +
                              static_cast<std::ptrdiff_t>(bestIndex));
        }
        return {bestChance, false};
    }

    // 储物袋兜底（逐颗扣减：decreaseItemQuantity(quantity-1)，quantity=1 移除）
    const StorageBagItem* bagBest = nullptr;
    double bagBestChance = -1.0;
    for (const StorageBagItem& item : d.storageBagItems) {
        if (item.itemType != "pill") continue;
        if (!item.effect.has_value()) continue;
        if (item.effect->pillType != "breakthrough") continue;
        if (item.effect->targetRealm != pillTargetRealm) continue;
        if (item.effect->breakthroughChance > bagBestChance) {
            bagBestChance = item.effect->breakthroughChance;
            bagBest = &item;
        }
    }
    if (bagBest != nullptr) {
        d.storageBagItems = pill::decreaseItemQuantity(
            d.storageBagItems, bagBest->itemId, 1);
        return {bagBestChance, true};
    }
    return {0.0, false};
}

/// 突破后修炼完成时间预估（updateCompletionEstimate；速率复用列直读乘区——
/// 与 Kotlin 对象版共享同一公式源；功法段经入口桶视图查找——R1.3 第二步，
/// 原逐候选 manualMapOf 全量重建退役；ownerRow = 弟子原行号，工作副本
/// 突破后的境界推进不影响桶键）
inline void updateCompletionEstimate(Disciple& d, GameState& state,
                                     const std::map<int32_t, std::size_t>& idx,
                                     std::size_t ownerRow,
                                     const ManualInstanceBuckets& mnBuckets) {
    const GameData& gd = state.gameData;
    const int32_t currentMonth = gd.gameYear * 12 + gd.gameMonth;

    stats::CultivationRateInput extra;
    extra.buildingBonus = residenceBuildingBonus(gd, d.id);
    double we = 0.0, wm = 0.0, qe = 0.0, qm = 0.0;
    preachingBonuses(state, idx, d.realm, d.discipleType, false, we, wm);
    preachingBonuses(state, idx, d.realm, d.discipleType, true, qe, qm);
    extra.preachingElderBonus = we + qe;
    extra.preachingMastersBonus = wm + qm;
    extra.parentCultivationBonus =
        parentBonusFor(state, idx, d.parentId1) +
        parentBonusFor(state, idx, d.parentId2);
    extra.masterDiscipleBonus =
        masterBonusFor(state, idx, d.masterId, d.realm);

    const double rate = stats::calculateCultivationPerPhaseColumn(
        d, ownerRow, gd, mnBuckets, gd.manualProficiencies, extra);
    const double maxCult = computeMaxCultivation(
        d.realm, d.realmLayer, d.cultivation);
    const double remaining =
        (d.cultivation < maxCult) ? (maxCult - d.cultivation) : 0.0;
    d.cultivationCompletionMonth =
        currentMonth + estimateMonthsToNextBreakthrough(remaining, rate);
    d.cultivationCompletionPhase = 1;
}

/// 单弟子连续突破循环（performBreakthrough 核心）。
/// RNG 契约：每次尝试恰好一次 BREAKTHROUGH 分区 nextDouble()。
/// 循环条件与 Kotlin 一致（shouldContinue && realm>0，无迭代上限——
/// 大境界递减天然有限：≤9 境界 × 9 层）
/// @param ownerRow 弟子在 DiscipleStore 的行号（装备/功法桶寻址键——
///        工作副本四槽/已学功法 id 与该行列一致，桶视图步骤内只读）
inline void performBreakthrough(
        Disciple& live, GameState& state, std::size_t ownerRow,
        const std::map<int32_t, std::size_t>& idx,
        const std::map<int32_t, int32_t>& committedElderComprehension,
        const EquipmentInstanceBuckets& eqBuckets,
        const ManualInstanceBuckets& mnBuckets,
        rng::RngManager& rng) {
    Disciple d = live;   // Kotlin: copy(cultivation = tables.cultivations[...]) 同步
    bool shouldContinue = true;
    int32_t successCount = 0;
    int32_t failCount = 0;

    while (shouldContinue && d.realm > 0) {
        const double maxCult = computeMaxCultivation(
            d.realm, d.realmLayer, d.cultivation);
        if (d.cultivation < maxCult) break;
        if (!isFullHpMp(d, ownerRow, state.gameData, eqBuckets, mnBuckets)) {
            break;
        }

        const int32_t pillTargetRealm =
            (d.realmLayer >= gamecore::disciple::realmConfig(d.realm).maxLayers)
                ? d.realm - 1
                : d.realm;
        // 突破丹概率加成参与本次尝试（Kotlin pillBonus → getBreakthroughChance）
        const auto pill = attemptAutoPill(d, pillTargetRealm, state);

        const auto input = breakthroughChanceInput(
            d, state, idx, committedElderComprehension, pill.first);
        const double chance = stats::calculateBreakthroughChance(d, input);
        const bool success =
            rng.getRng(rng::RngPartition::kBreakthrough).nextDouble() < chance;
        if (success) {
            ++successCount;
            applyBreakthroughSuccess(d);
            // 引导计数：累计突破次数（GuideCounterKeys.BREAKTHROUGHS）
            int64_t& counter = state.gameData.guideCounters["breakthroughs"];
            counter = counter + 1;
        } else {
            ++failCount;
            applyBreakthroughFailure(d);
            shouldContinue = false;
        }
    }

    // Checkpoint：Kotlin 在此处直写 live 列（读写回前的 cultivations 列值），
    // C++ 保持同序同源
    live.cultivationCheckpoint = live.cultivation;
    live.cultivationCheckpointGameMonth =
        state.gameData.gameYear * 12 + state.gameData.gameMonth;

    // 广告/玉符加成只对下一次尝试有效：发生突破尝试即清除
    if (successCount + failCount > 0) {
        d.statusData.erase("adBreakthroughBonus");
    }
    // 突破计数直写 live 列（writeBreakthroughCounts 语义）
    if (successCount > 0) live.breakthroughCount += successCount;
    if (failCount > 0) live.breakthroughFailCount += failCount;

    updateCompletionEstimate(d, state, idx, ownerRow, mnBuckets);

    // 精准字段写回（processRealtimeBreakthroughs 的 forEach 写回字段面）
    live.cultivation = d.cultivation;
    live.realm = d.realm;
    live.realmLayer = d.realmLayer;
    live.lifespan = d.lifespan;
    live.currentHp = d.currentHp;
    live.currentMp = d.currentMp;
    live.storageBagItems = d.storageBagItems;
    live.statusData = d.statusData;
    live.cultivationCompletionMonth = d.cultivationCompletionMonth;
    live.cultivationCompletionPhase = d.cultivationCompletionPhase;
}

/// 步骤 7 主流程：候选筛选 → 按 ids 顺序逐弟子执行突破 → 亲属赠送钩子 +
/// 大境界日志（候选级前后比对，与 Kotlin processRealtimeBreakthroughs 同构）。
/// DiscipleStore SoA 版：候选为行索引，逐候选物化工作副本 →
/// performBreakthrough（原地改 live）→ upsert 原位写回；RNG 抽取序 = 行序。
/// @param committedElderComprehension 结算入口长老悟性 committed 视图
///        （committedElderComprehensionOf——R1.2 去物化：长老悟性等 store
///        已提交视图读取源；全量 D 弟子物化快照退役）
inline void processBreakthroughs(
        GameState& state, rng::RngManager& rng,
        const std::map<int32_t, std::size_t>& idx,
        const std::map<int32_t, int32_t>& committedElderComprehension,
        const std::set<int32_t>& secretIds, ecs::World& world) {
    DiscipleStore& ds = state.disciples;
    // 步骤 7 入口：装备/功法**桶视图**一次构建（R1.3 第二步：原 id 键全量
    // 深拷贝映射退役——构建 O(E) 指针入桶，零实例拷贝）。本步骤在核心批次
    // （步骤 1-5 含孕养提交 applyEquipmentUpdates）之后执行——桶已含当旬
    // 最新 nurtureLevel（"当旬最新"语义对齐 Kotlin battleWritebackMaxHpMp
    // 的当前 state 现场口径）；步骤 7 全程只读两表（attemptAutoPill 只写
    // pills/储物袋），候选筛选与逐候选突破循环共享同一桶视图，
    // 消除逐实体 D 次重建。
    const auto eqBuckets = inst_bucket::makeInstanceBuckets(
        ds, state.equipmentInstances);
    const auto mnBuckets = inst_bucket::makeInstanceBuckets(
        ds, state.manualInstances);
    // 1. 列级直读筛选候选（存活 + 非秘境 + realm>0 + 修为满 + HP/MP 满）。
    //    迭代域：syncDiscipleEntities 校验/恢复
    //    不变量后按 View<DiscipleRef> 行序筛选（候选序 == 行序 == Kotlin ids
    //    序，RNG 抽取序逐位不变）。
    std::vector<std::size_t> candidates;
    {
        ecs::syncDiscipleEntities(world, ds.size());
        ecs::View<ecs::DiscipleRef> view(world.registry());
        view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
            const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
            if (ds.isAlive[row] == 0) return;
            const auto id = ds.numericIdAt(row);
            if (!id.has_value() || secretIds.count(*id)) return;
            if (ds.realms[row] <= 0) return;
            const double maxCult = computeMaxCultivation(
                ds.realms[row], ds.realmLayers[row], ds.cultivations[row]);
            if (ds.cultivations[row] < maxCult) return;
            if (!isFullHpMp(ds, row, state.gameData, eqBuckets, mnBuckets)) {
                return;
            }
            candidates.push_back(row);
        });
    }
    if (candidates.empty()) return;

    // 记录候选突破前境界/层数（亲属赠送与日志的比对基准）
    std::map<std::size_t, std::pair<int32_t, int32_t>> before;
    for (std::size_t row : candidates) {
        before[row] = {ds.realms[row], ds.realmLayers[row]};
    }

    // 2. 仅候选弟子按需处理（顺序 == ids 顺序 → RNG 抽取序列逐位一致）
    for (std::size_t row : candidates) {
        Disciple live = ds.materialize(row);   // 工作副本（语义 == 旧向量元素）
        performBreakthrough(live, state, row, idx, committedElderComprehension,
                            eqBuckets, mnBuckets, rng);
        ds.upsertDisciple(live);               // 原位写回（保序）
    }

    // 3. 亲属智能赠送（社交系统，SYSTEM RNG——relative_gift.h；
    //    触发条件 = 境界或层数变化，先于日志） +
    //    4. 大境界变化日志：仅大境界（realm）变化记录一条消息栏事件
    auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    for (std::size_t row : candidates) {
        const auto& oldVals = before[row];
        const bool realmChanged = oldVals.first != ds.realms[row];
        const bool layerChanged = oldVals.second != ds.realmLayers[row];
        if (realmChanged || layerChanged) {
            const auto id = ds.numericIdAt(row);
            if (id.has_value()) {
                relative_gift::processGiftsForBreakthrough(state, *id,
                                                           rngSystem);
            }
        }
        if (!realmChanged) continue;
        Disciple after = ds.materialize(row);
        recordGameEvent(state, after,
                        gamecore::disciple::realmConfig(after.realm).name);
    }
}

}  // namespace detail

// ── 主入口：每旬结算（注册进 SettlementEngine::onPhaseSettle / onCoreSettle） ──

/// 核心每旬批次（步骤 1-5：恢复/修炼累积/熟练度/孕养 + 批量提交）。
/// 零 RNG 消耗——AUTHORITATIVE 模式下由 onCoreSettle 注册执行；
/// 完整版 [runPhaseSettlement] 复用本函数后追加丹药/突破两步。
/// DiscipleStore SoA 版：合并遍历全链路列直读直写，零对象物化。
/// 迭代域：经 syncDiscipleEntities 校验/恢复
/// "View 迭代序 == Store 行序"不变量后按 View<DiscipleRef> 行序迭代
/// （与并行版 runPhaseCoreBatchParallel 同域；语义逐位不变）。
inline void runPhaseCoreBatch(state::GameState& state, ecs::World& world) {
    const auto eqBuckets = detail::inst_bucket::makeInstanceBuckets(
        state.disciples, state.equipmentInstances);
    const auto mnBuckets = detail::inst_bucket::makeInstanceBuckets(
        state.disciples, state.manualInstances);
    const auto idx = detail::indexById(state.disciples);
    const auto secretIds = detail::secretRealmMemberIds(state.gameData);
    // 藏经阁弟子预构建集合
    std::set<std::string> libraryIds;
    for (const auto& slot : state.gameData.librarySlots) {
        if (!slot.discipleId.empty()) libraryIds.insert(slot.discipleId);
    }

    detail::PendingProficiencies pendingProficiencies;
    std::map<std::string, state::EquipmentInstance> pendingEquipmentUpdates;

    state::DiscipleStore& ds = state.disciples;
    ecs::syncDiscipleEntities(world, ds.size());
    // 合并遍历：恢复 + 修炼累积 + 熟练度暂存 + 孕养暂存（语义保留）
    ecs::View<ecs::DiscipleRef> view(world.registry());
    view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
        const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
        if (ds.isAlive[row] == 0) return;
        const auto id = ds.numericIdAt(row);
        if (!id.has_value() || secretIds.count(*id)) return;
        // 1) HP/MP 恢复（列直读直写）
        detail::recoverHpMp(ds, row, state.gameData, eqBuckets, mnBuckets);
        // 2) 修炼累积（≥1e8 视为异常满值跳过）
        if (ds.cultivations[row] < kCultivationSkipThreshold) {
            detail::accumulateCultivation(state, row, idx, mnBuckets);
        }
        // 3) 功法熟练度增长（批量模式）
        detail::processManualProficiency(
            state.gameData, ds, row, mnBuckets,
            libraryIds.count(ds.ids[row]) > 0, pendingProficiencies);
        // 4) 装备孕养增长（批量模式）
        detail::processEquipmentNurture(ds, row, eqBuckets,
                                        pendingEquipmentUpdates);
    });

    // 5a) 单次提交熟练度；5b) 单次重建装备列表
    detail::commitManualProficiencies(state.gameData, pendingProficiencies);
    detail::applyEquipmentUpdates(state, pendingEquipmentUpdates);
}

// ════════════════════════════════════════════════════════════════════
// 每旬核心批次并行化（ECS JobSystem——消除 5000 弟子每旬 O(D) 单线程热点）
//
// 结构：与串行 [runPhaseCoreBatch] 完全同构，唯一区别 = 逐弟子循环改为
// JobSystem::parallelForIndexed 分块并行，每块累积**局部** pending 提供，
// 分块完成后按块序号**确定性合并**（块间无共享写；每弟子 id / 每装备 id
// 在块间唯一，合并序无关——见下"确定性"）。
//
// 迭代域：不经裸行号 0..N，而是经
// syncDiscipleEntities 从 View<DiscipleRef> 校验/恢复"迭代序 == Store 行序"
// 不变量（弟子增删漂移即重建），逐弟子行地址取自 DiscipleRef 组件。
// 分块仍按行区间切（position == 行号，校验已保证）。
//
// ## 确定性论证（清零 RNG 红线）
//   1. 本批次全程 **零 RNG**——不消耗任何分区（步骤 1-5 纯计算），
//      因此并行不产生 RNG 抽取序问题。
//   2. 逐弟子写入仅限**本人行**（currentHp/currentMp/cultivation 列），
//      跨线程不同 index 写不同元素 → 无数据竞争。
//   3. 读取面 = 本人行列 + 其他弟子**静态列**（realm/teaching/talent/
//      isAlive/spiritRoot/parentId/masterId/type）+ gameData（elderSlots/
//      residenceSlots/placedBuildings/manualProficiencies）——这些列在本批次
//      循环内**从不被写**（仅 currentHp/currentMp/cultivation 被写），并发读安全。
//      DiscipleRef 存储（行地址解析）在批次内只读，并发 find 安全。
//   4. 熟练度/装备孕养暂存按各自 id 键控，且同一 id 只被一个弟子（行）
//      处理 → 各块局部暂存合并到同一标准 map 时键唯一，最终结果与串行
//      逐位一致、与合并顺序无关。
//
// ## 边界（与串行一致）
//   - 每弟子同 id 行唯一（DiscipleStore.idToRow 保证）。
//   - 装备实例 id 在本批次内唯一（每装备归一个弟子的四槽）。
//   - 结果与 [runPhaseCoreBatch] 逐步一致——由守护测试
//     PhaseSettlementTest.CoreBatchParallelMatchesSerial 强制校验。
// ════════════════════════════════════════════════════════════════════

/// 并行每旬核心批次（JobSystem 分块；经 World/View 行序映射驱动；
/// 与串行版逐位一致——守护测试校验）
inline void runPhaseCoreBatchParallel(state::GameState& state,
                                      ecs::JobSystem& jobs,
                                      ecs::World& world) {
    const std::size_t rowCount = state.disciples.size();
    if (rowCount == 0) return;
    // 桶视图构建于 parallelFor 之前；块内 find 只读（并发读安全），
    // 实例向量批次内不被写（孕养走 pending 暂存，提交在并行段之后）
    const auto eqBuckets = detail::inst_bucket::makeInstanceBuckets(
        state.disciples, state.equipmentInstances);
    const auto mnBuckets = detail::inst_bucket::makeInstanceBuckets(
        state.disciples, state.manualInstances);
    const auto idx = detail::indexById(state.disciples);
    const auto secretIds = detail::secretRealmMemberIds(state.gameData);
    // 藏经阁弟子预构建集合
    std::set<std::string> libraryIds;
    for (const auto& slot : state.gameData.librarySlots) {
        if (!slot.discipleId.empty()) libraryIds.insert(slot.discipleId);
    }
    // E1 保序桥接：校验"View 序 == 行序"（漂移即重建）；position == 行号，
    // 行地址从 DiscipleRef 组件取（桥接规范第 3 条）。
    const auto entityByRow = ecs::syncDiscipleEntities(world, rowCount);
    const auto& refStorage = world.registry().storage<ecs::DiscipleRef>();

    struct ChunkResult {
        detail::PendingProficiencies pending;
        std::map<std::string, state::EquipmentInstance> equipmentUpdates;
    };
    const std::size_t nChunks = std::min(jobs.threadCount(), rowCount);
    std::vector<ChunkResult> chunkResults(nChunks);

    jobs.parallelForIndexed(rowCount, [&](std::size_t begin, std::size_t end,
                                          std::size_t c) {
        ChunkResult local;
        state::DiscipleStore& ds = state.disciples;
        for (std::size_t pos = begin; pos < end; ++pos) {
            const std::size_t row = refStorage.find(entityByRow[pos])->row;
            if (ds.isAlive[row] == 0) continue;
            const auto id = ds.numericIdAt(row);
            if (!id.has_value() || secretIds.count(*id)) continue;
            // 1) HP/MP 恢复（本人行列直写）
            detail::recoverHpMp(ds, row, state.gameData, eqBuckets, mnBuckets);
            // 2) 修炼累积（≥1e8 视为异常满值跳过）
            if (ds.cultivations[row] < kCultivationSkipThreshold) {
                detail::accumulateCultivation(state, row, idx, mnBuckets);
            }
            // 3) 功法熟练度（局部暂存）
            detail::processManualProficiency(
                state.gameData, ds, row, mnBuckets,
                libraryIds.count(ds.ids[row]) > 0, local.pending);
            // 4) 装备孕养（局部暂存）
            detail::processEquipmentNurture(ds, row, eqBuckets,
                                            local.equipmentUpdates);
        }
        chunkResults[c] = std::move(local);
    });

    // 确定性合并（按块序号；键在块间唯一 → 直接覆盖赋值）
    detail::PendingProficiencies pendingProficiencies;
    std::map<std::string, state::EquipmentInstance> pendingEquipmentUpdates;
    for (ChunkResult& cr : chunkResults) {
        for (auto& kv : cr.pending) {
            pendingProficiencies[kv.first] = std::move(kv.second);
        }
        for (auto& kv : cr.equipmentUpdates) {
            pendingEquipmentUpdates[kv.first] = std::move(kv.second);
        }
    }
    detail::commitManualProficiencies(state.gameData, pendingProficiencies);
    detail::applyEquipmentUpdates(state, pendingEquipmentUpdates);
}

/// 执行一旬弟子结算（时间推进由 SettlementEngine 负责，本函数只做结算）。
/// @param state 完整游戏状态（就地修改）
/// @param rng   RNG 分区管理器（仅 BREAKTHROUGH 分区被消耗）
/// @param world ECS 实体域（E2：步骤 0/1-5/6/7 迭代域经 sync 行序映射）
inline void runPhaseSettlement(state::GameState& state,
                               rng::RngManager& rng, ecs::World& world) {
    // 结算入口长老悟性 committed 视图：突破概率的长老悟性等字段对齐 Kotlin
    // stateStore.disciples.value（事务前已提交视图）——同旬长老属性变更
    // 不影响本旬突破判定（与 Kotlin 逐位一致）。
    // R1.2 去物化：快照唯一消费点 = 步骤 7 长老悟性读取（内/外门长老位
    // ≤2 名弟子），不再逐行物化全量 D 弟子（每旬 D 次深拷贝 → 0 次），
    // 直接按 SoA 列捕获入口时点值；**数值 id 键控**（Kotlin allDisciples
    // 按 id 关联）——S2 起偷盗后叛逃可在突破前移除行（行号漂移），行索引
    // 快照会错位关联，id 键控不受影响。
    const auto committedElderComprehension =
        detail::committedElderComprehensionOf(state);
    const auto secretIds = detail::secretRealmMemberIds(state.gameData);

    // 0) 自动装备/学习（仓库 + 储物袋候选 + 更高品阶替换）。
    //    在结算入口快照之后执行——对齐 Kotlin execute 的
    //    processAutoFromWarehouseRealtime 首步：本旬自动装配不影响突破概率
    //    的长老快照判定（committed 视图语义）。
    detail::processAutoFromWarehouse(state, world);

    runPhaseCoreBatch(state, world);

    // 6) 自动丹药补服（含写回后偷盗判定钩子）
    detail::processAutoPills(state, secretIds, rng, world);

    // 7) 突破检测（唯一 RNG 消耗点：BREAKTHROUGH 分区）+ 亲属赠送（SYSTEM）
    const auto idx = detail::indexById(state.disciples);
    detail::processBreakthroughs(state, rng, idx, committedElderComprehension,
                                 secretIds, world);
}

/// AUTHORITATIVE core 模式每旬结算（生产每旬不再需要 Kotlin
/// executeResidual 回写）。
/// 步骤序与完整版 [runPhaseSettlement] **完全一致**（0 自动装备 → 1-5 核心
/// 批次 → 6 丹药(+偷盗钩子) → 7 突破(+亲属赠送)），唯一差异 = 核心批次由
/// 调用方注入的并行实现（ECS PhaseCoreBatchSystem + JobSystem）驱动——
/// 串行/并行逐位一致由 PhaseSettlementTest.CoreBatchParallelMatchesSerial
/// 守护。保留核心批次外的步骤为串行：丹药/突破携带 RNG 与跨弟子状态依赖
/// （亲属赠送读全店关系列、偷盗链有月度/年度计数门控），不可分块并行。
/// @param runCoreBatch 核心批次实现（game_core.cpp 注入 ecsScheduler_.runAll）
inline void runPhaseSettlementCore(state::GameState& state,
                                   rng::RngManager& rng,
                                   const std::function<void()>& runCoreBatch,
                                   ecs::World& world) {
    // 入口长老悟性 committed 视图（突破长老悟性 committed 视图；同
    // runPhaseSettlement——R1.2 去物化：全量 D 弟子物化 → 长老位 ≤2 名
    // SoA 列直算，零物化）
    const auto committedElderComprehension =
        detail::committedElderComprehensionOf(state);
    const auto secretIds = detail::secretRealmMemberIds(state.gameData);

    // 0) 自动装备/学习（先于核心批次——对齐 Kotlin execute 首步序）
    detail::processAutoFromWarehouse(state, world);

    runCoreBatch();

    // 6) 自动丹药补服（含写回后偷盗判定钩子）
    detail::processAutoPills(state, secretIds, rng, world);

    // 7) 突破检测（BREAKTHROUGH 分区）+ 亲属赠送（SYSTEM 分区）
    const auto idx = detail::indexById(state.disciples);
    detail::processBreakthroughs(state, rng, idx, committedElderComprehension,
                                 secretIds, world);
}

// ── ECS System 适配器：把每旬核心批次表达为可调度系统 ──
//
// 把 AUTHORITATIVE core 模式的每旬热路径（runPhaseCoreBatch）包成一个
// ecs::ISystem，经 SystemScheduler 驱动（接入调度框架），内部用 JobSystem
// 并行化逐弟子批次。DiscipleStore 仍是权威，ECS System 是调度/并行化外壳。
//
// run 真用 World——迭代域经 View<DiscipleRef> 行序
// 映射（syncDiscipleEntities 校验/恢复不变量），系统签名不再是弃用形参；
// World 实体集惰性装配（首旬建齐，弟子增删漂移即重建），生产由 game_core
// 持久 ecsWorld_ 承载，稳态零重建。
class PhaseCoreBatchSystem : public ecs::ISystem {
public:
    PhaseCoreBatchSystem(state::GameState& state, ecs::JobSystem& jobs)
        : state_(&state), jobs_(&jobs) {}
    const char* name() const override { return "PhaseCoreBatch"; }
    int priority() const override { return 0; }
    // 系统内已用 JobSystem 并行化（页内并行）——系统级不再并行 → false
    bool isParallelizable() const override { return false; }
    void run(ecs::World& world) override {
        runPhaseCoreBatchParallel(*state_, *jobs_, world);
    }
private:
    state::GameState* state_;
    ecs::JobSystem* jobs_;
};

}  // namespace gamecore::system
