#include <gtest/gtest.h>

#include <chrono>
#include <cstdio>

#include "gamecore/state/models.h"
#include "gamecore/system/settlement.h"      // kMsPerPhase1x 常量
#include "gamecore/system/phase_settlement.h"

namespace gamecore {
namespace {

// ============================================================
// DiscipleStore 每旬核心批次性能基准（数据导向存储收益对照）
//
// 对照基线（Kotlin Phase0SettlementBenchmarkTest）：
//   Kotlin 每旬核心路径（HP/MP 恢复 + 修炼累积，真实 CultivationCore 列直读）：
//   100 弟子 167µs · 1000 弟子 327µs · 5000 弟子 1189µs（O(D)，每弟子 ~0.3µs）
//
// 本基准测量 C++ DiscipleStore SoA 版的 runPhaseCoreBatch（热路径，
// 全链路列直读直写）。输出仅供人工观测对比（CI 抖动不设断言阈值）。
// ============================================================

using state::Disciple;
using state::DiscipleStore;
using state::GameData;
using state::GameState;

Disciple makeDisciple(int32_t id, int32_t realm) {
    Disciple d;
    d.id = std::to_string(id);
    d.name = "弟子" + std::to_string(id);
    d.realm = realm;
    d.realmLayer = 1;
    d.cultivation = 10.0;
    d.isAlive = true;
    return d;
}

/// 采样 rounds 轮取最小值（warmup 预热 JIT/缓存）
template <typename F>
double bestOf(int warmup, int samples, F&& block) {
    for (int i = 0; i < warmup; ++i) block();
    double best = 1e18;
    for (int i = 0; i < samples; ++i) {
        const auto t0 = std::chrono::steady_clock::now();
        block();
        const auto t1 = std::chrono::steady_clock::now();
        const double us = std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count() / 1000.0;
        if (us < best) best = us;
    }
    return best;
}

TEST(DiscipleStoreBench, CoreBatchPerPhase) {
    const int counts[] = {100, 1000, 5000};
    for (int n : counts) {
        GameState state;
        state.disciples.clear();
        for (int32_t i = 0; i < n; ++i) {
            state.disciples.appendDisciple(makeDisciple(i + 1, 9));
        }
        ecs::World benchWorld;   // E2：迭代域实体集（bestOf 内复用——稳态零重建）
        const double us = bestOf(3, 5, [&]() {
            system::runPhaseCoreBatch(state, benchWorld);
        });
        std::printf("[DiscipleStoreBench] runPhaseCoreBatch D=%d: %.1f us (%.3f us/disciple)\n",
                    n, us, us / n);
    }
    // 对照 Kotlin 基线 327µs@1000（输出供人工对比，无断言）
    std::printf("[DiscipleStoreBench] baseline: Kotlin CultivationCore 327us@1000 (phase0)\n");
}

}  // namespace
}  // namespace gamecore
