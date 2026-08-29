#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/blood_refinement.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/exploration.h"
#include "gamecore/system/government.h"
#include "gamecore/system/disciple_stats.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/settlement_detail.h"
#include "gamecore/system/slot_cleanup.h"
#include "gamecore/system/spirit_field.h"

// ============================================================
// 月变结算钩子（计划 v2 阶段 2 / T2.2）
//
// 等价移植 Kotlin GameEngineCore.processMonthYearChange 的 monthChanged 分支
// 单事务编排（Kotlin 侧由 MonthSettlementExecutor 提取同构），注册进
// SettlementEngine::onMonthChange 钩子。
//
// 八步事务序（语义权威 = 各被调方法源码）：
//   1. 政策月度灵石扣除        ← government.h::processPolicyCosts（原语接线）
//   2. 政策月度忠诚/道德效果     ← CultivationSettlement.processPolicyMonthlyEffects
//   3. AI 兽袭目标预计算        ← 未下沉（见下方范围边界）
//   4. systemManager.onMonthlyEvent 七系统扇出（@SystemPriority 升序）：
//      Alchemy(210) → Forge(211) → Planting(214) → ChildBirth(235) →
//      Exploration(240) → Partner(240，稳定排序居后) → Mail(960)
//   5. 血炼完成检测            ← blood_refinement 原语 + 本文件结算段
//   6. 月度自动排班 + 住所忠诚   ← processResidenceLoyalty（排班未下沉）
//   7. 丹药持续效果月度衰减      ← HpMpRecoveryService.applyMonthlyDurationDecay
//   8. processMonthlyEventsOnState 十六子事件（已下沉六件 + 其余未下沉）
//
// RNG 消耗点核对表（分区 / 触发条件 / 抽取次数——对拍命门，逐点核对自源码）：
//   - EXPLORATION：妖兽移动 moveBeasts，每活跃妖兽 2 次 nextDouble（角度+距离）
//   - SYSTEM：灵田收获种子 roll nextInt(5)，每收获地块 1 次
//   - SYSTEM：伴侣配对 nextDouble，每通过过滤的 (男,女) 组合 1 次
//   - SYSTEM：偷盗兜底（批 10-3）每判定候选：概率 1 次 + 捕获 1 次 +
//     仓库选取 nextInt(仓库数)（仓库非空时）+ 金额波动 1 次 +
//     物品 nextInt(池大小) 0..N 次 + 偷盗后叛逃 1 次（无条件抽取）
//   - BREAKTHROUGH / BATTLE / 其他：本钩子零消耗（旬结算是 BREAKTHROUGH 唯一入口）
//   已知未下沉扇出的抽取点（场景规避 + 登记后续批次，见 t2-2-report.md）：
//   AI 兽袭 EXPLORATION（S3）、关卡刷新生成（S4-Exploration）、生产完成
//   SYSTEM（S4-Alchemy 同步段）、生育/招募/购买/附赋/商人等 SYSTEM
//   （S8 子事件余量；执法堂偷盗 S8 月度兜底已随批 10-3 下沉——S2 教化之道
//   道德增量后的反应式偷盗判定钩子仍未下沉）。
//
// 已知范围边界（详见 .superpowers/sdd/t2-2-report.md 覆盖矩阵）：
//   - S3 precomputeTargets：依赖 aiSectDisciples/aiSectBeastDirectTargets 等
//     AI 宗门域字段，不在 C++ 快照协议——属阶段 4 AI 宗门批次；
//     对拍场景 worldMapSects 为空 → 双端零抽取零写入
//   - S4 关卡刷新生成（LevelGenerator）/巡视楼战斗/妖兽攻击检测：战斗与
//     生成域未迁移；场景无玩家宗门 → 刷新不触发、检测/巡视纯早退
//   - S4 生育（DiscipleFactory 弟子生成批次）：场景 childBirthMonth 全空 → 双端零效果
//   - S4 炼丹/锻造自动排班与完成结算（Room 仓储/物品数据库域）：
//     场景无到期槽位且自动政策全关；ForgeSystem 本为异步 launch（事务内零效果）
//   - S4 邮件（MailService.processMonthlyMails 为异步网络拉取，事务内零状态效果）
//   - S6 自动排班 processAutoAssign（11 槽占用扫描跨多未迁移域）：
//     场景自动政策全关 → 双端纯早退；住所忠诚已实现
//   - S8 子事件下沉 recruitCountThisMonth 归零 / 灵矿月产 / gameOverCheck /
//     scoutExpiry（批 10-1）/ 月度叛逃检测（批 10-2）/ 月度偷盗兜底
//     （批 10-3）六件，其余十件（招募/任务/洞天/AI 兽战/12 月自动购买/
//     购买/附赋/任务刷新/秘境×2）场景规避 + 登记对应批次
//   - S2 教化之道道德增量后的偷盗判定钩子（SYSTEM）未随本批下沉——
//     与 T2.1 D2 同源（执法堂批次）；场景道德 ≥ 阈值规避
// ============================================================
namespace gamecore::system {

/// 伴侣配对基础概率（Kotlin PartnerSystem.PAIRING_PROBABILITY）
constexpr double kPairingProbability = 0.006;
/// 配对资格年龄下限（Kotlin PartnerSystem 过滤 age >= 18）
constexpr int32_t kPairingMinAge = 18;
/// 丹药月度衰减旬数（每月 3 旬；HpMpRecoveryService.applyMonthlyDurationDecay）
constexpr int32_t kMonthlyDecayPhases = 3;
// 忠诚/道德上限与月度增量常量单一定义于 government.h
//（kMaxLoyalty/kMoralEducationMax/kMoralEducationPerMonth 及各政策忠诚增量）

namespace detail {

using gamecore::state::Disciple;
using gamecore::state::DiscipleStore;
using gamecore::state::GameData;
using gamecore::state::GameState;

// 共享小工具（toIntOrNull/containsString/indexById/spiritRootCount/
// kotlinCharLength/isBlankString）单一定义于 settlement_detail.h
namespace settle_util = gamecore::system::settle_util;
using settle_util::containsString;
using settle_util::indexById;
using settle_util::isBlankString;
using settle_util::kotlinCharLength;
using settle_util::spiritRootCount;
using settle_util::toIntOrNull;

/// 消息栏事件记录（MutableGameState.recordGameEvent 完整守卫对齐：
/// summary/eventType blank 拒绝 + 四字段 Kotlin String.length 口径长度上限 +
/// P-9 序号 max+1 溢出回 1 + takeLast(MAX_EVENT_LOGS) 裁剪）
inline void recordGameEvent(GameState& state, const std::string& category,
                            const std::string& eventType,
                            const std::string& summary,
                            const std::string& relatedEntityId = "",
                            const std::string& relatedEntityName = "") {
    if (isBlankString(summary) || isBlankString(eventType)) return;
    if (kotlinCharLength(summary) > 200) return;
    if (kotlinCharLength(eventType) > 50) return;
    if (kotlinCharLength(relatedEntityId) > 50) return;
    if (kotlinCharLength(relatedEntityName) > 50) return;

    state::GameEventRecord event;
    event.year = state.gameData.gameYear;
    event.month = state.gameData.gameMonth;
    event.phase = state.gameData.gamePhase;
    event.category = category;
    event.eventType = eventType;
    event.summary = summary;
    event.relatedEntityId = relatedEntityId;
    event.relatedEntityName = relatedEntityName;
    int64_t maxSeq = 0;
    for (const auto& r : state.gameData.gameEventRecords) {
        maxSeq = std::max(maxSeq, r.sequenceId);
    }
    event.sequenceId = (maxSeq >= INT64_MAX - 1) ? 1 : maxSeq + 1;
    auto& records = state.gameData.gameEventRecords;
    records.push_back(event);
    constexpr std::size_t kMaxEventLogs = 200;   // GameConfig.Logs.MAX_EVENT_LOGS
    if (records.size() > kMaxEventLogs) {
        records.erase(records.begin(),
                      records.end() - static_cast<std::ptrdiff_t>(kMaxEventLogs));
    }
}

// ── 步骤 2：政策月度忠诚/道德效果 ──────────────────────────────────
// （CultivationSettlement.processPolicyMonthlyEffects：单次遍历合并净变化；
//   偷盗判定钩子未下沉——见文件头范围边界）

inline void processPolicyMonthlyEffects(GameState& state) {
    const GameData& gd = state.gameData;
    const auto& policies = gd.sectPolicies;

    // 忠诚净变化（各政策月度增减汇总，与 Kotlin 合并口径一致）
    int32_t loyaltyDelta = 0;
    if (policies.benevolentGovernance) loyaltyDelta += kBenevolentLoyaltyPerMonth;
    if (policies.relaxedMgmt) loyaltyDelta += kRelaxedMgmtLoyaltyPerMonth;
    if (policies.strictTraining) loyaltyDelta += kStrictTrainingLoyaltyPerMonth;
    if (policies.enhancedSecurity) loyaltyDelta += kEnhancedSecurityLoyaltyPerMonth;
    if (policies.curfew) loyaltyDelta += kCurfewLoyaltyPerMonth;

    for (std::size_t row = 0; row < state.disciples.size(); ++row) {
        DiscipleStore& ds = state.disciples;
        if (ds.isAlive[row] == 0) continue;
        // 忠诚：delta != 0 时 clamp 写回（getOrDefault 缺省 50 —— C++ 字段恒存在）
        if (loyaltyDelta != 0) {
            ds.loyalties[row] = std::max(
                0, std::min(kMaxLoyalty, ds.loyalties[row] + loyaltyDelta));
        }
        // 道德（教化之道）：仅当前低于上限时 +1 并 clamp；
        // 新道德仍低于偷盗阈值的判定钩子属执法堂批次（未下沉，见文件头）
        if (policies.moralEducation && ds.moralities[row] < kMoralEducationMax) {
            ds.moralities[row] = std::max(
                0, std::min(kMoralEducationMax,
                            ds.moralities[row] + kMoralEducationPerMonth));
        }
    }
}

// ── 步骤 4c：灵田收获（spirit_field.h 原语接线） ───────────────────
// Kotlin ProductionProcessor.processSpiritFieldHarvest 已在批次 4c 移植；
// 溢出邮件收集器结果在 C++ 侧丢弃（邮件实体不在快照协议——溢出转邮件的
// 可见面为零；仓库容量充足的对拍场景双端均无溢出）

inline void processSpiritFieldHarvestStep(GameState& state, rng::RngManager& rng) {
    OverflowMailCollector overflowMail;
    processSpiritFieldHarvest(state, rng, overflowMail);
    // overflowMail 内容即 Kotlin sendOverflowMail 的邮件草稿——C++ 无邮件协议，
    // 显式弃用（与"邮件域阶段 4 迁移"边界一致）
}

// ── 步骤 4f：伴侣配对（PartnerSystem.processPartnerMatching 完整移植） ──
// RNG 契约：每对通过过滤的 (male, female) 组合恰好一次 SYSTEM nextDouble；
// 遍历序 = eligibleMales 外层 × eligibleFemales 内层（assembleAll 快照序 ==
// C++ disciples 向量序）；pairedFemaleIds 跳过不改写快照。

inline bool hasBloodRelation(const Disciple& a, const Disciple& b) {
    const auto& aP1 = a.parentId1;
    const auto& aP2 = a.parentId2;
    const auto& bP1 = b.parentId1;
    const auto& bP2 = b.parentId2;
    return a.id == bP1 || a.id == bP2 ||
           b.id == aP1 || b.id == aP2 ||
           (!aP1.empty() && aP1 == bP1) ||
           (!aP1.empty() && aP1 == bP2) ||
           (!aP2.empty() && aP2 == bP1) ||
           (!aP2.empty() && aP2 == bP2);
}

inline void processPartnerMatching(GameState& state, rng::RngManager& rng,
                                   const std::map<int32_t, std::size_t>& idx) {
    // assembleAll 快照等价：循环期间只写 live 列，资格判定全部读入口快照副本
    //（DiscipleStore 版：逐行物化快照列表，语义 == 旧整向量拷贝）
    std::vector<Disciple> snapshot;
    snapshot.reserve(state.disciples.size());
    for (std::size_t i = 0; i < state.disciples.size(); ++i) {
        snapshot.push_back(state.disciples.materialize(i));
    }

    // 失效提议清理：pendingMarriageProposals 不在 C++ 快照协议（同意模式
    // 提案列表属 UI 域），本步为空操作——对拍场景以 consentRequired=false
    // （自动配对模式）保证双端一致（见文件头范围边界）

    const auto& bannedRootCounts = state.gameData.daoCompanionBannedRootCounts;
    const auto isBannedRoot = [&](const Disciple& d) {
        return std::find(bannedRootCounts.begin(), bannedRootCounts.end(),
                         spiritRootCount(d)) != bannedRootCounts.end();
    };

    std::vector<const Disciple*> eligibleMales;
    std::vector<const Disciple*> eligibleFemales;
    for (const auto& d : snapshot) {
        if (!d.isAlive || d.age < kPairingMinAge) continue;
        if (!d.partnerId.empty() || isBannedRoot(d)) continue;
        if (d.gender == "male") eligibleMales.push_back(&d);
        else if (d.gender == "female") eligibleFemales.push_back(&d);
    }

    if (eligibleMales.empty() || eligibleFemales.empty()) return;

    std::set<std::string> pairedFemaleIds;
    for (const Disciple* male : eligibleMales) {
        for (const Disciple* female : eligibleFemales) {
            if (pairedFemaleIds.count(female->id)) continue;
            if (hasBloodRelation(*male, *female)) continue;

            if (rng.getRng(rng::RngPartition::kSystem).nextDouble() <
                kPairingProbability) {
                // 同意模式提案分支未下沉（提案列表不在协议）——场景固定关闭；
                // 此处直接走自动配对分支（consentRequired=false 的 Kotlin 行为）
                const auto maleId = toIntOrNull(male->id);
                const auto femaleId = toIntOrNull(female->id);
                if (!maleId.has_value() || !femaleId.has_value()) continue;
                const auto mit = idx.find(*maleId);
                const auto fit = idx.find(*femaleId);
                if (mit == idx.end() || fit == idx.end()) continue;
                state.disciples.partnerIds[mit->second] = female->id;
                state.disciples.partnerIds[fit->second] = male->id;
                pairedFemaleIds.insert(female->id);
                recordGameEvent(state, "SECT", "marriage",
                                "弟子" + male->name + "与弟子" + female->name +
                                    "结为道侣",
                                male->id, male->name);
            }
        }
    }
}

// ── 步骤 5：血炼完成检测（processBloodRefinementCompletions） ──────

/// 单条到期血炼结算（settleSingleRefinement）
inline void settleSingleRefinement(GameState& state, const std::string& buildingId,
                                   const state::BloodRefinementProgress& progress,
                                   const std::map<int32_t, std::size_t>& idx) {
    (void)buildingId;
    const auto dId = toIntOrNull(progress.discipleId);
    if (!dId.has_value() || idx.find(*dId) == idx.end()) return;
    if (progress.selectedStat.empty()) return;
    // 防御：血炼期间弟子可能因其他系统死亡（isAlive[dId] == 0 直接返回）
    DiscipleStore& ds = state.disciples;
    const std::size_t row = idx.at(*dId);
    if (ds.isAlive[row] == 0) return;

    // NaN 无法被 coerceAtLeast 拦下——先 isFinite 归零再取非负
    const double safeBonusPct =
        std::isfinite(progress.bonusPercent)
            ? std::max(progress.bonusPercent, 0.0)
            : 0.0;

    auto& totals = state.gameData.bloodRefinementPctTotals;
    const auto existing = totals.find(progress.discipleId);
    state::BloodRefinementPctTotal updatedTotal =
        addPctToTotal(existing != totals.end()
                          ? existing->second
                          : state::BloodRefinementPctTotal{},
                      progress.selectedStat, safeBonusPct);
    updatedTotal.discipleId = progress.discipleId;
    totals[progress.discipleId] = updatedTotal;

    // 材料记录追加（bloodRefinements[id] += materialId）
    auto& refinements = state.gameData.bloodRefinements[progress.discipleId];
    refinements.push_back(progress.materialId);

    // 仅清除 statusData["buildingId"]（status 保持 REFINING——Kotlin 怪癖保留）
    ds.statusData[row].erase("buildingId");

    recordGameEvent(state, "SECT", "blood_refinement",
                    progress.discipleName + "的血练已完成！属性「" +
                        bloodRefinementStatDisplayName(progress.selectedStat) +
                        "」获得提升。",
                    "", progress.discipleName);
}

inline void processBloodRefinementCompletions(
        GameState& state, const std::map<int32_t, std::size_t>& idx) {
    auto& active = state.gameData.activeBloodRefinements;
    if (active.empty()) return;
    std::map<std::string, state::BloodRefinementProgress> remaining;
    for (const auto& [buildingId, progress] : active) {
        const int32_t elapsed = calculateElapsedMonths(
            progress.startYear, progress.startMonth,
            state.gameData.gameYear, state.gameData.gameMonth);
        if (elapsed < progress.durationMonths) {
            remaining.emplace(buildingId, progress);
        } else {
            settleSingleRefinement(state, buildingId, progress, idx);
        }
    }
    if (remaining.size() != active.size()) {
        active = std::move(remaining);
    }
}

// ── 步骤 6b：住所忠诚度（processResidenceLoyalty） ─────────────────

inline void processResidenceLoyalty(GameState& state) {
    std::set<std::string> residentIds;
    for (const auto& slot : state.gameData.residenceSlots) {
        // Kotlin isActive 为计算属性 == discipleId.isNotEmpty()
        if (!slot.discipleId.empty()) residentIds.insert(slot.discipleId);
    }
    for (std::size_t row = 0; row < state.disciples.size(); ++row) {
        DiscipleStore& ds = state.disciples;
        // Kotlin: id.toString() in residentIds && loyalties[id] < max
        if (residentIds.count(ds.ids[row]) && ds.loyalties[row] < kMaxLoyalty) {
            ds.loyalties[row] = std::min(ds.loyalties[row] + 1, kMaxLoyalty);
        }
    }
}

// ── 步骤 7：丹药持续效果月度衰减（applyMonthlyDurationDecayAll） ───

/// 单弟子月衰减（HpMpRecoveryService.applyMonthlyDurationDecay，
/// focusedPhaseCount=0 → monthlyDecay=3）
inline void applyMonthlyDurationDecay(Disciple& d) {
    if (d.pillEffectDuration <= 0) return;
    const int32_t newDuration = d.pillEffectDuration - kMonthlyDecayPhases;
    if (newDuration <= 0) {
        d.pillHpBonus = 0;
        d.pillMpBonus = 0;
        d.pillPhysicalAttackBonus = 0;
        d.pillMagicAttackBonus = 0;
        d.pillPhysicalDefenseBonus = 0;
        d.pillMagicDefenseBonus = 0;
        d.pillSpeedBonus = 0;
        d.pillCritRateBonus = 0.0;
        d.pillCritEffectBonus = 0.0;
        d.pillCultivationSpeedBonus = 0.0;
        d.pillSkillExpSpeedBonus = 0.0;
        d.pillNurtureSpeedBonus = 0.0;
        d.activePillCategory.clear();
        d.activePillTypes.clear();
        d.pillEffectDuration = 0;
    } else {
        d.pillEffectDuration = newDuration;
    }
}

/// 单弟子月衰减（DiscipleStore 行版，阶段 3 列直写；语义与 Disciple& 版一致）
inline void applyMonthlyDurationDecay(DiscipleStore& ds, std::size_t row) {
    if (ds.pillEffectDurations[row] <= 0) return;
    const int32_t newDuration = ds.pillEffectDurations[row] - kMonthlyDecayPhases;
    if (newDuration <= 0) {
        ds.pillHpBonuses[row] = 0;
        ds.pillMpBonuses[row] = 0;
        ds.pillPhysicalAttackBonuses[row] = 0;
        ds.pillMagicAttackBonuses[row] = 0;
        ds.pillPhysicalDefenseBonuses[row] = 0;
        ds.pillMagicDefenseBonuses[row] = 0;
        ds.pillSpeedBonuses[row] = 0;
        ds.pillCritRateBonuses[row] = 0.0;
        ds.pillCritEffectBonuses[row] = 0.0;
        ds.pillCultivationSpeedBonuses[row] = 0.0;
        ds.pillSkillExpSpeedBonuses[row] = 0.0;
        ds.pillNurtureSpeedBonuses[row] = 0.0;
        ds.activePillCategories[row].clear();
        ds.activePillTypes[row].clear();
        ds.pillEffectDurations[row] = 0;
    } else {
        ds.pillEffectDurations[row] = newDuration;
    }
}

inline void applyMonthlyDurationDecayAll(GameState& state) {
    DiscipleStore& ds = state.disciples;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] == 0) continue;
        applyMonthlyDurationDecay(ds, row);
    }
}

