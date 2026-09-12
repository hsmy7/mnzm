#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <string>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/cultivation.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/disciple_stats.h"

// ============================================================
// 突破系统
//
// 等价移植 Kotlin DiscipleBreakthroughHandler 的**纯逻辑**部分：
//   - 突破前置判定：修为满 + HP/MP 满 + realm > 0
//   - 突破概率判定：BREAKTHROUGH 分区 RNG nextDouble() < chance
//   - 成功应用：修为清零、层数+1 或大境界+1（寿命增益）、检查点同步
//   - 失败应用：修为清零、HP/MP × 10%（至少 1）
//   - 循环突破（满修为可连续突破，realm==0 停止）
//   - 突破计数写入 + 广告加成清除 + 完成时间预估
//
// 与 Kotlin 语义对齐要点：
//   - RNG 走 BREAKTHROUGH 分区（rng.getRng(RngPartition::kBreakthrough)）
//   - 失败 HP/MP = max(1, (cur * 0.1).toInt())——Kotlin toInt 截断
//   - 突破成功后 checkpointDisciple（速率变化点）
// ============================================================
namespace gamecore::system {

/// 突破失败 HP/MP 剩余比例（Kotlin BREAKTHROUGH_FAILURE_HP_MP_RATIO = 0.1）
constexpr double kBreakthroughFailureHpMpRatio = 0.1;

/// 突破结果（单次尝试）
struct BreakthroughOutcome {
    bool success = false;
    state::Disciple disciple;
    int32_t breakthroughCount = 0;  // 累计成功次数
    int32_t failCount = 0;          // 累计失败次数
};

/// 是否满足突破前置（修为满 + HP/MP 满）。
/// 负值 currentHp/currentMp 视为满（Kotlin -1=满语义）；上限按**基础口径**
/// computeBaseHpMp（天赋+词条+层数/方差乘区）计算——影子路径无 GameState，
/// 不含装备/功法加成；旬结算生产路径的等价判定见 phase_settlement::isFullHpMp
/// （含装备/功法映射重建）。
inline bool isDiscipleFullHpMp(const state::Disciple& disciple) {
    const auto effects = stats::mergeEffects(
        stats::talentEffectsFor(disciple.talentIds),
        stats::affixEffectsFor(disciple.affixIds));
    int32_t maxHp = 0;
    int32_t maxMp = 0;
    stats::computeBaseHpMp(disciple.realm, disciple.realmLayer, disciple.hpVariance,
                           disciple.mpVariance, effects, nullptr, maxHp, maxMp);
    const int32_t hp = disciple.currentHp < 0 ? maxHp : disciple.currentHp;
    const int32_t mp = disciple.currentMp < 0 ? maxMp : disciple.currentMp;
    return hp >= maxHp && mp >= maxMp;
}

/// 单次突破尝试：返回是否成功（RNG 判定）
inline bool tryBreakthrough(const state::Disciple& disciple, double chance,
                            rng::RngManager& rng) {
    return rng.getRng(rng::RngPartition::kBreakthrough).nextDouble() < chance;
}

/// 突破成功应用（Kotlin applyBreakthroughSuccess）
/// @param lifespanGain 突破寿命增益（由调用方计算：calculateBreakthroughLifespanGain）
inline state::Disciple applyBreakthroughSuccess(state::Disciple d,
                                                int32_t lifespanGain) {
    const int32_t oldRealm = d.realm;
    d.cultivation = 0.0;
    const auto& rc = gamecore::disciple::realmConfig(d.realm);
    if (d.realmLayer < rc.maxLayers) {
        d.realmLayer += 1;
    } else {
        d.realm -= 1;
        d.realmLayer = 1;
    }
    if (d.realm != oldRealm) {
        d.lifespan += lifespanGain;
    }
    return d;
}

/// 突破失败应用（Kotlin applyBreakthroughFailure：修为清零 + HP/MP 打一折）。
/// curHp/currentMp 负数取基础口径上限（computeBaseHpMp），与
/// phase_settlement::applyBreakthroughFailure 同式（HP/MP × 0.1 至少 1）。
inline state::Disciple applyBreakthroughFailure(state::Disciple d) {
    const auto effects = stats::mergeEffects(
        stats::talentEffectsFor(d.talentIds),
        stats::affixEffectsFor(d.affixIds));
    int32_t maxHp = 0;
    int32_t maxMp = 0;
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
    return d;
}

/// 连续突破循环（Kotlin performBreakthrough 核心）
/// @param chanceProvider 概率计算函数（返回 [0,1]；由调用方组装乘区）
/// @param lifespanGainProvider 寿命增益函数（大境界变化时调用）
/// @param maxIterations 循环保护上限（防数据损坏死循环）
inline BreakthroughOutcome performBreakthrough(
    state::Disciple d,
    const std::function<double(const state::Disciple&)>& chanceProvider,
    const std::function<int32_t(const state::Disciple&)>& lifespanGainProvider,
    rng::RngManager& rng,
    int32_t currentMonth, int32_t maxIterations = 16) {
    BreakthroughOutcome out;
    out.disciple = d;
    bool shouldContinue = true;
    int32_t iteration = 0;

    while (shouldContinue && out.disciple.realm > 0 && iteration < maxIterations) {
        iteration++;
        const double maxCult = computeMaxCultivation(
            out.disciple.realm, out.disciple.realmLayer, out.disciple.cultivation);
        if (out.disciple.cultivation < maxCult) break;
        if (!isDiscipleFullHpMp(out.disciple)) break;

        const double chance = chanceProvider(out.disciple);
        if (tryBreakthrough(out.disciple, chance, rng)) {
            out.breakthroughCount++;
            const int32_t gain = lifespanGainProvider(out.disciple);
            out.disciple = applyBreakthroughSuccess(out.disciple, gain);
        } else {
            out.failCount++;
            out.disciple = applyBreakthroughFailure(out.disciple);
            shouldContinue = false;
        }
    }

    // Checkpoint：突破后境界/层数可能变化，同步检查点
    checkpointDisciple(out.disciple, currentMonth);
    return out;
}

/// 修炼完成时间预估（Kotlin LazyEvaluationDispatcher.estimateMonthsToNextBreakthrough）
/// phasesNeeded = ceil(remaining / rate)；月数 = ceil(phasesNeeded / 3) 用整数
/// (phasesNeeded + 2) / 3 表达；remaining<=0 返回 0；rate<=0 返回极大值
inline int32_t estimateMonthsToNextBreakthrough(double remaining, double rate) {
    if (remaining <= 0.0) return 0;
    if (rate <= 0.0) return INT32_MAX;
    const int32_t phasesNeeded =
        static_cast<int32_t>(std::ceil(remaining / rate));
    return (phasesNeeded + 2) / 3;  // 3 旬 = 1 月，向上取整
}

}  // namespace gamecore::system
