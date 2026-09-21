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
#include "gamecore/ecs/disciple_component.h"  // syncDiscipleEntities 行序桥接
#include "gamecore/state/models.h"
#include "gamecore/system/blood_refinement.h"
#include "gamecore/system/child_birth.h"
#include "gamecore/system/disciple_purchase.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/exploration.h"
#include "gamecore/system/government.h"
#include "gamecore/system/disciple_stats.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/level_generator.h"
#include "gamecore/system/merchant_settlement.h"
#include "gamecore/system/mission_settlement.h"
#include "gamecore/system/mission_completion.h"
#include "gamecore/system/ai_sect_ops.h"
#include "gamecore/system/production.h"
#include "gamecore/system/recruit_settlement.h"
#include "gamecore/system/sect_decision.h"
#include "gamecore/system/secret_realm_settlement.h"
#include "gamecore/system/sect_power.h"
#include "gamecore/system/settlement_detail.h"
#include "gamecore/system/slot_cleanup.h"
#include "gamecore/system/spirit_field.h"

// ============================================================
// 月变结算钩子
//
// 等价移植 Kotlin GameEngineCore.processMonthYearChange 的 monthChanged 分支
// 单事务编排（Kotlin 侧由 MonthSettlementExecutor 提取同构），注册进
// SettlementEngine::onMonthChange 钩子。
//
// 八步事务序（语义权威 = 各被调方法源码）：
//   1. 政策月度灵石扣除        ← government.h::processPolicyCosts（原语接线）
//   2. 政策月度忠诚/道德效果     ← CultivationSettlement.processPolicyMonthlyEffects
//   3. AI 兽袭目标预计算        ← precomputeTargets（EXPLORATION；
//      消费方巡视楼/子事件 9 保留 Kotlin）
//   4. systemManager.onMonthlyEvent 七系统扇出（@SystemPriority 升序）：
//      Alchemy(210) → Forge(211) → Planting(214) → ChildBirth(235) →
//      Exploration(240) → Partner(240，稳定排序居后)
//      （Mail(960) 已移除——在线邮件月度拉取通道下线，Kotlin MailSystem 删除）
//   5. 血炼完成检测            ← blood_refinement 原语 + 本文件结算段
//   6. 月度自动排班 + 住所忠诚   ← processResidenceLoyalty（排班未下沉）
//   7. 丹药持续效果月度衰减      ← HpMpRecoveryService.applyMonthlyDurationDecay
//   8. processMonthlyEventsOnState 十六子事件（全部入 C++，相对序与 Kotlin 一致）
//
// RNG 消耗点核对表（分区 / 触发条件 / 抽取次数——对拍命门，逐点核对自源码）：
//   - EXPLORATION：妖兽移动 moveBeasts，每活跃妖兽 2 次 nextDouble（角度+距离）
//   - SYSTEM：灵田收获种子 roll nextInt(5)，每收获地块 1 次
//   - SYSTEM：伴侣配对 nextDouble，每通过过滤的 (男,女) 组合 1 次
//   - SYSTEM：偷盗兜底每判定候选：概率 1 次 + 捕获 1 次 +
//     仓库选取 nextInt(仓库数)（仓库非空时）+ 金额波动 1 次 +
//     物品 nextInt(池大小) 0..N 次 + 偷盗后叛逃 1 次（无条件抽取）
//   - BATTLE：子事件 6b 征伐环（P2-18 Stage 2）checkAttackConditions
//     门通过恰抽 1 次 nextDouble + executeAiBattle 全回合抽取；
//     子事件 6c 防守环（P2-18 Stage 1）decidePlayerAttack 六道闸通过者
//     恰抽 1 次 nextDouble + 到期战书 executeAiBattle 全回合抽取
//   - BREAKTHROUGH：本钩子零消耗（旬结算是 BREAKTHROUGH 唯一入口）
//   已知未下沉扇出的抽取点（场景规避 + 边界登记）：
//   AI 兽袭 EXPLORATION（precomputeTargets 已入本钩子）、关卡刷新生成
//   （LevelGenerator 接线）、生产完成 SYSTEM（炼丹/锻造同步段）、
//   生育/招募/购买/附庸/商人等 SYSTEM 子事件、执法堂月度偷盗兜底、
//   教化之道道德增量后的反应式偷盗判定钩子均已入 C++。
//
// 已知范围边界：
//   - precomputeTargets：aiSectBeastDirectTargets/aiSectBeastSkipCooldowns/
//     lockedBeastIds 入 GameState 顶层快照协议——Kotlin 同名 GameData 字段
//     @Transient 纯运行态；消费方巡视楼/子事件 9 保留 Kotlin
//   - 关卡刷新生成：LevelGenerator 生成 + playerAvgRealm 兜底 +
//     lastRefreshMonth 推进；巡视楼战斗/妖兽攻击检测仍战斗域 Kotlin，
//     场景 patrolSlots 为空 + 无玩家宗门 → 检测/巡视纯早退
//   - 生育：场景 childBirthMonth 全空 → 双端零效果
//   - 炼丹/锻造自动排班与完成结算（Room 仓储/物品数据库域）：
//     场景无到期槽位且自动政策全关；ForgeSystem 为异步 launch（事务内零效果）
//   - 自动排班：11 槽占用扫描 + 住所分配 + 四类生产候选 + 原子写入
//     （住所建筑表静态数据 + 双端守卫）
//   - 子事件：recruitCountThisMonth 归零 / 灵矿月产 / gameOverCheck /
//     scoutExpiry / 月度叛逃检测 / 月度偷盗兜底 / 附庸脱离检查 /
//     autoRecruit / 秘境到期关闭+AI 队伍派遣 / 12 月自动购买 /
//     弟子智能购买 / 任务刷新均已入 C++
//   - 教化之道道德增量后的偷盗判定钩子：道德提升后仍 < 偷盗阈值 →
//     单弟子偷盗判定 judgeSingleTheftCandidate，SYSTEM 抽取内嵌弟子循环序
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
using gamecore::state::DiscipleColumn;
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

/// 消息栏事件记录（settle_util 共享实现——完整守卫见 settlement_detail.h）
using settle_util::recordGameEvent;

// ── 步骤 2：政策月度忠诚/道德效果 ──────────────────────────────────
// （CultivationSettlement.processPolicyMonthlyEffects：单次遍历合并净变化；
//   教化之道道德提升后仍低于偷盗阈值的判定钩子——
//   Kotlin 事务内版 processSingleDiscipleTheft(id, state) 等价）

// 前置声明（定义在偷盗链区域——judgeSingleTheftCandidate 依赖的辅助
// 函数均定义于文件后部 detail:: 命名空间；单翻译单元内声明后定义合法）
inline int32_t lawMoralityThreshold();
inline void judgeSingleTheftCandidate(GameState& state, int32_t id,
                                      int32_t currentMonth,
                                      rng::DeterministicRng& rngSystem,
                                      ecs::World& world);

inline void processPolicyMonthlyEffects(GameState& state, rng::RngManager& rng,
                                        ecs::World& world) {
    const GameData& gd = state.gameData;
    const auto& policies = gd.sectPolicies;

    // 忠诚净变化（各政策月度增减汇总，与 Kotlin 合并口径一致）
    int32_t loyaltyDelta = 0;
    if (policies.benevolentGovernance) loyaltyDelta += kBenevolentLoyaltyPerMonth;
    if (policies.relaxedMgmt) loyaltyDelta += kRelaxedMgmtLoyaltyPerMonth;
    if (policies.strictTraining) loyaltyDelta += kStrictTrainingLoyaltyPerMonth;
    if (policies.enhancedSecurity) loyaltyDelta += kEnhancedSecurityLoyaltyPerMonth;
    if (policies.curfew) loyaltyDelta += kCurfewLoyaltyPerMonth;

    auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    const int32_t currentMonth = gd.gameYear * 12 + gd.gameMonth;
    // 迭代域 = 入口 id 快照（sync + View
    // 行序）+ 逐 id rowOf 现查。教化之道偷盗钩子可能移行（被捕原位改写/
    // 偷盗后叛逃 remove），快照序 == 行序 == Kotlin tables.ids 序（Kotlin
    // 同域为活表迭代，移行即 CME——生产不触发；快照即其安全等价）。
    DiscipleStore& ds = state.disciples;
    ecs::syncDiscipleEntities(world, ds.size());
    std::vector<std::pair<std::string, int32_t>> idSnapshot;  // (id 串, int id 或 -1)
    {
        ecs::View<ecs::DiscipleRef> view(world.registry());
        view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
            const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
            if (ds.isAlive[row] == 0) return;
            idSnapshot.emplace_back(
                ds.ids[row], toIntOrNull(ds.ids[row]).value_or(-1));
        });
    }
    for (const auto& [idStr, idInt] : idSnapshot) {
        // 前序偷盗钩子可能移行——行存在性重解析（id 寻址等价）
        const auto rowOpt = ds.rowOf(idStr);
        if (!rowOpt.has_value()) continue;
        const std::size_t row = *rowOpt;
        if (ds.isAlive[row] == 0) continue;
        // 忠诚：delta != 0 时 clamp 写回（getOrDefault 缺省 50 —— C++ 字段恒存在）
        if (loyaltyDelta != 0) {
            ds.loyalties[row] = std::max(
                0, std::min(kMaxLoyalty, ds.loyalties[row] + loyaltyDelta));
        }
        // 道德（教化之道）：仅当前低于上限时 +1 并 clamp；
        // 新道德仍低于偷盗阈值 → 单弟子偷盗判定（Kotlin 事务内版
        // 钩子——SYSTEM 抽取内嵌于弟子循环序，与 Kotlin 逐位一致）
        if (policies.moralEducation && ds.moralities[row] < kMoralEducationMax) {
            ds.moralities[row] = std::max(
                0, std::min(kMoralEducationMax,
                            ds.moralities[row] + kMoralEducationPerMonth));
            if (ds.moralities[row] < lawMoralityThreshold()) {
                if (idInt >= 0) {
                    judgeSingleTheftCandidate(state, idInt, currentMonth,
                                              rngSystem, world);
                }
            }
        }
    }
}

// ── 步骤 4c：灵田收获（spirit_field.h 原语接线） ───────────────────
// Kotlin ProductionProcessor.processSpiritFieldHarvest 的等价移植；
// 溢出邮件收集器结果在 C++ 侧丢弃（邮件实体不在快照协议——溢出转邮件的
// 可见面为零；仓库容量充足的对拍场景双端均无溢出）

inline void processSpiritFieldHarvestStep(GameState& state, rng::RngManager& rng) {
    OverflowMailCollector overflowMail;
    processSpiritFieldHarvest(state, rng, overflowMail);
    // overflowMail 内容即 Kotlin sendOverflowMail 的邮件草稿——C++ 无邮件协议，
    // 显式弃用（邮件域不在 C++ 状态/协议范围）
}

// ── 步骤 4d：生育（child_birth.h 等价移植） ─────────────────────────
// Kotlin ChildBirthSystem.processMonthlyBirth——SYSTEM 分区消费序逐位对齐
//（性别/名字/灵根继承或 SpiritRootGenerator/弟子生成六段；父死分支零消费）

inline void processChildBirthStep(GameState& state, rng::RngManager& rng,
                                  ecs::World& world) {
    child_birth::processMonthlyBirth(
        state, rng.getRng(rng::RngPartition::kSystem), world);
}

