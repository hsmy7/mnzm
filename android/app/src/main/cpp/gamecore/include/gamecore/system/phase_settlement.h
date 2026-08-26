#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/breakthrough.h"
#include "gamecore/system/cultivation.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/disciple_stats.h"
#include "gamecore/system/pill_system.h"
#include "gamecore/system/settlement_detail.h"

// ============================================================
// 每旬弟子结算（计划 v2 阶段 2 / T2.1）
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
// 已知范围边界（详见 .superpowers/sdd/t2-1-report.md）：
//   - Kotlin 循环首步 processAutoFromWarehouseRealtime（自动装备/学习）不在
//     本批结算范围（简报 §1 结算范围六项之外），开关全关时为纯早退；
//   - 丹药写回中的偷盗判定钩子（道德<阈值 → 执法堂 SYSTEM RNG）属执法系统，
//     突破后的亲属赠送（SYSTEM RNG）属社交系统，均未随本批下沉；
//     对拍场景以"无亲属关系 + 低道德丹药缺席"保证双端语义一致。
// ============================================================
namespace gamecore::system {

/// 每旬修炼累积跳过门限（Kotlin checkBreakthroughsAndPills 内联字面量 1e8：
/// 凡界最大修为约 2e7，≥1e8 视为异常满值直接跳过）
constexpr double kCultivationSkipThreshold = 1e8;

/// HP/MP 恢复乘区总和（RecoveryZones：base 1.0 + 建筑/丹药/境界乘区均为预留 0）
constexpr double kRecoveryZoneTotal = 1.0;

/// 每旬熟练度基础增长参数（ManualProficiencySystem：6/s × (1+藏经阁0.5) × 2000ms）
constexpr double kBaseProficiencyRate = 6.0;
constexpr double kLibraryProficiencyBonusRate = 0.5;
constexpr int32_t kMaxProficiency = 30000;

/// 每旬装备孕养经验（EquipmentNurtureSystem.NURTURE_GAIN_PER_PHASE =
/// 5.0 × MS_PER_PHASE_1X / 1000 = 10.0）
constexpr double kNurtureGainPerPhase = 10.0;

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
//     isBlankString 已抽取至 settlement_detail.h（T2.2 起与月变钩子共享单一定义），
//     经下方别名以原名使用
namespace settle_util = gamecore::system::settle_util;
using settle_util::containsString;
using settle_util::indexById;
using settle_util::isBlankString;
using settle_util::kotlinCharLength;
using settle_util::spiritRootCount;
using settle_util::toIntOrNull;

/// 装备实例 id 映射（associateBy：同 id 保留最后一个）
inline std::map<std::string, EquipmentInstance> equipmentMapOf(
        const std::vector<EquipmentInstance>& list) {
    std::map<std::string, EquipmentInstance> m;
    for (const auto& e : list) m[e.id] = e;
    return m;
}

/// 功法实例 id 映射
inline std::map<std::string, ManualInstance> manualMapOf(
        const std::vector<ManualInstance>& list) {
    std::map<std::string, ManualInstance> m;
    for (const auto& e : list) m[e.id] = e;
    return m;
}

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

/// 含血炼口径最终 maxHp/maxMp（battleWritebackMaxHpMp 数学等价：
/// getFinalStats 的 maxHp/maxMp 与 getMaxHpMpColumn 共用同一基础公式）
inline void finalMaxHpMp(const Disciple& d, const GameData& gd,
                         const std::map<std::string, EquipmentInstance>& eqMap,
                         const std::map<std::string, ManualInstance>& mnMap,
                         int32_t& outMaxHp, int32_t& outMaxMp) {
    stats::getMaxHpMp(d, findBloodRefinementPct(gd, d.id), eqMap, mnMap,
                      gd.manualProficiencies, outMaxHp, outMaxMp);
}

/// 含血炼口径最终 maxHp/maxMp（DiscipleStore SoA 版，阶段 3 热路径用）
inline void finalMaxHpMp(const DiscipleStore& ds, std::size_t row,
                         const GameData& gd,
                         const std::map<std::string, EquipmentInstance>& eqMap,
                         const std::map<std::string, ManualInstance>& mnMap,
                         int32_t& outMaxHp, int32_t& outMaxMp) {
    stats::getMaxHpMp(ds, row, findBloodRefinementPct(gd, ds.ids[row]), eqMap,
                      mnMap, gd.manualProficiencies, outMaxHp, outMaxMp);
}

/// HP/MP 是否均已满（isDiscipleFullHpMp；负值视为满）。
/// 映射重建时机对齐 Kotlin battleWritebackMaxHpMp——每次从**当前 state**现场
/// 重建装备/功法映射（孕养升级当旬的候选判定即依赖最新 nurtureLevel）；
/// 与恢复步骤的入口快照映射（recoverHpMpSingleColumn 共享映射）刻意区分。
inline bool isFullHpMp(const Disciple& d, const GameState& state) {
    const GameData& gd = state.gameData;
    const auto eqMap = equipmentMapOf(state.equipmentInstances);
    const auto mnMap = manualMapOf(state.manualInstances);
    int32_t maxHp = 0, maxMp = 0;
    stats::getMaxHpMp(d, findBloodRefinementPct(gd, d.id), eqMap, mnMap,
                      gd.manualProficiencies, maxHp, maxMp);
    const int32_t hp = d.currentHp < 0 ? maxHp : d.currentHp;
    const int32_t mp = d.currentMp < 0 ? maxMp : d.currentMp;
    return hp >= maxHp && mp >= maxMp;
}

/// HP/MP 是否均已满（DiscipleStore SoA 版，阶段 3 候选筛选用）
inline bool isFullHpMp(const DiscipleStore& ds, std::size_t row,
                       const GameState& state) {
    const GameData& gd = state.gameData;
    const auto eqMap = equipmentMapOf(state.equipmentInstances);
    const auto mnMap = manualMapOf(state.manualInstances);
    int32_t maxHp = 0, maxMp = 0;
    stats::getMaxHpMp(ds, row, findBloodRefinementPct(gd, ds.ids[row]), eqMap,
                      mnMap, gd.manualProficiencies, maxHp, maxMp);
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

inline void recoverHpMp(Disciple& d, const GameData& gd,
                        const std::map<std::string, EquipmentInstance>& eqMap,
                        const std::map<std::string, ManualInstance>& mnMap) {
    const int32_t curHp = d.currentHp;
    const int32_t curMp = d.currentMp;
    if (curHp < 0 && curMp < 0) return;   // 特殊状态（负值=满）整体跳过

    int32_t maxHp = 0, maxMp = 0;
    finalMaxHpMp(d, gd, eqMap, mnMap, maxHp, maxMp);

    // 满血提前退出（负值视为满，语义与对象版一致）
    const int32_t effHp = curHp < 0 ? maxHp : curHp;
    const int32_t effMp = curMp < 0 ? maxMp : curMp;
    if (effHp >= maxHp && effMp >= maxMp) return;

    const double multiplier = 1.0;   // phasesToSettle = 1
    if (curHp >= 0) d.currentHp = std::min(curHp + recoveryAmount(maxHp, multiplier), maxHp);
    if (curMp >= 0) d.currentMp = std::min(curMp + recoveryAmount(maxMp, multiplier), maxMp);
}

/// HP/MP 恢复（DiscipleStore SoA 版，阶段 3 热路径用——列直读直写）
inline void recoverHpMp(DiscipleStore& ds, std::size_t row, const GameData& gd,
                        const std::map<std::string, EquipmentInstance>& eqMap,
                        const std::map<std::string, ManualInstance>& mnMap) {
    const int32_t curHp = ds.currentHps[row];
    const int32_t curMp = ds.currentMps[row];
    if (curHp < 0 && curMp < 0) return;   // 特殊状态（负值=满）整体跳过

    int32_t maxHp = 0, maxMp = 0;
    finalMaxHpMp(ds, row, gd, eqMap, mnMap, maxHp, maxMp);

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
/// 天赋注册表未迁移 → flat 恒 0，填表后经 stats::talentEffectsFor 生效）
inline int32_t effectiveTeaching(const Disciple& elder) {
    const auto effects = stats::talentEffectsFor(elder.talentIds);
    return elder.teaching +
           static_cast<int32_t>(stats::effectValue(effects, "teachingFlat"));
}

/// 有效教学值（DiscipleStore 行版，阶段 3 列访问）
inline int32_t effectiveTeaching(const DiscipleStore& ds, std::size_t row) {
    const auto effects = stats::talentEffectsFor(ds.talentIds[row]);
    return ds.teachings[row] +
           static_cast<int32_t>(stats::effectValue(effects, "teachingFlat"));
}

/// 住所建筑修炼系数（calculateBuildingCultivationBonus 列直读版：
/// 首匹配槽位 → 匹配建筑 displayName 查表；无槽/无建筑 → 1.0）
inline double residenceBuildingBonus(const GameData& gd,
                                     const std::string& discipleIdStr) {
    const auto idOpt = toIntOrNull(discipleIdStr);
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

/// 讲道长老/师兄加成（calculatePreachingBonusesColumn；inner=false 外门 / true 青云内门）。
/// DiscipleStore SoA 版（阶段 3）：长老/师兄经 id→行查列直读。
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
            // 职务加成 PositionBonus（天赋/词条注册表未迁移 → 恒 0，见文件头注释）
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
/// checkpoint 只在速率变化点同步）。DiscipleStore SoA 版（阶段 3 热路径）：
/// 全链路列直读直写，零对象物化。
inline void accumulateCultivation(
        GameState& state, std::size_t row,
        const std::map<int32_t, std::size_t>& idx,
        const std::map<std::string, ManualInstance>& mnMap) {
    DiscipleStore& ds = state.disciples;
    const GameData& gd = state.gameData;
    const int32_t realm = ds.realms[row];
    const int32_t realmLayer = ds.realmLayers[row];
    const double cultivation = ds.cultivations[row];
    const double maxCultivation = computeMaxCultivation(
        realm, realmLayer, cultivation);
    if (cultivation >= maxCultivation) return;

    stats::CultivationRateInput extra;
    extra.buildingBonus = residenceBuildingBonus(gd, ds.ids[row]);

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
        ds, row, gd, mnMap, gd.manualProficiencies, extra);
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
/// S10 修复语义保留——pending 显式条目优先于旧值，防同周期双倍增长）
inline void processManualProficiency(
        const GameData& gd, const Disciple& d,
        const std::map<std::string, ManualInstance>& mnMap,
        bool inLibrary, PendingProficiencies& pending) {
    if (d.manualIds.empty()) return;
    const double libraryBonus =
        inLibrary ? kLibraryProficiencyBonusRate : 0.0;
    const double profGain = kBaseProficiencyRate * (1.0 + libraryBonus) *
                            kMsPerPhase1x / 1000.0;
    if (profGain <= 0.0) return;

    std::vector<ManualProficiencyData> profList;
    const auto pit = pending.find(d.id);
    if (pit != pending.end()) {
        if (pit->second.has_value()) profList = *pit->second;
    } else {
        const auto git = gd.manualProficiencies.find(d.id);
        if (git != gd.manualProficiencies.end()) profList = git->second;
    }

    bool changed = false;
    for (const std::string& manualId : d.manualIds) {
        const auto mit = mnMap.find(manualId);
        if (mit == mnMap.end()) continue;
        if (accumulateProficiencyForManual(profList, manualId, mit->second,
                                           profGain)) {
            changed = true;
        }
    }
    // 清理已替换/遗忘功法的残留条目（防僵尸条目累积）
    const auto newEnd = std::remove_if(profList.begin(), profList.end(),
        [&](const ManualProficiencyData& p) {
            return !containsString(d.manualIds, p.manualId);
        });
    if (newEnd != profList.end()) {
        profList.erase(newEnd, profList.end());
        changed = true;
    }

    if (changed) {
        pending[d.id] = profList.empty()
            ? std::nullopt
            : std::optional<std::vector<ManualProficiencyData>>(profList);
    }
}

/// 步骤 3：单弟子熟练度增长（DiscipleStore 行版，阶段 3 列直读 manualIds/id）
inline void processManualProficiency(
        const GameData& gd, const DiscipleStore& ds, std::size_t row,
        const std::map<std::string, ManualInstance>& mnMap,
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
        const auto mit = mnMap.find(manualId);
        if (mit == mnMap.end()) continue;
        if (accumulateProficiencyForManual(profList, manualId, mit->second,
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

/// 孕养等级上限表（rarity 1..6 → getMaxNurtureLevel；单一定义供两处共用）
inline constexpr int32_t kNurtureMaxLevels[] = {5, 9, 13, 17, 21, 25};

inline int32_t nurtureMaxLevel(int32_t rarity) {
    return (rarity >= 1 && rarity <= 6) ? kNurtureMaxLevels[rarity - 1] : 5;
}

/// 孕养升级所需经验（getExpRequiredForLevelUp；满级返回 +inf）
inline double expRequiredForLevelUp(int32_t level, int32_t rarity) {
    const int32_t maxLevel = nurtureMaxLevel(rarity);
    if (level >= maxLevel) return HUGE_VAL;
    const double baseExp = 100.0 * (level + 1);
    double rarityMultiplier;
    switch (rarity) {
        case 2: rarityMultiplier = 1.5; break;
        case 3: rarityMultiplier = 2.0; break;
        case 4: rarityMultiplier = 3.0; break;
        case 5: rarityMultiplier = 4.5; break;
        case 6: rarityMultiplier = 6.0; break;
        default: rarityMultiplier = 1.0; break;
    }
    return baseExp * rarityMultiplier;
}

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

/// 步骤 4：单弟子四槽孕养增长（批量模式：从共享快照映射读原值，
/// 更新累积到 updates——与 Kotlin settleNurtureInPlace 读写面一致）
inline void processEquipmentNurture(
        const Disciple& d,
        const std::map<std::string, EquipmentInstance>& eqMap,
        std::map<std::string, EquipmentInstance>& updates) {
    for (const std::string& eqId :
         {d.weaponId, d.armorId, d.bootsId, d.accessoryId}) {
        if (eqId.empty()) continue;
        const auto sit = eqMap.find(eqId);
        if (sit == eqMap.end()) continue;
        EquipmentInstance working = sit->second;
        if (applyNurtureExp(working, kNurtureGainPerPhase)) {
            updates[eqId] = working;
        }
    }
}

/// 步骤 4：单弟子四槽孕养增长（DiscipleStore 行版，阶段 3 列直读四槽 id）
inline void processEquipmentNurture(
        const DiscipleStore& ds, std::size_t row,
        const std::map<std::string, EquipmentInstance>& eqMap,
        std::map<std::string, EquipmentInstance>& updates) {
    for (const std::string& eqId :
         {ds.weaponIds[row], ds.armorIds[row], ds.bootsIds[row], ds.accessoryIds[row]}) {
        if (eqId.empty()) continue;
        const auto sit = eqMap.find(eqId);
        if (sit == eqMap.end()) continue;
        EquipmentInstance working = sit->second;
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
            default:
                return true;
        }
    }
    return false;
}

/// 自动服用主流程（DisciplePillManager.processAutoUsePills；返回是否实际服用。
/// 排序：规则优先级降序 + 稀有度降序的稳定排序；突破丹被排除）
inline bool autoUsePills(Disciple& d) {
    std::vector<const StorageBagItem*> pillItems;
    for (const StorageBagItem& item : d.storageBagItems) {
        if (item.itemType != "pill") continue;
        if (!item.effect.has_value()) continue;
        if (pill::classify(*item.effect) == pill::PillRule::kBreakthrough) continue;
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
    // 2026-08 修复语义：修炼速度加成统一收敛于 pillEffects 体系，
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
/// DiscipleStore SoA 版（阶段 3）：逐行物化工作副本 → 服用 → upsert 原位写回
///（保序；upsertDisciple 对既有 id 原位覆盖）。仅实际服用时写回。
inline void processAutoPills(GameState& state,
                             const std::set<int32_t>& secretIds) {
    DiscipleStore& ds = state.disciples;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] == 0) continue;
        const auto id = toIntOrNull(ds.ids[row]);
        if (!id.has_value() || secretIds.count(*id)) continue;

        Disciple d = ds.materialize(row);
        if (!hasUsablePills(d)) continue;
        Disciple working = d;
        if (!autoUsePills(working)) continue;   // result.disciple == disciple → 跳过
        writePillResult(d, working, state.gameData);
        ds.upsertDisciple(d);                   // 原位写回（保序）
    }
}

// ── 步骤 7：突破检测（processBreakthroughs / performBreakthrough） ───

/// 突破概率输入组装（tryBreakthrough 的长老悟性/职务/广告/丧亲/师徒提取）。
/// @param committed 结算入口时的弟子快照副本——对齐 Kotlin tryBreakthrough:282
///        经 stateStore.disciples.value（事务前已提交视图）读取长老悟性；
///        存活/境界条件判断仍用 live 状态（与 Kotlin tables 参数一致）
inline stats::BreakthroughChanceInput breakthroughChanceInput(
        const Disciple& d, const GameState& state,
        const std::map<int32_t, std::size_t>& idx,
        const std::vector<Disciple>& committed,
        double pillBonus) {
    stats::BreakthroughChanceInput in;
    in.pillBonus = pillBonus;   // 突破丹概率加成（attemptAutoPill 返回值）
    const GameData& gd = state.gameData;
    const auto& slots = gd.elderSlots;

    // 内/外门长老悟性（仅对应弟子类型生效）：存活与境界门槛按 live 列判定，
    // 悟性数值/职务加成取**结算入口已提交视图**的长老对象（getBaseStats()
    // .comprehension 口径）；职务加成 PositionBonus 属天赋/词条注册表
    // （未迁移 → 恒 0，填表后接入）
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
        if (it->second < committed.size()) {
            return {stats::baseComprehension(committed[it->second]), 0.0};
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
/// P-9 追加序号 max+1 溢出回 1；takeLast(MAX_EVENT_LOGS) 裁剪语义保留；
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
///         取"首个最大值"（Kotlin maxByOrNull 语义，严格大于才替换）
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
        state.pills.erase(state.pills.begin() +
                          static_cast<std::ptrdiff_t>(bestIndex));
        return {bestChance, false};
    }

    // 储物袋兜底（命中后**整条移除**并返回已修改弟子——对齐 Kotlin
    // `storageBagItems - bestPill` 的 List.minus(element) 全量相等移除语义，
    // 非 decreaseItemQuantity(quantity-1)；itemId 袋内唯一为生产不变量）
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
        std::vector<StorageBagItem> remaining;
        remaining.reserve(d.storageBagItems.size());
        for (StorageBagItem& item : d.storageBagItems) {
            if (item.itemId == bagBest->itemId) continue;   // 整条移除
            remaining.push_back(std::move(item));
        }
        d.storageBagItems = std::move(remaining);
        return {bagBestChance, true};
    }
    return {0.0, false};
}

/// 突破后修炼完成时间预估（updateCompletionEstimate；速率复用列直读乘区——
/// 与 Kotlin 对象版共享同一公式源；manualInstances 结算全程不被写入，
/// 现场构建映射与 Kotlin store 缓存视图等价）
inline void updateCompletionEstimate(Disciple& d, GameState& state,
                                     const std::map<int32_t, std::size_t>& idx) {
    const GameData& gd = state.gameData;
    const int32_t currentMonth = gd.gameYear * 12 + gd.gameMonth;
    const auto mnMap = manualMapOf(state.manualInstances);

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
        d, gd, mnMap, gd.manualProficiencies, extra);
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
inline void performBreakthrough(
        Disciple& live, GameState& state,
        const std::map<int32_t, std::size_t>& idx,
        const std::vector<Disciple>& committed,
        rng::RngManager& rng) {
    Disciple d = live;   // Kotlin: copy(cultivation = tables.cultivations[...]) 同步
    bool shouldContinue = true;
    int32_t successCount = 0;
    int32_t failCount = 0;

    while (shouldContinue && d.realm > 0) {
        const double maxCult = computeMaxCultivation(
            d.realm, d.realmLayer, d.cultivation);
        if (d.cultivation < maxCult) break;
        if (!isFullHpMp(d, state)) break;

        const int32_t pillTargetRealm =
            (d.realmLayer >= gamecore::disciple::realmConfig(d.realm).maxLayers)
                ? d.realm - 1
                : d.realm;
        // 突破丹概率加成参与本次尝试（Kotlin pillBonus → getBreakthroughChance）
        const auto pill = attemptAutoPill(d, pillTargetRealm, state);

        const auto input = breakthroughChanceInput(
            d, state, idx, committed, pill.first);
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

    updateCompletionEstimate(d, state, idx);

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
/// DiscipleStore SoA 版（阶段 3）：候选为行索引，逐候选物化工作副本 →
/// performBreakthrough（原地改 live）→ upsert 原位写回；RNG 抽取序 = 行序。
/// @param committed 结算入口时的弟子快照（长老悟性等 store 已提交视图读取源）
inline void processBreakthroughs(
        GameState& state, rng::RngManager& rng,
        const std::map<int32_t, std::size_t>& idx,
        const std::vector<Disciple>& committed,
        const std::set<int32_t>& secretIds) {
    DiscipleStore& ds = state.disciples;
    // 1. 列级直读筛选候选（存活 + 非秘境 + realm>0 + 修为满 + HP/MP 满；
    //    满血判定现场重建映射——对齐 battleWritebackMaxHpMp 语义）
    std::vector<std::size_t> candidates;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] == 0) continue;
        const auto id = toIntOrNull(ds.ids[row]);
        if (!id.has_value() || secretIds.count(*id)) continue;
        if (ds.realms[row] <= 0) continue;
        const double maxCult = computeMaxCultivation(
            ds.realms[row], ds.realmLayers[row], ds.cultivations[row]);
        if (ds.cultivations[row] < maxCult) continue;
        if (!isFullHpMp(ds, row, state)) continue;
        candidates.push_back(row);
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
        performBreakthrough(live, state, idx, committed, rng);
        ds.upsertDisciple(live);               // 原位写回（保序）
    }

    // 3. 亲属智能赠送（社交系统，SYSTEM RNG——未随本批下沉，见文件头注释）
    // 4. 大境界变化日志：仅大境界（realm）变化记录一条消息栏事件
    for (std::size_t row : candidates) {
        const auto& oldVals = before[row];
        if (oldVals.first == ds.realms[row]) continue;
        Disciple after = ds.materialize(row);
        recordGameEvent(state, after,
                        gamecore::disciple::realmConfig(after.realm).name);
    }
}

}  // namespace detail

// ── 主入口：每旬结算（注册进 SettlementEngine::onPhaseSettle / onCoreSettle） ──

/// 核心每旬批次（步骤 1-5：恢复/修炼累积/熟练度/孕养 + 批量提交）。
/// 零 RNG 消耗——T2.4 AUTHORITATIVE 过渡模式下由 onCoreSettle 注册执行；
/// 完整版 [runPhaseSettlement] 复用本函数后追加丹药/突破两步。
/// DiscipleStore SoA 版（阶段 3）：合并遍历全链路列直读直写，零对象物化。
inline void runPhaseCoreBatch(state::GameState& state) {
    const auto eqMap = detail::equipmentMapOf(state.equipmentInstances);
    const auto mnMap = detail::manualMapOf(state.manualInstances);
    const auto idx = detail::indexById(state.disciples);
    const auto secretIds = detail::secretRealmMemberIds(state.gameData);
    // P-1 藏经阁弟子预构建集合
    std::set<std::string> libraryIds;
    for (const auto& slot : state.gameData.librarySlots) {
        if (!slot.discipleId.empty()) libraryIds.insert(slot.discipleId);
    }

    detail::PendingProficiencies pendingProficiencies;
    std::map<std::string, state::EquipmentInstance> pendingEquipmentUpdates;

    state::DiscipleStore& ds = state.disciples;
    // 合并遍历：恢复 + 修炼累积 + 熟练度暂存 + 孕养暂存（P0.1 优化语义保留）
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] == 0) continue;
        const auto id = detail::toIntOrNull(ds.ids[row]);
        if (!id.has_value() || secretIds.count(*id)) continue;
        // 1) HP/MP 恢复（列直读直写）
        detail::recoverHpMp(ds, row, state.gameData, eqMap, mnMap);
        // 2) 修炼累积（≥1e8 视为异常满值跳过）
        if (ds.cultivations[row] < kCultivationSkipThreshold) {
            detail::accumulateCultivation(state, row, idx, mnMap);
        }
        // 3) 功法熟练度增长（批量模式）
        detail::processManualProficiency(
            state.gameData, ds, row, mnMap,
            libraryIds.count(ds.ids[row]) > 0, pendingProficiencies);
        // 4) 装备孕养增长（批量模式）
        detail::processEquipmentNurture(ds, row, eqMap, pendingEquipmentUpdates);
    }

    // 5a) 单次提交熟练度；5b) 单次重建装备列表
    detail::commitManualProficiencies(state.gameData, pendingProficiencies);
    detail::applyEquipmentUpdates(state, pendingEquipmentUpdates);
}

