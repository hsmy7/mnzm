// ============================================================
// island_cliff_test.cpp — 浮空岛崖壁布局合成器 GTest
//
// 覆盖：三侧环 + 两角的锚定不变式、拼接连续性、末块裁剪与 UV 同步收缩、
// 镜像位、变体确定性/无相邻重复、纹理缺失降级、非法输入防御、尺寸自适应。
// 纹理尺寸表与生产同源（source-mapping.json 的 map_edge_* bake 产物尺寸）。
//
// ★ 池归属判据说明：生产配置中 LEFT 与 RIGHT 池引用**同一组变体**
//   （右侧靠镜像位复用左侧纹理），故**不能**用「纹理下标属于哪个池」区分左右环
//   ——本测试以**产出顺序 + 几何位置**判定池归属：产出序恒为
//   左环 → 右环 → 下环 → 左下角 → 右下角，左环全部 x+w ≤ 0、右环全部 x ≥ mapW。
// ============================================================
#include <gtest/gtest.h>
#include <vector>
#include "gamecore/map/island_cliff.h"

namespace {

using gamecore::map::IslandCliffConfig;
using gamecore::map::IslandCliffPiece;
using gamecore::map::computeIslandCliffLayout;
using gamecore::map::islandCliffMaxPieces;
using gamecore::map::kIslandCliffFlagMirrorX;

// ── 生产纹理尺寸表（与 build-edge-ktx.mjs / source-mapping.json 同源）──
// 0=left_1 1=left_2 2=left_3 3=bottom_1 4=bottom_2 5=corner_bl 6=corner_br
constexpr int32_t TEX_COUNT = 7;
constexpr float TEX_W[TEX_COUNT] = {1176, 1120, 1180, 2560, 2304, 1832, 1828};
constexpr float TEX_H[TEX_COUNT] = {3552, 3368, 3552, 1696, 1888, 2400, 2396};

// 池平铺表：LEFT={0,1,2} RIGHT={0,1,2}（同一组变体，靠镜像区分）
//          BOTTOM={3,4} CORNER_BL={5} CORNER_BR={6}
constexpr int32_t POOL_BASE[5] = {0, 3, 6, 8, 9};
constexpr int32_t POOL_COUNT[5] = {3, 3, 2, 1, 1};
constexpr int32_t POOL_FLAT[10] = {0, 1, 2, 0, 1, 2, 3, 4, 5, 6};

constexpr float MAP_W = 128 * 48.0f;  // 6144
constexpr float MAP_H = 128 * 48.0f;

IslandCliffConfig makeConfig(int32_t cols = 128, int32_t rows = 128, int32_t seed = 42) {
    IslandCliffConfig cfg;
    cfg.cols = cols;
    cfg.rows = rows;
    cfg.tileSize = 48;
    cfg.seed = seed;
    cfg.textureCount = TEX_COUNT;
    cfg.textureW = TEX_W;
    cfg.textureH = TEX_H;
    for (int32_t i = 0; i < 5; i++) {
        cfg.poolBase[i] = POOL_BASE[i];
        cfg.poolCountArr[i] = POOL_COUNT[i];
    }
    cfg.poolFlat = POOL_FLAT;
    return cfg;
}

std::vector<IslandCliffPiece> runLayout(int32_t cols = 128, int32_t rows = 128, int32_t seed = 42) {
    const IslandCliffConfig cfg = makeConfig(cols, rows, seed);
    std::vector<IslandCliffPiece> out(static_cast<size_t>(islandCliffMaxPieces(cfg)));
    const int32_t n = computeIslandCliffLayout(cfg, out.data(), static_cast<int32_t>(out.size()));
    out.resize(static_cast<size_t>(n));
    return out;
}

bool inPool(int32_t pool, int32_t tex) {
    for (int32_t i = 0; i < POOL_COUNT[pool]; i++) {
        if (POOL_FLAT[POOL_BASE[pool] + i] == tex) return true;
    }
    return false;
}

/** 按池切分产出。
 *  ★ 不能用几何位置切分：左下角位于 x = -cornerW（x+w ≤ 0），几何上会被误判为左环。
 *  ★ 不能用「纹理下标属于哪个池」区分左右环：生产配置中 LEFT/RIGHT 引用**同一组
 *    变体**（右侧靠镜像复用），inPool(0)/inPool(1) 恒同时命中。改用**镜像位**区分
 *    （左环 flags=0、右环 flags=镜像位），下环/两角再用纹理下标判别。 */
struct Split {
    std::vector<IslandCliffPiece> left, right, bottom, corners;
};
Split splitByPool(const std::vector<IslandCliffPiece>& v) {
    Split s;
    for (const auto& p : v) {
        if (inPool(2, p.texIdx)) { s.bottom.push_back(p); continue; }
        if (inPool(3, p.texIdx) || inPool(4, p.texIdx)) { s.corners.push_back(p); continue; }
        if ((p.flags & kIslandCliffFlagMirrorX) != 0) s.right.push_back(p);
        else s.left.push_back(p);
    }
    return s;
}

float uMin(const IslandCliffPiece& p) { return p.u0 < p.u1 ? p.u0 : p.u1; }
float uMax(const IslandCliffPiece& p) { return p.u0 < p.u1 ? p.u1 : p.u0; }

}  // namespace