// ── 步骤 4f：伴侣配对（PartnerSystem.processPartnerMatching 完整移植） ──
// RNG 契约：每对通过过滤的 (male, female) 组合恰好一次 SYSTEM nextDouble；
// 遍历序 = eligibleMales 外层 × eligibleFemales 内层（assembleAll 快照序 ==
// C++ disciples 向量序）；pairedFemale 位图跳过不改写快照。

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
                                   const std::map<int32_t, std::size_t>& idx,
                                   ecs::World& world) {
    // assembleAll 快照等价：循环期间只写 live 列，资格判定全部读入口快照副本
    //（DiscipleStore 版：逐行物化快照列表，语义 == 旧整向量拷贝）。
    // 快照构建经 sync + View<DiscipleRef> 行序
    //（快照序 == 行序 == Kotlin assembleAll 序，M×F 配对 RNG 消费序不变）。
    DiscipleStore& ds = state.disciples;
    ecs::syncDiscipleEntities(world, ds.size());
    std::vector<Disciple> snapshot;
    snapshot.reserve(ds.size());
    ecs::View<ecs::DiscipleRef> view(world.registry());
    view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
        snapshot.push_back(ds.materialize(ref.row));   // 行地址取自组件（桥接规范 3）
    });

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

    // 实现约束：SYSTEM RNG 引用提升到循环外（不逐 roll 重取分区）；
    // 已配对女性经 vector<bool> 位图按候选下标 O(1) 判定。
    // **循环形状不变**——M×F 配对
    // 迭代序 = RNG 消费序（Kotlin 逐位对拍红线），结构级降复杂度需双端
    // 同步改算法（行为基线变化）。
    auto& pairingRng = rng.getRng(rng::RngPartition::kSystem);
    std::vector<char> pairedFemale(eligibleFemales.size(), 0);
    for (const Disciple* male : eligibleMales) {
        for (std::size_t fi = 0; fi < eligibleFemales.size(); ++fi) {
            const Disciple* female = eligibleFemales[fi];
            if (pairedFemale[fi]) continue;
            if (hasBloodRelation(*male, *female)) continue;

            if (pairingRng.nextDouble() < kPairingProbability) {
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
                pairedFemale[fi] = 1;
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
    DiscipleStore& ds = state.disciples;
    const std::size_t row = idx.at(*dId);
    if (progress.selectedStat.empty()) {
        // 数据异常防御：进度被 processBloodRefinementCompletions 移除，但
        // REFINING 受保护状态须显式打破（与 Kotlin settleSingleRefinement 同步）
        ds.statusData[row].erase("buildingId");
        ds.statuses[row] = "IDLE";
        return;
    }
    // 防御：血炼期间弟子可能因其他系统死亡（isAlive[dId] == 0 直接返回）
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

    // 材料记录追加（bloodRefinements[id] += materialId）——审计 P2-7：
    // 纯审计轨迹零消费者，追加处 takeLast 封顶防长会话单调增长
    auto& refinements = state.gameData.bloodRefinements[progress.discipleId];
    refinements.push_back(progress.materialId);
    if (refinements.size() > BLOOD_REFINEMENT_RECORDS_CAP) {
        refinements.erase(refinements.begin(),
                          refinements.end() - static_cast<std::ptrdiff_t>(BLOOD_REFINEMENT_RECORDS_CAP));
    }

    // 清除 statusData["buildingId"] 并重置状态为 IDLE——REFINING 是受保护状态
    // （Kotlin deriveDiscipleStatus 永不回退），须在结算事务内显式打破；
    // 与 Kotlin settleSingleRefinement 语义同步（根因修复：血炼完成/取消后
    // 弟子曾永久卡"血炼池中"）
    ds.statusData[row].erase("buildingId");
    ds.statuses[row] = "IDLE";

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

// ── 步骤 6a：月度自动排班（Kotlin ProductionProcessor.
//    processAutoAssign 等价移植；零 RNG 纯数据变换）────────────────────
// 语义（对齐 Kotlin 源码）：
//   occupiedIds = 11 槽占用弟子（长老/灵矿/藏经阁/仓库驻守/巡视/宗门驻守/
//     战斗队伍/活跃任务/秘境/洞穴活跃队伍/血炼/生产槽）
//   idleDisciples = 存活 + IDLE + 非 occupied（可变池——候选按行号维护）
//   住所分配（单人/多人政策 → 建筑识别 → 候选排序 → 逐空槽）
//   生产候选（灵植/灵矿/炼丹/锻造：政策开关 → 筛选排序 → take(空槽数)，
//     超出空槽数的合格候选回流池供低优先级类型）
//   原子写入（住所/生产/灵矿——只写镜像槽位字段，不写 DiscipleStatus，
//     Kotlin 事务内同语义——状态由 UI 层 syncAllDiscipleStatuses 派生）

/// 住所建筑定义（Kotlin BuildingFeature 注册表 Residence 分类子集——
/// displayName → slotsPerInstance；双端守卫测试防漂移（静态数据）
struct ResidenceBuildingDef {
    const char* displayName;
    int32_t slotsPerInstance;
};
inline const ResidenceBuildingDef kResidenceBuildings[] = {
    {"初级单人住所", 1}, {"中级单人住所", 1},
    {"初级多人住所", 4}, {"中级多人住所", 4},
};

inline bool isResidenceSingle(const std::string& name) {
    for (const auto& d : kResidenceBuildings) {
        if (d.slotsPerInstance == 1 && name == d.displayName) return true;
    }
    return false;
}
inline bool isResidenceMulti(const std::string& name) {
    for (const auto& d : kResidenceBuildings) {
        if (d.slotsPerInstance == 4 && name == d.displayName) return true;
    }
    return false;
}

/// 长老槽位占用弟子（Kotlin collectElderSlotDiscipleIds：10 单槽 + 7 列表）
inline void collectElderSlotDiscipleIds(const state::ElderSlots& es,
                                        std::set<std::string>& out) {
    const std::string* singles[] = {
        &es.viceSectMaster, &es.herbGardenElder, &es.alchemyElder,
        &es.forgeElder, &es.outerElder, &es.preachingElder,
        &es.lawEnforcementElder, &es.innerElder, &es.qingyunPreachingElder,
        &es.recruitingElder,
    };
    for (const auto* s : singles) {
        if (!s->empty()) out.insert(*s);
    }
    const std::vector<state::DirectDiscipleSlot>* lists[] = {
        &es.preachingMasters, &es.lawEnforcementDisciples,
        &es.qingyunPreachingMasters, &es.herbGardenDisciples,
        &es.alchemyDisciples, &es.forgeDisciples,
        &es.spiritMineDeaconDisciples,
    };
    for (const auto* l : lists) {
        for (const auto& d : *l) {
            if (!d.discipleId.empty()) out.insert(d.discipleId);
        }
    }
}

/// 11 槽占用弟子 ID 收集（Kotlin buildOccupiedSlotDiscipleIds）
inline std::set<std::string> buildOccupiedSlotDiscipleIds(
    const state::GameData& gd) {
    std::set<std::string> out;
    collectElderSlotDiscipleIds(gd.elderSlots, out);
    for (const auto& s : gd.spiritMineSlots) {
        if (!s.discipleId.empty()) out.insert(s.discipleId);
    }
    for (const auto& s : gd.librarySlots) {
        if (!s.discipleId.empty()) out.insert(s.discipleId);
    }
    for (const auto& s : gd.warehouseGarrisons) {
        if (!s.discipleId.empty()) out.insert(s.discipleId);
    }
    for (const auto& s : gd.patrolSlots) {
        if (!s.discipleId.empty()) out.insert(s.discipleId);
    }
    for (const auto& sect : gd.worldMapSects) {
        if (!sect.isPlayerSect) continue;
        for (const auto& g : sect.garrisonSlots) {
            if (!g.discipleId.empty()) out.insert(g.discipleId);
        }
    }
    for (const auto& t : gd.battleTeams) {
        for (const auto& sl : t.slots) {
            if (!sl.discipleId.empty()) out.insert(sl.discipleId);
        }
    }
    for (const auto& m : gd.activeMissions) {
        for (const auto& id : m.discipleIds) out.insert(id);
    }
    if (!gd.secretRealmState.id.empty()) {
        for (const auto& m : gd.secretRealmSession.members) {
            if (!m.isDead) out.insert(m.discipleId);
        }
    }
    for (const auto& t : gd.caveExplorationTeams) {
        if (t.status == "TRAVELING" || t.status == "EXPLORING") {
            for (const auto& id : t.memberIds) out.insert(id);
        }
    }
    for (const auto& [k, p] : gd.activeBloodRefinements) {
        if (!p.discipleId.empty()) out.insert(p.discipleId);
    }
    for (const auto& s : gd.productionSlots) {
        if (s.assignedDiscipleId && !s.assignedDiscipleId->empty()) {
            out.insert(*s.assignedDiscipleId);
        }
    }
    return out;
}

/// 自动排班候选（行号 + 排序键——Kotlin Disciple 对象池的行等价）
struct AutoAssignRow {
    std::size_t row;
    bool followed;
    int32_t rootCount;
    int32_t attr;
};
inline bool autoAssignRowLess(const AutoAssignRow& a, const AutoAssignRow& b) {
    // Kotlin compareByDescending { followed } thenBy { rootCount }
    //   thenByDescending { attr }
    if (a.followed != b.followed) return a.followed > b.followed;
    if (a.rootCount != b.rootCount) return a.rootCount < b.rootCount;
    return a.attr > b.attr;
}

/// 生产槽候选提取（Kotlin takeCandidates：政策关闭 → 空；筛选排序后
/// take(maxCount)，超出回流池）
template <typename AttrFn>
inline std::vector<std::size_t> takeAutoAssignCandidates(
    std::vector<std::size_t>& pool, int32_t maxCount, bool focused,
    const std::vector<int32_t>& rootCounts, int32_t threshold,
    const state::DiscipleStore& ds, AttrFn attrOf) {
    if (!focused && rootCounts.empty()) return {};
    std::vector<AutoAssignRow> sorted;
    for (const auto row : pool) {
        const bool followed =
            ds.statusData[row].count("followed") > 0 &&
            ds.statusData[row].at("followed") == "true";
        const bool matchesFilter =
            (focused && followed) ||
            (std::find(rootCounts.begin(), rootCounts.end(),
                       spiritRootCount(ds.spiritRootTypes[row])) !=
             rootCounts.end());
        if (!matchesFilter) continue;
        if (attrOf(row) < threshold) continue;
        sorted.push_back(
            AutoAssignRow{row, followed, spiritRootCount(ds.spiritRootTypes[row]),
                          attrOf(row)});
    }
    std::stable_sort(sorted.begin(), sorted.end(), autoAssignRowLess);
    std::vector<std::size_t> taken;
    taken.reserve(static_cast<std::size_t>(std::max(maxCount, 0)));
    for (const auto& c : sorted) {
        if (static_cast<int32_t>(taken.size()) >= std::max(maxCount, 0)) break;
        taken.push_back(c.row);
    }
    // 从池移除（Kotlin pool.remove(it)——按 id 等价）
    for (const auto row : taken) {
        pool.erase(std::remove(pool.begin(), pool.end(), row), pool.end());
    }
    return taken;
}

/// 单档位住所分配（Kotlin computeResidenceAssignmentsForSlots）：
/// 候选筛选（关注/灵根数 + 悟性阈值）→ 排序 → 逐空槽（key=buildingInstanceId:slotIndex）
inline void computeResidenceForSlots(
    const std::vector<std::size_t>& allCandidates,
    const std::set<std::string>& buildingIds, const state::GameData& gd,
    const state::DiscipleStore& ds, bool focused,
    const std::vector<int32_t>& rootCounts, int32_t threshold,
    const std::set<std::string>& excludeAssignedIds,
    std::map<std::string, std::pair<std::string, std::string>>& out) {
    if (buildingIds.empty()) return;
    std::vector<AutoAssignRow> sorted;
    for (const auto row : allCandidates) {
        const bool followed =
            ds.statusData[row].count("followed") > 0 &&
            ds.statusData[row].at("followed") == "true";
        const bool matchesFilter =
            (focused && followed) ||
            (std::find(rootCounts.begin(), rootCounts.end(),
                       spiritRootCount(ds.spiritRootTypes[row])) !=
             rootCounts.end());
        if (ds.ids[row].empty()) continue;
        if (excludeAssignedIds.count(ds.ids[row])) continue;
        if (!matchesFilter) continue;
        if (ds.comprehensions[row] < threshold) continue;
        sorted.push_back(
            AutoAssignRow{row, followed, spiritRootCount(ds.spiritRootTypes[row]),
                          ds.comprehensions[row]});
    }
    std::stable_sort(sorted.begin(), sorted.end(), autoAssignRowLess);

    // 空槽（Kotlin residenceSlots.filter { buildingInstanceId in buildingIds &&
    //   discipleId.isEmpty() }——遍历序 == Kotlin 列表序）
    std::vector<const state::ResidenceSlot*> emptySlots;
    for (const auto& s : gd.residenceSlots) {
        if (buildingIds.count(s.buildingInstanceId) && s.discipleId.empty()) {
            emptySlots.push_back(&s);
        }
    }
    for (std::size_t i = 0; i < emptySlots.size(); ++i) {
        if (i >= sorted.size()) break;
        const auto& c = sorted[i];
        const std::string key = emptySlots[i]->buildingInstanceId + ":" +
                                std::to_string(emptySlots[i]->slotIndex);
        out[key] = {ds.ids[c.row], ds.names[c.row]};
    }
}

/// 住所分配（Kotlin computeResidenceAssignments：单人 + 多人两档）
inline std::map<std::string, std::pair<std::string, std::string>>
computeResidenceAssignmentsCpp(const state::GameData& gd,
                               const state::DiscipleStore& ds,
                               const state::SectPolicies& policies,
                               const std::set<std::string>& occupiedResidentIds,
                               ecs::World& world) {
    std::map<std::string, std::pair<std::string, std::string>> assignments;
    const bool singleEnabled =
        policies.autoSingleResidenceFocused ||
        !policies.autoSingleResidenceRootCounts.empty();
    const bool multiEnabled =
        policies.autoMultiResidenceFocused ||
        !policies.autoMultiResidenceRootCounts.empty();
    if (!singleEnabled && !multiEnabled) return assignments;

    std::set<std::string> singleIds;
    std::set<std::string> multiIds;
    for (const auto& b : gd.placedBuildings) {
        if (singleEnabled && isResidenceSingle(b.displayName)) {
            singleIds.insert(b.instanceId);
        }
        if (multiEnabled && isResidenceMulti(b.displayName)) {
            multiIds.insert(b.instanceId);
        }
    }

    // 候选 = 存活 + 非 occupiedResident（Kotlin assembleAll filter）。
    // 候选收集经 sync + View<DiscipleRef> 行序
    //（候选序 == 行序，排序输入序/分配结果逐位不变）。
    std::vector<std::size_t> allCandidates;
    {
        ecs::syncDiscipleEntities(world, ds.size());
        ecs::View<ecs::DiscipleRef> view(world.registry());
        view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
            const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
            if (ds.isAlive[row] != 1) return;
            if (occupiedResidentIds.count(ds.ids[row])) return;
            allCandidates.push_back(row);
        });
    }

    if (singleEnabled) {
        computeResidenceForSlots(
            allCandidates, singleIds, gd, ds, policies.autoSingleResidenceFocused,
            policies.autoSingleResidenceRootCounts,
            policies.autoSingleResidenceThreshold, {}, assignments);
    }
    // 多人排除单人已分配
    std::set<std::string> singleAssigned;
    for (const auto& [k, v] : assignments) singleAssigned.insert(v.first);
    if (multiEnabled) {
        computeResidenceForSlots(
            allCandidates, multiIds, gd, ds, policies.autoMultiResidenceFocused,
            policies.autoMultiResidenceRootCounts,
            policies.autoMultiResidenceThreshold, singleAssigned, assignments);
    }
    return assignments;
}

/// 月度自动排班主流程（Kotlin ProductionProcessor.processAutoAssign）
inline void processAutoAssign(GameState& state, ecs::World& world) {
    auto& gd = state.gameData;
    const auto& policies = gd.sectPolicies;
    const auto& ds = state.disciples;

    // 1. 11 槽占用
    const auto occupiedIds = buildOccupiedSlotDiscipleIds(gd);

    // 2. idle 池（存活 + IDLE + 非 occupied）——sync + View 行序迭代
    std::vector<std::size_t> pool;
    {
        ecs::syncDiscipleEntities(world, ds.size());
        ecs::View<ecs::DiscipleRef> view(world.registry());
        view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
            const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
            if (ds.isAlive[row] != 1) return;
            if (ds.statuses[row] != "IDLE") return;
            if (occupiedIds.count(ds.ids[row])) return;
            pool.push_back(row);
        });
    }

    // 3. 住所分配
    std::set<std::string> occupiedResidentIds;
    for (const auto& s : gd.residenceSlots) {
        if (!s.discipleId.empty()) occupiedResidentIds.insert(s.discipleId);
    }
    auto allAssignments =
        computeResidenceAssignmentsCpp(gd, ds, policies, occupiedResidentIds,
                                       world);
    std::set<std::string> assignedResidentIds;
    for (const auto& [k, v] : allAssignments) assignedResidentIds.insert(v.first);
    pool.erase(
        std::remove_if(pool.begin(), pool.end(),
                       [&](std::size_t row) {
                           return assignedResidentIds.count(ds.ids[row]) > 0;
                       }),
        pool.end());

    // 4. 空槽计数
    const auto countEmpty = [&gd](const char* type) {
        int32_t n = 0;
        for (const auto& s : gd.productionSlots) {
            // Kotlin assignedDiscipleId.isNullOrEmpty()——nullopt 或空串均未分配
            if (s.buildingType == type &&
                (!s.assignedDiscipleId || s.assignedDiscipleId->empty()) &&
                s.status == "IDLE") {
                ++n;
            }
        }
        return n;
    };
    const int32_t emptyHerbSlots = countEmpty("HERB_GARDEN");
    int32_t emptyMineSlots = 0;
    for (const auto& s : gd.spiritMineSlots) {
        if (s.discipleId.empty()) ++emptyMineSlots;
    }
    const int32_t emptyAlchemySlots = countEmpty("ALCHEMY");
    const int32_t emptyForgeSlots = countEmpty("FORGE");

    // 5. 四类候选（takeCandidates——属性读取列）
    const auto herbCandidates = takeAutoAssignCandidates(
        pool, emptyHerbSlots, policies.autoPlantFocused,
        policies.autoPlantRootCounts, policies.autoPlantThreshold, ds,
        [&ds](std::size_t row) { return ds.spiritPlantings[row]; });
    const auto mineCandidates = takeAutoAssignCandidates(
        pool, emptyMineSlots, policies.autoMineFocused,
        policies.autoMineRootCounts, policies.autoMineThreshold, ds,
        [&ds](std::size_t row) { return ds.minings[row]; });
    const auto alchemyCandidates = takeAutoAssignCandidates(
        pool, emptyAlchemySlots, policies.autoAlchemyFocused,
        policies.autoAlchemyRootCounts, policies.autoAlchemyThreshold, ds,
        [&ds](std::size_t row) { return ds.pillRefinings[row]; });
    const auto forgeCandidates = takeAutoAssignCandidates(
        pool, emptyForgeSlots, policies.autoForgeFocused,
        policies.autoForgeRootCounts, policies.autoForgeThreshold, ds,
        [&ds](std::size_t row) { return ds.artifactRefinings[row]; });

    // 6. 全空早退
    if (allAssignments.empty() && herbCandidates.empty() &&
        mineCandidates.empty() && alchemyCandidates.empty() &&
        forgeCandidates.empty()) {
        return;
    }

    // 7. 原子写入（applyAutoAssignments）
    // 7.1 住所
    if (!allAssignments.empty()) {
        std::set<std::string> writtenIds;
        for (auto& slot : gd.residenceSlots) {
            const std::string key =
                slot.buildingInstanceId + ":" + std::to_string(slot.slotIndex);
            const auto it = allAssignments.find(key);
            if (it != allAssignments.end() && slot.discipleId.empty() &&
                !writtenIds.count(it->second.first)) {
                writtenIds.insert(it->second.first);
                slot.discipleId = it->second.first;
                slot.discipleName = it->second.second;
            }
        }
    }
    // 7.2 生产槽（灵植/炼丹/锻造——迭代器填空槽）
    const auto assignProduction =
        [&gd](const char* type,
              const std::vector<std::size_t>& cands,
              const state::DiscipleStore& dss) {
            if (cands.empty()) return;
            std::size_t ci = 0;
            for (auto& s : gd.productionSlots) {
                if (s.buildingType != type) continue;
                if (s.assignedDiscipleId && !s.assignedDiscipleId->empty()) continue;
                if (s.status != "IDLE") continue;
                if (ci >= cands.size()) break;
                s.assignedDiscipleId = dss.ids[cands[ci]];
                s.assignedDiscipleName = dss.names[cands[ci]];
                ++ci;
            }
        };
    assignProduction("HERB_GARDEN", herbCandidates, ds);
    assignProduction("ALCHEMY", alchemyCandidates, ds);
    assignProduction("FORGE", forgeCandidates, ds);
    // 7.3 灵矿
    if (!mineCandidates.empty()) {
        std::size_t mi = 0;
        for (auto& s : gd.spiritMineSlots) {
            if (!s.discipleId.empty()) continue;
            if (mi >= mineCandidates.size()) break;
            s.discipleId = ds.ids[mineCandidates[mi]];
            s.discipleName = ds.names[mineCandidates[mi]];
            ++mi;
        }
    }
}

