// ============================================================
// building_tx_test.cpp — 建筑放置/迁移/升级/拆除事务黄金用例
//（batch-06 下沉）
//
// 守护目标：placeBuildingTx / moveBuildingTx / upgradeBuildingTx /
// upgradeBuildingsTx / removeBuildingsTx 校验链判定序（与 Kotlin
// BuildingDelegate.doPlaceBuilding / moveBuildingDirect /
// BuildingUpgradeCalculator / removeBuildingsInternal 逐字对齐）、
// 灵石精确扣减（place/upgrade 低阶直扣；remove 返还走 wallet 记年度账）、
// 限建/重叠/越界/不存在防御零写入、迁移原子性（计数不变、邻座无扰动）、
// 批量升级稳定序与「升级中间态」增量互斥、零 RNG 审计（签名级 + 双运行
// 逐位一致 + 分发面全分区快照差分）。
//
// 作用域契约：sectScopedIds（同宗建筑 instanceId 集）与占位几何/限建标志/
// 造价/counterKey 由 Kotlin 组装传入（C++ GridBuildingData 无 sectId 字段，
// 宗门过滤不可在 C++ 复现，见 building_tx.h 头注释 §2.36 同偏差登记）；
// 本文件以手工集合覆盖判定原语。
// ============================================================
#include <gtest/gtest.h>

#include <cstdint>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/building_tx.h"

namespace {

using gamecore::state::GameData;
using gamecore::state::GridBuildingData;
using gamecore::state::WorldSect;
namespace btx = gamecore::system::building_tx;

// 与 Kotlin GameConfig.SectMap 同值的测试几何（门楼矩形移至可建环中央——
// 几何为参数化输入，专门覆盖门楼禁建格臂；生产值由 Kotlin 传入）
constexpr int32_t kWidth = 128;
constexpr int32_t kHeight = 128;
constexpr int32_t kBorder = 3;

btx::PlaceGeom testGeom(int32_t gateX = 61, int32_t gateY = 60) {
    btx::PlaceGeom g;
    g.border = kBorder;
    g.worldWidth = kWidth;
    g.worldHeight = kHeight;
    g.gateX = gateX;
    g.gateY = gateY;
    g.gateWidth = 6;
    g.gateHeight = 2;
    return g;
}

GameData newState(int64_t spiritStones = 10000) {
    GameData gd;
    gd.spiritStones = spiritStones;
    return gd;
}

/// 玩家宗门（等级可指定——宗门等级门槛臂）
GameData newStateWithSect(int64_t spiritStones, int32_t level) {
    GameData gd = newState(spiritStones);
    WorldSect sect;
    sect.id = "sect-player";
    sect.isPlayerSect = true;
    sect.level = level;
    gd.worldMapSects.push_back(sect);
    return gd;
}

GridBuildingData building(const std::string& instanceId, int32_t x, int32_t y,
                          int32_t w = 2, int32_t h = 3,
                          const std::string& key = "single_residence",
                          const std::string& name = "单人住所") {
    GridBuildingData b;
    b.buildingId = key;
    b.displayName = name;
    b.gridX = x;
    b.gridY = y;
    b.width = w;
    b.height = h;
    b.instanceId = instanceId;
    return b;
}

// ── 放置 happy + 字段逐项 + 精确扣减 + 引导计数 ─────────────────

TEST(BuildingTxTest, PlaceHappyWritesAllFieldsAndCounter) {
    auto gd = newState(10000);
    const auto b = building("inst-1", 20, 20);
    const auto r = btx::placeBuildingTx(gd, b, /*cost=*/1200,
                                        /*requiredSectLevel=*/0,
                                        /*unlimitedBuild=*/true,
                                        /*globallyUnique=*/false,
                                        "buildingBuilt_单人住所", testGeom(),
                                        {});
    ASSERT_TRUE(r.ok);
    EXPECT_STREQ(r.errorType, "");
    ASSERT_EQ(gd.placedBuildings.size(), 1u);
    EXPECT_EQ(gd.placedBuildings[0].buildingId, "single_residence");
    EXPECT_EQ(gd.placedBuildings[0].displayName, "单人住所");
    EXPECT_EQ(gd.placedBuildings[0].gridX, 20);
    EXPECT_EQ(gd.placedBuildings[0].gridY, 20);
    EXPECT_EQ(gd.placedBuildings[0].width, 2);
    EXPECT_EQ(gd.placedBuildings[0].height, 3);
    EXPECT_EQ(gd.placedBuildings[0].instanceId, "inst-1");
    EXPECT_EQ(gd.spiritStones, 10000 - 1200);
    EXPECT_EQ(r.spiritStonesAfter, 10000 - 1200);
    EXPECT_EQ(gd.guideCounters.at("buildingBuilt_单人住所"), 1);
}

// ── 放置校验链失败臂（判定序与 Kotlin 逐字对齐，全臂零写入） ─────

TEST(BuildingTxTest, PlaceRejectsSectLevelZeroWrite) {
    auto gd = newStateWithSect(10000, /*level=*/0);  // 小型 < 需求 1
    const auto r = btx::placeBuildingTx(gd, building("inst-1", 20, 20), 1200,
                                        /*requiredSectLevel=*/1, true, false,
                                        "k", testGeom(), {});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "SectLevel");
    EXPECT_TRUE(gd.placedBuildings.empty());
    EXPECT_EQ(gd.spiritStones, 10000);
    EXPECT_TRUE(gd.guideCounters.empty());
}

