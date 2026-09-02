#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/data/beast_material_db.h"
#include "gamecore/data/equipment_db.h"
#include "gamecore/data/herb_db.h"
#include "gamecore/data/manual_db.h"
#include "gamecore/data/recipe_db.h"
#include "gamecore/system/ai_sect_recruit.h"
#include "gamecore/system/disciple_factory.h"
#include "gamecore/system/disciple_stats.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/government.h"
#include "gamecore/system/level_generator.h"
#include "gamecore/system/lifecycle.h"
#include "gamecore/system/month_settlement.h"
#include "gamecore/system/name_service.h"
#include "gamecore/system/rarity_progression.h"
#include "gamecore/system/recruit_settlement.h"
#include "gamecore/system/sect_trade.h"
#include "gamecore/system/secret_realm.h"
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

// ── 批 Y-1 常量（年变零 RNG 小件；逐条对齐 Kotlin 配置源）──────────
// 附庸年贡（GameConfig.AIAttack.VASSAL_TRIBUTE_RATIO / VASSAL_TRIBUTE_MIN）
constexpr double kVassalTributeRatio = 0.5;
constexpr int64_t kVassalTributeMin = 1;
// 附属宗门年贡按等级（VassalConfig.TRIBUTE_BY_SECT_LEVEL 0..3 + 未知等级默认）
constexpr int64_t kVassalTributeDefault = 50'000;
// 商人手动刷新机会（MerchantAndRecruitService.MERCHANT_REFRESH_CHANCE_INTERVAL_YEARS /
// GameConfig.JadePurchase.MERCHANT_REFRESH_MAX）
constexpr int32_t kMerchantRefreshChanceIntervalYears = 30;
constexpr int32_t kMerchantRefreshMax = 999;
// 好感度衰减（FavorConfig.DECAY_NO_GIFT_YEARS / DECAY_AMOUNT / DECAY_THRESHOLD）
constexpr int32_t kFavorDecayNoGiftYears = 1;
constexpr int32_t kFavorDecayAmount = 1;
constexpr int32_t kFavorDecayThreshold = 80;
// 联盟（FavorConfig.MIN_ALLIANCE_FAVOR / ALLIANCE_DURATION_YEARS）
constexpr int32_t kMinAllianceFavor = 80;
constexpr int32_t kAllianceDurationYears = 5;
// 死亡弟子清理年限（DiscipleLifecycleProcessor.CULL_DEAD_AFTER_YEARS）
constexpr int32_t kCullDeadAfterYears = 1;
// 哀悼期哨兵（DiscipleTables.GRIEF_YEAR_NULL_SENTINEL = -1：无丧亲期）
constexpr int32_t kGriefYearNullSentinel = -1;

// ── 批 Y-3（T1-④ 招募刷新）常量（RecruitService companion 逐值对齐）──
// 招募弟子基础年龄范围
constexpr int32_t kRecruitAgeMin = 16;
constexpr int32_t kRecruitAgeRange = 14;
// 纳徒长老魅力加成公式参数（RECRUIT_CHARM_BASELINE/DIVISOR/MAX）
constexpr int32_t kRecruitCharmBaseline = 80;
constexpr int32_t kRecruitCharmDivisor = 4;
constexpr int32_t kMaxRecruitBonusCap = 20;
// 招募数量兜底（FALLBACK_RECRUIT_COUNT——无玩家宗门时）
constexpr int32_t kFallbackRecruitCount = 7;
// 广纳门徒政策招募加成（GameConfig.PolicyConfig.OPEN_RECRUITMENT_POOL_BONUS）
constexpr double kOpenRecruitmentPoolBonus = 0.50;
// 招募列表刷新间隔（CultivationEventProcessor.RECRUIT_REFRESH_INTERVAL_YEARS）
constexpr int32_t kRecruitRefreshIntervalYears = 3;

// ── 批 Y-4a（T2-④ 交易刷新）常量（DiplomacyService companion 逐值对齐）──
// AI 宗门交易刷新间隔（SECT_TRADE_REFRESH_INTERVAL_YEARS）
constexpr int32_t kSectTradeRefreshIntervalYears = 3;
// 交易物品数量（generateSectTradeItems itemCount）/ 最大尝试次数（×3）
constexpr int32_t kSectTradeItemCount = 20;
constexpr int32_t kSectTradeMaxAttempts = kSectTradeItemCount * 3;
// 灵草/种子基准价（Kotlin GameConfig.Rarity.herbPrice/seedPrice——派生
// getter：price = Rarity.get(rarity).herbPrice/seedPrice；下标 0 未用）
constexpr int32_t kHerbBasePrice[7] = {0, 400, 1600, 8000, 48000, 336000, 2688000};
constexpr int32_t kSeedBasePrice[7] = {0, 80, 320, 1600, 9600, 67200, 537600};
// 材料基准价（Kotlin GameConfig.Rarity.materialBasePrice——收购池 priceMap
// 兜底：池构建恒有价，仅防御性回退用）
constexpr int32_t kMaterialBasePrice[7] = {0, 400, 1600, 8000, 48000, 336000, 2688000};