// ── 步骤 6b：住所忠诚度（processResidenceLoyalty） ─────────────────

inline void processResidenceLoyalty(GameState& state, ecs::World& world) {
    std::set<std::string> residentIds;
    for (const auto& slot : state.gameData.residenceSlots) {
        // Kotlin isActive 为计算属性 == discipleId.isNotEmpty()
        if (!slot.discipleId.empty()) residentIds.insert(slot.discipleId);
    }
    // 迭代域经 sync + View<DiscipleRef> 行序
    DiscipleStore& ds = state.disciples;
    ecs::syncDiscipleEntities(world, ds.size());
    ecs::View<ecs::DiscipleRef> view(world.registry());
    view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
        const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
        // Kotlin: id.toString() in residentIds && loyalties[id] < max
        if (residentIds.count(ds.ids[row]) && ds.loyalties[row] < kMaxLoyalty) {
            ds.loyalties[row] = std::min(ds.loyalties[row] + 1, kMaxLoyalty);
        }
    });
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

/// 单弟子月衰减（DiscipleStore 行版，列直写；语义与 Disciple& 版一致）
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

inline void applyMonthlyDurationDecayAll(GameState& state, ecs::World& world) {
    DiscipleStore& ds = state.disciples;
    // 迭代域经 sync + View<DiscipleRef> 行序
    ecs::syncDiscipleEntities(world, ds.size());
    ecs::View<ecs::DiscipleRef> view(world.registry());
    view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
        const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
        if (ds.isAlive[row] == 0) return;
        applyMonthlyDurationDecay(ds, row);
    });
}

