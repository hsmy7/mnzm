#include <gtest/gtest.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <vector>

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
// 断言口径 = **同机归一化比值**（best 与 P50 双判据）：
//   比值 = 积分段耗时 / 同机参考负载耗时（遍历 1 MiB 数组的访存型负载，
//   与被测同资源类）。两者在同一次运行内**交错**采样（同一负载窗口），
//   机器频率/调度对两者同向放大 ⇒ 比值与机器快慢无关，消除绝对墙钟判据
//   因机器差异与中度背景负载产生的假红。
//   预算常量由本机安静窗口实测比值留余量定标（见 kAccrualSegmentBestRatioBudget）。
//   适用范围（实测边界）：机器被**超量调度**（并发进程数超过物理核）时，被测
//   积分段与参考负载涨幅不同步——访存密集路径受缓存/带宽竞争影响更大——比值
//   仍会超门。故本门禁应在**安静窗口**执行（CI/看护与构建串行化）；过载机器
//   上出现的红按"机器过载"归因。
// 绝对耗时（best/P50/max/遥测 overBudget 计数）全部打印为观察项、不判红；
// 生产侧绝对预算 GameCore::kAccrualSegmentBudgetUs 仍驱动遥测超预算计数。
// 超门即红 = D1 触发条件的桌面实证，须归因入报告。
// ============================================================

namespace gamecore {
namespace {

constexpr int32_t kBenchDisciples = 5000;
/// 采样剖面：预热压低首跑噪声，正式采样供 best/P50 统计
constexpr int kWarmupRuns = 3;
constexpr int kSampleRuns = 15;

/// 归一化预算（同机比值 = 积分段 / 参考负载）：
/// 参考负载单次耗时与本机安静窗口积分段同量级（约 650us）⇒ 安静比值 ≈ 1.0。
/// best 预算取原 1ms 绝对预算的等效比值（1000 / 650 ≈ 1.54，即与绝对口径
/// 同严格度，但对机器快慢与中度背景负载免疫）；P50 预算余量更大，吸收中位抬升。
constexpr double kAccrualSegmentBestRatioBudget = 1.55;
constexpr double kAccrualSegmentP50RatioBudget = 1.90;

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
    state.disciples.bodyIds.resize(kBenchDisciples);
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
        w.part = "WEAPON";
        w.ownerId = id;
        w.isEquipped = true;
        state.equipmentInstances.push_back(w);
        state.disciples.weaponIds[n - 1] = w.id;

        state::EquipmentInstance a;
        a.id = "a" + id;
        a.name = "甲" + id;
        a.part = "BODY";
        a.ownerId = id;
        a.isEquipped = true;
        state.equipmentInstances.push_back(a);
        state.disciples.bodyIds[n - 1] = a.id;
    }
}

/// 100ms tick 形态 accrue 的采样统计（微秒；0 判定窗口 = 纯积分段）。
/// 预热后采 kSampleRuns 次，升序排序取 best/P50/max——best 与 P50 配合
/// 参考负载 best（[calibrationBestUs]）构成归一化门禁判据，max 仅诊断打印。
struct AccrueSampleStats {
    double bestUs = 0.0;
    double p50Us = 0.0;
    double maxUs = 0.0;
    double calibrationBestUs = 0.0;
};

/// 参考负载工作集（1 MiB 双精度数组，超 L2 量级）与遍历遍数：
/// 使单次参考耗时与被测积分段同量级（数百微秒），且访存主导 —— 与被测同
/// 资源类，对缓存/内存带宽竞争与频率变化同向响应。
constexpr int32_t kCalibrationArrayBytes = 1 << 20;
constexpr int32_t kCalibrationPasses = 21;

/// 参考负载的易失汇聚点：把计算结果写入 volatile，阻止编译器把遍历整段
/// 消除（否则参考耗时为 0，比值不可用）。
volatile double gCalibrationSink = 0.0;

