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
using gamecore::map::roadEdgeLength;
using gamecore::map::roadEdgeWidth;
using gamecore::map::tileTypeForBitmask;

// ============================================================
// 道路渲染合成器测试（单一主体 + 直路免旋转 + 按方向边缘）
// 覆盖：全掩码产出量守恒、主体恒定、直路双边缘、T 中心缺侧边缘+内凹角、
//      转角外缘重叠+内凹角、孤格左右轴、十字内凹角、顺序契约、枚举序。
// ============================================================

namespace {

/// 便利封装：产出单格操作序列。
std::vector<RoadDrawOp> opsFor(int mask, int tileSize = 36) {
    std::vector<RoadDrawOp> out(kMaxRoadDrawOpsPerTile);
    const int n = emitRoadDrawOps(mask, tileSize, out.data());
    out.resize(n);
    return out;
}

bool isEdge(const RoadDrawOp& op) {
    return op.sprite == RoadSprite::EDGE_V || op.sprite == RoadSprite::EDGE_H;
}

}  // namespace

// ── 主体恒定：全 16 掩码首个操作 = BODY 铺满整格 ─────────────────
TEST(RoadCompositorTest, BodySpriteForAllMasks) {
    for (int mask = 0; mask <= kMaskAll; ++mask) {
        const auto ops = opsFor(mask);
        ASSERT_FALSE(ops.empty()) << "mask=" << mask;
        EXPECT_EQ(RoadSprite::BODY, ops[0].sprite) << "mask=" << mask;
        EXPECT_EQ(0, ops[0].x);
        EXPECT_EQ(0, ops[0].y);
        EXPECT_EQ(36, ops[0].w);
        EXPECT_EQ(36, ops[0].h);
    }
}

// ── 操作数守恒：孤格/直路/转角 5、T 3、十字 1 ────────────────────
TEST(RoadCompositorTest, OpCountConservationForAllMasks) {
    for (int mask = 0; mask <= kMaskAll; ++mask) {
        const auto ops = opsFor(mask);
        switch (tileTypeForBitmask(mask)) {
            case RoadTileType::SINGLE:
            case RoadTileType::VERTICAL:
            case RoadTileType::HORIZONTAL:
                EXPECT_EQ(5, static_cast<int>(ops.size())) << "mask=" << mask;
                break;
            case RoadTileType::CORNER_TOP_LEFT:
            case RoadTileType::CORNER_TOP_RIGHT:
            case RoadTileType::CORNER_BOTTOM_LEFT:
            case RoadTileType::CORNER_BOTTOM_RIGHT:
                EXPECT_EQ(6, static_cast<int>(ops.size())) << "mask=" << mask;
                break;
            case RoadTileType::T_UP:
            case RoadTileType::T_DOWN:
            case RoadTileType::T_RIGHT:
            case RoadTileType::T_LEFT:
                EXPECT_EQ(5, static_cast<int>(ops.size())) << "mask=" << mask;
                break;
            case RoadTileType::CROSS:
                EXPECT_EQ(5, static_cast<int>(ops.size())) << "mask=" << mask;
                break;
        }
        EXPECT_LE(ops.size(), static_cast<size_t>(kMaxRoadDrawOpsPerTile));
    }
}