// ── 步骤 8c：灵矿月度产出结算（processSpiritMineProductionMonthly） ─

/// 执事道德基准（GameConfig.PolicyConfig.ELDER_SKILL_BASELINE）
constexpr int32_t kElderSkillBaselineConst = 80;

/// 构建灵矿乘区（buildSpiritMineZones：矿工采矿列直读 + 执事基础道德 +
/// 政策倍率；天赋/词条注册表为空表占位 → 基础道德 == morality 字段值，
/// 填表后经 stats:: 聚合接入）
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
// 十六件子事件均已入 C++（详见 processMonthlyEvents 分发段）；相对序与 Kotlin 一致。

// 草稿结构 SecretRealmCloseDraft 定义于 secret_realm_settlement.h
//（属主文件——closeSecretRealmByExpiry 内部填充）；
// 草稿结构 PurchaseLogDraft 定义于 disciple_purchase.h
//（属主文件——applyPurchaseDecisions 内部填充）。

/// 月变事务编排结果（Kotlin policyResult 等价——事务外 checkpointAllProduction
/// 决策依据 + 平台效应草稿回传；当前真相源仍在 Kotlin，本结果仅供测试断言与
/// nativeSettleMonth 信封——月变真相源切换后 Kotlin 残留执行器消费草稿）。
/// 定义于 detail 命名空间（processMonthlyEvents 前置依赖），
/// detail 结束后以 `using detail::MonthSettlementResult` 导出到 gamecore::system。
struct MonthSettlementResult {
    PolicyCostResult policyCosts;
    std::optional<secret_realm_settle::SecretRealmCloseDraft> secretRealmClose;
    std::vector<disciple_purchase::PurchaseLogDraft> purchaseLogs;
    // 子事件 6b 征伐环平台效应草稿（P2-18 Stage 2）：玩家占领宗门被 AI
    // 夺回 → 建筑没收（buildingFacade.seizeBuildingsOfSect——特性注册表/
    // Room 生产槽位保留 Kotlin 残留执行器，反向通道回同步）
    std::vector<std::string> seizedSectBuildings;
};

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

// ── 子事件 4：月度叛逃检测（Kotlin LawEnforcementProcessor.
//    processLawEnforcementMonthly 等价移植）──────────────────────────
//
// 配置读取（远程配置注入 gameConfig()，默认值与
// game_config.json 一致；const val 类常量保持编译期）。
// RNG 契约（对拍命门）：按 at-risk 行序，每名弟子先 SYSTEM 抽 1 次
// nextDouble 与叛逃概率比较（≥ 概率跳过）；判定通过再抽第 2 次与捕获率
// 比较（< 捕获率 → 捕获思过；否则逃脱清理）。捕获/逃脱路径零额外抽取。

// 执法堂配置（可远程覆盖段——Kotlin GameConfig.LawEnforcementConfig 默认值）
inline int32_t lawLoyaltyThreshold() {
    return gamecore::gameConfig().lawLoyaltyThreshold;       // 30
}
inline double lawDesertionProbPerPoint() {
    return gamecore::gameConfig().lawProbPerPoint;           // 0.01
}
inline double lawDesertionMaxProb() {
    return gamecore::gameConfig().lawMaxProb;                // 0.90
}
inline double lawBaseCaptureRate() {
    return gamecore::gameConfig().lawBaseCaptureRate;        // 0.0
}
inline int32_t lawIntelligenceBase() {
    return gamecore::gameConfig().lawIntelligenceBase;       // 50
}
inline double lawElderBonusPerPoint() {
    return gamecore::gameConfig().lawElderBonusPerPoint;     // 0.01
}
inline int32_t lawDiscipleIntelligenceStep() {
    return gamecore::gameConfig().lawDiscipleIntelligenceStep;  // 5
}
inline double lawDiscipleBonusPerStep() {
    return gamecore::gameConfig().lawDiscipleBonusPerStep;   // 0.01
}
inline int32_t lawReflectionYears() {
    return gamecore::gameConfig().lawReflectionYears;        // 5
}
inline int32_t lawNewDiscipleProtectionMonths() {
    return gamecore::gameConfig().lawNewDiscipleProtectionMonths;  // 12
}
inline int32_t lawHerdLoyaltyThreshold() {
    return gamecore::gameConfig().lawHerdLoyaltyThreshold;   // 50
}
// 政策加成（GameConfig.PolicyConfig；const val 类）
constexpr double kEnhancedSecurityEffect = 0.20;
constexpr double kRewardPunishEffect = 0.30;

/// 叛逃免疫状态（Kotlin DESERTION_IMMUNE_STATUSES；WAREHOUSE_GARRISON 不免疫）
inline bool isDesertionImmuneStatus(const std::string& status) {
    return status == "ON_MISSION" || status == "REFLECTING" ||
           status == "REFINING" || status == "IN_TEAM" ||
           status == "SECRET_REALM";
}

/// 从众门控：存活弟子平均忠诚（整数除法截断）< 阈值；无存活弟子 → false。
/// 迭代域经 sync + View<DiscipleRef> 行序
///（求和归约与序无关，切换收益 = 每入口复用同一不变量校验）。
inline bool isAverageLoyaltyLowEnough(const state::DiscipleStore& ds,
                                      ecs::World& world) {
    int32_t count = 0;
    int64_t sum = 0;
    ecs::syncDiscipleEntities(world, ds.size());
    ecs::View<ecs::DiscipleRef> view(world.registry());
    view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
        const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
        if (ds.isAlive[row] != 1) return;
        ++count;
        sum += ds.loyalties[row];
    });
    if (count == 0) return false;
    return (sum / count) < lawHerdLoyaltyThreshold();
}

/// 捕获率 = 基础 + 长老智力加成（× 职务乘算因子）+ 执法弟子阶梯加成 + 政策，
/// clamp [0,1]。Kotlin 无存活/境界校验——按 id 命中即计（无则跳过该贡献项）。
inline double calculateCaptureRate(GameState& state,
                                   const std::map<int32_t, std::size_t>& idx) {
    const auto& gd = state.gameData;
    const auto& ds = state.disciples;
    double captureRate = lawBaseCaptureRate();
    const auto& elderId = gd.elderSlots.lawEnforcementElder;
    if (!elderId.empty()) {
        if (const auto eid = toIntOrNull(elderId)) {
            const auto it = idx.find(*eid);
            if (it != idx.end()) {
                const int32_t intel = stats::baseIntelligence(ds, it->second);
                const int32_t above = intel > lawIntelligenceBase()
                                          ? intel - lawIntelligenceBase() : 0;
                const double posBonus = stats::positionEffectBonus(
                    ds, it->second, "LAW_ENFORCEMENT");
                captureRate += above * lawElderBonusPerPoint() * (1.0 + posBonus);
            }
        }
    }
    for (const auto& slot : gd.elderSlots.lawEnforcementDisciples) {
        if (slot.discipleId.empty()) continue;
        if (const auto did = toIntOrNull(slot.discipleId)) {
            const auto it = idx.find(*did);
            if (it == idx.end()) continue;
            const int32_t intel = stats::baseIntelligence(ds, it->second);
            const int32_t above = intel > lawIntelligenceBase()
                                      ? intel - lawIntelligenceBase() : 0;
            captureRate += (static_cast<double>(above) /
                            lawDiscipleIntelligenceStep()) *
                           lawDiscipleBonusPerStep();
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
    double p = (lawLoyaltyThreshold() - loyal) * lawDesertionProbPerPoint();
    if (p < 0.0) p = 0.0;
    if (p > lawDesertionMaxProb()) p = lawDesertionMaxProb();
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
        std::to_string(currentYear + lawReflectionYears());
    state.disciples.appendDisciple(d);
    state.gameData.guideCounters["discipleImprisoned"] += 1;
    recordGameEvent(state, "SECT", "desertion_caught",
                    d.name + "企图叛逃，被执法堂捕获思过", d.id, d.name);
}

/// 逃脱清理：11 类槽位清空（含住所）→ 移除装备/功法实例与熟练度 → 移除弟子
/// + 年度计数 + 事件（Kotlin desertDiscipleCleanup / processTheftDesertionCleanup
/// 共体；忠诚复核对齐。月度叛逃路径 = "desertion"/"脱离宗门"，
/// 偷盗后叛逃路径 = "theft_desertion"/"偷盗后叛逃"）
inline void desertDiscipleCleanup(GameState& state, int32_t id, int32_t threshold,
                                  const std::map<int32_t, std::size_t>& idx,
                                  const std::string& eventType = "desertion",
                                  const std::string& summarySuffix = "脱离宗门") {
    const auto it = idx.find(id);
    if (it == idx.end()) return;
    const std::size_t row = it->second;
    // 储物袋条目随弟子存储独立删除，不收集袋条目 itemId（防误删仓库堆叠）
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
        // S5：gameData.activeMissions 升级为完整模型——清理 op 协议仍为
        // Lite（成员过滤仅需 id/两列表），toMissionLiteList/mergeMissionLiteList
        // 共享转换（slot_cleanup.h；语义与 Kotlin clearActiveMissions 全字段
        // copy 等价）
        in.activeMissions = toMissionLiteList(state.gameData.activeMissions);
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
        state.gameData.activeMissions =
            mergeMissionLiteList(state.gameData.activeMissions, out.activeMissions);
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
    // 弟子强化派生 map 统一收口（审计 P2-7/P3-4：原仅清 manualProficiencies，
    // 漏血炼三 map——叛逃弟子血炼加成随之残留）
    eraseDiscipleDerivedMaps(state.gameData, snapshot.id);

    state.disciples.removeById(snapshot.id);
    state.gameData.annualDesertedDisciples += 1;
    recordGameEvent(state, "SECT", eventType, snapshot.name + summarySuffix,
                    snapshot.id, snapshot.name);
}

/// 月度叛逃检测主流程（Kotlin processLawEnforcementMonthly）
/// WS-3 E2 迭代域：at-risk id 快照（sync + View
/// 行序，== Kotlin findAtRiskDiscipleIds 的入口快照序）+ 逐 id rowOf 现查。
/// Kotlin 侧即快照迭代（每名风险弟子恰检一次）；捕获路径 remove+末尾重插、
/// 逃脱路径 remove 均不扰动后续 id 的判定（行存在性重解析兜底）。
inline void processLawEnforcementMonthly(GameState& state,
                                         rng::RngManager& rng,
                                         ecs::World& world) {
    auto& ds = state.disciples;
    if (!isAverageLoyaltyLowEnough(ds, world)) return;
    const int32_t currentMonthValue =
        state.gameData.gameYear * 12 + state.gameData.gameMonth;
    const double captureRate = calculateCaptureRate(state, indexById(ds));
    auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    std::vector<int32_t> atRiskIds;
    {
        ecs::syncDiscipleEntities(world, ds.size());
        ecs::View<ecs::DiscipleRef> view(world.registry());
        view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
            const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
            if (ds.isAlive[row] != 1) return;
            if (isDesertionImmuneStatus(ds.statuses[row])) return;
            if (ds.loyalties[row] >= lawLoyaltyThreshold()) return;
            if (currentMonthValue - ds.recruitedMonths[row] <
                lawNewDiscipleProtectionMonths()) {
                return;
            }
            const int32_t id = toIntOrNull(ds.ids[row]).value_or(-1);
            if (id < 0) return;
            atRiskIds.push_back(id);
        });
    }
    for (const int32_t id : atRiskIds) {
        // 前序捕获（remove+重插）/逃脱（remove）移行——行存在性重解析
        // （B18-P2：本轮索引一次构建，同一轮内复用传参——见下方等价性论证）
        const auto freshIdx = indexById(ds);
        const auto rit = freshIdx.find(id);
        if (rit == freshIdx.end()) continue;
        const std::size_t row = rit->second;
        const double prob = calcDesertionProbability(ds.loyalties[row]);
        if (rngSystem.nextDouble() >= prob) continue;
        // 第二次抽取：捕获 vs 逃脱
        if (rngSystem.nextDouble() < captureRate) {
            // B18-P2：复用本轮索引（构建与调用之间无 ds 变更——变异发生在被调
            // 函数内部，只影响下一迭代的重解析需求）
            captureDiscipleForReflection(state, id, state.gameData.gameYear, freshIdx);
        } else {
            desertDiscipleCleanup(state, id, lawLoyaltyThreshold(), freshIdx);
        }
    }
}