// ── 步骤 8c：灵矿月度产出结算（processSpiritMineProductionMonthly） ─

/// 执事道德基准（GameConfig.PolicyConfig.ELDER_SKILL_BASELINE）
constexpr int32_t kElderSkillBaselineConst = 80;

/// 构建灵矿乘区（buildSpiritMineZones：矿工采矿列直读 + 执事基础道德 +
/// 政策倍率；天赋/词条注册表为空表占位 → 基础道德 == morality 字段值，
/// 与 T2.1 D7 口径一致，填表后经 stats:: 聚合接入）
inline SpiritMineZones buildMonthSpiritMineZones(const GameState& state,
                                                 const std::map<int32_t, std::size_t>& idx) {
    const GameData& gd = state.gameData;
    const auto& slots = gd.spiritMineSlots;
    const int32_t minerCount = static_cast<int32_t>(std::count_if(
        slots.begin(), slots.end(),
        [](const state::SpiritMineSlot& s) { return !s.discipleId.empty(); }));

    double miningBonus = 0.0;
    const DiscipleStore& ds = state.disciples;
    for (const auto& slot : slots) {
        if (slot.discipleId.empty()) continue;
        const auto id = detail::toIntOrNull(slot.discipleId);
        if (!id.has_value()) continue;
        const auto it = idx.find(*id);
        if (it == idx.end()) continue;
        if (ds.isAlive[it->second] == 0) continue;
        if (ds.minings[it->second] > kSpiritMineMiningThreshold) {
            miningBonus += (ds.minings[it->second] - kSpiritMineMiningThreshold) *
                           kSpiritMineMiningBonusRate;
        }
    }
    const double avgMiningBonus =
        (minerCount > 0) ? miningBonus / minerCount : 0.0;

    const double boostMultiplier =
        gd.sectPolicies.spiritMineBoost ? kSpiritMineBoostMultiplier : 1.0;

    double deaconBonus = 0.0;
    for (const auto& slot : gd.elderSlots.spiritMineDeaconDisciples) {
        if (slot.discipleId.empty()) continue;
        const auto id = detail::toIntOrNull(slot.discipleId);
        if (!id.has_value()) continue;
        const auto it = idx.find(*id);
        if (it == idx.end()) continue;
        if (ds.isAlive[it->second] == 0) continue;
        const int32_t diff = std::max(
            ds.moralities[it->second] - kElderSkillBaselineConst, 0);
        deaconBonus += diff * kDeaconMoralityBonusRate;
    }

    SpiritMineZones zones;
    zones.minerCount = minerCount;
    zones.avgMiningSkillBonus = avgMiningBonus;
    zones.deaconMoralityBonus = deaconBonus;
    zones.policyBoost = multiplierToZone(boostMultiplier);
    return zones;
}