namespace detail {

// ════════════════════════════════════════════════════════════════
// 批 Y-3（T1-③ 死亡链）平台效应草稿——C++ 死亡链状态面（老化/槽位/哀悼/
// 解绑/血炼/装备清/死亡记录）完成后，Kotlin 残留执行器经草稿执行平台效应：
// 袋物品物化回仓库（含溢出邮件）/DAO 清理/DeathEvent/死亡记录档案/
// lifeEvents 丧亲事件。与 S-17 秘境草稿同构（信封回传）。
// 定义于 detail 前（detail 函数前置依赖），detail 结束后以
// `using detail::YearSettlementDraft` 导出到 gamecore::system。
// ════════════════════════════════════════════════════════════════

/// 死亡弟子草稿（Kotlin 残留：袋物品物化 + DAO 清理 + DeathEvent + addDeathRecord）
struct AgedDeathDraft {
    std::string discipleId;
    std::string name;
    std::string surname;
    int32_t age = 0;
    int32_t realm = 9;
    int32_t realmLayer = 1;
    int32_t deathYear = 0;
    std::string cause = "age";
    std::vector<state::StorageBagItem> storageBagItems;  // 袋物品（物化回仓库）
};

/// 丧亲事件草稿（Kotlin 残留：lifeEvents 瞬态列写入）
struct BereavementDraft {
    int32_t grievingId = 0;
    std::string relationship;   // 道侣/父/母/亲属
    std::string deceasedName;
    int32_t grievingAge = 0;
};

/// 年变平台效应草稿集合（runYearSettlement 可选 out + nativeSettleYear 信封）
struct YearSettlementDraft {
    std::vector<AgedDeathDraft> agedDeaths;
    std::vector<BereavementDraft> bereavements;
};

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

// ════════════════════════════════════════════════════════════════
// 批 Y-1：年变零 RNG 小件下沉（T1 六件 + T2 五件 + T1-⑧ 净化）
// 等价移植 Kotlin CultivationEventMonthlyOps.processYearlyEvents /
// enqueueYearlyOps 的零 RNG 子项（审计 2026-09-01；每件语义逐条对齐源码，
// 生产年变仍在 Kotlin——本组函数供 runYearSettlement 接线 + 对拍基准）。
// ════════════════════════════════════════════════════════════════

/// 弟子最大寿元（Kotlin Disciple.computeMaxAge 等价——寿元计算唯一来源，
/// 口径与 DiscipleAgePolicy.kt 一致：lifespan / realmMaxAge /
/// realmMaxAge×(1+天赋+词条 lifespan 加成) 三者取 max，上限 20000）
inline int32_t discipleAgeMax(const state::Disciple& d) {
    double bonus = 0.0;
    for (const auto& id : d.talentIds) {
        if (auto t = gamecore::data::talentById(id)) {
            const auto it = t->effects.find("lifespan");
            if (it != t->effects.end()) bonus += it->second;
        }
    }
    for (const auto& id : d.affixIds) {
        if (auto a = gamecore::data::affixById(id)) {
            const auto it = a->effects.find("lifespan");
            if (it != a->effects.end()) bonus += it->second;
        }
    }
    const int32_t realmMax = realmMaxAge(d.realm);
    const int32_t traitLifespan =
        std::max(static_cast<int32_t>(realmMax * (1.0 + bonus)), 1);
    // 嵌套 max 替代 initializer_list 重载（NDK libc++ 可移植性）
    const int32_t raw = std::max(std::max(d.lifespan, realmMax), traitLifespan);
    return std::min(raw, kAbsoluteMaxAgeCeiling);
}

/// 好感度查询（与 month_settlement.h breakawayFavor 同源——Kotlin
/// FavorDomain.findRelation：双向匹配首条，缺失默认 50）
inline int32_t findRelationFavor(
    const std::vector<state::SectRelation>& sectRelations,
    const std::string& playerSectId, const std::string& otherSectId) {
    for (const auto& r : sectRelations) {
        if ((r.sectId1 == playerSectId && r.sectId2 == otherSectId) ||
            (r.sectId1 == otherSectId && r.sectId2 == playerSectId)) {
            return r.favor;
        }
    }
    return 50;
}

/// T1-① 附庸年贡（Kotlin VassalService.processYearlyTribute）：
/// 玩家是附庸时按上年收入比例向上主宗缴纳年贡（钱包扣 LOW/VassalTribute/
/// Internal，autoConvert=true 与 Kotlin 默认一致）；无主宗/贡额 0 → 早退。
/// 零 RNG。
inline void processYearlyTribute(GameState& state) {
    auto& gd = state.gameData;
    if (gd.suzerainSectId.empty()) return;
    const int64_t income = gd.lastYearSpiritStoneIncome;
    // 统一 int64_t（NDK 下 int64_t=long，与 0LL 的 long long 三元推导冲突——
    // 全显式 int64_t 保证 libc++ 可移植）
    const int64_t minTribute = income > 0 ? kVassalTributeMin : static_cast<int64_t>(0);
    const int64_t tribute = std::max(
        static_cast<int64_t>(static_cast<double>(income) * kVassalTributeRatio),
        minTribute);
    if (tribute <= 0) return;
    // 钱包扣减（Kotlin 失败仅记日志——C++ 静默等价）
    SpiritStoneWallet::deduct(gd, tribute, SpiritStoneGrade::LOW,
                              "VassalTribute", "Internal", true);
}

/// T1-② 玩家附属宗门年贡（Kotlin VassalService.processYearlyVassalTribute）：
/// 遍历附属契约——新建立当年不计贡（establishedYear >= year）、本年已贡跳过
/// （lastTributeYear >= year）、宗门已不存在 → 移除契约；否则按宗门等级查表
/// （未知等级默认 50000）累计 + 更新 lastTributeYear=year；有变更 → 钱包
/// add 总额（LOW/Internal）+ 契约列表写回。零 RNG。
inline void processYearlyVassalTribute(GameState& state, int32_t year) {
    auto& gd = state.gameData;
    std::vector<state::VassalContract> updated;
    updated.reserve(gd.vassalContracts.size());
    int64_t totalTribute = 0;
    bool changed = false;
    for (const auto& contract : gd.vassalContracts) {
        if (contract.establishedYear >= year) {
            updated.push_back(contract);
            continue;
        }
        if (contract.lastTributeYear >= year) {
            updated.push_back(contract);
            continue;
        }
        // 宗门不存在 → 移除
        bool found = false;
        for (const auto& sect : gd.worldMapSects) {
            if (sect.id == contract.vassalSectId) { found = true; break; }
        }
        if (!found) {
            changed = true;
            continue;
        }
        int64_t amount = kVassalTributeDefault;
        for (const auto& sect : gd.worldMapSects) {
            if (sect.id == contract.vassalSectId) {
                switch (sect.level) {
                    case 0: amount = 200'000LL; break;
                    case 1: amount = 800'000LL; break;
                    case 2: amount = 3'000'000LL; break;
                    case 3: amount = 10'000'000LL; break;
                    default: amount = kVassalTributeDefault; break;
                }
                break;
            }
        }
        totalTribute += amount;
        state::VassalContract c = contract;
        c.lastTributeYear = year;
        updated.push_back(std::move(c));
        changed = true;
    }
    if (changed) {
        SpiritStoneWallet::add(gd, totalTribute, SpiritStoneGrade::LOW, "Internal");
        gd.vassalContracts = std::move(updated);
    }
}

/// T1-⑤ 自动拒绝（Kotlin RecruitService.processAutoReject）：
/// 惰性门 autoRejectIdle → 0；筛选空/无有效灵根数 → 0；recruitList 按
/// 灵根数 ∈ filter 分区（distinctBy id 等价——列表 id 唯一性由净化维护）；
/// 损坏条目（isValidRecruit 失败）不拒绝保留；validRejected 空 → 置惰性。
/// 零 RNG。
inline int32_t processAutoReject(GameState& state) {
    auto& gd = state.gameData;
    if (state.autoRejectIdle) return 0;
    if (gd.autoRejectSpiritRootFilter.empty()) return 0;
    std::set<int32_t> validFilter;
    for (int32_t v : gd.autoRejectSpiritRootFilter) {
        if (v >= 1 && v <= 5) validFilter.insert(v);
    }
    if (validFilter.empty()) return 0;

    std::vector<state::Disciple> kept;
    std::vector<state::Disciple> rejected;
    std::vector<state::Disciple> corruptedRejected;
    for (const auto& d : gd.recruitList) {
        const int32_t rootCount = recruit_settle::nonBlankRootCount(d.spiritRootType);
        if (validFilter.count(rootCount) != 0) {
            if (recruit_settle::isValidRecruit(d)) {
                rejected.push_back(d);
            } else {
                corruptedRejected.push_back(d);
            }
        } else {
            kept.push_back(d);
        }
    }
    if (rejected.empty()) {
        state.autoRejectIdle = true;
        return 0;
    }
    kept.insert(kept.end(), corruptedRejected.begin(), corruptedRejected.end());
    gd.recruitList = std::move(kept);
    return static_cast<int32_t>(rejected.size());
}

/// T1-⑥ 商人手动刷新机会（Kotlin MerchantAndRecruitService.
/// giveMerchantRefreshChanceIfDue）：year<=0 防御；已达上限（999）跳过；
/// lastGrant==0 首次或差值 ≥30 年 → +1（coerceAtMost 999）+ 更新授予年。
/// 零 RNG。
inline void processMerchantRefreshChance(GameState& state, int32_t year) {
    if (year <= 0) return;
    auto& gd = state.gameData;
    if (gd.merchantRefreshChances >= kMerchantRefreshMax) return;
    if (gd.merchantLastRefreshChanceGrantYear == 0 ||
        year - gd.merchantLastRefreshChanceGrantYear >= kMerchantRefreshChanceIntervalYears) {
        gd.merchantRefreshChances =
            std::min(gd.merchantRefreshChances + 1, kMerchantRefreshMax);
        gd.merchantLastRefreshChanceGrantYear = year;
    }
}

/// T1-⑦ 年度老化清理（Kotlin DiscipleLifecycleProcessor.processYearlyAging）：
/// cullDeadDisciples(currentYear - 1)——deathYears 有条目（>0）且
/// <= 阈值（死亡满 CULL_DEAD_AFTER_YEARS=1 年）的弟子整行移除。
/// Kotlin 同时记录 _deathRecords（纯内存，C++ 无对应）。零 RNG。
inline void processYearlyAging(GameState& state, int32_t currentYear) {
    const int32_t threshold = currentYear - kCullDeadAfterYears;
    state::DiscipleStore& ds = state.disciples;
    std::vector<std::string> toRemove;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.deathYears[row] != 0 && ds.deathYears[row] <= threshold) {
            toRemove.push_back(ds.ids[row]);
        }
    }
    for (const auto& id : toRemove) {
        ds.removeById(id);
    }
}

/// T1-⑧ 招募列表老化 + 净化（Kotlin RecruitService.ageRecruitList）：
/// ① 全员 age+1，age >= computeMaxAge 视为寿元耗尽移除；
/// ② sanitizeRecruitList 等价——损坏过滤（isValidRecruit）+ 三级去重
/// （id/内容/同人签名——保留首个）+ 已入宗门残留移除（isSamePerson 跨表）。
/// 零 RNG。
inline void processRecruitAging(GameState& state) {
    auto& gd = state.gameData;
    // ① 老化 + 超寿元移除
    std::vector<state::Disciple> alive;
    for (const auto& d : gd.recruitList) {
        state::Disciple aged = d;
        aged.age = d.age + 1;
        const int32_t maxAge = discipleAgeMax(aged);
        if (aged.age < maxAge) alive.push_back(std::move(aged));
    }
    // ② 净化：损坏过滤 + 三级去重 + 已入宗门残留
    std::vector<state::Disciple> valid;
    for (const auto& d : alive) {
        if (recruit_settle::isValidRecruit(d)) valid.push_back(d);
    }
    // 二级去重（id / 内容）+ 同人签名去重（保留首个；isSamePerson 年龄容差）
    std::vector<state::Disciple> deduped;
    std::set<std::string> idSeen;
    for (const auto& d : valid) {
        if (idSeen.insert(d.id).second) deduped.push_back(d);
    }
    std::vector<state::Disciple> contentDeduped;
    std::vector<state::Disciple> contentSeen;
    for (const auto& d : deduped) {
        bool dup = false;
        for (const auto& prev : contentSeen) {
            if (recruit_settle::discipleContentEquals(prev, d)) { dup = true; break; }
        }
        if (!dup) {
            contentDeduped.push_back(d);
            contentSeen.push_back(d);
        }
    }
    // 已入宗门残留（跨表 isSamePerson——签名 + 年龄容差）
    std::vector<state::Disciple> sectDisciples;
    for (std::size_t row = 0; row < state.disciples.size(); ++row) {
        sectDisciples.push_back(state.disciples.materialize(row));
    }
    std::vector<state::Disciple> result;
    for (const auto& d : contentDeduped) {
        bool inSect = false;
        for (const auto& s : sectDisciples) {
            if (recruit_settle::isSamePerson(d, s)) { inSect = true; break; }
        }
        if (!inSect) result.push_back(d);
    }
    gd.recruitList = std::move(result);
}

/// T2-① AI 宗门弟子老化（Kotlin CaveExplorationProcessor.
/// processSectDisciplesAging → AISectDiscipleManager.processAging）：
/// 非玩家宗门 aiSectDisciples 全员 age+1，超寿元（> computeMaxAge）置
/// isAlive=false 后过滤移除。aiSectDisciples 为 std::map 键升序（Kotlin
/// LinkedHashMap 插入序——顺序归一化边界见批 10-4，对拍以键升序构造）。
/// 零 RNG（AI 独立分区在生成期，不在本件）。
inline void processSectDisciplesAging(GameState& state) {
    std::map<std::string, std::vector<state::Disciple>> updated;
    for (const auto& kv : state.aiSectDisciples) {
        const std::string& sectId = kv.first;
        bool isPlayer = false;
        for (const auto& sect : state.gameData.worldMapSects) {
            if (sect.id == sectId && sect.isPlayerSect) { isPlayer = true; break; }
        }
        if (isPlayer) {
            updated[sectId] = kv.second;
            continue;
        }
        std::vector<state::Disciple> aged;
        for (const auto& d : kv.second) {
            state::Disciple a = d;
            a.age = d.age + 1;
            a.isAlive = a.age <= discipleAgeMax(a);
            if (a.isAlive) aged.push_back(std::move(a));
        }
        updated[sectId] = std::move(aged);
    }
    state.aiSectDisciples = std::move(updated);
}

// ════════════════════════════════════════════════════════════════
// 批 Y-4b（T2-③）：商人收购刷新（Kotlin MerchantAndRecruitService.
// refreshMerchantAcquisition + buildMerchantItemPools/createMerchantItem/
// mergeMerchantItems 等价移植）
//
// RNG 契约：SYSTEM 分区（调用方传 rng.getRng(kSystem)）——数量 1×nextInt(9)
// + 每 item：品阶 1×nextDouble + 选池 1×nextInt + 库存 1×nextInt +
// 丹药 grade 1×nextDouble + 价格波动 1×nextDouble（消费序与 Kotlin
// 逐位一致）。已知边界（对拍排除）：MerchantItem.id/itemId 为 Kotlin
// UUID 镜像生成字段——C++ 用确定性自增 id 占位（T2-③/④ 共用）。
// ════════════════════════════════════════════════════════════════

/// 商人物品确定性 id（Kotlin UUID——镜像生成字段，对拍排除；商人收购/
/// 宗门交易共用）
inline std::string nextTradeItemId() {
    static uint64_t counter = 0;
    return "gc-trade-" + std::to_string(++counter);
}