TEST(BuildingTxTest, PlaceRejectsOutsideBorderRing) {
    auto gd = newState(10000);
    const auto r = btx::placeBuildingTx(gd, building("inst-1", 1, 20), 1200, 0,
                                        true, false, "k", testGeom(), {});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "OutOfBounds");
    EXPECT_TRUE(gd.placedBuildings.empty());
}

TEST(BuildingTxTest, PlaceRejectsGateBlockedCells) {
    auto gd = newState(10000);
    // 门楼矩形 (61,60) 6×2：与脚印 (60,60,2,3) 在 (61,60)/(61,61) 相交
    const auto r = btx::placeBuildingTx(gd, building("inst-1", 60, 60), 1200, 0,
                                        true, false, "k", testGeom(), {});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "OutOfBounds");
    EXPECT_TRUE(gd.placedBuildings.empty());
}

TEST(BuildingTxTest, PlaceRejectsGloballyUniqueLimit) {
    auto gd = newState(10000);
    // 全局唯一：跨宗门（scope 不含）仍计数
    gd.placedBuildings.push_back(
        building("other-sect-inst", 60, 20, 2, 3, "warehouse", "宗门仓库"));
    const auto r = btx::placeBuildingTx(
        gd, building("inst-1", 20, 20, 2, 3, "warehouse", "宗门仓库"), 1200, 0,
        /*unlimitedBuild=*/false, /*globallyUnique=*/true, "k", testGeom(),
        /*sectScopedIds=*/{});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "BuildLimit");
    EXPECT_EQ(gd.placedBuildings.size(), 1u);
}

TEST(BuildingTxTest, PlaceRejectsSameSectLimitScoped) {
    auto gd = newState(10000);
    // 同名建筑在异宗（scope 外）不拦截；同宗（scope 内）拦截
    gd.placedBuildings.push_back(
        building("other-sect-inst", 60, 20, 2, 3, "mine", "灵矿场"));
    const auto pass = btx::placeBuildingTx(
        gd, building("inst-1", 20, 20, 2, 3, "mine", "灵矿场"), 1200, 0, false,
        false, "k", testGeom(), {"inst-1"});
    ASSERT_TRUE(pass.ok);
    const auto reject = btx::placeBuildingTx(
        gd, building("inst-2", 40, 20, 2, 3, "mine", "灵矿场"), 1200, 0, false,
        false, "k", testGeom(), {"inst-1", "inst-2"});
    EXPECT_FALSE(reject.ok);
    EXPECT_STREQ(reject.errorType, "BuildLimit");
    EXPECT_EQ(gd.placedBuildings.size(), 2u);
}

