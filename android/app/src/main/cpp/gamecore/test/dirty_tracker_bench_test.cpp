#include <gtest/gtest.h>

#include <chrono>
#include <cstdio>
#include <string>

#include "gamecore/state/dirty_tracker.h"
#include "gamecore/state/gameview_encode.h"

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

// R2.2 镜像通道传输编码开销对照（PhaseSegmentTimer mirror 段"未劣化"证据）：
// 同一"每旬全脏"负载下，对同一棵 diffToTree 分别取两种终端编码——
//   JSON 文本：tree.dump()（旧 nativeExportDirty 载荷）
//   protobuf ：state::encodeGameView(tree)（换轨后载荷）
// 二者共享 diffToTree 全量序列化 + 基线推进（mirror 段主体，两格式逐字节同源），
// 差异仅在终端编码。断言恒真（不设阈值门，CI 抖动），printf 供人工观测：
// protobuf 信封字节应显著 < JSON 文本、终端编码耗时不劣于 dump（消费者侧还省
// 一次 kotlinx 全量 JSON parse）。G2 <10ms 终态由 R2.3 镜像瘦身达成（本批镜像仍全量）。
TEST(DirtyTrackerBench, MirrorTransportJsonVsProtobuf) {
    const int counts[] = {100, 1000, 5000};
    volatile std::size_t jsonBytes = 0;
    volatile std::size_t protoBytes = 0;
    for (int n : counts) {
        const auto n32 = static_cast<int32_t>(n);
        const double jsonUs = bestOfUs(3, 5, [&] {
            GameState state;
            for (int32_t i = 0; i < n32; ++i) state.disciples.appendDisciple(makeBenchDisciple(i));
            DirtyTracker tracker;
            tracker.resetBaseline(state);
            for (int32_t r = 0; r < n32; ++r) state.disciples.cultivations[r] += 1.0;
            const std::string out = tracker.diffToJson(state);
            jsonBytes = out.size();
        });
        const double protoUs = bestOfUs(3, 5, [&] {
            GameState state;
            for (int32_t i = 0; i < n32; ++i) state.disciples.appendDisciple(makeBenchDisciple(i));
            DirtyTracker tracker;
            tracker.resetBaseline(state);
            for (int32_t r = 0; r < n32; ++r) state.disciples.cultivations[r] += 1.0;
            const nlohmann::json tree = tracker.diffToTree(state);
            const std::string out = state::encodeGameView(tree, "bench");
            protoBytes = out.size();
        });
        std::printf(
            "[DirtyTrackerBench] mirror n=%d JSON=%.0fus/%zuB  protobuf=%.0fus/%zuB\n",
            n, jsonUs, static_cast<std::size_t>(jsonBytes),
            protoUs, static_cast<std::size_t>(protoBytes));
        EXPECT_GT(jsonBytes, 0u);
        EXPECT_GT(protoBytes, 0u);
    }
}

}  // namespace
}  // namespace gamecore