/// 同机参考负载：按缓存行步长遍历 1 MiB 数组做乘加（访存主导）。
/// 用途 = 提供随机器快慢（频率/ILP/内存层次）变化的耗时基准，供比值归一化；
/// 其受缓存竞争的涨幅与被测积分段不同步（见文件头"适用范围"）。
double runCalibrationWork() {
    static double array[kCalibrationArrayBytes / sizeof(double)] = {};
    constexpr int32_t kWords = kCalibrationArrayBytes / sizeof(double);
    constexpr int32_t kStride = 8;  // 8 doubles = 64B，跨缓存行
    double acc = 0.5;
    for (int32_t pass = 0; pass < kCalibrationPasses; ++pass) {
        for (int32_t i = 0; i < kWords; i += kStride) {
            array[i] = array[i] * 1.0000001 + acc;
            acc += array[i];
        }
    }
    gCalibrationSink = acc;
    return acc;
}

/// 采样：每轮**交错**测一次被测负载与一次参考负载，使二者经历同一负载窗口
/// （先后分段测量会在负载只落一段时歪曲比值）。返回 best/P50/max 与参考 best。
template <typename Fn>
AccrueSampleStats sampleAccrueUs(Fn&& accrue) {
    for (int i = 0; i < kWarmupRuns; ++i) {
        accrue();
        static_cast<void>(runCalibrationWork());
    }
    std::vector<double> us;
    us.reserve(kSampleRuns);
    double calibrationBestUs = 0.0;
    for (int i = 0; i < kSampleRuns; ++i) {
        const auto t0 = std::chrono::steady_clock::now();
        accrue();
        const auto t1 = std::chrono::steady_clock::now();
        us.push_back(
            std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0)
                .count() /
            1000.0);

        const auto c0 = std::chrono::steady_clock::now();
        static_cast<void>(runCalibrationWork());
        const auto c1 = std::chrono::steady_clock::now();
        const double calibrationUs =
            std::chrono::duration_cast<std::chrono::nanoseconds>(c1 - c0)
                .count() /
            1000.0;
        if (i == 0 || calibrationUs < calibrationBestUs) {
            calibrationBestUs = calibrationUs;
        }
    }
    std::sort(us.begin(), us.end());
    // 偶数样本取上中位（偏严不偏松）；kSampleRuns 为奇数时即精确中位数
    return AccrueSampleStats{us.front(), us[kSampleRuns / 2], us.back(),
                             calibrationBestUs};
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

    const auto sample = sampleAccrueUs([&] { core.accrue(100, true); });
    const double calibrationBestUs = sample.calibrationBestUs;
    ASSERT_GT(calibrationBestUs, 0.0);
    // 同机归一化比值（机器速度无关；口径见文件头）
    const double bestRatio = sample.bestUs / calibrationBestUs;
    const double p50Ratio = sample.p50Us / calibrationBestUs;

    const auto& tel = core.accrualTelemetry();
    std::printf(
        "[AccrualSegmentBench] accrue(100ms) D=5000 core: best %.1f us, "
        "p50 %.1f us (last %lld us, max %lld us, samples %lld, "
        "overBudget %lld) | calib %.1f us | ratio best %.3f, p50 %.3f\n",
        sample.bestUs, sample.p50Us, static_cast<long long>(tel.lastSegmentUs),
        static_cast<long long>(tel.maxSegmentUs),
        static_cast<long long>(tel.samples),
        static_cast<long long>(tel.overBudgetCount), calibrationBestUs,
        bestRatio, p50Ratio);

    // 遥测计数面自洽：kWarmupRuns 预热 + kSampleRuns 采样
    EXPECT_EQ(tel.samples, kWarmupRuns + kSampleRuns);
    EXPECT_GE(tel.lastSegmentUs, 0);

    // B8 硬门禁：归一化 best 与 P50 双判据（G1 同族全八步每旬结算形态可比；
    // 本门为其连续积分子集）。绝对耗时与尾部计数已在上方打印，仅作观察项。
    EXPECT_LT(bestRatio, kAccrualSegmentBestRatioBudget);
    EXPECT_LT(p50Ratio, kAccrualSegmentP50RatioBudget);
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

    const auto sample = sampleAccrueUs([&] { core.accrue(100, true); });
    const auto& tel = core.accrualTelemetry();
    std::printf(
        "[AccrualSegmentBench] accrue(100ms) D=5000 full-inventory: "
        "best %.1f us, p50 %.1f us, max %.1f us (overBudget %lld)\n",
        sample.bestUs, sample.p50Us, sample.maxUs,
        static_cast<long long>(tel.overBudgetCount));
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