// ── 子事件 3：月度偷盗兜底（Kotlin LawEnforcementProcessor.
//    processTheftIfNeeded → processTheftMonthly → processSingleDiscipleTheft
//    非事务版 → executeFullTheftCheck 全链等价移植）────────────────────
//
// 配置常量取 Kotlin GameConfig.LawEnforcementConfig / PolicyConfig 默认值
// （config() 远程配置可空覆盖）。
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
// 后续判定全部实时可见；生产真实 store 的 committed 快照读与此存在口径差
//（月变真相源切换时消除）。

// 偷盗配置（可远程覆盖段——Kotlin GameConfig.LawEnforcementConfig；
// PROB_PER_POINT/MAX_PROB 复用叛逃 getter）
inline int32_t lawMoralityThreshold() {
    return gamecore::gameConfig().lawMoralityThreshold;    // 30
}
inline int32_t lawMaxTheftPerYear() {
    return gamecore::gameConfig().lawMaxTheftPerYear;      // 3
}
inline int32_t lawMaxTheftJudgementsPerMonth() {
    return gamecore::gameConfig().lawMaxTheftJudgementsPerMonth;  // 3
}
// 政策：宵禁偷盗概率减免（GameConfig.PolicyConfig.CURFEW_EVENT_REDUCTION；
// const val 类）
constexpr double kCurfewEventReduction = 0.30;
// 境界基准偷盗量（等比 ×4，下标 = 弟子 realm 1..9；const val 类）
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
    double p = (lawMoralityThreshold() - morality) * lawDesertionProbPerPoint();
    if (p < 0.0) p = 0.0;
    if (p > lawDesertionMaxProb()) p = lawDesertionMaxProb();
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
    ds.markCol(DiscipleColumn::Status, row);   // R2 列级写屏障（偷盗链写点）
    auto& sd = ds.statusData[row];
    sd["reflectionStartYear"] = std::to_string(state.gameData.gameYear);
    sd["reflectionEndYear"] =
        std::to_string(state.gameData.gameYear + lawReflectionYears());
    ds.markCol(DiscipleColumn::StatusData, row);
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
    // 门控先于本次扣减生效；与基线读存在残余口径差）
    const int64_t stonesBeforeDeduction = gd.spiritStones;
    if (stolenAmount > 0) {
        gd.spiritStones = std::max(gd.spiritStones - stolenAmount, static_cast<int64_t>(0));
    }
    const std::vector<LootedItemEntry> stolenItems = selectTheftItems(
        thief, stonesBeforeDeduction, warehouses, garrisons, state.equipmentStacks,
        state.manualStacks, state.pills, state.materials, state.herbs,
        state.seeds, rngSystem);
    if (!stolenItems.empty()) applyStolenItemsToStores(state, stolenItems);
    // 弟子储物袋（容量无上限；条目 stackedData 缺省 = 未物化语义）
    auto& ds = state.disciples;
    const auto it = ds.idToRow.find(thief.id);
    if (it == ds.idToRow.end()) return;   // Kotlin firstOrNull null → return
    const std::size_t row = it->second;
    ds.storageBagSpiritStones[row] += stolenAmount;
    ds.markCol(DiscipleColumn::StorageBagSpiritStones, row);  // R2 列级写屏障
    for (const auto& item : stolenItems) {
        state::StorageBagItem entry;
        entry.itemId = item.id;
        entry.itemType = item.type;
        entry.name = item.name;
        entry.rarity = item.rarity;
        entry.quantity = item.count;
        entry.obtainedYear = gd.gameYear;
        entry.obtainedMonth = gd.gameMonth;
        // 审计 P2-8：统一入袋入口——满袋「不再拾取」（跳过剩余赃物，
        // 不销毁已有物品；灵石仍入袋不受条目容量影响）
        if (!addToDiscipleBagList(ds.storageBagItems[row], std::move(entry))) {
            break;
        }
    }
    ds.markCol(DiscipleColumn::StorageBagItems, row);   // R2 列级写屏障
    std::string itemSummary;
    if (!stolenItems.empty()) {
        itemSummary = "（含" + std::to_string(stolenItems.size()) + "种物品）";
    }
    recordGameEvent(state, "SECT", "warehouse_theft",
                    "宗门仓库被盗，损失" + std::to_string(stolenAmount) +
                        "灵石" + itemSummary);
    gd.annualTheftCount += 1;
}

/// 单弟子偷盗判定入口（提取自 Kotlin processSingleDiscipleTheft(id,
/// state) 事务内版——灵石检查 → canDiscipleAttemptTheft 复检（当前态）→
/// 标记判定（lastTheftJudgementYears + theftJudgementsThisMonth+1，先于概率
/// 抽取——未遂同计数）→ executeFullTheftCheck 完整链（偷盗概率 → 捕获 →
/// 仓库守卫 → 成功偷窃 → 偷后叛逃）。RNG 抽取序同月度兜底单候选（SYSTEM
/// 分区，①②③④⑤⑥）；行解析按 id 当前态重做（前序判定可能移除/改态）。
/// 供月度兜底（子事件 3）与教化之道钩子（步骤 2）共用。
inline void judgeSingleTheftCandidate(GameState& state, int32_t id,
                                      int32_t currentMonth,
                                      rng::DeterministicRng& rngSystem,
                                      ecs::World& world) {
    auto& gd = state.gameData;
    auto& ds = state.disciples;
    // 前置链（Kotlin canDiscipleAttemptTheft 顺序：从众门控 → 存活 → IDLE
    // → 保护期 → 年判定 → 月上限 → 年上限；灵石检查为 processSingleDisciple
    // Theft 首行）
    if (gd.spiritStones <= 0) return;
    if (!isAverageLoyaltyLowEnough(ds, world)) return;
    const auto freshIdx = indexById(ds);
    const auto rit = freshIdx.find(id);
    if (rit == freshIdx.end()) return;   // assemble null → 静默
    const std::size_t row = rit->second;
    if (ds.isAlive[row] != 1) return;
    if (ds.statuses[row] != "IDLE") return;
    if (currentMonth - ds.recruitedMonths[row] <
        lawNewDiscipleProtectionMonths()) {
        return;
    }
    if (ds.lastTheftJudgementYears[row] == gd.gameYear) return;
    if (gd.theftJudgementsThisMonth >= lawMaxTheftJudgementsPerMonth()) return;
    if (gd.annualTheftCount >= lawMaxTheftPerYear()) return;
    // 标记判定（先于概率抽取——尝试失败同样计数）
    gd.theftJudgementsThisMonth += 1;
    ds.lastTheftJudgementYears[row] = gd.gameYear;
    // executeFullTheftCheck（完整链，RNG 抽取序与 Kotlin 逐位一致）
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
    if (rngSystem.nextDouble() >= effectiveTheftProb) return;
    // Step 2: 执法堂判定——直接以抓捕率判定
    if (rngSystem.nextDouble() < captureRate) {
        captureDiscipleForTheft(state, thief);
        return;
    }
    // Step 3: 仓库驻守判定——纯智力比拼
    if (warehouseGarrisonCheck(state, thief, st.intelligence,
                               warehouses, gd.warehouseGarrisons,
                               rngSystem)) {
        return;
    }
    // 偷窃成功 → 执行（灵石 + 物品）
    executeSuccessfulTheft(state, thief, warehouses,
                           gd.warehouseGarrisons, rngSystem);
    // Step 4: 偷盗后叛逃判定（仅看忠诚；抽取无条件）
    const double desertionProb = calcDesertionProbability(st.loyalty);
    if (rngSystem.nextDouble() < desertionProb) {
        desertDiscipleCleanup(state, id, lawLoyaltyThreshold(),
                              indexById(ds), "theft_desertion",
                              "偷盗后叛逃");
    }
}

/// 月度偷盗兜底主流程（Kotlin processTheftIfNeeded → processTheftMonthly →
/// processSingleDiscipleTheft 非事务版全链；safelyRunInState("theft") 语义 =
/// 异常吞掉中止本子事件、保留已写入状态；单候选判定委托
/// [judgeSingleTheftCandidate]（语义零变更））
inline void processTheftMonthlyFallback(
    GameState& state, rng::RngManager& rng,
    const std::map<int32_t, std::size_t>& idx, ecs::World& world) {
    try {
        auto& gd = state.gameData;
        auto& ds = state.disciples;
        // ① 月度判定计数器归零（Kotlin 首行无条件 update）
        gd.theftJudgementsThisMonth = 0;
        if (gd.spiritStones <= 0) return;
        if (gd.annualTheftCount >= lawMaxTheftPerYear()) return;
        if (!isAverageLoyaltyLowEnough(ds, world)) return;
        // hasCandidate 门控（Kotlin 无保护期检查——与候选收集 deliberate 差异
        // 保留）。迭代域经 sync + View 行序。
        const int32_t currentYear = gd.gameYear;
        bool hasCandidate = false;
        {
            ecs::syncDiscipleEntities(world, ds.size());
            ecs::View<ecs::DiscipleRef> view(world.registry());
            view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
                if (hasCandidate) return;
                const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
                if (ds.isAlive[row] != 1) return;
                if (ds.statuses[row] != "IDLE") return;
                if (ds.moralities[row] >= lawMoralityThreshold()) return;
                if (ds.lastTheftJudgementYears[row] == currentYear) return;
                hasCandidate = true;
            });
        }
        if (!hasCandidate) return;
        // processTheftMonthly：候选收集（门控/灵石复检——Kotlin 结构性重复保留）
        if (gd.spiritStones <= 0) return;
        if (!isAverageLoyaltyLowEnough(ds, world)) return;
        const int32_t currentMonth = gd.gameYear * 12 + gd.gameMonth;
        std::vector<int32_t> candidateIds;   // Kotlin tables.ids（Int）行序
        {
            ecs::syncDiscipleEntities(world, ds.size());
            ecs::View<ecs::DiscipleRef> view(world.registry());
            view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
                const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
                if (ds.isAlive[row] != 1) return;
                if (ds.statuses[row] != "IDLE") return;
                if (ds.moralities[row] >= lawMoralityThreshold()) return;
                if (currentMonth - ds.recruitedMonths[row] <
                    lawNewDiscipleProtectionMonths()) {
                    return;
                }
                if (ds.lastTheftJudgementYears[row] == currentYear) return;
                const int32_t id = toIntOrNull(ds.ids[row]).value_or(-1);
                if (id < 0) return;
                candidateIds.push_back(id);
            });
        }
        auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
        // 候选 take(3)：无论判定成败均消耗候选名额
        const std::size_t judgeCount =
            std::min(candidateIds.size(),
                     static_cast<std::size_t>(lawMaxTheftJudgementsPerMonth()));
        for (std::size_t k = 0; k < judgeCount; ++k) {
            const int32_t id = candidateIds[k];
            // 前一候选叛逃会移除行——行存在性重解析（id 寻址等价）。
            // B18-P2：索引**一次构建**供同一表达式复用（原写法同一表达式内
            // 两次 indexById 重建，纯成本零语义）
            const auto idx = indexById(ds);
            if (idx.find(id) == idx.end()) continue;
            judgeSingleTheftCandidate(state, id, currentMonth, rngSystem, world);
        }
    } catch (const std::exception&) {
        // Kotlin safelyRunInState：异常吞掉，中止偷盗子事件、保留已写入状态
    }
}

