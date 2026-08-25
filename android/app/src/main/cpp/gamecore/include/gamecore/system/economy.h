#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "gamecore/state/models.h"

// ============================================================
// 经济系统（Kotlin→C++ 迁移批次 4）
//
// 等价移植 Kotlin SpiritStoneExchange + SpiritStoneWallet 的**纯逻辑**部分：
//   - 灵石品阶/兑换（LOW/MID/HIGH，名义汇率 1:10,000，售卖价 ×0.8）
//   - Wallet add/deduct/batch（含自动售卖中/上品补差价）
//   - 年度报告累积（annualIncomeBySource / annualExpenditureByReason）
//
// 与 Kotlin 语义对齐要点：
//   - EFFECTIVE_RATIO = (10_000 * 0.8) = 8000（Kotlin 经 (RATIO * 0.8).toLong()）
//   - safeAdd/safeMultiply 溢出回绕 Long.MAX_VALUE
//   - DeductResult 三态（Success/Insufficient/Invalid）
//   - batch 预检查所有扣减 → 失败回滚 autoSell（保存 preSnapshot）
// ============================================================
namespace gamecore::system {

/// 灵石品阶（Kotlin SpiritStoneGrade.name）
enum class SpiritStoneGrade {
    LOW = 0,
    MID = 1,
    HIGH = 2,
};

/// 品阶名 → 枚举（未知返回 LOW 兜底；仅供内部解析）
inline SpiritStoneGrade spiritStoneGradeFromName(const std::string& name) {
    if (name == "MID") return SpiritStoneGrade::MID;
    if (name == "HIGH") return SpiritStoneGrade::HIGH;
    return SpiritStoneGrade::LOW;
}

/// 枚举 → 品阶名（与 kotlinx JSON 序列化一致）
inline const char* spiritStoneGradeName(SpiritStoneGrade grade) {
    switch (grade) {
        case SpiritStoneGrade::LOW: return "LOW";
        case SpiritStoneGrade::MID: return "MID";
        case SpiritStoneGrade::HIGH: return "HIGH";
    }
    return "LOW";
}

/// 灵石兑换工具（Kotlin SpiritStoneExchange 等价移植）
class SpiritStoneExchange {
public:
    /// 名义汇率：1 中品 = 10,000 下品
    static constexpr int64_t kRatio = 10'000L;

    /// 售卖价汇率：1 中品 ↔ 8,000 下品（RATIO × 0.8）
    static constexpr int64_t kEffectiveRatio = 8'000L;

    /// 安全乘法：溢出回绕 Long.MAX_VALUE（Kotlin safeMultiply）
    static int64_t safeMultiply(int64_t a, int64_t b) {
        const int64_t result = a * b;
        if (a != 0 && result / a != b) return INT64_MAX;
        return result;
    }

    /// 安全加法：结果 < 0（溢出）时回绕 Long.MAX_VALUE（Kotlin safeAdd）
    static int64_t safeAdd(int64_t a, int64_t b) {
        const int64_t result = a + b;
        return result < 0 ? INT64_MAX : result;
    }

    /// 所有品阶按售卖价折算的下品总价值
    static int64_t totalSellValue(int64_t low, int64_t mid, int64_t high) {
        int64_t total = low;
        if (mid > 0) total = safeAdd(total, safeMultiply(mid, kEffectiveRatio));
        if (high > 0) {
            total = safeAdd(total,
                            safeMultiply(high, safeMultiply(kEffectiveRatio, kEffectiveRatio)));
        }
        return total;
    }

    /// quantity 个 grade 灵石 → 等值下品（按售卖价）
    static int64_t toLowGrade(int64_t quantity, SpiritStoneGrade grade) {
        if (quantity <= 0) return 0;
        switch (grade) {
            case SpiritStoneGrade::LOW: return quantity;
            case SpiritStoneGrade::MID:
                return safeMultiply(quantity, kEffectiveRatio);
            case SpiritStoneGrade::HIGH:
                return safeMultiply(quantity,
                                    safeMultiply(kEffectiveRatio, kEffectiveRatio));
        }
        return 0;
    }