/// 矿工忠诚衰减（applyMinerLoyaltyDecay：连续挖矿满 3 月 -1 忠诚并归零计数；
/// 无效/空槽位计数归零；弟子查无此人仅按 ids.contains 判定——死亡也衰减）
inline void applyMinerLoyaltyDecay(GameState& state,
                                   const std::map<int32_t, std::size_t>& idx) {
    for (auto& slot : state.gameData.spiritMineSlots) {
        if (slot.discipleId.empty()) {
            slot.consecutiveMiningMonths = 0;
            continue;
        }
        const auto id = detail::toIntOrNull(slot.discipleId);
        if (!id.has_value() || idx.find(*id) == idx.end()) {
            slot.consecutiveMiningMonths = 0;
            continue;
        }
        const int32_t newMonths = slot.consecutiveMiningMonths + 1;
        if (newMonths >= 3) {
            DiscipleStore& ds = state.disciples;
            const std::size_t row = idx.at(*id);
            ds.loyalties[row] = std::max(ds.loyalties[row] - 1, 0);
            slot.consecutiveMiningMonths = 0;
        } else {
            slot.consecutiveMiningMonths = newMonths;
        }
    }
}

/// 灵矿月产结算主体：乘区构建 → 差分产出入账（钱包 Mine 来源）→
/// 引导计数 → lastSettledMonth 推进 → 矿工忠诚衰减
inline void processSpiritMineProductionMonthly(
        GameState& state, const std::map<int32_t, std::size_t>& idx) {
    GameData& gd = state.gameData;
    const int32_t currentMonth = toAbsoluteMonth(gd.gameYear, gd.gameMonth);
    const SpiritMineZones zones = buildMonthSpiritMineZones(state, idx);
    const int64_t monthlyRate = calculateSpiritMineMonthly(
        zones, kSpiritMineBaseOutputPerMiner);
    const int32_t lastSettled = gd.spiritMineLastSettledMonth;
    if (currentMonth > lastSettled && monthlyRate > 0) {
        const int64_t totalOutput = monthlyRate * (currentMonth - lastSettled);
        SpiritStoneWallet::add(gd, totalOutput, SpiritStoneGrade::LOW, "Mine");
        int64_t& counter = gd.guideCounters["miningOutput"];
        counter += totalOutput;
    }
    gd.spiritMineLastSettledMonth = currentMonth;
    applyMinerLoyaltyDecay(state, idx);
}

