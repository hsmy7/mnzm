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
#include "gamecore/system/time_units.h"  // B6 连续轨换算常量（kGameSecondsPer*）
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
// 月变双臂（B6 拆分后现状；语义权威 = 各被调方法源码）：
//   - runMonthSettlement（离散臂，shadow/回滚基准）：八步全量——
//     1. 政策月度灵石扣除      ← government.h::processPolicyCosts（原语接线）
//     2. 政策月度道德效果      ← CultivationSettlement.processPolicyMonthlyEffects
//     3. AI 兽袭目标预计算     ← precomputeTargets（EXPLORATION）
//     4. 四系统扇出（@SystemPriority 升序）：
//        Alchemy(210) → Forge(211) → Planting(214) → Exploration(240)
//        （Mail(960) 已移除——在线邮件月度拉取通道下线，Kotlin MailSystem 删除）
//     5. 月度自动排班          ← processAutoAssign
//     6. 丹药持续效果月度衰减  ← HpMpRecoveryService.applyMonthlyDurationDecay
//     7. processMonthlyEvents 15 项子事件（全部入 C++，相对序与 Kotlin 一致）
//     8. 自动续炼启动          ← production.h autoRestart
//   - runMonthEvents（连续臂，生产 AUTHORITATIVE）：= 离散臂去积分型四项，
//     仅判定步 3/4/5/7/8（政策灵石/道德/丹药衰减/灵矿由连续轨承担）。
//
// RNG 消耗点核对表（分区 / 触发条件 / 抽取次数——对拍命门，逐点核对自源码）：
//   - EXPLORATION：妖兽移动 moveBeasts，每活跃妖兽 2 次 nextDouble（角度+距离）
//   - SYSTEM：灵田收获种子 roll nextInt(5)，每收获地块 1 次
//   - BATTLE：子事件 6b 征伐环（P2-18 Stage 2）checkAttackConditions
//     门通过恰抽 1 次 nextDouble + executeAiBattle 全回合抽取；
//     子事件 6c 防守环（P2-18 Stage 1）decidePlayerAttack 六道闸通过者
//     恰抽 1 次 nextDouble + 到期战书 executeAiBattle 全回合抽取
//   - BREAKTHROUGH：本钩子零消耗（旬结算是 BREAKTHROUGH 唯一入口）
//   已知未下沉扇出的抽取点（场景规避 + 边界登记）：
//   AI 兽袭 EXPLORATION（precomputeTargets 已入本钩子）、关卡刷新生成
//   （LevelGenerator 接线）、生产完成 SYSTEM（炼丹/锻造同步段）、
//   购买/附庸/商人等 SYSTEM 子事件均已入 C++。
//
// 已知范围边界：
//   - precomputeTargets：aiSectBeastDirectTargets/aiSectBeastSkipCooldowns/
//     lockedBeastIds 入 GameState 顶层快照协议——Kotlin 同名 GameData 字段
//     @Transient 纯运行态；消费方巡视楼/子事件 9 保留 Kotlin
//   - 关卡刷新生成：LevelGenerator 生成 + playerAvgRealm 兜底 +
//     lastRefreshMonth 推进；巡视楼战斗/妖兽攻击检测仍战斗域 Kotlin，
//     场景 patrolSlots 为空 + 无玩家宗门 → 检测/巡视纯早退
//   - 炼丹/锻造自动排班与完成结算（Room 仓储/物品数据库域）：
//     场景无到期槽位且自动政策全关；ForgeSystem 为异步 launch（事务内零效果）
//   - 自动排班：11 槽占用扫描 + 住所分配 + 四类生产候选 + 原子写入
//     （住所建筑表静态数据 + 双端守卫）
//   - 子事件：recruitCountThisMonth 归零 / 灵矿月产 / gameOverCheck /
//     scoutExpiry / 附庸脱离检查 /
//     秘境到期关闭+AI 队伍派遣 / 12 月自动购买 /
//     弟子智能购买 / 任务刷新均已入 C++
//
// B6 拆分（结算改造 2026-09-27 §10）：连续臂（realtimeAccrual）下本编排的
// **积分型四项**（步骤 1 政策灵石 / 步骤 2 道德 / 步骤 6 丹药衰减 /
// 子事件 11 灵矿）由 accrueMonthlyContinuous 按游戏秒连续承担，月界走
// runMonthEvents（判定入口，跳过四项防双计）——见文件尾「B6 月度连续
// 积分轨」段；离散臂（旧行为/对拍基准）本编排逐位不变。
// ============================================================
namespace gamecore::system {

/// 丹药月度衰减旬数（每月 3 旬；HpMpRecoveryService.applyMonthlyDurationDecay）
constexpr int32_t kMonthlyDecayPhases = 3;
// 道德上限与月度增量常量单一定义于 government.h
//（kMoralEducationMax/kMoralEducationPerMonth）

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

// ── 步骤 2：政策月度道德效果 ──────────────────────────────────────
// （CultivationSettlement.processPolicyMonthlyEffects：单次遍历合并净变化）

inline void processPolicyMonthlyEffects(GameState& state, ecs::World& world) {
    const GameData& gd = state.gameData;
    const auto& policies = gd.sectPolicies;
    if (!policies.moralEducation) return;
    DiscipleStore& ds = state.disciples;
    // 迭代域经 sync + View 行序（行序 == Kotlin tables.ids 序）
    ecs::syncDiscipleEntities(world, ds.size());
    ecs::View<ecs::DiscipleRef> view(world.registry());
    view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
        const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
        if (ds.isAlive[row] == 0) return;
        // 道德（教化之道）：仅当前低于上限时 +1 并 clamp
        if (ds.moralities[row] >= kMoralEducationMax) return;
        ds.moralities[row] = std::max(
            0, std::min(kMoralEducationMax,
                        ds.moralities[row] + kMoralEducationPerMonth));
    });
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