/// 商人池条目（Kotlin PoolEntry{name, type}）
struct PoolEntry {
    std::string name;
    std::string type;
};

/// 商人物品池（Kotlin MerchantItemPools；poolByRarity 下标 0 未用 1..6）
struct MerchantItemPools {
    std::vector<std::vector<PoolEntry>> poolByRarity;
    std::map<std::string, int32_t> rarityMap;
    std::map<std::string, int64_t> priceMap;
};

/// 构建商人物品池（Kotlin buildMerchantItemPools）：装备/功法（Kotlin
/// ManualDatabase.isInitialized 守卫——C++ 静态表恒真）/丹药（MEDIUM
/// 品阶 + 名去重，批 Y-4 price 已补全）/妖兽材料/灵草/种子 +
/// 中品/上品灵石（价格按下品结算 RATIO）。零 RNG。
inline MerchantItemPools buildMerchantItemPools() {
    MerchantItemPools pools;
    pools.poolByRarity.assign(7, {});
    constexpr int64_t kRatio = 10'000L;  // SpiritStoneExchange.RATIO
    for (const auto& t : data::equipmentTemplates()) {
        pools.poolByRarity[static_cast<std::size_t>(t.rarity)].push_back({t.name, "equipment"});
        pools.rarityMap[t.name] = t.rarity;
        pools.priceMap[t.name] = static_cast<int64_t>(t.price);
    }
    for (const auto& t : data::manualTemplates()) {
        pools.poolByRarity[static_cast<std::size_t>(t.rarity)].push_back({t.name, "manual"});
        pools.rarityMap[t.name] = t.rarity;
        pools.priceMap[t.name] = static_cast<int64_t>(t.price);
    }
    // 丹药：MEDIUM 品阶 + 名字去重（Kotlin addedPillNames 首次出现序）
    std::set<std::string> addedPills;
    for (const auto& r : data::pillRecipes()) {
        if (r.grade != "medium") continue;
        if (addedPills.count(r.name) != 0) continue;
        addedPills.insert(r.name);
        pools.poolByRarity[static_cast<std::size_t>(r.rarity)].push_back({r.name, "pill"});
        pools.rarityMap[r.name] = r.rarity;
        pools.priceMap[r.name] = static_cast<int64_t>(r.price);
    }
    for (const auto& m : data::beastMaterialTemplates()) {
        pools.poolByRarity[static_cast<std::size_t>(m.rarity)].push_back({m.name, "material"});
        pools.rarityMap[m.name] = m.rarity;
        pools.priceMap[m.name] = static_cast<int64_t>(m.price);
    }
    for (const auto& h : data::herbTemplates()) {
        pools.poolByRarity[static_cast<std::size_t>(h.rarity)].push_back({h.name, "herb"});
        pools.rarityMap[h.name] = h.rarity;
        pools.priceMap[h.name] = static_cast<int64_t>(kHerbBasePrice[h.rarity]);
    }
    for (const auto& s : data::seedTemplates()) {
        pools.poolByRarity[static_cast<std::size_t>(s.rarity)].push_back({s.name, "seed"});
        pools.rarityMap[s.name] = s.rarity;
        pools.priceMap[s.name] = static_cast<int64_t>(kSeedBasePrice[s.rarity]);
    }
    // 中品（3）/上品（4）灵石入收购池
    pools.poolByRarity[3].push_back({"中品灵石", "spiritStone"});
    pools.rarityMap["中品灵石"] = 3;
    pools.priceMap["中品灵石"] = kRatio;
    pools.poolByRarity[4].push_back({"上品灵石", "spiritStone"});
    pools.rarityMap["上品灵石"] = 4;
    pools.priceMap["上品灵石"] = kRatio * kRatio;
    return pools;
}

/// 按品阶选池随机条目（Kotlin selectItemByRarity：池空 → null 零 RNG；
/// 非空 → 1×nextInt）
inline std::optional<PoolEntry> selectItemByRarity(const MerchantItemPools& pools,
                                                   rng::DeterministicRng& rng,
                                                   int32_t rarity) {
    const auto& pool = pools.poolByRarity[static_cast<std::size_t>(rarity)];
    if (pool.empty()) return std::nullopt;
    return pool[static_cast<std::size_t>(
        rng.nextInt(static_cast<int32_t>(pool.size())))];
}

/// 第一个非空品阶池随机条目（Kotlin selectFirstAvailableItem：
/// (1..6) 升序首个非空池——1×nextInt）
inline std::optional<PoolEntry> selectFirstAvailableItem(const MerchantItemPools& pools,
                                                         rng::DeterministicRng& rng) {
    for (int32_t rarity = 1; rarity <= 6; ++rarity) {
        const auto& pool = pools.poolByRarity[static_cast<std::size_t>(rarity)];
        if (!pool.empty()) {
            return pool[static_cast<std::size_t>(
                rng.nextInt(static_cast<int32_t>(pool.size())))];
        }
    }
    return std::nullopt;
}

/// 商品库存量抽样（Kotlin calculateMerchantStock + capSpiritStoneStock：
/// 消耗品/耐用品两档曲线；灵石 ≤3——sect_trade.h sectTradeStock 同源）
inline int32_t calculateMerchantStock(rng::DeterministicRng& rng,
                                      const std::string& type, int32_t rarity) {
    int32_t stock = sectTradeStock(rng, type, rarity);
    if (type == "spiritStone") stock = std::min(stock, 3);
    return stock;
}

/// 丹药随机品阶（Kotlin selectMerchantPillGrade：1×nextDouble；
/// <0.03 HIGH / <0.40 MEDIUM / else LOW——返回 grade.name）
inline std::string selectMerchantPillGrade(rng::DeterministicRng& rng) {
    const double roll = rng.nextDouble();
    if (roll < 0.03) return "HIGH";
    if (roll < 0.40) return "MEDIUM";
    return "LOW";
}

/// 创建商人物品（Kotlin createMerchantItem：forcedRarity 缺省取池 rarityMap；
/// 库存 1×nextInt + 丹药 grade 1×nextDouble + 价格波动 1×nextDouble；
/// 丹药价 = basePrice × gradeMultiplier（MEDIUM 恒 1.0）roundToLong）
inline state::MerchantItem createMerchantItem(const PoolEntry& entry,
                                              const MerchantItemPools& pools,
                                              rng::DeterministicRng& rng,
                                              int32_t year, int32_t month,
                                              std::optional<int32_t> forcedRarity = std::nullopt) {
    state::MerchantItem item;
    item.id = nextTradeItemId();
    item.name = entry.name;
    item.type = entry.type;
    item.itemId = nextTradeItemId();
    const auto rit = pools.rarityMap.find(entry.name);
    const int32_t rarity = forcedRarity.value_or(
        rit != pools.rarityMap.end() ? rit->second : 1);
    item.rarity = rarity;
    const auto pit = pools.priceMap.find(entry.name);
    const int64_t basePrice = pit != pools.priceMap.end()
        ? pit->second : static_cast<int64_t>(kMaterialBasePrice[rarity]);
    item.quantity = calculateMerchantStock(rng, entry.type, rarity);
    std::optional<std::string> grade;
    double priceMultiplier = 1.0;
    if (entry.type == "pill") {
        const std::string g = selectMerchantPillGrade(rng);
        if (g == "HIGH") { grade = "上品"; priceMultiplier = 2.0; }
        else if (g == "LOW") { grade = "下品"; priceMultiplier = 0.5; }
        else { grade = "中品"; }
    }
    // Kotlin (basePrice * grade.priceMultiplier / MEDIUM.multiplier).roundToLong()
    // MEDIUM.multiplier = 1.0 —— roundToLong = Math.round（半值向上）
    const int64_t adjustedPrice = static_cast<int64_t>(
        std::llround(static_cast<double>(basePrice) * priceMultiplier));
    item.price = sectTradePriceFluctuation(adjustedPrice, rng);
    item.obtainedYear = year;
    item.obtainedMonth = month;
    item.grade = grade;
    return item;
}

/// 合并同键条目（Kotlin mergeMerchantItems：key = name:type[:grade]；
/// 数量相加 + 加权平均价（Int 除法）；**保持首次出现序**——Kotlin
/// LinkedHashMap 插入序，禁止 std::map 字典序）
inline std::vector<state::MerchantItem> mergeMerchantItems(
    std::vector<state::MerchantItem> items) {
    std::vector<state::MerchantItem> merged;
    std::map<std::string, std::size_t> keyToIndex;
    for (const auto& item : items) {
        std::string key = item.name + ":" + item.type;
        if (item.grade.has_value()) key += ":" + *item.grade;
        const auto it = keyToIndex.find(key);
        if (it != keyToIndex.end()) {
            state::MerchantItem& existing = merged[it->second];
            const int64_t total =
                static_cast<int64_t>(existing.quantity) + item.quantity;
            const int64_t weighted =
                (existing.price * existing.quantity + item.price * item.quantity) / total;
            existing.quantity = static_cast<int32_t>(total);
            existing.price = weighted;
        } else {
            keyToIndex[key] = merged.size();
            merged.push_back(item);
        }
    }
    return merged;
}

/// 商人收购刷新（Kotlin MerchantAndRecruitService.refreshMerchantAcquisition）：
/// 数量 1×nextInt(9) → 逐 item 品阶/选池/库存/grade/价格 → 合并 →
/// 写回 merchantAcquisitionItems + merchantAcquisitionLastRefreshYear。
/// SYSTEM 分区（调用方传 rng.getRng(kSystem)）。
inline void refreshMerchantAcquisition(GameState& state, rng::DeterministicRng& rng,
                                       int32_t year, int32_t month) {
    const MerchantItemPools pools = buildMerchantItemPools();
    bool allEmpty = true;
    for (const auto& pool : pools.poolByRarity) {
        if (!pool.empty()) { allEmpty = false; break; }
    }
    if (allEmpty) return;
    constexpr int32_t kAcqMin = 1, kAcqMax = 9;
    const int32_t count = kAcqMin + rng.nextInt(kAcqMax - kAcqMin + 1);
    std::vector<state::MerchantItem> newItems;
    for (int32_t i = 0; i < count; ++i) {
        const int32_t selectedRarity = rollRarity(rng, year);
        std::optional<PoolEntry> selected = selectItemByRarity(pools, rng, selectedRarity);
        if (!selected.has_value()) selected = selectFirstAvailableItem(pools, rng);
        if (selected.has_value()) {
            newItems.push_back(createMerchantItem(*selected, pools, rng, year, month));
        }
    }
    auto merged = mergeMerchantItems(std::move(newItems));
    state.gameData.merchantAcquisitionItems = std::move(merged);
    state.gameData.merchantAcquisitionLastRefreshYear = year;
}

