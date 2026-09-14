#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <string>
#include <utility>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/cultivation.h"
#include "gamecore/system/economy.h"

// ============================================================
// 内政系统
//
// 等价移植 Kotlin ZoneCalculator + CultivationSettlement 的**纯公式**部分：
//   - 乘区法通用公式（calculate：base × Π(1 + zone)）
//   - 概率乘区（calculateProbability：base × (1+positive) × (1-penalty)，clamp [0,1]）
//   - 时间缩减/加速（calculateReducedDuration/calculateAcceleratedTime）
//   - 政策月度成本（processPolicyCosts：固定/按弟子数/周期性三模式）
//   - 灵矿月度产出（SpiritMineZones.calculateMonthly：时间戳差分结算）
//   - 年度年俸（processAnnualSalary 核心：开源节流 -30%、忠诚 ±1）
//   - 政策月度忠诚/道德效果（processPolicyMonthlyEffects）
//
// 与 Kotlin 语义对齐要点：
//   - roundToLong = std::round（Kotlin roundToLong 四舍五入）
//   - 灵石扣除走 SpiritStoneWallet（economy.h）——不足自动关闭政策
//   - 绝对月份 = year×12 + month
// ============================================================
namespace gamecore::system {

// ── 政策配置常量（Kotlin GameConfig.PolicyConfig）────────────────

// 固定月消耗
constexpr int64_t kEnhancedSecurityMonthly = 3000;
constexpr int64_t kAlchemyIncentiveMonthly = 3000;
constexpr int64_t kForgeIncentiveMonthly = 3000;
constexpr int64_t kHerbCultivationMonthly = 3000;
constexpr int64_t kManualResearchMonthly = 4000;
constexpr int64_t kCurfewMonthly = 1000;
constexpr int64_t kRewardPunishMonthly = 3000;
constexpr int64_t kStrictTrainingMonthly = 20000;
constexpr int64_t kRelaxedMgmtMonthly = 3000;
constexpr int64_t kSpiritSpringMonthly = 2000;
// 按弟子数计费（单价/弟子/月）
constexpr int64_t kCultivationSubsidyPerDisciple = 300;    // 化神下弟子
constexpr int64_t kAsceticTrainingPerDisciple = 800;       // 全弟子
constexpr int64_t kMoralEducationPerDisciple = 100;        // 全弟子
constexpr int64_t kBenevolentGovernancePerDisciple = 100;  // 全弟子
// 周期性消耗
constexpr int64_t kOpenRecruitmentCost = 50000;            // 每 3 年
constexpr int32_t kOpenRecruitmentCooldownMonths = 36;
// 月度忠诚/道德效果
constexpr int32_t kMoralEducationPerMonth = 1;             // 道德 +1
constexpr int32_t kBenevolentLoyaltyPerMonth = 1;          // 忠诚 +1
constexpr int32_t kRelaxedMgmtLoyaltyPerMonth = 2;         // 忠诚 +2
constexpr int32_t kStrictTrainingLoyaltyPerMonth = -1;     // 忠诚 -1
constexpr int32_t kEnhancedSecurityLoyaltyPerMonth = -1;   // 忠诚 -1
constexpr int32_t kCurfewLoyaltyPerMonth = -1;             // 忠诚 -1
constexpr int32_t kMoralEducationMax = 70;                 // 道德上限
constexpr int32_t kMaxLoyalty = 100;                       // 忠诚上限
// 开源节流年俸削减
constexpr double kFrugalitySalaryReduction = 0.30;         // -30%

// ── 乘区法工具（Kotlin ZoneCalculator）─────────────────────────

/// 核心乘区法：base × Π(1 + zone)
inline double zoneCalculate(double base, const std::vector<double>& zones) {
    double result = base;
    for (double z : zones) {
        result *= 1.0 + z;
    }
    return result;
}

/// 乘数 → 乘区加算值（multiplier 1.4 → 0.4）
inline double multiplierToZone(double multiplier) { return multiplier - 1.0; }

/// 乘区加算值 → 乘数（0.4 → 1.4）
inline double zoneToMultiplier(double zone) { return 1.0 + zone; }

/// 概率型乘区（保证 [0,1]）
inline double calculateProbability(double baseProb, double positiveSum,
                                   double penaltySum) {
    const double positiveMult = 1.0 + positiveSum;
    const double penaltyMult = std::max(1.0 - penaltySum, 0.0);
    return std::max(0.0, std::min(1.0, baseProb * positiveMult * penaltyMult));
}

/// 持续时间缩减（各缩减率 (1-r) 乘算，至少 1）
inline int32_t calculateReducedDuration(int32_t baseDuration,
                                        const std::vector<double>& reductions) {
    double factor = 1.0;
    for (double r : reductions) {
        factor *= (1.0 - std::max(0.0, std::min(1.0, r)));
    }
    return std::max(static_cast<int32_t>(std::round(baseDuration * factor)), 1);
}

/// 加速等效时间：ceil(base / Π(1+bonus))，至少 1
inline int32_t calculateAcceleratedTime(int32_t baseTime,
                                        const std::vector<double>& speedBonuses) {
    double multiplier = 1.0;
    for (double b : speedBonuses) {
        multiplier *= 1.0 + b;
    }
    return std::max(static_cast<int32_t>(std::ceil(baseTime / multiplier)), 1);
}

// ── 政策月度成本（Kotlin processPolicyCosts）────────────────────

/// 政策成本结果（Kotlin PolicyCostResult）
struct PolicyCostResult {
    bool allPaid = true;
    std::vector<std::string> disabledPolicies;
    std::vector<std::pair<std::string, int64_t>> deducted;
};

/// 灵矿增产政策无灵石消耗（SPIRIT_MINE_BOOST_MONTHLY = 0）——不参与扣除

/// 政策月度成本结算（Kotlin processPolicyCosts 核心）
/// @param discipleCount 全弟子数（isAlive==1）
/// @param huashenBelowCount 化神下（realm > 5）弟子数
inline PolicyCostResult processPolicyCosts(
    state::GameData& gd, int32_t discipleCount, int32_t huashenBelowCount) {
    PolicyCostResult out;
    auto& policies = gd.sectPolicies;

    // 固定月消耗政策（不足 → 自动关闭）
    auto tryDeduct = [&](int64_t cost, const std::string& name, bool enabled,
                         void (*disable)(state::SectPolicies&)) {
        if (!enabled || cost <= 0) return;
        const auto r = SpiritStoneWallet::deduct(gd, cost, SpiritStoneGrade::LOW,
                                                 "PolicyCost", "Internal", true);
        if (r.status == DeductStatus::kSuccess) {
            out.deducted.emplace_back(name, cost);
        } else {
            disable(policies);
            out.disabledPolicies.push_back(name);
            out.allPaid = false;
        }
    };

    tryDeduct(kAlchemyIncentiveMonthly, "丹道激励", policies.alchemyIncentive,
              [](state::SectPolicies& p) { p.alchemyIncentive = false; });
    tryDeduct(kForgeIncentiveMonthly, "锻造激励", policies.forgeIncentive,
              [](state::SectPolicies& p) { p.forgeIncentive = false; });
    tryDeduct(kHerbCultivationMonthly, "灵药培育", policies.herbCultivation,
              [](state::SectPolicies& p) { p.herbCultivation = false; });
    tryDeduct(kManualResearchMonthly, "功法研习", policies.manualResearch,
              [](state::SectPolicies& p) { p.manualResearch = false; });
    tryDeduct(kEnhancedSecurityMonthly, "增强治安", policies.enhancedSecurity,
              [](state::SectPolicies& p) { p.enhancedSecurity = false; });
    tryDeduct(kCurfewMonthly, "宵禁", policies.curfew,
              [](state::SectPolicies& p) { p.curfew = false; });
    tryDeduct(kRewardPunishMonthly, "赏善罚恶", policies.rewardPunish,
              [](state::SectPolicies& p) { p.rewardPunish = false; });
    tryDeduct(kStrictTrainingMonthly, "严苛训练", policies.strictTraining,
              [](state::SectPolicies& p) { p.strictTraining = false; });
    tryDeduct(kRelaxedMgmtMonthly, "松弛管理", policies.relaxedMgmt,
              [](state::SectPolicies& p) { p.relaxedMgmt = false; });
    tryDeduct(kSpiritSpringMonthly, "灵泉灌溉", policies.spiritSpring,
              [](state::SectPolicies& p) { p.spiritSpring = false; });

    // 按弟子数计费
    if (policies.cultivationSubsidy) {
        const int64_t cost = kCultivationSubsidyPerDisciple * huashenBelowCount;
        tryDeduct(cost, "修行津贴", true,
                  [](state::SectPolicies& p) { p.cultivationSubsidy = false; });
    }
    if (policies.asceticTraining) {
        const int64_t cost = kAsceticTrainingPerDisciple * discipleCount;
        tryDeduct(cost, "苦修令", true,
                  [](state::SectPolicies& p) { p.asceticTraining = false; });
    }
    if (policies.moralEducation) {
        const int64_t cost = kMoralEducationPerDisciple * discipleCount;
        tryDeduct(cost, "教化之道", true,
                  [](state::SectPolicies& p) { p.moralEducation = false; });
    }
    if (policies.benevolentGovernance) {
        const int64_t cost = kBenevolentGovernancePerDisciple * discipleCount;
        tryDeduct(cost, "仁政爱徒", true,
                  [](state::SectPolicies& p) { p.benevolentGovernance = false; });
    }

    // 周期性消耗：广纳门徒每 3 年扣一次（冷却期内不扣）
    if (policies.openRecruitment) {
        const int32_t currentMonth = toAbsoluteMonth(gd.gameYear, gd.gameMonth);
        if (currentMonth - gd.openRecruitmentLastPaidMonth >=
            kOpenRecruitmentCooldownMonths) {
            tryDeduct(kOpenRecruitmentCost, "广纳门徒", true,
                      [](state::SectPolicies& p) { p.openRecruitment = false; });
            // 记录本次付费月份
            for (const auto& d : out.deducted) {
                if (d.first == "广纳门徒") {
                    gd.openRecruitmentLastPaidMonth = currentMonth;
                    break;
                }
            }
        }
    }
    return out;
}

// ── 政策月度忠诚/道德效果（Kotlin processPolicyMonthlyEffects）───

/// 单弟子月度忠诚/道德净变化
/// @return (loyaltyDelta, moralityDelta)（调用方应用 clamp）
inline std::pair<int32_t, int32_t> policyMonthlyDeltas(
    const state::SectPolicies& policies) {
    int32_t loyaltyDelta = 0;
    if (policies.benevolentGovernance) loyaltyDelta += kBenevolentLoyaltyPerMonth;
    if (policies.relaxedMgmt) loyaltyDelta += kRelaxedMgmtLoyaltyPerMonth;
    if (policies.strictTraining) loyaltyDelta += kStrictTrainingLoyaltyPerMonth;
    if (policies.enhancedSecurity) loyaltyDelta += kEnhancedSecurityLoyaltyPerMonth;
    if (policies.curfew) loyaltyDelta += kCurfewLoyaltyPerMonth;
    const int32_t moralityDelta = policies.moralEducation ? kMoralEducationPerMonth : 0;
    return {loyaltyDelta, moralityDelta};
}

// ── 灵矿产出（Kotlin SpiritMineZones）──────────────────────────

/// 灵矿乘区
struct SpiritMineZones {
    int32_t minerCount = 0;
    double avgMiningSkillBonus = 0.0;
    double deaconMoralityBonus = 0.0;
    double policyBoost = 0.0;
};

/// 灵矿增产政策倍率（SPIRIT_MINE_BOOST_MULTIPLIER = 1.2）
constexpr double kSpiritMineBoostMultiplier = 1.2;
/// 执事道德加成率（DEACON_MORALITY_BONUS_RATE = 0.01/点超基准）
constexpr double kDeaconMoralityBonusRate = 0.01;
/// 长老技能基线（ELDER_SKILL_BASELINE = 80）
constexpr int32_t kElderSkillBaseline = 80;
/// 采矿技能阈值（SPIRIT_MINE_MINING_THRESHOLD = 70）
constexpr int32_t kSpiritMineMiningThreshold = 70;
/// 采矿技能加成率（SPIRIT_MINE_MINING_BONUS_RATE = 0.02/点超阈值）
constexpr double kSpiritMineMiningBonusRate = 0.02;
/// 每矿工基础产出（SPIRIT_MINE_BASE_OUTPUT_PER_MINER = 170）
constexpr int32_t kSpiritMineBaseOutputPerMiner = 170;

/// 灵矿月总产出（Kotlin SpiritMineZones.calculateMonthly）
inline int64_t calculateSpiritMineMonthly(const SpiritMineZones& zones,
                                          double basePerMiner) {
    const double base = zones.minerCount * basePerMiner;
    const double result = zoneCalculate(
        base, {zones.avgMiningSkillBonus, zones.deaconMoralityBonus,
               zones.policyBoost});
    return static_cast<int64_t>(std::round(result));
}

/// 构建灵矿乘区（Kotlin buildSpiritMineZones 核心）
/// @param minerCount 矿工数（spiritMineSlots 中 discipleId 非空）
/// @param miningSkills 各矿工采矿技能（超阈值部分加成）
/// @param deaconMoralityBonuses 各执事道德超基准部分 × 0.01 之和
/// @param spiritMineBoost 是否启用灵矿增产政策
inline SpiritMineZones buildSpiritMineZones(
    int32_t minerCount, const std::vector<int32_t>& miningSkills,
    const std::vector<int32_t>& deaconMoralityBonuses,
    bool spiritMineBoost) {
    SpiritMineZones zones;
    zones.minerCount = minerCount;
    double miningBonus = 0.0;
    for (int32_t mining : miningSkills) {
        if (mining > kSpiritMineMiningThreshold) {
            miningBonus += (mining - kSpiritMineMiningThreshold) *
                           kSpiritMineMiningBonusRate;
        }
    }
    zones.avgMiningSkillBonus = (minerCount > 0) ? miningBonus / minerCount : 0.0;
    const double boostMultiplier =
        spiritMineBoost ? kSpiritMineBoostMultiplier : 1.0;
    zones.policyBoost = multiplierToZone(boostMultiplier);
    double deaconBonus = 0.0;
    for (int32_t morality : deaconMoralityBonuses) {
        const double diff = std::max(morality - kElderSkillBaseline, 0);
        deaconBonus += diff * kDeaconMoralityBonusRate;
    }
    zones.deaconMoralityBonus = deaconBonus;
    return zones;
}

/// 灵矿月度产出结算（Kotlin processSpiritMineProductionMonthly 核心）
/// @return (总产出, 是否结算)（currentMonth > lastSettled 且 rate > 0）
inline std::pair<int64_t, bool> settleSpiritMineProduction(
    state::GameData& gd, const SpiritMineZones& zones) {
    const int32_t currentMonth = toAbsoluteMonth(gd.gameYear, gd.gameMonth);
    const int64_t monthlyRate = calculateSpiritMineMonthly(
        zones, kSpiritMineBaseOutputPerMiner);
    const int32_t lastSettled = gd.spiritMineLastSettledMonth;
    if (currentMonth > lastSettled && monthlyRate > 0) {
        const int32_t delta = currentMonth - lastSettled;
        const int64_t totalOutput = monthlyRate * delta;
        gd.spiritMineLastSettledMonth = currentMonth;
        return {totalOutput, true};
    }
    gd.spiritMineLastSettledMonth = currentMonth;
    return {0, false};
}

// ── 年度年俸（Kotlin processAnnualSalary 核心）─────────────────

/// 年俸计划（Kotlin SalaryPlan）
struct SalaryPlan {
    std::vector<std::pair<int32_t, int64_t>> eligibleSalaries;  // (discipleId, salary)
    int64_t totalRequired = 0;
};

/// 计算应得俸禄弟子清单（Kotlin calculateSalaryPlan 核心）
/// @param yearlySalary realm → 年俸
/// @param yearlySalaryEnabled realm → 是否启用
/// @param aliveRealms 存活弟子的 (id, realm)
inline SalaryPlan calculateSalaryPlan(
    const std::map<int32_t, int32_t>& yearlySalary,
    const std::map<int32_t, bool>& yearlySalaryEnabled,
    const std::vector<std::pair<int32_t, int32_t>>& aliveRealms) {
    SalaryPlan plan;
    for (const auto& [id, realm] : aliveRealms) {
        const auto enabledIt = yearlySalaryEnabled.find(realm);
        if (enabledIt == yearlySalaryEnabled.end() || !enabledIt->second) continue;
        const auto salaryIt = yearlySalary.find(realm);
        if (salaryIt == yearlySalary.end()) continue;
        const int64_t salary = salaryIt->second;
        if (salary <= 0) continue;
        plan.eligibleSalaries.emplace_back(id, salary);
        plan.totalRequired += salary;
    }
    if (plan.totalRequired <= 0) {
        plan.eligibleSalaries.clear();
        plan.totalRequired = 0;
    }
    return plan;
}

/// 年俸发放（Kotlin processAnnualSalary 核心）：开源节流 -30%、忠诚 +1
/// @param frugality 是否开源节流政策
/// @return 实际发放的年俸（灵石不足返回 0 表示未发放）
inline int64_t payAnnualSalary(state::GameData& gd, const SalaryPlan& plan,
                               bool frugality) {
    if (plan.totalRequired <= 0) return 0;
    // 灵石不足 → 不发俸禄（调用方负责忠诚 -1）
    if (gd.spiritStones < plan.totalRequired) return 0;
    const double salaryMultiplier =
        frugality ? (1.0 - kFrugalitySalaryReduction) : 1.0;
    const auto r = SpiritStoneWallet::deduct(gd, plan.totalRequired,
                                             SpiritStoneGrade::LOW, "Salary",
                                             "Salary", true);
    if (r.status != DeductStatus::kSuccess) return 0;
    // 逐弟子发放（忠诚发放由调用方处理——本函数只计算实际金额）
    return static_cast<int64_t>(
        std::round(plan.totalRequired * salaryMultiplier));
}

// ══════════════════════════════════════════════════════════════════
// 政策开关事务（batch-18b）
//
// 语义权威 = Kotlin `SectPolicyToggleUseCase` 三个直调点（通用 `toggle`
// 入口 / `toggleOpenRecruitment` / `toggleSpiritMineBoost`），判定序逐字对齐：
//   关闭态 → 需要扣费时先 canAfford（不足 → 失败信封；Kotlin 回退臂产出
//   用户可见文案）→ 事务内 deduct(autoConvert=true) → 置位 → 激活计数 +1
//   开启态 → 仅置位 false（不退款、不计数）
//
// `affectsCultivationRate`（修行津贴/苦修令/松弛管理三类）→ **同事务内**
// checkpointAllDisciples（列级：checkpoint = 当前修为 + 绝对月），与 Kotlin
// `discipleTables.checkpointAllDisciples(year*12+month)` 逐位等价。
//
// 🔴 生产类政策（丹道激励/锻造激励/灵药培育/灵泉灌溉——影响炼丹/锻造/灵田
// 速率，见 FormulaService.calculateWorkDurationWithAllDisciples 与
// buildHerbGardenMaturityZones）由回执 `productionCheckpointNeeded` 标记，
// **Kotlin 臂在事务成功后调用 `checkpointAllProduction()`**（CLAUDE.md 6.4 /
// 13.3 红线）。C++ 侧不可代办：生产槽位真源是 Kotlin ProductionSlotRepository
//（仅月结窗口整表镜像，§2.11），C++ 镜像在月中可能陈旧。
//
// RNG 契约：三事务**全部零抽取**（签名级：不接受 rng 参数）。
// ══════════════════════════════════════════════════════════════════

/// 激活政策引导计数键（Kotlin GuideCounterKeys.POLICY_ACTIVATED）
inline constexpr const char* kPolicyActivatedCounterKey = "policyActivated";

/// 政策开关回执
struct PolicyToggleOutcome {
    bool ok = false;                    // false = 校验失败（Kotlin 回退臂重执行）
    std::string errorType;              // "UNKNOWN_POLICY" / "INSUFFICIENT_STONES"
    std::string message;
    bool wasEnabled = false;            // 事务前状态（Kotlin `wasEnabled`）
    bool enabled = false;               // 事务后状态
    int64_t costPaid = 0;               // 本次实际扣费（关闭态不扣 = 0）
    bool cultivationCheckpoint = false; // 是否已执行修炼全量 checkpoint
    /// 生产类政策标记——Kotlin 臂据此调用 checkpointAllProduction()（13.3 红线）
    bool productionCheckpointNeeded = false;
    int32_t checkpointMonth = 0;        // 修炼 checkpoint 使用的绝对月（未执行为 0）
};

/// 可开关政策字段名 → 成员指针（Kotlin getter/setter 的字段名镜像）。
/// 未知字段返回 nullptr（防御：UI 传名漂移 → 失败信封回退 Kotlin 原路径）。
inline bool* sectPolicyField(state::SectPolicies& p, const std::string& field) {
    if (field == "spiritMineBoost") return &p.spiritMineBoost;
    if (field == "enhancedSecurity") return &p.enhancedSecurity;
    if (field == "alchemyIncentive") return &p.alchemyIncentive;
    if (field == "forgeIncentive") return &p.forgeIncentive;
    if (field == "herbCultivation") return &p.herbCultivation;
    if (field == "cultivationSubsidy") return &p.cultivationSubsidy;
    if (field == "manualResearch") return &p.manualResearch;
    if (field == "openRecruitment") return &p.openRecruitment;
    if (field == "asceticTraining") return &p.asceticTraining;
    if (field == "curfew") return &p.curfew;
    if (field == "rewardPunish") return &p.rewardPunish;
    if (field == "strictTraining") return &p.strictTraining;
    if (field == "relaxedMgmt") return &p.relaxedMgmt;
    if (field == "spiritSpring") return &p.spiritSpring;
    if (field == "frugality") return &p.frugality;
    if (field == "moralEducation") return &p.moralEducation;
    if (field == "benevolentGovernance") return &p.benevolentGovernance;
    return nullptr;
}

/// 生产类政策集合（影响炼丹/锻造/灵田速率——CLAUDE.md 6.4 / 13.3）。
/// 灵矿增产（spiritMineBoost）不在此列：其速率变化由
/// `spiritMineLastSettledMonth` 时间戳差分承扣，无需 duration 重算。
inline bool isProductionClassPolicy(const std::string& field) {
    return field == "alchemyIncentive" || field == "forgeIncentive" ||
           field == "herbCultivation" || field == "spiritSpring";
}

/// 下品灵石可负担判定（Kotlin SpiritStoneWallet.canAfford(amount, LOW) 等价：
/// 余额 + 按开关折算的中/上品等价下品值）。
inline bool canAffordLowGrade(const state::GameData& gd, int64_t amount) {
    if (gd.spiritStones >= amount) return true;
    int64_t midValue = 0;
    int64_t highValue = 0;
    if (gd.autoSellMidGradeForPurchase) {
        midValue = SpiritStoneExchange::toLowGrade(gd.midGradeSpiritStones,
                                                   SpiritStoneGrade::MID);
    }
    if (gd.autoSellHighGradeForPurchase) {
        highValue = SpiritStoneExchange::toLowGrade(gd.highGradeSpiritStones,
                                                    SpiritStoneGrade::HIGH);
    }
    return gd.spiritStones + midValue + highValue >= amount;
}

/// 全量弟子修炼 checkpoint（列级直写——Kotlin
/// DiscipleTables.checkpointAllDisciples：存活弟子 checkpoint = 当前修为，
/// 记当前绝对月；`checkpointDisciple` 的 isAlive 守卫同款）。
inline void checkpointAllDisciplesColumns(state::GameState& state, int32_t month) {
    auto& ds = state.disciples;
    for (std::size_t row = 0; row < ds.ids.size(); ++row) {
        if (ds.isAlive[row] != 1) continue;
        ds.cultivationCheckpoints[row] = ds.cultivations[row];
        ds.cultivationCheckpointGameMonths[row] = month;
    }
}

/// 通用政策开关（Kotlin SectPolicyToggleUseCase.toggle）。
/// @param field Kotlin 侧 getter/setter 对应的政策字段名
/// @param monthlyCost 调用方在事务外算得的首月费用（按弟子数计费政策由
///        Kotlin 装配——`countTotalDisciples` / `countHuashenBelow` 依赖
///        DiscipleTables 组装，批次先例 §2.36「宗门过滤归 Kotlin」同口径）
/// @param affectsCultivationRate 是否影响修炼速率（切换后同事务 checkpoint）
inline PolicyToggleOutcome policyToggleTx(state::GameState& state,
                                          const std::string& field,
                                          int64_t monthlyCost,
                                          bool affectsCultivationRate) {
    PolicyToggleOutcome out;
    auto& gd = state.gameData;
    bool* flag = sectPolicyField(gd.sectPolicies, field);
    if (flag == nullptr) {
        out.errorType = "UNKNOWN_POLICY";
        out.message = "未知政策字段: " + field;
        return out;
    }
    out.productionCheckpointNeeded = isProductionClassPolicy(field);
    out.wasEnabled = *flag;
    if (!out.wasEnabled) {
        if (monthlyCost > 0) {
            if (!canAffordLowGrade(gd, monthlyCost)) {
                out.errorType = "INSUFFICIENT_STONES";
                out.message = "灵石不足" + std::to_string(monthlyCost) +
                              "，无法开启政策";
                return out;
            }
            const auto r = SpiritStoneWallet::deduct(
                gd, monthlyCost, SpiritStoneGrade::LOW, "PolicyCost", "Internal", true);
            if (r.status != DeductStatus::kSuccess) {
                out.errorType = "INSUFFICIENT_STONES";
                out.message = "灵石不足" + std::to_string(monthlyCost) +
                              "，无法开启政策";
                return out;
            }
            out.costPaid = monthlyCost;
        }
        *flag = true;
        out.enabled = true;
        ++gd.guideCounters[kPolicyActivatedCounterKey];
    } else {
        *flag = false;
        out.enabled = false;
    }
    if (affectsCultivationRate) {
        out.checkpointMonth = toAbsoluteMonth(gd.gameYear, gd.gameMonth);
        checkpointAllDisciplesColumns(state, out.checkpointMonth);
        out.cultivationCheckpoint = true;
    }
    out.ok = true;
    return out;
}

/// 广纳门徒开关（Kotlin SectPolicyToggleUseCase.toggleOpenRecruitment）：
/// 开启时扣固定费用并记录付费月；关闭仅置位。
inline PolicyToggleOutcome openRecruitmentToggleTx(state::GameState& state) {
    PolicyToggleOutcome out;
    auto& gd = state.gameData;
    out.wasEnabled = gd.sectPolicies.openRecruitment;
    if (!out.wasEnabled) {
        if (!canAffordLowGrade(gd, kOpenRecruitmentCost)) {
            out.errorType = "INSUFFICIENT_STONES";
            out.message = "灵石不足" + std::to_string(kOpenRecruitmentCost) +
                          "，无法开启广纳门徒";
            return out;
        }
        const int32_t currentMonth = toAbsoluteMonth(gd.gameYear, gd.gameMonth);
        const auto r = SpiritStoneWallet::deduct(
            gd, kOpenRecruitmentCost, SpiritStoneGrade::LOW, "PolicyCost", "Internal", true);
        if (r.status != DeductStatus::kSuccess) {
            out.errorType = "INSUFFICIENT_STONES";
            out.message = "灵石不足" + std::to_string(kOpenRecruitmentCost) +
                          "，无法开启广纳门徒";
            return out;
        }
        out.costPaid = kOpenRecruitmentCost;
        gd.sectPolicies.openRecruitment = true;
        out.enabled = true;
        ++gd.guideCounters[kPolicyActivatedCounterKey];
        gd.openRecruitmentLastPaidMonth = currentMonth;
    } else {
        gd.sectPolicies.openRecruitment = false;
        out.enabled = false;
    }
    out.ok = true;
    return out;
}

/// 灵矿增产开关（Kotlin SectPolicyToggleUseCase.toggleSpiritMineBoost）：
/// 免费；开启时激活计数 +1 并把结算月戳推前到当前月（防开启前时长被新倍率
/// 追认——差分结算语义）。
inline PolicyToggleOutcome spiritMineBoostToggleTx(state::GameState& state) {
    PolicyToggleOutcome out;
    auto& gd = state.gameData;
    out.wasEnabled = gd.sectPolicies.spiritMineBoost;
    const bool newValue = !out.wasEnabled;
    gd.sectPolicies.spiritMineBoost = newValue;
    out.enabled = newValue;
    if (!out.wasEnabled) {
        ++gd.guideCounters[kPolicyActivatedCounterKey];
        const int32_t currentMonth = toAbsoluteMonth(gd.gameYear, gd.gameMonth);
        if (currentMonth > gd.spiritMineLastSettledMonth) {
            gd.spiritMineLastSettledMonth = currentMonth;
        }
    }
    out.ok = true;
    return out;
}

}  // namespace gamecore::system
