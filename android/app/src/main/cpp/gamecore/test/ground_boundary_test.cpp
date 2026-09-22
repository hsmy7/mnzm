#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "gamecore/map/ground_boundary.h"

namespace gamecore {
namespace {

using gamecore::map::GroundBoundaryConfig;
using gamecore::map::kBottomDepthMaxScale;
using gamecore::map::kBottomDepthMinScale;
using gamecore::map::kBottomMeshRowCount;
using gamecore::map::kBottomTexRepeatPx;
using gamecore::map::kBottomTuckPx;
using gamecore::map::kGateApronX0;
using gamecore::map::kGateApronX1;
using gamecore::map::kGateApronY;
using gamecore::map::kGroundBoundaryHeaderFloats;
using gamecore::map::kGroundBoundaryVersion;
using gamecore::map::kGroundMaxInset;
using gamecore::map::kGroundMaxOutset;
using gamecore::map::buildBottomMesh;
using gamecore::map::buildGroundMesh;
using gamecore::map::buildGroundTileMask;
using gamecore::map::computeGroundBoundary;
using gamecore::map::groundBottomDepthScale;
using gamecore::map::groundPointInPolygon;
using gamecore::map::sampleGroundControlPolygon;
using gamecore::map::triangulateGroundPolygon;

// ============================================================
// 弯曲地皮轮廓合成器测试（旧 island_cliff 崖壁系统的替代者）
// 覆盖：采样确定性、振幅不变式（±kGroundMaxOutset/Inset 带）、自交零、
//      可建矩形包含（九处建筑/道路校验零改动的根基）、门楼缓冲带贴边、
//      三角化有效性（面积守恒 + 顶点界）、地皮 UV 口径、底部 mesh 顶边
//      与折线共数据 + 深度剖面值域 + 纵向细分行结构 + UV 连续 + 确定性、
//      逐格掩码（可建格恒 bit0=1）、复合布局。
// ============================================================

constexpr int32_t kCols = 128;
constexpr int32_t kRows = 128;
constexpr int32_t kTile = 48;

/// 标准配置（与生产一致）
GroundBoundaryConfig stdCfg() {
    GroundBoundaryConfig cfg;
    cfg.cols = kCols;
    cfg.rows = kRows;
    cfg.tileSize = kTile;
    cfg.bottomDepth = 768.0f;
    return cfg;
}

/// 采样折线（世界像素）
std::vector<float> stdPoly() {
    std::vector<float> poly;
    sampleGroundControlPolygon(stdCfg(), poly);
    return poly;
}

// ── 采样确定性：两次采样逐位一致 ─────────────────────────────────
TEST(GroundBoundaryTest, SamplingDeterministic) {
    const auto a = stdPoly();
    const auto b = stdPoly();
    ASSERT_EQ(a.size(), b.size());
    EXPECT_EQ(26 * 20 * 2u, a.size());
    for (size_t i = 0; i < a.size(); ++i) {
        ASSERT_FLOAT_EQ(a[i], b[i]) << "i=" << i;
    }
}

// ── 振幅不变式：折线全部落在矩形外扩带内，且每点至少「贴」四边之一 ──
//（法向偏移 ≤ kGroundMaxInset——不越过树环吃进可建区；切向坐标可自由
// 沿边走，故按「四边带之并集」判定而非两轴同时收紧）
TEST(GroundBoundaryTest, AmplitudeWithinBand) {
    const auto poly = stdPoly();
    const float w = static_cast<float>(kCols * kTile);
    const float h = static_cast<float>(kRows * kTile);
    const float outMax = kGroundMaxOutset + 1.0e-4f;
    const float inMax = kGroundMaxInset + 1.0e-4f;
    for (size_t i = 0; i + 1 < poly.size(); i += 2) {
        const float nx = poly[i] / w;
        const float ny = poly[i + 1] / h;
        ASSERT_GE(nx, -outMax) << "i=" << i;
        ASSERT_LE(nx, 1.0f + outMax) << "i=" << i;
        ASSERT_GE(ny, -outMax) << "i=" << i;
        ASSERT_LE(ny, 1.0f + outMax) << "i=" << i;
        const bool nearLeft = nx <= inMax;
        const bool nearRight = nx >= 1.0f - inMax;
        const bool nearTop = ny <= inMax;
        const bool nearBottom = ny >= 1.0f - inMax;
        ASSERT_TRUE(nearLeft || nearRight || nearTop || nearBottom)
            << "i=" << i << " off-edge (" << nx << "," << ny << ")";
    }
}

// ── 简单多边形：非相邻边零自交 ───────────────────────────────────
TEST(GroundBoundaryTest, NoSelfIntersection) {
    const auto poly = stdPoly();
    const size_t n = poly.size() / 2;
    auto seg = [&](size_t i, float& ax, float& ay, float& bx, float& by) {
        ax = poly[i * 2];
        ay = poly[i * 2 + 1];
        bx = poly[((i + 1) % n) * 2];
        by = poly[((i + 1) % n) * 2 + 1];
    };
    auto ccw = [](float ax, float ay, float bx, float by, float cx, float cy) {
        return (cy - ay) * (bx - ax) > (by - ay) * (cx - ax);
    };
    for (size_t i = 0; i < n; ++i) {
        float ax, ay, bx, by;
        seg(i, ax, ay, bx, by);
        for (size_t j = i + 2; j < n; ++j) {
            if (i == 0 && j == n - 1) continue;  // 首末相邻
            float cx, cy, dx, dy;
            seg(j, cx, cy, dx, dy);
            const bool intersect =
                ccw(ax, ay, cx, cy, dx, dy) != ccw(bx, by, cx, cy, dx, dy) &&
                ccw(ax, ay, bx, by, cx, cy) != ccw(ax, ay, bx, by, dx, dy);
            ASSERT_FALSE(intersect) << "segs " << i << "×" << j;
        }
    }
}

// ── 可建矩形包含：全部可建格四角 + 边界采样点在轮廓内 ─────────────
// （九处建筑/道路矩形校验与读档自愈零改动的硬前提）
TEST(GroundBoundaryTest, BuildableRectFullyInside) {
    const auto poly = stdPoly();
    const int32_t ring = 3;  // GameConfig.SectMap.BORDER_TREE_RING
    const float t = static_cast<float>(kTile);
    const float x0 = static_cast<float>(ring) * t;
    const float y0 = static_cast<float>(ring) * t;
    const float x1 = static_cast<float>(kCols - ring) * t;
    const float y1 = static_cast<float>(kRows - ring) * t;
    // 矩形四边每 12px 采样 + 四角
    auto check = [&](float x, float y) {
        ASSERT_TRUE(groundPointInPolygon(x, y, poly))
            << "(" << x << "," << y << ")";
    };
    for (float x = x0; x <= x1; x += 12.0f) {
        check(x, y0);
        check(x, y1);
    }
    for (float y = y0; y <= y1; y += 12.0f) {
        check(x0, y);
        check(x1, y);
    }
    // 全部可建格四角（离散覆盖任一建筑矩形的角点）
    for (int32_t row = ring; row < kRows - ring; ++row) {
        for (int32_t col = ring; col < kCols - ring; ++col) {
            const float px = static_cast<float>(col) * t;
            const float py = static_cast<float>(row) * t;
            check(px, py);
            check(px + t, py);
            check(px, py + t);
            check(px + t, py + t);
        }
    }
}

// ── 门楼缓冲带：清场区（±1 列）x 范围内下边界 ≥ 地图底边（贴边在外）──
TEST(GroundBoundaryTest, GateApronPinnedBelowMapBottom) {
    const auto poly = stdPoly();
    const float w = static_cast<float>(kCols * kTile);
    const float h = static_cast<float>(kRows * kTile);
    // 门楼格（x∈[GATE_X, GATE_X+6]，底 = 地图底边）与清场 ±1 列的四角必须在轮廓内
    const float t = static_cast<float>(kTile);
    const int32_t gateX0 = (kCols - 6) / 2 - 1;   // GATE_X−1（清场起点）
    const int32_t gateX1 = (kCols - 6) / 2 + 6 + 1;  // 清场终点（不含）
    for (int32_t col = gateX0; col < gateX1; ++col) {
        for (int32_t row = kRows - 2; row < kRows; ++row) {
            const float px = static_cast<float>(col) * t;
            const float py = static_cast<float>(row) * t;
            ASSERT_TRUE(groundPointInPolygon(px, py, poly));
            ASSERT_TRUE(groundPointInPolygon(px + t, py, poly));
            ASSERT_TRUE(groundPointInPolygon(px, py + t, poly));
            ASSERT_TRUE(groundPointInPolygon(px + t, py + t, poly));
        }
    }
    // 归一化口径：kGateApron 常量与门楼清场区一致（漂移即红）
    ASSERT_LE(kGateApronX0, static_cast<float>(gateX0) * t / w);
    ASSERT_GE(kGateApronX1, static_cast<float>(gateX1) * t / w);
    ASSERT_GE(kGateApronY, 1.0f + 1.0e-3f);
    (void)h;
}

// ── 三角化有效性：索引界 / 面积守恒 / 输出量 ─────────────────────
TEST(GroundBoundaryTest, TriangulationValid) {
    const auto poly = stdPoly();
    const size_t n = poly.size() / 2;
    std::vector<int32_t> idx;
    triangulateGroundPolygon(poly, idx);
    ASSERT_EQ(idx.size(), (n - 2) * 3u);
    for (int32_t i : idx) {
        ASSERT_GE(i, 0);
        ASSERT_LT(static_cast<size_t>(i), n);
    }
    // 三角形有向面积和 = 多边形有向面积（允许 1% 浮点容差）
    double triArea2 = 0.0;
    for (size_t k = 0; k + 2 < idx.size(); k += 3) {
        const float* a = &poly[static_cast<size_t>(idx[k]) * 2];
        const float* b = &poly[static_cast<size_t>(idx[k + 1]) * 2];
        const float* c = &poly[static_cast<size_t>(idx[k + 2]) * 2];
        triArea2 += static_cast<double>(a[0]) * (b[1] - c[1]) +
                    static_cast<double>(b[0]) * (c[1] - a[1]) +
                    static_cast<double>(c[0]) * (a[1] - b[1]);
    }
    double polyArea2 = 0.0;
    for (size_t i = 0; i < n; ++i) {
        const size_t j = (i + 1) % n;
        polyArea2 += static_cast<double>(poly[i * 2]) * poly[j * 2 + 1] -
                     static_cast<double>(poly[j * 2]) * poly[i * 2 + 1];
    }
    EXPECT_NEAR(std::fabs(triArea2), std::fabs(polyArea2),
                std::fabs(polyArea2) * 0.01)
        << "tri=" << triArea2 << " poly=" << polyArea2;
}

// ── 地皮 mesh：顶点布局/UV 口径/白顶点色 ─────────────────────────
TEST(GroundBoundaryTest, GroundMeshLayoutAndUv) {
    const auto cfg = stdCfg();
    const auto poly = stdPoly();
    std::vector<float> mesh;
    buildGroundMesh(cfg, poly, mesh);
    ASSERT_EQ(mesh.size() % 8, 0u);
    ASSERT_GT(mesh.size(), 0u);
    const float t = static_cast<float>(kTile);
    for (size_t v = 0; v + 7 < mesh.size(); v += 8) {
        EXPECT_FLOAT_EQ(mesh[v] / t, mesh[v + 2]);   // u = x/tileSize
        EXPECT_FLOAT_EQ(mesh[v + 1] / t, mesh[v + 3]);  // v = y/tileSize
        EXPECT_FLOAT_EQ(1.0f, mesh[v + 4]);
        EXPECT_FLOAT_EQ(1.0f, mesh[v + 7]);
        ASSERT_TRUE(std::isfinite(mesh[v]) && std::isfinite(mesh[v + 1]));
    }
}

// ── 底部 mesh 辅助：重算朝下段集合（与 buildBottomMesh 同口径）────
struct DownSeg {
    size_t i;
    size_t j;
};

std::vector<DownSeg> downSegsOf(const std::vector<float>& poly) {
    const size_t n = poly.size() / 2;
    float cx = 0.0f, cy = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        cx += poly[i * 2];
        cy += poly[i * 2 + 1];
    }
    cx /= static_cast<float>(n);
    cy /= static_cast<float>(n);
    std::vector<DownSeg> segs;
    for (size_t i = 0; i < n; ++i) {
        const size_t j = (i + 1) % n;
        const float ax = poly[i * 2], ay = poly[i * 2 + 1];
        const float bx = poly[j * 2], by = poly[j * 2 + 1];
        const float dx = bx - ax, dy = by - ay;
        const float len = std::sqrt(dx * dx + dy * dy);
        if (len < 1.0e-6f) continue;
        float nx = dy / len;
        float ny = -dx / len;
        const float mx = (ax + bx) * 0.5f - cx;
        const float my = (ay + by) * 0.5f - cy;
        if (nx * mx + ny * my < 0.0f) { nx = -nx; ny = -ny; }
        if (ny > 0.0f) segs.push_back({i, j});
    }
    return segs;
}

/// 折线累计弧长（与 buildBottomMesh 同式；arc[0] = 周长）
std::vector<float> arcOf(const std::vector<float>& poly) {
    const size_t n = poly.size() / 2;
    std::vector<float> arc(n, 0.0f);
    for (size_t i = 1; i <= n; ++i) {
        const size_t a = i - 1;
        const size_t b = i % n;
        const float dx = poly[b * 2] - poly[a * 2];
        const float dy = poly[b * 2 + 1] - poly[a * 2 + 1];
        arc[i % n] = arc[a] + std::sqrt(dx * dx + dy * dy);
    }
    return arc;
}

// ── 底部 mesh：顶边与折线共数据 / 行结构 / 深度剖面 / UV 连续 ────
TEST(GroundBoundaryTest, BottomMeshSharesBoundaryRowsAndUvContinuous) {
    const auto cfg = stdCfg();
    const auto poly = stdPoly();
    const size_t n = poly.size() / 2;
    const auto arc = arcOf(poly);
    const float perimeter = arc[0];
    std::vector<float> colDepth(n);
    for (size_t i = 0; i < n; ++i) {
        colDepth[i] = cfg.bottomDepth * groundBottomDepthScale(arc[i] / perimeter);
        // 剖面值域：[0.6, 1.0]×最大深度（用户拍板「中等」档）
        ASSERT_GE(colDepth[i], kBottomDepthMinScale * cfg.bottomDepth - 0.5f) << "i=" << i;
        ASSERT_LE(colDepth[i], kBottomDepthMaxScale * cfg.bottomDepth + 0.5f) << "i=" << i;
    }
    const auto segs = downSegsOf(poly);
    ASSERT_GT(segs.size(), 0u);

    std::vector<float> mesh;
    buildBottomMesh(cfg, poly, mesh);
    ASSERT_EQ(mesh.size() % 8, 0u);
    ASSERT_GT(mesh.size(), 0u);
    const size_t vertCount = mesh.size() / 8;
    // 行结构：朝下段 × kBottomMeshRowCount 行 × 6 顶点（整 quad 三角对）
    ASSERT_EQ(vertCount, segs.size() * static_cast<size_t>(kBottomMeshRowCount) * 6u);
    ASSERT_EQ(vertCount % 6, 0u);

    // 按构造序逐 quad 逐顶点对照：quad 序 = 朝下段序 × 行序；每 quad 6 顶点
    // [(A_top)(B_top)(A_bot)(B_top)(B_bot)(A_bot)]，y = py + colDepth·fr − tuck，
    // u = arc[i]/repeat、v = colDepth·fr/repeat（A=段起点 i、B=段终点 j）。
    const float rowsF = static_cast<float>(kBottomMeshRowCount);
    size_t topRowVerts = 0;
    for (size_t k = 0; k < segs.size(); ++k) {
        const size_t i = segs[k].i;
        const size_t j = segs[k].j;
        const float ax = poly[i * 2], ay = poly[i * 2 + 1];
        const float bx = poly[j * 2], by = poly[j * 2 + 1];
        const float uA = arc[i] / kBottomTexRepeatPx;
        const float uB = arc[j] / kBottomTexRepeatPx;
        const float dA = colDepth[i];
        const float dB = colDepth[j];
        for (int32_t r = 0; r < kBottomMeshRowCount; ++r) {
            const float fr0 = static_cast<float>(r) / rowsF;
            const float fr1 = static_cast<float>(r + 1) / rowsF;
            const float exp[6][4] = {
                { ax, ay + dA * fr0 - kBottomTuckPx, uA, dA * fr0 / kBottomTexRepeatPx },
                { bx, by + dB * fr0 - kBottomTuckPx, uB, dB * fr0 / kBottomTexRepeatPx },
                { ax, ay + dA * fr1 - kBottomTuckPx, uA, dA * fr1 / kBottomTexRepeatPx },
                { bx, by + dB * fr0 - kBottomTuckPx, uB, dB * fr0 / kBottomTexRepeatPx },
                { bx, by + dB * fr1 - kBottomTuckPx, uB, dB * fr1 / kBottomTexRepeatPx },
                { ax, ay + dA * fr1 - kBottomTuckPx, uA, dA * fr1 / kBottomTexRepeatPx },
            };
            for (int32_t m = 0; m < 6; ++m) {
                const size_t vIdx = (k * static_cast<size_t>(kBottomMeshRowCount) +
                                     static_cast<size_t>(r)) * 6 + static_cast<size_t>(m);
                const float* p = &mesh[vIdx * 8];
                const float* e = exp[m];
                ASSERT_TRUE(std::isfinite(p[0]) && std::isfinite(p[1]));
                EXPECT_FLOAT_EQ(p[0], e[0]) << "seg " << k << " row " << r << " m " << m;
                EXPECT_FLOAT_EQ(p[1], e[1]) << "seg " << k << " row " << r << " m " << m;
                EXPECT_NEAR(p[2], e[2], 1.0e-4f) << "seg " << k << " row " << r << " m " << m;
                EXPECT_NEAR(p[3], e[3], 1.0e-4f) << "seg " << k << " row " << r << " m " << m;
                if (r == 0 && (m == 0 || m == 1)) ++topRowVerts;
            }
        }
    }
    // 顶行（fr=0，与草皮共边藏缝）顶点 = 每朝下段 2 个
    ASSERT_EQ(topRowVerts, segs.size() * 2u);

    // UV 连续：同位置的共享端点（跨 quad/跨行）UV 全等 ⇒ 岩石纹理无缝
    std::vector<std::pair<float, float>> uv;
    std::vector<std::pair<float, float>> pos;
    for (size_t v = 0; v < vertCount; ++v) {
        const float* p = &mesh[v * 8];
        for (size_t k = 0; k < pos.size(); ++k) {
            if (std::fabs(pos[k].first - p[0]) < 1.0e-3f &&
                std::fabs(pos[k].second - p[1]) < 1.0e-3f) {
                EXPECT_FLOAT_EQ(uv[k].first, p[2]) << "vert " << v << " shared u";
                EXPECT_FLOAT_EQ(uv[k].second, p[3]) << "vert " << v << " shared v";
                break;
            }
        }
        pos.push_back({p[0], p[1]});
        uv.push_back({p[2], p[3]});
    }
}

// ── 底部 mesh：底缘自然不规则（深度非全等）且确定性逐位一致 ──────
TEST(GroundBoundaryTest, BottomMeshVariesNaturallyAndDeterministic) {
    const auto cfg = stdCfg();
    const auto poly = stdPoly();
    const size_t n = poly.size() / 2;
    const auto arc = arcOf(poly);
    const float perimeter = arc[0];
    std::vector<float> colDepth(n);
    for (size_t i = 0; i < n; ++i) {
        colDepth[i] = cfg.bottomDepth * groundBottomDepthScale(arc[i] / perimeter);
    }
    // 非平行带：列深最大差显著（> 48px = 一格），且至少 16 个 1px 量化档
    float lo = 3.0e38f, hi = -3.0e38f;
    for (float d : colDepth) {
        lo = std::fmin(lo, d);
        hi = std::fmax(hi, d);
    }
    EXPECT_GT(hi - lo, 48.0f) << "depth profile flat";
    std::vector<int32_t> quanta;
    for (float d : colDepth) {
        quanta.push_back(static_cast<int32_t>(d));
    }
    std::sort(quanta.begin(), quanta.end());
    quanta.erase(std::unique(quanta.begin(), quanta.end()), quanta.end());
    EXPECT_GE(static_cast<int32_t>(quanta.size()), 16) << "depth profile not natural";

    // 底缘不得高于顶缘（行深 > 0）——逐 quad 行高校验放在共享测试，此处查底行
    std::vector<float> meshA;
    std::vector<float> meshB;
    buildBottomMesh(cfg, poly, meshA);
    buildBottomMesh(cfg, poly, meshB);
    ASSERT_EQ(meshA.size(), meshB.size());
    for (size_t i = 0; i < meshA.size(); ++i) {
        ASSERT_FLOAT_EQ(meshA[i], meshB[i]) << "i=" << i;  // 确定性：逐位一致
    }
}

// ── 逐格掩码：可建格恒 bit0=1，边界带有 0 有 3 ───────────────────
TEST(GroundBoundaryTest, TileMaskCoversBuildableArea) {
    const auto cfg = stdCfg();
    const auto poly = stdPoly();
    std::vector<uint8_t> mask;
    buildGroundTileMask(cfg, poly, mask);
    ASSERT_EQ(mask.size(), static_cast<size_t>(kCols) * kRows);
    const int32_t ring = 3;
    for (int32_t row = ring; row < kRows - ring; ++row) {
        for (int32_t col = ring; col < kCols - ring; ++col) {
            ASSERT_EQ(1u, mask[static_cast<size_t>(row) * kCols + col] & 1u)
                << "cell (" << col << "," << row << ") lost base bit";
        }
    }
    // 边界树环带：既有 0（轮廓外）也有 3（轮廓内）——轮廓确实改变边界
    int zeros = 0, fulls = 0;
    for (int32_t row = 0; row < kRows; ++row) {
        for (int32_t col = 0; col < kCols; ++col) {
            const bool border = row < ring || col < ring ||
                                row >= kRows - ring || col >= kCols - ring;
            if (!border) continue;
            const uint8_t m = mask[static_cast<size_t>(row) * kCols + col];
            if (m == 0) ++zeros;
            if (m == 3) ++fulls;
        }
    }
    EXPECT_GT(zeros, 0);
    EXPECT_GT(fulls, 0);
}

// ── 复合布局：头部/段偏移/尺寸自洽 ───────────────────────────────
TEST(GroundBoundaryTest, CompositeLayoutConsistent) {
    const auto cfg = stdCfg();
    std::vector<float> out;
    computeGroundBoundary(cfg, out);
    ASSERT_GT(out.size(), static_cast<size_t>(kGroundBoundaryHeaderFloats));
    EXPECT_FLOAT_EQ(static_cast<float>(kGroundBoundaryVersion), out[0]);
    const int32_t polyCount = static_cast<int32_t>(out[1]);
    EXPECT_EQ(static_cast<float>(cfg.cols), out[2]);
    EXPECT_EQ(static_cast<float>(cfg.rows), out[3]);
    EXPECT_EQ(static_cast<float>(cfg.tileSize), out[4]);
    EXPECT_FLOAT_EQ(cfg.bottomDepth, out[5]);
    const size_t maskOffset = static_cast<size_t>(out[6]);
    const size_t groundOffset = static_cast<size_t>(out[7]);
    const size_t groundCount = static_cast<size_t>(out[8]);
    const size_t bottomOffset = static_cast<size_t>(out[9]);
    const size_t bottomCount = static_cast<size_t>(out[10]);
    EXPECT_EQ(static_cast<size_t>(kGroundBoundaryHeaderFloats) +
                  static_cast<size_t>(polyCount) * 2, maskOffset);
    EXPECT_EQ(maskOffset + static_cast<size_t>(cfg.cols) * cfg.rows, groundOffset);
    EXPECT_EQ(groundOffset + groundCount, bottomOffset);
    EXPECT_EQ(bottomOffset + bottomCount, out.size());
    EXPECT_EQ(groundCount % 8, 0u);
    EXPECT_EQ(bottomCount % 8, 0u);
    EXPECT_GT(bottomCount, 0u);
    // 折线段与直接采样逐位一致（同一函数）
    std::vector<float> poly(out.begin() + kGroundBoundaryHeaderFloats,
                            out.begin() + maskOffset);
    const auto ref = stdPoly();
    ASSERT_EQ(ref.size(), poly.size());
    for (size_t i = 0; i < ref.size(); ++i) {
        ASSERT_FLOAT_EQ(ref[i], poly[i]);
    }
    // 掩码段与直接构建逐位一致
    std::vector<uint8_t> mask(static_cast<size_t>(cfg.cols) * cfg.rows);
    for (size_t i = 0; i < mask.size(); ++i) {
        mask[i] = static_cast<uint8_t>(out[maskOffset + i]);
    }
    std::vector<uint8_t> refMask;
    buildGroundTileMask(cfg, poly, refMask);
    ASSERT_EQ(refMask.size(), mask.size());
    for (size_t i = 0; i < mask.size(); ++i) {
        ASSERT_EQ(refMask[i], mask[i]) << "mask[" << i << "]";
    }
}

// ── 非法输入防御 ─────────────────────────────────────────────────
TEST(GroundBoundaryTest, InvalidConfigYieldsEmpty) {
    GroundBoundaryConfig cfg = stdCfg();
    cfg.cols = 0;
    std::vector<float> out;
    computeGroundBoundary(cfg, out);
    EXPECT_EQ(0u, out.size());
    cfg = stdCfg();
    cfg.tileSize = -1;
    computeGroundBoundary(cfg, out);
    EXPECT_EQ(0u, out.size());
}

}  // namespace
}  // namespace gamecore
