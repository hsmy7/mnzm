#include <gtest/gtest.h>
#include <unordered_set>

#include "gamecore/map/road_system.h"

namespace gamecore {
namespace {

using gamecore::map::RoadGrid;
using gamecore::map::RoadTileType;
using gamecore::map::kDirDown;
using gamecore::map::kDirLeft;
using gamecore::map::kDirRight;
using gamecore::map::kDirUp;
using gamecore::map::kMaskAll;

// ============================================================
// 道路系统测试
// 覆盖：位掩码→形态映射、边框自动判断、放置/删除邻居重算、
//      并行道路（不重复描边）、建筑/道路冲突、可建环。
// ============================================================

TEST(RoadBitmaskTest, TileTypeMapCoversAll) {
    EXPECT_EQ(RoadTileType::SINGLE, map::tileTypeForBitmask(0));
    EXPECT_EQ(RoadTileType::VERTICAL, map::tileTypeForBitmask(kDirUp | kDirDown));
    EXPECT_EQ(RoadTileType::HORIZONTAL, map::tileTypeForBitmask(kDirLeft | kDirRight));
    EXPECT_EQ(RoadTileType::CORNER_TOP_LEFT, map::tileTypeForBitmask(kDirUp | kDirLeft));
    EXPECT_EQ(RoadTileType::CORNER_TOP_RIGHT, map::tileTypeForBitmask(kDirUp | kDirRight));
    EXPECT_EQ(RoadTileType::CORNER_BOTTOM_LEFT, map::tileTypeForBitmask(kDirDown | kDirLeft));
    EXPECT_EQ(RoadTileType::CORNER_BOTTOM_RIGHT, map::tileTypeForBitmask(kDirDown | kDirRight));
    // T 型：主干方向 = 单独臂
    EXPECT_EQ(RoadTileType::T_UP, map::tileTypeForBitmask(kDirUp | kDirLeft | kDirRight));
    EXPECT_EQ(RoadTileType::T_DOWN, map::tileTypeForBitmask(kDirDown | kDirLeft | kDirRight));
    EXPECT_EQ(RoadTileType::T_RIGHT, map::tileTypeForBitmask(kDirUp | kDirDown | kDirRight));
    EXPECT_EQ(RoadTileType::T_LEFT, map::tileTypeForBitmask(kDirUp | kDirDown | kDirLeft));
    EXPECT_EQ(RoadTileType::CROSS, map::tileTypeForBitmask(kMaskAll));
    // 死路（道路端点）按方向归为直路
    EXPECT_EQ(RoadTileType::VERTICAL, map::tileTypeForBitmask(kDirUp));
    EXPECT_EQ(RoadTileType::HORIZONTAL, map::tileTypeForBitmask(kDirLeft));
}

TEST(RoadBitmaskTest, TileTypeFromDeadEndSingle) {
    // 单个孤立道路 → SINGLE
    RoadGrid grid(10, 10);
    grid.placeRoad(5, 5, {}, 0);
    EXPECT_EQ(RoadTileType::SINGLE, grid.tileAt(5, 5));
}

// ── 边框自动判断 ────────────────────────────────────────────────

TEST(RoadBorderTest, BorderMaskIsComplementOfConnectionMask) {
    // 无道路邻居 → 四边全描边
    EXPECT_EQ(kMaskAll, map::roadBorderMask(0));
    // 上下连接（内部上下边不描边），左右无边 → 只描左右
    EXPECT_EQ(kDirLeft | kDirRight, map::roadBorderMask(kDirUp | kDirDown));
    // 左右连接 → 只描上下
    EXPECT_EQ(kDirUp | kDirDown, map::roadBorderMask(kDirLeft | kDirRight));
    // 十字（四边全通）→ 不描任何边（完全内部，多道交叉不重复边框）
    EXPECT_EQ(0, map::roadBorderMask(kMaskAll));
    // 转角（上+右）→ 只描下+左 = 外缘
    EXPECT_EQ(kDirDown | kDirLeft, map::roadBorderMask(kDirUp | kDirRight));
}

// ── 放置/删除 + 邻居重算 ─────────────────────────────────────────

TEST(RoadPlaceTest, HorizontalPairBothHorizontal) {
    RoadGrid grid(10, 10);
    ASSERT_TRUE(grid.placeRoad(3, 4, {}, 0));
    ASSERT_TRUE(grid.placeRoad(4, 4, {}, 0));  // 右邻居
    EXPECT_EQ(RoadTileType::HORIZONTAL, grid.tileAt(3, 4));
    EXPECT_EQ(RoadTileType::HORIZONTAL, grid.tileAt(4, 4));
}

TEST(RoadPlaceTest, VerticalPairBothVertical) {
    RoadGrid grid(10, 10);
    grid.placeRoad(5, 5, {}, 0);
    grid.placeRoad(5, 6, {}, 0);
    EXPECT_EQ(RoadTileType::VERTICAL, grid.tileAt(5, 5));
    EXPECT_EQ(RoadTileType::VERTICAL, grid.tileAt(5, 6));
}

TEST(RoadPlaceTest, LShapedCorner) {
    RoadGrid grid(10, 10);
    // 上 + 左 转角
    grid.placeRoad(5, 5, {}, 0);
    grid.placeRoad(5, 4, {}, 0);  // 上
    grid.placeRoad(4, 5, {}, 0);  // 左
    EXPECT_EQ(RoadTileType::CORNER_TOP_LEFT, grid.tileAt(5, 5));
}

TEST(RoadPlaceTest, CrossIntersection) {
    RoadGrid grid(11, 11);
    const int cx = 5, cy = 5;
    grid.placeRoad(cx, cy, {}, 0);
    grid.placeRoad(cx, cy - 1, {}, 0);
    grid.placeRoad(cx, cy + 1, {}, 0);
    grid.placeRoad(cx - 1, cy, {}, 0);
    grid.placeRoad(cx + 1, cy, {}, 0);
    EXPECT_EQ(RoadTileType::CROSS, grid.tileAt(cx, cy));
}

TEST(RoadPlaceTest, TIntersection) {
    RoadGrid grid(11, 11);
    const int cx = 5, cy = 5;
    grid.placeRoad(cx, cy, {}, 0);
    grid.placeRoad(cx, cy - 1, {}, 0);  // 上
    grid.placeRoad(cx, cy + 1, {}, 0);  // 下
    grid.placeRoad(cx + 1, cy, {}, 0);  // 右
    // 上+下+右 = 缺左 → T_RIGHT（主干朝右）
    EXPECT_EQ(RoadTileType::T_RIGHT, grid.tileAt(cx, cy));
}

// ── 删除后邻居自动重新拼接 ───────────────────────────────────────

TEST(RoadRemoveTest, RemoveCenterOfCrossRestoresNeighbors) {
    RoadGrid grid(11, 11);
    const int cx = 5, cy = 5;
    for (int dx = -1; dx <= 1; ++dx) grid.placeRoad(cx + dx, cy, {}, 0);
    for (int dy = -1; dy <= 1; ++dy) grid.placeRoad(cx, cy + dy, {}, 0);
    EXPECT_EQ(RoadTileType::CROSS, grid.tileAt(cx, cy));

    // 删除中心 → 四个臂各自重算：
    //   - 上格(cy-1)：只剩中心格? 中心删除后，上格无其它邻居 → SINGLE
    //   - 四个端格各自变成只有中心的单臂
    grid.removeRoad(cx, cy);
    EXPECT_FALSE(grid.isRoad(cx, cy));
    // 上臂端(cy-2) 与 中心之间消失 → 上臂端仍连 cy-1
    EXPECT_TRUE(grid.isRoad(cx, cy - 1));
    // cy-1 现在只有一条臂（连 cy-2? 无）→ 上格只有 1 个连接（向上? 实为向中心但中心已删）
    // 重新检查：移除中心后，cy-1 的邻居 = (cx, cy-2)(上) 与 (cx,cy)(下,已非道路)。
    // 若未放 cy-2，则 cy-1 无连接 → SINGLE。
    EXPECT_EQ(RoadTileType::SINGLE, grid.tileAt(cx, cy - 1));
}

TEST(RoadRemoveTest, RemoveEndBreaksLine) {
    RoadGrid grid(10, 10);
    grid.placeRoad(3, 4, {}, 0);
    grid.placeRoad(4, 4, {}, 0);
    grid.placeRoad(5, 4, {}, 0);
    EXPECT_EQ(RoadTileType::HORIZONTAL, grid.tileAt(4, 4));
    // 删除中间 (4,4) → 两端各自的邻接都断成单格
    grid.removeRoad(4, 4);
    EXPECT_EQ(RoadTileType::SINGLE, grid.tileAt(3, 4));
    EXPECT_EQ(RoadTileType::SINGLE, grid.tileAt(5, 4));
}

// ── 并行道路：内部不重复描边 ─────────────────────────────────────

TEST(RoadParallelTest, ThreeRowsInternalEdgesNoBorder) {
    RoadGrid grid(10, 10);
    // 三行并行横向路（相邻、无空隙）：y=3,4,5，x=2..6
    for (int y = 3; y <= 5; ++y)
        for (int x = 2; x <= 6; ++x) grid.placeRoad(x, y, {}, 0);

    // 相邻并行道路会互相连接成网格：中间行上/下/左/右皆道路 → 十字，
    // 且内部（所有有道路邻居的方向）不重复描边 —— 这是"并行道路之间不出现外边框"的核心。
    for (int x = 3; x <= 5; ++x) {
        EXPECT_EQ(RoadTileType::CROSS, grid.tileAt(x, 4)) << "middle (" << x << ",4)";
        EXPECT_EQ(0, grid.borderMaskAt(x, 4)) << "middle border (" << x << ",4)";
    }
    // 顶行内部格：上无（boundary），下/左/右有 → 只需描上边（外缘），内部不描
    EXPECT_EQ(RoadTileType::T_DOWN, grid.tileAt(4, 3));
    EXPECT_EQ(kDirUp, grid.borderMaskAt(4, 3)) << "top row should only border on top";
    // 底行内部格：上有，下无，左/右有 → 只需描下边
    EXPECT_EQ(RoadTileType::T_UP, grid.tileAt(4, 5));
    EXPECT_EQ(kDirDown, grid.borderMaskAt(4, 5));
}

// ── 可建造判定（道路 vs 建筑冲突 + 可建环）──────────────────────

TEST(RoadCanPlaceTest, BlockedByCellRejects) {
    // key 编码 (x<<32)|y
    std::unordered_set<int64_t> blocked;
    blocked.insert(RoadGrid::packCell(5, 5));  // 建筑占位
    EXPECT_FALSE(RoadGrid::canPlaceRoad(5, 5, 10, 10, 0, blocked, nullptr));
    EXPECT_TRUE(RoadGrid::canPlaceRoad(4, 4, 10, 10, 0, blocked, nullptr));
}

TEST(RoadCanPlaceTest, BorderRingRejects) {
    EXPECT_FALSE(RoadGrid::canPlaceRoad(0, 0, 10, 10, 2, {}, nullptr));  // 边界环内
    EXPECT_TRUE(RoadGrid::canPlaceRoad(2, 2, 10, 10, 2, {}, nullptr));
}

TEST(RoadCanPlaceTest, BuildingCannotOverlapRoad) {
    RoadGrid grid(10, 10);
    grid.placeRoad(4, 4, {}, 0);
    // 已有道路的格子不允许放建筑
    EXPECT_FALSE(RoadGrid::canPlaceBuilding(4, 4, 10, 10, 0, {}, &grid));
    // 空位可放
    EXPECT_TRUE(RoadGrid::canPlaceBuilding(6, 6, 10, 10, 0, {}, &grid));
}

TEST(RoadCanPlaceTest, PlaceHonorsConflict) {
    std::unordered_set<int64_t> blocked;
    blocked.insert(RoadGrid::packCell(7, 7));
    RoadGrid grid(10, 10);
    // 被占位格不可放置（placeRoad 返回 false 且状态不变）
    EXPECT_FALSE(grid.placeRoad(7, 7, blocked, 0));
    EXPECT_FALSE(grid.isRoad(7, 7));
}

// ── rebuild（读档全量重建）───────────────────────────────────────

TEST(RoadGridTest, RebuildRecomputesAllMasks) {
    RoadGrid grid(10, 10);
    grid.placeRoad(3, 4, {}, 0);
    grid.placeRoad(4, 4, {}, 0);
    EXPECT_EQ(RoadTileType::HORIZONTAL, grid.tileAt(3, 4));
    // 重建为竖向两格 → 再次按新集合推导
    grid.rebuild({{3, 4}, {3, 5}});
    EXPECT_EQ(RoadTileType::VERTICAL, grid.tileAt(3, 4));
    EXPECT_EQ(RoadTileType::VERTICAL, grid.tileAt(3, 5));
}

}  // namespace
}  // namespace gamecore