TEST(BuildingTxTest, PlaceRejectsOverlapSameSectZeroWrite) {
    auto gd = newState(10000);
    gd.placedBuildings.push_back(building("neighbor", 21, 22));  // 与目标相交
    // 同宗（scope 含 neighbor）→ 拒绝；异宗（scope 不含）→ 放行（各宗独立网格）
    const auto reject = btx::placeBuildingTx(
        gd, building("inst-1", 20, 20), 1200, 0, true, false, "k", testGeom(),
        {"neighbor"});
    EXPECT_FALSE(reject.ok);
    EXPECT_STREQ(reject.errorType, "Overlap");
    EXPECT_EQ(gd.placedBuildings.size(), 1u);
    EXPECT_EQ(gd.spiritStones, 10000);
    const auto pass = btx::placeBuildingTx(
        gd, building("inst-1", 20, 20), 1200, 0, true, false, "k", testGeom(),
        /*sectScopedIds=*/{});
    EXPECT_TRUE(pass.ok);
    EXPECT_EQ(gd.placedBuildings.size(), 2u);
}

TEST(BuildingTxTest, PlaceRejectsInsufficientStonesZeroWrite) {
    auto gd = newState(1199);
    const auto r = btx::placeBuildingTx(gd, building("inst-1", 20, 20), 1200, 0,
                                        true, false, "k", testGeom(), {});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "Insufficient");
    EXPECT_TRUE(gd.placedBuildings.empty());
    EXPECT_EQ(gd.spiritStones, 1199);
}

// ── 迁移：happy + 防御 + 原子性 ────────────────────────────────

TEST(BuildingTxTest, MoveHappyUpdatesCoordsOnly) {
    auto gd = newState(10000);
    gd.placedBuildings.push_back(building("inst-1", 20, 20));
    gd.placedBuildings.push_back(building("neighbor", 40, 40));
    const auto r = btx::moveBuildingTx(gd, "inst-1", 50, 50, testGeom());
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(gd.placedBuildings.size(), 2u);  // 原子性：计数不变（等价 place+remove 而非增删）
    EXPECT_EQ(gd.placedBuildings[0].gridX, 50);
    EXPECT_EQ(gd.placedBuildings[0].gridY, 50);
    EXPECT_EQ(gd.placedBuildings[0].width, 2);   // 其余字段零扰动
    EXPECT_EQ(gd.placedBuildings[0].height, 3);
    EXPECT_EQ(gd.placedBuildings[1].gridX, 40);  // 邻座零扰动
    EXPECT_EQ(gd.spiritStones, 10000);           // 迁移零费用
}

TEST(BuildingTxTest, MoveRejectsMissingAndOutOfBoundsZeroWrite) {
    auto gd = newState(10000);
    gd.placedBuildings.push_back(building("inst-1", 20, 20));
    const auto ghost = btx::moveBuildingTx(gd, "ghost", 50, 50, testGeom());
    EXPECT_FALSE(ghost.ok);
    EXPECT_STREQ(ghost.errorType, "NotFound");
    const auto oob = btx::moveBuildingTx(gd, "inst-1", 1, 1, testGeom());
    EXPECT_FALSE(oob.ok);
    EXPECT_STREQ(oob.errorType, "OutOfBounds");
    // 无重叠检查（与 Kotlin moveBuildingDirect 一致）：目标叠邻座仍放行
    const auto overlapOk = btx::moveBuildingTx(gd, "inst-1", 40, 40, testGeom());
    EXPECT_TRUE(overlapOk.ok);
    EXPECT_EQ(gd.placedBuildings[0].gridX, 40);
}

// ── 升级（单座）：原地变换 + 精确扣减 + 校验链 ─────────────────