    /// 下品 → grade 灵石（按售卖价），返回 (可兑换数量, 剩余下品)
    static std::pair<int64_t, int64_t> fromLowGrade(int64_t lowGradeAmount,
                                                    SpiritStoneGrade grade) {
        if (lowGradeAmount <= 0) return {0, 0};
        switch (grade) {
            case SpiritStoneGrade::LOW:
                return {lowGradeAmount, 0};
            case SpiritStoneGrade::MID:
                return {lowGradeAmount / kEffectiveRatio,
                        lowGradeAmount % kEffectiveRatio};
            case SpiritStoneGrade::HIGH: {
                const int64_t unit = safeMultiply(kEffectiveRatio, kEffectiveRatio);
                return {lowGradeAmount / unit, lowGradeAmount % unit};
            }
        }
        return {0, 0};
    }

    /// 跨品阶兑换：source → target，返回 (成功转换数量, 剩余 source 品阶数量)
    static std::pair<int64_t, int64_t> exchange(int64_t quantity,
                                                SpiritStoneGrade source,
                                                SpiritStoneGrade target) {
        if (source == target) return {quantity, 0};
        if (quantity <= 0) return {0, 0};
        const int64_t low = toLowGrade(quantity, source);
        const auto [converted, remainingLow] = fromLowGrade(low, target);
        // 剩余下品折算回源品阶，保持返回值单位与 source 一致
        const int64_t remainingSource = fromLowGrade(remainingLow, source).first;
        return {converted, remainingSource};
    }

    /// 下品按售卖价拆分到各品阶（仅 >0 的品阶输出）
    static std::map<SpiritStoneGrade, int64_t> splitToGrades(int64_t lowGradeAmount) {
        std::map<SpiritStoneGrade, int64_t> out;
        if (lowGradeAmount <= 0) return out;
        const int64_t unit = safeMultiply(kEffectiveRatio, kEffectiveRatio);
        const int64_t high = lowGradeAmount / unit;
        const int64_t mid = (lowGradeAmount % unit) / kEffectiveRatio;
        const int64_t low = lowGradeAmount % kEffectiveRatio;
        if (high > 0) out[SpiritStoneGrade::HIGH] = high;
        if (mid > 0) out[SpiritStoneGrade::MID] = mid;
        if (low > 0) out[SpiritStoneGrade::LOW] = low;
        return out;
    }
};

// ── 灵石扣除结果（Kotlin DeductResult 三态）─────────────────────

enum class DeductStatus {
    kSuccess,
    kInsufficient,
    kInvalid,
};

struct DeductResult {
    DeductStatus status = DeductStatus::kInvalid;
    int64_t balanceAfter = 0;   // Success
    int64_t balance = 0;        // Insufficient：当前余额
    int64_t required = 0;       // Insufficient：所需数量
};

inline DeductResult deductSuccess(int64_t balanceAfter) {
    DeductResult r;
    r.status = DeductStatus::kSuccess;
    r.balanceAfter = balanceAfter;
    return r;
}

inline DeductResult deductInsufficient(int64_t balance, int64_t required) {
    DeductResult r;
    r.status = DeductStatus::kInsufficient;
    r.balance = balance;
    r.required = required;
    return r;
}

inline DeductResult deductInvalid() {
    DeductResult r;
    r.status = DeductStatus::kInvalid;
    return r;
}

// ── 批量变更（Kotlin SpiritStoneOperation / BatchResult）────────

struct SpiritStoneOperation {
    int64_t delta = 0;                       // 正=增，负=扣（不可为 0 / Long.MIN_VALUE）
    SpiritStoneGrade grade = SpiritStoneGrade::LOW;
    std::string reason = "Internal";         // SpiritStoneReason.key
    std::string source = "Internal";         // SpiritStoneSource.key
};

struct BatchResult {
    int successCount = 0;
    int failedCount = 0;
    std::vector<DeductResult> results;
};

/// 灵石品阶余额读写（Kotlin spiritStoneCount / updateGrade）
inline int64_t spiritStoneCount(const state::GameData& gd, SpiritStoneGrade grade) {
    switch (grade) {
        case SpiritStoneGrade::LOW: return gd.spiritStones;
        case SpiritStoneGrade::MID: return gd.midGradeSpiritStones;
        case SpiritStoneGrade::HIGH: return gd.highGradeSpiritStones;
    }
    return 0;
}

/// 设置指定品阶余额（copy-on-write 语义：返回新 GameData）
inline state::GameData withSpiritStoneCount(state::GameData gd, SpiritStoneGrade grade,
                                            int64_t newAmount) {
    switch (grade) {
        case SpiritStoneGrade::LOW: gd.spiritStones = newAmount; break;
        case SpiritStoneGrade::MID: gd.midGradeSpiritStones = newAmount; break;
        case SpiritStoneGrade::HIGH: gd.highGradeSpiritStones = newAmount; break;
    }
    return gd;
}

/// 灵石钱包（Kotlin SpiritStoneWallet 纯逻辑等价）
///
/// 与 Kotlin 一致的语义约束：
///   - add：amount<=0 直接返回当前余额；溢出回绕 Long.MAX_VALUE
///   - deduct：amount<=0 → Invalid；下品不足且 autoConvert 时自动售卖中/上品
///   - batch：预检查所有扣减（含 autoConvert），失败整体回滚（恢复 preSnapshot）；
///     执行阶段逐条失败只记 failedCount 不中断
///   - 年度报告：delta>0 记 annualIncomeBySource/annualTotalIncome；
///     delta<0 记 annualExpenditureByReason/annualTotalExpenditure
class SpiritStoneWallet {
public:
    /// 在事务内增加灵石；返回变更后该品阶余额
    static int64_t add(state::GameData& gd, int64_t amount, SpiritStoneGrade grade,
                       const std::string& source = "Internal",
                       const std::string& metadata = "") {
        (void)metadata;
        const int64_t current = spiritStoneCount(gd, grade);
        if (amount <= 0) return current;
        const int64_t newAmount =
            (current > INT64_MAX - amount) ? INT64_MAX : current + amount;
        gd = withSpiritStoneCount(gd, grade, newAmount);
        recordAnnual(gd, newAmount - current, "Internal", source);
        return newAmount;
    }