// ── 直路边缘几何：1/6 厚 × 1/2 长，2 条/侧，只出横向（直行格）侧 ──
TEST(RoadCompositorTest, EdgeGeometry) {
    const int tileSize = 36;
    const int edgeW = roadEdgeWidth(tileSize);   // 6
    const int edgeL = roadEdgeLength(tileSize);  // 18
    EXPECT_EQ(6, edgeW);
    EXPECT_EQ(18, edgeL);

    // 竖直直路（上+下）：左右 EDGE_V，各 2 条（y=0 与 y=18）
    auto ops = opsFor(kDirUp | kDirDown, tileSize);
    ASSERT_EQ(5, static_cast<int>(ops.size()));
    EXPECT_EQ(RoadSprite::EDGE_V, ops[1].sprite);
    EXPECT_EQ(0, ops[1].x);
    EXPECT_EQ(0, ops[1].y);
    EXPECT_EQ(edgeW, ops[1].w);
    EXPECT_EQ(edgeL, ops[1].h);
    EXPECT_EQ(edgeL, ops[2].y);
    EXPECT_EQ(RoadSprite::EDGE_V, ops[3].sprite);
    EXPECT_EQ(tileSize - edgeW, ops[3].x);
    // 水平直路（左+右）：上下 EDGE_H，各 2 条（x=0 与 x=18）
    ops = opsFor(kDirLeft | kDirRight, tileSize);
    ASSERT_EQ(5, static_cast<int>(ops.size()));
    EXPECT_EQ(RoadSprite::EDGE_H, ops[1].sprite);
    EXPECT_EQ(0, ops[1].x);
    EXPECT_EQ(0, ops[1].y);
    EXPECT_EQ(edgeL, ops[1].w);
    EXPECT_EQ(edgeW, ops[1].h);
    EXPECT_EQ(edgeL, ops[2].x);
    EXPECT_EQ(RoadSprite::EDGE_H, ops[3].sprite);
    EXPECT_EQ(tileSize - edgeW, ops[3].y);
}

// ── 孤格：主体 + 左右轴 2 侧（每侧 2 条）共 5 ──────────────────
TEST(RoadCompositorTest, SingleCellFixedLR) {
    const auto ops = opsFor(0);
    ASSERT_EQ(5, static_cast<int>(ops.size()));
    EXPECT_EQ(RoadSprite::BODY, ops[0].sprite);
    EXPECT_EQ(RoadSprite::EDGE_V, ops[1].sprite);  // 左
    EXPECT_EQ(RoadSprite::EDGE_V, ops[2].sprite);
    EXPECT_EQ(RoadSprite::EDGE_V, ops[3].sprite);  // 右
    EXPECT_EQ(RoadSprite::EDGE_V, ops[4].sprite);
}

// ── T 中心：缺邻居侧 1 面（2 条）+ 分支基座两内凹角交汇块共 5 ──
TEST(RoadCompositorTest, TJunctionComposite) {
    // T_UP（上+左+右，缺下）：下侧 EDGE_H + 顶部左/右内凹角交汇块
    auto ops = opsFor(kDirUp | kDirLeft | kDirRight);
    ASSERT_EQ(5, static_cast<int>(ops.size()));
    EXPECT_EQ(RoadSprite::BODY, ops[0].sprite);
    EXPECT_EQ(RoadSprite::EDGE_H, ops[1].sprite);
    EXPECT_EQ(36 - 6, ops[1].y);  // 贴下缘（edgeW=6）
    EXPECT_EQ(RoadSprite::EDGE_V, ops[3].sprite);   // 左上交汇块
    EXPECT_EQ(0, ops[3].x);
    EXPECT_EQ(0, ops[3].y);
    EXPECT_EQ(RoadSprite::EDGE_V, ops[4].sprite);   // 右上交汇块
    EXPECT_EQ(30, ops[4].x);
    EXPECT_EQ(0, ops[4].y);
    // T_RIGHT（上+下+右，缺左）：左侧 EDGE_V + 右部上/下内凹角交汇块
    ops = opsFor(kDirUp | kDirDown | kDirRight);
    ASSERT_EQ(5, static_cast<int>(ops.size()));
    EXPECT_EQ(RoadSprite::EDGE_V, ops[1].sprite);
    EXPECT_EQ(0, ops[1].x);  // 贴左缘
    EXPECT_EQ(RoadSprite::EDGE_V, ops[3].sprite);   // 右上交汇块
    EXPECT_EQ(RoadSprite::EDGE_V, ops[4].sprite);   // 右下交汇块
}

