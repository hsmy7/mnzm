// ============================================================
// road_tx_test.cpp — 道路放置/拆除事务黄金用例（batch-07 下沉）
//
// 守护目标：placeRoadTx / removeRoadTx 校验链判定序（与 Kotlin
// RoadFacadeImpl 逐字对齐）、灵石精确扣减、重复/不存在防御零写入、
// 邻域掩码重算正确性、零 RNG 审计（签名级 + 双运行逐位一致 + 分发面
// 全分区快照差分）、与 road_compositor 合成器联动一致性（roads 变更后
// compose 输出含新边）。
//
// 占位集合契约：placeRoadTx 的 occupiedCells 由 Kotlin 组装（本宗建筑占地
// ∪ FixedSectGateway.blockedCells——C++ GridBuildingData 无 sectId 字段，
// 宗门过滤不可在 C++ 复现，见 road_tx.h 头注释）；本文件以手工集合覆盖
// 判定原语，宗门过滤语义由 Kotlin 侧 GameEngineRoadOpsTest 守护。
// ============================================================
#include <gtest/gtest.h>

#include <cstdint>
#include <vector>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/map/road_compositor.h"
#include "gamecore/map/road_system.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/road_tx.h"

namespace {

using gamecore::map::kDirDown;
using gamecore::map::kDirLeft;
using gamecore::map::kDirRight;
using gamecore::map::kDirUp;
using gamecore::map::RoadGrid;
using gamecore::map::RoadSprite;
using gamecore::state::GameData;
using gamecore::state::RoadData;

// 与 Kotlin GameConfig.SectMap / GameConfig.Road 同值的测试常量
constexpr int32_t kWidth = 128;
constexpr int32_t kHeight = 128;
constexpr int32_t kBorder = 3;
constexpr int64_t kCost = 20;

/// 空状态（指定灵石余额）
GameData newState(int64_t spiritStones = 1000) {
    GameData gd;
    gd.spiritStones = spiritStones;
    return gd;
}

/// 按掩码产出 compose 操作中指定 sprite 的数量（联动断言辅助）
int spriteCount(int mask, RoadSprite sprite) {
    gamecore::map::RoadDrawOp ops[gamecore::map::kMaxRoadDrawOpsPerTile];
    const int n = gamecore::map::emitRoadDrawOps(mask, 48, ops);
    int count = 0;
    for (int i = 0; i < n; ++i) {
        if (ops[i].sprite == sprite) ++count;
    }
    return count;
}

// ── 放置 happy + 精确扣减 ──────────────────────────────────────

TEST(RoadTxTest, PlaceHappyDeductsExactCost) {
    auto gd = newState(1000);
    const auto r = gamecore::system::placeRoadTx(gd, 20, 20, kWidth, kHeight,
                                                 kBorder, kCost, {});
    ASSERT_TRUE(r.ok);
    EXPECT_STREQ(r.errorType, "");
    ASSERT_EQ(gd.roads.size(), 1u);
    EXPECT_EQ(gd.roads[0].gridX, 20);
    EXPECT_EQ(gd.roads[0].gridY, 20);
    EXPECT_EQ(gd.roads[0].bitMask, 0);
    EXPECT_EQ(gd.roads[0].roadType, "SINGLE");
    EXPECT_EQ(gd.spiritStones, 1000 - kCost);
    EXPECT_EQ(r.spiritStonesAfter, 1000 - kCost);
}

TEST(RoadTxTest, PlaceAdjacentRecomputesNeighborhood) {
    auto gd = newState(1000);
    ASSERT_TRUE(gamecore::system::placeRoadTx(gd, 10, 10, kWidth, kHeight,
                                              kBorder, kCost, {}).ok);
    ASSERT_TRUE(gamecore::system::placeRoadTx(gd, 11, 10, kWidth, kHeight,
                                              kBorder, kCost, {}).ok);
    ASSERT_EQ(gd.roads.size(), 2u);
    EXPECT_EQ(gd.spiritStones, 1000 - 2 * kCost);
    // (10,10) 右邻 (11,10)=路 → mask=RIGHT；形态按死路归为横向直路
    const RoadData* left = nullptr;
    const RoadData* right = nullptr;
    for (const auto& r : gd.roads) {
        if (r.gridX == 10) left = &r;
        if (r.gridX == 11) right = &r;
    }
    ASSERT_NE(left, nullptr);
    ASSERT_NE(right, nullptr);
    EXPECT_EQ(left->bitMask, kDirRight);
    EXPECT_EQ(left->roadType, "HORIZONTAL");
    EXPECT_EQ(right->bitMask, kDirLeft);
    EXPECT_EQ(right->roadType, "HORIZONTAL");
}

TEST(RoadTxTest, PlaceCornerRecomputesBothTypes) {
    auto gd = newState(1000);
    // L 型三连：(10,10) (11,10) (11,11) → (11,10) 为左下转角（左邻+下邻）
    ASSERT_TRUE(gamecore::system::placeRoadTx(gd, 10, 10, kWidth, kHeight,
                                              kBorder, kCost, {}).ok);
    ASSERT_TRUE(gamecore::system::placeRoadTx(gd, 11, 10, kWidth, kHeight,
                                              kBorder, kCost, {}).ok);
    ASSERT_TRUE(gamecore::system::placeRoadTx(gd, 11, 11, kWidth, kHeight,
                                              kBorder, kCost, {}).ok);
    for (const auto& r : gd.roads) {
        if (r.gridX == 11 && r.gridY == 10) {
            EXPECT_EQ(r.bitMask, kDirLeft | kDirDown);
            EXPECT_EQ(r.roadType, "CORNER_BOTTOM_LEFT");
        }
    }
}

// ── 校验链失败臂（判定序与 Kotlin 逐字对齐） ───────────────────

TEST(RoadTxTest, PlaceRejectsOutsideBorderRing) {
    auto gd = newState(1000);
    const auto r = gamecore::system::placeRoadTx(gd, 1, 1, kWidth, kHeight,
                                                 kBorder, kCost, {});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "OutOfBounds");
    EXPECT_TRUE(gd.roads.empty());
    EXPECT_EQ(gd.spiritStones, 1000);
}

