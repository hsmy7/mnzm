#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/government.h"
#include "gamecore/system/settlement_detail.h"

// ============================================================
// 年变结算钩子（计划 v2 阶段 2 / T2.3）
//
// 等价移植 Kotlin GameEngineCore.processMonthYearChange 的 yearChanged 分支
// （年变先于月变——settlement.h 钩子序已对齐），Kotlin 侧由
// YearSettlementExecutor 提取同构编排。
//
// Kotlin processYearlyEvents(year) 为 L3b 分帧结构：
//   T1 立即组 11 项（单事务保相对序）+ T2 延迟组 11 项（yearlyOpsQueue 由
//   引擎 tick 30ms 预算 drain / 存档前 flush / 读档 clear——C++ 无对应分帧概念）。
// 本批下沉 T1 中语义可闭环的两项，其余场景规避/登记批次（逐项表见
// .superpowers/sdd/t2-3-semantics.md §1）：
//   ✅ #10 garrisonAndReport 年报快照段：worldMapSects 空时驻军轮换恒等返回
//      （AISectGarrisonManager.kt:79 无玩家宗门直接 return gameData）→ C++
//      只需年报追加 + annual* 十二项清零；轮换完整移植随 AI 宗门批次登记
//      （aiSectDisciples 已入快照协议——批 10-4 GameState 顶层，见 models.h）
//   ✅ gameMonth==1 时年俸（processAnnualSalary 全逻辑含不足分支）
//   ❌ 附庸×2（附庸批次）/ 弟子生命周期 aging×3 与监牢释放（生命周期批次）/
//      招募三件套（招募批次）/ 商人赠予（商人批次）/ autoBuy（购买批次）/
//      T2 十一项（AI 宗门/外交/秘境批次）——测试惰性依赖的 NPE 由
//      safelyRunInState 捕获 ≡ no-op，双端一致；生产补齐随各归属批次
//
// RNG：T1/T2 全程零随机抽取——场景规避后（lastRecruitYear 差值判据不满足、
// recruitList 空、AI/外交/秘境域数据空）年变全程零消耗，RNG 对拍退化为
// "分区状态不变"断言。
// ============================================================
namespace gamecore::system {

/// 年度报告保留条数上限（GameConfig.Logs.MAX_YEARLY_REPORTS）
constexpr std::size_t kMaxYearlyReports = 100;

namespace detail {

using gamecore::state::Disciple;
using gamecore::state::DiscipleStore;
using gamecore::state::GameData;
using gamecore::state::GameState;
using settle_util::toIntOrNull;

/// Kotlin String.isBlank（空串或全空白字符）
inline bool yearIsBlankString(const std::string& s) {
    if (s.empty()) return true;
    return std::all_of(s.begin(), s.end(), [](unsigned char c) {
        return c == ' ' || c == '\t' || c == '\n' ||
               c == '\r' || c == '\f' || c == '\v';
    });
}

/// 年度报告快照 + annual* 计数清零（runGarrisonAndReport 的年报段；
/// 驻军轮换在无玩家宗门时恒等返回——aiSectDisciples 轮换随 AI 宗门批次
/// 下沉，见文件头）。takeLast(MAX_YEARLY_REPORTS=100) 裁剪语义保留。
inline void runYearlyReportSnapshot(GameState& state) {
    GameData& gd = state.gameData;
    state::YearlyReport report;
    report.year = gd.gameYear - 1;   // 报告归属刚结束的年份
    report.totalIncome = gd.annualTotalIncome;
    report.totalExpenditure = gd.annualTotalExpenditure;
    report.incomeBySource = gd.annualIncomeBySource;
    report.expenditureByReason = gd.annualExpenditureByReason;
    report.equipmentBySource = gd.annualEquipmentBySource;
    report.pillBySource = gd.annualPillBySource;
    report.herbBySource = gd.annualHerbBySource;
    report.alchemyCompleted = gd.annualAlchemyCount;
    report.forgeCompleted = gd.annualForgeCount;
    report.herbsHarvested = gd.annualHerbCount;
    report.newDisciples = gd.annualNewDisciples;
    report.deceasedDisciples = gd.annualDeceasedDisciples;
    report.desertedDisciples = gd.annualDesertedDisciples;

    gd.yearlyReports.push_back(report);
    if (gd.yearlyReports.size() > kMaxYearlyReports) {
        gd.yearlyReports.erase(
            gd.yearlyReports.begin(),
            gd.yearlyReports.end() -
                static_cast<std::ptrdiff_t>(kMaxYearlyReports));
    }
    // annual* 快照后清零（新年计数从零开始；与 Kotlin runGarrisonAndReport
    // copy 字段面逐一对应，annualTheftCount 同步归零）
    gd.annualIncomeBySource.clear();
    gd.annualExpenditureByReason.clear();
    gd.annualTotalIncome = 0;
    gd.annualTotalExpenditure = 0;
    gd.annualEquipmentBySource.clear();
    gd.annualPillBySource.clear();
    gd.annualHerbBySource.clear();
    gd.annualAlchemyCount = 0;
    gd.annualForgeCount = 0;
    gd.annualHerbCount = 0;
    gd.annualNewDisciples = 0;
    gd.annualDeceasedDisciples = 0;
    gd.annualDesertedDisciples = 0;
    gd.annualTheftCount = 0;
}

/// 年俸计划构建（calculateSalaryPlan 列直读版 + 幽灵防御：
/// isAlive/names/realms 三表齐全且名非空白——C++ Disciple 向量模型天然齐全，
/// 保留 name.isBlank 跳过；enabledConfig[realm]==true 且 salary>0 过滤一致）
inline SalaryPlan buildYearSalaryPlan(const GameState& state) {
    const GameData& gd = state.gameData;
    const DiscipleStore& ds = state.disciples;
    SalaryPlan plan;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] == 0) continue;
        if (yearIsBlankString(ds.names[row])) continue;   // 幽灵防御：空名跳过
        const auto enabledIt = gd.yearlySalaryEnabled.find(ds.realms[row]);
        if (enabledIt == gd.yearlySalaryEnabled.end() || !enabledIt->second) {
            continue;
        }
        const auto salaryIt = gd.yearlySalary.find(ds.realms[row]);
        if (salaryIt == gd.yearlySalary.end()) continue;
        const int64_t salary = salaryIt->second;
        if (salary <= 0) continue;
        const auto id = toIntOrNull(ds.ids[row]);
        if (!id.has_value()) continue;   // Kotlin id.toIntOrNull ?: skip
        plan.eligibleSalaries.emplace_back(*id, salary);
        plan.totalRequired += salary;
    }
    if (plan.totalRequired <= 0) {
        plan.eligibleSalaries.clear();
        plan.totalRequired = 0;
    }
    return plan;
}

