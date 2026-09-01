#include <gtest/gtest.h>

#include <vector>

#include "gamecore/map/road_compositor.h"

namespace gamecore {
namespace {

using gamecore::map::RoadDrawOp;
using gamecore::map::RoadSprite;
using gamecore::map::RoadTileType;
using gamecore::map::emitRoadDrawOps;
using gamecore::map::kDirDown;
using gamecore::map::kDirLeft;
using gamecore::map::kDirRight;
using gamecore::map::kDirUp;
using gamecore::map::kMaskAll;
using gamecore::map::kMaxRoadDrawOpsPerTile;
using gamecore::map::kRoadSpriteCount;
using gamecore::map::roadBorderMask;
using gamecore::map::tileTypeForBitmask;

// ============================================================
// 道路渲染合成器测试（计划 v2 阶段 6：单一权威守护）
// 覆盖：全掩码产出量守恒、主体映射、描边条/转角件几何、
//      十字中心装饰、操作顺序契约、枚举序与图集声明序一致。
// ============================================================

namespace {

/// 便利封装：产出单格操作序列。
std::vector<RoadDrawOp> opsFor(int mask, int tileSize = 36) {
    std::vector<RoadDrawOp> out(kMaxRoadDrawOpsPerTile);
    const int n = emitRoadDrawOps(mask, tileSize, out.data());
    out.resize(n);
    return out;
}

bool hasEdge(const std::vector<RoadDrawOp>& ops, int border, int dir) {
    return (border & dir) != 0;
}

}  // namespace

// ── 主体映射：全 16 掩码首个操作 = 形态对应主体 ─────────────────
TEST(RoadCompositorTest, BaseSpriteMatchesTileTypeForAllMasks) {
    for (int mask = 0; mask <= kMaskAll; ++mask) {
        const auto ops = opsFor(mask);
        ASSERT_FALSE(ops.empty()) << "mask=" << mask;
        const RoadTileType type = tileTypeForBitmask(mask);
        const RoadSprite expected = type == RoadTileType::HORIZONTAL ? RoadSprite::BASE
                                  : type == RoadTileType::VERTICAL   ? RoadSprite::BASE_V
                                                                     : RoadSprite::JUNCTION;
        EXPECT_EQ(expected, ops[0].sprite) << "mask=" << mask;
        // 主体铺满整格
        EXPECT_EQ(0, ops[0].x);
        EXPECT_EQ(0, ops[0].y);
        EXPECT_EQ(36, ops[0].w);
        EXPECT_EQ(36, ops[0].h);
    }
}

// ── 操作数守恒：1 主体 + 描边条(popcount(border)) + 转角件 + 十字中心 ──
TEST(RoadCompositorTest, OpCountConservationForAllMasks) {
    for (int mask = 0; mask <= kMaskAll; ++mask) {
        const auto ops = opsFor(mask);
        const int border = roadBorderMask(mask);
        int edges = 0;
        for (int dir = kDirUp; dir <= kDirLeft; dir <<= 1) {
            if (hasEdge(ops, border, dir)) ++edges;
        }
        int corners = 0;
        if ((border & kDirUp) && (border & kDirLeft)) ++corners;
        if ((border & kDirUp) && (border & kDirRight)) ++corners;
        if ((border & kDirDown) && (border & kDirLeft)) ++corners;
        if ((border & kDirDown) && (border & kDirRight)) ++corners;
        const int cross = tileTypeForBitmask(mask) == RoadTileType::CROSS ? 1 : 0;
        EXPECT_EQ(1 + edges + corners + cross, static_cast<int>(ops.size()))
            << "mask=" << mask;
        // 上限兜底
        EXPECT_LE(ops.size(), static_cast<size_t>(kMaxRoadDrawOpsPerTile));
    }
}

// ── 描边条几何：1/4 格厚，位置贴边 ─────────────────────────────
TEST(RoadCompositorTest, EdgeGeometry) {
    const int tileSize = 36;
    const int quarter = tileSize / 4;
    // mask=0（孤格）：四边全描边
    auto ops = opsFor(0, tileSize);
    ASSERT_EQ(9, static_cast<int>(ops.size()));
    // 上（EDGE_H 贴上缘）、下（贴下缘）、左（EDGE_V 贴左缘）、右（贴右缘）
    EXPECT_EQ(RoadSprite::EDGE_H, ops[1].sprite);
    EXPECT_EQ(0, ops[1].y);
    EXPECT_EQ(tileSize, ops[1].w);
    EXPECT_EQ(quarter, ops[1].h);
    EXPECT_EQ(RoadSprite::EDGE_H, ops[2].sprite);
    EXPECT_EQ(tileSize - quarter, ops[2].y);
    EXPECT_EQ(RoadSprite::EDGE_V, ops[3].sprite);
    EXPECT_EQ(0, ops[3].x);
    EXPECT_EQ(quarter, ops[3].w);
    EXPECT_EQ(tileSize, ops[3].h);
    EXPECT_EQ(RoadSprite::EDGE_V, ops[4].sprite);
    EXPECT_EQ(tileSize - quarter, ops[4].x);
}

// ── 转角件几何：quarter×quarter，贴外角 ────────────────────────
TEST(RoadCompositorTest, CornerGeometry) {
    const int tileSize = 36;
    const int quarter = tileSize / 4;
    const auto ops = opsFor(0, tileSize);  // 孤格：四角全有
    ASSERT_EQ(9, static_cast<int>(ops.size()));
    // 顺序契约：左上、右上、左下、右下（跟在 4 条描边条之后）
    EXPECT_EQ(RoadSprite::CORNER_TL, ops[5].sprite);
    EXPECT_EQ(0, ops[5].x);
    EXPECT_EQ(0, ops[5].y);
    EXPECT_EQ(RoadSprite::CORNER_TR, ops[6].sprite);
    EXPECT_EQ(tileSize - quarter, ops[6].x);
    EXPECT_EQ(0, ops[6].y);
    EXPECT_EQ(RoadSprite::CORNER_BL, ops[7].sprite);
    EXPECT_EQ(0, ops[7].x);
    EXPECT_EQ(tileSize - quarter, ops[7].y);
    EXPECT_EQ(RoadSprite::CORNER_BR, ops[8].sprite);
    EXPECT_EQ(tileSize - quarter, ops[8].x);
    EXPECT_EQ(tileSize - quarter, ops[8].y);
    for (int i = 5; i <= 8; ++i) {
        EXPECT_EQ(quarter, ops[i].w);
        EXPECT_EQ(quarter, ops[i].h);
    }
}

// ── 直路（对边连通）：仅两端描边，无转角 ───────────────────────
TEST(RoadCompositorTest, StraightRoadEdgesOnly) {
    // 垂直直路（上+下）：左右描边（EDGE_V），上下内部不描边
    auto ops = opsFor(kDirUp | kDirDown);
    ASSERT_EQ(3, static_cast<int>(ops.size()));
    EXPECT_EQ(RoadSprite::BASE_V, ops[0].sprite);
    EXPECT_EQ(RoadSprite::EDGE_V, ops[1].sprite);
    EXPECT_EQ(RoadSprite::EDGE_V, ops[2].sprite);
    // 水平直路（左+右）：上下描边（EDGE_H）
    ops = opsFor(kDirLeft | kDirRight);
    ASSERT_EQ(3, static_cast<int>(ops.size()));
    EXPECT_EQ(RoadSprite::BASE, ops[0].sprite);
    EXPECT_EQ(RoadSprite::EDGE_H, ops[1].sprite);
    EXPECT_EQ(RoadSprite::EDGE_H, ops[2].sprite);
}

// ── 死路端点按方向归直路（1 连接）─────────────────────────────
TEST(RoadCompositorTest, DeadEndTreatedAsStraight) {
    auto ops = opsFor(kDirUp);
    ASSERT_EQ(6, static_cast<int>(ops.size()));  // base_v + 3 边 + 2 角
    EXPECT_EQ(RoadSprite::BASE_V, ops[0].sprite);
    // 上方向有邻居（内部）→ 无上描边；其余三边描边 + 下两角
    for (int i = 1; i <= 3; ++i) {
        EXPECT_TRUE(ops[i].sprite == RoadSprite::EDGE_H || ops[i].sprite == RoadSprite::EDGE_V)
            << "i=" << i;
    }
    EXPECT_EQ(RoadSprite::CORNER_BL, ops[4].sprite);
    EXPECT_EQ(RoadSprite::CORNER_BR, ops[5].sprite);
}

// ── 十字中心装饰：2×2 格居中外溢，跟在主体后 ───────────────────
TEST(RoadCompositorTest, CrossCenterDecoration) {
    const auto ops = opsFor(kMaskAll);
    ASSERT_EQ(2, static_cast<int>(ops.size()));  // junction 主体 + 十字中心（无边无角）
    EXPECT_EQ(RoadSprite::JUNCTION, ops[0].sprite);
    EXPECT_EQ(RoadSprite::CROSS_CENTER, ops[1].sprite);
    EXPECT_EQ(-18, ops[1].x);  // -tileSize/2（外溢半格）
    EXPECT_EQ(-18, ops[1].y);
    EXPECT_EQ(72, ops[1].w);   // 2×tileSize
    EXPECT_EQ(72, ops[1].h);
}

// ── T 型路口：junction 主体 + 单侧描边（T 只有一侧无邻居，无转角）──
TEST(RoadCompositorTest, TJunctionComposite) {
    // T_UP（上+左+右，缺下）：仅下侧描边；转角需相邻两外缘，此处无
    const auto ops = opsFor(kDirUp | kDirLeft | kDirRight);
    ASSERT_EQ(2, static_cast<int>(ops.size()));
    EXPECT_EQ(RoadSprite::JUNCTION, ops[0].sprite);
    EXPECT_EQ(RoadSprite::EDGE_H, ops[1].sprite);
    EXPECT_EQ(36 - 9, ops[1].y);  // 贴下缘
}

// ── 顺序契约：主体恒为首，描边条先于转角件先于十字中心 ─────────
TEST(RoadCompositorTest, OpOrderContractForAllMasks) {
    auto rank = [](RoadSprite s) {
        if (s == RoadSprite::BASE || s == RoadSprite::BASE_V || s == RoadSprite::JUNCTION) return 0;
        if (s == RoadSprite::EDGE_H || s == RoadSprite::EDGE_V) return 1;
        if (s == RoadSprite::CROSS_CENTER) return 3;
        return 2;  // corners
    };
    for (int mask = 0; mask <= kMaskAll; ++mask) {
        const auto ops = opsFor(mask);
        ASSERT_FALSE(ops.empty());
        for (size_t i = 1; i < ops.size(); ++i) {
            EXPECT_LE(rank(ops[i - 1].sprite), rank(ops[i].sprite))
                << "mask=" << mask << " i=" << i;
        }
    }
}

// ── 几何有界：格内局部坐标不越出 [-tileSize/2, tileSize*3/2] ────
TEST(RoadCompositorTest, GeometryBoundedForAllMasks) {
    const int tileSize = 36;
    for (int mask = 0; mask <= kMaskAll; ++mask) {
        for (const auto& op : opsFor(mask, tileSize)) {
            EXPECT_GE(op.x, -tileSize / 2) << "mask=" << mask;
            EXPECT_GE(op.y, -tileSize / 2) << "mask=" << mask;
            EXPECT_LE(op.x + op.w, tileSize * 3 / 2) << "mask=" << mask;
            EXPECT_LE(op.y + op.h, tileSize * 3 / 2) << "mask=" << mask;
            EXPECT_GT(op.w, 0);
            EXPECT_GT(op.h, 0);
        }
    }
}

// ── 整型几何在 4 的倍数 tileSize 下与浮点一致（运行时 tileSize=36）──
TEST(RoadCompositorTest, IntegralGeometryMatchesFloatAtRuntimeTileSize) {
    // 运行时不变量：GameConfig.TILE_SIZE = 36（4 的倍数）——整型除法
    // 与 Vulkan 浮点表达式几何完全一致，无半像素漂移。
    constexpr int kRuntimeTileSize = 36;
    const float tileSizeF = static_cast<float>(kRuntimeTileSize);
    const float quarterF = tileSizeF * 0.25f;
    // 贴边位置：整型 tileSize - tileSize/4 == 浮点 tileSizeF - quarterF
    EXPECT_FLOAT_EQ(tileSizeF - quarterF,
                    static_cast<float>(kRuntimeTileSize - kRuntimeTileSize / 4));
    EXPECT_FLOAT_EQ(quarterF, static_cast<float>(kRuntimeTileSize / 4));
    // 十字中心偏移：整型 -tileSize/2 == 浮点 -tileSizeF*0.5f
    const auto cross = opsFor(kMaskAll, kRuntimeTileSize);
    EXPECT_FLOAT_EQ(-tileSizeF * 0.5f, static_cast<float>(cross[1].x));
    EXPECT_FLOAT_EQ(-tileSizeF * 0.5f, static_cast<float>(cross[1].y));
}

// ── 枚举序守护：RoadSprite 序号 = ROAD_RECTS 声明序（三端映射锚点）──
TEST(RoadCompositorTest, SpriteEnumOrderIsContractAnchor) {
    // 序号即 Kotlin SpriteAtlasDef.ROAD_RECTS 声明序 / roadUVMap 索引 /
    // RoadCompositorBridge.SPRITE_KEYS 下标（Kotlin 侧有同源守护测试）。
    EXPECT_EQ(0, static_cast<int>(RoadSprite::BASE));
    EXPECT_EQ(1, static_cast<int>(RoadSprite::BASE_V));
    EXPECT_EQ(2, static_cast<int>(RoadSprite::JUNCTION));
    EXPECT_EQ(3, static_cast<int>(RoadSprite::EDGE_H));
    EXPECT_EQ(4, static_cast<int>(RoadSprite::EDGE_V));
    EXPECT_EQ(5, static_cast<int>(RoadSprite::CORNER_TR));
    EXPECT_EQ(6, static_cast<int>(RoadSprite::CORNER_TL));
    EXPECT_EQ(7, static_cast<int>(RoadSprite::CORNER_BR));
    EXPECT_EQ(8, static_cast<int>(RoadSprite::CORNER_BL));
    EXPECT_EQ(9, static_cast<int>(RoadSprite::CROSS_CENTER));
    EXPECT_EQ(kRoadSpriteCount, 10);
    EXPECT_EQ(kMaxRoadDrawOpsPerTile, 10);
}

}  // namespace
}  // namespace gamecore