TEST(RoadTxTest, PlaceRejectsOccupiedCellZeroWrite) {
    auto gd = newState(1000);
    // 占用集合由 Kotlin 组装传入（此处模拟：两格环内占位——须选可建环内格，
    // 界检先于占位判定；FixedSectGateway 门楼格位于树环内，实际只会触达界检臂）
    const std::vector<int64_t> occupied = {
        RoadGrid::packCell(30, 30),
        RoadGrid::packCell(40, 40),
    };
    for (const auto& cell : occupied) {
        const int32_t x = static_cast<int32_t>(cell >> 32);
        const int32_t y = static_cast<int32_t>(cell & 0xFFFFFFFFll);
        const auto r = gamecore::system::placeRoadTx(gd, x, y, kWidth, kHeight,
                                                     kBorder, kCost, occupied);
        EXPECT_FALSE(r.ok);
        EXPECT_STREQ(r.errorType, "Occupied");
    }
    EXPECT_TRUE(gd.roads.empty());
    EXPECT_EQ(gd.spiritStones, 1000);
}

TEST(RoadTxTest, PlaceRejectsDuplicateZeroWrite) {
    auto gd = newState(1000);
    ASSERT_TRUE(gamecore::system::placeRoadTx(gd, 20, 20, kWidth, kHeight,
                                              kBorder, kCost, {}).ok);
    const auto r = gamecore::system::placeRoadTx(gd, 20, 20, kWidth, kHeight,
                                                 kBorder, kCost, {});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "AlreadyRoad");
    EXPECT_EQ(gd.roads.size(), 1u);
    EXPECT_EQ(gd.spiritStones, 1000 - kCost);
}

TEST(RoadTxTest, PlaceRejectsInsufficientStonesZeroWrite) {
    auto gd = newState(kCost - 1);
    const auto r = gamecore::system::placeRoadTx(gd, 20, 20, kWidth, kHeight,
                                                 kBorder, kCost, {});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "Insufficient");
    EXPECT_TRUE(gd.roads.empty());
    EXPECT_EQ(gd.spiritStones, kCost - 1);
}

// ── 拆除 ──────────────────────────────────────────────────────

TEST(RoadTxTest, RemoveHappyNoRefundRecomputesNeighbors) {
    auto gd = newState(1000);
    ASSERT_TRUE(gamecore::system::placeRoadTx(gd, 20, 20, kWidth, kHeight,
                                              kBorder, kCost, {}).ok);
    ASSERT_TRUE(gamecore::system::placeRoadTx(gd, 21, 20, kWidth, kHeight,
                                              kBorder, kCost, {}).ok);
    ASSERT_EQ(gd.spiritStones, 1000 - 2 * kCost);

    const auto r = gamecore::system::removeRoadTx(gd, 21, 20, kWidth, kHeight);
    ASSERT_TRUE(r.ok);
    // 灵石不返还
    EXPECT_EQ(gd.spiritStones, 1000 - 2 * kCost);
    ASSERT_EQ(gd.roads.size(), 1u);
    EXPECT_EQ(gd.roads[0].gridX, 20);
    // 邻居掩码回落为孤格
    EXPECT_EQ(gd.roads[0].bitMask, 0);
    EXPECT_EQ(gd.roads[0].roadType, "SINGLE");
}

