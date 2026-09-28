#include <gtest/gtest.h>

#include <chrono>
#include <cstdio>

#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"

// ============================================================
// AccrualSegmentBench — 积分段耗时门禁（结算改造 2026-09-27 §10 B8）
//
// 方案 §10 B8 验收：积分段 < 1ms @5000 弟子（门禁断言）。积分段 =
// GameCore::accrue 内 accrueContinuous（L1）+ accrueMonthlyContinuous（L3）
// 两段连续积分（判定窗口循环不属积分段）；本 bench 走生产同入口 accrue
// （100ms tick 形态 = 0 判定窗口），实测即生产每 tick 积分路径成本。
//
// ## 口径（与 phase_settlement_bench G1 同族可比）
// 硬门禁场景 = G1 门禁同族的修炼热路径形态（5000 弟子，无实例清单）——
// 那边同族全八步每旬结算 1176us@5000，本门断言其连续积分子集 < 1000us。
// 真实快照形态（每弟子 1 功法 + 2 装备）为信息观测（打印无断言，沿
// TimingPerPhase 先例）——其成本主体是按 tick 频次重建的桶视图与逐项
// 累积（数据驱动，随在册实例数伸缩），登记于方案 §7.2/D1 债观察项。
//
// 采样取 min-of-N（warmup 后最优样本，抗调度/频率抖动）；预算判据与
// GameCore::kAccrualSegmentBudgetUs（D1 债触发判据同源，方案 §7）一致。
// 超门即红 = D1 触发条件的桌面实证，须归因入报告。
// ============================================================

namespace gamecore {
namespace {

constexpr int32_t kBenchDisciples = 5000;

/// 最小存活弟子（炼气九层一层；修炼远未满——积分路径全链活跃；
/// 与 phase_settlement_bench 同族场景逐字段一致）
state::Disciple makeBenchDisciple(int32_t n) {
    state::Disciple d;
    d.id = std::to_string(n);
    d.name = "弟子" + std::to_string(n);          // ≤15 字节（跨工具链 SSO）
    d.realm = 9;
    d.realmLayer = 1;
    d.cultivation = 10.0;
    d.isAlive = true;
    d.spiritRootType = "metal";
    return d;
}

/// 全量场景：每弟子 1 功法实例 + 2 件已装备实例（实例 id 全局唯一）
void populateFullInventory(state::GameState& state) {
    state.disciples.manualIds.resize(kBenchDisciples);
    state.disciples.weaponIds.resize(kBenchDisciples);
    state.disciples.armorIds.resize(kBenchDisciples);
    for (int32_t n = 1; n <= kBenchDisciples; ++n) {
        const std::string id = std::to_string(n);

        state::ManualInstance mn;
        mn.id = "m" + id;
        mn.name = "功法" + id;
        mn.type = "MIND";
        mn.ownerId = id;
        mn.isLearned = true;
        state.manualInstances.push_back(mn);
        state.disciples.manualIds[n - 1] = {mn.id};

        state::EquipmentInstance w;
        w.id = "w" + id;
        w.name = "剑" + id;
        w.slot = "WEAPON";
        w.ownerId = id;
        w.isEquipped = true;
        state.equipmentInstances.push_back(w);
        state.disciples.weaponIds[n - 1] = w.id;

        state::EquipmentInstance a;
        a.id = "a" + id;
        a.name = "甲" + id;
        a.slot = "ARMOR";
        a.ownerId = id;
        a.isEquipped = true;
        state.equipmentInstances.push_back(a);
        state.disciples.armorIds[n - 1] = a.id;
    }
}

/// 100ms tick 形态 accrue 的 min-of-N 采样（微秒；0 判定窗口 = 纯积分段）。
/// N=15/预热 3：门禁实测 best≈670us vs 预算 1000us，裕度 1.5×——首轮全量
/// ctest 曾在构建余载下一次性假红（B8 途中实证），加深采样压低尾噪。
template <typename Fn>
double bestAccrueUs(Fn&& accrue) {
    accrue();
    accrue();
    accrue();
    double best = 1e18;
    for (int i = 0; i < 15; ++i) {
        const auto t0 = std::chrono::steady_clock::now();
        accrue();
        const auto t1 = std::chrono::steady_clock::now();
        const double us =
            std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0)
                .count() /
            1000.0;
        if (us < best) best = us;
    }
    return best;
}

}  // namespace