// ── 步骤 8e：游戏结束检查（checkGameOverCondition） ────────────────

inline void checkGameOverCondition(GameState& state) {
    const GameData& gd = state.gameData;
    if (gd.isGameOver) return;
    // 玩家宗门定义：isPlayerSect 的宗门；无玩家宗门 → 不判定
    std::string playerSectId;
    bool hasPlayerSect = false;
    for (const auto& sect : gd.worldMapSects) {
        if (sect.isPlayerSect) {
            playerSectId = sect.id;
            hasPlayerSect = true;
            break;
        }
    }
    if (!hasPlayerSect) return;
    // 玩家仍控制任意宗门：本宗未被占领，或占领了其他宗门
    for (const auto& sect : gd.worldMapSects) {
        const bool controls =
            (sect.isPlayerSect && sect.occupierSectId.empty()) ||
            (sect.occupierSectId == playerSectId && !sect.isPlayerSect);
        if (controls) return;
    }
    state.gameData.isGameOver = true;
}

// ── 步骤 8：processMonthlyEventsOnState 可下沉子集 ────────────────
// Kotlin 十六子事件全序：recruitReset → autoRecruit → theft → lawEnforcement →
// completedMissions → aiSectOperations → gameOverCheck → scoutExpiry →
// aiBeastRemaining → [12月 autoBuy] → spiritMine → disciplePurchase →
// vassalBreakaway → missionRefresh → secretRealmExpiry → secretRealmAiTeams。
// 已下沉六件（recruitReset/spiritMine/gameOverCheck/scoutExpiry 批 10-1/
// lawEnforcementMonthly 批 10-2/theft 月度兜底批 10-3），其余场景规避 +
// 登记批次（文件头范围边界）；相对序与 Kotlin 一致。

/// 子事件 8：侦察信息过期清理（Kotlin CultivationEventDiplomacyOps.
/// applyScoutInfoExpiry 等价移植；零 RNG 纯数据变换）。
///
/// 语义（逐条对齐 Kotlin 源码）：
/// - 过期判定：year > expiryYear || (year == expiryYear && month > expiryMonth)
/// - 无过期 → 纯早退零写入（Lazy 门控等价）
/// - 三段更新（②③均基于"原始 sectDetails"，对齐 Kotlin 读取顺序）：
///   ① 剩余条目 → details[sectId].scoutInfo 刷新（无明细则新建 SectDetail(sectId)）；
///   ② 被移除条目 → 原明细 scoutInfo.sectId 非空者清空为默认值；
///   ③ worldMapSects：scoutInfo 移除且原明细 scoutInfo.sectId 非空 → isKnown=false
inline void applyScoutInfoExpiry(GameState& state, int32_t year, int32_t month) {
    auto& gd = state.gameData;
    const auto expired = [&](const state::SectScoutInfo& info) {
        return year > info.expiryYear ||
               (year == info.expiryYear && month > info.expiryMonth);
    };
    std::map<std::string, state::SectScoutInfo> updated;
    bool hasExpired = false;
    for (const auto& [sectId, info] : gd.scoutInfo) {
        if (expired(info)) {
            hasExpired = true;
            continue;
        }
        updated.emplace(sectId, info);
    }
    if (!hasExpired) return;

    std::map<std::string, state::SectDetail> updatedDetails = gd.sectDetails;
    for (const auto& [sectId, info] : updated) {
        auto it = updatedDetails.find(sectId);
        if (it == updatedDetails.end()) {
            state::SectDetail d;
            d.sectId = sectId;
            d.scoutInfo = info;
            updatedDetails.emplace(sectId, std::move(d));
        } else {
            it->second.scoutInfo = info;
        }
    }
    for (const auto& [sectId, detail] : gd.sectDetails) {
        if (updated.find(sectId) == updated.end() &&
            !detail.scoutInfo.sectId.empty()) {
            updatedDetails[sectId].scoutInfo = state::SectScoutInfo{};
        }
    }
    for (auto& sect : gd.worldMapSects) {
        if (updated.find(sect.id) != updated.end()) continue;
        const auto it = gd.sectDetails.find(sect.id);
        if (it != gd.sectDetails.end() && !it->second.scoutInfo.sectId.empty()) {
            sect.isKnown = false;
        }
    }
    gd.scoutInfo = std::move(updated);
    gd.sectDetails = std::move(updatedDetails);
}

// ── 子事件 4：月度叛逃检测（批 10-2：Kotlin LawEnforcementProcessor.
//    processLawEnforcementMonthly 等价移植）──────────────────────────
//
// 配置常量取 Kotlin GameConfig.LawEnforcementConfig 默认值（config() 为
// 远程配置可空覆盖——C++ 侧暂取默认值，远程配置注入 S-10 同族债务）。
// RNG 契约（对拍命门）：按 at-risk 行序，每名弟子先 SYSTEM 抽 1 次
// nextDouble 与叛逃概率比较（≥ 概率跳过）；判定通过再抽第 2 次与捕获率
// 比较（< 捕获率 → 捕获思过；否则逃脱清理）。捕获/逃脱路径零额外抽取。

// 执法堂配置默认值（GameConfig.LawEnforcementConfig）
constexpr int32_t kLawLoyaltyThreshold = 30;
constexpr double kLawDesertionProbPerPoint = 0.01;
constexpr double kLawDesertionMaxProb = 0.90;
constexpr double kLawBaseCaptureRate = 0.0;
constexpr int32_t kLawIntelligenceBase = 50;
constexpr double kLawElderBonusPerPoint = 0.01;
constexpr int32_t kLawDiscipleIntelligenceStep = 5;
constexpr double kLawDiscipleBonusPerStep = 0.01;
constexpr int32_t kLawReflectionYears = 5;
constexpr int32_t kLawNewDiscipleProtectionMonths = 12;
constexpr int32_t kLawHerdLoyaltyThreshold = 50;
// 政策加成（GameConfig.PolicyConfig）
constexpr double kEnhancedSecurityEffect = 0.20;
constexpr double kRewardPunishEffect = 0.30;

/// 叛逃免疫状态（Kotlin DESERTION_IMMUNE_STATUSES；WAREHOUSE_GARRISON 不免疫）
inline bool isDesertionImmuneStatus(const std::string& status) {
    return status == "ON_MISSION" || status == "REFLECTING" ||
           status == "REFINING" || status == "IN_TEAM" ||
           status == "SECRET_REALM";
}

/// 从众门控：存活弟子平均忠诚（整数除法截断）< 阈值；无存活弟子 → false
inline bool isAverageLoyaltyLowEnough(const state::DiscipleStore& ds) {
    int32_t count = 0;
    int64_t sum = 0;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] != 1) continue;
        ++count;
        sum += ds.loyalties[row];
    }
    if (count == 0) return false;
    return (sum / count) < kLawHerdLoyaltyThreshold;
}

/// 捕获率 = 基础 + 长老智力加成（× 职务乘算因子）+ 执法弟子阶梯加成 + 政策，
/// clamp [0,1]。Kotlin 无存活/境界校验——按 id 命中即计（无则跳过该贡献项）。
inline double calculateCaptureRate(GameState& state,
                                   const std::map<int32_t, std::size_t>& idx) {
    const auto& gd = state.gameData;
    const auto& ds = state.disciples;
    double captureRate = kLawBaseCaptureRate;
    const auto& elderId = gd.elderSlots.lawEnforcementElder;
    if (!elderId.empty()) {
        if (const auto eid = toIntOrNull(elderId)) {
            const auto it = idx.find(*eid);
            if (it != idx.end()) {
                const int32_t intel = stats::baseIntelligence(ds, it->second);
                const int32_t above = intel > kLawIntelligenceBase
                                          ? intel - kLawIntelligenceBase : 0;
                const double posBonus = stats::positionEffectBonus(
                    ds, it->second, "LAW_ENFORCEMENT");
                captureRate += above * kLawElderBonusPerPoint * (1.0 + posBonus);
            }
        }
    }
    for (const auto& slot : gd.elderSlots.lawEnforcementDisciples) {
        if (slot.discipleId.empty()) continue;
        if (const auto did = toIntOrNull(slot.discipleId)) {
            const auto it = idx.find(*did);
            if (it == idx.end()) continue;
            const int32_t intel = stats::baseIntelligence(ds, it->second);
            const int32_t above = intel > kLawIntelligenceBase
                                      ? intel - kLawIntelligenceBase : 0;
            captureRate += (static_cast<double>(above) /
                            kLawDiscipleIntelligenceStep) *
                           kLawDiscipleBonusPerStep;
        }
    }
    if (gd.sectPolicies.enhancedSecurity) captureRate += kEnhancedSecurityEffect;
    if (gd.sectPolicies.rewardPunish) captureRate += kRewardPunishEffect;
    if (captureRate < 0.0) captureRate = 0.0;
    if (captureRate > 1.0) captureRate = 1.0;
    return captureRate;
}