// ── 步骤 6a：月度自动排班（Kotlin ProductionProcessor.
//    processAutoAssign 等价移植；零 RNG 纯数据变换）────────────────────
// 语义（对齐 Kotlin 源码）：
//   occupiedIds = 10 槽占用弟子（长老/灵矿/藏经阁/仓库驻守/巡视/宗门驻守/
//     战斗队伍/活跃任务/秘境/洞穴活跃队伍/生产槽）
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

/// 长老槽位占用弟子（Kotlin collectElderSlotDiscipleIds：9 单槽 + 6 列表）
inline void collectElderSlotDiscipleIds(const state::ElderSlots& es,
                                        std::set<std::string>& out) {
    const std::string* singles[] = {
        &es.viceSectMaster, &es.herbGardenElder, &es.alchemyElder,
        &es.forgeElder, &es.outerElder, &es.preachingElder,
        &es.innerElder, &es.qingyunPreachingElder,
        &es.recruitingElder,
    };
    for (const auto* s : singles) {
        if (!s->empty()) out.insert(*s);
    }
    const std::vector<state::DirectDiscipleSlot>* lists[] = {
        &es.preachingMasters,
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

/// 10 槽占用弟子 ID 收集（Kotlin buildOccupiedSlotDiscipleIds）
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

/// 丹药加成全清（applyMonthlyDurationDecay 清零分支提炼——离散月衰减与
/// B6 连续衰减共用同一清零面，防双份漂移）
inline void clearPillEffectBonuses(DiscipleStore& ds, std::size_t row) {
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
}

/// 单弟子月衰减（DiscipleStore 行版，列直写；语义与 Disciple& 版一致）
inline void applyMonthlyDurationDecay(DiscipleStore& ds, std::size_t row) {
    if (ds.pillEffectDurations[row] <= 0) return;
    const int32_t newDuration = ds.pillEffectDurations[row] - kMonthlyDecayPhases;
    if (newDuration <= 0) {
        clearPillEffectBonuses(ds, row);
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

/// 灵矿月产结算主体：乘区构建 → 差分产出入账（钱包 Mine 来源）→
/// 引导计数 → lastSettledMonth 推进
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
// Kotlin 月度子事件全序（15 项，实现编号 1/5/6/6b/6c/7/8/9/10/11/12/13/14/15/16
// ——2/3/4 为历史编号空洞；autoRecruit 已随招募链下线不在其列）：
// recruitReset → completedMissions → aiSectOperations → aiConquest(6b) →
// aiPlayerDefense(6c) → gameOverCheck → scoutExpiry → aiBeastRemaining →
// [12月 autoBuy] → spiritMine → disciplePurchase → vassalBreakaway →
// missionRefresh → secretRealmExpiry → secretRealmAiTeams。
// 15 项子事件均已入 C++（详见 processMonthlyEvents 分发段）；相对序与 Kotlin 一致。

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
// getPermanentBaseStats 战力之和——玩家与 AI 同一公式。

/// 弟子战力（Kotlin calculateDisciplePower——永久基础属性 + 星级乘区）
/// gd 用于反查 `gachaStarMap[templateId]`：AI 弟子与存量旧弟子 templateId 为空
/// ⇒ 0 星 ⇒ 恒 ×1.00（口径 A，无需为它们特判）
inline int64_t sectPowerOfDisciple(const state::Disciple& d, const state::GameData& gd) {
    const auto st = stats::baseStats(d);
    return discipleCombatPowerWithStar(st.physicalAttack, st.magicAttack, st.maxHp,
                                       st.physicalDefense, st.magicDefense, st.speed,
                                       resolveStar(gd, d.templateId));
}

/// 宗门总战力（Kotlin calculateSectPower：filter isAlive + sumOf Long）。
/// 迭代域经 sync + View<DiscipleRef> 行序
///（战力和归约与序无关，切换收益 = 复用同一不变量校验）。
inline int64_t calculateSectPower(const state::DiscipleStore& ds,
                                  ecs::World& world,
                                  const state::GameData& gd) {
    int64_t power = 0;
    ecs::syncDiscipleEntities(world, ds.size());
    ecs::View<ecs::DiscipleRef> view(world.registry());
    view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
        const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
        if (ds.isAlive[row] != 1) return;
        power += sectPowerOfDisciple(ds.materialize(row), gd);
    });
    return power;
}

/// AI 宗门总战力（同一公式，作用于 aiSectDisciples 弟子列表）
inline int64_t calculateAiSectPower(
    const std::map<std::string, std::vector<state::Disciple>>& aiSectDisciples,
    const std::string& sectId, const state::GameData& gd) {
    const auto it = aiSectDisciples.find(sectId);
    if (it == aiSectDisciples.end()) return 0;
    int64_t power = 0;
    for (const auto& d : it->second) {
        if (!d.isAlive) continue;
        power += sectPowerOfDisciple(d, gd);
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
        calculateAiSectPower(state.aiSectDisciples, contract.vassalSectId, state.gameData);
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
    const int64_t playerPower = calculateSectPower(state.disciples, world, state.gameData);
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
                                 ecs::World& world,
                                 bool settleSpiritMineMonthly = true) {
    // 子事件 1：招募月度计数归零
    state.gameData.recruitCountThisMonth = 0;
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
    // 子事件 11：灵矿月度产出结算（零 RNG 差分入账）。B6 连续臂
    //（settleSpiritMineMonthly=false）由 accrueMonthlyContinuous 按毫秒差分
    // 连续承担——此处仅同步旧月字段投影，保双臂切换差分基准新鲜
    //（离散臂回滚时不重复结算已入账月份）。
    if (settleSpiritMineMonthly) {
        detail::processSpiritMineProductionMonthly(state, idx);
    } else {
        state.gameData.spiritMineLastSettledMonth =
            toAbsoluteMonth(state.gameData.gameYear, state.gameData.gameMonth);
    }
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
            aiPower += sectPowerOfDisciple(d, state.gameData);
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
/// @param rng     RNG 分区管理器（EXPLORATION：妖兽移动；SYSTEM：收获 roll）
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

    // 步骤 2：政策月度道德效果（教化之道 +1 clamp；零 RNG）
    detail::processPolicyMonthlyEffects(state, world);

    // 步骤 3：AI 兽袭目标预计算（precomputeTargets 等价移植；
    // 写入 aiSectBeastDirectTargets——巡视楼/子事件 9 消费方保留 Kotlin）
    detail::precomputeTargets(state, rng);

    // 步骤 4：四系统扇出（@SystemPriority 升序）
    // 4a Alchemy(210) / 4b Forge(211)：炼丹/锻造完成结算（production.h——
    //   完成判定/成功率 roll（SYSTEM）/产出入库/职业
    //   晋升/槽位重置；RNG 抽取序 = forge 槽位序 → alchemy 槽位序，对齐
    //   Kotlin processBuildingProduction。autoRestart 续炼启动段（Kotlin 原
    //   月结事务提交后异步独立事务）置月结编排末尾执行——processAutoProductionStep）
    production::processBuildingProductionStep(state, rng);
    // 4c Planting(214)：灵田成熟收获 + 续种（SYSTEM 种子 roll）
    detail::processSpiritFieldHarvestStep(state, rng);
    // 4d Exploration(240)：世界关卡惰性管理（清理 + 刷新生成 + 移动；
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

    // 步骤 5：月度自动排班（processAutoAssign 等价移植——零 RNG
    // 纯数据变换，政策全关纯早退）
    detail::processAutoAssign(state, world);

    // 步骤 6：丹药持续效果月度衰减
    detail::applyMonthlyDurationDecayAll(state, world);

    // 步骤 7：月度事件（15 项子事件 + 草稿收集）
    detail::processMonthlyEvents(state, rng, aiRng, aiBatch, idx, out, world);

    // 步骤 8：自动排班（autoRestart 续炼启动；Kotlin processAutoAlchemy/
    // processAutoForge 为月结事务提交后异步独立事务——读取月结最终状态，C++ 置
    // 编排末尾等价对齐；零 RNG 不扰动抽取序）
    production::processAutoProductionStep(state);

    return out;
}

// ════════════════════════════════════════════════════════════════
// B6 月度连续积分轨（结算改造 2026-09-27 §10：积分型析出至 B4 连续轨）
// ════════════════════════════════════════════════════════════════
//
// 连续臂（NativeEngineFlag.realtimeAccrual，默认 false）下，月结八步中的
// **积分型四项**改由 [accrueMonthlyContinuous] 按游戏秒连续承担：
//   1. 政策月度灵石扣除（费率 = 月费 ÷ 6.0 灵石/游戏秒，INV-6 小数累积进位）
//   2. 政策月度道德效果（教化之道 +1/月 → 1/6 点/游戏秒，clamp 70）
//   6. 丹药持续效果衰减（−3 旬/月 → 0.5 旬/游戏秒，≤0 清零）
//   子事件 11 灵矿月产（月差分 → spiritMineLastSettledGameMs 毫秒差分）
// 四项全部零 RNG → [runMonthEvents]（连续臂月界判定入口）与
// [runMonthSettlement]（离散臂）的判定项次数与 RNG 消耗序逐位一致。
//
// carry 均为运行态（GameCore 成员，不入档）：重载后损失 ≤1 tick 的累积量，
// 数值可忽略；语义与 B4 RecoveryCarry 同型。

/// 连续月度积分轨运行态累积器。
/// 进位口径（B6 整数分子制——零浮点，总量逐位守恒）：
/// 分子 += 月速率 × Δms；整除月长（kGameMsPerMonth=6000，丹药旬为
/// kGameMsPerPhase=2000）即进位，余数保留——与 INV-6 小数累积进位同义，
/// 乘除全程整数无 ulp 漂移。
struct MonthlyAccrualCarry {
    /// 政策名 → 分子累积（Δms × 月费，毫秒·灵石）
    std::map<std::string, int64_t> policyCosts;
    /// 弟子数值 id → Δms 累积（道德；进位点 = 月长）
    std::map<int32_t, int64_t> morality;
    /// 弟子数值 id → Δms 累积（丹药衰减；进位点 = 旬长）
    std::map<int32_t, int64_t> pillDecay;
    /// 灵矿产出分子累积（Δms × 月产，毫秒·灵石）
    int64_t spiritMine = 0;
    /// 连续轨禁用政策累积（月界经 runMonthEvents 信封回传 Kotlin
    /// checkpointAllProduction——离散臂为月结内即时禁用，连续臂延迟至月界上报）
    std::vector<std::string> disabledPolicies;
};

/// 单弟子连续道德累积（教化之道：kMoralEducationPerMonth 点/月长；
/// clamp kMoralEducationMax）
inline void accrueContinuousMorality(state::DiscipleStore& ds, std::size_t row,
                                     int32_t numericId, int64_t deltaGameMs,
                                     MonthlyAccrualCarry& carry) {
    if (ds.moralities[row] >= kMoralEducationMax) {
        carry.morality[numericId] = 0;   // 满值清残留（满后新空间从零起）
        return;
    }
    int64_t& c = carry.morality[numericId];
    c += deltaGameMs * kMoralEducationPerMonth;
    const int64_t whole = c / kGameMsPerMonth;
    if (whole > 0) {
        c %= kGameMsPerMonth;
        ds.moralities[row] = std::min(
            kMoralEducationMax,
            ds.moralities[row] + static_cast<int32_t>(whole));
        ds.markCol(state::DiscipleColumn::Morality, row);
        if (ds.moralities[row] >= kMoralEducationMax) c = 0;
    }
}

/// 单弟子连续丹药衰减（1 旬/旬长 = 0.5 旬/游戏秒；≤0 清零全部加成）
inline void accrueContinuousPillDecay(state::DiscipleStore& ds, std::size_t row,
                                      int32_t numericId, int64_t deltaGameMs,
                                      MonthlyAccrualCarry& carry) {
    if (ds.pillEffectDurations[row] <= 0) {
        carry.pillDecay[numericId] = 0;   // 清残留——新服丹药从满时长起算
        return;
    }
    int64_t& c = carry.pillDecay[numericId];
    c += deltaGameMs;
    const int64_t whole = c / kGameMsPerPhase;
    if (whole <= 0) return;
    c %= kGameMsPerPhase;
    const int32_t newDuration =
        ds.pillEffectDurations[row] - static_cast<int32_t>(whole);
    if (newDuration <= 0) {
        detail::clearPillEffectBonuses(ds, row);
        c = 0;
    } else {
        ds.pillEffectDurations[row] = newDuration;
        ds.markCol(state::DiscipleColumn::PillEffectDuration, row);
    }
}

/// 月度积分项连续承担体（连续臂每 tick 调用；方案 §2.4「积分型析出至
/// accrueContinuous」的 L3 执行面）。
///
/// 迭代域 = DiscipleStore 行序（与 accrueContinuous 契约一致，不用 ECS View）；
/// 弟子计数（政策按弟子数费率）与道德/丹药衰减共享单次遍历。
/// 灵矿差分用 GameData.elapsedGameMs（旬粒度权威轴投影，advancePhase 单点
/// 回写）——调用点须位于判定窗口循环**之后**（本 tick 旬推进已入投影）。
inline void accrueMonthlyContinuous(state::GameState& state, int64_t deltaGameMs,
                                    MonthlyAccrualCarry& carry) {
    if (deltaGameMs <= 0) return;
    auto& gd = state.gameData;
    auto& policies = gd.sectPolicies;
    state::DiscipleStore& ds = state.disciples;

    // ── 弟子域单次遍历：计数（政策费率）+ 道德 + 丹药衰减 ──────────
    int32_t discipleCount = 0;
    int32_t huashenBelowCount = 0;
    const std::size_t rowCount = ds.size();
    for (std::size_t row = 0; row < rowCount; ++row) {
        if (ds.isAlive[row] == 0) continue;
        ++discipleCount;
        if (ds.realms[row] > 5) ++huashenBelowCount;   // realm 5=化神，>5=化神下
        const auto id = ds.numericIdAt(row);
        if (!id.has_value()) continue;
        if (policies.moralEducation) {
            accrueContinuousMorality(ds, row, *id, deltaGameMs, carry);
        }
        accrueContinuousPillDecay(ds, row, *id, deltaGameMs, carry);
    }

    // ── 政策月度灵石连续扣（费率 = 月费/月长，整数分子制；不足 → 关政策 +
    //    清 carry，禁用名单累积至月界信封上报）────────────────────────
    std::size_t tableSize = 0;
    const PolicyCostEntry* table = policyCostTable(tableSize);
    for (std::size_t i = 0; i < tableSize; ++i) {
        const PolicyCostEntry& entry = table[i];
        if (!entry.enabled(policies)) {
            carry.policyCosts[entry.name] = 0;   // 关闭期不积累（重开从零起）
            continue;
        }
        const int64_t monthlyCost =
            policyCostOf(entry, discipleCount, huashenBelowCount);
        if (monthlyCost <= 0) continue;
        int64_t& c = carry.policyCosts[entry.name];
        c += deltaGameMs * monthlyCost;
        const int64_t whole = c / kGameMsPerMonth;      // 应扣灵石数
        if (whole <= 0) continue;
        c %= kGameMsPerMonth;
        const auto r = SpiritStoneWallet::deduct(gd, whole, SpiritStoneGrade::LOW,
                                                 "PolicyCost", "Internal", true);
        if (r.status == DeductStatus::kSuccess) {
            // 已扣成功——carry 保留余数即可
        } else {
            entry.disable(policies);
            c = 0;
            carry.disabledPolicies.push_back(entry.name);
        }
    }

    // ── 灵矿月产连续差分（spiritMineLastSettledGameMs 毫秒差分，INV-2
    //    未截断；乘区实时构建——矿工/执事/政策变化即时反映产出）────────
    const int64_t nowGameMs = gd.elapsedGameMs;
    int64_t lastMs = gd.spiritMineLastSettledGameMs;
    if (lastMs < 0) lastMs = 0;
    const int64_t spanMs = nowGameMs - lastMs;
    if (spanMs > 0) {
        const SpiritMineZones zones =
            detail::buildMonthSpiritMineZones(state, ds.numericIdToRow);
        const int64_t monthlyRate = calculateSpiritMineMonthly(
            zones, kSpiritMineBaseOutputPerMiner);
        if (monthlyRate > 0) {
            carry.spiritMine += spanMs * monthlyRate;
            const int64_t whole = carry.spiritMine / kGameMsPerMonth;
            if (whole > 0) {
                carry.spiritMine %= kGameMsPerMonth;
                SpiritStoneWallet::add(gd, whole, SpiritStoneGrade::LOW, "Mine");
                int64_t& counter = gd.guideCounters["miningOutput"];
                counter += whole;
            }
        }
        gd.spiritMineLastSettledGameMs = nowGameMs;
    }
}

/// 月变判定入口（B6 连续臂专用：= [runMonthSettlement] 去积分型四项）。
/// 步骤 1（政策灵石）/2（道德）/6（丹药衰减）删除（连续轨承担）；
/// 子事件 11 灵矿改旧月字段投影同步（settleSpiritMineMonthly=false）。
/// 判定项（3/4/5/7/8）相对序与 [runMonthSettlement] 逐位一致——四项均为
/// 零 RNG，月判定 RNG 消耗序不变（B6 验收）。
/// 政策禁用上报：连续轨累积的 disabledPolicies（carry 由 GameCore 传入）
/// 填入信封 policyCosts——Kotlin 侧 checkpointAllProduction 触发口径不变。
inline MonthSettlementResult runMonthEvents(state::GameState& state,
                                            rng::RngManager& rng,
                                            rng::DeterministicRng& aiRng,
                                            ai_ops::AiMonthBatchState& aiBatch,
                                            MonthlyAccrualCarry& carry,
                                            ecs::World& world) {
    MonthSettlementResult out;
    const auto idx = detail::indexById(state.disciples);

    // 步骤 3：AI 兽袭目标预计算（同离散臂）
    detail::precomputeTargets(state, rng);

    // 步骤 4：四系统扇出（4a/4b 炼丹锻造完成结算 → 4c 灵田收获 → 4d/4e 探索）
    production::processBuildingProductionStep(state, rng);
    detail::processSpiritFieldHarvestStep(state, rng);
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
                auto remaining =
                    filterExpiredLevels(wl, state.gameData.gameYear,
                                        state.gameData.gameMonth);
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
                state.gameData.worldLevels =
                    moveBeasts(merged, state.gameData.gameYear,
                               state.gameData.gameMonth, rng);
                state.gameData.worldLevelLastRefreshMonth = absMonth;
            } else {
                auto remaining =
                    filterExpiredLevels(wl, state.gameData.gameYear,
                                        state.gameData.gameMonth);
                state.gameData.worldLevels =
                    moveBeasts(remaining, state.gameData.gameYear,
                               state.gameData.gameMonth, rng);
            }
        } else {
            const auto monthly = processWorldLevelsMonthly(
                wl, state.gameData.worldLevelLastRefreshMonth,
                state.gameData.gameYear, state.gameData.gameMonth,
                rng, /*allowRefresh=*/false);
            state.gameData.worldLevels = std::move(monthly.levels);
        }
    }

    // 步骤 5：月度自动排班（同离散臂）
    detail::processAutoAssign(state, world);

    // 步骤 6（政策灵石/道德/丹药衰减）：连续轨承担——连续臂跳过（防双计）

    // 步骤 7：月度事件（灵矿子事件仅同步旧月字段投影）
    detail::processMonthlyEvents(state, rng, aiRng, aiBatch, idx, out, world,
                                 /*settleSpiritMineMonthly=*/false);

    // 步骤 8：自动续炼启动（同离散臂）
    production::processAutoProductionStep(state);

    // 连续轨禁用政策上报（月界信封 → Kotlin checkpointAllProduction）
    out.policyCosts.disabledPolicies = std::move(carry.disabledPolicies);
    carry.disabledPolicies.clear();
    if (!out.policyCosts.disabledPolicies.empty()) out.policyCosts.allPaid = false;

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
