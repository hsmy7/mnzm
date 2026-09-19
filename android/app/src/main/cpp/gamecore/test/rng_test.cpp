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
// 64 位域 xorshift 后先截断 32 位再做 32 位循环旋转），seed=42。
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
    // 11 个快照分区（含 MISSION=8 + W4-A·A5 CHAT=10 + R4.4/B14 RESIDUAL=11；
    // AI_SECT_MIRROR=9 为通道型不进快照——新增快照分区时本断言同步 +1）
    EXPECT_EQ(states.size(), 11u);

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

// ============================================================
// R4.4/B14 — 残留执行器本地随机域（RESIDUAL=11）登记与播种
//
// Kotlin 侧该分区是**本地 PCG 分区**（isLocal=true ⇒ 零 per-roll JNI），
// C++ 侧不设生产消费点；此处锁死"登记 + 播种对齐"三件事：
//   1. id 上界随新分区上移（否则 JNI 合法分区守卫会静默拒绝 11）；
//   2. 播种式与 Kotlin `systemSeed + id` 逐位同式；
//   3. 参与快照导出/恢复（inSnapshot=true）。
// ============================================================

TEST(RngManagerTest, ResidualPartitionIdIsRegisteredAndWithinMaxId) {
    // 新分区 id 必须落在 kMaxPartitionId 上界内——上界是 JNI 入口的
    // 合法性守卫唯一权威（曾因写死成员导致 MISSION(8) 恒返回 0）
    EXPECT_EQ(static_cast<int32_t>(RngPartition::kResidual), 11);
    EXPECT_EQ(RngManager::kMaxPartitionId, 11);
    EXPECT_LE(static_cast<int32_t>(RngPartition::kResidual), RngManager::kMaxPartitionId);
    // 既有 id 逐位不变（红线 1：本批只追加新分区，不改旧分区）
    EXPECT_EQ(static_cast<int32_t>(RngPartition::kBattle), 0);
    EXPECT_EQ(static_cast<int32_t>(RngPartition::kChat), 10);
    EXPECT_EQ(static_cast<int32_t>(RngPartition::kAiSectMirror), 9);
}

TEST(RngManagerTest, ResidualPartitionSeededBySystemSeedPlusId) {
    // 与 Kotlin `DeterministicRng.fromSeed(systemSeed + partition.id)` 同式
    constexpr int64_t kSeed = 987654321LL;
    RngManager mgr;
    mgr.initSystemSeed(kSeed);
    auto expected = DeterministicRng::fromSeed(kSeed + 11);
    auto& actual = mgr.getRng(RngPartition::kResidual);
    for (int i = 0; i < 16; ++i) {
        EXPECT_EQ(expected.nextInt(), actual.nextInt());
    }
}

TEST(RngManagerTest, ResidualPartitionInSnapshotAndRestorable) {
    EXPECT_TRUE(RngManager::inSnapshot(RngPartition::kResidual));
    RngManager mgr;
    mgr.initSystemSeed(4242);
    for (int i = 0; i < 7; ++i) mgr.getRng(RngPartition::kResidual).nextInt(50);

    const auto states = mgr.exportStates();
    EXPECT_TRUE(states.count(11) == 1u);  // 11 号键在导出面内

    const auto before = mgr.getRng(RngPartition::kResidual).nextInt(50);
    mgr.restoreStates(states);
    EXPECT_EQ(before, mgr.getRng(RngPartition::kResidual).nextInt(50));
}

TEST(RngManagerTest, ResidualPartitionDoesNotDisturbOthers) {
    // 取用新分区不得扰动既有分区的抽取序（红线 1）
    RngManager a;
    RngManager b;
    a.initSystemSeed(2026);
    b.initSystemSeed(2026);
    for (int i = 0; i < 10; ++i) b.getRng(RngPartition::kResidual).nextInt();
    for (int i = 0; i < 12; ++i) {
        EXPECT_EQ(a.getRng(RngPartition::kBattle).nextInt(),
                  b.getRng(RngPartition::kBattle).nextInt());
        EXPECT_EQ(a.getRng(RngPartition::kSystem).nextInt(),
                  b.getRng(RngPartition::kSystem).nextInt());
        EXPECT_EQ(a.getRng(RngPartition::kChat).nextInt(),
                  b.getRng(RngPartition::kChat).nextInt());
    }
}

}  // namespace
}  // namespace gamecore::rng