/// 叛逃概率 = (阈值 − 忠诚) × 每点概率，clamp [0, 0.90]
inline double calcDesertionProbability(int32_t loyal) {
    double p = (kLawLoyaltyThreshold - loyal) * kLawDesertionProbPerPoint;
    if (p < 0.0) p = 0.0;
    if (p > kLawDesertionMaxProb) p = kLawDesertionMaxProb;
    return p;
}

/// 捕获思过：remove + 末尾重插（REFLECTING + 思过年限 statusData）+ 引导计数
/// + 事件（Kotlin captureDiscipleForReflection）
inline void captureDiscipleForReflection(GameState& state, int32_t id,
                                         int32_t currentYear,
                                         const std::map<int32_t, std::size_t>& idx) {
    const auto it = idx.find(id);
    if (it == idx.end()) return;   // 已移除 → 跳过（Kotlin assemble null 早退）
    state::Disciple d = state.disciples.materialize(it->second);
    state.disciples.removeById(d.id);
    d.status = "REFLECTING";
    d.statusData["reflectionStartYear"] = std::to_string(currentYear);
    d.statusData["reflectionEndYear"] =
        std::to_string(currentYear + kLawReflectionYears);
    state.disciples.appendDisciple(d);
    state.gameData.guideCounters["discipleImprisoned"] += 1;
    recordGameEvent(state, "SECT", "desertion_caught",
                    d.name + "企图叛逃，被执法堂捕获思过", d.id, d.name);
}

/// 逃脱清理：11 类槽位清空（含住所）→ 移除装备/功法实例与熟练度 → 移除弟子
/// + 年度计数 + 事件（Kotlin desertDiscipleCleanup / processTheftDesertionCleanup
/// 共体；忠诚复核对齐。月度叛逃路径 = "desertion"/"脱离宗门"（批 10-2），
/// 偷盗后叛逃路径 = "theft_desertion"/"偷盗后叛逃"（批 10-3））
inline void desertDiscipleCleanup(GameState& state, int32_t id, int32_t threshold,
                                  const std::map<int32_t, std::size_t>& idx,
                                  const std::string& eventType = "desertion",
                                  const std::string& summarySuffix = "脱离宗门") {
    const auto it = idx.find(id);
    if (it == idx.end()) return;
    const std::size_t row = it->second;
    // D-03：储物袋独立存储后随弟子删除，不收集袋条目 itemId（防误删仓库堆叠）
    if (state.disciples.loyalties[row] >= threshold) return;
    const state::Disciple snapshot = state.disciples.materialize(row);
    std::vector<std::string> desertEquipIds;
    for (const std::string* equipId :
         {&snapshot.weaponId, &snapshot.armorId,
          &snapshot.bootsId, &snapshot.accessoryId}) {
        if (!equipId->empty()) desertEquipIds.push_back(*equipId);
    }

    // 11 类槽位清理（Kotlin clearAllSlotsState → clearAllSlotsDataOnly 纯数据
    // 变换；includeResidence=true；生产 Repository 同步为 Kotlin 侧 Room 域，
    // 不在游戏状态内）
    {
        SlotCleanupInput in;
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
        in.activeMissions = state.gameData.activeMissions;
        const auto out = clearAllSlotsDataOnly(in, snapshot.id, /*includeResidence=*/true);
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
        state.gameData.activeMissions = out.activeMissions;
    }

    // 装备实例移除（叛逃带走装备）
    if (!desertEquipIds.empty()) {
        auto& eq = state.equipmentInstances;
        eq.erase(std::remove_if(eq.begin(), eq.end(),
                                [&](const state::EquipmentInstance& e) {
                                    return std::find(desertEquipIds.begin(),
                                                     desertEquipIds.end(),
                                                     e.id) != desertEquipIds.end();
                                }),
                 eq.end());
    }
    // 功法实例移除
    if (!snapshot.manualIds.empty()) {
        auto& mn = state.manualInstances;
        mn.erase(std::remove_if(mn.begin(), mn.end(),
                                [&](const state::ManualInstance& m) {
                                    return std::find(snapshot.manualIds.begin(),
                                                     snapshot.manualIds.end(),
                                                     m.id) != snapshot.manualIds.end();
                                }),
                 mn.end());
    }
    // 功法熟练度移除（键 = 弟子 id 字符串）
    state.gameData.manualProficiencies.erase(snapshot.id);

    state.disciples.removeById(snapshot.id);
    state.gameData.annualDesertedDisciples += 1;
    recordGameEvent(state, "SECT", eventType, snapshot.name + summarySuffix,
                    snapshot.id, snapshot.name);
}

/// 月度叛逃检测主流程（Kotlin processLawEnforcementMonthly）
inline void processLawEnforcementMonthly(GameState& state,
                                         rng::RngManager& rng) {
    auto& ds = state.disciples;
    if (!isAverageLoyaltyLowEnough(ds)) return;
    const int32_t currentMonthValue =
        state.gameData.gameYear * 12 + state.gameData.gameMonth;
    const double captureRate = calculateCaptureRate(state, indexById(ds));
    auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] != 1) continue;
        if (isDesertionImmuneStatus(ds.statuses[row])) continue;
        if (ds.loyalties[row] >= kLawLoyaltyThreshold) continue;
        if (currentMonthValue - ds.recruitedMonths[row] <
            kLawNewDiscipleProtectionMonths) {
            continue;
        }
        const int32_t id = toIntOrNull(ds.ids[row]).value_or(-1);
        if (id < 0) continue;
        const double prob = calcDesertionProbability(ds.loyalties[row]);
        if (rngSystem.nextDouble() >= prob) continue;
        // 第二次抽取：捕获 vs 逃脱
        if (rngSystem.nextDouble() < captureRate) {
            captureDiscipleForReflection(state, id, state.gameData.gameYear,
                                         indexById(ds));
        } else {
            desertDiscipleCleanup(state, id, kLawLoyaltyThreshold,
                                  indexById(ds));
        }
    }
}

// ── 子事件 3：月度偷盗兜底（批 10-3：Kotlin LawEnforcementProcessor.
//    processTheftIfNeeded → processTheftMonthly → processSingleDiscipleTheft
//    非事务版 → executeFullTheftCheck 全链等价移植）────────────────────
//
// 配置常量取 Kotlin GameConfig.LawEnforcementConfig / PolicyConfig 默认值
// （config() 远程配置可空覆盖——S-10/S-13 同族债务）。
//
// RNG 契约（对拍命门，SYSTEM 分区，每候选按序）：
//   ① 偷盗概率抽取 1 次（< 有效概率 → 继续；≥ → 本候选终止，仅 1 抽）
//   ② 执法堂捕获抽取 1 次（< 捕获率 → 捕获思过，共 2 抽）
//   ③ 仓库选取 nextInt(仓库数) 1 次（仅仓库非空时；守卫失守 → 落入 ④）
//   ④ 偷盗金额随机波动 1 次（固定在 0.8 + 0.4×nextDouble）
//   ⑤ 物品抽取 nextInt(池大小) 0..N 次（池非空才抽；每次移除已选条目）
//   ⑥ 偷盗后叛逃概率抽取 1 次（无条件抽取，与概率值无关）
//
// 读取口径（对拍基线 = FakeGameStateStore 顺序语义）：嵌套 update 立即
// 持久化——theftJudgementsThisMonth 归零/递增、annualTheftCount 递增对
// 后续判定全部实时可见；生产真实 store 的 committed 快照读与此存在口径差，
// 登记 S-14（随月变真相源切换批次消除）。

// 偷盗配置默认值（GameConfig.LawEnforcementConfig；PROB_PER_POINT/MAX_PROB
// 与叛逃同源常量，复用 kLawDesertion*）
constexpr int32_t kLawMoralityThreshold = 30;
constexpr int32_t kLawMaxTheftPerYear = 3;
constexpr int32_t kLawMaxTheftJudgementsPerMonth = 3;
// 政策：宵禁偷盗概率减免（GameConfig.PolicyConfig.CURFEW_EVENT_REDUCTION）
constexpr double kCurfewEventReduction = 0.30;
// 境界基准偷盗量（等比 ×4，下标 = 弟子 realm 1..9）
constexpr int64_t kTheftRealmBaseAmounts[10] = {0, 500, 2'000, 8'000, 32'000,
                                                128'000, 512'000, 2'000'000,
                                                8'000'000, 32'000'000};