    /// 在事务内扣除灵石
    static DeductResult deduct(state::GameData& gd, int64_t amount,
                               SpiritStoneGrade grade,
                               const std::string& reason = "Internal",
                               const std::string& source = "Internal",
                               bool autoConvert = true) {
        if (amount <= 0) return deductInvalid();
        int64_t current = spiritStoneCount(gd, grade);

        if (grade == SpiritStoneGrade::LOW && autoConvert && current < amount) {
            const AutoSellPlan plan = calculateAutoSell(gd, amount - current);
            if (plan.gainedLow > 0 && gd.spiritStones + plan.gainedLow >= amount) {
                autoSellHigherGrades(gd, plan);
            } else {
                return deductInsufficient(current, amount);
            }
        } else if (current < amount) {
            return deductInsufficient(current, amount);
        }

        const int64_t balanceBefore = spiritStoneCount(gd, grade);
        const int64_t newAmount = (balanceBefore - amount) < 0 ? 0 : (balanceBefore - amount);
        gd = withSpiritStoneCount(gd, grade, newAmount);
        recordAnnual(gd, -amount, reason, source);
        return deductSuccess(newAmount);
    }

    /// 在事务内批量变更（原子：预检查失败整体回滚）
    static BatchResult batch(state::GameData& gd,
                             const std::vector<SpiritStoneOperation>& operations,
                             bool autoConvert = false) {
        BatchResult out;
        if (operations.empty()) return out;

        const state::GameData preSnapshot = gd;
        bool hasAutoSold = false;

        // 预检查所有扣减（确保原子性）
        for (const auto& op : operations) {
            if (op.delta >= 0) continue;
            const int64_t absAmount = -op.delta;
            const int64_t curr = spiritStoneCount(gd, op.grade);
            if (curr < absAmount) {
                if (!autoConvert || op.grade != SpiritStoneGrade::LOW) {
                    if (hasAutoSold) gd = preSnapshot;
                    out.failedCount = static_cast<int>(operations.size());
                    return out;
                }
                const AutoSellPlan plan = calculateAutoSell(gd, absAmount - curr);
                if (plan.gainedLow <= 0 || gd.spiritStones + plan.gainedLow < absAmount) {
                    if (hasAutoSold) gd = preSnapshot;
                    out.failedCount = static_cast<int>(operations.size());
                    return out;
                }
                autoSellHigherGrades(gd, plan);
                hasAutoSold = true;
            }
        }

        // 执行阶段
        for (const auto& op : operations) {
            if (op.delta >= 0) {
                const int64_t current = spiritStoneCount(gd, op.grade);
                const int64_t newAmount =
                    (current > INT64_MAX - op.delta) ? INT64_MAX : current + op.delta;
                gd = withSpiritStoneCount(gd, op.grade, newAmount);
                recordAnnual(gd, op.delta, op.reason, op.source);
                out.successCount++;
                out.results.push_back(deductSuccess(newAmount));
            } else {
                if (op.delta == INT64_MIN) {
                    out.failedCount++;
                    out.results.push_back(deductInvalid());
                    continue;
                }
                const int64_t absAmount = -op.delta;
                const int64_t current = spiritStoneCount(gd, op.grade);
                if (current < absAmount) {
                    out.failedCount++;
                    out.results.push_back(deductInsufficient(current, absAmount));
                    continue;
                }
                const int64_t newAmount = (current - absAmount) < 0 ? 0 : (current - absAmount);
                gd = withSpiritStoneCount(gd, op.grade, newAmount);
                recordAnnual(gd, op.delta, op.reason, op.source);
                out.successCount++;
                out.results.push_back(deductSuccess(newAmount));
            }
        }
        return out;
    }

private:
    struct AutoSellPlan {
        int64_t sellMidCount = 0;
        int64_t sellHighCount = 0;
        int64_t gainedLow = 0;
    };