// 生产地图 128×128：三侧环铺满 + 两角，各池条目数符合实测铺装数
TEST(IslandCliffTest, piecesCoverThreeSidesAtMap128) {
    const auto pieces = runLayout();
    const Split s = splitByPool(pieces);
    // 实测铺装（由纹理尺寸与地图尺寸推导）：
    //   左/右环：第一块 3552 + 第二块（截断至 6144-3552=2592）→ 2 块
    //   下环：铺装区间 [916, 5230] 宽 4314 → 2560 + 1754（末块截断）→ 2 块
    //   两角：各 1 块
    EXPECT_EQ(2u, s.left.size()) << "左环块数";
    EXPECT_EQ(2u, s.right.size()) << "右环块数";
    EXPECT_EQ(2u, s.bottom.size()) << "下环块数";
    EXPECT_EQ(2u, s.corners.size()) << "两角块数";
    EXPECT_EQ(8u, pieces.size());
}

// 左环：右缘贴 x=0、顶边贴 y=0、沿 +y 无缝隙连续、末块恰好止于 mapH
TEST(IslandCliffTest, leftRingAnchorsAndContinuity) {
    const auto pieces = runLayout();
    const Split s = splitByPool(pieces);
    ASSERT_EQ(2u, s.left.size());
    float acc = 0.0f;
    for (size_t i = 0; i < s.left.size(); i++) {
        const auto& p = s.left[i];
        EXPECT_NEAR(p.x + p.w, 0.0f, 0.001f) << "左环右缘未贴 x=0 (idx " << i << ")";
        EXPECT_NEAR(p.y, acc, 0.001f) << "左环 y 不连续 (idx " << i << ")";
        EXPECT_GT(p.w, 0.0f);
        EXPECT_GT(p.h, 0.0f);
        EXPECT_EQ(0, p.flags) << "左环不应带镜像位";
        acc += p.h;
    }
    EXPECT_NEAR(acc, MAP_H, 0.001f) << "左环未恰好铺满地图高度";
    // 末块被截断 → 高 < 纹理高，且 UV 的 v1 按截断比例收缩
    const auto& last = s.left.back();
    EXPECT_LT(last.h, TEX_H[last.texIdx]);
    EXPECT_NEAR(last.v1, last.h / TEX_H[last.texIdx], 0.001f) << "左环末块 UV 未随裁剪收缩";
}