constexpr double kTheftSpeedBonusPerPoint = 0.005;
constexpr int32_t kTheftSpeedBase = 50;
constexpr double kTheftIntelligenceBonusPerPoint = 0.003;
constexpr int32_t kTheftIntelligenceBase = 50;
constexpr double kTheftMaxRatioOfTotal = 0.10;
constexpr int64_t kTheftMinAmount = 100;
constexpr int64_t kTheftItemBaseDivisor = 20'000;
constexpr int32_t kTheftItemGuardReduction = 2;
constexpr int32_t kTheftItemUnitSpeedFactor = 3;
constexpr int32_t kTheftItemUnitIntelFactor = 3;

/// 偷盗物品临时记录（Kotlin LawEnforcementProcessor.LootedItemEntry）
struct LootedItemEntry {
    std::string id;
    std::string name;
    std::string type;
    int32_t rarity = 0;
    int32_t count = 0;
};

/// 偷盗尝试有效概率（Kotlin shouldAttemptTheft 公式段：道德差 × 每点概率
/// clamp [0, 0.90]，宵禁 ×(1−0.30)）
inline double theftAttemptProbability(int32_t morality, bool curfew) {
    double p = (kLawMoralityThreshold - morality) * kLawDesertionProbPerPoint;
    if (p < 0.0) p = 0.0;
    if (p > kLawDesertionMaxProb) p = kLawDesertionMaxProb;
    if (curfew) p *= (1.0 - kCurfewEventReduction);
    return p;
}

/// 偷盗被捕（Kotlin handleLawEnforcementCapture——原位状态改写：不 remove/
/// 重插、无引导计数；非数字 id 整块跳过含事件，对齐 Kotlin toIntOrNull 早退）
inline void captureDiscipleForTheft(GameState& state,
                                    const state::Disciple& disciple) {
    if (!toIntOrNull(disciple.id).has_value()) return;
    auto& ds = state.disciples;
    const auto it = ds.idToRow.find(disciple.id);
    if (it == ds.idToRow.end()) return;   // ids.contains false → 跳过
    const std::size_t row = it->second;
    if (ds.isAlive[row] != 1) return;
    ds.statuses[row] = "REFLECTING";
    auto& sd = ds.statusData[row];
    sd["reflectionStartYear"] = std::to_string(state.gameData.gameYear);
    sd["reflectionEndYear"] =
        std::to_string(state.gameData.gameYear + kLawReflectionYears);
    recordGameEvent(state, "SECT", "theft_caught", disciple.name + "偷盗被捕",
                    disciple.id, disciple.name);
}

/// 仓库守卫判定（Kotlin handleWarehouseGarrisonCheck 非事务版：随机选仓库 →
/// 活跃驻守 → 守卫智力比对；thiefIntel > guardIntel → 守卫失守返回 false，
/// 否则抓捕返回 true。守卫缺失 → 守卫失守）
inline bool warehouseGarrisonCheck(
    GameState& state, const state::Disciple& thief, int32_t thiefIntel,
    const std::vector<state::GridBuildingData>& warehouses,
    const std::vector<state::WarehouseGarrisonSlot>& garrisons,
    rng::DeterministicRng& rngSystem) {
    if (warehouses.empty()) return false;
    const state::GridBuildingData& wh = warehouses[static_cast<std::size_t>(
        rngSystem.nextInt(static_cast<int32_t>(warehouses.size())))];
    const auto git = std::find_if(
        garrisons.begin(), garrisons.end(),
        [&](const state::WarehouseGarrisonSlot& g) {
            return g.buildingInstanceId == wh.instanceId &&
                   !g.discipleId.empty();   // Kotlin isActive 计算属性
        });
    if (git == garrisons.end()) return false;
    const auto& ds = state.disciples;
    const auto rit = ds.idToRow.find(git->discipleId);
    if (rit == ds.idToRow.end()) return false;   // 守卫不存在 → 失守
    const int32_t guardIntel = stats::baseIntelligence(ds, rit->second);
    if (thiefIntel > guardIntel) return false;
    captureDiscipleForTheft(state, thief);
    return true;
}

/// 偷盗灵石金额（Kotlin calcTheftAmount 新公式：境界基准 ×(1+身法/智力加成)
/// ×随机波动(±20%)，clamp [100, 宗门灵石×10%]。Kotlin Long.coerceIn 在
/// max<min 时抛 IllegalArgumentException → safelyRunInState 吞掉中止本
/// 子事件——以异常等价模拟）
inline int64_t calcTheftAmount(const state::Disciple& thief,
                               int64_t totalSpiritStones,
                               rng::DeterministicRng& rngSystem) {
    if (totalSpiritStones <= 0) return 0;
    const int32_t realmLevel =
        std::min(std::max(thief.realm, 1), 9);
    const int64_t baseAmount = kTheftRealmBaseAmounts[realmLevel];
    const auto st = stats::baseStats(thief);
    const double speedBonus =
        std::max(st.speed - kTheftSpeedBase, 0) * kTheftSpeedBonusPerPoint;
    const double intelBonus = std::max(st.intelligence - kTheftIntelligenceBase,
                                       0) * kTheftIntelligenceBonusPerPoint;
    const double rawAmount =
        static_cast<double>(baseAmount) * (1.0 + speedBonus + intelBonus);
    const double randomFactor = 0.8 + rngSystem.nextDouble() * 0.4;
    const int64_t maxAmount = static_cast<int64_t>(
        static_cast<double>(totalSpiritStones) * kTheftMaxRatioOfTotal);
    const int64_t stolen = static_cast<int64_t>(rawAmount * randomFactor);
    if (maxAmount < kTheftMinAmount) {
        throw std::runtime_error("coerceIn violated: maxAmount < THEFT_MIN_AMOUNT");
    }
    int64_t result = stolen;
    if (result < kTheftMinAmount) result = kTheftMinAmount;
    if (result > maxAmount) result = maxAmount;
    return result;
}

/// 加权物品选取（Kotlin performWeightedItemSelection：偷盗能力 = 境界基准 +
/// 身法/智力加成换算物品单位；守卫减员每活跃守卫 −2；六类堆叠轨道按数量
/// 展开等概率池，均匀抽取（抽取即移除），按 (id,type) 保首现序分组计数）
inline std::vector<LootedItemEntry> selectTheftItems(
    const state::Disciple& thief, int64_t sectSpiritStones,
    const std::vector<state::GridBuildingData>& warehouses,
    const std::vector<state::WarehouseGarrisonSlot>& garrisons,
    const std::vector<state::EquipmentStack>& equipmentStacks,
    const std::vector<state::ManualStack>& manualStacks,
    const std::vector<state::Pill>& pills,
    const std::vector<state::Material>& materials,
    const std::vector<state::Herb>& herbs,
    const std::vector<state::Seed>& seeds,
    rng::DeterministicRng& rngSystem) {
    if (sectSpiritStones <= 0) return {};
    const int32_t realmLevel = std::min(std::max(thief.realm, 1), 9);
    const int64_t baseAmount = kTheftRealmBaseAmounts[realmLevel];
    const auto st = stats::baseStats(thief);
    const int32_t speedUnits = static_cast<int32_t>(
        std::max(st.speed - kTheftSpeedBase, 0) * kTheftSpeedBonusPerPoint *
        kTheftItemUnitSpeedFactor);
    const int32_t intelUnits = static_cast<int32_t>(
        std::max(st.intelligence - kTheftIntelligenceBase, 0) *
        kTheftIntelligenceBonusPerPoint * kTheftItemUnitIntelFactor);
    int32_t activeGuardCount = 0;
    for (const auto& w : warehouses) {
        for (const auto& g : garrisons) {
            if (g.buildingInstanceId == w.instanceId &&
                !g.discipleId.empty()) {
                ++activeGuardCount;
                break;
            }
        }
    }
    const int32_t capacity = static_cast<int32_t>(
                                 baseAmount / kTheftItemBaseDivisor) +
                             speedUnits + intelUnits;
    const int32_t finalCount = std::max(
        capacity - activeGuardCount * kTheftItemGuardReduction, 1);

    struct PoolEntry {
        std::string type;
        std::string id;
        std::string name;
        int32_t rarity;
    };
    std::vector<PoolEntry> pool;
    const auto expand = [&pool](const std::string& type, const std::string& id,
                                const std::string& name, int32_t rarity,
                                int32_t quantity) {
        const int32_t q = std::max(quantity, 0);
        for (int32_t k = 0; k < q; ++k) {
            pool.push_back(PoolEntry{type, id, name, rarity});
        }
    };
    // 池展开顺序对齐 Kotlin add() 调用序：材料 → 丹药 → 灵草 → 种子 →
    // 装备 → 功法（RNG 消费序红线）
    for (const auto& it : materials) expand("material", it.id, it.name, it.rarity, it.quantity);
    for (const auto& it : pills) expand("pill", it.id, it.name, it.rarity, it.quantity);
    for (const auto& it : herbs) expand("herb", it.id, it.name, it.rarity, it.quantity);
    for (const auto& it : seeds) expand("seed", it.id, it.name, it.rarity, it.quantity);
    for (const auto& it : equipmentStacks) expand("equipment", it.id, it.name, it.rarity, it.quantity);
    for (const auto& it : manualStacks) expand("manual", it.id, it.name, it.rarity, it.quantity);
    if (pool.empty()) return {};

    const int32_t draws =
        std::min(finalCount, static_cast<int32_t>(pool.size()));
    std::vector<PoolEntry> picked;
    picked.reserve(static_cast<std::size_t>(draws));
    for (int32_t k = 0; k < draws; ++k) {
        const int32_t idx =
            rngSystem.nextInt(static_cast<int32_t>(pool.size()));
        picked.push_back(pool[static_cast<std::size_t>(idx)]);
        pool.erase(pool.begin() + idx);
    }
    // 按 (id,type) 分组，保持首现顺序（Kotlin groupBy LinkedHashMap 语义）
    std::vector<LootedItemEntry> out;
    for (const auto& p : picked) {
        bool merged = false;
        for (auto& e : out) {
            if (e.id == p.id && e.type == p.type) {
                e.count += 1;
                merged = true;
                break;
            }
        }
        if (!merged) {
            out.push_back(LootedItemEntry{p.id, p.name, p.type, p.rarity, 1});
        }
    }
    return out;
}