// ── 转角：外缘两开放侧 2 条（重叠）+ 内凹角交汇块，全部在格内 ──
TEST(RoadCompositorTest, CornerEdgesStayInCell) {
    // CORNER_TOP_LEFT（上+左，外缘=下+右）：底部 EDGE_H + 右侧 EDGE_V + 左上内凹角交汇块
    const int tileSize = 36;
    const auto ops = opsFor(kDirUp | kDirLeft, tileSize);
    ASSERT_EQ(6, static_cast<int>(ops.size()));
    EXPECT_EQ(RoadSprite::BODY, ops[0].sprite);
    EXPECT_EQ(RoadSprite::EDGE_H, ops[1].sprite);   // 底
    EXPECT_EQ(RoadSprite::EDGE_H, ops[2].sprite);
    EXPECT_EQ(RoadSprite::EDGE_V, ops[3].sprite);   // 右
    EXPECT_EQ(RoadSprite::EDGE_V, ops[4].sprite);
    EXPECT_EQ(RoadSprite::EDGE_V, ops[5].sprite);   // 左上内凹角交汇块
    EXPECT_EQ(0, ops[5].x);
    EXPECT_EQ(0, ops[5].y);
    EXPECT_EQ(6, ops[5].w);
    EXPECT_EQ(6, ops[5].h);
    EXPECT_EQ(tileSize - roadEdgeWidth(tileSize), ops[3].x);  // 贴右缘
    EXPECT_EQ(0, ops[3].y);
    EXPECT_EQ(6, ops[3].w);
    EXPECT_EQ(18, ops[3].h);
    // 完全在格内：竖边末段底端 == tileSize（不再外溢补角）
    EXPECT_EQ(tileSize, ops[4].y + ops[4].h);
    // 全部边缘/交汇块均在格内（不溢出格界）
    for (const auto& op : ops) {
        EXPECT_GE(op.x, 0);
        EXPECT_GE(op.y, 0);
        EXPECT_LE(op.x + op.w, tileSize);
        EXPECT_LE(op.y + op.h, tileSize);
    }
}

// ── 十字中心：纯主体石板 + 四内凹角交汇块共 5 ─────────────────
TEST(RoadCompositorTest, CrossCenterJoins) {
    const auto ops = opsFor(kMaskAll);
    ASSERT_EQ(5, static_cast<int>(ops.size()));
    EXPECT_EQ(RoadSprite::BODY, ops[0].sprite);
    // 四个内凹角各一块 6×6 交汇块（使四条臂边缘在角部相交）
    for (int i = 1; i <= 4; ++i) {
        EXPECT_EQ(RoadSprite::EDGE_V, ops[i].sprite) << "i=" << i;
        EXPECT_EQ(6, ops[i].w) << "i=" << i;
        EXPECT_EQ(6, ops[i].h) << "i=" << i;
        EXPECT_GE(ops[i].x, 0);
        EXPECT_GE(ops[i].y, 0);
        EXPECT_LE(ops[i].x + ops[i].w, 36);
        EXPECT_LE(ops[i].y + ops[i].h, 36);
    }
}