TEST(RoadTxTest, RemoveRejectsMissingRoadZeroWrite) {
    auto gd = newState(1000);
    ASSERT_TRUE(gamecore::system::placeRoadTx(gd, 20, 20, kWidth, kHeight,
                                              kBorder, kCost, {}).ok);
    const auto r = gamecore::system::removeRoadTx(gd, 21, 20, kWidth, kHeight);
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "NotFound");
    EXPECT_EQ(gd.roads.size(), 1u);
}

TEST(RoadTxTest, RemoveKeepsNonAdjacentNeighborMasks) {
    auto gd = newState(1000);
    // 横排三格：(10,10)(11,10)(12,10)，删中格 → 两端掩码各自归零
    for (const int32_t x : {10, 11, 12}) {
        ASSERT_TRUE(gamecore::system::placeRoadTx(gd, x, 10, kWidth, kHeight,
                                                  kBorder, kCost, {}).ok);
    }
    ASSERT_TRUE(gamecore::system::removeRoadTx(gd, 11, 10, kWidth, kHeight).ok);
    ASSERT_EQ(gd.roads.size(), 2u);
    for (const auto& r : gd.roads) {
        EXPECT_EQ(r.bitMask, 0);
        EXPECT_EQ(r.roadType, "SINGLE");
    }
}

// ── 零 RNG 审计 ───────────────────────────────────────────────

TEST(RoadTxTest, ZeroRngDeterministicReplay) {
    // 签名级：placeRoadTx/removeRoadTx 均不接受 RngManager/种子参数；
    // 行为级：同初始状态两次事务序列，终态 roads/spiritStones 逐位一致。
    auto build = [] {
        auto gd = newState(100);
        (void)gamecore::system::placeRoadTx(gd, 10, 10, kWidth, kHeight, kBorder, kCost, {});
        (void)gamecore::system::placeRoadTx(gd, 11, 10, kWidth, kHeight, kBorder, kCost, {});
        (void)gamecore::system::placeRoadTx(gd, 11, 11, kWidth, kHeight, kBorder, kCost, {});
        (void)gamecore::system::removeRoadTx(gd, 10, 10, kWidth, kHeight);
        return gd;
    };
    const auto a = build();
    const auto b = build();
    ASSERT_EQ(a.roads.size(), b.roads.size());
    for (std::size_t i = 0; i < a.roads.size(); ++i) {
        EXPECT_EQ(a.roads[i].gridX, b.roads[i].gridX);
        EXPECT_EQ(a.roads[i].gridY, b.roads[i].gridY);
        EXPECT_EQ(a.roads[i].bitMask, b.roads[i].bitMask);
        EXPECT_EQ(a.roads[i].roadType, b.roads[i].roadType);
    }
    EXPECT_EQ(a.spiritStones, b.spiritStones);
}

// ── 与 road_compositor 合成器联动一致性 ────────────────────────

TEST(RoadTxTest, ComposeReflectsNewEdgeAfterTx) {
    // 孤格（mask=0）：描边在左右轴 → compose 输出 EDGE_V×4、无 EDGE_H
    EXPECT_EQ(spriteCount(0, RoadSprite::BODY), 1);
    EXPECT_EQ(spriteCount(0, RoadSprite::EDGE_V), 4);
    EXPECT_EQ(spriteCount(0, RoadSprite::EDGE_H), 0);

    auto gd = newState(1000);
    ASSERT_TRUE(gamecore::system::placeRoadTx(gd, 20, 20, kWidth, kHeight,
                                              kBorder, kCost, {}).ok);
    ASSERT_TRUE(gamecore::system::placeRoadTx(gd, 21, 20, kWidth, kHeight,
                                              kBorder, kCost, {}).ok);
    // 事务后 (20,20) 掩码含 RIGHT → 描边退右轴，上下新边（EDGE_H）进入 compose 输出
    int mask20 = -1;
    for (const auto& r : gd.roads) {
        if (r.gridX == 20 && r.gridY == 20) mask20 = r.bitMask;
    }
    ASSERT_EQ(mask20, kDirRight);
    EXPECT_EQ(spriteCount(mask20, RoadSprite::BODY), 1);
    EXPECT_EQ(spriteCount(mask20, RoadSprite::EDGE_V), 0);
    EXPECT_EQ(spriteCount(mask20, RoadSprite::EDGE_H), 4);
    // 描边掩码不再含右轴（新邻接方向转为道路内部）
    EXPECT_EQ((gamecore::map::roadBorderMask(mask20) & kDirRight), 0);
}