TEST(BuildingTxTest, UpgradeHappyInPlaceTransformExactDeduction) {
    auto gd = newStateWithSect(100000, /*level=*/1);
    gd.placedBuildings.push_back(building("inst-1", 20, 20));
    const auto r = btx::upgradeBuildingTx(
        gd, "inst-1", "single_residence_upgraded", "中级单人住所", 4, 4,
        /*cost=*/38000, testGeom(), {"inst-1"});
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.upgradedCount, 1);
    ASSERT_EQ(gd.placedBuildings.size(), 1u);
    EXPECT_EQ(gd.placedBuildings[0].buildingId, "single_residence_upgraded");
    EXPECT_EQ(gd.placedBuildings[0].displayName, "中级单人住所");
    EXPECT_EQ(gd.placedBuildings[0].width, 4);
    EXPECT_EQ(gd.placedBuildings[0].height, 4);
    EXPECT_EQ(gd.placedBuildings[0].gridX, 20);   // 左上角不变
    EXPECT_EQ(gd.placedBuildings[0].gridY, 20);
    EXPECT_EQ(gd.placedBuildings[0].instanceId, "inst-1");  // 实例身份不变
    EXPECT_EQ(gd.spiritStones, 100000 - 38000);
    EXPECT_EQ(r.spiritStonesAfter, 100000 - 38000);
}

TEST(BuildingTxTest, UpgradeRejectsChainZeroWrite) {
    auto gd = newStateWithSect(100000, /*level=*/1);
    gd.placedBuildings.push_back(building("inst-1", 20, 20));
    // 不存在
    auto r = btx::upgradeBuildingTx(gd, "ghost", "t", "目标", 4, 4, 38000,
                                    testGeom(), {"inst-1"});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "NotFound");
    // 等级不足（< 中型）
    auto small = newStateWithSect(100000, /*level=*/0);
    small.placedBuildings.push_back(building("inst-1", 20, 20));
    r = btx::upgradeBuildingTx(small, "inst-1", "t", "目标", 4, 4, 38000,
                               testGeom(), {"inst-1"});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "SectLevel");
    // 灵石不足（判定序：等级先于灵石）
    r = btx::upgradeBuildingTx(gd, "inst-1", "t", "目标", 4, 4, 100001,
                               testGeom(), {"inst-1"});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "Insufficient");
    // 升级后占地越界（目标 4×4 于 (123,123) → 越界）
    gd.placedBuildings[0].gridX = 123;
    gd.placedBuildings[0].gridY = 123;
    r = btx::upgradeBuildingTx(gd, "inst-1", "t", "目标", 4, 4, 38000,
                               testGeom(), {"inst-1"});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "SpaceBlocked");
    // 升级后占地与同宗邻座重叠（异宗 scope 外放行对照在批量用例覆盖）
    gd.placedBuildings[0].gridX = 20;
    gd.placedBuildings[0].gridY = 20;
    gd.placedBuildings.push_back(building("neighbor", 22, 22));
    r = btx::upgradeBuildingTx(gd, "inst-1", "t", "目标", 4, 4, 38000,
                               testGeom(), {"inst-1", "neighbor"});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "SpaceBlocked");
    // 全臂零写入：源建筑字段与灵石原样
    EXPECT_EQ(gd.placedBuildings[0].buildingId, "single_residence");
    EXPECT_EQ(gd.placedBuildings[0].width, 2);
    EXPECT_EQ(gd.spiritStones, 100000);
}

// ── 批量升级：稳定序 + 可负担上限 + 增量 canFit ────────────────