// ════════════════════════════════════════════════════════════════
// 批 Y-4a（T2-④）：AI 宗门交易列表年度刷新（Kotlin DiplomacyService.
// refreshAllSectTrades + generateSectTradeItems 等价移植）
//
// RNG 契约：局部种子确定性 RNG——DeterministicRng.fromSeed(
// sectId.hashCode() + year)（sect_trade.h sectTradeSeed），同 (sectId,
// year) 生成结果完全可复现；**零分区 RNG 消耗**（SYSTEM 等分区不参与）。
// 已知边界（对拍排除）：MerchantItem.id/itemId 为 Kotlin UUID 镜像
// 生成字段——C++ 用确定性自增 id 占位（nextTradeItemId 定义于批 Y-4b）。
// ════════════════════════════════════════════════════════════════

/// 按品阶选池 + 随机选取（Kotlin `templates.random(random)` 等价：
/// getByRarity(rarity) 非空选该品阶池，空则全池兜底——恰好 1×nextInt）
template <typename T>
inline const T& pickTradeTemplate(rng::DeterministicRng& rngLocal,
                                  const std::vector<T>& templates,
                                  int32_t rarity) {
    std::vector<const T*> pool;
    for (const auto& t : templates) {
        if (t.rarity == rarity) pool.push_back(&t);
    }
    if (pool.empty()) {
        for (const auto& t : templates) pool.push_back(&t);
    }
    return *pool[static_cast<std::size_t>(
        rngLocal.nextInt(static_cast<int32_t>(pool.size())))];
}

/// 装备类商品（Kotlin generateEquipmentItem）：模板池选 1×nextInt +
/// 价格波动 1×nextDouble + 库存 1×nextInt
inline state::MerchantItem generateTradeEquipmentItem(
    rng::DeterministicRng& rngLocal, int32_t rarity, int32_t year) {
    const auto& tpl = pickTradeTemplate(rngLocal, data::equipmentTemplates(), rarity);
    state::MerchantItem item;
    item.id = nextTradeItemId();
    item.name = tpl.name;
    item.type = "equipment";
    item.itemId = nextTradeItemId();
    item.rarity = tpl.rarity;
    item.price = sectTradePriceFluctuation(static_cast<int64_t>(tpl.price), rngLocal);
    item.quantity = sectTradeStock(rngLocal, "equipment", rarity);
    item.obtainedYear = year;
    item.obtainedMonth = 1;
    return item;
}

/// 功法类商品（Kotlin generateManualItem；isInitialized 守卫在 C++ 恒真
/// ——manualTemplates() 静态表，生产恒加载）
inline state::MerchantItem generateTradeManualItem(
    rng::DeterministicRng& rngLocal, int32_t rarity, int32_t year) {
    const auto& tpl = pickTradeTemplate(rngLocal, data::manualTemplates(), rarity);
    state::MerchantItem item;
    item.id = nextTradeItemId();
    item.name = tpl.name;
    item.type = "manual";
    item.itemId = nextTradeItemId();
    item.rarity = tpl.rarity;
    item.price = sectTradePriceFluctuation(static_cast<int64_t>(tpl.price), rngLocal);
    item.quantity = sectTradeStock(rngLocal, "manual", rarity);
    item.obtainedYear = year;
    item.obtainedMonth = 1;
    return item;
}

/// 丹药类商品（Kotlin generatePillItem）：getPillsByRarity 空 → null
///（零 RNG）；非空 → 1×nextInt 选模板 + 价格波动 + 库存；grade =
/// PillGrade.displayName（批 Y-4 price 已补全）
inline std::optional<state::MerchantItem> generateTradePillItem(
    rng::DeterministicRng& rngLocal, int32_t rarity, int32_t year) {
    const auto& recipes = data::pillRecipes();
    std::vector<const data::PillRecipeTemplate*> pool;
    for (const auto& r : recipes) {
        if (r.rarity == rarity) pool.push_back(&r);
    }
    if (pool.empty()) return std::nullopt;
    const auto& tpl = *pool[static_cast<std::size_t>(
        rngLocal.nextInt(static_cast<int32_t>(pool.size())))];
    state::MerchantItem item;
    item.id = nextTradeItemId();
    item.name = tpl.name;
    item.type = "pill";
    item.itemId = nextTradeItemId();
    item.rarity = tpl.rarity;
    item.price = sectTradePriceFluctuation(static_cast<int64_t>(tpl.price), rngLocal);
    item.quantity = sectTradeStock(rngLocal, "pill", rarity);
    item.obtainedYear = year;
    item.obtainedMonth = 1;
    // PillGrade.displayName（grade lower 名 → 显示名）
    if (tpl.grade == "low") item.grade = "下品";
    else if (tpl.grade == "high") item.grade = "上品";
    else item.grade = "中品";
    return item;
}

/// 材料类商品（Kotlin generateMaterialItem）：getMaterialsByRarity 空 → null
inline std::optional<state::MerchantItem> generateTradeMaterialItem(
    rng::DeterministicRng& rngLocal, int32_t rarity, int32_t year) {
    const auto& templates = data::beastMaterialTemplates();
    std::vector<const data::BeastMaterialTemplate*> pool;
    for (const auto& t : templates) {
        if (t.rarity == rarity) pool.push_back(&t);
    }
    if (pool.empty()) return std::nullopt;
    const auto& tpl = *pool[static_cast<std::size_t>(
        rngLocal.nextInt(static_cast<int32_t>(pool.size())))];
    state::MerchantItem item;
    item.id = nextTradeItemId();
    item.name = tpl.name;
    item.type = "material";
    item.itemId = nextTradeItemId();
    item.rarity = tpl.rarity;
    item.price = sectTradePriceFluctuation(static_cast<int64_t>(tpl.price), rngLocal);
    item.quantity = sectTradeStock(rngLocal, "material", rarity);
    item.obtainedYear = year;
    item.obtainedMonth = 1;
    return item;
}

/// 草药类商品（Kotlin generateHerbItem）：getByRarity 空 → null
inline std::optional<state::MerchantItem> generateTradeHerbItem(
    rng::DeterministicRng& rngLocal, int32_t rarity, int32_t year) {
    const auto& templates = data::herbTemplates();
    std::vector<const data::HerbTemplate*> pool;
    for (const auto& t : templates) {
        if (t.rarity == rarity) pool.push_back(&t);
    }
    if (pool.empty()) return std::nullopt;
    const auto& tpl = *pool[static_cast<std::size_t>(
        rngLocal.nextInt(static_cast<int32_t>(pool.size())))];
    state::MerchantItem item;
    item.id = nextTradeItemId();
    item.name = tpl.name;
    item.type = "herb";
    item.itemId = nextTradeItemId();
    item.rarity = tpl.rarity;
    // Kotlin Herb.price 为派生 getter（GameConfig.Rarity.herbPrice）
    item.price = sectTradePriceFluctuation(
        static_cast<int64_t>(kHerbBasePrice[tpl.rarity]), rngLocal);
    item.quantity = sectTradeStock(rngLocal, "herb", rarity);
    item.obtainedYear = year;
    item.obtainedMonth = 1;
    return item;
}

/// 种子类商品（Kotlin generateSeedItem）：getSeedsByRarity 空 → null
inline std::optional<state::MerchantItem> generateTradeSeedItem(
    rng::DeterministicRng& rngLocal, int32_t rarity, int32_t year) {
    const auto& templates = data::seedTemplates();
    std::vector<const data::SeedTemplate*> pool;
    for (const auto& t : templates) {
        if (t.rarity == rarity) pool.push_back(&t);
    }
    if (pool.empty()) return std::nullopt;
    const auto& tpl = *pool[static_cast<std::size_t>(
        rngLocal.nextInt(static_cast<int32_t>(pool.size())))];
    state::MerchantItem item;
    item.id = nextTradeItemId();
    item.name = tpl.name;
    item.type = "seed";
    item.itemId = nextTradeItemId();
    item.rarity = tpl.rarity;
    // Kotlin Seed.price 为派生 getter（GameConfig.Rarity.seedPrice）
    item.price = sectTradePriceFluctuation(
        static_cast<int64_t>(kSeedBasePrice[tpl.rarity]), rngLocal);
    item.quantity = sectTradeStock(rngLocal, "seed", rarity);
    item.obtainedYear = year;
    item.obtainedMonth = 1;
    return item;
}

/// 灵石类商品（Kotlin generateSpiritStoneItem）：品阶超当年上限 → null
///（零 RNG）；非空 → 价格波动 1×nextDouble + 库存 1×nextInt（≤3）
inline std::optional<state::MerchantItem> generateTradeSpiritStoneItem(
    rng::DeterministicRng& rngLocal, int32_t rarity, int32_t year) {
    const auto mapped = sectTradeSpiritStone(rarity, year);
    if (!mapped.has_value()) return std::nullopt;
    state::MerchantItem item;
    item.id = nextTradeItemId();
    item.name = (rarity >= 4) ? "上品灵石" : "中品灵石";
    item.type = "spiritStone";
    item.itemId = nextTradeItemId();
    item.rarity = mapped->first;
    item.price = sectTradePriceFluctuation(mapped->second, rngLocal);
    item.quantity = std::min(sectTradeStock(rngLocal, "spiritStone", rarity), 3);
    item.obtainedYear = year;
    item.obtainedMonth = 1;
    return item;
}