// ── 分发面（GameCore::execute 信封） ───────────────────────────

class RoadTxFixture : public ::testing::Test {
protected:
    void SetUp() override {
        core_ = std::make_unique<gamecore::GameCore>(&clock_, &logger_);
        gamecore::GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(
            actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    gamecore::FixedClock clock_;
    gamecore::ConsoleLogger logger_;
    std::unique_ptr<gamecore::GameCore> core_;
};

TEST_F(RoadTxFixture, DispatchPlaceSuccessAndMirrorFields) {
    auto& gd = core_->state().gameData;
    gd.spiritStones = 1000;
    const auto r = exec(gamecore::action::ROAD_PLACE,
                        {{"gridX", 20}, {"gridY", 20},
                         {"width", kWidth}, {"height", kHeight},
                         {"border", kBorder}, {"cost", kCost},
                         {"occupiedCells", std::vector<int64_t>{}}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("placed"), true);
    EXPECT_EQ(r.at("data").at("spiritStones").get<int64_t>(), 1000 - kCost);
    // 状态真相在 C++（镜像通道经 dirty diff 回读 gameData.roads/spiritStones）
    ASSERT_EQ(gd.roads.size(), 1u);
    EXPECT_EQ(gd.roads[0].roadType, "SINGLE");
}

TEST_F(RoadTxFixture, DispatchPlaceFailureEnvelopeZeroWrite) {
    auto& gd = core_->state().gameData;
    gd.spiritStones = kCost - 1;
    const auto r = exec(gamecore::action::ROAD_PLACE,
                        {{"gridX", 20}, {"gridY", 20},
                         {"width", kWidth}, {"height", kHeight},
                         {"border", kBorder}, {"cost", kCost},
                         {"occupiedCells", std::vector<int64_t>{}}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "Insufficient");
    // 失败零写入——Kotlin 回退臂重执行校验链产出 Blocked
    EXPECT_TRUE(gd.roads.empty());
    EXPECT_EQ(gd.spiritStones, kCost - 1);
}

TEST_F(RoadTxFixture, DispatchRemoveHappyAndNotFound) {
    auto& gd = core_->state().gameData;
    gd.spiritStones = 1000;
    const nlohmann::json placeParams = {
        {"gridX", 20}, {"gridY", 20},
        {"width", kWidth}, {"height", kHeight},
        {"border", kBorder}, {"cost", kCost},
        {"occupiedCells", std::vector<int64_t>{}}};
    ASSERT_EQ(exec(gamecore::action::ROAD_PLACE, placeParams).at("status"),
              "success");

    const auto removed = exec(gamecore::action::ROAD_REMOVE,
                              {{"gridX", 20}, {"gridY", 20},
                               {"width", kWidth}, {"height", kHeight}});
    ASSERT_EQ(removed.at("status"), "success");
    EXPECT_EQ(removed.at("data").at("removed"), true);
    EXPECT_TRUE(gd.roads.empty());

    const auto again = exec(gamecore::action::ROAD_REMOVE,
                            {{"gridX", 20}, {"gridY", 20},
                             {"width", kWidth}, {"height", kHeight}});
    ASSERT_EQ(again.at("status"), "failure");
    EXPECT_EQ(again.at("code"), "NotFound");
}

TEST_F(RoadTxFixture, DispatchRoadTxConsumesZeroRng) {
    // 全分区 RNG 快照差分：事务前后逐分区状态不变（抽取集为空集）
    const auto before = core_->rng().exportStates();
    auto& gd = core_->state().gameData;
    gd.spiritStones = 1000;
    ASSERT_EQ(exec(gamecore::action::ROAD_PLACE,
                   {{"gridX", 20}, {"gridY", 20},
                    {"width", kWidth}, {"height", kHeight},
                    {"border", kBorder}, {"cost", kCost},
                    {"occupiedCells", std::vector<int64_t>{}}}).at("status"),
              "success");
    ASSERT_EQ(exec(gamecore::action::ROAD_REMOVE,
                   {{"gridX", 20}, {"gridY", 20},
                    {"width", kWidth}, {"height", kHeight}}).at("status"),
              "success");
    EXPECT_EQ(core_->rng().exportStates(), before);
}

}  // namespace