    /// 计算自动售卖计划（Kotlin calculateAutoSell）
    static AutoSellPlan calculateAutoSell(const state::GameData& gd, int64_t shortfall) {
        AutoSellPlan plan;
        int64_t remaining = shortfall;

        if (gd.autoSellMidGradeForPurchase && remaining > 0 && gd.midGradeSpiritStones > 0) {
            const int64_t ratio = SpiritStoneExchange::kEffectiveRatio;
            plan.sellMidCount = ((remaining + ratio - 1) / ratio);
            if (plan.sellMidCount > gd.midGradeSpiritStones) {
                plan.sellMidCount = gd.midGradeSpiritStones;
            }
            if (plan.sellMidCount > 0) {
                const int64_t gained = SpiritStoneExchange::toLowGrade(
                    plan.sellMidCount, SpiritStoneGrade::MID);
                plan.gainedLow += gained;
                remaining = (remaining - gained) < 0 ? 0 : (remaining - gained);
            }
        }
        if (gd.autoSellHighGradeForPurchase && remaining > 0 && gd.highGradeSpiritStones > 0) {
            const int64_t highRatio = SpiritStoneExchange::kEffectiveRatio *
                                      SpiritStoneExchange::kEffectiveRatio;
            plan.sellHighCount = (remaining + highRatio - 1) / highRatio;
            if (plan.sellHighCount > gd.highGradeSpiritStones) {
                plan.sellHighCount = gd.highGradeSpiritStones;
            }
            if (plan.sellHighCount > 0) {
                plan.gainedLow += SpiritStoneExchange::toLowGrade(
                    plan.sellHighCount, SpiritStoneGrade::HIGH);
            }
        }
        return plan;
    }

    /// 执行自动售卖（Kotlin autoSellHigherGrades；含 AutoSell 年度记录）
    static void autoSellHigherGrades(state::GameData& gd, const AutoSellPlan& plan) {
        if (plan.sellMidCount > 0) {
            const int64_t gainedLow = SpiritStoneExchange::toLowGrade(
                plan.sellMidCount, SpiritStoneGrade::MID);
            gd.midGradeSpiritStones -= plan.sellMidCount;
            gd.spiritStones += gainedLow;
            recordAnnual(gd, -plan.sellMidCount, "AutoSell", "Internal");
            recordAnnual(gd, gainedLow, "AutoSell", "Internal");
        }
        if (plan.sellHighCount > 0) {
            const int64_t gainedLow = SpiritStoneExchange::toLowGrade(
                plan.sellHighCount, SpiritStoneGrade::HIGH);
            gd.highGradeSpiritStones -= plan.sellHighCount;
            gd.spiritStones += gainedLow;
            recordAnnual(gd, -plan.sellHighCount, "AutoSell", "Internal");
            recordAnnual(gd, gainedLow, "AutoSell", "Internal");
        }
    }

    /// 年度报告累积（Kotlin recordAndEmit 的年度部分）
    static void recordAnnual(state::GameData& gd, int64_t delta,
                             const std::string& reason, const std::string& source) {
        if (delta > 0) {
            const auto it = gd.annualIncomeBySource.find(source);
            const int64_t cur = (it != gd.annualIncomeBySource.end()) ? it->second : 0;
            gd.annualIncomeBySource[source] = cur + delta;
            gd.annualTotalIncome += delta;
        } else if (delta < 0) {
            const int64_t absD = -delta;
            const auto it = gd.annualExpenditureByReason.find(reason);
            const int64_t cur = (it != gd.annualExpenditureByReason.end()) ? it->second : 0;
            gd.annualExpenditureByReason[reason] = cur + absD;
            gd.annualTotalExpenditure += absD;
        }
    }
};

}  // namespace gamecore::system