TEST(BuildingTxTest, UpgradeBatchHappyStableOrderAndCounts) {
    auto gd = newStateWithSect(200000, /*level=*/1);
    // 候选乱序放入；稳定序 = gridX 升序
    gd.placedBuildings.push_back(building("b", 40, 20));
    gd.placedBuildings.push_back(building("a", 20, 20));
    const auto r = btx::upgradeBuildingsTx(
        gd, "single_residence", "single_residence_upgraded", "中级单人住所", 4,
        4, /*maxCount=*/10, /*cost=*/38000, testGeom(), {"a", "b"});
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.upgradedCount, 2);
    EXPECT_EQ(r.spaceBlockedCount, 0);
    EXPECT_EQ(gd.spiritStones, 200000 - 2 * 38000);
    // 稳定序：gridX=20 者先升级（两座升级后同 key，按坐标验证中间态序——
    // 用扣减总额与计数断言；顺序本身由失败臂增量校验用例锁定）
    for (const auto& b : gd.placedBuildings) {
        EXPECT_EQ(b.buildingId, "single_residence_upgraded");
    }
}

TEST(BuildingTxTest, UpgradeBatchAffordableCapAndIncrementalFit) {
    auto gd = newStateWithSect(100000, /*level=*/1);  // 仅够 2 座（差价 38000）
    // 相邻两座 2×3（20,20)/(22,20) 候选升级为 4×4：稳定序先审 a(20,20)——
    // 其目标占地与 b 原始脚印重叠 → spaceBlocked；后审 b——目标占地与
    // a 未升级脚印（2×3）不相交 → 升级（「升级中间态」增量校验语义）
    gd.placedBuildings.push_back(building("a", 20, 20));
    gd.placedBuildings.push_back(building("b", 22, 20));
    const auto r = btx::upgradeBuildingsTx(
        gd, "single_residence", "single_residence_upgraded", "中级单人住所", 4,
        4, /*maxCount=*/10, /*cost=*/38000, testGeom(), {"a", "b"});
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.upgradedCount, 1);
    EXPECT_EQ(r.spaceBlockedCount, 1);
    EXPECT_EQ(gd.spiritStones, 100000 - 38000);  // 只扣实升级数
    EXPECT_EQ(gd.placedBuildings[0].buildingId, "single_residence");
    EXPECT_EQ(gd.placedBuildings[1].buildingId, "single_residence_upgraded");
}

TEST(BuildingTxTest, UpgradeBatchRejectsZeroWrite) {
    // 等级整批判定先行
    auto small = newStateWithSect(100000, /*level=*/0);
    small.placedBuildings.push_back(building("a", 20, 20));
    auto r = btx::upgradeBuildingsTx(small, "single_residence", "t", "目标", 4,
                                     4, 10, 38000, testGeom(), {"a"});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "SectLevel");
    // 空候选
    auto empty = newStateWithSect(100000, /*level=*/1);
    r = btx::upgradeBuildingsTx(empty, "single_residence", "t", "目标", 4, 4,
                                10, 38000, testGeom(), {});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "NoCandidates");
    // 灵石不足任意一座
    auto poor = newStateWithSect(37999, /*level=*/1);
    poor.placedBuildings.push_back(building("a", 20, 20));
    r = btx::upgradeBuildingsTx(poor, "single_residence", "t", "目标", 4, 4, 10,
                                38000, testGeom(), {"a"});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "Insufficient");
    // maxCount<=0（Kotlin 上游守卫同口径）
    r = btx::upgradeBuildingsTx(poor, "single_residence", "t", "目标", 4, 4, 0,
                                38000, testGeom(), {"a"});
    EXPECT_FALSE(r.ok);
    EXPECT_STREQ(r.errorType, "Insufficient");
}

// ── 拆除：返还 + 年度账 + 幽灵/空表防御 ────────────────────────

TEST(BuildingTxTest, RemoveHappyRefundsAndReportsRemovedIds) {
    auto gd = newState(1000);
    gd.placedBuildings.push_back(building("inst-1", 20, 20));
    gd.placedBuildings.push_back(building("inst-2", 40, 40));
    const auto r = btx::removeBuildingsTx(
        gd, {{"inst-1", 500}, {"inst-2", 0}});
    ASSERT_TRUE(r.ok);
    // 回执以实际移除为准（Kotlin 残差清理依据）
    ASSERT_EQ(r.removedInstanceIds.size(), 2u);
    EXPECT_EQ(r.removedInstanceIds[0], "inst-1");
    EXPECT_EQ(r.removedInstanceIds[1], "inst-2");
    EXPECT_TRUE(gd.placedBuildings.empty());
    // 返还走 wallet.add：余额入账 + 年度账记录（与 Kotlin wallet.add 同原语）
    EXPECT_EQ(gd.spiritStones, 1500);
    EXPECT_EQ(gd.annualTotalIncome, 500);
    EXPECT_EQ(gd.annualIncomeBySource.at("Refund"), 500);
}