/// 被盗物品从六类堆叠轨道扣除（Kotlin LootCalculator.applyLoot 的
/// stolenItems 段——偷盗路径 stolenSpiritStones/stolenBagCount 恒 0）：
/// 按 id 扣减 quantity（下限 0 不删除），末尾统一过滤 0 数量条目
inline void applyStolenItemsToStores(GameState& state,
                                     const std::vector<LootedItemEntry>& items) {
    const auto decrement = [](auto& store, const std::string& id,
                              int32_t count) {
        for (auto& it : store) {
            if (it.id == id) {
                it.quantity = std::max(it.quantity - count, 0);
                break;   // Kotlin EntityStore.update(id) 单条命中
            }
        }
    };
    for (const auto& it : items) {
        if (it.type == "material") decrement(state.materials, it.id, it.count);
        else if (it.type == "pill") decrement(state.pills, it.id, it.count);
        else if (it.type == "herb") decrement(state.herbs, it.id, it.count);
        else if (it.type == "seed") decrement(state.seeds, it.id, it.count);
        else if (it.type == "equipment") decrement(state.equipmentStacks, it.id, it.count);
        else if (it.type == "manual") decrement(state.manualStacks, it.id, it.count);
    }
    const auto dropEmpty = [](auto& store) {
        store.erase(std::remove_if(store.begin(), store.end(),
                                   [](const auto& it) {
                                       return it.quantity <= 0;
                                   }),
                    store.end());
    };
    dropEmpty(state.materials);
    dropEmpty(state.pills);
    dropEmpty(state.herbs);
    dropEmpty(state.seeds);
    dropEmpty(state.equipmentStacks);
    dropEmpty(state.manualStacks);
}

/// 成功偷窃（Kotlin executeSuccessfulTheft 月变路径：扣宗门灵石（下限 0）→
/// 物品抽取与仓库扣除 → 弟子储物袋入账 → 事件 → 年度计数递增）
inline void executeSuccessfulTheft(
    GameState& state, const state::Disciple& thief,
    const std::vector<state::GridBuildingData>& warehouses,
    const std::vector<state::WarehouseGarrisonSlot>& garrisons,
    rng::DeterministicRng& rngSystem) {
    auto& gd = state.gameData;
    const int64_t stolenAmount = calcTheftAmount(thief, gd.spiritStones, rngSystem);
    if (stolenAmount <= 0 && gd.spiritStones <= 0) return;
    // 物品抽取门控取扣减前灵石（Kotlin gd = currentData 快照语义的结构位置：
    // 门控先于本次扣减生效；与基线读的残余口径差归 S-14 同族登记）
    const int64_t stonesBeforeDeduction = gd.spiritStones;
    if (stolenAmount > 0) {
        gd.spiritStones = std::max(gd.spiritStones - stolenAmount, static_cast<int64_t>(0));
    }
    const std::vector<LootedItemEntry> stolenItems = selectTheftItems(
        thief, stonesBeforeDeduction, warehouses, garrisons, state.equipmentStacks,
        state.manualStacks, state.pills, state.materials, state.herbs,
        state.seeds, rngSystem);
    if (!stolenItems.empty()) applyStolenItemsToStores(state, stolenItems);
    // 弟子储物袋（容量无上限；D-03 条目 stackedData 缺省 = 未物化语义）
    auto& ds = state.disciples;
    const auto it = ds.idToRow.find(thief.id);
    if (it == ds.idToRow.end()) return;   // Kotlin firstOrNull null → return
    const std::size_t row = it->second;
    ds.storageBagSpiritStones[row] += stolenAmount;
    for (const auto& item : stolenItems) {
        state::StorageBagItem entry;
        entry.itemId = item.id;
        entry.itemType = item.type;
        entry.name = item.name;
        entry.rarity = item.rarity;
        entry.quantity = item.count;
        entry.obtainedYear = gd.gameYear;
        entry.obtainedMonth = gd.gameMonth;
        ds.storageBagItems[row].push_back(entry);
    }
    std::string itemSummary;
    if (!stolenItems.empty()) {
        itemSummary = "（含" + std::to_string(stolenItems.size()) + "种物品）";
    }
    recordGameEvent(state, "SECT", "warehouse_theft",
                    "宗门仓库被盗，损失" + std::to_string(stolenAmount) +
                        "灵石" + itemSummary);
    gd.annualTheftCount += 1;
}