/// 生成宗门交易物品列表（Kotlin DiplomacyService.generateSectTradeItems）：
/// 局部种子 RNG——20 个条目、7 类型随机（1×nextInt(7)）、品阶曲线抽样
/// （1×nextDouble）、类型内生成、名称去重、最多 60 次尝试；按品阶降序
/// 稳定排序（Kotlin sortedByDescending 稳定）。
inline std::vector<state::MerchantItem> generateSectTradeItems(
    int32_t year, const std::string& sectId) {
    auto rngLocal = rng::DeterministicRng::fromSeed(sectTradeSeed(sectId, year));
    std::vector<state::MerchantItem> items;
    std::set<std::string> generatedNames;
    int32_t attempts = 0;
    while (static_cast<int32_t>(items.size()) < kSectTradeItemCount &&
           attempts < kSectTradeMaxAttempts) {
        ++attempts;
        // 7 类型随机（Kotlin types[rngLocal.nextInt(7)]）
        static const char* kTypes[7] = {
            "equipment", "manual", "pill", "material", "herb", "seed", "spiritStone"};
        const char* type = kTypes[rngLocal.nextInt(7)];
        // 品阶曲线（1×nextDouble——RarityTimeProgression.rollRarity）
        const int32_t rarity = rollRarity(rngLocal, year);
        std::optional<state::MerchantItem> item;
        if (std::strcmp(type, "equipment") == 0) {
            item = generateTradeEquipmentItem(rngLocal, rarity, year);
        } else if (std::strcmp(type, "manual") == 0) {
            item = generateTradeManualItem(rngLocal, rarity, year);
        } else if (std::strcmp(type, "pill") == 0) {
            item = generateTradePillItem(rngLocal, rarity, year);
        } else if (std::strcmp(type, "material") == 0) {
            item = generateTradeMaterialItem(rngLocal, rarity, year);
        } else if (std::strcmp(type, "herb") == 0) {
            item = generateTradeHerbItem(rngLocal, rarity, year);
        } else if (std::strcmp(type, "seed") == 0) {
            item = generateTradeSeedItem(rngLocal, rarity, year);
        } else {
            item = generateTradeSpiritStoneItem(rngLocal, rarity, year);
        }
        if (!item.has_value()) continue;
        if (generatedNames.count(item->name) != 0) continue;
        generatedNames.insert(item->name);
        items.push_back(std::move(*item));
    }
    std::stable_sort(items.begin(), items.end(),
        [](const state::MerchantItem& a, const state::MerchantItem& b) {
            return a.rarity > b.rarity;
        });
    return items;
}

/// 交易刷新判据（Kotlin shouldRefreshSectTrade：距上次刷新满 3 年，或
/// 列表空兜底；tradeLastRefreshYear 未来值按 0 自愈）
inline bool shouldRefreshSectTrade(int32_t year, const state::SectDetail& detail) {
    const int32_t lastRefresh =
        detail.tradeLastRefreshYear > year ? 0 : detail.tradeLastRefreshYear;
    return year - lastRefresh >= kSectTradeRefreshIntervalYears ||
           detail.tradeItems.empty();
}

/// 年度强制刷新所有 AI 宗门交易列表（Kotlin DiplomacyService.
/// refreshAllSectTrades）：sectDetails 空早退；遍历 worldMapSects 跳过
/// 玩家宗门/无详情；判据满足 → 局部种子生成；统一写回 sectDetails
///（tradeLastRefreshYear = year）。零分区 RNG 消耗。
inline void refreshAllSectTrades(GameState& state, int32_t year) {
    auto& gd = state.gameData;
    if (gd.sectDetails.empty()) return;
    std::map<std::string, std::vector<state::MerchantItem>> refreshed;
    for (const auto& sect : gd.worldMapSects) {
        if (sect.isPlayerSect) continue;
        auto it = gd.sectDetails.find(sect.id);
        if (it == gd.sectDetails.end()) continue;
        if (!shouldRefreshSectTrade(year, it->second)) continue;
        refreshed[sect.id] = generateSectTradeItems(year, sect.id);
    }
    if (refreshed.empty()) return;
    auto updated = gd.sectDetails;
    for (const auto& kv : refreshed) {
        state::SectDetail detail = updated.count(kv.first) != 0
            ? updated.at(kv.first) : state::SectDetail{};
        detail.sectId = kv.first;
        detail.tradeItems = kv.second;
        detail.tradeLastRefreshYear = year;
        updated[kv.first] = std::move(detail);
    }
    gd.sectDetails = std::move(updated);
}

/// T2-⑥ 联盟到期解散（Kotlin DiplomacyEventProcessor.checkAllianceExpiry）：
/// 年差 >= ALLIANCE_DURATION_YEARS(5) 的联盟到期——从 alliances 移除 +
/// 成员宗门 worldMapSects.allianceId/allianceStartYear 清零。零 RNG。
inline void processAllianceExpiry(GameState& state, int32_t year) {
    auto& gd = state.gameData;
    std::vector<state::Alliance> expired;
    for (const auto& a : gd.alliances) {
        if (year - a.startYear >= kAllianceDurationYears) expired.push_back(a);
    }
    if (expired.empty()) return;
    std::vector<state::Alliance> remaining;
    for (const auto& a : gd.alliances) {
        if (year - a.startYear < kAllianceDurationYears) remaining.push_back(a);
    }
    std::vector<state::WorldSect> sects = gd.worldMapSects;
    for (auto& sect : sects) {
        for (const auto& a : expired) {
            const bool isMember =
                std::find(a.sectIds.begin(), a.sectIds.end(), sect.id) != a.sectIds.end();
            if (isMember) { sect.allianceId = ""; sect.allianceStartYear = 0; break; }
        }
    }
    gd.alliances = std::move(remaining);
    gd.worldMapSects = std::move(sects);
}

/// T2-⑦ 联盟好感度过低自动解散（Kotlin FavorEventProcessor.
/// checkAllianceFavorDrop）：玩家参与的联盟——盟友好感 < MIN_ALLIANCE_FAVOR(80)
/// → 解散（移除联盟 + 成员宗门清 alliance 字段）。零 RNG。
inline void processAllianceFavorDrop(GameState& state) {
    auto& gd = state.gameData;
    // 玩家宗门 id（"player" 哨兵——Kotlin sectIds.contains("player")）
    std::string playerSectId;
    for (const auto& sect : gd.worldMapSects) {
        if (sect.isPlayerSect) { playerSectId = sect.id; break; }
    }
    std::vector<state::Alliance> dissolved;
    for (const auto& alliance : gd.alliances) {
        const bool hasPlayer =
            std::find(alliance.sectIds.begin(), alliance.sectIds.end(), "player") !=
            alliance.sectIds.end();
        if (!hasPlayer) continue;
        const std::string* sectId = nullptr;
        for (const auto& sid : alliance.sectIds) {
            if (sid != "player") { sectId = &sid; break; }
        }
        if (sectId == nullptr || playerSectId.empty()) continue;
        const int32_t favor = findRelationFavor(gd.sectRelations, playerSectId, *sectId);
        if (favor < kMinAllianceFavor) dissolved.push_back(alliance);
    }
    if (dissolved.empty()) return;
    std::vector<state::Alliance> remaining;
    for (const auto& a : gd.alliances) {
        const bool isDissolved =
            std::find_if(dissolved.begin(), dissolved.end(),
                         [&](const state::Alliance& x) { return x.id == a.id; }) !=
            dissolved.end();
        if (!isDissolved) remaining.push_back(a);
    }
    std::vector<state::WorldSect> sects = gd.worldMapSects;
    for (auto& sect : sects) {
        for (const auto& a : dissolved) {
            const bool isMember =
                std::find(a.sectIds.begin(), a.sectIds.end(), sect.id) != a.sectIds.end();
            if (isMember) { sect.allianceId = ""; sect.allianceStartYear = 0; break; }
        }
    }
    gd.alliances = std::move(remaining);
    gd.worldMapSects = std::move(sects);
}

/// T2-⑨ 好感度自然衰减（Kotlin FavorEventProcessor.processFavorDecay）：
/// 玩家相关 + 已相识 + shouldDecay（favor>80 且距上次交互 ≥1 年）→
/// favor 减 1（coerceAtLeast 80）+ noGiftYears+1；有变化才写回。零 RNG。
inline void processFavorDecay(GameState& state, int32_t currentYear) {
    auto& gd = state.gameData;
    std::string playerSectId;
    for (const auto& sect : gd.worldMapSects) {
        if (sect.isPlayerSect) { playerSectId = sect.id; break; }
    }
    if (playerSectId.empty()) return;
    std::vector<state::SectRelation> updated = gd.sectRelations;
    bool changed = false;
    for (auto& relation : updated) {
        if (!relation.acquainted) continue;
        const bool involvesPlayer =
            relation.sectId1 == playerSectId || relation.sectId2 == playerSectId;
        if (!involvesPlayer) continue;
        if (relation.favor <= kFavorDecayThreshold) continue;
        const int32_t yearsSinceGift = currentYear - relation.lastInteractionYear;
        if (yearsSinceGift < kFavorDecayNoGiftYears) continue;
        relation.favor = std::max(relation.favor - kFavorDecayAmount, kFavorDecayThreshold);
        relation.noGiftYears += 1;
        changed = true;
    }
    if (changed) gd.sectRelations = std::move(updated);
}

