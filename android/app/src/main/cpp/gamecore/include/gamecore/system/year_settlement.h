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
#include "gamecore/system/inventory.h"  // nextItemIdCounter（id 注册表）
#include "gamecore/ecs/disciple_component.h"  // syncDiscipleEntities 行序桥接
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
#include "gamecore/system/month_settlement.h"
#include "gamecore/system/name_service.h"
#include "gamecore/system/rarity_progression.h"
#include "gamecore/system/sect_trade.h"
#include "gamecore/system/secret_realm.h"
#include "gamecore/system/settlement_detail.h"

// ============================================================
// 年变结算钩子
//
// 等价移植 Kotlin GameEngineCore.processMonthYearChange 的 yearChanged 分支
// （年变先于月变——settlement.h 钩子序已对齐），Kotlin 侧由
// YearSettlementExecutor 提取同构编排。
//
// Kotlin processYearlyEvents(year) 为 L3b 分帧结构：
//   T1 立即组 8 项（单事务保相对序：yearlyTribute/yearlyVassalTribute/
//   discipleAging/merchantRefreshChance/yearlyAging/reflectionRelease/
//   garrisonAndReport/autoBuy）+ T2 延迟组 8 项（yearlyOpsQueue 由
//   引擎 tick 30ms 预算 drain / 存档前 flush / 读档 clear——C++ 无对应分帧概念）。
// C++ 编排（runYearSettlement）覆盖 T1 中 7 项（discipleAging 为 Kotlin 侧
// 状态重推导 syncAllDiscipleStatuses——幂等纯派生，C++ 列存储权威维护状态
// 无需重推导）与 T2 中 7 项（aiAlliances 经场景规避双端一致）；个别子项
// 场景规避说明（测试惰性依赖 NPE 由 safelyRunInState 捕获 ≡ no-op）。
//
// RNG：T1 全程零随机抽取；T2 仅个别子项消费（收购 SYSTEM / 秘境
// SECRET_REALM）——场景数据空（外交/秘境域数据空）
// 年变其余路径零消耗，RNG 对拍退化为"分区状态不变"断言。
// ============================================================
namespace gamecore::system {

/// 年度报告保留条数上限（GameConfig.Logs.MAX_YEARLY_REPORTS）
constexpr std::size_t kMaxYearlyReports = 100;

// ── 年变零 RNG 小件常量（逐条对齐 Kotlin 配置源）──────────
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

// ── 交易刷新常量（DiplomacyService companion 逐值对齐）──
// AI 宗门交易刷新间隔（SECT_TRADE_REFRESH_INTERVAL_YEARS）
constexpr int32_t kSectTradeRefreshIntervalYears = 3;
// 交易物品数量（generateSectTradeItems itemCount）/ 最大尝试次数（×3）
constexpr int32_t kSectTradeItemCount = 20;
constexpr int32_t kSectTradeMaxAttempts = kSectTradeItemCount * 3;
// 灵草/种子基准价（Kotlin GameConfig.Rarity.herbPrice/seedPrice——派生// getter：price = Rarity.get(rarity).herbPrice/seedPrice；下标 0 未用）
constexpr int32_t kHerbBasePrice[7] = {0, 400, 1600, 8000, 48000, 336000, 2688000};
constexpr int32_t kSeedBasePrice[7] = {0, 80, 320, 1600, 9600, 67200, 537600};
// 材料基准价（Kotlin GameConfig.Rarity.materialBasePrice——收购池 priceMap
// 兜底：池构建恒有价，仅防御性回退用）
constexpr int32_t kMaterialBasePrice[7] = {0, 400, 1600, 8000, 48000, 336000, 2688000};

namespace detail {

// ════════════════════════════════════════════════════════════════
// 弟子老化死亡链平台效应草稿——C++ 死亡链状态面（老化/槽位/血炼/
// 装备清/死亡记录）完成后，Kotlin 残留执行器经草稿执行平台效应：
// 袋物品物化回仓库（含溢出邮件）/DAO 清理/DeathEvent/死亡记录档案。
// 与秘境关闭草稿同构（信封回传）。
// 定义于 detail 前（detail 函数前置依赖），detail 结束后以
// `using detail::YearSettlementDraft` 导出到 gamecore::system。
// ════════════════════════════════════════════════════════════════

/// 死亡弟子草稿（Kotlin 残留：袋物品物化 + DAO 清理 + DeathEvent + addDeathRecord）
struct AgedDeathDraft {
    std::string discipleId;
    std::string name;
    std::string surname;
    int32_t realm = 9;
    int32_t realmLayer = 1;
    int32_t deathYear = 0;
    std::string cause = "unknown";
    std::vector<state::StorageBagItem> storageBagItems;  // 袋物品（物化回仓库）
};

/// 年变平台效应草稿集合（runYearSettlement 可选 out + nativeSettleYear 信封）
struct YearSettlementDraft {
    std::vector<AgedDeathDraft> agedDeaths;
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
/// 驻军轮换无玩家宗门时恒等返回——完整轮换见 processGarrisonRotation）。
/// takeLast(MAX_YEARLY_REPORTS=100) 裁剪语义保留。
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
    // copy 字段面逐一对应）
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
}

/// 年俸计划构建（calculateSalaryPlan 列直读版 + 幽灵防御：
/// isAlive/names/realms 三表齐全且名非空白——C++ Disciple 向量模型天然齐全，
/// 保留 name.isBlank 跳过；enabledConfig[realm]==true 且 salary>0 过滤一致）。
/// 迭代域经 sync + View<DiscipleRef> 行序
///（eligibleSalaries 序 == 行序，逐笔发放序/总和不相关但同序保持）。
inline SalaryPlan buildYearSalaryPlan(const GameState& state, ecs::World& world) {
    const GameData& gd = state.gameData;
    const DiscipleStore& ds = state.disciples;
    SalaryPlan plan;
    ecs::syncDiscipleEntities(world, ds.size());
    ecs::View<ecs::DiscipleRef> view(world.registry());
    view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
        const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
        if (ds.isAlive[row] == 0) return;
        if (yearIsBlankString(ds.names[row])) return;   // 幽灵防御：空名跳过
        const auto enabledIt = gd.yearlySalaryEnabled.find(ds.realms[row]);
        if (enabledIt == gd.yearlySalaryEnabled.end() || !enabledIt->second) {
            return;
        }
        const auto salaryIt = gd.yearlySalary.find(ds.realms[row]);
        if (salaryIt == gd.yearlySalary.end()) return;
        const int64_t salary = salaryIt->second;
        if (salary <= 0) return;
        const auto id = toIntOrNull(ds.ids[row]);
        if (!id.has_value()) return;   // Kotlin id.toIntOrNull ?: skip
        plan.eligibleSalaries.emplace_back(*id, salary);
        plan.totalRequired += salary;
    });
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
    }
}