TEST(BuildingTxTest, RemoveSkipsGhostInstancesAndEmptyList) {
    auto gd = newState(1000);
    gd.placedBuildings.push_back(building("inst-1", 20, 20));
    // 幽灵实例跳过（零写入零返还）
    auto r = btx::removeBuildingsTx(gd, {{"ghost", 500}, {"inst-1", 250}});
    ASSERT_TRUE(r.ok);
    ASSERT_EQ(r.removedInstanceIds.size(), 1u);
    EXPECT_EQ(r.removedInstanceIds[0], "inst-1");
    EXPECT_EQ(gd.spiritStones, 1250);
    // 空表防御
    auto empty = newState(1000);
    const auto none = btx::removeBuildingsTx(empty, {});
    EXPECT_TRUE(none.ok);
    EXPECT_TRUE(none.removedInstanceIds.empty());
}

// ── 零 RNG 审计 ───────────────────────────────────────────────

TEST(BuildingTxTest, ZeroRngDeterministicReplay) {
    // 签名级：五事务均不接受 RngManager/种子参数；
    // 行为级：同初始状态两次事务序列，终态逐位一致。
    auto build = [] {
        auto gd = newStateWithSect(100000, 1);
        (void)btx::placeBuildingTx(gd, building("a", 20, 20), 1200, 0, true,
                                   false, "k", testGeom(), {"a"});
        (void)btx::moveBuildingTx(gd, "a", 30, 30, testGeom());
        (void)btx::upgradeBuildingTx(gd, "a", "up", "中级", 4, 4, 38000,
                                     testGeom(), {"a"});
        (void)btx::placeBuildingTx(gd, building("b", 60, 20), 1200, 0, true,
                                   false, "k", testGeom(), {"b"});
        (void)btx::removeBuildingsTx(gd, {{"b", 300}});
        return gd;
    };
    const auto x = build();
    const auto y = build();
    ASSERT_EQ(x.placedBuildings.size(), y.placedBuildings.size());
    for (std::size_t i = 0; i < x.placedBuildings.size(); ++i) {
        EXPECT_EQ(x.placedBuildings[i].buildingId, y.placedBuildings[i].buildingId);
        EXPECT_EQ(x.placedBuildings[i].displayName, y.placedBuildings[i].displayName);
        EXPECT_EQ(x.placedBuildings[i].gridX, y.placedBuildings[i].gridX);
        EXPECT_EQ(x.placedBuildings[i].gridY, y.placedBuildings[i].gridY);
        EXPECT_EQ(x.placedBuildings[i].width, y.placedBuildings[i].width);
        EXPECT_EQ(x.placedBuildings[i].height, y.placedBuildings[i].height);
        EXPECT_EQ(x.placedBuildings[i].instanceId, y.placedBuildings[i].instanceId);
    }
    EXPECT_EQ(x.spiritStones, y.spiritStones);
    EXPECT_EQ(x.guideCounters.size(), y.guideCounters.size());
    EXPECT_EQ(x.annualTotalIncome, y.annualTotalIncome);
}

// ── 分发面（GameCore::execute 信封） ───────────────────────────