// 右环：左缘贴 x=mapW、镜像位 = 1、u0 > u1（不依赖 u0<u1）、连续且止于 mapH
TEST(IslandCliffTest, rightRingMirroredAndAnchored) {
    const auto pieces = runLayout();
    const Split s = splitByPool(pieces);
    ASSERT_EQ(2u, s.right.size());
    float acc = 0.0f;
    for (size_t i = 0; i < s.right.size(); i++) {
        const auto& p = s.right[i];
        EXPECT_NEAR(p.x, MAP_W, 0.001f) << "右环左缘未贴 x=mapW (idx " << i << ")";
        EXPECT_NEAR(p.y, acc, 0.001f) << "右环 y 不连续 (idx " << i << ")";
        EXPECT_EQ(kIslandCliffFlagMirrorX, p.flags & kIslandCliffFlagMirrorX) << "右环缺镜像位";
        EXPECT_GT(p.u0, p.u1) << "右环 UV 未取镜像朝向（应 u0>u1）";
        EXPECT_NEAR(uMin(p), 0.0f, 0.001f);
        EXPECT_NEAR(uMax(p), 1.0f, 0.001f);
        acc += p.h;
    }
    EXPECT_NEAR(acc, MAP_H, 0.001f) << "右环未恰好铺满地图高度";
}

// 下环：顶边贴 y=mapH、沿 +x 无缝隙连续、末块 UV 随横截收缩、区间按角宽比例内缩
TEST(IslandCliffTest, bottomRingAnchorsAndContinuity) {
    const auto pieces = runLayout();
    const Split s = splitByPool(pieces);
    ASSERT_EQ(2u, s.bottom.size());
    float acc = -1.0f;
    for (size_t i = 0; i < s.bottom.size(); i++) {
        const auto& p = s.bottom[i];
        EXPECT_NEAR(p.y, MAP_H, 0.001f) << "下环顶边未贴 y=mapH (idx " << i << ")";
        if (acc >= 0.0f) EXPECT_NEAR(p.x, acc, 0.001f) << "下环 x 不连续 (idx " << i << ")";
        acc = p.x + p.w;
    }
    // 末块横截 → 宽 < 纹理宽，UV 的 u1 按比例收缩
    const auto& last = s.bottom.back();
    EXPECT_LT(last.w, TEX_W[last.texIdx]);
    EXPECT_NEAR(last.u1, last.w / TEX_W[last.texIdx], 0.001f) << "下环末块 UV 未随裁剪收缩";
    // 铺装区间：起点 = 左下角宽 × bottomStartRatio；终点 = mapW - 右下角宽 × bottomEndRatio
    const float startX = TEX_W[5] * 0.5f;
    const float endX = MAP_W - TEX_W[6] * 0.5f;
    EXPECT_NEAR(s.bottom.front().x, startX, 0.001f) << "下环起点未按 bottomStartRatio 内缩";
    EXPECT_NEAR(acc, endX, 0.001f) << "下环终点未按 bottomEndRatio 内缩";
}

// 两角：内缘贴地图角点、顶边贴 y=mapH；且绘制序在两环与下环之后（最后覆盖）
TEST(IslandCliffTest, cornersAnchorAndDrawnLast) {
    const auto pieces = runLayout();
    const Split s = splitByPool(pieces);
    ASSERT_EQ(2u, s.corners.size());
    // 产出序契约：左环 → 右环 → 下环 → 两角（用索引判定，不依赖纹理下标——
    // 左右环共享变体，按下标无法与角区分）
    const size_t ringCount = s.left.size() + s.right.size() + s.bottom.size();
    ASSERT_EQ(pieces.size(), ringCount + 2);
    const size_t blIdx = ringCount;
    const size_t brIdx = ringCount + 1;

    // 两环均在角之前
    for (size_t i = 0; i < ringCount; i++) {
        EXPECT_LT(i, blIdx) << "环条目未在两角之前";
    }
    // 左下角：内缘（右端）贴 x=0、顶边贴 y=mapH
    EXPECT_EQ(5, pieces[blIdx].texIdx) << "倒数第二块应为左下角";
    EXPECT_NEAR(pieces[blIdx].x + pieces[blIdx].w, 0.0f, 0.001f) << "左下角内缘未贴 x=0";
    EXPECT_NEAR(pieces[blIdx].y, MAP_H, 0.001f) << "左下角顶边未贴 y=mapH";
    EXPECT_EQ(0, pieces[blIdx].flags);
    // 右下角：内缘（左端）贴 x=mapW、顶边贴 y=mapH
    EXPECT_EQ(6, pieces[brIdx].texIdx) << "末块应为右下角";
    EXPECT_NEAR(pieces[brIdx].x, MAP_W, 0.001f) << "右下角内缘未贴 x=mapW";
    EXPECT_NEAR(pieces[brIdx].y, MAP_H, 0.001f) << "右下角顶边未贴 y=mapH";
    EXPECT_EQ(0, pieces[brIdx].flags);
}