// ════════════════════════════════════════════════════════════════
// 年变零 RNG 小件（T1/T2 子项）——每件语义逐条对齐 Kotlin
// processYearlyEvents / enqueueYearlyOps 源码，供 runYearSettlement
// 编排接线 + 对拍基准。
// ════════════════════════════════════════════════════════════════

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

/// 附庸年贡（Kotlin VassalService.processYearlyTribute）：
/// 玩家是附庸时按上年收入比例向上主宗缴纳年贡（钱包扣 LOW/VassalTribute/
/// Internal，autoConvert=true 与 Kotlin 默认一致）；无主宗/贡额 0 → 早退。
/// 收入取 annualTotalIncome 年度流水（缺陷 #3 修复：原 lastYearSpiritStoneIncome
/// 零写入点恒 0 → 年贡恒早退；本钩子在年报快照清零 annual* 之前执行，
/// 读到的正是上一完整年收入）。零 RNG。
inline void processYearlyTribute(GameState& state) {
    auto& gd = state.gameData;
    if (gd.suzerainSectId.empty()) return;
    const int64_t income = gd.annualTotalIncome;
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

/// 玩家附属宗门年贡（Kotlin VassalService.processYearlyVassalTribute）：
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

/// 商人手动刷新机会（Kotlin MerchantAndRecruitService.
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

/// 年度老化清理（Kotlin DiscipleLifecycleProcessor.processYearlyAging）：
/// cullDeadDisciples(currentYear - 1)——deathYears 有条目（>0）且
/// <= 阈值（死亡满 CULL_DEAD_AFTER_YEARS=1 年）的弟子整行移除。
/// Kotlin 同时记录 _deathRecords（纯内存，C++ 无对应）。零 RNG。
inline void processYearlyAging(GameState& state, int32_t currentYear,
                               ecs::World& world) {
    const int32_t threshold = currentYear - kCullDeadAfterYears;
    state::DiscipleStore& ds = state.disciples;
    // WS-3 E2 行序桥接：待移除收集经 sync + View 行序；移除在
    // 收集完成后统一执行（不跨 View 持有）。
    std::vector<std::string> toRemove;
    {
        ecs::syncDiscipleEntities(world, ds.size());
        ecs::View<ecs::DiscipleRef> view(world.registry());
        view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
            const std::size_t row = ref.row;   // 行地址取自组件（桥接规范 3）
            if (ds.deathYears[row] != 0 && ds.deathYears[row] <= threshold) {
                toRemove.push_back(ds.ids[row]);
            }
        });
    }
    for (const auto& id : toRemove) {
        ds.removeById(id);
    }
}

