#include <gtest/gtest.h>

#include <chrono>
#include <cstdio>
#include <string>

#include "gamecore/state/dirty_tracker.h"

namespace gamecore {
namespace {

// ============================================================
// DirtyTracker diffToJson 性能基准（同步通道开销对照）
//
// 当前实现：基线缓存为 JSON 树，每导出仅当前状态一次序列化（比较后
// 移入缓存，零深拷贝）；协议与消费语义不变。
//
// 场景为"每旬全脏"最坏情形（所有弟子修炼推进 → disciples 全量 upsert）
// 与"空闲零变更"对照。输出仅供人工观测对比（CI 抖动不设断言阈值）。
// ============================================================

using state::DirtyTracker;
using state::GameState;

/// 防优化 sink（结果尺寸写 volatile，禁止编译器裁掉 diff 调用）
volatile std::size_t g_benchSink = 0;

state::Disciple makeBenchDisciple(int32_t id) {
    state::Disciple d;
    d.id = std::to_string(id);
    d.name = "弟子" + std::to_string(id);
    d.realm = id % 9;
    d.realmLayer = 1;
    d.cultivation = 10.0;
    d.isAlive = true;
    return d;
}

/// 采样 rounds 轮取最小值（warmup 预热缓存/分配器）
template <typename F>
double bestOfUs(int warmup, int samples, F&& block) {
    for (int i = 0; i < warmup; ++i) block();
    double best = 1e18;
    for (int i = 0; i < samples; ++i) {
        const auto t0 = std::chrono::steady_clock::now();
        block();
        const auto t1 = std::chrono::steady_clock::now();
        const double us =
            std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count() / 1000.0;
        if (us < best) best = us;
    }
    return best;
}

TEST(DirtyTrackerBench, DiffToJsonAllDirtyPerPhase) {
    const int counts[] = {100, 1000, 5000};
    for (int n : counts) {
        GameState state;
        for (int32_t i = 0; i < n; ++i) {
            state.disciples.appendDisciple(makeBenchDisciple(i));
        }
        DirtyTracker tracker;
        tracker.resetBaseline(state);

        // 每旬全脏：所有弟子 cultivation 推进（生产每旬形态）
        const auto n32 = static_cast<int32_t>(n);
        const double bestUs = bestOfUs(3, 5, [&] {
            for (int32_t r = 0; r < n32; ++r) {
                state.disciples.cultivations[r] += 1.0;
            }
            const std::string out = tracker.diffToJson(state);
            g_benchSink = out.size();
        });
        std::printf("[DirtyTrackerBench] diffToJson all-dirty n=%d: %.0f us\n", n, bestUs);
    }
}

TEST(DirtyTrackerBench, DiffToJsonIdleNoChange) {
    const int counts[] = {100, 5000};
    for (int n : counts) {
        GameState state;
        for (int32_t i = 0; i < n; ++i) {
            state.disciples.appendDisciple(makeBenchDisciple(i));
        }
        DirtyTracker tracker;
        tracker.resetBaseline(state);

        // 空闲零变更：仅当前状态序列化 + 树比较（应显著低于全脏场景）
        const double bestUs = bestOfUs(3, 5, [&] {
            const std::string out = tracker.diffToJson(state);
            g_benchSink = out.size();
        });
        std::printf("[DirtyTrackerBench] diffToJson idle n=%d: %.0f us\n", n, bestUs);
    }
}

}  // namespace
}  // namespace gamecore