// ── 子事件 12：附庸脱离检查（Kotlin VassalService.
//    processMonthlyBreakawayCheck 等价移植）──────────────────────────
//
// 全链读事务内 state（Kotlin 经 MutableGameState 重载——无口径差）；
// RNG 契约（对拍命门）：每份契约在"宗门存在且 AI 战力 > 0"门后恰抽
// SYSTEM nextDouble 1 次（< 脱离概率 → 契约移除 + 事件）；宗门已不存在
// → 无抽取直接移除（无事件，worldMapSects 查不到名字）；AI 战力 0 →
// 无抽取不脱离。
//
// 依赖协议扩容（本批）：aiSectDisciples（GameState 顶层 Map<String,
// List<Disciple>>，Kotlin GameData.aiSectDisciples @Transient 重型数据）/
// sectBattleRecords / VassalContract 修正为 Kotlin 真实形状（原占位结构
// 系早期误植，休眠未暴露）/ SectRelation.acquainted 补齐。
//
// 战力口径：SectCombatPowerCalculator.calculateSectPower = 存活弟子
// getPermanentBaseStats（血炼 null 口径）战力之和——玩家与 AI 同一公式。

/// 弟子战力（Kotlin calculateDisciplePower(aggregate, null)——永久基础属性）
inline int64_t sectPowerOfDisciple(const state::Disciple& d) {
    const auto st = stats::baseStats(d);
    return discipleCombatPower(st.physicalAttack, st.magicAttack, st.maxHp,
                               st.physicalDefense, st.magicDefense, st.speed);
}

/// 宗门总战力（Kotlin calculateSectPower：filter isAlive + sumOf Long）。
/// 迭代域经 sync + View<DiscipleRef> 行序
///（战力和归约与序无关，切换收益 = 复用同一不变量校验）。
inline int64_t calculateSectPower(const state::DiscipleStore& ds,
                                  ecs::World& world) {
    int64_t power = 0;
    ecs::syncDiscipleEntities(world, ds.size());
    ecs::View<ecs::DiscipleRef> view(world.registry());
    view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
        const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
        if (ds.isAlive[row] != 1) return;
        power += sectPowerOfDisciple(ds.materialize(row));
    });
    return power;
}

/// AI 宗门总战力（同一公式，作用于 aiSectDisciples 弟子列表）
inline int64_t calculateAiSectPower(
    const std::map<std::string, std::vector<state::Disciple>>& aiSectDisciples,
    const std::string& sectId) {
    const auto it = aiSectDisciples.find(sectId);
    if (it == aiSectDisciples.end()) return 0;
    int64_t power = 0;
    for (const auto& d : it->second) {
        if (!d.isAlive) continue;
        power += sectPowerOfDisciple(d);
    }
    return power;
}

/// 好感度查询（Kotlin FavorDomain.findRelation：双向匹配首条，缺失默认 50）
inline int32_t breakawayFavor(const std::vector<state::SectRelation>& sectRelations,
                              const std::string& playerSectId,
                              const std::string& vassalSectId) {
    for (const auto& r : sectRelations) {
        if ((r.sectId1 == playerSectId && r.sectId2 == vassalSectId) ||
            (r.sectId1 == vassalSectId && r.sectId2 == playerSectId)) {
            return r.favor;
        }
    }
    return 50;
}

/// 好感等级（Kotlin SectRelationLevel.fromFavor → ordinal 0..4；越界回 HOSTILE）
inline int32_t favorLevelOrdinal(int32_t favor) {
    if (favor >= 80) return 4;    // INTIMATE
    if (favor >= 60) return 3;    // FRIENDLY
    if (favor >= 40) return 2;    // NORMAL
    if (favor >= 20) return 1;    // ANTAGONISTIC
    return 0;                     // HOSTILE（含负值默认分支）
}

/// 单附属脱离判定（Kotlin checkSingleVassalBreakaway，true=脱离移除）
inline bool checkSingleVassalBreakaway(
    GameState& state, const state::VassalContract& contract,
    int64_t playerPower, const std::string& playerSectId,
    int32_t conquests, int32_t losses, int32_t battleWins, int32_t battleLosses,
    rng::DeterministicRng& rngSystem) {
    // 宗门已不存在 → 移除（无抽取；事件由调用方按 worldMapSects 查名，查不到不发）
    bool sectExists = false;
    for (const auto& s : state.gameData.worldMapSects) {
        if (s.id == contract.vassalSectId) { sectExists = true; break; }
    }
    if (!sectExists) return true;
    const int64_t aiPower =
        calculateAiSectPower(state.aiSectDisciples, contract.vassalSectId);
    if (aiPower <= 0) return false;
    // Kotlin powerRatio = playerPower(Long) / aiPower.toDouble()
    const double powerRatio = static_cast<double>(playerPower) /
                              static_cast<double>(aiPower);
    const double breakChance = sectBreakawayChance(
        powerRatio, conquests, losses, battleWins, battleLosses,
        favorLevelOrdinal(breakawayFavor(state.gameData.sectRelations,
                                         playerSectId, contract.vassalSectId)));
    return rngSystem.nextDouble() < breakChance;
}

/// 附庸脱离检查主流程（Kotlin processMonthlyBreakawayCheck）
inline void processVassalBreakaway(GameState& state, rng::RngManager& rng,
                                   ecs::World& world) {
    const auto& contracts = state.gameData.vassalContracts;
    if (contracts.empty()) return;
    // 玩家宗门（无 isPlayerSect 条目 → 纯早退，零抽取）
    const state::WorldSect* playerSect = nullptr;
    for (const auto& s : state.gameData.worldMapSects) {
        if (s.isPlayerSect) { playerSect = &s; break; }
    }
    if (playerSect == nullptr) return;
    // 近 3 年战报计数（year >= gameYear - 3）
    const int32_t minYear = state.gameData.gameYear - 3;
    int32_t conquests = 0, losses = 0, battleWins = 0, battleLosses = 0;
    for (const auto& r : state.gameData.sectBattleRecords) {
        if (r.year < minYear) continue;
        if (r.type == "CONQUEST") ++conquests;
        else if (r.type == "LOST_SECT") ++losses;
        else if (r.type == "BATTLE_WIN") ++battleWins;
        else if (r.type == "BATTLE_LOSS") ++battleLosses;
    }
    const int64_t playerPower = calculateSectPower(state.disciples, world);
    auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    std::vector<std::string> removedIds;
    for (const auto& contract : contracts) {
        if (checkSingleVassalBreakaway(state, contract, playerPower,
                                       playerSect->id, conquests, losses,
                                       battleWins, battleLosses, rngSystem)) {
            removedIds.push_back(contract.vassalSectId);
        }
    }
    if (removedIds.empty()) return;
    auto& remaining = state.gameData.vassalContracts;
    remaining.erase(std::remove_if(remaining.begin(), remaining.end(),
                                   [&](const state::VassalContract& c) {
                                       return std::find(removedIds.begin(),
                                                        removedIds.end(),
                                                        c.vassalSectId) !=
                                              removedIds.end();
                                   }),
                    remaining.end());
    for (const auto& sectId : removedIds) {
        for (const auto& s : state.gameData.worldMapSects) {
            if (s.id == sectId) {
                recordGameEvent(state, "WORLD", "vassal_breakaway",
                                s.name + "脱离了附属关系");
                break;
            }
        }
    }
}

/// 前向声明（子事件 6b：AI 攻玩家决策与防守战，P2-18 决策项③ Stage 1）。
/// 定义于文件尾（sect_defense_battle.h）——决策层 sect_attack_decision.h
/// 依赖本文件符号（kAiMinDisciplesForAttack/favorLevelOrdinal 等），
/// 只能在本文件完整定义之后包含，互包不可行。
inline void processAiPlayerDefenseSubEvent(GameState& state,
                                           rng::RngManager& rng);

/// 前向声明（子事件 6b：AI-vs-AI 征伐环，P2-18 决策项③ Stage 2）。
/// 定义于文件尾 sect_conquest.h；seizedSectBuildings = 玩家建筑没收草稿
///（MonthSettlementResult 平台效应，经 nativeSettleMonth 信封回传）。
inline void processAiConquestSubEvent(GameState& state,
                                      rng::RngManager& rng,
                                      std::vector<std::string>& seizedSectBuildings);