/// 思过到期释放（Kotlin DiscipleLifecycleProcessor.processReflectionRelease）：
/// REFLECTING 且存活、reflectionEndYear 已到（year >= endYear）→ 回 IDLE +
/// 清 reflectionStartYear/reflectionEndYear + 道德 +5（clamp SKILL_MAX=200）。
/// statusData 键名与状态常量锚点：building_residual_tx.h 同名定义
///（kReflectingStatusName/kReflectionEndYearKey——残余清扫事务共用）。
/// 修复登记（结算改造方案 §9.1 缺陷 #1）：AUTHORITATIVE 生产路径年变此前
/// 无本实现，思过弟子永不自动释放——B9 起双端同语义。零 RNG。
inline constexpr int32_t kReflectionReleaseMoralityBonus = 5;   // Kotlin REFLECTION_RELEASE_MORALITY_BONUS

inline void processReflectionRelease(GameState& state, int32_t year) {
    state::DiscipleStore& ds = state.disciples;
    static constexpr const char* kReflecting = "REFLECTING";
    static constexpr const char* kIdle = "IDLE";
    static constexpr const char* kStartKey = "reflectionStartYear";
    static constexpr const char* kEndKey = "reflectionEndYear";
    const std::size_t rows = ds.size();
    for (std::size_t row = 0; row < rows; ++row) {
        if (ds.statuses[row] != kReflecting) continue;
        if (ds.isAlive[row] != 1) continue;
        const auto& sd = ds.statusData[row];
        const auto it = sd.find(kEndKey);
        if (it == sd.end()) continue;               // Kotlin toIntOrNull ?: skip
        const auto endYear = toIntOrNull(it->second);
        if (!endYear.has_value() || year < *endYear) continue;
        ds.statuses[row] = kIdle;
        ds.statusData[row].erase(kStartKey);
        ds.statusData[row].erase(kEndKey);
        int32_t& morality = ds.moralities[row];
        morality = std::min(morality + kReflectionReleaseMoralityBonus,
                            gamecore::stats::kSkillMax);
    }
}

/// AI 尸体新陈代谢：死亡超
/// [AI_CORPSE_RETENTION_YEARS] 年的条目整行移除——「死亡不删除」容器族治理。
/// 战报/外交语义只引用宗门 id 不引用尸体个体（审计 §结论）；保留窗口内
/// 尸体供近期战报追溯。与战史 3 年消费窗口同源（产品决策项①推荐值）。
/// 幂等：年结与导入（normalizeAICorpseEntries）共用同一规则。
inline constexpr int AI_CORPSE_RETENTION_YEARS = 3;