// ── B8 硬门禁：积分段 < 1ms @5000 弟子（G1 同族 core 形态）──────────
TEST(AccrualSegmentBench, SegmentUnderBudgetAt5000) {
    FixedClock clock;
    NullLogger logger;
    GameCore core(&clock, &logger);
    GameCoreConfig config;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));

    auto& state = core.state();
    for (int32_t n = 1; n <= kBenchDisciples; ++n) {
        state.disciples.appendDisciple(makeBenchDisciple(n));
    }

    const double bestUs = bestAccrueUs([&] { core.accrue(100, true); });

    const auto& tel = core.accrualTelemetry();
    std::printf(
        "[AccrualSegmentBench] accrue(100ms) D=5000 core: best %.1f us "
        "(last %lld us, max %lld us, samples %lld, overBudget %lld)\n",
        bestUs, static_cast<long long>(tel.lastSegmentUs),
        static_cast<long long>(tel.maxSegmentUs),
        static_cast<long long>(tel.samples),
        static_cast<long long>(tel.overBudgetCount));

    // 遥测计数面自洽：3 预热 + 15 采样 = 18 次
    EXPECT_EQ(tel.samples, 18);
    EXPECT_GE(tel.lastSegmentUs, 0);
    EXPECT_EQ(tel.overBudgetCount, 0);  // 门禁通过 ⇒ 无超预算样本

    // B8 硬门禁（min 采样 vs 1ms 预算；G1 同族全八步 1176us，本门为其
    // 连续积分子集）——超门即 D1 债触发条件成立，红 = 如实
    EXPECT_LT(bestUs, static_cast<double>(GameCore::kAccrualSegmentBudgetUs));
}

// ── 信息观测：真实快照形态（每弟子 1 功法 + 2 装备，打印无断言）────
// 成本主体 = 按 tick 频次重建的桶视图 + 逐项累积（数据驱动，随在册实例
// 数伸缩）——方案 §7.2/D1 登记的形状观察项。B8 途中修复：孕养步
// "桶命中后再全量 O(I) 线性扫"缺陷形（修前 167ms@5000，修后见打印）。
TEST(AccrualSegmentBench, SegmentFullInventoryObservation) {
    FixedClock clock;
    NullLogger logger;
    GameCore core(&clock, &logger);
    GameCoreConfig config;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));

    auto& state = core.state();
    for (int32_t n = 1; n <= kBenchDisciples; ++n) {
        state.disciples.appendDisciple(makeBenchDisciple(n));
    }
    populateFullInventory(state);

    const double bestUs = bestAccrueUs([&] { core.accrue(100, true); });
    const auto& tel = core.accrualTelemetry();
    std::printf(
        "[AccrualSegmentBench] accrue(100ms) D=5000 full-inventory: "
        "best %.1f us (overBudget %lld)\n",
        bestUs, static_cast<long long>(tel.overBudgetCount));
}

// ── 小规模对照（100 弟子）：积分段应远低于预算（正常路径负证）──────
TEST(AccrualSegmentBench, SegmentSmallSectWellUnderBudget) {
    FixedClock clock;
    NullLogger logger;
    GameCore core(&clock, &logger);
    GameCoreConfig config;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));

    auto& state = core.state();
    for (int32_t n = 1; n <= 100; ++n) {
        state.disciples.appendDisciple(makeBenchDisciple(n));
    }

    const auto t0 = std::chrono::steady_clock::now();
    core.accrue(100, true);
    const auto t1 = std::chrono::steady_clock::now();
    const double us =
        std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count() /
        1000.0;
    std::printf("[AccrualSegmentBench] accrue(100ms) D=100: %.1f us\n", us);
    EXPECT_LT(us, static_cast<double>(GameCore::kAccrualSegmentBudgetUs));

    // 旗标关零采样（旧行为臂不进积分段，遥测不增长）
    const auto samplesBefore = core.accrualTelemetry().samples;
    EXPECT_EQ(system::kSettleFlagNone, core.accrue(100, false));
    EXPECT_EQ(samplesBefore, core.accrualTelemetry().samples);
}

}  // namespace gamecore