/// T2-⑩ 哀悼期到期（Kotlin DiscipleLifecycleProcessor.processGriefExpiry）：
/// griefEndYears 列直写——到期（griefEnd != -1 且 currentYear >= griefEnd）→ 置 -1。
/// 零 RNG。
inline void processGriefExpiry(GameState& state, int32_t currentYear) {
    state::DiscipleStore& ds = state.disciples;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.griefEndYears[row] != kGriefYearNullSentinel &&
            currentYear >= ds.griefEndYears[row]) {
            ds.griefEndYears[row] = kGriefYearNullSentinel;
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 批 Y-2：年变中件下沉（T1-⑨ 条件 SYSTEM + T1-⑩ 驻军轮换）
// ════════════════════════════════════════════════════════════════

/// 思过释放道德增量（DiscipleLifecycleProcessor.REFLECTION_RELEASE_MORALITY_BONUS）
constexpr int32_t kReflectionReleaseMoralityBonus = 5;
/// 思过释放忠诚增量（DiscipleLifecycleProcessor.REFLECTION_RELEASE_LOYALTY_BONUS）
constexpr int32_t kReflectionReleaseLoyaltyBonus = 5;
/// 驻军槽位数量（AISectGarrisonManager.GARRISON_SLOT_COUNT）
constexpr int32_t kGarrisonSlotCount = 10;
/// 驻军留守名额（AISectGarrisonManager：占领者最强 10 名留守宗门）
constexpr int32_t kGarrisonStayCount = 10;

/// 灵根数 → 颜色（Kotlin SpiritRoot.countColor：1..5 固定色，其余兜底灰）
inline std::string spiritRootCountColor(const std::string& spiritRootType) {
    int32_t count = 1;
    if (!spiritRootType.empty()) {
        count = 1 + static_cast<int32_t>(std::count(
            spiritRootType.begin(), spiritRootType.end(), ','));
    }
    switch (count) {
        case 1: return "#E74C3C";
        case 2: return "#F39C12";
        case 3: return "#9B59B6";
        case 4: return "#27AE60";
        default: return "#95A5A6";
    }
}

/// T1-⑨ 思过到期释放（Kotlin DiscipleLifecycleProcessor.
/// processReflectionRelease）：到期（statusData.reflectionEndYear <= year）
/// 思过弟子释放为 IDLE + 清思过字段 + 道德/忠诚 +5（cap 200/100）；
/// 释放后道德 < 偷盗阈值 → 单弟子偷盗判定（SYSTEM 钩子——judgeSingleTheftCandidate
/// 与月变教化之道钩子同源，抽取序逐位一致）。RNG：条件性 SYSTEM（仅道德<阈值
/// 弟子触发，每名 1..6 次判定抽取）。
inline void processReflectionRelease(GameState& state, int32_t year,
                                     rng::RngManager& rng) {
    auto& gd = state.gameData;
    auto& ds = state.disciples;
    auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    const int32_t currentMonth = gd.gameYear * 12 + gd.gameMonth;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] != 1) continue;
        if (ds.statuses[row] != "REFLECTING") continue;
        const auto endIt = ds.statusData[row].find("reflectionEndYear");
        if (endIt == ds.statusData[row].end()) continue;
        const auto endYearOpt = settle_util::toIntOrNull(endIt->second);
        if (!endYearOpt.has_value() || year < *endYearOpt) continue;
        // 释放：IDLE + 清思过字段 + 道德/忠诚 +5（cap）
        ds.statuses[row] = "IDLE";
        ds.statusData[row].erase("reflectionStartYear");
        ds.statusData[row].erase("reflectionEndYear");
        ds.moralities[row] =
            std::min(ds.moralities[row] + kReflectionReleaseMoralityBonus,
                     stats::kSkillMax);
        ds.loyalties[row] =
            std::min(ds.loyalties[row] + kReflectionReleaseLoyaltyBonus,
                     kMaxLoyalty);
        // 道德 < 阈值 → 单弟子偷盗判定（与 Kotlin 事务内版一致）
        if (ds.moralities[row] < lawMoralityThreshold()) {
            const auto idOpt = settle_util::toIntOrNull(ds.ids[row]);
            if (idOpt.has_value()) {
                judgeSingleTheftCandidate(state, *idOpt, currentMonth,
                                          rngSystem);
            }
        }
    }
}

/// T1-⑩ 占领宗门驻军轮换（Kotlin AISectGarrisonManager.rotateGarrisonSlots）：
/// 玩家宗门在场 + 存在 AI 占领宗门（occupierSectId 非空且非玩家）时——按占领者
/// 分组，每占领者存活弟子按 realm 升序（1 最强 → 9 最弱），前 10 名留守宗门、
/// 第 11 名起逐占领宗门填满 GARRISON_SLOT_COUNT(10) 个驻军槽。
/// 分组顺序不影响结果（各占领者独立构建候选池）——std::map 键升序与 Kotlin
/// groupBy 插入序结果等价。零 RNG。
inline void processGarrisonRotation(GameState& state) {    auto& gd = state.gameData;
    std::string playerSectId;
    for (const auto& sect : gd.worldMapSects) {
        if (sect.isPlayerSect) { playerSectId = sect.id; break; }
    }
    if (playerSectId.empty()) return;

    std::vector<const state::WorldSect*> occupiedByAi;
    for (const auto& sect : gd.worldMapSects) {
        if (!sect.isPlayerSect && !sect.occupierSectId.empty() &&
            sect.occupierSectId != playerSectId) {
            occupiedByAi.push_back(&sect);
        }
    }
    if (occupiedByAi.empty()) return;

    std::map<std::string, std::vector<const state::WorldSect*>> grouped;
    for (const auto* sect : occupiedByAi) {
        grouped[sect->occupierSectId].push_back(sect);
    }
    std::vector<state::WorldSect> updated = gd.worldMapSects;

    for (const auto& kv : grouped) {
        const std::string& occupierId = kv.first;
        const auto it = state.aiSectDisciples.find(occupierId);
        if (it == state.aiSectDisciples.end()) continue;
        std::vector<const state::Disciple*> alive;
        for (const auto& d : it->second) {
            if (d.isAlive) alive.push_back(&d);
        }
        if (alive.empty()) continue;
        std::stable_sort(alive.begin(), alive.end(),
                         [](const state::Disciple* a, const state::Disciple* b) {
                             return a->realm < b->realm;
                         });
        // 前 10 留守，第 11 名起外派
        std::vector<const state::Disciple*> pool;
        for (std::size_t i = static_cast<std::size_t>(kGarrisonStayCount);
             i < alive.size(); ++i) {
            pool.push_back(alive[i]);
        }
        for (const auto* sect : kv.second) {
            std::vector<state::GarrisonSlot> newSlots;
            newSlots.reserve(static_cast<std::size_t>(kGarrisonSlotCount));
            for (int32_t index = 0; index < kGarrisonSlotCount; ++index) {
                if (!pool.empty()) {
                    const state::Disciple* d = pool.front();
                    pool.erase(pool.begin());
                    state::GarrisonSlot slot;
                    slot.index = index;
                    slot.discipleId = d->id;
                    slot.discipleName = d->name;
                    slot.discipleRealm = realmName(d->realm);
                    slot.discipleSpiritRootColor =
                        spiritRootCountColor(d->spiritRootType);
                    slot.portraitRes = d->portraitRes;
                    newSlots.push_back(std::move(slot));
                } else {
                    state::GarrisonSlot slot;
                    slot.index = index;
                    newSlots.push_back(std::move(slot));
                }
            }
            for (auto& s : updated) {
                if (s.id == sect->id) {
                    s.garrisonSlots = std::move(newSlots);
                    break;
                }
            }
        }
    }
    gd.worldMapSects = std::move(updated);
}

/// T2-⑪ 远古秘境年变刷新（Kotlin SecretRealmService.processYearlySpawn）：
/// 未现世（secretRealmState 空）且冷却满（year - cooldown(coerceAtLeast 0) >= 50）
/// → SECRET_REALM 分区：findSecretRealmPosition（≤100 次尝试 × 2 nextInt +
/// 兜底扫描零 RNG）→ 1×nextInt(SPRITE_VARIANT_COUNT) 精灵变体 → 写
/// SecretRealmState（id 为镜像生成字段——Kotlin UUID，C++ 空串占位，diff 排除）
/// + SECT secret_realm 事件。RNG 消费序：位置尝试 → 变体（与 Kotlin 一致）。
inline void processAncientSecretRealmSpawn(GameState& state, int32_t year,
                                           rng::RngManager& rng) {
    auto& gd = state.gameData;
    if (!gd.secretRealmState.id.empty()) return;
    const int32_t cooldown = std::max(gd.secretRealmCooldownYear, 0);
    if (!secretRealmYearlySpawnEligible(year, cooldown)) return;

    const auto pos = findSecretRealmPosition(rng, gd.worldMapSects);
    state::SecretRealmState realm;
    realm.id = "";   // UUID 镜像生成字段（Kotlin UUID.randomUUID）
    realm.x = static_cast<float>(pos.first);
    realm.y = static_cast<float>(pos.second);
    realm.spawnYear = year;
    realm.spawnMonth = gd.gameMonth;
    realm.spriteIndex = rollSecretRealmSpriteIndex(rng);
    gd.secretRealmState = std::move(realm);
    settle_util::recordGameEvent(
        state, "SECT", "secret_realm",
        "远古秘境现世！传说中上古大能陨落之地，藏有无数机缘与凶险");
}

/// 纳徒长老魅力加成（Kotlin RecruitService.calcRecruitBonusCap：
/// max(0, (charm-80)/4) 整数除法截断，coerceAtMost 20）
inline int32_t recruitBonusCap(int32_t charm) {
    const int32_t raw = std::max(0, (charm - kRecruitCharmBaseline) / kRecruitCharmDivisor);
    return std::min(raw, kMaxRecruitBonusCap);
}

