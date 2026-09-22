#pragma once

#include <cmath>
#include <cstdint>
#include <vector>

// ============================================================
// 弯曲地皮轮廓合成器（渲染合成单一权威，地图边缘系统 v2）
//
// 取代 island_cliff 崖壁拼接系统（已退役）：地图边界不再由独立崖壁
// 精灵沿矩形三边拼接，而是由**程序生成的闭合曲线**唯一定义——
//
//   固定控制点（第一阶段）→ 闭合 Catmull-Rom → 定步长采样折线（闭多边形）
//     → ① ground mesh：多边形耳切三角化，UV = 世界坐标/tileSize
//        （独立 map_grass_1 纹理 REPEAT，每格一贴，与旧逐格底图对齐）
//     → ② bottom mesh：外法线朝下的边界段向下挤出 bottomDepth 三角带，
//        顶边与折线**同一套数据**（仅向上收进 kBottomTuck 藏缝）→ 草皮与
//        底部零缝隙/零错位，岩石材质只负责表现、不携带任何岛屿轮廓
//     → ③ 逐格掩码：bit0 = 格矩形四角全在轮廓内（地面变体/平铺装饰可画）、
//        bit1 = 树锚点（格底边中点）在轮廓内（立体装饰可画）
//
// 纯函数、零 Android 依赖、桌面 GTest 直接覆盖。双端渲染路径只消费输出：
//   - C++ Vulkan/GLES：SceneStore.setGroundBoundary → scene_draw 建批
//   - Kotlin Canvas：GroundBoundaryBridge → SoftwareCanvasBackend
//     （Path 填充/裁切 + BitmapShader REPEAT，几何同源自折线）
//
// ## 振幅不变式（内容逻辑零改动的根基，ground_boundary_test 锁定）
//   采样点全部落在地图矩形 ±kMaxOutset/kMaxInset 带内（归一化）：
//   kMaxInset < BORDER_TREE_RING/W ⇒ 轮廓**必包含**整个可建矩形
//   （内缩树环内全部格）⇒ 建筑/道路九处矩形校验、读档自愈、世界坐标、
//   存档格式全部零改动；门楼 x 区间下边界钉在地图底边外
//   （kGateApronY）⇒ 门楼格严格在轮廓内。
//
// ## 曲线与确定性
//   统一参数化 Catmull-Rom（张力 0.5，过全部控制点），每段等分
//   kSamplesPerSegment 份采样。第一阶段控制点为编译期常量（全宗门同形）；
//   后续参数化/随机化只允许替换控制点表，采样/三角化/掩码管线不变。
//
// ## 绕序与 UV 约定
//   折线 = 屏幕坐标 Y 向下**顺时针**（shoelace A2 > 0，与 SpriteBatcher
//   quad 顶点序 (x1,y1)(x2,y1)(x1,y2)… 同手性；管线 VK_CULL_MODE_NONE
//   不剔除，绕序仅为约定一致性）。地皮 UV = (x/tileSize, y/tileSize)；
//   底部 UV u = 边界累计弧长/tileSize、v = 距边界深度/tileSize——相邻
//   底部 quad 在共享折线点处 UV 全等 ⇒ 岩石纹理无缝。
//
// ## 输出复合布局（GroundBoundaryBridge 与 SceneStore 共同消费）
//   [0]=version [1]=polyCount [2]=cols [3]=rows [4]=tileSize
//   [5]=bottomDepth [6]=maskOffset [7]=groundMeshOffset
//   [8]=groundMeshFloatCount [9]=bottomMeshOffset [10]=bottomMeshFloatCount
//   之后依次：折线 N×2、掩码 cols×rows、地皮 mesh（顶点×8 float，
//   SpriteVertex 布局 px,py,u,v,r,g,b,a）、底部 mesh（同布局）。
// ============================================================
namespace gamecore::map {

/// 振幅不变式（归一化坐标，相对地图矩形；< BORDER_TREE_RING/cols = 0.0234）
inline constexpr float kGroundMaxOutset = 0.0160f;  ///< 外扩上限（≈98px，云/天空侧）
inline constexpr float kGroundMaxInset = 0.0160f;   ///< 内缩上限（≈98px，<3 格树环）

/// 门楼缓冲带下边界（归一化 y）：门楼清场区 ±1 列的 x 范围内，轮廓下沿
/// 不高于此值（在地图底边外侧），保证门楼格（底 = 地图底边）严格在轮廓内。
inline constexpr float kGateApronY = 1.004f;
/// 门楼缓冲带 x 范围（归一化；覆盖 GATE_X−1..GATE_X+WIDTH 列 ≈ [0.469, 0.531]）
inline constexpr float kGateApronX0 = 0.440f;
inline constexpr float kGateApronX1 = 0.560f;

/// 底部 mesh 顶边向上收进量（世界像素）：草皮 mesh 后绘、恰好压住收进带，
/// 消除浮点共边可能的天空发丝缝。
inline constexpr float kBottomTuckPx = 2.0f;
/// 底部带产出的法线阈值：外法线 y 分量 > 0（朝屏幕下方）才挤出底部。
inline constexpr float kBottomNormalMinY = 0.0f;

/// 每段等分采样数（26 点 × 20 = 520 折线点，弦步长 ≈ 21px < 0.5 格）
inline constexpr int32_t kGroundSamplesPerSegment = 20;

/// 复合输出头部 float 数
inline constexpr int32_t kGroundBoundaryHeaderFloats = 11;
/// 复合输出版本
inline constexpr int32_t kGroundBoundaryVersion = 1;

/// 合成器配置（Kotlin GameConfig.SectMap 常量经 JNI 传值，单一数据源在 Kotlin）
struct GroundBoundaryConfig {
    int32_t cols = 0;          ///< 地图列数（格）
    int32_t rows = 0;          ///< 地图行数（格）
    int32_t tileSize = 48;     ///< 单格像素
    float bottomDepth = 768.0f;  ///< 底部岩石带深度（世界像素，第一阶段定值）
};

// ── 第一阶段固定控制点（归一化，屏幕 Y 向下顺时针）──────────────────
// 振幅全部落在 ±kGroundMaxOutset/Inset 内；四角各设贴角点（防 Catmull-Rom
// 长弦把地图角切进轮廓内——可建矩形包含不变式的关键）；x∈[0.440, 0.560]
// 的三点钉在 kGateApronY（门楼缓冲带）；首尾相接闭合。
inline constexpr float kGroundControlPoints[] = {
    // 上边（左→右）
    0.020f, -0.006f,  0.180f, 0.012f,  0.340f, -0.010f,  0.520f, 0.008f,
    0.700f, -0.008f,  0.860f, 0.010f,
    // 右上角贴角点
    0.992f, 0.012f,
    // 右边（上→下）
    1.010f, 0.220f,   0.990f, 0.400f,  1.008f, 0.580f,   0.992f, 0.750f,
    1.004f, 0.900f,
    // 右下角贴角点
    0.992f, 0.990f,
    // 下边（右→左）+ 门楼缓冲带三点（钉 kGateApronY）
    0.800f, 1.010f,   0.680f, 0.992f,
    0.560f, kGateApronY,  0.500f, kGateApronY,  0.440f, kGateApronY,
    0.360f, 0.996f,   0.240f, 1.010f,
    // 左下角贴角点
    0.010f, 0.992f,
    // 左边（下→上）
    -0.006f, 0.920f,  0.012f, 0.720f,  -0.008f, 0.540f,  0.010f, 0.360f,
    -0.006f, 0.180f,
};
inline constexpr int32_t kGroundControlPointCount =
    static_cast<int32_t>(sizeof(kGroundControlPoints) / sizeof(float) / 2);

/// 折线点数（控制点数 × 每段采样数）
inline int32_t groundBoundaryPolyCount(const GroundBoundaryConfig& cfg) {
    (void)cfg;
    return kGroundControlPointCount * kGroundSamplesPerSegment;
}

// ── 闭合 Catmull-Rom 采样 ────────────────────────────────────────
// 统一参数化、张力 0.5，曲线过全部控制点；输出 n×subdiv 个 (x,y) 世界像素。
// 采样点落在地图矩形外（外扩）为合法状态（归一化坐标允许轻微越界）。
inline void sampleGroundControlPolygon(const GroundBoundaryConfig& cfg,
                                       std::vector<float>& out) {
    const float W = static_cast<float>(cfg.cols * cfg.tileSize);
    const float H = static_cast<float>(cfg.rows * cfg.tileSize);
    const int32_t n = kGroundControlPointCount;
    const int32_t subdiv = kGroundSamplesPerSegment;
    out.clear();
    out.reserve(static_cast<size_t>(n) * subdiv * 2);
    auto at = [&](int32_t i) -> const float* {
        const int32_t k = ((i % n) + n) % n;
        return &kGroundControlPoints[k * 2];
    };
    for (int32_t i = 0; i < n; ++i) {
        const float* p0 = at(i - 1);
        const float* p1 = at(i);
        const float* p2 = at(i + 1);
        const float* p3 = at(i + 2);
        for (int32_t s = 0; s < subdiv; ++s) {
            const float t = static_cast<float>(s) / static_cast<float>(subdiv);
            const float t2 = t * t;
            const float t3 = t2 * t;
            for (int32_t axis = 0; axis < 2; ++axis) {
                const float v = 0.5f * ((2.0f * p1[axis]) +
                    (-p0[axis] + p2[axis]) * t +
                    (2.0f * p0[axis] - 5.0f * p1[axis] + 4.0f * p2[axis] - p3[axis]) * t2 +
                    (-p0[axis] + 3.0f * p1[axis] - 3.0f * p2[axis] + p3[axis]) * t3);
                out.push_back(v * ((axis == 0) ? W : H));
            }
        }
    }
}

// ── 偶奇规则点在多边形内 ─────────────────────────────────────────
inline bool groundPointInPolygon(float x, float y, const std::vector<float>& poly) {
    const size_t n = poly.size() / 2;
    bool inside = false;
    for (size_t i = 0, j = n - 1; i < n; j = i++) {
        const float xi = poly[i * 2];
        const float yi = poly[i * 2 + 1];
        const float xj = poly[j * 2];
        const float yj = poly[j * 2 + 1];
        if ((yi > y) != (yj > y)) {
            const float crossX = (xj - xi) * (y - yi) / (yj - yi) + xi;
            if (x < crossX) inside = !inside;
        }
    }
    return inside;
}

/// 折线包围盒（世界像素）
inline void groundPolyBounds(const std::vector<float>& poly, float& minX, float& minY,
                             float& maxX, float& maxY) {
    minX = minY = 3.0e38f;
    maxX = maxY = -3.0e38f;
    for (size_t i = 0; i + 1 < poly.size(); i += 2) {
        minX = std::fmin(minX, poly[i]);
        maxX = std::fmax(maxX, poly[i]);
        minY = std::fmin(minY, poly[i + 1]);
        maxY = std::fmax(maxY, poly[i + 1]);
    }
}

// ── 逐格掩码 ─────────────────────────────────────────────────────
// bit0：格矩形四角全在轮廓内（地面变体/平铺装饰可画——保守判定，边界带
// 格不画变体，由地皮 mesh 提供底色）；bit1：树锚点（格底边中点）在轮廓内。
// 加速：轮廓被 ±kGroundMaxOutset/Inset 带约束 ⇒ 完全落在矩形内缩
// kGroundMaxInset 带内的格恒为 3（免 PIP），仅边缘带格逐点判定。
inline void buildGroundTileMask(const GroundBoundaryConfig& cfg,
                                const std::vector<float>& poly,
                                std::vector<uint8_t>& out) {
    const int32_t cols = cfg.cols;
    const int32_t rows = cfg.rows;
    const float t = static_cast<float>(cfg.tileSize);
    const float W = static_cast<float>(cols) * t;
    const float H = static_cast<float>(rows) * t;
    out.assign(static_cast<size_t>(cols) * rows, 0);
    const float safeX0 = kGroundMaxInset * W;
    const float safeY0 = kGroundMaxInset * H;
    const float safeX1 = W - safeX0;
    const float safeY1 = H - safeY0;
    for (int32_t row = 0; row < rows; ++row) {
        const float y0 = static_cast<float>(row) * t;
        const float y1 = y0 + t;
        const bool rowSafe = (y0 >= safeY0) && (y1 <= safeY1);
        for (int32_t col = 0; col < cols; ++col) {
            const float x0 = static_cast<float>(col) * t;
            const float x1 = x0 + t;
            uint8_t m;
            if (rowSafe && x0 >= safeX0 && x1 <= safeX1) {
                m = 3;  // 内缩安全带内：恒在轮廓内
            } else {
                const bool quadFull = groundPointInPolygon(x0, y0, poly) &&
                                      groundPointInPolygon(x1, y0, poly) &&
                                      groundPointInPolygon(x0, y1, poly) &&
                                      groundPointInPolygon(x1, y1, poly);
                const bool treeFoot =
                    groundPointInPolygon(x0 + t * 0.5f, y1, poly);
                m = static_cast<uint8_t>((quadFull ? 1 : 0) | (treeFoot ? 2 : 0));
            }
            out[static_cast<size_t>(row) * cols + col] = m;
        }
    }
}

// ── 折线绕序与耳切三角化 ─────────────────────────────────────────
// 折线为屏幕 Y 向下顺时针 ⇒ shoelace A2 = Σ(xᵢ·yᵢ₊₁ − xᵢ₊₁·yᵢ) > 0。
// 凸顶点/耳内点判定均以 A2>0 手性为准；输入逆序时先翻转保证输出一致。
inline float groundPolyArea2(const std::vector<float>& poly) {
    float a2 = 0.0f;
    const size_t n = poly.size() / 2;
    for (size_t i = 0; i < n; ++i) {
        const size_t j = (i + 1) % n;
        a2 += poly[i * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[i * 2 + 1];
    }
    return a2;
}

namespace ground_boundary_internal {

inline float cross2(float ax, float ay, float bx, float by, float cx, float cy) {
    return (bx - ax) * (cy - by) - (by - ay) * (cx - bx);
}

inline bool pointInTri(float px, float py, float ax, float ay,
                       float bx, float by, float cx, float cy) {
    const float d1 = cross2(ax, ay, bx, by, px, py);
    const float d2 = cross2(bx, by, cx, cy, px, py);
    const float d3 = cross2(cx, cy, ax, ay, px, py);
    const bool hasNeg = (d1 < 0.0f) || (d2 < 0.0f) || (d3 < 0.0f);
    const bool hasPos = (d1 > 0.0f) || (d2 > 0.0f) || (d3 > 0.0f);
    return !(hasNeg && hasPos);
}

}  // namespace ground_boundary_internal

/// 耳切三角化：输出索引三元组（指向折线顶点）。要求简单多边形。
/// 数值兜底：一圈找不到合法耳（理论不可达）时剪掉最凸顶点防死循环。
inline void triangulateGroundPolygon(const std::vector<float>& poly,
                                     std::vector<int32_t>& outIdx) {
    outIdx.clear();
    const size_t n = poly.size() / 2;
    if (n < 3) return;
    std::vector<int32_t> ring(static_cast<size_t>(n));
    for (size_t i = 0; i < n; ++i) ring[i] = static_cast<int32_t>(i);
    if (groundPolyArea2(poly) < 0.0f) {
        std::reverse(ring.begin(), ring.end());
    }
    const float* p = poly.data();
    auto P = [&](int32_t i) -> const float* { return &p[static_cast<size_t>(i) * 2]; };
    size_t guard = 0;
    const size_t guardMax = ring.size() * ring.size() + 16;
    while (ring.size() > 3 && guard++ < guardMax) {
        const size_t m = ring.size();
        bool clipped = false;
        for (size_t k = 0; k < m; ++k) {
            const int32_t i0 = ring[(k + m - 1) % m];
            const int32_t i1 = ring[k];
            const int32_t i2 = ring[(k + 1) % m];
            const float* a = P(i0);
            const float* b = P(i1);
            const float* c = P(i2);
            if (ground_boundary_internal::cross2(a[0], a[1], b[0], b[1], c[0], c[1]) <=
                0.0f) {
                continue;  // 凹顶点非耳
            }
            bool contains = false;
            for (size_t q = 0; q < m; ++q) {
                const int32_t iq = ring[q];
                if (iq == i0 || iq == i1 || iq == i2) continue;
                const float* t = P(iq);
                if (ground_boundary_internal::pointInTri(t[0], t[1], a[0], a[1],
                                                         b[0], b[1], c[0], c[1])) {
                    contains = true;
                    break;
                }
            }
            if (contains) continue;
            outIdx.push_back(i0);
            outIdx.push_back(i1);
            outIdx.push_back(i2);
            ring.erase(ring.begin() + static_cast<long>(k));
            clipped = true;
            break;
        }
        if (!clipped) {
            // 数值兜底：剪掉叉积最大的顶点（最凸），保证终止
            size_t best = 0;
            float bestCross = -3.0e38f;
            for (size_t k = 0; k < ring.size(); ++k) {
                const float* a = P(ring[(k + ring.size() - 1) % ring.size()]);
                const float* b = P(ring[k]);
                const float* c = P(ring[(k + 1) % ring.size()]);
                const float cr = ground_boundary_internal::cross2(a[0], a[1], b[0],
                                                                  b[1], c[0], c[1]);
                if (cr > bestCross) { bestCross = cr; best = k; }
            }
            outIdx.push_back(ring[(best + ring.size() - 1) % ring.size()]);
            outIdx.push_back(ring[best]);
            outIdx.push_back(ring[(best + 1) % ring.size()]);
            ring.erase(ring.begin() + static_cast<long>(best));
        }
    }
    if (ring.size() == 3) {
        outIdx.push_back(ring[0]);
        outIdx.push_back(ring[1]);
        outIdx.push_back(ring[2]);
    }
}

// ── 地皮 mesh（SpriteVertex 布局顶点流）─────────────────────────
// UV = (x/tileSize, y/tileSize)：独立草纹理 REPEAT 每格一贴，与旧逐格
// 底图/整图 REPEAT quad 同口径。顶点色恒白（淡入 alpha 由消费端逐帧乘）。
inline void buildGroundMesh(const GroundBoundaryConfig& cfg,
                            const std::vector<float>& poly,
                            std::vector<float>& out) {
    out.clear();
    std::vector<int32_t> idx;
    triangulateGroundPolygon(poly, idx);
    const float t = static_cast<float>(cfg.tileSize);
    out.reserve(idx.size() * 8);
    for (int32_t i : idx) {
        const float x = poly[static_cast<size_t>(i) * 2];
        const float y = poly[static_cast<size_t>(i) * 2 + 1];
        out.push_back(x);
        out.push_back(y);
        out.push_back(x / t);
        out.push_back(y / t);
        out.push_back(1.0f);
        out.push_back(1.0f);
        out.push_back(1.0f);
        out.push_back(1.0f);
    }
}

// ── 底部 mesh（与地皮共用同一折线）──────────────────────────────
// 逐段判定：外法线（背离质心）y 分量 > kBottomNormalMinY 才挤出三角带。
// 顶边 = 折线点上移 kBottomTuckPx（藏进草皮之下）；UV u = 该端点累计弧长
// /tileSize（相邻 quad 共享端点 ⇒ UV 全等 ⇒ 岩石纹理无缝）、v = 深度归一。
inline void buildBottomMesh(const GroundBoundaryConfig& cfg,
                            const std::vector<float>& poly,
                            std::vector<float>& out) {
    out.clear();
    const size_t n = poly.size() / 2;
    if (n < 3) return;
    const float t = static_cast<float>(cfg.tileSize);
    const float depth = cfg.bottomDepth;
    if (depth <= 0.0f) return;
    float cx = 0.0f, cy = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        cx += poly[i * 2];
        cy += poly[i * 2 + 1];
    }
    cx /= static_cast<float>(n);
    cy /= static_cast<float>(n);
    std::vector<float> arc(static_cast<size_t>(n), 0.0f);
    for (size_t i = 1; i <= n; ++i) {
        const size_t a = i - 1;
        const size_t b = i % n;
        const float dx = poly[b * 2] - poly[a * 2];
        const float dy = poly[b * 2 + 1] - poly[a * 2 + 1];
        arc[i % n] = arc[a] + std::sqrt(dx * dx + dy * dy);
    }
    const float perimeter = arc[0];  // 闭合：末段回到起点
    out.reserve(static_cast<size_t>(n) * 2 * 8);
    for (size_t i = 0; i < n; ++i) {
        const size_t j = (i + 1) % n;
        const float ax = poly[i * 2], ay = poly[i * 2 + 1];
        const float bx = poly[j * 2], by = poly[j * 2 + 1];
        const float dx = bx - ax, dy = by - ay;
        const float len = std::sqrt(dx * dx + dy * dy);
        if (len < 1.0e-6f) continue;
        // 屏幕坐标 Y 向下、顺时针折线 ⇒ 外法线 = (dy, -dx) 归一化
        float nx = dy / len;
        float ny = -dx / len;
        const float mx = (ax + bx) * 0.5f - cx;
        const float my = (ay + by) * 0.5f - cy;
        if (nx * mx + ny * my < 0.0f) { nx = -nx; ny = -ny; }
        if (ny <= kBottomNormalMinY) continue;
        const float topAy = ay - kBottomTuckPx;
        const float topBy = by - kBottomTuckPx;
        const float uA = arc[i] / t;
        const float uB = arc[j] / t;
        const float vTop = 0.0f;
        const float vBot = depth / t;
        const float botAy = ay + depth - kBottomTuckPx;
        const float botBy = by + depth - kBottomTuckPx;
        // 与 SpriteBatcher.add 同手性两三角：(A_top, B_top, A_bot)(B_top, B_bot, A_bot)
        const float quad[6][8] = {
            { ax, topAy, uA, vTop, 1, 1, 1, 1 },
            { bx, topBy, uB, vTop, 1, 1, 1, 1 },
            { ax, botAy, uA, vBot, 1, 1, 1, 1 },
            { bx, topBy, uB, vTop, 1, 1, 1, 1 },
            { bx, botBy, uB, vBot, 1, 1, 1, 1 },
            { ax, botAy, uA, vBot, 1, 1, 1, 1 },
        };
        for (auto& v : quad) {
            for (float f : v) out.push_back(f);
        }
        (void)perimeter;
    }
}

// ── 总装配（复合 FloatArray，布局见文件头）──────────────────────
inline void computeGroundBoundary(const GroundBoundaryConfig& cfg,
                                  std::vector<float>& out) {
    out.clear();
    if (cfg.cols <= 0 || cfg.rows <= 0 || cfg.tileSize <= 0) return;
    std::vector<float> poly;
    sampleGroundControlPolygon(cfg, poly);
    std::vector<uint8_t> mask;
    buildGroundTileMask(cfg, poly, mask);
    std::vector<float> groundMesh;
    buildGroundMesh(cfg, poly, groundMesh);
    std::vector<float> bottomMesh;
    buildBottomMesh(cfg, poly, bottomMesh);

    const int32_t polyCount = static_cast<int32_t>(poly.size() / 2);
    size_t off = static_cast<size_t>(kGroundBoundaryHeaderFloats) + poly.size();
    const float maskOffset = static_cast<float>(off);
    off += mask.size();
    const float groundMeshOffset = static_cast<float>(off);
    off += groundMesh.size();
    const float bottomMeshOffset = static_cast<float>(off);

    out.reserve(off + bottomMesh.size());
    out.push_back(static_cast<float>(kGroundBoundaryVersion));
    out.push_back(static_cast<float>(polyCount));
    out.push_back(static_cast<float>(cfg.cols));
    out.push_back(static_cast<float>(cfg.rows));
    out.push_back(static_cast<float>(cfg.tileSize));
    out.push_back(cfg.bottomDepth);
    out.push_back(maskOffset);
    out.push_back(groundMeshOffset);
    out.push_back(static_cast<float>(groundMesh.size()));
    out.push_back(bottomMeshOffset);
    out.push_back(static_cast<float>(bottomMesh.size()));
    for (size_t i = 0; i < poly.size(); ++i) out.push_back(poly[i]);
    for (uint8_t m : mask) out.push_back(static_cast<float>(m));
    for (float f : groundMesh) out.push_back(f);
    for (float f : bottomMesh) out.push_back(f);
}

}  // namespace gamecore::map