inline void cullAICorpseEntries(GameState& state, int32_t gameYear) {
    for (auto& kv : state.aiSectDisciples) {
        auto& list = kv.second;
        std::erase_if(list, [gameYear](const state::Disciple& d) {
            return !d.isAlive && d.deathYear > 0 &&
                   (gameYear - d.deathYear) >= AI_CORPSE_RETENTION_YEARS;
        });
    }
}

/// 导入侧归一（幂等压缩）：旧档死亡条目缺 deathYear（字段新增，
/// 宽松导入缺省 0）→ 补「导入年」（保守：3 年窗口后自然清出）；随后按
/// 同一规则压缩——老档首次读入即回缩，存档体积随之回落，多次导入结果一致。
inline void normalizeAICorpseEntries(GameState& state) {
    const int32_t gameYear = state.gameData.gameYear;
    for (auto& kv : state.aiSectDisciples) {
        for (auto& d : kv.second) {
            if (!d.isAlive && d.deathYear == 0) d.deathYear = gameYear;
        }
    }
    cullAICorpseEntries(state, gameYear);
}

// ════════════════════════════════════════════════════════════════
// 商人收购刷新（Kotlin MerchantAndRecruitService.
// refreshMerchantAcquisition + buildMerchantItemPools/createMerchantItem/
// mergeMerchantItems 等价移植）
//
// RNG 契约：SYSTEM 分区（调用方传 rng.getRng(kSystem)）——数量 1×nextInt(9)
// + 每 item：品阶 1×nextDouble + 选池 1×nextInt + 库存 1×nextInt +
// 丹药 grade 1×nextDouble + 价格波动 1×nextDouble（消费序与 Kotlin
// 逐位一致）。已知边界（对拍排除）：MerchantItem.id/itemId 为 Kotlin
// UUID 镜像生成字段——C++ 用确定性自增 id 占位（商人收购/交易共用）。
// ════════════════════════════════════════════════════════════════

