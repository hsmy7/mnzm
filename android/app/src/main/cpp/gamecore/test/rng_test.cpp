#include <gtest/gtest.h>

#include <cmath>

#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/rng/rng_manager.h"

namespace gamecore::rng {
namespace {

// ============================================================
// 黄金序列测试 — 跨语言确定性守护
//
// 黄金值来源：Java 复刻 Kotlin DeterministicRng（PCG-XSH-RR 64→32，
// 先截断 32 位再旋转的修复后语义），seed=42。
// 若 Kotlin 侧算法被修改，C++ 单测仍守住 PCG 语义；
// 与真实 Kotlin 引擎的权威对拍由差分对拍框架（JUnit + JNI）负责。
// ============================================================

TEST(DeterministicRngTest, GoldenSequenceNextInt) {
    auto rng = DeterministicRng::fromSeed(42);
    const int32_t kExpected[] = {
        1062842430, 32784007, 126953611, -787045584, 575377284,
        1948502678, 767380387, -361637985, 1278922247, 827613313};
    for (const auto expected : kExpected) {
        EXPECT_EQ(expected, rng.nextInt());
    }
}

TEST(DeterministicRngTest, GoldenSequenceNextIntBound) {
    auto rng = DeterministicRng::fromSeed(42);
    const int32_t kExpected[] = {0, 81, 13, 45, 91, 19, 80, 37, 95, 78};
    for (const auto expected : kExpected) {
        EXPECT_EQ(expected, rng.nextInt(100));
    }
}

TEST(DeterministicRngTest, GoldenSequenceNextIntSmallBound) {
    auto rng = DeterministicRng::fromSeed(42);
    const int32_t kExpected[] = {0, 0, 0, 3, 1, 6, 2, 1, 3, 0};
    for (const auto expected : kExpected) {
        EXPECT_EQ(expected, rng.nextInt(7));
    }
}

TEST(DeterministicRngTest, GoldenSequenceNextDouble) {
    auto rng = DeterministicRng::fromSeed(42);
    const double kExpected[] = {
        0.49492457415908575, 0.015266242902725935, 0.059117381926625970,
        0.63350334018468860, 0.26793092675507070};
    for (const auto expected : kExpected) {
        EXPECT_DOUBLE_EQ(expected, rng.nextDouble());
    }
}

TEST(DeterministicRngTest, GoldenSequenceNextLongBound) {
    auto rng = DeterministicRng::fromSeed(42);
    const int64_t kExpected[] = {42430, 84007, 53611, 21712, 77284};
    for (const auto expected : kExpected) {
        EXPECT_EQ(expected, rng.nextLong(100000));
    }
}

TEST(DeterministicRngTest, GoldenSequenceNextLongMax) {
    auto rng = DeterministicRng::fromSeed(42);
    // Kotlin: nextLong(Long.MAX_VALUE) = nextInt() & Long.MAX_VALUE（正数不变）
    const int64_t kExpected[] = {1062842430, 32784007, 126953611};
    for (const auto expected : kExpected) {
        EXPECT_EQ(expected, rng.nextLong());
    }
}

TEST(DeterministicRngTest, GoldenStateAfterConsumption) {
    auto rng = DeterministicRng::fromSeed(42);
    for (int i = 0; i < 10; ++i) rng.nextInt();
    // Java 复刻：state 的 int64 位级值
    EXPECT_EQ(static_cast<int64_t>(-345184097085408952LL), rng.snapshot());
}

// ============================================================
// 语义测试（翻译自 Kotlin DeterministicRngTest）
// ============================================================

TEST(DeterministicRngTest, SameSeedProducesSameSequence) {
    auto r1 = DeterministicRng::fromSeed(42);
    auto r2 = DeterministicRng::fromSeed(42);
    for (int i = 0; i < 20; ++i) {
        EXPECT_EQ(r1.nextInt(100), r2.nextInt(100));
    }
}

TEST(DeterministicRngTest, DifferentSeedProducesDifferentSequence) {
    auto r1 = DeterministicRng::fromSeed(42);
    auto r2 = DeterministicRng::fromSeed(99);
    bool allSame = true;
    for (int i = 0; i < 10; ++i) {
        if (r1.nextInt(100) != r2.nextInt(100)) {
            allSame = false;
            break;
        }
    }
    EXPECT_FALSE(allSame);
}

TEST(DeterministicRngTest, SnapshotRestorePreservesState) {
    auto rng = DeterministicRng::fromSeed(42);
    for (int i = 0; i < 10; ++i) rng.nextInt(100);
    const auto snapshot = rng.snapshot();
    const auto valBefore = rng.nextInt(100);
    rng.restore(snapshot);
    const auto valAfter = rng.nextInt(100);
    EXPECT_EQ(valBefore, valAfter);
}

TEST(DeterministicRngTest, NextIntWithinBounds) {
    auto rng = DeterministicRng::fromSeed(42);
    for (int i = 0; i < 1000; ++i) {
        const auto v = rng.nextInt(10);
        EXPECT_GE(v, 0);
        EXPECT_LT(v, 10);
    }
}

TEST(DeterministicRngTest, NextDoubleWithin01) {
    auto rng = DeterministicRng::fromSeed(42);
    for (int i = 0; i < 1000; ++i) {
        const auto v = rng.nextDouble();
        EXPECT_GE(v, 0.0);
        EXPECT_LT(v, 1.0);
    }
}

TEST(DeterministicRngTest, NextGaussianSameSeedSameSequence) {
    auto r1 = DeterministicRng::fromSeed(42);
    auto r2 = DeterministicRng::fromSeed(42);
    for (int i = 0; i < 20; ++i) {
        EXPECT_DOUBLE_EQ(r1.nextGaussian(), r2.nextGaussian());
    }
}

TEST(DeterministicRngTest, NextGaussianFinite) {
    auto rng = DeterministicRng::fromSeed(42);
    for (int i = 0; i < 1000; ++i) {
        EXPECT_TRUE(std::isfinite(rng.nextGaussian()));
    }
}

TEST(DeterministicRngTest, NextGaussianMeanParameters) {
    auto rng = DeterministicRng::fromSeed(42);
    double sum = 0.0;
    constexpr int kCount = 1000;
    for (int i = 0; i < kCount; ++i) sum += rng.nextGaussian(50.0, 10.0);
    const double mean = sum / kCount;
    EXPECT_GE(mean, 45.0);
    EXPECT_LE(mean, 55.0);
}

// ============================================================
// 分区管理器测试（翻译自 Kotlin GameRngManagerTest 语义）
// ============================================================

TEST(RngManagerTest, PartitionIsolation) {
    RngManager mgr;
    mgr.initSystemSeed(42);
    auto& battle = mgr.getRng(RngPartition::kBattle);
    auto& exploration = mgr.getRng(RngPartition::kExploration);
    const auto b1 = battle.nextInt(100);
    const auto e1 = exploration.nextInt(100);
    // 不同分区独立推进，序列不同（分区种子偏移）
    EXPECT_NE(b1, e1);
}

TEST(RngManagerTest, ExportRestoreRoundTrip) {
    RngManager mgr;
    mgr.initSystemSeed(42);
    for (int i = 0; i < 5; ++i) mgr.getRng(RngPartition::kSystem).nextInt(100);

    const auto states = mgr.exportStates();
    EXPECT_EQ(states.size(), 9u);  // 9 个分区（批 11-4 新增 MISSION=8）

    const auto valBefore = mgr.getRng(RngPartition::kSystem).nextInt(100);
    mgr.restoreStates(states);
    const auto valAfter = mgr.getRng(RngPartition::kSystem).nextInt(100);
    EXPECT_EQ(valBefore, valAfter);
}

TEST(RngManagerTest, RestoreIgnoresUnknownPartition) {
    RngManager mgr;
    mgr.initSystemSeed(42);
    const auto snapshotBefore = mgr.getRng(RngPartition::kBattle).snapshot();
    std::map<int32_t, int64_t> states{{999, 12345L}};  // 未知分区
    mgr.restoreStates(states);                          // 不应崩溃
    // 未知分区被跳过，BATTLE 状态不变
    EXPECT_EQ(snapshotBefore, mgr.getRng(RngPartition::kBattle).snapshot());
}

}  // namespace
}  // namespace gamecore::rng