/// 执行一旬弟子结算（时间推进由 SettlementEngine 负责，本函数只做结算）。
/// @param state 完整游戏状态（就地修改）
/// @param rng   RNG 分区管理器（仅 BREAKTHROUGH 分区被消耗）
inline void runPhaseSettlement(state::GameState& state,
                               rng::RngManager& rng) {
    // 结算入口弟子快照：突破概率的长老悟性等字段对齐 Kotlin
    // stateStore.disciples.value（事务前已提交视图）——同旬长老属性变更
    // 不影响本旬突破判定（与 Kotlin 逐位一致）。
    // DiscipleStore 版：逐行物化快照列表（语义 == 旧整向量拷贝）
    std::vector<state::Disciple> committedDisciples;
    committedDisciples.reserve(state.disciples.size());
    for (std::size_t i = 0; i < state.disciples.size(); ++i) {
        committedDisciples.push_back(state.disciples.materialize(i));
    }
    const auto secretIds = detail::secretRealmMemberIds(state.gameData);

    runPhaseCoreBatch(state);

    // 6) 自动丹药补服
    detail::processAutoPills(state, secretIds);

    // 7) 突破检测（唯一 RNG 消耗点：BREAKTHROUGH 分区）
    const auto idx = detail::indexById(state.disciples);
    detail::processBreakthroughs(state, rng, idx, committedDisciples, secretIds);
}

}  // namespace gamecore::system
