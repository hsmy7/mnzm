// ============================================================
// storage_bag_tx_test — 开袋抽签事务守护（ADR 随机源治理 阶段 1①）
//
// 守护目标：storage_bag_tx.h 与 Kotlin `generateStorageBagRewards` 逐位一致——
//   - 件数域 [5, 20]、种类域 [0, 7)
//   - **消费序逐位对齐**：先 nextInt(16) 再逐件 nextInt(7)
//   - 校验链先行（空 bagId / 品阶越界）→ **失败零抽取**（RNG 状态不动）
//   - 分区归属：只推进 EXPLORATION，其余分区快照逐位不变（RNG 红线）
//   - 双运行同参数 → 描述符序列逐位一致（确定性证明）
//   - 信封级：execute 通道 status/data 面（含 count 与 draws 数组）
// ============================================================

#include "gtest/gtest.h"

#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/storage_bag_tx.h"

namespace gamecore {
namespace {

namespace storage_bag_tx = gamecore::system::storage_bag_tx;

class StorageBagTxFixture : public ::testing::Test {
protected:
    void SetUp() override { core_ = makeCore(42); }

    std::unique_ptr<GameCore> makeCore(int64_t seed) {
        auto clock = std::make_unique<FixedClock>();
        auto logger = std::make_unique<ConsoleLogger>();
        auto core = std::make_unique<GameCore>(clock.get(), logger.get());
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = seed;
        core->initialize(config);
        // GameCore 只持裸指针（不持有）——fixture 必须保活注入对象
        ownedClocks_.push_back(std::move(clock));
        ownedLoggers_.push_back(std::move(logger));
        return core;
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    /// 全分区 RNG 快照（镜像 rngStates 段——exportStateJson 每次同步刷新）
    std::map<int32_t, int64_t> rngSnapshot() {
        return core_->rng().exportStates();
    }

    /// 直接调事务（绕过信封，验事务层语义）
    storage_bag_tx::OpenOutcome direct(const std::string& bagId, int32_t rarity) {
        return storage_bag_tx::openStorageBagTx(
            core_->rng().getRng(rng::RngPartition::kExploration), bagId, rarity);
    }

    std::vector<std::unique_ptr<FixedClock>> ownedClocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> ownedLoggers_;
    std::unique_ptr<GameCore> core_;
};

// ── 正常路径：件数域 / 种类域 / 描述符条数一致 ──────────────────────

TEST_F(StorageBagTxFixture, DrawCountInRangeAndMatchesDescriptorLength) {
    // 重复抽样：件数必须恒在 [5, 20]（每次抽签的形状约束）
    for (int i = 0; i < 200; ++i) {
        const auto out = direct("bag-1", 3);
        ASSERT_TRUE(out.ok) << out.message;
        EXPECT_GE(static_cast<int32_t>(out.draws.size()),
                  storage_bag_tx::kMinRewardCount);
        EXPECT_LE(static_cast<int32_t>(out.draws.size()),
                  storage_bag_tx::kMaxRewardCount);
    }
}

TEST_F(StorageBagTxFixture, DrawCountCoverageHitsBothBounds) {
    // 件数域覆盖性：2000 次抽样应同时命中下界与上界
    // （下界/上界概率各 1/16 ⇒ 漏采概率 (15/16)^2000 ≈ 0，不 flaky）
    int32_t minSeen = storage_bag_tx::kMaxRewardCount + 1;
    int32_t maxSeen = 0;
    for (int i = 0; i < 2000; ++i) {
        const auto out = direct("bag-1", 1);
        ASSERT_TRUE(out.ok);
        const int32_t n = static_cast<int32_t>(out.draws.size());
        minSeen = std::min(minSeen, n);
        maxSeen = std::max(maxSeen, n);
    }
    EXPECT_EQ(minSeen, storage_bag_tx::kMinRewardCount);
    EXPECT_EQ(maxSeen, storage_bag_tx::kMaxRewardCount);
}

TEST_F(StorageBagTxFixture, DrawKindsAlwaysWithinKindDomain) {
    for (int i = 0; i < 200; ++i) {
        const auto out = direct("bag-1", 1);
        ASSERT_TRUE(out.ok);
        for (const auto& d : out.draws) {
            EXPECT_GE(d.kind, 0);
            EXPECT_LT(d.kind, storage_bag_tx::kRewardKindCount);
        }
    }
}

TEST_F(StorageBagTxFixture, AllSevenKindsObservedAcrossManyOpens) {
    // 覆盖性：200 次 × 平均 12.5 件 ⇒ 期望每 kind 约 357 次；
    // 断言 7 种全部出现（最稀有 kind 漏采概率 (6/7)^2500 ≈ 0）
    std::vector<bool> seen(storage_bag_tx::kRewardKindCount, false);
    for (int i = 0; i < 200; ++i) {
        for (const auto& d : direct("bag-1", 6).draws) seen[d.kind] = true;
    }
    for (int32_t k = 0; k < storage_bag_tx::kRewardKindCount; ++k) {
        EXPECT_TRUE(seen[k]) << "kind " << k << " 从未出现——抽取映射可能被改动";
    }
}

// ── 校验链先行：失败零抽取（RNG 状态逐位不变）──────────────────────

TEST_F(StorageBagTxFixture, EmptyBagIdFailsWithZeroRngConsumption) {
    const auto before = rngSnapshot();
    const auto out = direct("", 3);
    EXPECT_FALSE(out.ok);
    EXPECT_EQ(out.errorType, "BagNotFound");
    EXPECT_TRUE(out.draws.empty());
    EXPECT_EQ(before, rngSnapshot()) << "校验失败路径不得消费任何分区 RNG";
}

TEST_F(StorageBagTxFixture, RarityOutOfRangeFailsWithZeroRngConsumption) {
    for (const int32_t bad : {0, -1, 7, 100}) {
        const auto before = rngSnapshot();
        const auto out = direct("bag-1", bad);
        EXPECT_FALSE(out.ok) << "rarity=" << bad << " 应被拒绝";
        EXPECT_EQ(out.errorType, "InvalidRarity");
        EXPECT_TRUE(out.draws.empty());
        EXPECT_EQ(before, rngSnapshot()) << "rarity=" << bad << " 失败路径消费了 RNG";
    }
}

TEST_F(StorageBagTxFixture, BoundaryRaritiesAccepted) {
    auto core = makeCore(11);
    EXPECT_TRUE(storage_bag_tx::openStorageBagTx(
        core->rng().getRng(rng::RngPartition::kExploration), "bag-1", 1).ok);
    EXPECT_TRUE(storage_bag_tx::openStorageBagTx(
        core->rng().getRng(rng::RngPartition::kExploration), "bag-1", 6).ok);
}

// ── RNG 红线：只推进 EXPLORATION，其余分区逐位不变 ─────────────────

TEST_F(StorageBagTxFixture, OnlyExplorationPartitionAdvances) {
    const auto before = rngSnapshot();
    const auto out = direct("bag-1", 4);
    ASSERT_TRUE(out.ok);
    const auto after = rngSnapshot();

    for (const auto& [partitionId, state] : before) {
        if (partitionId == static_cast<int32_t>(rng::RngPartition::kExploration)) {
            EXPECT_NE(state, after.at(partitionId))
                << "EXPLORATION 分区应被推进（抽签即消费）";
        } else {
            EXPECT_EQ(state, after.at(partitionId))
                << "分区 " << partitionId << " 被意外消费——RNG 分区归属违规";
        }
    }
}

// ── 确定性：双运行同种子 → 描述符序列逐位一致 ──────────────────────

TEST_F(StorageBagTxFixture, DoubleRunProducesBitIdenticalDrawSequence) {
    auto coreA = makeCore(20260914);
    auto coreB = makeCore(20260914);

    const auto a = storage_bag_tx::openStorageBagTx(
        coreA->rng().getRng(rng::RngPartition::kExploration), "bag-1", 3);
    const auto b = storage_bag_tx::openStorageBagTx(
        coreB->rng().getRng(rng::RngPartition::kExploration), "bag-1", 3);

    ASSERT_TRUE(a.ok);
    ASSERT_TRUE(b.ok);
    ASSERT_EQ(a.draws.size(), b.draws.size());
    for (size_t i = 0; i < a.draws.size(); ++i) {
        EXPECT_EQ(a.draws[i].kind, b.draws[i].kind) << "第 " << i << " 件不一致";
    }
    // 分区终态亦须一致（消费序相同的充分证据）
    EXPECT_EQ(coreA->rng().getRng(rng::RngPartition::kExploration).snapshot(),
              coreB->rng().getRng(rng::RngPartition::kExploration).snapshot());
}

TEST_F(StorageBagTxFixture, SamePartitionStateProducesSameSequence) {
    // 同一起点的两次抽签必须同序（同分区同 state ⇒ 同描述符序列）
    auto coreA = makeCore(7);
    auto coreB = makeCore(7);
    const auto a = storage_bag_tx::openStorageBagTx(
        coreA->rng().getRng(rng::RngPartition::kExploration), "bag-x", 2);
    const auto b = storage_bag_tx::openStorageBagTx(
        coreB->rng().getRng(rng::RngPartition::kExploration), "bag-x", 2);
    ASSERT_EQ(a.draws.size(), b.draws.size());
    for (size_t i = 0; i < a.draws.size(); ++i) {
        EXPECT_EQ(a.draws[i].kind, b.draws[i].kind);
    }
}

// ── 信封面：execute 通道 status/data ──────────────────────────────

TEST_F(StorageBagTxFixture, DispatchEnvelopeHappyPath) {
    nlohmann::json params;
    params["bagId"] = "bag-envelope";
    params["rarity"] = 3;
    const auto result = exec(action::STORAGE_BAG_OPEN_TX, params);

    ASSERT_TRUE(result.contains("status"));
    EXPECT_EQ(result.at("status").get<std::string>(), "success");
    ASSERT_TRUE(result.contains("data"));
    const auto& data = result.at("data");
    EXPECT_TRUE(data.contains("count"));
    EXPECT_TRUE(data.contains("draws"));
    EXPECT_EQ(data.at("count").get<int32_t>(),
              static_cast<int32_t>(data.at("draws").size()));
    EXPECT_GE(data.at("count").get<int32_t>(), storage_bag_tx::kMinRewardCount);
    EXPECT_LE(data.at("count").get<int32_t>(), storage_bag_tx::kMaxRewardCount);
    for (const auto& d : data.at("draws")) {
        EXPECT_TRUE(d.contains("kind"));
        EXPECT_GE(d.at("kind").get<int32_t>(), 0);
        EXPECT_LT(d.at("kind").get<int32_t>(), storage_bag_tx::kRewardKindCount);
    }
}

TEST_F(StorageBagTxFixture, DispatchEnvelopeFailurePaths) {
    nlohmann::json emptyId;
    emptyId["bagId"] = "";
    emptyId["rarity"] = 3;
    const auto missBag = exec(action::STORAGE_BAG_OPEN_TX, emptyId);
    EXPECT_EQ(missBag.at("status").get<std::string>(), "failure");
    EXPECT_EQ(missBag.at("code").get<std::string>(), "BagNotFound");

    nlohmann::json badRarity;
    badRarity["bagId"] = "bag-1";
    badRarity["rarity"] = 9;
    const auto bad = exec(action::STORAGE_BAG_OPEN_TX, badRarity);
    EXPECT_EQ(bad.at("status").get<std::string>(), "failure");
    EXPECT_EQ(bad.at("code").get<std::string>(), "InvalidRarity");
}

TEST_F(StorageBagTxFixture, DispatchFailureIsZeroRngConsumption) {
    // 失败路径零抽取是"双臂同序"的前提：Kotlin 回退臂会重执行原路径，
    // 若 native 已消费过 EXPLORATION，回退臂的抽取起点将偏移 ⇒ 双实现分叉
    const auto before = rngSnapshot();
    nlohmann::json bad;
    bad["bagId"] = "bag-1";
    bad["rarity"] = 0;
    (void)exec(action::STORAGE_BAG_OPEN_TX, bad);
    EXPECT_EQ(before, rngSnapshot());
}

}  // namespace
}  // namespace gamecore