class BuildingTxFixture : public ::testing::Test {
protected:
    void SetUp() override {
        core_ = std::make_unique<gamecore::GameCore>(&clock_, &logger_);
        gamecore::GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result =
            core_->execute(actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    gamecore::FixedClock clock_;
    gamecore::ConsoleLogger logger_;
    std::unique_ptr<gamecore::GameCore> core_;
};

TEST_F(BuildingTxFixture, DispatchPlaceSuccessAndMirrorFields) {
    auto& gd = core_->state().gameData;
    gd.spiritStones = 10000;
    const auto r = exec(gamecore::action::BUILDING_PLACE,
                        {{"buildingId", "single_residence"},
                         {"displayName", "单人住所"},
                         {"gridX", 20}, {"gridY", 20},
                         {"width", 2}, {"height", 3},
                         {"instanceId", "inst-1"},
                         {"cost", 1200},
                         {"counterKey", "buildingBuilt_单人住所"},
                         {"sectScopedIds", std::vector<std::string>{}},
                         {"border", kBorder}, {"worldWidth", kWidth},
                         {"worldHeight", kHeight},
                         {"gateX", 61}, {"gateY", 126},
                         {"gateWidth", 6}, {"gateHeight", 2}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("placed"), true);
    EXPECT_EQ(r.at("data").at("instanceId"), "inst-1");
    EXPECT_EQ(r.at("data").at("spiritStones").get<int64_t>(), 10000 - 1200);
    // 状态真相在 C++（镜像通道经 dirty diff 回读 placedBuildings/spiritStones/guideCounters）
    ASSERT_EQ(gd.placedBuildings.size(), 1u);
    EXPECT_EQ(gd.guideCounters.at("buildingBuilt_单人住所"), 1);
}

TEST_F(BuildingTxFixture, DispatchPlaceFailureEnvelopeZeroWrite) {
    auto& gd = core_->state().gameData;
    gd.spiritStones = 1199;
    const auto r = exec(gamecore::action::BUILDING_PLACE,
                        {{"buildingId", "single_residence"},
                         {"displayName", "单人住所"},
                         {"gridX", 20}, {"gridY", 20},
                         {"width", 2}, {"height", 3},
                         {"instanceId", "inst-1"},
                         {"cost", 1200},
                         {"counterKey", "k"},
                         {"sectScopedIds", std::vector<std::string>{}},
                         {"border", kBorder}, {"worldWidth", kWidth},
                         {"worldHeight", kHeight},
                         {"gateX", 61}, {"gateY", 126},
                         {"gateWidth", 6}, {"gateHeight", 2}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "Insufficient");
    // 失败零写入——Kotlin 回退臂重执行校验链
    EXPECT_TRUE(gd.placedBuildings.empty());
    EXPECT_EQ(gd.spiritStones, 1199);
}

TEST_F(BuildingTxFixture, DispatchMoveUpgradeRemoveHappyChain) {
    auto& gd = core_->state().gameData;
    gd.spiritStones = 100000;
    WorldSect sect;
    sect.isPlayerSect = true;
    sect.level = 1;
    gd.worldMapSects.push_back(sect);
    const nlohmann::json geom = {{"border", kBorder},
                                 {"worldWidth", kWidth},
                                 {"worldHeight", kHeight},
                                 {"gateX", 61}, {"gateY", 126},
                                 {"gateWidth", 6}, {"gateHeight", 2}};
    nlohmann::json placeParams = {
        {"buildingId", "single_residence"},
        {"displayName", "单人住所"},
        {"gridX", 20}, {"gridY", 20},
        {"width", 2}, {"height", 3},
        {"instanceId", "inst-1"},
        {"cost", 1200},
        {"counterKey", "k"},
        {"sectScopedIds", std::vector<std::string>{"inst-1"}}};
    placeParams.merge_patch(geom);
    ASSERT_EQ(exec(gamecore::action::BUILDING_PLACE, placeParams).at("status"),
              "success");

    // 迁移
    nlohmann::json moveParams = {{"instanceId", "inst-1"},
                                 {"newGridX", 40},
                                 {"newGridY", 40}};
    moveParams.merge_patch(geom);
    const auto moved = exec(gamecore::action::BUILDING_MOVE, moveParams);
    ASSERT_EQ(moved.at("status"), "success");
    EXPECT_EQ(moved.at("data").at("moved"), true);
    EXPECT_EQ(gd.placedBuildings[0].gridX, 40);

    // 升级（原地变换 + 差价）
    nlohmann::json upgradeParams = {
        {"instanceId", "inst-1"},
        {"targetKey", "single_residence_upgraded"},
        {"targetDisplayName", "中级单人住所"},
        {"targetWidth", 4}, {"targetHeight", 4},
        {"cost", 38000},
        {"sectScopedIds", std::vector<std::string>{"inst-1"}}};
    upgradeParams.merge_patch(geom);
    const auto upgraded = exec(gamecore::action::BUILDING_UPGRADE, upgradeParams);
    ASSERT_EQ(upgraded.at("status"), "success");
    EXPECT_EQ(gd.placedBuildings[0].buildingId, "single_residence_upgraded");
    EXPECT_EQ(gd.spiritStones, 100000 - 1200 - 38000);

    // 批量升级（无候选 → failure 信封）
    nlohmann::json batchParams = {
        {"sourceKey", "multi_residence"},
        {"targetKey", "multi_residence_upgraded"},
        {"targetDisplayName", "中级多人住所"},
        {"targetWidth", 6}, {"targetHeight", 6},
        {"maxCount", 10}, {"cost", 56000},
        {"sectScopedIds", std::vector<std::string>{"inst-1"}}};
    batchParams.merge_patch(geom);
    const auto batch = exec(gamecore::action::BUILDING_UPGRADE_BATCH, batchParams);
    ASSERT_EQ(batch.at("status"), "failure");
    EXPECT_EQ(batch.at("code"), "NoCandidates");

    // 拆除（返还 + 回执）
    const auto removed =
        exec(gamecore::action::BUILDING_REMOVE,
             {{"refunds",
               nlohmann::json::array({{{"instanceId", "inst-1"},
                                       {"refund", 600}}})}});
    ASSERT_EQ(removed.at("status"), "success");
    EXPECT_EQ(removed.at("data").at("removedIds"),
              nlohmann::json::array({"inst-1"}));
    EXPECT_TRUE(gd.placedBuildings.empty());
    EXPECT_EQ(gd.spiritStones, 100000 - 1200 - 38000 + 600);
}

TEST_F(BuildingTxFixture, DispatchBuildingTxConsumesZeroRng) {
    // 全分区 RNG 快照差分：事务前后逐分区状态不变（抽取集为空集）
    const auto before = core_->rng().exportStates();
    auto& gd = core_->state().gameData;
    gd.spiritStones = 100000;
    const nlohmann::json geom = {{"border", kBorder},
                                 {"worldWidth", kWidth},
                                 {"worldHeight", kHeight},
                                 {"gateX", 61}, {"gateY", 126},
                                 {"gateWidth", 6}, {"gateHeight", 2}};
    nlohmann::json placeParams = {
        {"buildingId", "single_residence"},
        {"displayName", "单人住所"},
        {"gridX", 20}, {"gridY", 20},
        {"width", 2}, {"height", 3},
        {"instanceId", "inst-1"},
        {"cost", 1200},
        {"counterKey", "k"},
        {"sectScopedIds", std::vector<std::string>{}}};
    placeParams.merge_patch(geom);
    ASSERT_EQ(exec(gamecore::action::BUILDING_PLACE, placeParams).at("status"),
              "success");
    nlohmann::json moveParams = {{"instanceId", "inst-1"},
                                 {"newGridX", 40},
                                 {"newGridY", 40}};
    moveParams.merge_patch(geom);
    ASSERT_EQ(exec(gamecore::action::BUILDING_MOVE, moveParams).at("status"),
              "success");
    ASSERT_EQ(exec(gamecore::action::BUILDING_REMOVE,
                   {{"refunds",
                     nlohmann::json::array({{{"instanceId", "inst-1"},
                                             {"refund", 100}}})}})
                  .at("status"),
              "success");
    EXPECT_EQ(core_->rng().exportStates(), before);
}

}  // namespace