// ── 顺序契约：主体恒为首，边缘条在后 ─────────────────────────
TEST(RoadCompositorTest, OpOrderContractForAllMasks) {
    for (int mask = 0; mask <= kMaskAll; ++mask) {
        const auto ops = opsFor(mask);
        ASSERT_FALSE(ops.empty());
        EXPECT_EQ(RoadSprite::BODY, ops[0].sprite) << "mask=" << mask;
        for (size_t i = 1; i < ops.size(); ++i) {
            EXPECT_TRUE(isEdge(ops[i])) << "mask=" << mask << " i=" << i;
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

// ── 枚举序守护：RoadSprite 序号 = ROAD_RECTS 声明序（三端映射锚点）──
TEST(RoadCompositorTest, SpriteEnumOrderIsContractAnchor) {
    // 序号即 Kotlin SpriteAtlasDef.ROAD_RECTS 声明序 / roadUVMap 索引 /
    // RoadCompositorBridge.SPRITE_KEYS 下标（Kotlin 侧有同源守护测试）。
    EXPECT_EQ(0, static_cast<int>(RoadSprite::BODY));
    EXPECT_EQ(1, static_cast<int>(RoadSprite::EDGE_V));
    EXPECT_EQ(2, static_cast<int>(RoadSprite::EDGE_H));
    EXPECT_EQ(kRoadSpriteCount, 3);
    EXPECT_EQ(kMaxRoadDrawOpsPerTile, 6);
}

// ── flipU 规则（2.4 边缘条方向修正）：左/上缘 flip=true（深色边朝外），
//    右/下缘与交汇块 flip=false；主体恒 false ─────────────────────────
TEST(RoadCompositorTest, FlipURuleForLeftTopEdges) {
    // 竖直直路（上+下）：左缘 2 条 flip=true，右缘 2 条 flip=false
    auto ops = opsFor(kDirUp | kDirDown);
    ASSERT_EQ(5, static_cast<int>(ops.size()));
    EXPECT_FALSE(ops[0].flipU);                       // 主体
    EXPECT_TRUE(ops[1].flipU);                        // 左缘上
    EXPECT_TRUE(ops[2].flipU);                        // 左缘下
    EXPECT_FALSE(ops[3].flipU);                       // 右缘上
    EXPECT_FALSE(ops[4].flipU);                       // 右缘下
    // 水平直路（左+右）：上缘 flip=true，下缘 flip=false
    ops = opsFor(kDirLeft | kDirRight);
    ASSERT_EQ(5, static_cast<int>(ops.size()));
    EXPECT_TRUE(ops[1].flipU);                        // 上缘左条
    EXPECT_TRUE(ops[2].flipU);                        // 上缘右条
    EXPECT_FALSE(ops[3].flipU);                       // 下缘左条
    EXPECT_FALSE(ops[4].flipU);                       // 下缘右条
}

// ── flipU：孤格左右轴 / T 中心 / 转角 / 十字全掩码规则 ────────────
TEST(RoadCompositorTest, FlipUForCornerAndJunctionCases) {
    // 孤格（mask=0）：左缘 flip=true、右缘 flip=false
    auto ops = opsFor(0);
    ASSERT_EQ(5, static_cast<int>(ops.size()));
    EXPECT_TRUE(ops[1].flipU);
    EXPECT_TRUE(ops[2].flipU);
    EXPECT_FALSE(ops[3].flipU);
    EXPECT_FALSE(ops[4].flipU);
    // T_RIGHT（上+下+右，缺左）：左缘面 flip=true，交汇块 false
    ops = opsFor(kDirUp | kDirDown | kDirRight);
    ASSERT_EQ(5, static_cast<int>(ops.size()));
    EXPECT_TRUE(ops[1].flipU);
    EXPECT_TRUE(ops[2].flipU);
    EXPECT_FALSE(ops[3].flipU);                       // 内凹角交汇块
    EXPECT_FALSE(ops[4].flipU);
    // T_DOWN（左+右+下，缺上）：上缘面 flip=true
    ops = opsFor(kDirLeft | kDirRight | kDirDown);
    ASSERT_EQ(5, static_cast<int>(ops.size()));
    EXPECT_TRUE(ops[1].flipU);
    EXPECT_TRUE(ops[2].flipU);
    EXPECT_FALSE(ops[3].flipU);
    EXPECT_FALSE(ops[4].flipU);
    // 转角 CORNER_TOP_RIGHT（邻上+右，外缘=下+左）：下缘 false + 左缘 true + join false
    ops = opsFor(kDirUp | kDirRight);
    ASSERT_EQ(6, static_cast<int>(ops.size()));
    EXPECT_FALSE(ops[1].flipU);                       // 下缘（EDGE_H）
    EXPECT_FALSE(ops[2].flipU);
    EXPECT_TRUE(ops[3].flipU);                        // 左缘（EDGE_V）
    EXPECT_TRUE(ops[4].flipU);
    EXPECT_FALSE(ops[5].flipU);                       // 内凹角交汇块
    // 十字中心：全部交汇块 flip=false
    ops = opsFor(kMaskAll);
    ASSERT_EQ(5, static_cast<int>(ops.size()));
    EXPECT_FALSE(ops[0].flipU);
    for (int i = 1; i <= 4; ++i) EXPECT_FALSE(ops[i].flipU) << "i=" << i;
}

}  // namespace
}  // namespace gamecore