/// T1-④ 年度招募列表刷新（Kotlin RecruitService.refreshRecruitList）：
/// 玩家宗门等级招募范围（1..4/1..6/1..10/1..15）+ 纳徒长老魅力加成（含职务
/// 加成乘算）→ SYSTEM 数量抽取；无玩家宗门兜底 nextInt(7) coerceAtLeast 1；
/// 广纳门徒政策 +50%（roundToInt）；逐弟子生成（SYSTEM 分区串行：性别 1×
/// nextInt(2) → 名字 generateName（FULL 姓氏 nextInt + 给定名 nextDouble+
/// nextInt）→ 灵根 SpiritRootGenerator（nextDouble+洗牌）→ 年龄
/// 16+nextInt(14) → DiscipleFactory.create 固定序）→ recruitList 追加 +
/// lastRecruitYear + 惰性门重置 + processAutoRecruit。id 为镜像生成字段
///（Kotlin UUID，C++ 空串占位，diff 排除）。RNG 消费序逐位对齐 Kotlin。
inline void processRefreshRecruitList(GameState& state, int32_t year,
                                      rng::RngManager& rng) {
    auto& gd = state.gameData;
    auto& ds = state.disciples;
    // 差值判据（Kotlin CultivationEventProcessor.RECRUIT_REFRESH_INTERVAL_YEARS=3：
    // 老档相位漂移自愈；失败时 lastRecruitYear 不更新，次年自动重试）
    if (year - gd.lastRecruitYear < kRecruitRefreshIntervalYears) return;
    auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);

    // 招募数量（玩家宗门等级 range + 长老加成；无玩家宗门兜底）
    int32_t recruitCount = 0;
    bool hasPlayerSect = false;
    for (const auto& sect : gd.worldMapSects) {
        if (!sect.isPlayerSect) continue;
        hasPlayerSect = true;
        int32_t rangeFirst = 1;
        int32_t rangeLast = 4;
        switch (sect.level) {
            case 1: rangeFirst = 1; rangeLast = 6; break;   // MEDIUM
            case 2: rangeFirst = 1; rangeLast = 10; break;  // LARGE
            case 3: rangeFirst = 1; rangeLast = 15; break;  // TOP
            default: rangeFirst = 1; rangeLast = 4; break;  // SMALL/兜底
        }
        // 纳徒长老魅力加成（魅力 + 职务加成乘算）
        int32_t bonusCap = 0;
        const std::string& elderId = gd.elderSlots.recruitingElder;
        if (!elderId.empty()) {
            const auto intId = settle_util::toIntOrNull(elderId);
            if (intId.has_value()) {
                for (std::size_t row = 0; row < ds.size(); ++row) {
                    if (settle_util::toIntOrNull(ds.ids[row]) == intId) {
                        const int32_t charm = ds.charms[row];
                        const double posBonus =
                            stats::positionEffectBonus(ds, row, "RECRUITING");
                        bonusCap = static_cast<int32_t>(
                            static_cast<double>(recruitBonusCap(charm)) *
                            (1.0 + posBonus));
                        break;
                    }
                }
            }
        }
        const int32_t until = rangeLast + 1 + bonusCap;
        recruitCount = (until <= rangeFirst)
            ? rangeFirst
            : rangeFirst + rngSystem.nextInt(until - rangeFirst);
        break;
    }
    if (!hasPlayerSect) {
        // 兜底：nextInt(7) → 0..6 coerceAtLeast 1
        recruitCount = std::max(rngSystem.nextInt(kFallbackRecruitCount), 1);
    }
    // 广纳门徒政策：招募数 +50%（roundToInt）
    if (recruitCount > 0 && gd.sectPolicies.openRecruitment) {
        recruitCount = static_cast<int32_t>(
            std::round(static_cast<double>(recruitCount) *
                       (1.0 + kOpenRecruitmentPoolBonus)));
    }

    // 逐弟子生成（SYSTEM 分区串行，消费序与 Kotlin 逐位一致）
    std::vector<state::Disciple> newRecruits;
    newRecruits.reserve(static_cast<std::size_t>(recruitCount));
    std::set<std::string> usedNames;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        usedNames.insert(ds.names[row]);
    }
    for (const auto& r : gd.recruitList) usedNames.insert(r.name);
    for (int32_t i = 0; i < recruitCount; ++i) {
        const std::string gender = (rngSystem.nextInt(2) == 0) ? "male" : "female";
        const auto nameResult = generateName(gender, NameStyle::kFull,
                                             usedNames, rngSystem);
        const std::string spiritRoot = child_birth::generateSpiritRoot(rngSystem);
        const int32_t age = kRecruitAgeMin + rngSystem.nextInt(kRecruitAgeRange);
        DiscipleCreationSeed seed;
        seed.id = "";   // 镜像生成字段（Kotlin UUID）
        seed.gender = gender;
        seed.fullName = nameResult.fullName;
        seed.surname = nameResult.surname;
        seed.spiritRootType = spiritRoot;
        seed.age = age;
        seed.realm = 9;
        seed.realmLayer = 1;
        state::Disciple d = createDisciple(seed, rngSystem);
        usedNames.insert(d.name);
        newRecruits.push_back(std::move(d));
    }
    gd.recruitList.insert(gd.recruitList.end(),
                          std::make_move_iterator(newRecruits.begin()),
                          std::make_move_iterator(newRecruits.end()));
    gd.lastRecruitYear = year;
    // 新增弟子 → 重置惰性（autoRecruitIdle/autoRejectIdle）+ 自动招募
    state.autoRecruitIdle = false;
    state.autoRejectIdle = false;
    recruit_settle::processAutoRecruit(state);
}

// ════════════════════════════════════════════════════════════════
// 批 Y-3（T1-③ 弟子老化死亡链）：C++ 状态面 + 平台效应草稿
// Kotlin DiscipleLifecycleProcessor.processDiscipleAging 等价移植——
// 老化判定（age+1、5 岁境界层回正、computeMaxAge 寿元耗尽）→ 逐死者
// 状态面（11 槽清理/哀悼传播/道侣师徒解绑/血炼清理/袋物品草稿/装备功法
// 清除/死亡记录/事件/年死亡计数）→ 统一移除 + 活弟子老化。
// 平台效应（袋物品物化回仓库含溢出邮件/DAO 清理/DeathEvent/死亡记录档案/
// lifeEvents 丧亲事件）经 YearSettlementDraft 草稿回传 Kotlin 残留执行器。
// ════════════════════════════════════════════════════════════════

/// 亲属判定（Kotlin DiscipleStatCalculator.areRelatives：道侣/父母/子女/兄弟姐妹）
inline bool isRelatives(const state::DiscipleStore& ds, std::size_t a, std::size_t b) {
    // 道侣
    if (!ds.partnerIds[a].empty() && ds.partnerIds[a] == ds.ids[b]) return true;
    if (!ds.partnerIds[b].empty() && ds.partnerIds[b] == ds.ids[a]) return true;
    // 父母-子女
    if (!ds.parentId1s[a].empty() && ds.parentId1s[a] == ds.ids[b]) return true;
    if (!ds.parentId2s[a].empty() && ds.parentId2s[a] == ds.ids[b]) return true;
    if (!ds.parentId1s[b].empty() && ds.parentId1s[b] == ds.ids[a]) return true;
    if (!ds.parentId2s[b].empty() && ds.parentId2s[b] == ds.ids[a]) return true;
    // 兄弟姐妹（共同父母；Kotlin 单亲也支持）
    const std::string& a1 = ds.parentId1s[a];
    const std::string& a2 = ds.parentId2s[a];
    if (a1.empty() && a2.empty()) return false;
    return (!a1.empty() && (a1 == ds.parentId1s[b] || a1 == ds.parentId2s[b])) ||
           (!a2.empty() && (a2 == ds.parentId1s[b] || a2 == ds.parentId2s[b]));
}

/// 哀悼期传播 + 丧亲草稿（Kotlin computeGriefEndYearMap +
/// computeBereavementRecords——列行版：对死者行，存活且非本人且 isRelatives →
/// griefEndYear = max(既有, currentYear+1) 列写；新进入哀悼者（原列哨兵 -1）
/// 生成丧亲记录草稿——关系文本按列直读（道侣/父/母/亲属，第 4 分支"子女"
/// 因对称不可达输出"亲属"——对齐 Kotlin 注释））
inline void applyGriefToRelativesStep(state::DiscipleStore& ds,
                                      std::size_t deadRow,
                                      int32_t currentYear,
                                      YearSettlementDraft* draft) {
    const int32_t griefEndYear = currentYear + 1;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (row == deadRow) continue;
        if (ds.isAlive[row] != 1) continue;
        if (!isRelatives(ds, row, deadRow)) continue;
        const int32_t existing = ds.griefEndYears[row];
        const int32_t newEnd =
            (existing != kGriefYearNullSentinel && existing > griefEndYear)
                ? existing
                : griefEndYear;
        ds.griefEndYears[row] = newEnd;
        // 新进入哀悼者（原哨兵）→ 丧亲草稿（Kotlin computeBereavementRecords）
        if (existing == kGriefYearNullSentinel && draft != nullptr) {
            BereavementDraft bd;
            const std::string& deadId = ds.ids[deadRow];
            const std::string relationship =
                (!ds.partnerIds[row].empty() && ds.partnerIds[row] == deadId) ? "道侣"
                : (!ds.parentId1s[row].empty() && ds.parentId1s[row] == deadId) ? "父/母"
                : (!ds.parentId2s[row].empty() && ds.parentId2s[row] == deadId) ? "父/母"
                : "亲属";
            const auto gid = settle_util::toIntOrNull(ds.ids[row]);
            if (gid.has_value()) {
                bd.grievingId = *gid;
                bd.relationship = relationship;
                bd.deceasedName = ds.names[deadRow];
                bd.grievingAge = ds.ages[row];
                draft->bereavements.push_back(std::move(bd));
            }
        }
    }
}

/// 道侣解绑（Kotlin unbindPartnerColumns——列行版：清空死者伴侣行指向）
inline void unbindPartnerColumnsStep(state::DiscipleStore& ds,
                                     std::size_t deadRow) {
    const auto partnerInt = settle_util::toIntOrNull(ds.partnerIds[deadRow]);
    if (!partnerInt.has_value()) return;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (settle_util::toIntOrNull(ds.ids[row]) == partnerInt) {
            ds.partnerIds[row].clear();
            return;
        }
    }
}

/// 师徒解绑（Kotlin unbindMasterColumns：扫描 masterIds 列清空指向死者的徒弟行）
inline void unbindMasterColumnsStep(state::DiscipleStore& ds,
                                    const std::string& deadId) {
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.masterIds[row] == deadId) ds.masterIds[row].clear();
    }
}

/// 11 槽位清理（Kotlin clearAllSlotsState——SlotCleanupInput 构造 + 应用；
/// slot_cleanup.h 的 SlotCleanupInput/Result 定义于 gamecore::system 直接）
inline void applySlotCleanupStep(GameState& state, const std::string& discipleId) {
    auto& gd = state.gameData;
    SlotCleanupInput in;
    in.spiritMineSlots = gd.spiritMineSlots;
    in.librarySlots = gd.librarySlots;
    in.elderSlots = gd.elderSlots;
    in.residenceSlots = gd.residenceSlots;
    in.activeBloodRefinements = gd.activeBloodRefinements;
    in.patrolSlots = gd.patrolSlots;
    in.warehouseGarrisons = gd.warehouseGarrisons;
    in.battleTeams = gd.battleTeams;
    in.worldMapSects = gd.worldMapSects;
    in.productionSlots = gd.productionSlots;
    in.caveExplorationTeams = gd.caveExplorationTeams;
    in.activeMissions = gd.activeMissions;
    const SlotCleanupResult out =
        clearAllSlotsDataOnly(in, discipleId, /*includeResidence=*/true);
    gd.spiritMineSlots = out.spiritMineSlots;
    gd.librarySlots = out.librarySlots;
    gd.elderSlots = out.elderSlots;
    gd.residenceSlots = out.residenceSlots;
    gd.activeBloodRefinements = out.activeBloodRefinements;
    gd.patrolSlots = out.patrolSlots;
    gd.warehouseGarrisons = out.warehouseGarrisons;
    gd.battleTeams = out.battleTeams;
    gd.worldMapSects = out.worldMapSects;
    gd.productionSlots = out.productionSlots;
    gd.caveExplorationTeams = out.caveExplorationTeams;
    gd.activeMissions = out.activeMissions;
}