/// 月度偷盗兜底主流程（Kotlin processTheftIfNeeded → processTheftMonthly →
/// processSingleDiscipleTheft 非事务版全链；safelyRunInState("theft") 语义 =
/// 异常吞掉中止本子事件、保留已写入状态）
inline void processTheftMonthlyFallback(
    GameState& state, rng::RngManager& rng,
    const std::map<int32_t, std::size_t>& idx) {
    try {
        auto& gd = state.gameData;
        auto& ds = state.disciples;
        // ① 月度判定计数器归零（Kotlin 首行无条件 update）
        gd.theftJudgementsThisMonth = 0;
        if (gd.spiritStones <= 0) return;
        if (gd.annualTheftCount >= kLawMaxTheftPerYear) return;
        if (!isAverageLoyaltyLowEnough(ds)) return;
        // hasCandidate 门控（Kotlin 无保护期检查——与候选收集 deliberate 差异保留）
        const int32_t currentYear = gd.gameYear;
        bool hasCandidate = false;
        for (std::size_t row = 0; row < ds.size() && !hasCandidate; ++row) {
            if (ds.isAlive[row] != 1) continue;
            if (ds.statuses[row] != "IDLE") continue;
            if (ds.moralities[row] >= kLawMoralityThreshold) continue;
            if (ds.lastTheftJudgementYears[row] == currentYear) continue;
            hasCandidate = true;
        }
        if (!hasCandidate) return;
        // processTheftMonthly：候选收集（门控/灵石复检——Kotlin 结构性重复保留）
        if (gd.spiritStones <= 0) return;
        if (!isAverageLoyaltyLowEnough(ds)) return;
        const int32_t currentMonth = gd.gameYear * 12 + gd.gameMonth;
        std::vector<int32_t> candidateIds;   // Kotlin tables.ids（Int）行序
        for (std::size_t row = 0; row < ds.size(); ++row) {
            if (ds.isAlive[row] != 1) continue;
            if (ds.statuses[row] != "IDLE") continue;
            if (ds.moralities[row] >= kLawMoralityThreshold) continue;
            if (currentMonth - ds.recruitedMonths[row] <
                kLawNewDiscipleProtectionMonths) {
                continue;
            }
            if (ds.lastTheftJudgementYears[row] == currentYear) continue;
            const int32_t id = toIntOrNull(ds.ids[row]).value_or(-1);
            if (id < 0) continue;
            candidateIds.push_back(id);
        }
        auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
        // 候选 take(3)：无论判定成败均消耗候选名额
        const std::size_t judgeCount =
            std::min(candidateIds.size(),
                     static_cast<std::size_t>(kLawMaxTheftJudgementsPerMonth));
        for (std::size_t k = 0; k < judgeCount; ++k) {
            const int32_t id = candidateIds[k];
            // 前一候选叛逃会移除行——行序重解析（Kotlin 以 id 寻址等价）
            const auto freshIdx = indexById(ds);
            const auto rit = freshIdx.find(id);
            if (rit == freshIdx.end()) continue;   // assemble null → 静默
            const std::size_t row = rit->second;
            // canDiscipleAttemptTheft（三层限制 + 门控复检）
            if (gd.spiritStones <= 0) continue;   // 首行 committed 灵石检查
            if (!isAverageLoyaltyLowEnough(ds)) continue;
            if (ds.isAlive[row] != 1) continue;
            if (ds.statuses[row] != "IDLE") continue;
            if (currentMonth - ds.recruitedMonths[row] <
                kLawNewDiscipleProtectionMonths) {
                continue;
            }
            if (ds.lastTheftJudgementYears[row] == currentYear) continue;
            if (gd.theftJudgementsThisMonth >= kLawMaxTheftJudgementsPerMonth) continue;
            if (gd.annualTheftCount >= kLawMaxTheftPerYear) continue;
            // 标记判定（先于概率抽取——尝试失败同样计数）
            gd.theftJudgementsThisMonth += 1;
            ds.lastTheftJudgementYears[row] = currentYear;
            // executeFullTheftCheck
            const state::Disciple thief = ds.materialize(row);
            const auto st = stats::baseStats(thief);
            const double captureRate = calculateCaptureRate(state, freshIdx);
            std::vector<state::GridBuildingData> warehouses;
            for (const auto& b : gd.placedBuildings) {
                if (b.displayName == "仓库") warehouses.push_back(b);
            }
            // Step 1: 偷盗概率判定
            const double effectiveTheftProb =
                theftAttemptProbability(st.morality, gd.sectPolicies.curfew);
            if (rngSystem.nextDouble() >= effectiveTheftProb) continue;
            // Step 2: 执法堂判定——直接以抓捕率判定
            if (rngSystem.nextDouble() < captureRate) {
                captureDiscipleForTheft(state, thief);
                continue;
            }
            // Step 3: 仓库驻守判定——纯智力比拼
            if (warehouseGarrisonCheck(state, thief, st.intelligence,
                                       warehouses, gd.warehouseGarrisons,
                                       rngSystem)) {
                continue;
            }
            // 偷窃成功 → 执行（灵石 + 物品）
            executeSuccessfulTheft(state, thief, warehouses,
                                   gd.warehouseGarrisons, rngSystem);
            // Step 4: 偷盗后叛逃判定（仅看忠诚；抽取无条件）
            const double desertionProb = calcDesertionProbability(st.loyalty);
            if (rngSystem.nextDouble() < desertionProb) {
                desertDiscipleCleanup(state, id, kLawLoyaltyThreshold,
                                      indexById(ds), "theft_desertion",
                                      "偷盗后叛逃");
            }
        }
    } catch (const std::exception&) {
        // Kotlin safelyRunInState：异常吞掉，中止偷盗子事件、保留已写入状态
    }
}

inline void processMonthlyEvents(GameState& state, rng::RngManager& rng,
                                 const std::map<int32_t, std::size_t>& idx) {
    // 子事件 1：招募月度计数归零
    state.gameData.recruitCountThisMonth = 0;
    // 子事件 3：月度偷盗兜底（批 10-3；Kotlin processTheftIfNeeded 全链，
    // 首行无条件归零 theftJudgementsThisMonth 已随行移植）
    detail::processTheftMonthlyFallback(state, rng, idx);
    // 子事件 4：月度叛逃检测（批 10-2）
    detail::processLawEnforcementMonthly(state, rng);
    // 子事件 7：游戏结束检查
    detail::checkGameOverCondition(state);
    // 子事件 8：侦察信息过期清理（批 10-1）
    detail::applyScoutInfoExpiry(state, state.gameData.gameYear,
                                 state.gameData.gameMonth);
    // 子事件 11：灵矿月度产出结算
    detail::processSpiritMineProductionMonthly(state, idx);
    // 其余子事件未下沉——见文件头范围边界（rng 参数供后续批次接线）
}

}  // namespace detail

// ── 主入口：月变结算（注册进 SettlementEngine::onMonthChange） ─────

/// 月变事务编排结果（Kotlin policyResult 等价——事务外 checkpointAllProduction
/// 决策依据；当前真相源仍在 Kotlin，本结果仅供测试断言与未来接线）
struct MonthSettlementResult {
    PolicyCostResult policyCosts;
};

/// 执行一次月变结算（时间推进与月界检测由 SettlementEngine 负责）。
/// @param state 完整游戏状态（就地修改）
/// @param rng   RNG 分区管理器（EXPLORATION：妖兽移动；SYSTEM：收获 roll/伴侣配对）
inline MonthSettlementResult runMonthSettlement(state::GameState& state,
                                                rng::RngManager& rng) {
    MonthSettlementResult out;
    const auto idx = detail::indexById(state.disciples);

    // 步骤 1：政策月度灵石扣除
    {
        int32_t discipleCount = 0;
        int32_t huashenBelowCount = 0;
        const state::DiscipleStore& ds = state.disciples;
        for (std::size_t row = 0; row < ds.size(); ++row) {
            if (ds.isAlive[row] == 0) continue;
            ++discipleCount;
            if (ds.realms[row] > 5) ++huashenBelowCount;   // realm 5=化神，>5=化神下
        }
        out.policyCosts = processPolicyCosts(state.gameData, discipleCount,
                                             huashenBelowCount);
    }

    // 步骤 2：政策月度忠诚/道德效果
    detail::processPolicyMonthlyEffects(state);

    // 步骤 3：AI 兽袭目标预计算——未下沉（AI 宗门域，阶段 4 批次；
    // 场景 worldMapSects 为空 → Kotlin 同样零抽取零写入）

    // 步骤 4：七系统扇出（@SystemPriority 升序；稳定排序 Exploration(240)
    // 先于 Partner(240)，对齐 Dagger Set 注入序的现行生产行为）
    // 4a Alchemy(210)：异步自动炼丹 launch + 同步完成结算——均未下沉
    //   （Room 仓储/物品数据库域；场景无到期槽位；launch 本身事务内零效果）
    // 4b Forge(211)：异步自动锻造 launch——事务内零效果（未下沉无影响面）
    // 4c Planting(214)：灵田成熟收获 + 续种（SYSTEM 种子 roll）
    detail::processSpiritFieldHarvestStep(state, rng);
    // 4d ChildBirth(235)：生育——未下沉（弟子生成批次；场景 childBirthMonth
    //   全空 → 双端零效果零抽取）
    // 4e Exploration(240)：世界关卡惰性管理（清理 + 移动；刷新生成属
    //   LevelGenerator 批次未下沉 → allowRefresh 恒 false，与"无玩家宗门"
    //   路径语义一致：不生成、不推进 lastRefreshMonth）
    {
        const auto monthly = processWorldLevelsMonthly(
            state.gameData.worldLevels,
            state.gameData.worldLevelLastRefreshMonth,
            state.gameData.gameYear, state.gameData.gameMonth,
            rng, /*allowRefresh=*/false);
        state.gameData.worldLevels = std::move(monthly.levels);
        // refreshed 恒 false（生成未下沉）→ lastRefreshMonth 保持不变
    }
    // 巡视楼战斗 / 妖兽攻击检测：战斗域未下沉；场景 patrolSlots 为空 +
    // 无玩家宗门 → 双端纯早退零抽取
    // 4f Partner(240)：道侣配对（SYSTEM 配对概率抽卡）
    detail::processPartnerMatching(state, rng, idx);
    // 4g Mail(960)：异步网络邮件拉取——事务内零状态效果（C++ 空操作等价）

    // 步骤 5：血炼完成检测
    detail::processBloodRefinementCompletions(state, idx);

    // 步骤 6：月度自动排班（未下沉，场景自动政策全关纯早退）+ 住所忠诚
    detail::processResidenceLoyalty(state);

    // 步骤 7：丹药持续效果月度衰减
    detail::applyMonthlyDurationDecayAll(state);

    // 步骤 8：月度事件（三件已下沉子事件）
    detail::processMonthlyEvents(state, rng, idx);

    return out;
}

}  // namespace gamecore::system