inline void processMonthlyEvents(GameState& state, rng::RngManager& rng,
                                 rng::DeterministicRng& aiRng,
                                 ai_ops::AiMonthBatchState& aiBatch,
                                 const std::map<int32_t, std::size_t>& idx,
                                 MonthSettlementResult& out,
                                 ecs::World& world) {
    // 子事件 1：招募月度计数归零
    state.gameData.recruitCountThisMonth = 0;
    // 子事件 2：自动招募（RecruitService.processAutoRecruit 等价移植；
    // 零 RNG——不扰动后续子事件的 SYSTEM 抽取序）
    recruit_settle::processAutoRecruit(state);
    // 子事件 3：月度偷盗兜底（Kotlin processTheftIfNeeded 全链，
    // 首行无条件归零 theftJudgementsThisMonth 已随行移植）
    detail::processTheftMonthlyFallback(state, rng, idx, world);
    // 子事件 4：月度叛逃检测
    detail::processLawEnforcementMonthly(state, rng, world);
    // 子事件 5：任务完成（MissionSystem.processMissionCompletion +
    //   CultivationEventMissionOps.processCompletedMissionsLazy 等价移植——
    //   MISSION/BATTLE/ENEMY_GEN 三分区；战斗组装
    //   createBattle/convertDiscipleToCombatant/EnemyGenerator 同入 C++，
    //   执行引擎 battle::executeBattle 既有 DiffBattle 家族对拍守护；
    //   编排位与 Kotlin 月变序一致，MISSION 抽取序不变）
    mission_settle::processCompletedMissions(state, rng);
    // 子事件 6：洞天 AI 操作（processAISectOperations 3 参版——
    //   仓库清场 + AI 弟子热控分批修炼（AI 独立 RNG 突破/补全） + 宗门等级
    //   同步 + 成员过滤；热控批量上界为平台效应由 Kotlin 推送，批状态机
    //   C++ 内存运行）
    ai_ops::aiProcessSectOperations(
        state, aiRng,
        ai_ops::aiComputeBatch(aiBatch,
                               state.gameData.gameYear * 12 + state.gameData.gameMonth));
    // 子事件 6b：AI-vs-AI 征伐环（P2-18 决策项③ Stage 2——Kotlin
    //   decideAttacks 编排/AI vs AI 战斗/AISectOccupationResolver 占领结算
    //   AUTHORITATIVE 复活。纯决策（战前快照守军）+ 收齐后统一应用两段式；
    //   BATTLE 分区：checkAttackConditions 门通过恰抽 1 次 nextDouble +
    //   executeAiBattle 全回合。建筑没收为平台效应草稿
    //   seizedSectBuildings → nativeSettleMonth 信封。实现于文件尾
    //   sect_conquest.h）
    processAiConquestSubEvent(state, rng, out.seizedSectBuildings);
    // 子事件 6c：AI 攻玩家决策与防守战（P2-18 决策项③ Stage 1——Kotlin
    //   PlayerDefenseProcessor 休眠链 AUTHORITATIVE 复活：旧档预警收敛/
    //   到期战书内联结算/新攻击决策+冷却写点/AI 占领宗门驻军填充。
    //   BATTLE 分区：decidePlayerAttack 六道闸通过者恰抽 1 次
    //   nextDouble + executeAiBattle 全回合。实现于文件尾 sect_defense_battle.h。
    //   置于 6b 征伐环之后——Kotlin 休眠 2 参链内序
    //   processAIVsAIBattles → processPlayerDefenseBattles）
    processAiPlayerDefenseSubEvent(state, rng);
    // 子事件 7：游戏结束检查
    detail::checkGameOverCondition(state);
    // 子事件 8：侦察信息过期清理
    detail::applyScoutInfoExpiry(state, state.gameData.gameYear,
                                 state.gameData.gameMonth);
    // 子事件 9：AI 兽战余量（processRemainingTargets 等价移植——
    //   兽战组装（preGenStats 妖兽/遭遇战 PvP）+ 击败标记 + 事件 + 死亡处理；
    //   战斗执行 BATTLE 分区——与 Kotlin BattleExecutionRouter 同分区）
    // 子事件 9 执行（置于 autoBuy 之前——Kotlin 子事件序 9 < 10）
    ai_ops::aiProcessRemainingTargets(state, rng.getRng(rng::RngPartition::kBattle));
    // 子事件 10：12 月自动购买（AutoBuyService.executeAutoBuy 等价移植；
    //   仅 month==12；全链零 RNG，回退分支确定性化）
    if (state.gameData.gameMonth == 12) {
        merchant_settle::executeAutoBuy(state);
    }
    // 子事件 11：灵矿月度产出结算
    detail::processSpiritMineProductionMonthly(state, idx);
    // 子事件 12：弟子智能购买（DisciplePurchaseService.executePurchase
    //   等价移植；SYSTEM 分区 shuffled——位于灵矿后、附庸前，与 Kotlin 月变
    //   编排相对序一致；购买日志草稿收集）
    disciple_purchase::processDisciplePurchase(state, rng, &out.purchaseLogs,
                                               world);
    // 子事件 13：附庸脱离检查
    detail::processVassalBreakaway(state, rng, world);
    // 子事件 14：任务刷新（CultivationEventMissionOps.
    //   processMissionRefreshIfDue 等价移植；month%3==0 才刷新，消费
    //   MISSION 分区——位于附庸后、秘境前，与 Kotlin 月变编排相对序一致）
    mission_settle::processMissionRefresh(state, rng);
    // 子事件 15：秘境现世期满自动关闭（钱包/背包/会话清场；
    //   邮件与 gate 保留 Kotlin——草稿收集：关闭发生时回传 memberIds +
    //   背包快照供 Kotlin 发邮件/释放 gate）
    {
        secret_realm_settle::SecretRealmCloseDraft closeDraft;
        secret_realm_settle::processMonthlyExpiryCheck(
            state, state.gameData.gameYear, &closeDraft);
        if (closeDraft.closed) {
            closeDraft.slotId = state.gameData.currentSlot;
            out.secretRealmClose = std::move(closeDraft);
        }
    }
    // 子事件 16：秘境 AI 队伍月度派遣（SecretRealmAIProcessor.
    //   processMonthlyAiTeams 等价移植；零 RNG）
    secret_realm_settle::processMonthlyAiTeams(state);
}

// ── 步骤 3：AI 兽袭目标预计算（Kotlin AISectBeastAttackProcessor.
//    precomputeTargets 等价移植；EXPLORATION 分区）──────────────────────
//
// 语义（逐条对齐 Kotlin 源码）：
// - 活跃妖兽 = worldLevels filter（type=="BEAST" && !defeated &&
//   !checkLevelExpired(year,month) && id !in lockedBeastIds）sortedBy id；
//   无活跃妖兽 → 纯早退（cleanExpiredSkipCooldowns 也不执行——对齐 Kotlin
//   `if (activeBeasts.isEmpty()) return`）
// - 每妖兽候选 = worldMapSects filter（!isPlayerSect && !isPlayerOccupied）
//   按欧氏距离（Float 精度 sqrt→toFloat）升序取前 2——std::stable_sort 对齐
//   Kotlin sortedBy 稳定序（相等距离保持 worldMapSects 原序）
// - 每候选门控序：冷却（>= absoluteMonth 跳过，含等号）→ AI 弟子池存在 →
//   存活数 >= kAiMinDisciplesForAttack（10）→ 战力比较：
//     beastPower <= 0 → 必攻（零抽取）；aiPower <= beastPower → 记冷却跳过；
//     否则抽 1 次 EXPLORATION nextDouble（prob = min((ratio-1)×0.3+0.3, 0.9)，
//     < prob 命中，否则记冷却跳过）
// - 命中宗门（≤2，去重）写入 aiSectBeastDirectTargets[beast.id]；
//   末尾清理超 12 月冷却记录（value < absoluteMonth-12 移除）
// - RNG 抽取序（对拍命门）：每妖兽 × 每候选宗门恰 1 次，位于妖兽移动
//   （步骤 4e moveBeasts）之前——步骤 3 先于步骤 4 执行
// - 快照语义：Kotlin `val gd = state.gameData` 为进入时值快照，recordSkipCooldown/
//   targets 写入不影响后续 beast 的读取——C++ 以 gdSnapshot 值拷贝对齐
//   （引用会读到本函数写入的冷却 → 后续 beast 错误跳过，行为漂移）

/// AI 攻妖兽概率基础倍率（Kotlin ATTACK_PROB_BASE_MULTIPLIER）
constexpr double kAiAttackProbBaseMultiplier = 0.3;
/// AI 攻妖兽概率上限（Kotlin ATTACK_PROB_CAP）
constexpr double kAiAttackProbCap = 0.9;
/// AI 进攻最低存活弟子数（GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK）
constexpr int32_t kAiMinDisciplesForAttack = 10;
/// 跳过攻击冷却期（月，Kotlin SKIP_COOLDOWN_MONTHS）
constexpr int32_t kAiSkipCooldownMonths = 12;

/// 记录 AI 宗门跳过冷却（Kotlin recordSkipCooldown：值=当前绝对月）
inline void recordBeastSkipCooldown(GameState& state, const std::string& sectId,
                                    int32_t absoluteMonth) {
    state.aiSectBeastSkipCooldowns[sectId] = absoluteMonth;
}

/// 清理超期冷却记录（Kotlin cleanExpiredSkipCooldowns：保留 value >= 绝对月-12；
///   仅当确有移除时写回，对齐 Kotlin size 比较）
inline void cleanExpiredBeastSkipCooldowns(GameState& state, int32_t absoluteMonth) {
    auto& cooldowns = state.aiSectBeastSkipCooldowns;
    const int32_t cutoff = absoluteMonth - kAiSkipCooldownMonths;
    std::map<std::string, int32_t> cleaned;
    for (const auto& [sectId, value] : cooldowns) {
        if (value >= cutoff) cleaned.emplace(sectId, value);
    }
    if (cleaned.size() < cooldowns.size()) {
        cooldowns = std::move(cleaned);
    }
}

/// 收集对指定妖兽有进攻资格的 AI 宗门 id 列表（最多 2 个，距离升序；
///   Kotlin collectQualifiedAiForBeast 等价移植）
inline std::vector<std::string> collectQualifiedAiForBeast(
    GameState& state, const state::GameData& gdSnapshot,
    const std::map<std::string, int32_t>& cooldownSnapshot,
    const state::WorldLevel& beast, rng::RngManager& rng,
    int32_t absoluteMonth) {
    struct Candidate {
        const state::WorldSect* sect;
        float dist;
    };
    std::vector<Candidate> candidates;
    for (const auto& sect : gdSnapshot.worldMapSects) {
        if (sect.isPlayerSect || sect.isPlayerOccupied) continue;
        const float dx = beast.x - sect.x;
        const float dy = beast.y - sect.y;
        const float dist = static_cast<float>(
            std::sqrt(static_cast<double>(dx * dx + dy * dy)));
        if (std::isnan(dist) || std::isinf(dist)) continue;
        candidates.push_back({&sect, dist});
    }
    // Kotlin sortedBy 稳定排序 → std::stable_sort（相等距离保持原序）
    std::stable_sort(candidates.begin(), candidates.end(),
                     [](const Candidate& a, const Candidate& b) {
                         return a.dist < b.dist;
                     });
    if (candidates.size() > 2) candidates.resize(2);

    std::vector<std::string> qualified;
    qualified.reserve(2);
    for (const auto& cand : candidates) {
        if (qualified.size() >= 2) break;
        const auto& sect = *cand.sect;
        const auto cooldownIt = cooldownSnapshot.find(sect.id);
        const int32_t cooldown = cooldownIt == cooldownSnapshot.end()
                                     ? 0 : cooldownIt->second;
        if (cooldown >= absoluteMonth) continue;

        const auto disciplesIt = state.aiSectDisciples.find(sect.id);
        if (disciplesIt == state.aiSectDisciples.end()) continue;
        int32_t aliveCount = 0;
        int64_t aiPower = 0;
        for (const auto& d : disciplesIt->second) {
            if (!d.isAlive) continue;
            ++aliveCount;
            aiPower += sectPowerOfDisciple(d);
        }
        if (aliveCount < kAiMinDisciplesForAttack) continue;

        const int64_t beastPower = beastCombatPower(
            beast.beastMaxHp, beast.beastPhysicalAttack, beast.beastMagicAttack,
            beast.beastPhysicalDefense, beast.beastMagicDefense, beast.beastSpeed);

        bool canAttack = false;
        if (beastPower <= 0) {
            canAttack = true;
        } else if (aiPower <= beastPower) {
            recordBeastSkipCooldown(state, sect.id, absoluteMonth);
        } else {
            const double ratio = static_cast<double>(aiPower) /
                                 static_cast<double>(beastPower);
            const double prob = std::min(
                (ratio - 1.0) * kAiAttackProbBaseMultiplier +
                    kAiAttackProbBaseMultiplier,
                kAiAttackProbCap);
            if (rng.getRng(rng::RngPartition::kExploration).nextDouble() < prob) {
                canAttack = true;
            } else {
                recordBeastSkipCooldown(state, sect.id, absoluteMonth);
            }
        }
        if (canAttack &&
            std::find(qualified.begin(), qualified.end(), sect.id) ==
                qualified.end()) {
            qualified.push_back(sect.id);
        }
    }
    return qualified;
}