/// idx 查找辅助（返回行下标；不存在返回 size）
inline std::size_t idx_find(const GameState& state, int32_t id) {
    for (std::size_t i = 0; i < state.disciples.size(); ++i) {
        const auto cur = toIntOrNull(state.disciples.idAt(i));
        if (cur.has_value() && *cur == id) return i;
    }
    return state.disciples.size();
}

/// 年俸发放（processAnnualSalary 足额分支的逐弟子写回面；
/// 扣减额为 Σ原额、发放到弟子袋为 round(salary_i × multiplier) 之和——
/// 差额由设计销毁，双端公式一致即逐位对齐）
inline void paySalariesToDisciples(GameState& state, const SalaryPlan& plan,
                                   bool frugality) {
    const double multiplier =
        frugality ? (1.0 - kFrugalitySalaryReduction) : 1.0;
    DiscipleStore& ds = state.disciples;
    for (const auto& [id, salary] : plan.eligibleSalaries) {
        const auto it = idx_find(state, id);
        if (it == ds.size()) continue;   // ids.contains 校验等价
        if (ds.isAlive[it] == 0) continue;
        const int64_t actualSalary = static_cast<int64_t>(
            std::round(static_cast<double>(salary) * multiplier));
        ds.storageBagSpiritStones[it] += actualSalary;
        ds.salaryPaidCounts[it] += 1;
        // 开源节流政策下不发忠诚
        if (!frugality) {
            ds.loyalties[it] = std::min(ds.loyalties[it] + 1, kMaxLoyalty);
        }
    }
}

}  // namespace detail

/// 年俸结算主体（processAnnualSalary：计划 → canAfford → 发放/忠诚惩罚）。
/// canAfford 采用 LOW 品纯 spiritStones 比较——autoSell 中/高品兑换开关开启时
/// Kotlin 会折算中高品余额，对拍场景锁定两开关 false（默认值）。
inline void processAnnualSalary(state::GameState& state) {
    const SalaryPlan plan = detail::buildYearSalaryPlan(state);
    if (plan.totalRequired <= 0) return;   // Kotlin calculateSalaryPlan null → return

    const bool frugality = state.gameData.sectPolicies.frugality;
    // canAfford：LOW 品口径（场景锁定 autoSell 开关 false → 纯 spiritStones）
    if (state.gameData.spiritStones < plan.totalRequired) {
        // 灵石不足 → 应得弟子 loyalty -1（coerceAtLeast MIN_LOYALTY=0），不发俸禄
        state::DiscipleStore& ds = state.disciples;
        for (const auto& [id, salary] : plan.eligibleSalaries) {
            const auto i = detail::idx_find(state, id);
            if (i == ds.size()) continue;
            if (ds.isAlive[i] == 0) continue;
            ds.loyalties[i] = std::max(ds.loyalties[i] - 1, 0);
        }
        return;
    }

    // 足额：钱包扣减（Σ原额，LOW/Salary/Salary/autoConvert=true）
    SpiritStoneWallet::deduct(state.gameData, plan.totalRequired,
                              SpiritStoneGrade::LOW, "Salary", "Salary", true);
    detail::paySalariesToDisciples(state, plan, frugality);
}

/// 年变编排主入口（注册进 SettlementEngine::onYearChange）。
/// @param state 完整游戏状态（就地修改）
/// @param rng   RNG 分区管理器（本钩子全程零消耗；参数供后续批次接线）
inline void runYearSettlement(state::GameState& state,
                              rng::RngManager& rng) {
    (void)rng;   // 年变 T1/T2 场景规避后零 RNG 抽取（见文件头）

    // ── processYearlyEvents(year)：T1 已下沉子集 ──
    // #10 garrisonAndReport：年报快照 + annual* 清零（驻军轮换恒等，见上）
    detail::runYearlyReportSnapshot(state);
    // #1/#2 附庸、#3/#7/#9 生命周期 aging 与监牢释放、#4/#5/#8 招募三件套、
    // #6 商人赠予、#11 autoBuy、T2 十一项——场景规避 + 登记批次（文件头）

    // ── gameMonth==1 时年俸（Kotlin processMonthYearChange 第二分支）──
    if (state.gameData.gameMonth == 1) {
        processAnnualSalary(state);
    }
}

}  // namespace gamecore::system