// 裁剪与 UV 的严格一致性：每条目的 UV 跨度必须等于「绘制尺寸 / 纹理尺寸」。
//
// 这是双端渲染端真正依赖的契约（UV 跨度算错 → 素材被拉伸或采样到相邻内容），
// 也是"末块按比例收缩 UV、不拉伸素材"的可验证表述。
// ★ 不用「是否落在地图范围内」推断是否裁剪：下环的裁剪边界是**铺装区间终点**
//   （mapW − 角宽×比例），不是地图边界，落在 map 内也可能已被裁剪。
TEST(IslandCliffTest, pieceUvSpanMatchesDrawRatio) {
    const auto pieces = runLayout();
    ASSERT_FALSE(pieces.empty());
    for (size_t i = 0; i < pieces.size(); i++) {
        const auto& p = pieces[i];
        ASSERT_GE(p.texIdx, 0);
        ASSERT_LT(p.texIdx, TEX_COUNT);
        const float tw = TEX_W[p.texIdx];
        const float th = TEX_H[p.texIdx];

        // 不超过纹理尺寸（越界即采样越界）
        EXPECT_LE(p.w, tw + 0.001f) << "[" << i << "] 条目宽超过纹理宽";
        EXPECT_LE(p.h, th + 0.001f) << "[" << i << "] 条目高超过纹理高";

        // UV 跨度 ≡ 绘制尺寸比例（镜像位下 u0>u1，按区间长度算）
        const float uSpan = uMax(p) - uMin(p);
        const float vSpan = (p.v1 > p.v0 ? p.v1 - p.v0 : p.v0 - p.v1);
        EXPECT_NEAR(uSpan, p.w / tw, 0.001f)
            << "[" << i << "] UV 横向跨度与绘制宽/纹理宽不符（素材会被拉伸）";
        EXPECT_NEAR(vSpan, p.h / th, 0.001f)
            << "[" << i << "] UV 纵向跨度与绘制高/纹理高不符（素材会被拉伸）";

        // 未裁剪（跨度 = 1）时绘制尺寸必须 1:1
        if (uSpan > 0.999f) {
            EXPECT_NEAR(p.w, tw, 0.001f) << "[" << i << "] 横向未裁剪却非 1:1";
        }
        if (vSpan > 0.999f) {
            EXPECT_NEAR(p.h, th, 0.001f) << "[" << i << "] 纵向未裁剪却非 1:1";
        }
    }
}

// 变体确定性：同 seed 两次调用逐位一致；不同 seed 至少一处变体不同
TEST(IslandCliffTest, variantDeterminism) {
    const auto a = runLayout(128, 128, 7);
    const auto b = runLayout(128, 128, 7);
    ASSERT_EQ(a.size(), b.size());
    for (size_t i = 0; i < a.size(); i++) {
        EXPECT_EQ(a[i].texIdx, b[i].texIdx) << "同 seed 变体不一致 idx " << i;
        EXPECT_FLOAT_EQ(a[i].x, b[i].x);
        EXPECT_FLOAT_EQ(a[i].y, b[i].y);
        EXPECT_FLOAT_EQ(a[i].u0, b[i].u0);
        EXPECT_FLOAT_EQ(a[i].u1, b[i].u1);
    }
    // 多 seed 扫描：至少存在一个 seed 使变体序列与 seed=7 不同（防"随机"退化为常量）
    bool anyDifferent = false;
    for (int32_t s = 0; s < 16 && !anyDifferent; s++) {
        const auto c = runLayout(128, 128, s);
        ASSERT_EQ(a.size(), c.size());
        for (size_t i = 0; i < a.size(); i++) {
            if (a[i].texIdx != c[i].texIdx) { anyDifferent = true; break; }
        }
    }
    EXPECT_TRUE(anyDifferent) << "变体选择未随 seed 变化（随机性失效）";
}

