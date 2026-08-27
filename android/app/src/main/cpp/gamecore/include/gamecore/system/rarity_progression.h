#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <utility>
#include <vector>

#include "gamecore/rng/rng_manager.h"

// ============================================================
// 品阶时间进度曲线（Kotlin→C++ 迁移计划 v2 阶段 4 / 批 4-4）
//
// 等价移植 Kotlin RarityTimeProgression（旅行商人 / 收购 / 宗门交易共用）：
//   - maxRarityForYear：分段开放品阶范围（[1,20) 凡 → [20,80) 凡~灵 →
//     [80,300) 凡~宝 → [300,500) 凡~玄 → [500,1500) 凡~地 → [1500,∞) 凡~天）
//   - pityRarityForYear：保底品阶（下一分段最高品阶；末段取自身）
//   - rarityWeightsForYear：段内线性插值 + 幂份额曲线 + 3000 年后爬升轨道
//   - rollRarity：按权重累计抽样，**恰好消费 1 次 nextDouble**
//
// 与 Kotlin 语义对齐要点：
//   - 段间权重有意不连续（每段起点从凡品主导爬升，勿"修正"）
//   - rollRarity 的 rng 由调用方传入（宗门交易用本地种子 RNG；
//     旅行商人用 SYSTEM 分区）——本文件不绑定固定分区
//   - 权重表 std::map 有序（确定性；抽样按品阶降序遍历）
// ============================================================
namespace gamecore::system {

// ── 品阶时间曲线配置（Kotlin RarityTimeProgression 同源）─────
namespace rarity_progression_cfg {
inline constexpr double kRarityOneWeightAtSegmentStart = 0.9;
inline constexpr double kRarityOneWeightAtSegmentEnd = 0.1;
inline constexpr double kSharePower = 2.0;
inline constexpr int32_t kFinalSegmentEndYear = 3000;
inline constexpr int32_t kPost3000StepYears = 100;
inline constexpr double kXuanStepPerCentury = 0.0004;
inline constexpr double kEarthStepPerCentury = 0.0002;
inline constexpr double kMythicStepPerCentury = 0.0004;
inline constexpr double kFanpinStepPerCentury = 0.0006;
inline constexpr double kLingpinStepPerCentury = 0.0004;
inline constexpr double kMaxMythicProbability = 0.02;
inline constexpr double kFanpinFloor = 0.04;
inline constexpr double kLingpinFloor = 0.15;
}  // namespace rarity_progression_cfg

/// 分段表：(分段起始年, 分段最高品阶)，按起始年升序
inline const std::vector<std::pair<int32_t, int32_t>>& raritySegments() {
    static const std::vector<std::pair<int32_t, int32_t>> kSegments = {
        {1, 1}, {20, 2}, {80, 3}, {300, 4}, {500, 5}, {1500, 6},
    };
    return kSegments;
}

/// 该年份可出现的最高品阶（Kotlin maxRarityForYear；year < 1 防御性返回 1）
inline int32_t maxRarityForYear(int32_t year) {
    const int32_t y = std::max(year, 1);
    int32_t result = 1;
    for (const auto& [startYear, rarity] : raritySegments()) {
        if (y >= startYear) result = rarity;
    }
    return result;
}

/// 保底品阶：下一分段的最高品阶（末段取自身；Kotlin pityRarityForYear）
inline int32_t pityRarityForYear(int32_t year) {
    const int32_t max = maxRarityForYear(year);
    const auto& segs = raritySegments();
    std::size_t index = 0;
    for (std::size_t i = 0; i < segs.size(); ++i) {
        if (segs[i].second == max) index = i;
    }
    if (index == segs.size() - 1) return max;
    return segs[index + 1].second;
}

/// 3000 年后爬升总档数（天品从末段终点权重爬升到 MAX_MYTHIC_PROBABILITY）
inline int32_t post3000MaxSteps() {
    // (1..5) sumOf { it^2 } = 1+4+9+16+25 = 55
    const double w6Base =
        (1.0 - rarity_progression_cfg::kRarityOneWeightAtSegmentEnd) / 55.0;
    return static_cast<int32_t>((rarity_progression_cfg::kMaxMythicProbability - w6Base) /
                                rarity_progression_cfg::kMythicStepPerCentury);
}

/// 3000 年后爬升：每 100 年从凡品/灵品抽取权重反哺玄/地/天（抽取总量 = 注入总量）
inline std::map<int32_t, double> applyPost3000Climb(const std::map<int32_t, double>& base,
                                                    int32_t year) {
    const int32_t k = std::clamp((year - rarity_progression_cfg::kFinalSegmentEndYear) /
                                     rarity_progression_cfg::kPost3000StepYears,
                                 0, post3000MaxSteps());
    std::map<int32_t, double> adjusted = base;
    adjusted[6] = base.at(6) + static_cast<double>(k) * rarity_progression_cfg::kMythicStepPerCentury;
    adjusted[5] = base.at(5) + static_cast<double>(k) * rarity_progression_cfg::kEarthStepPerCentury;
    adjusted[4] = base.at(4) + static_cast<double>(k) * rarity_progression_cfg::kXuanStepPerCentury;
    adjusted[1] = std::max(base.at(1) - static_cast<double>(k) * rarity_progression_cfg::kFanpinStepPerCentury,
                           rarity_progression_cfg::kFanpinFloor);
    adjusted[2] = std::max(base.at(2) - static_cast<double>(k) * rarity_progression_cfg::kLingpinStepPerCentury,
                           rarity_progression_cfg::kLingpinFloor);
    return adjusted;
}

/// 权重归一化（各值除以总和）
inline std::map<int32_t, double> normalizeRarityWeights(const std::map<int32_t, double>& weights) {
    double sum = 0.0;
    for (const auto& [r, w] : weights) sum += w;
    std::map<int32_t, double> out;
    for (const auto& [r, w] : weights) out[r] = w / sum;
    return out;
}

/// 该年份的品阶权重表（键 1..maxRarity，归一化后和为 1.0；Kotlin rarityWeightsForYear）
inline std::map<int32_t, double> rarityWeightsForYear(int32_t year) {
    const int32_t maxRarity = maxRarityForYear(year);
    if (maxRarity == 1) return {{1, 1.0}};  // 纯凡品段（防除零）

    const auto& segs = raritySegments();
    std::size_t segIndex = 0;
    for (std::size_t i = 0; i < segs.size(); ++i) {
        if (segs[i].second == maxRarity) segIndex = i;
    }
    const int32_t segStart = segs[segIndex].first;
    const int32_t segEnd = segIndex == segs.size() - 1
                               ? rarity_progression_cfg::kFinalSegmentEndYear
                               : segs[segIndex + 1].first;
    // Double 计算防 Int 溢出；t 越界钳制（3000 年后饱和在 1.0）
    const double t = std::clamp(
        (static_cast<double>(std::max(year, segStart)) - static_cast<double>(segStart)) /
            static_cast<double>(segEnd - segStart),
        0.0, 1.0);

    std::map<int32_t, double> weights;
    const double w1 = rarity_progression_cfg::kRarityOneWeightAtSegmentStart +
                      (rarity_progression_cfg::kRarityOneWeightAtSegmentEnd -
                       rarity_progression_cfg::kRarityOneWeightAtSegmentStart) *
                          t;
    weights[1] = w1;
    const double otherTotal = 1.0 - w1;
    std::map<int32_t, double> shares;
    double shareSum = 0.0;
    for (int32_t r = 2; r <= maxRarity; ++r) {
        const double share =
            std::pow(static_cast<double>(maxRarity - r + 1), rarity_progression_cfg::kSharePower);
        shares[r] = share;
        shareSum += share;
    }
    for (const auto& [r, share] : shares) {
        weights[r] = otherTotal * share / shareSum;
    }

    std::map<int32_t, double> adjusted;
    if (maxRarity == 6 && year > rarity_progression_cfg::kFinalSegmentEndYear) {
        adjusted = applyPost3000Climb(weights, year);
    } else {
        adjusted = weights;
    }
    return normalizeRarityWeights(adjusted);
}

/// 按权重累计抽样（Kotlin rollRarity；恰好消费 1 次 nextDouble）
/// rng 由调用方传入（宗门交易本地种子 / 旅行商人 SYSTEM 分区）。
inline int32_t rollRarity(rng::DeterministicRng& rng, int32_t year) {
    const auto weights = rarityWeightsForYear(year);
    const double rand = rng.nextDouble();
    double cumulative = 0.0;
    // weights.entries.sortedByDescending { it.key } —— 品阶降序遍历（std::map 反序）
    for (auto it = weights.rbegin(); it != weights.rend(); ++it) {
        cumulative += it->second;
        if (rand < cumulative) return it->first;
    }
    return 1;  // fallback：权重表恒含品阶 1
}

}  // namespace gamecore::system