/// 装备/功法清除（Kotlin：四槽装备 id + 功法 id 从实例集合过滤）
inline void clearEquipmentAndManuals(GameState& state, std::size_t deadRow) {
    auto& ds = state.disciples;
    std::set<std::string> deleteEquipIds;
    if (!ds.weaponIds[deadRow].empty()) deleteEquipIds.insert(ds.weaponIds[deadRow]);
    if (!ds.armorIds[deadRow].empty()) deleteEquipIds.insert(ds.armorIds[deadRow]);
    if (!ds.bootsIds[deadRow].empty()) deleteEquipIds.insert(ds.bootsIds[deadRow]);
    if (!ds.accessoryIds[deadRow].empty()) deleteEquipIds.insert(ds.accessoryIds[deadRow]);
    const std::set<std::string> deleteManualIds(
        ds.manualIds[deadRow].begin(), ds.manualIds[deadRow].end());
    auto& eq = state.equipmentInstances;
    eq.erase(std::remove_if(eq.begin(), eq.end(),
                            [&](const state::EquipmentInstance& e) {
                                return deleteEquipIds.count(e.id) != 0;
                            }),
             eq.end());
    auto& mn = state.manualInstances;
    mn.erase(std::remove_if(mn.begin(), mn.end(),
                            [&](const state::ManualInstance& m) {
                                return deleteManualIds.count(m.id) != 0;
                            }),
             mn.end());
}

/// T1-③ 弟子老化死亡链主入口（Kotlin DiscipleLifecycleProcessor.
/// processDiscipleAging 等价——状态面 + 平台效应草稿）。
/// 零 RNG。多死者顺序：逐死者状态面（列操作，remove 前列号有效）→
/// 统一 removeById（旋转同步索引）→ 活弟子老化。
inline void processDiscipleAgingStep(GameState& state, int32_t currentYear,
                                     YearSettlementDraft* draft) {
    auto& ds = state.disciples;
    auto& gd = state.gameData;

    // 1. 老化判定（物化快照：age+1、5 岁境界层回正 + status=IDLE、computeMaxAge）
    std::vector<std::size_t> deadRows;
    std::vector<state::Disciple> agedSnapshots;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] != 1) continue;
        state::Disciple aged = ds.materialize(row);
        aged.age += 1;
        if (aged.age == 5 && aged.realmLayer == 0) {
            aged.realmLayer = 1;
            aged.status = "IDLE";
        }
        if (aged.age >= discipleAgeMax(aged)) {
            deadRows.push_back(row);
            agedSnapshots.push_back(std::move(aged));
        }
    }

    // 2. 逐死者状态面（列操作，行号有效）
    for (std::size_t i = 0; i < deadRows.size(); ++i) {
        const std::size_t deadRow = deadRows[i];
        const std::string& id = ds.ids[deadRow];
        // 槽位清理（11 类）
        applySlotCleanupStep(state, id);
        // 哀悼期传播 + 丧亲草稿
        applyGriefToRelativesStep(ds, deadRow, currentYear, draft);
        // 道侣/师徒解绑
        unbindPartnerColumnsStep(ds, deadRow);
        unbindMasterColumnsStep(ds, id);
        // 血炼清理
        gd.bloodRefinementBonusTotals.erase(id);
        gd.bloodRefinements.erase(id);
        // 装备/功法清除
        clearEquipmentAndManuals(state, deadRow);
        // 死亡草稿（平台效应：袋物品物化/DAO/DeathEvent/死亡记录）
        if (draft != nullptr) {
            AgedDeathDraft ad;
            const auto& aged = agedSnapshots[i];
            ad.discipleId = id;
            ad.name = aged.name;
            ad.surname = aged.surname;
            ad.age = aged.age;
            ad.realm = aged.realm;
            ad.realmLayer = aged.realmLayer;
            ad.deathYear = currentYear;
            ad.cause = "age";
            ad.storageBagItems = aged.storageBagItems;
            draft->agedDeaths.push_back(std::move(ad));
        }
        // 死亡记录（列：deathYears = currentYear；remove 前写）
        ds.deathYears[deadRow] = currentYear;
        gd.annualDeceasedDisciples += 1;
        settle_util::recordGameEvent(state, "SECT", "death",
                                     ds.names[deadRow] + "陨落（寿元耗尽）",
                                     id, ds.names[deadRow]);
    }

    // 3. 统一移除死亡弟子（removeById——旋转同步索引）
    for (const auto& aged : agedSnapshots) {
        ds.removeById(aged.id);
    }

    // 4. 活弟子老化（age+1 + 5 岁境界层回正——跳过死亡）
    std::set<std::string> deadIds;
    for (const auto& aged : agedSnapshots) deadIds.insert(aged.id);
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] != 1) continue;
        if (deadIds.count(ds.ids[row]) != 0) continue;
        const int32_t agedAge = ds.ages[row] + 1;
        ds.ages[row] = agedAge;
        if (agedAge == 5 && ds.realmLayers[row] == 0) {
            ds.realmLayers[row] = 1;
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

// 年变平台效应草稿导出到 gamecore::system（runYearSettlement 签名 + nativeSettleYear 信封）
using detail::AgedDeathDraft;
using detail::BereavementDraft;
using detail::YearSettlementDraft;

/// 年变编排主入口（注册进 SettlementEngine::onYearChange）。
/// @param state 完整游戏状态（就地修改）
/// @param rng   RNG 分区管理器（T2-③ 收购 SYSTEM / T2-⑪ 秘境 SECRET_REALM）
/// @param aiRng AI 宗门独立分区 RNG（批 Y-4c T2-② AI 招募；种子
///   systemSeed + AI_SECT.id(6)×31337——GameCore::aiRng()）
/// @param draft 年变平台效应草稿（批 Y-3 T1-③ 死亡链：可为 null——承载
///   死亡弟子（袋物品物化/DAO 清理/DeathEvent/死亡记录）与丧亲事件
///   （lifeEvents）供 Kotlin 残留执行器消费；null 时仅状态面）
inline void runYearSettlement(state::GameState& state,
                              rng::RngManager& rng,
                              rng::DeterministicRng& aiRng,
                              YearSettlementDraft* draft = nullptr) {
    (void)rng;   // 年变 T1/T2 批 Y-1 子集零 RNG 抽取（其余项场景规避，见文件头）

    // ── processYearlyEvents(year)：T1 立即组（Kotlin 严格相对序
    // #1→#2→#3→#4→#5→#6→#7→#8→#9→#10→#11；#3/#4 大件批 Y-3 未下沉）──
    // #1 附庸年贡（T1-① 批 Y-1）
    detail::processYearlyTribute(state);
    // #2 附属宗门年贡（T1-② 批 Y-1）
    detail::processYearlyVassalTribute(state, state.gameData.gameYear);
    // #3 弟子老化死亡链（T1-③ 批 Y-3：老化判定 + 逐死者状态面（11 槽/哀悼/
    // 解绑/血炼/装备清/死亡记录/事件）+ 平台效应草稿（袋物品物化/DAO/
    // DeathEvent/死亡记录档案/丧亲 lifeEvents））
    detail::processDiscipleAgingStep(state, state.gameData.gameYear, draft);
    // #4 招募列表刷新（T1-④ 批 Y-3：SYSTEM 生成链——数量/性别/名字/灵根/
    // 弟子工厂 + 长老加成 + 广纳门徒政策；差值判据内部）
    detail::processRefreshRecruitList(state, state.gameData.gameYear, rng);
    // #5 自动拒绝（T1-⑤ 批 Y-1）
    detail::processAutoReject(state);
    // #6 商人赠予（T1-⑥ 批 Y-1：手动刷新机会）
    detail::processMerchantRefreshChance(state, state.gameData.gameYear);
    // #7 年度老化清理（T1-⑦ 批 Y-1：死亡弟子列清理）
    detail::processYearlyAging(state, state.gameData.gameYear);
    // #8 招募老化+净化（T1-⑧ 批 Y-1）
    detail::processRecruitAging(state);
    // #9 思过释放（T1-⑨ 批 Y-2：释放 + 条件性 SYSTEM 偷盗钩子）
    detail::processReflectionRelease(state, state.gameData.gameYear, rng);
    // #10 garrisonAndReport：驻军轮换（T1-⑩ 批 Y-2）+ 年报快照 + annual* 清零
    detail::processGarrisonRotation(state);
    detail::runYearlyReportSnapshot(state);
    // #11 autoBuy（批 11-3 merchant_settlement.h 已下沉；年变 T1 无条件调用
    // executeAutoBuy——与月变 12 月同函数，1 月执行新年购买）
    merchant_settle::executeAutoBuy(state);

    // ── T2 延迟组（Kotlin yearlyOpsQueue 分帧 drain；C++ 无分帧——批 Y-1
    //    下沉零 RNG 子项按原相对序同步执行，行为基线登记见 §7.6 批 Y 计划；
    //    大件（AI 招募/交易刷新/秘境刷新）随批 Y-2/Y-3 下沉）──
    // #4 AI 弟子老化（T2-① 批 Y-1）
    detail::processSectDisciplesAging(state);
    // #10 AI 宗门周期性招募（T2-② 批 Y-4c：AI 独立分区 RNG——差值判据
    // 每 3 年；占领路由 + 尾部自动招募）
    detail::runSectRecruitmentIfDue(state, aiRng, state.gameData.gameYear);
    // #12 商人收购刷新（T2-③ 批 Y-4b：SYSTEM 分区——数量 1×nextInt(9) +
    // 每 item 品阶 1×nextDouble + 选池 1×nextInt + 库存/grade/价格波动）
    detail::refreshMerchantAcquisition(state, rng.getRng(rng::RngPartition::kSystem),
                                       state.gameData.gameYear, 1);
    // #13 AI 宗门交易列表刷新（T2-④ 批 Y-4a：局部种子——零分区 RNG）
    detail::refreshAllSectTrades(state, state.gameData.gameYear);
    // #6 联盟到期（T2-⑥ 批 Y-1）
    detail::processAllianceExpiry(state, state.gameData.gameYear);
    // #7 联盟好感衰减检查（T2-⑦ 批 Y-1）
    detail::processAllianceFavorDrop(state);
    // #9 好感衰减（T2-⑨ 批 Y-1）
    detail::processFavorDecay(state, state.gameData.gameYear);
    // #10 哀悼期到期（T2-⑩ 批 Y-1）
    detail::processGriefExpiry(state, state.gameData.gameYear);
    // #22 远古秘境年变刷新（T2-⑪ 批 Y-2：SECRET_REALM 分区——位置 + 变体）
    detail::processAncientSecretRealmSpawn(state, state.gameData.gameYear, rng);

    // ── gameMonth==1 时年俸（Kotlin processMonthYearChange 第二分支）──
    if (state.gameData.gameMonth == 1) {
        processAnnualSalary(state);
    }
}

}  // namespace gamecore::system