// 变体无相邻重复：同侧连续两块不得为同一变体
TEST(IslandCliffTest, noAdjacentVariantRepeat) {
    for (int32_t seed = 0; seed < 32; seed++) {
        const auto pieces = runLayout(128, 128, seed);
        const Split s = splitByPool(pieces);
        for (size_t i = 1; i < s.left.size(); i++) {
            EXPECT_NE(s.left[i - 1].texIdx, s.left[i].texIdx) << "seed=" << seed << " 左环相邻变体重复";
        }
        for (size_t i = 1; i < s.right.size(); i++) {
            EXPECT_NE(s.right[i - 1].texIdx, s.right[i].texIdx) << "seed=" << seed << " 右环相邻变体重复";
        }
        for (size_t i = 1; i < s.bottom.size(); i++) {
            EXPECT_NE(s.bottom[i - 1].texIdx, s.bottom[i].texIdx) << "seed=" << seed << " 下环相邻变体重复";
        }
    }
}

// 纹理缺失降级：屏蔽某张纹理 → 仅引用它的条目消失，其余完整且不崩溃
//
// ★ 不变量是「单调减少 + 无被屏蔽纹理条目 + 仍部分可用」，而非固定条数：
//   左右环**共享变体**（同一组纹理），故屏蔽一张侧变体会同时影响左右两环，
//   减少条数取决于该变体在哪几处被选中 —— 断言具体条数会把
//   「变体选择策略」与「降级行为」耦合在一起（前者可变，后者不可变）。
TEST(IslandCliffTest, missingTextureDegradesGracefully) {
    const auto full = runLayout();
    for (int32_t masked = 0; masked < TEX_COUNT; masked++) {
        IslandCliffConfig cfg = makeConfig();
        cfg.textureMask = ~(1u << static_cast<uint32_t>(masked));
        std::vector<IslandCliffPiece> out(static_cast<size_t>(islandCliffMaxPieces(cfg)));
        const int32_t n = computeIslandCliffLayout(cfg, out.data(), static_cast<int32_t>(out.size()));
        ASSERT_GT(n, 0) << "屏蔽 tex=" << masked << " 后整层消失（应为部分降级）";
        out.resize(static_cast<size_t>(n));
        for (const auto& p : out) {
            EXPECT_NE(masked, p.texIdx) << "被屏蔽纹理仍产出条目 tex=" << masked;
        }
        EXPECT_LE(out.size(), full.size()) << "屏蔽 tex=" << masked << " 后条目反而增多";
        // 两角有各自独立纹理：屏蔽非角纹理时两角必须在场
        if (masked != 5 && masked != 6) {
            const Split s = splitByPool(out);
            EXPECT_EQ(2u, s.corners.size()) << "屏蔽 tex=" << masked << " 不应影响两角";
        }
    }
    // 全屏蔽 → 0 条（不产出任何内容，也不崩溃）
    IslandCliffConfig none = makeConfig();
    none.textureMask = 0u;
    std::vector<IslandCliffPiece> out(static_cast<size_t>(islandCliffMaxPieces(none)));
    EXPECT_EQ(0, computeIslandCliffLayout(none, out.data(), static_cast<int32_t>(out.size())));
}