/// 商人物品确定性 id（Kotlin UUID——镜像生成字段，对拍排除；商人收购/
/// 宗门交易共用）
inline std::string nextTradeItemId() {
    // 进程级 static 计数器收敛到 inventory.h 注册表
    return "gc-trade-" + std::to_string(nextItemIdCounter("gc-trade"));
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
/// 品阶 + 名去重）/妖兽材料/灵草/种子 +
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
    // 丹药：MEDIUM 品阶 + 名字去重（Kotlin addedPillNames 首次出现序）。
    // 序 = Kotlin ItemDatabase.allPills 模板序（pillRecipesInTemplateOrder，
    // 2026 批收尾修复：原 pillRecipes() 配方序与 Kotlin 模板序相反）
    std::set<std::string> addedPills;
    for (const auto& r : data::pillRecipesInTemplateOrder()) {
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
// AI 宗门交易列表年度刷新（Kotlin DiplomacyService.
// refreshAllSectTrades + generateSectTradeItems 等价移植）
//
// RNG 契约：局部种子确定性 RNG——DeterministicRng.fromSeed(
// sectId.hashCode() + year)（sect_trade.h sectTradeSeed），同 (sectId,
// year) 生成结果完全可复现；**零分区 RNG 消耗**（SYSTEM 等分区不参与）。
// 已知边界（对拍排除）：MerchantItem.id/itemId 为 Kotlin UUID 镜像
// 生成字段——C++ 用确定性自增 id 占位（nextTradeItemId 见本文件前文）。
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
    // Kotlin ManualDatabase.generateRandom(min=rarity, max=rarity, type=null)
    // 先经 generateRarity(min,max) 阶梯——该阶梯**无条件消耗 1×nextDouble**
    //（ManualDatabase.generateRarity 无 min==max 短路；min==max 时各分档经
    // coerceAtMost(max) 恒回 rarity，值不影响选择，但 draw 必须消耗以对齐
    // RNG 流）。EquipmentDatabase.generateRandom 有 min==max 短路故无此
    // 消耗——manual 与 equipment 的 RNG 消费序差异源（2026 批收尾对拍实锤）。
    rngLocal.nextDouble();
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
/// PillGrade.displayName。池序 = Kotlin
/// ItemDatabase.allPills 模板序（pillRecipesInTemplateOrder——原
/// pillRecipes() 配方序与 Kotlin 模板序相反，2026 批收尾整链对拍实锤）
inline std::optional<state::MerchantItem> generateTradePillItem(
    rng::DeterministicRng& rngLocal, int32_t rarity, int32_t year) {
    const auto& recipes = data::pillRecipesInTemplateOrder();
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

/// 联盟到期解散（Kotlin DiplomacyEventProcessor.checkAllianceExpiry）：
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

/// 联盟好感度过低自动解散（Kotlin FavorEventProcessor.
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

/// 好感度自然衰减（Kotlin FavorEventProcessor.processFavorDecay）：
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

// ════════════════════════════════════════════════════════════════
// 年变中件下沉（驻军轮换）
// ════════════════════════════════════════════════════════════════

/// 驻军槽位数量（AISectGarrisonManager.GARRISON_SLOT_COUNT）
constexpr int32_t kGarrisonSlotCount = 10;
/// 驻军留守名额（AISectGarrisonManager：占领者最强 10 名留守宗门）
constexpr int32_t kGarrisonStayCount = 10;

/// 灵根数 → 颜色（Kotlin SpiritRoot.countColor 同式，Q31 灵根数色：
/// 1金 2红 3紫 4蓝 5灰，其余兜底灰；四份同表的判据由 GachaColorSingleSourceGuardTest 钉住）
inline std::string spiritRootCountColor(const std::string& spiritRootType) {
    int32_t count = 1;
    if (!spiritRootType.empty()) {
        count = 1 + static_cast<int32_t>(std::count(
            spiritRootType.begin(), spiritRootType.end(), ','));
    }
    switch (count) {
        case 1: return "#ffd700";
        case 2: return "#f44336";
        case 3: return "#9c27b0";
        case 4: return "#2196f3";
        default: return "#b8b8b8";
    }
}

/// 占领宗门驻军轮换（Kotlin AISectGarrisonManager.rotateGarrisonSlots）：
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

/// 远古秘境年变刷新（Kotlin SecretRealmService.processYearlySpawn）：
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

}  // namespace detail

/// 年俸结算主体（processAnnualSalary：计划 → canAfford → 发放）。
/// canAfford 采用 LOW 品纯 spiritStones 比较——autoSell 中/高品兑换开关开启时
/// Kotlin 会折算中高品余额，对拍场景锁定两开关 false（默认值）。
inline void processAnnualSalary(state::GameState& state, ecs::World& world) {
    const SalaryPlan plan = detail::buildYearSalaryPlan(state, world);
    if (plan.totalRequired <= 0) return;   // Kotlin calculateSalaryPlan null → return

    const bool frugality = state.gameData.sectPolicies.frugality;
    // canAfford：LOW 品口径（场景锁定 autoSell 开关 false → 纯 spiritStones）
    if (state.gameData.spiritStones < plan.totalRequired) {
        // 灵石不足 → 不发俸禄
        return;
    }

    // 足额：钱包扣减（Σ原额，LOW/Salary/Salary/autoConvert=true）
    SpiritStoneWallet::deduct(state.gameData, plan.totalRequired,
                              SpiritStoneGrade::LOW, "Salary", "Salary", true);
    detail::paySalariesToDisciples(state, plan, frugality);
}

// 年变平台效应草稿导出到 gamecore::system（runYearSettlement 签名 + nativeSettleYear 信封）
using detail::AgedDeathDraft;
using detail::YearSettlementDraft;

/// 年变编排主入口（注册进 SettlementEngine::onYearChange）。
/// @param state 完整游戏状态（就地修改）
/// @param rng   RNG 分区管理器（商人收购 SYSTEM / 秘境 SECRET_REALM）
/// @param aiRng AI 宗门独立分区 RNG（保留传输契约——年变无消费点）
/// @param draft 年变平台效应草稿信封（当前无填充点——恒空数组；
///   信封结构供 nativeSettleYear 回传 Kotlin 残留执行器）
/// @param world ECS 实体集（年结域全部弟子迭代
///   经 syncDiscipleEntities 行序桥接——持久实体集由 GameCore 承载，测试
///   传临时 World 同构）
inline void runYearSettlement(state::GameState& state,
                              rng::RngManager& rng,
                              rng::DeterministicRng& aiRng,
                              ecs::World& world,
                              YearSettlementDraft* draft = nullptr) {
    (void)rng;   // 显式占位——rng 由下方商人收购/秘境子项实际消费
    (void)draft;   // 信封参数保留传输契约（当前无填充点）
    (void)aiRng;   // AI 独立分区流保留传输契约（年变无消费点）

    // ── processYearlyEvents(year)：T1 立即组（Kotlin 严格相对序
    // #1→#2→#3→#4→#5→#6→#7→#8；#3 discipleAging 为 Kotlin 状态重推导
    // 幂等纯派生，C++ 列存储权威维护无需重推导）──
    // #1 附庸年贡
    detail::processYearlyTribute(state);
    // #2 附属宗门年贡
    detail::processYearlyVassalTribute(state, state.gameData.gameYear);
    // #4 商人赠予（手动刷新机会）
    detail::processMerchantRefreshChance(state, state.gameData.gameYear);
    // #5 年度老化清理（死亡弟子列清理）
    detail::processYearlyAging(state, state.gameData.gameYear, world);
    // #6 思过到期释放（缺陷 #1 修复：生产路径此前缺失——
    // 思过弟子永不自动释放；Kotlin 序 = yearlyAging 之后、年报快照之前）
    detail::processReflectionRelease(state, state.gameData.gameYear);
    // #7 garrisonAndReport：驻军轮换 + 年报快照 + annual* 清零
    detail::processGarrisonRotation(state);
    detail::runYearlyReportSnapshot(state);
    // #8 autoBuy（merchant_settlement.h；年变 T1 无条件调用
    // executeAutoBuy——与月变 12 月同函数，1 月执行新年购买）
    merchant_settle::executeAutoBuy(state);

    // ── T2 延迟组（Kotlin yearlyOpsQueue 分帧 drain；C++ 无分帧——
    //    子项按原相对序同步执行）──
    // #4 AI 尸体新陈代谢（年结统一压缩，
    // 死亡超保留窗口的条目整行移除）
    detail::cullAICorpseEntries(state, state.gameData.gameYear);
    // #12 商人收购刷新（SYSTEM 分区——数量 1×nextInt(9) +
    // 每 item 品阶 1×nextDouble + 选池 1×nextInt + 库存/grade/价格波动）
    detail::refreshMerchantAcquisition(state, rng.getRng(rng::RngPartition::kSystem),
                                       state.gameData.gameYear, 1);
    // #13 AI 宗门交易列表刷新（局部种子——零分区 RNG）
    detail::refreshAllSectTrades(state, state.gameData.gameYear);
    // #6 联盟到期
    detail::processAllianceExpiry(state, state.gameData.gameYear);
    // #7 联盟好感衰减检查
    detail::processAllianceFavorDrop(state);
    // #9 好感衰减
    detail::processFavorDecay(state, state.gameData.gameYear);
    // #22 远古秘境年变刷新（SECRET_REALM 分区——位置 + 变体）
    detail::processAncientSecretRealmSpawn(state, state.gameData.gameYear, rng);

    // ── gameMonth==1 时年俸（Kotlin processMonthYearChange 第二分支）──
    if (state.gameData.gameMonth == 1) {
        processAnnualSalary(state, world);
    }
}

}  // namespace gamecore::system