/// 步骤 3：AI 兽袭目标预计算（Kotlin AISectBeastAttackProcessor.
///   precomputeTargets 等价移植；写入 aiSectBeastDirectTargets + 冷却清理）
inline void precomputeTargets(GameState& state, rng::RngManager& rng) {
    // Kotlin `val gd = state.gameData` 值快照语义（见上注释）：aiSectBeast*
    // 域为 GameState 顶层字段（Kotlin GameData @Transient），冷却快照同样
    // 值拷贝——recordSkipCooldown 写入不影响后续妖兽的冷却读取
    const state::GameData gdSnapshot = state.gameData;
    const std::map<std::string, int32_t> cooldownSnapshot =
        state.aiSectBeastSkipCooldowns;
    const int32_t year = gdSnapshot.gameYear;
    const int32_t month = gdSnapshot.gameMonth;

    std::vector<const state::WorldLevel*> activeBeasts;
    for (const auto& level : gdSnapshot.worldLevels) {
        if (level.type != "BEAST" || level.defeated ||
            checkLevelExpired(level, year, month)) {
            continue;
        }
        if (std::find(state.lockedBeastIds.begin(), state.lockedBeastIds.end(),
                      level.id) != state.lockedBeastIds.end()) {
            continue;
        }
        activeBeasts.push_back(&level);
    }
    if (activeBeasts.empty()) return;
    std::sort(activeBeasts.begin(), activeBeasts.end(),
              [](const state::WorldLevel* a, const state::WorldLevel* b) {
                  return a->id < b->id;
              });

    const int32_t absoluteMonth = year * 12 + month;
    for (const auto* beast : activeBeasts) {
        auto qualified = collectQualifiedAiForBeast(
            state, gdSnapshot, cooldownSnapshot, *beast, rng, absoluteMonth);
        if (!qualified.empty()) {
            state.aiSectBeastDirectTargets[beast->id] = std::move(qualified);
        }
    }
    cleanExpiredBeastSkipCooldowns(state, absoluteMonth);
}

}  // namespace detail

// 月变编排结果导出到 gamecore::system（runMonthSettlement / nativeSettleMonth 信封）
using detail::MonthSettlementResult;

// ── 主入口：月变结算（注册进 SettlementEngine::onMonthChange） ─────

/// 执行一次月变结算（时间推进与月界检测由 SettlementEngine 负责）。
/// @param state   完整状态（就地修改）
/// @param rng     RNG 分区管理器（EXPLORATION：妖兽移动；SYSTEM：收获 roll/伴侣配对）
/// @param aiRng   AI 独立 RNG（systemSeed + 6×31337 播种；子事件 6/9 消费）
/// @param aiBatch AI 热控分批内存态（批量上界由 Kotlin 推送平台热档）
/// @param world   ECS 实体集（月结域全部弟子迭代
///   经 syncDiscipleEntities 行序桥接——持久实体集由 GameCore 承载，测试
///   传临时 World 同构）
inline MonthSettlementResult runMonthSettlement(state::GameState& state,
                                                rng::RngManager& rng,
                                                rng::DeterministicRng& aiRng,
                                                ai_ops::AiMonthBatchState& aiBatch,
                                                ecs::World& world) {
    MonthSettlementResult out;
    const auto idx = detail::indexById(state.disciples);

    // 步骤 1：政策月度灵石扣除（计数经 sync + View 行序）
    {
        int32_t discipleCount = 0;
        int32_t huashenBelowCount = 0;
        const state::DiscipleStore& ds = state.disciples;
        ecs::syncDiscipleEntities(world, ds.size());
        ecs::View<ecs::DiscipleRef> view(world.registry());
        view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
            const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
            if (ds.isAlive[row] == 0) return;
            ++discipleCount;
            if (ds.realms[row] > 5) ++huashenBelowCount;   // realm 5=化神，>5=化神下
        });
        out.policyCosts = processPolicyCosts(state.gameData, discipleCount,
                                             huashenBelowCount);
    }

    // 步骤 2：政策月度忠诚/道德效果（教化之道低道德偷盗判定钩子——
    // SYSTEM 抽取内嵌弟子循环序，与 Kotlin 逐位一致）
    detail::processPolicyMonthlyEffects(state, rng, world);

    // 步骤 3：AI 兽袭目标预计算（precomputeTargets 等价移植；
    // 写入 aiSectBeastDirectTargets——巡视楼/子事件 9 消费方保留 Kotlin）
    detail::precomputeTargets(state, rng);

    // 步骤 4：七系统扇出（@SystemPriority 升序；稳定排序 Exploration(240)
    // 先于 Partner(240)，对齐 Dagger Set 注入序的现行生产行为）
    // 4a Alchemy(210) / 4b Forge(211)：炼丹/锻造完成结算（production.h——
    //   完成判定/成功率 roll（SYSTEM）/产出入库/职业
    //   晋升/槽位重置；RNG 抽取序 = forge 槽位序 → alchemy 槽位序，对齐
    //   Kotlin processBuildingProduction。autoRestart 续炼启动段（Kotlin 原
    //   月结事务提交后异步独立事务）置月结编排末尾执行——processAutoProductionStep）
    production::processBuildingProductionStep(state, rng);
    // 4c Planting(214)：灵田成熟收获 + 续种（SYSTEM 种子 roll）
    detail::processSpiritFieldHarvestStep(state, rng);
    // 4d ChildBirth(235)：生育（processMonthlyBirth 等价移植——
    //   child_birth.h；到期母亲逐人生育 + 新生儿入 recruitList + 自动招募
    //   惰性重置 + processAutoRecruit；SYSTEM 分区消费序逐位对齐）
    detail::processChildBirthStep(state, rng, world);
    // 4e Exploration(240)：世界关卡惰性管理（清理 + 刷新生成 + 移动；
    //   LevelGenerator 接线——shouldRefresh 判定 + 玩家
    //   宗门门控 + playerAvgRealm 安全兜底 + 生成 + lastRefreshMonth 推进）
    {
        auto& wl = state.gameData.worldLevels;
        const int32_t absMonth = state.gameData.gameYear * 12 +
                                 state.gameData.gameMonth;
        const bool shouldRefresh =
            state.gameData.worldLevelLastRefreshMonth == 0 ||
            (absMonth - state.gameData.worldLevelLastRefreshMonth) >=
                kLevelRefreshIntervalMonths;
        if (shouldRefresh) {
            bool hasPlayerSect = false;
            for (const auto& sect : state.gameData.worldMapSects) {
                if (sect.isPlayerSect) {
                    hasPlayerSect = true;
                    break;
                }
            }
            if (hasPlayerSect) {
                // 清理过期（Kotlin 步骤 1：每月都做，早于刷新判定）
                auto remaining =
                    filterExpiredLevels(wl, state.gameData.gameYear,
                                        state.gameData.gameMonth);
                // playerAvgRealm：存活弟子平均境界 toInt（Kotlin assembleAll
                // filter isAlive → average().toInt()；无存活 → null）。
                // WS-3 E2 行序桥接：计数经 sync + View 行序
                int32_t avgRealm = 0;
                int32_t aliveCount = 0;
                double realmSum = 0.0;
                {
                    const state::DiscipleStore& dsw = state.disciples;
                    ecs::syncDiscipleEntities(world, dsw.size());
                    ecs::View<ecs::DiscipleRef> viewAvg(world.registry());
                    viewAvg.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
                        const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
                        if (dsw.isAlive[row] != 1) return;
                        realmSum += static_cast<double>(dsw.realms[row]);
                        ++aliveCount;
                    });
                }
                const int32_t* avgRealmPtr =
                    aliveCount > 0 ? &avgRealm : nullptr;
                if (avgRealmPtr) {
                    avgRealm = static_cast<int32_t>(realmSum / aliveCount);
                }
                const auto generated = generateWorldLevels(
                    rng, state.gameData.worldMapSects,
                    state.gameData.gameYear, state.gameData.gameMonth,
                    remaining, kMaxNewLevelsDefault, avgRealmPtr);
                auto merged = remaining;
                for (auto& l : generated.levels) merged.push_back(std::move(l));
                // 步骤 3：妖兽移动（Kotlin moveBeasts 在刷新后统一执行）
                state.gameData.worldLevels =
                    moveBeasts(merged, state.gameData.gameYear,
                               state.gameData.gameMonth, rng);
                state.gameData.worldLevelLastRefreshMonth = absMonth;
            } else {
                // 无玩家宗门：只清理不生成不推进（Kotlin 提前 return 分支；
                // moveBeasts 随后在 else 统一执行）
                auto remaining =
                    filterExpiredLevels(wl, state.gameData.gameYear,
                                        state.gameData.gameMonth);
                state.gameData.worldLevels =
                    moveBeasts(remaining, state.gameData.gameYear,
                               state.gameData.gameMonth, rng);
            }
        } else {
            // 非刷新月：清理 + 移动（原 processWorldLevelsMonthly 语义）
            const auto monthly = processWorldLevelsMonthly(
                wl, state.gameData.worldLevelLastRefreshMonth,
                state.gameData.gameYear, state.gameData.gameMonth,
                rng, /*allowRefresh=*/false);
            state.gameData.worldLevels = std::move(monthly.levels);
        }
    }
    // 巡视楼战斗 / 妖兽攻击检测：战斗域未下沉；场景 patrolSlots 为空 +
    // 无玩家宗门 → 双端纯早退零抽取
    // 4f Partner(240)：道侣配对（SYSTEM 配对概率抽卡）
    detail::processPartnerMatching(state, rng, idx, world);
    // 步骤 5：血炼完成检测
    detail::processBloodRefinementCompletions(state, idx);

    // 步骤 6：月度自动排班（processAutoAssign 等价移植——零 RNG
    // 纯数据变换，政策全关纯早退）+ 住所忠诚
    detail::processAutoAssign(state, world);
    detail::processResidenceLoyalty(state, world);

    // 步骤 7：丹药持续效果月度衰减
    detail::applyMonthlyDurationDecayAll(state, world);

    // 步骤 8：月度事件（十六子事件 + 草稿收集）
    detail::processMonthlyEvents(state, rng, aiRng, aiBatch, idx, out, world);

    // 步骤 9：自动排班（autoRestart 续炼启动；Kotlin processAutoAlchemy/
    // processAutoForge 为月结事务提交后异步独立事务——读取月结最终状态，C++ 置
    // 编排末尾等价对齐；零 RNG 不扰动抽取序）
    production::processAutoProductionStep(state);

    return out;
}

}  // namespace gamecore::system

// ── 子事件 6b 实现（P2-18 决策项③ Stage 1：AI 攻玩家决策与防守战）──
// sect_attack_decision.h 依赖本文件符号（detail::kAiMinDisciplesForAttack/
// favorLevelOrdinal/sectPowerOfDisciple），只能在文件尾包含；
// sect_defense_battle.h 同理依赖 sect_attack_decision.h——包含链：
// month_settlement.h（本文件，子事件分发段前向声明）→ sect_attack_decision.h
// → sect_defense_battle.h（processAiPlayerDefenseSubEvent 定义）。
//
// 特例：若本翻译单元以 sect_attack_decision.h 为**首包含**（如
// GameCoreBridge.cpp 对拍桥——此时本文件正被其嵌套解析、其决策符号
// 尚未定义），跳过 sect_defense_battle.h 的解析，由该 TU 在
// sect_attack_decision.h 完成后自行包含（其不用月结函数则无需包含）。
#include "gamecore/system/sect_attack_decision.h"
#ifdef GAMECORE_SYSTEM_SECT_ATTACK_DECISION_COMPLETED_
#include "gamecore/system/sect_defense_battle.h"
#endif
#ifdef GAMECORE_SYSTEM_SECT_DEFENSE_BATTLE_COMPLETED_
#include "gamecore/system/sect_conquest.h"
#endif