// 非法输入防御
TEST(IslandCliffTest, invalidInputReturnsZero) {
    const IslandCliffConfig good = makeConfig();
    std::vector<IslandCliffPiece> out(static_cast<size_t>(islandCliffMaxPieces(good)));
    const int32_t cap = static_cast<int32_t>(out.size());

    EXPECT_EQ(0, computeIslandCliffLayout(good, nullptr, cap)) << "空输出缓冲";
    EXPECT_EQ(0, computeIslandCliffLayout(good, out.data(), 0)) << "零容量";

    IslandCliffConfig c = good; c.cols = 0;
    EXPECT_EQ(0, computeIslandCliffLayout(c, out.data(), cap)) << "cols=0";
    EXPECT_EQ(0, islandCliffMaxPieces(c)) << "cols=0 容量应为 0";
    c = good; c.rows = 0;
    EXPECT_EQ(0, computeIslandCliffLayout(c, out.data(), cap)) << "rows=0";
    EXPECT_EQ(0, islandCliffMaxPieces(c)) << "rows=0 容量应为 0";
    c = good; c.tileSize = 0;
    EXPECT_EQ(0, computeIslandCliffLayout(c, out.data(), cap)) << "tileSize=0";
    EXPECT_EQ(0, islandCliffMaxPieces(c)) << "tileSize=0 容量应为 0";
    c = good; c.textureW = nullptr;
    EXPECT_EQ(0, computeIslandCliffLayout(c, out.data(), cap)) << "textureW=null";
    c = good; c.textureH = nullptr;
    EXPECT_EQ(0, computeIslandCliffLayout(c, out.data(), cap)) << "textureH=null";
    c = good; c.poolFlat = nullptr;
    EXPECT_EQ(0, computeIslandCliffLayout(c, out.data(), cap)) << "poolFlat=null";
    c = good; c.textureCount = 0;
    EXPECT_EQ(0, computeIslandCliffLayout(c, out.data(), cap)) << "textureCount=0";
}

// 尺寸自适应：容量上界足够，且小/大/极端地图都不越界、结构不变式恒成立
TEST(IslandCliffTest, scalesAcrossMapSizes) {
    for (const int32_t cells : {1, 2, 4, 20, 64, 128, 300}) {
        const auto pieces = runLayout(cells, cells, 5);
        const IslandCliffConfig cfg = makeConfig(cells, cells, 5);
        EXPECT_LE(static_cast<int32_t>(pieces.size()), islandCliffMaxPieces(cfg))
            << "容量上界不足 cells=" << cells;
        const float H = cells * 48.0f;
        const float W = cells * 48.0f;
        const Split s = splitByPool(pieces);
        float acc = 0.0f;
        for (const auto& p : s.left) { EXPECT_NEAR(p.y, acc, 0.01f); acc += p.h; }
        EXPECT_NEAR(acc, H, 0.01f) << "cells=" << cells << " 左环未铺满";
        for (const auto& p : pieces) {
            EXPECT_GT(p.w, 0.0f);
            EXPECT_GT(p.h, 0.0f);
        }
        for (const auto& p : s.left) EXPECT_LE(p.x + p.w, 0.001f);
        for (const auto& p : s.right) EXPECT_GE(p.x, W - 0.001f);
        for (const auto& p : s.bottom) EXPECT_GE(p.y, H - 0.001f);
        for (const auto& p : s.corners) EXPECT_GE(p.y, H - 0.001f);
    }
}

// topInset 生效：调大后侧环整体下移，但铺满高度不变（观感微调点可用）
TEST(IslandCliffTest, topInsetShiftsRings) {
    IslandCliffConfig cfg = makeConfig();
    cfg.topInset = 48.0f;
    std::vector<IslandCliffPiece> out(static_cast<size_t>(islandCliffMaxPieces(cfg)));
    const int32_t n = computeIslandCliffLayout(cfg, out.data(), static_cast<int32_t>(out.size()));
    ASSERT_GT(n, 0);
    out.resize(static_cast<size_t>(n));
    const Split s = splitByPool(out);
    ASSERT_EQ(2u, s.left.size());
    float acc = 0.0f;
    for (const auto& p : s.left) {
        EXPECT_NEAR(p.y, acc + 48.0f, 0.001f) << "topInset 未生效";
        acc += p.h;
    }
    EXPECT_NEAR(acc, MAP_H, 0.001f) << "topInset 不应改变铺满总高";
    // 转角不受 topInset 影响（仍贴 y=mapH）
    for (const auto& p : s.corners) EXPECT_NEAR(p.y, MAP_H, 0.001f);
}
