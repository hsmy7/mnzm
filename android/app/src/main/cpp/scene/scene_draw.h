#pragma once

#include <cstdint>
#include <map>
#include <vector>
#include <algorithm>
#include <cmath>

#include "Rhi.h"
#include "SpriteBatcher.h"
#include "scene_uv_tables.h"
// 叠加层状态与预览协议（R3.3）——纯声明头文件，零 Android 依赖
#include "scene_store.h"
// 石板道路求解器 + 渲染合成器 + 绘制层序合成器（单一权威，与旧路径同源）
#include "gamecore/map/road_compositor.h"
#include "gamecore/map/draw_order.h"

// ============================================================
// scene_draw — 场景绘制核心（重构方案 2026-09-17 R3.2/B10）
//
// **单份绘制实现**：旧 drawAllTiles（17 参数全量数组，灰度回滚臂）与
// 新 drawFrame（SceneStore + 生成 UV 表）消费同一构建逻辑——新旧路径
// 像素等价由构造保证（同代码同值 ⇒ 同顶点流 ⇒ 同像素），
// scene_equivalence_test 以顶点流逐位对照锁定。
//
// 本头只做「数据 → SpriteBatcher 顶点」的装配（可见性剔除/装饰锚点/
// 道路合成/建筑 Y 归并/作物阶段插值/云层），不含：
//   - JNI/Android 依赖（桌面 GTest 直编）；
//   - 提交与溢出遥测结算（桥侧 g_renderer->draw / noteBatcherOverflow）；
//   - 热控/LOD/溢出降级判定（桥侧读全局量装配 skipDecor/skipClouds）。
// 线程契约：渲染线程单消费者（与既有 drawAllTiles 同）。
//
// R3.3/B11 追加 `buildOverlayLayers`：四类世界叠加层（网格线/占地预览框+
// 预览精灵/选中高亮/拆除高亮）的几何生成——旧路径由 Kotlin 每帧逐 rect 跨
// JNI（放置模式最坏 ~258 次 drawRect），现由 drawFrame 的 overlayFlags 模式位
// 驱动在本头单份实现内算出（格→世界矩形、线宽、透明度常量、相机投影、
// Y 轴压缩全在 C++ 侧），Kotlin 只在值变化时推选中索引/拆除标记/预览几何。
// ============================================================
namespace scene {

/// UV 向内收缩（kUvEpsilon 别名语义：防图集邻居渗色）
inline constexpr float kSceneUvEpsilon = kUvEpsilon;

/// 作物帧间平滑状态（插值基准存原始逻辑进度，平滑值仅用于绘制；
/// surface 纪元复位由持有方 clear——防旧 surface 进度基准污染新 surface）
struct CropSmoothingState {
    /// 上一帧作物原始进度（key = (gx<<32)|gy 网格编码）
    std::map<int64_t, float> lastProgress;
    /// 本帧活跃作物 key（收获/拆除后帧末清除残留条目）
    std::vector<int64_t> activeKeys;

    void clear() {
        lastProgress.clear();
        activeKeys.clear();
    }
};

/// 地图层绘制输入——旧路径由 JNI 数组元素装配，新路径由 SceneStore +
/// 生成 UV 表装配，两侧逐字段同值（等价性红线）。
struct MapLayerParams {
    // 相机/帧级量
    float viewLeft = 0.0f;
    float viewTop = 0.0f;
    float viewRight = 0.0f;
    float viewBottom = 0.0f;
    float scale = 1.0f;
    float fadeAlpha = 1.0f;
    float frameAlpha = 0.0f;

    // 层开关（桥侧装配：溢出降级/热控/装饰 LOD 同一判定，两路同值）
    bool skipDecor = false;
    bool skipClouds = false;
    bool buildingShadows = true;
    bool buildingVisible = true;

    // 地形
    const int32_t* tiles = nullptr;
    int64_t tileCount = 0;
    int cols = 0;
    int rows = 0;
    int tileSize = 48;
    uint32_t atlasTexId = 0;

    // 整图 REPEAT 地面（既有编译期关闭特性 GROUND_QUAD_ENABLED 的参数化——
    // 两路调用点恒传 false，保留判定形状待驱动问题定位后启用）
    bool groundQuadEnabled = false;
    uint32_t groundTexId = 0;

    // UV 表与逐层数据（指针 + 计数显式传入；计数语义与旧路径
    // GetArrayLength 推导一致）
    const float* tileUv = nullptr;
    int tileUvCount = 0;

    const int32_t* roads = nullptr;
    int64_t roadCount = 0;
    const float* roadUv = nullptr;
    int roadUvCount = 0;

    const float* buildings = nullptr;
    int buildingClaim = 0;      // 调用方声明的建筑数
    int buildingDataFloats = 0; // 数组实际容量（float 数；钳制 min(claim, cap/5)）
    const float* buildingUv = nullptr;
    int buildingUvCount = 0;

    const float* crops = nullptr;
    int cropCount = 0;
    const float* cropUv = nullptr;
    int cropUvCount = 0;

    const float* clouds = nullptr;
    int cloudCount = 0;
    const float* cloudUv = nullptr;
    int cloudUvCount = 0;
};

/// 崖壁层绘制输入（布局条目 + 纹理 ID 表）
struct CliffLayerParams {
    const float* data = nullptr;   // [texIdx,x,y,w,h,u0,v0,u1,v1,flags] × N
    int pieceCount = 0;
    const uint32_t* texIds = nullptr;
    int texCount = 0;
    float viewLeft = 0.0f;
    float viewTop = 0.0f;
    float viewRight = 0.0f;
    float viewBottom = 0.0f;
    float scale = 1.0f;
    float fadeAlpha = 1.0f;
};

// ============================================================
// 叠加层（overlay）——R3.3/B11：几何全部 C++ 生成，每帧零逐 rect 跨线
// ============================================================

/// drawFrame 的 overlayFlags 位定义（**与 Kotlin VulkanRenderBackend 的
/// OVERLAY_FLAG_* 常量逐位同值**，双端由守卫测试 SceneOverlayFlagsMirrorGuardTest 锁定）
inline constexpr int32_t kOverlayBitBuildingVisible = 1 << 0;  ///< 建筑层可见
inline constexpr int32_t kOverlayBitGridVisible = 1 << 1;      ///< 放置/移动模式网格线
inline constexpr int32_t kOverlayBitPreviewSprite = 1 << 2;    ///< 预览精灵（showPreview）
inline constexpr int32_t kOverlayBitPreviewBox = 1 << 3;       ///< 占地预览框
inline constexpr int32_t kOverlayBitPreviewValid = 1 << 4;     ///< 预览合法性（绿/红）
inline constexpr int32_t kOverlayBitSelection = 1 << 5;        ///< 选中高亮本帧可画
inline constexpr int32_t kOverlayBitDemolish = 1 << 6;         ///< 拆除高亮本帧可画

/// 叠加层绘制输入——数据来自 SceneStore 的叠加层状态（变化驱动导入），
/// 相机/视口来自 drawFrame 每帧标量，常量来自生成表 scene_uv_tables.h。
struct OverlayParams {
    // 相机与视口（与投影矩阵同源——drawFrame 消毒后的值）
    float camX = 0.0f;
    float camY = 0.0f;
    float scale = 1.0f;
    int32_t viewportW = 0;
    int32_t viewportH = 0;

    // 场景来源（网格线世界尺寸 + 高亮占地换算）
    int32_t cols = 0;
    int32_t rows = 0;
    int32_t tileSize = 48;

    // 选中高亮
    const float* buildings = nullptr;
    int32_t buildingCount = 0;
    int32_t selectionIndex = -1;

    // 拆除高亮（与 buildings 同序，逐建筑 1 字节）
    const uint8_t* markers = nullptr;
    int32_t markerCount = 0;

    // 预览（占地框 + 精灵，触控驱动的世界像素几何）
    PreviewState preview;
    uint32_t atlasTexId = 0;

    // 层开关（桥侧按 overlayFlags 位装配）
    bool gridVisible = false;
    bool previewSpriteVisible = false;
    bool previewBoxVisible = false;
    bool previewValid = true;
    bool selectionEnabled = false;
    bool demolishEnabled = false;
    /// 选中高亮特性开关（RenderFlags.selectionHighlight 经 setRenderFlags 推送）
    bool selectionHighlightFlag = true;
};

/// 预览几何有限性守卫：非有限值（NaN/±Inf）整层跳过——旧路径把 NaN 直接送进
/// 顶点流（该帧矩形本身已不可辨），新路径不产出这类退化顶点。真实输入恒有限
/// （触控/图集常量），本守卫只挡数据篡改/上游算错，不构成视觉差异。
inline bool previewFinite(const PreviewState& s) {
    const float vals[16] = {
        s.boxX, s.boxY, s.boxW, s.boxH,
        s.spriteX, s.spriteY, s.spriteW, s.spriteH,
        s.u0, s.v0, s.u1, s.v1,
        s.r, s.g, s.b, s.a
    };
    for (const float v : vals) {
        if (!(v >= -1e9f && v <= 1e9f)) return false;  // NaN 比较恒 false 一并拦截
    }
    return true;
}

/// 可见性检测（视口世界坐标矩形相交；与旧 NativeBridge isRectVisible 同式）
inline bool sceneRectVisible(float x, float y, float w, float h,
                             float viewLeft, float viewTop,
                             float viewRight, float viewBottom) {
    return !(x + w <= viewLeft || x >= viewRight ||
             y + h <= viewTop || y >= viewBottom);
}

/// 瓦片几何扩展因子（每边 0.5 屏幕像素防相邻瓦片裂缝；scale 极小时用 1 防除零）
inline float sceneGapEpsilon(float scale) {
    const float epsScale = (scale > 0.001f) ? scale : 1.0f;
    return 0.5f / epsScale;
}

/// 立体层装饰绘制项（树——收集后与建筑按地面接触点归并绘制）
struct ObjectDecorDrawItem {
    float bottomY;
    float x, y, w, h;
    float u0, v0, u1, v1;
};

/// 可见建筑绘制项（预计算几何 + 底边 Y，供层序归并）
struct BuildingDrawItem {
    float bottomY;
    float x, y, w, h;
    float floorX, floorY, floorW, floorH;  // 占地矩形（阴影用）
    int uvIndex;
    bool shadow;
};

/// 单帧立体层装饰收集上限（视口最大格数上界，防异常数据把缓冲撑爆）
inline constexpr size_t kMaxObjectDecorItems = 20000;

/// 地图层批量构建（地形+装饰 → 道路 → 建筑+立体装饰归并 → 作物 → 云）。
///
/// 与旧 drawAllTiles 函数体逐段同构（段序/判定/常量/浮点表达式一一对应）；
/// 跨帧复用的装饰/建筑收集缓冲为函数级 static（渲染线程单消费者，
/// 与旧实现同纪律）。返回 batcher.end() 顶点数（提交/溢出结算在桥侧）。
inline int buildMapBatch(SpriteBatcher& batcher, const MapLayerParams& p,
                         const float projMatrix[16], CropSmoothingState& cropState) {
    if (p.tiles == nullptr || p.tileUv == nullptr) return 0;
    // 深度防御：rows×cols 超数组实际长度即堆越界读（旧路径同源防御）
    if (static_cast<int64_t>(p.rows) * p.cols > p.tileCount) return 0;

    const float fadeAlpha = p.fadeAlpha;
    const float gapEpsilon = sceneGapEpsilon(p.scale);
    const float tileSizeF = static_cast<float>(p.tileSize);

    batcher.begin(projMatrix);

    // ---- 整图 REPEAT 地面（GROUND_QUAD_ENABLED 恒 false——保留既有形状）----
    if (p.groundQuadEnabled && p.groundTexId != 0) {
        SpriteBatcher groundBatcher;
        groundBatcher.begin(projMatrix);
        float gx0 = std::max(0.0f, p.viewLeft);
        float gy0 = std::max(0.0f, p.viewTop);
        float gx1 = std::min(static_cast<float>(p.cols * p.tileSize), p.viewRight);
        float gy1 = std::min(static_cast<float>(p.rows * p.tileSize), p.viewBottom);
        if (gx1 > gx0 && gy1 > gy0) {
            groundBatcher.add(p.groundTexId,
                gx0, gy0, gx1 - gx0, gy1 - gy0,
                gx0 / tileSizeF, gy0 / tileSizeF,
                gx1 / tileSizeF, gy1 / tileSizeF,
                1.0f, 1.0f, 1.0f, fadeAlpha);
        }
        const int groundVerts = groundBatcher.end();
        // 提交由回调之外承担——本分支两路调用点恒 false，绘制路径不可达
        // （与旧实现一致：g_renderer->draw 在桥侧；此处仅保持形状）
        (void)groundVerts;
    }

    // 可见范围钳制迭代（平板省电）+ 装饰越界余量外扩
    const int minCol = std::max(0, static_cast<int>(std::floor(p.viewLeft / tileSizeF)) - kDecorMarginCols);
    const int maxCol = std::min(p.cols - 1, static_cast<int>(std::ceil(p.viewRight / tileSizeF)) + kDecorMarginCols);
    const int minRow = std::max(0, static_cast<int>(std::floor(p.viewTop / tileSizeF)) - kDecorMarginRows);
    const int maxRow = std::min(p.rows - 1, static_cast<int>(std::ceil(p.viewBottom / tileSizeF)) + kDecorMarginRows);

    // 立体层装饰 / 建筑绘制项缓冲（跨帧复用容量，渲染线程单消费者）
    static std::vector<ObjectDecorDrawItem> decorItems;
    static std::vector<BuildingDrawItem> buildingItems;
    static std::vector<float> decorBottomY;
    static std::vector<float> buildingBottomY;
    static std::vector<uint8_t> mergedOrder;
    decorItems.clear();
    buildingItems.clear();
    decorBottomY.clear();
    buildingBottomY.clear();
    mergedOrder.clear();

    // ---- 1. 瓦片层（地面逐格 + 装饰叠加层收集）----
    for (int row = minRow; row <= maxRow; row++) {
        const int64_t rowBase = static_cast<int64_t>(row) * p.cols;
        float wy = static_cast<float>(row * p.tileSize);
        for (int col = minCol; col <= maxCol; col++) {
            int tile = static_cast<int>(p.tiles[rowBase + col]);

            float wx = static_cast<float>(col * p.tileSize);

            if (!sceneRectVisible(wx, wy, tileSizeF, tileSizeF,
                                  p.viewLeft, p.viewTop, p.viewRight, p.viewBottom)) continue;

            // (A) 地面底图：逐格绘制（整图 REPEAT 关闭期恒走此路径）
            if (!p.groundQuadEnabled || p.groundTexId == 0) {
                int gIdx = 0;
                for (int gv = 0; gv < kGroundVariantCount; gv++) {
                    if (tile == kGroundVariants[gv]) { gIdx = tile; break; }
                }
                if (gIdx < p.tileUvCount) {
                    batcher.add(p.atlasTexId,
                        wx - gapEpsilon, wy - gapEpsilon,
                        tileSizeF + 2.0f * gapEpsilon,
                        tileSizeF + 2.0f * gapEpsilon,
                        p.tileUv[gIdx * 4] + kSceneUvEpsilon,
                        p.tileUv[gIdx * 4 + 1] + kSceneUvEpsilon,
                        p.tileUv[gIdx * 4 + 2] - kSceneUvEpsilon,
                        p.tileUv[gIdx * 4 + 3] - kSceneUvEpsilon,
                        1.0f, 1.0f, 1.0f, fadeAlpha);
                }
            }

            // (B) 装饰叠加层（草/石/树）——skipDecor 由桥侧装配（热控/LOD/溢出降级）
            if (!p.skipDecor && tile >= kDecorTileMin && tile <= kDecorTileMax &&
                tile < kTileTypeCount) {
                int uvIdx = tile;
                if (uvIdx < p.tileUvCount) {
                    float u0 = p.tileUv[uvIdx * 4] + kSceneUvEpsilon;
                    float v0 = p.tileUv[uvIdx * 4 + 1] + kSceneUvEpsilon;
                    float u1 = p.tileUv[uvIdx * 4 + 2] - kSceneUvEpsilon;
                    float v1 = p.tileUv[uvIdx * 4 + 3] - kSceneUvEpsilon;

                    // 锚点 = 格底边居中（与 Canvas 侧 drawGroundRow 同式，双端逐位一致）
                    float geo[4];
                    gamecore::map::decorDrawRect(wx, wy, tileSizeF,
                        kTileSpriteW[tile], kTileSpriteH[tile], geo);
                    if (kTileObjectLayer[tile] == 0) {
                        batcher.add(p.atlasTexId,
                            geo[0] - gapEpsilon,
                            geo[1] - gapEpsilon,
                            geo[2] + 2.0f * gapEpsilon,
                            geo[3] + 2.0f * gapEpsilon,
                            u0, v0, u1, v1,
                            1.0f, 1.0f, 1.0f, fadeAlpha);
                    } else if (decorItems.size() < kMaxObjectDecorItems) {
                        decorItems.push_back(ObjectDecorDrawItem{
                            wy + tileSizeF, geo[0], geo[1], geo[2], geo[3],
                            u0, v0, u1, v1});
                    }
                }
            }
            // (C) 建筑占位格：地面已画，建筑精灵由建筑层叠加
        }
    }

    // ---- 2. 石板道路层（装饰之上、建筑之下——与 Canvas 侧烘焙顺序一致）----
    // 单一权威合成器：格内局部整型几何 → 操作序列 → SpriteBatcher 装配
    if (p.roads != nullptr && p.roadUv != nullptr) {
        // 防御：roadUv 须容纳 kRoadSpriteCount 组 [u0,v0,u1,v1]
        //（quad 数语义，与旧路径完整长度 ≥ kRoadSpriteCount×4 守卫等价）
        if (static_cast<int64_t>(p.rows) * p.cols <= p.roadCount &&
            p.roadUvCount >= gamecore::map::kRoadSpriteCount) {
            for (int row = minRow; row <= maxRow; row++) {
                float wy = static_cast<float>(row * p.tileSize);
                for (int col = minCol; col <= maxCol; col++) {
                    // 1-based 编码（0=非道路，1=单格，2..16=四邻掩码+1）
                    const int raw = p.roads[static_cast<int64_t>(row) * p.cols + col];
                    if (raw == 0) continue;
                    const int mask = raw - 1;
                    float wx = static_cast<float>(col * p.tileSize);
                    if (!sceneRectVisible(wx, wy, tileSizeF, tileSizeF,
                                          p.viewLeft, p.viewTop, p.viewRight, p.viewBottom)) continue;

                    gamecore::map::RoadDrawOp ops[gamecore::map::kMaxRoadDrawOpsPerTile];
                    const int opCount = gamecore::map::emitRoadDrawOps(mask, p.tileSize, ops);
                    for (int i = 0; i < opCount; i++) {
                        const int si = static_cast<int>(ops[i].sprite);
                        // flipU 消费：左/上缘条水平镜像（深色描边边朝外）
                        float ru0 = p.roadUv[si * 4] + kSceneUvEpsilon;
                        float ru1 = p.roadUv[si * 4 + 2] - kSceneUvEpsilon;
                        if (ops[i].flipU) {
                            float tmp = ru0; ru0 = ru1; ru1 = tmp;
                        }
                        batcher.add(p.atlasTexId,
                            wx + static_cast<float>(ops[i].x), wy + static_cast<float>(ops[i].y),
                            static_cast<float>(ops[i].w), static_cast<float>(ops[i].h),
                            ru0, p.roadUv[si * 4 + 1] + kSceneUvEpsilon,
                            ru1, p.roadUv[si * 4 + 3] - kSceneUvEpsilon,
                            1.0f, 1.0f, 1.0f, fadeAlpha);
                    }
                }
            }
        }
    }

    // ---- 3. 建筑层 + 立体层装饰（同一画家序归并）----
    const bool buildingLayerActive = p.buildingVisible && p.buildings != nullptr &&
                                     p.buildingUv != nullptr && p.buildingClaim > 0;
    if (buildingLayerActive) {
        // buildingClaim 与数组容量取小（防御上游不一致的越界读）
        const int effectiveCount = std::min(p.buildingClaim, p.buildingDataFloats / 5);
        const int fpCount = static_cast<int>(sizeof(kFootprintW) / sizeof(kFootprintW[0]));

        for (int i = 0; i < effectiveCount; i++) {
            const int idx = i * 5;
            float gx = p.buildings[idx];
            float gy = p.buildings[idx + 1];
            float sw = p.buildings[idx + 2];   // 精灵宽度（比例尺寸，可能大于占地）
            float sh = p.buildings[idx + 3];   // 精灵高度
            int nameIdx = static_cast<int>(p.buildings[idx + 4]);

            // 固定结构（宗门入口门楼/阶梯）nameIdx ≥ kStructureNameBase
            const bool isStructure = nameIdx >= kStructureNameBase;
            int fpW, fpH;
            if (isStructure) {
                const int si = nameIdx - kStructureNameBase;
                const int sfpCount = static_cast<int>(sizeof(kStructureFpW) / sizeof(kStructureFpW[0]));
                if (si >= 0 && si < sfpCount) { fpW = kStructureFpW[si]; fpH = kStructureFpH[si]; }
                else { fpW = 2; fpH = 2; }
            } else if (nameIdx >= 0 && nameIdx < fpCount) {
                fpW = kFootprintW[nameIdx];
                fpH = kFootprintH[nameIdx];
            } else {
                fpW = 2; fpH = 2;
            }

            // 精灵底部对齐于占地网格：offsetX 居中，offsetY 底部对齐
            float offsetX = (fpW - sw) * tileSizeF * 0.5f;
            float offsetY = (fpH - sh) * tileSizeF;
            float px = gx * tileSizeF + offsetX;
            float py = gy * tileSizeF + offsetY;
            float pw = sw * tileSizeF;
            float ph = sh * tileSizeF;

            float ftPx = gx * tileSizeF;
            float ftPy = gy * tileSizeF;
            float ftPw = fpW * tileSizeF;
            float ftPh = fpH * tileSizeF;

            if (!sceneRectVisible(px, py, pw, ph,
                                  p.viewLeft, p.viewTop, p.viewRight, p.viewBottom)) continue;

            int buvIdx = nameIdx;
            if (buvIdx < 0 || buvIdx >= p.buildingUvCount) buvIdx = 0;

            // 地面接触点（占地底边）——归并序键；上游数组已按 gridY+height 升序，保序收集
            buildingItems.push_back(BuildingDrawItem{
                (gy + fpH) * tileSizeF,
                px, py, pw, ph,
                ftPx, ftPy, ftPw, ftPh,
                buvIdx,
                p.buildingShadows && !isStructure});
        }

        // 归并绘制序：立体层装饰 + 可见建筑（两组各自已按底边 Y 升序）
        const size_t itemCount = decorItems.size() + buildingItems.size();
        mergedOrder.resize(itemCount);
        if (itemCount > 0) {
            decorBottomY.clear();
            decorBottomY.reserve(decorItems.size());
            for (const ObjectDecorDrawItem& d : decorItems) decorBottomY.push_back(d.bottomY);
            buildingBottomY.clear();
            buildingBottomY.reserve(buildingItems.size());
            for (const BuildingDrawItem& b : buildingItems) buildingBottomY.push_back(b.bottomY);
            gamecore::map::mergeObjectLayerOrder(
                decorBottomY.data(), static_cast<int>(decorBottomY.size()),
                buildingBottomY.data(), static_cast<int>(buildingBottomY.size()),
                mergedOrder.data());
        }

        size_t di = 0;
        size_t bi = 0;
        for (size_t k = 0; k < mergedOrder.size(); k++) {
            if (mergedOrder[k] == 0) {
                const ObjectDecorDrawItem& d = decorItems[di++];
                batcher.add(p.atlasTexId, d.x, d.y, d.w, d.h, d.u0, d.v0, d.u1, d.v1,
                    1.0f, 1.0f, 1.0f, fadeAlpha);
                continue;
            }
            const BuildingDrawItem& b = buildingItems[bi++];
            // (A2) 建筑投影阴影（精灵之下；固定结构不投影）
            if (b.shadow) {
                float shx = b.floorX + tileSizeF * kShadowOffsetTiles;
                float shy = b.floorY + tileSizeF * kShadowOffsetTiles;
                batcher.add(0, shx, shy, b.floorW, b.floorH,
                    0.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, kShadowAlpha * fadeAlpha);
            }
            // (B) 建筑精灵
            batcher.add(p.atlasTexId, b.x, b.y, b.w, b.h,
                p.buildingUv[b.uvIndex * 4] + kSceneUvEpsilon,
                p.buildingUv[b.uvIndex * 4 + 1] + kSceneUvEpsilon,
                p.buildingUv[b.uvIndex * 4 + 2] - kSceneUvEpsilon,
                p.buildingUv[b.uvIndex * 4 + 3] - kSceneUvEpsilon,
                1.0f, 1.0f, 1.0f, fadeAlpha);
        }
    } else if (!decorItems.empty()) {
        // 建筑层关闭/无建筑：立体装饰仍须绘制（单独成序——已按行序升序收集）
        for (const ObjectDecorDrawItem& d : decorItems) {
            batcher.add(p.atlasTexId, d.x, d.y, d.w, d.h, d.u0, d.v0, d.u1, d.v1,
                1.0f, 1.0f, 1.0f, fadeAlpha);
        }
    }

    // ---- 3.（续）灵田作物层（建筑精灵之上）----
    // 阶段索引 + 阶段内淡化 alpha 与 Kotlin SpiritCropRender 同数学。
    // 激活条件 = 数据通道在场（与旧路径 cropData && cropUVMap 同）——
    // 空数组时绘制循环空转，但**帧末残留清理仍须执行**（清收获/拆除格的
    // 插值基准，防同格重播时从陈旧进度起算）
    if (p.crops != nullptr && p.cropUv != nullptr) {
        for (int i = 0; i < p.cropCount; i++) {
            const int idx = i * 3;
            float gx = p.crops[idx];
            float gy = p.crops[idx + 1];
            float progress = p.crops[idx + 2];

            // NaN/越界防御：非法数据不画任何像素（数据篡改防御层）
            if (progress != progress || progress < 0.0f || progress > 1.0f) continue;
            if (gx != gx || gy != gy) continue;

            // 帧间平滑（与 Kotlin SpiritCropRender.smoothedProgress 同数学）；
            // 超界 float→int 是 UB → 多值收敛同 key 串扰（1e6 上限防御）
            if (gx < -1e6f || gx > 1e6f || gy < -1e6f || gy > 1e6f) continue;
            const int64_t key = (static_cast<int64_t>(static_cast<int>(gx)) << 32) |
                                static_cast<int64_t>(static_cast<int>(gy));
            cropState.activeKeys.push_back(key);
            float drawProgress = progress;
            const auto prevIt = cropState.lastProgress.find(key);
            if (prevIt != cropState.lastProgress.end()) {
                const float prev = prevIt->second;
                float a = p.frameAlpha;
                if (a != a) {
                    a = 0.0f;
                } else if (a < 0.0f) {
                    a = 0.0f;
                } else if (a > 1.0f) {
                    a = 1.0f;
                }
                drawProgress = prev + (progress - prev) * a;
                if (drawProgress < 0.0f) drawProgress = 0.0f;
                if (drawProgress > 1.0f) drawProgress = 1.0f;
            }
            cropState.lastProgress[key] = progress;

            int stage;
            float alpha;
            if (drawProgress < 1.0f / 3.0f) {
                stage = 0;
                alpha = drawProgress * 3.0f;
            } else if (drawProgress < 2.0f / 3.0f) {
                stage = 1;
                alpha = (drawProgress - 1.0f / 3.0f) * 3.0f;
            } else {
                stage = 2;
                alpha = (drawProgress - 2.0f / 3.0f) * 3.0f;
            }
            if (stage >= p.cropUvCount) continue;

            float px = gx * tileSizeF;
            float py = gy * tileSizeF;
            if (!sceneRectVisible(px, py, tileSizeF, tileSizeF,
                                  p.viewLeft, p.viewTop, p.viewRight, p.viewBottom)) continue;

            batcher.add(p.atlasTexId, px, py, tileSizeF, tileSizeF,
                p.cropUv[stage * 4] + kSceneUvEpsilon,
                p.cropUv[stage * 4 + 1] + kSceneUvEpsilon,
                p.cropUv[stage * 4 + 2] - kSceneUvEpsilon,
                p.cropUv[stage * 4 + 3] - kSceneUvEpsilon,
                1.0f, 1.0f, 1.0f, alpha * fadeAlpha);
        }

        // 帧末裁剪：无作物的格清除残留进度条目（收获/拆除场景）
        for (auto it = cropState.lastProgress.begin(); it != cropState.lastProgress.end();) {
            const bool active = std::find(cropState.activeKeys.begin(), cropState.activeKeys.end(),
                it->first) != cropState.activeKeys.end();
            if (!active) {
                it = cropState.lastProgress.erase(it);
            } else {
                ++it;
            }
        }
        cropState.activeKeys.clear();
    }

    // ---- 3.5 云层（世界顶部动态云朵——建筑/作物之上、UI 之下）----
    if (p.clouds != nullptr && p.cloudUv != nullptr && p.cloudCount > 0 && !p.skipClouds) {
        for (int i = 0; i < p.cloudCount; i++) {
            const int idx = i * kCloudStride;
            float cx = p.clouds[idx];
            float cy = p.clouds[idx + 1];
            float cw = p.clouds[idx + 2];
            float ch = p.clouds[idx + 3];
            float alpha = p.clouds[idx + 5];

            // NaN/非法值防御（alpha 用显式 NaN 判定——NaN 比较恒 false 穿透区间检查）
            if (cx != cx || cy != cy || cw != cw || ch != ch) continue;
            if (cw <= 0.0f || ch <= 0.0f) continue;
            if (alpha != alpha || alpha < 0.0f || alpha > 1.0f) continue;
            if (cx < -1e6f || cx > 1e6f || cy < -1e6f || cy > 1e6f) continue;

            if (!sceneRectVisible(cx, cy, cw, ch,
                                  p.viewLeft, p.viewTop, p.viewRight, p.viewBottom)) continue;

            // spriteIndex → UV 索引（NaN/负值/越界统一回退 0）
            float spriteF = p.clouds[idx + 4];
            int uvIdx;
            if (!(spriteF >= 0.0f && spriteF < static_cast<float>(p.cloudUvCount))) {
                uvIdx = 0;
            } else {
                uvIdx = static_cast<int>(spriteF);
            }

            batcher.add(p.atlasTexId, cx, cy, cw, ch,
                p.cloudUv[uvIdx * 4] + kSceneUvEpsilon,
                p.cloudUv[uvIdx * 4 + 1] + kSceneUvEpsilon,
                p.cloudUv[uvIdx * 4 + 2] - kSceneUvEpsilon,
                p.cloudUv[uvIdx * 4 + 3] - kSceneUvEpsilon,
                1.0f, 1.0f, 1.0f, alpha * fadeAlpha);
        }
    }

    return batcher.end();
}

/// 崖壁层构建 + 逐纹理连续段提交（z 序：天空 → 崖壁 → 地面）。
///
/// submit(texId, vertices, count) 在每个纹理连续段边界被调（生产 =
/// renderer->draw；测试 = 顶点流记录器）。独立纹理 UV 不加 UV_EPSILON
/// （独立纹理无图集邻居，加偏移会在地图边界露 0.5 纹素透明缝）；
/// 镜像条目 u0>u1 取 min/max 归一（批只接受 u0 ≤ u1 矩形语义）。
template <typename Submit>
inline void buildCliffLayer(SpriteBatcher& batcher, const float projMatrix[16],
                            const CliffLayerParams& p, Submit&& submit) {
    if (p.data == nullptr || p.pieceCount <= 0 || p.texIds == nullptr || p.texCount <= 0) return;

    const float fadeAlpha = p.fadeAlpha;
    const float gapEpsilon = sceneGapEpsilon(p.scale);
    constexpr int kStride = kCliffStride;

    batcher.begin(projMatrix);

    uint32_t batchTexId = 0;
    bool haveBatch = false;

    for (int i = 0; i < p.pieceCount; i++) {
        const int base = i * kStride;
        const int32_t texIdx = static_cast<int32_t>(p.data[base]);
        const float sx = p.data[base + 1];
        const float sy = p.data[base + 2];
        const float sw = p.data[base + 3];
        const float sh = p.data[base + 4];
        float u0 = p.data[base + 5];
        float v0 = p.data[base + 6];
        float u1 = p.data[base + 7];
        float v1 = p.data[base + 8];

        // 纹理缺失降级（上传失败/越界）→ 跳过该条目，不画白、不崩溃
        if (texIdx < 0 || texIdx >= p.texCount) continue;
        const uint32_t texId = p.texIds[texIdx];
        if (texId == 0) continue;

        // NaN/非法值防御 + 镜像/裁剪归一 + UV 范围守卫（与旧实现同式）
        const bool badFloat = (sx != sx) || (sy != sy) || (sw != sw) || (sh != sh) ||
                              (u0 != u0) || (v0 != v0) || (u1 != u1) || (v1 != v1);
        if (badFloat) continue;
        if (sw <= 0.0f || sh <= 0.0f) continue;
        if (!sceneRectVisible(sx, sy, sw, sh,
                              p.viewLeft, p.viewTop, p.viewRight, p.viewBottom)) continue;
        if (u0 > u1) { const float t = u0; u0 = u1; u1 = t; }
        if (v0 > v1) { const float t = v0; v0 = v1; v1 = t; }
        if (u0 < 0.0f || v0 < 0.0f || u1 > 1.0f || v1 > 1.0f) continue;

        if (!haveBatch || texId != batchTexId) {
            if (haveBatch && batcher.vertexCount > 0) {
                submit(batchTexId, batcher.vertices, batcher.vertexCount);
            }
            batcher.begin(projMatrix);
            batchTexId = texId;
            haveBatch = true;
        }

        batcher.add(texId,
            sx - gapEpsilon, sy - gapEpsilon,
            sw + 2.0f * gapEpsilon, sh + 2.0f * gapEpsilon,
            u0, v0, u1, v1,
            1.0f, 1.0f, 1.0f, fadeAlpha);
    }

    if (haveBatch && batcher.vertexCount > 0) {
        submit(batchTexId, batcher.vertices, batcher.vertexCount);
    }
}

/// 叠加层占地尺寸（高亮层口径）——与旧 Kotlin
/// `SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.getOrElse(nameIdx) { 2 to 2 }` 逐位
/// 同语义：表内直取，表外（含固定结构 nameIdx ≥ kStructureNameBase）回退 2×2。
/// **与地图建筑层的占地判定不同**（那一层对固定结构另有 kStructureFpW/H 分支）——
/// 高亮框沿用旧路径口径以保证两路等价（固定结构高亮框偏小属既有缺陷，
/// 按方案 §7.2 登记留待后续批裁决，不在等价重构批顺手改）。
inline void overlayFootprint(int32_t nameIdx, int32_t* outW, int32_t* outH) {
    const int32_t fpCount = static_cast<int32_t>(sizeof(kFootprintW) / sizeof(kFootprintW[0]));
    if (nameIdx >= 0 && nameIdx < fpCount) {
        *outW = kFootprintW[nameIdx];
        *outH = kFootprintH[nameIdx];
    } else {
        *outW = 2;
        *outH = 2;
    }
}

/// 屏幕线宽折算世界线宽（高亮/描边类）：max(2px, tileSize×0.06×scale) / scale
/// ——与旧 Kotlin `maxOf(2f, tileSize * HIGHLIGHT_LINE_WIDTH_TILES * scale) / scale`
/// 同式同序（乘法左结合序一致，逐位等价）。
inline float overlayLineWidth(float tileSizeF, float scale) {
    const float scaleSafe = (scale > kOverlayMinScale) ? scale : kOverlayMinScale;
    return std::max(kHighlightLineMinPx, tileSizeF * kHighlightLineWidthTiles * scaleSafe) / scaleSafe;
}

/// 叠加层（overlay）几何生成 + 逐纹理连续段提交（R3.3/B11）。
///
/// **旧路径每帧逐 rect 跨线的替身**：网格线 / 放置预览框 / 选中高亮 / 拆除高亮
/// 四类叠加层的全部矩形（含预览精灵）在此按 SceneStore 状态 + 相机算出，
/// 层序与旧 Kotlin 路径严格一致：
///   选中高亮 → 拆除高亮 → 预览精灵 → 占地框 → 网格线
/// （精灵绘于两处高亮之上、占地框与网格线之下——"填充绿纱罩于精灵之上"的
///   既有观感；预览精灵用图集纹理，故其前后各成一段颜色批）。
///
/// 与旧路径逐条 drawRect 的关系：每个矩形仍是**同一 6 顶点三角带**
/// （SpriteBatcher.add 的顶点序与 NativeBridge.drawRect 手写 6 顶点完全一致），
/// 仅按纹理合批 ⇒ 顶点流逐位相同、draw call 数从"每 rect 一次"降为"每纹理段一次"
/// （由 scene_overlay_equivalence_test 锁定）。
///
/// 覆盖层**不乘 fadeAlpha**（与旧路径一致：淡入只作用于地图层 quad，
/// 预览/高亮不受影响——双端同语义）。
template <typename Submit>
inline void buildOverlayLayers(SpriteBatcher& batcher, const float projMatrix[16],
                               const OverlayParams& p, Submit&& submit) {
    const float tileSizeF = static_cast<float>(p.tileSize);
    const float scaleSafe = (p.scale > kOverlayMinScale) ? p.scale : kOverlayMinScale;

    bool haveBatch = false;
    uint32_t batchTexId = 0;
    auto flushBatch = [&]() {
        if (haveBatch && batcher.vertexCount > 0) {
            submit(batchTexId, batcher.vertices, batcher.vertexCount);
        }
        haveBatch = false;
    };
    auto emit = [&](uint32_t texId, float x, float y, float w, float h,
                    float u0, float v0, float u1, float v1,
                    float r, float g, float b, float a) {
        if (haveBatch && texId != batchTexId) flushBatch();
        if (!haveBatch) {
            batcher.begin(projMatrix);
            batchTexId = texId;
            haveBatch = true;
        }
        batcher.add(texId, x, y, w, h, u0, v0, u1, v1, r, g, b, a);
    };
    // 纯色矩形（图集外——高亮/网格线/占地框）
    auto emitRect = [&](float x, float y, float w, float h,
                        float r, float g, float b, float a) {
        emit(0u, x, y, w, h, 0.0f, 0.0f, 0.0f, 0.0f, r, g, b, a);
    };
    // 填充 + 四边描边（描边盖住填充边缘，避免颜色叠加发亮——旧路径同序）
    auto emitBox = [&](float x, float y, float w, float h, float lw,
                       float r, float g, float b, float fillAlpha, float edgeAlpha) {
        emitRect(x, y, w, h, r, g, b, fillAlpha);
        emitRect(x, y, w, lw, r, g, b, edgeAlpha);
        emitRect(x, (y + h) - lw, w, lw, r, g, b, edgeAlpha);
        emitRect(x, y, lw, h, r, g, b, edgeAlpha);
        emitRect((x + w) - lw, y, lw, h, r, g, b, edgeAlpha);
    };
    // 建筑条目占地矩形（gridX/gridY/nameIdx → 世界矩形；旧路径同口径）
    auto footprintRect = [&](int32_t buildingIndex, float* out) {
        const int32_t base = buildingIndex * kBuildingStride;
        const int32_t gx = static_cast<int32_t>(p.buildings[base]);
        const int32_t gy = static_cast<int32_t>(p.buildings[base + 1]);
        const int32_t nameIdx = static_cast<int32_t>(p.buildings[base + 4]);
        int32_t fpW = 2;
        int32_t fpH = 2;
        overlayFootprint(nameIdx, &fpW, &fpH);
        out[0] = static_cast<float>(gx) * tileSizeF;
        out[1] = static_cast<float>(gy) * tileSizeF;
        out[2] = static_cast<float>(fpW) * tileSizeF;
        out[3] = static_cast<float>(fpH) * tileSizeF;
    };

    // ---- 1. 选中建筑高亮（金色描边 + 半透明填充，5 rect）----
    if (p.selectionEnabled && p.selectionHighlightFlag &&
        p.buildings != nullptr && p.selectionIndex >= 0 && p.selectionIndex < p.buildingCount) {
        float rect[4];
        footprintRect(p.selectionIndex, rect);
        emitBox(rect[0], rect[1], rect[2], rect[3], overlayLineWidth(tileSizeF, p.scale),
            kGoldR, kGoldG, kGoldB, kHighlightFillAlpha, kHighlightEdgeAlpha);
    }

    // ---- 2. 一键拆除占地高亮（逐建筑绿填充 / 选中红填充 + 红描边）----
    if (p.demolishEnabled && p.buildings != nullptr && p.markers != nullptr) {
        const int32_t count = std::min(p.buildingCount, p.markerCount);
        float rect[4];
        for (int32_t i = 0; i < count; i++) {
            if (p.markers[i] == kDemolishMarkNone) continue;
            footprintRect(i, rect);
            if (p.markers[i] == kDemolishMarkSelected) {
                emitBox(rect[0], rect[1], rect[2], rect[3], overlayLineWidth(tileSizeF, p.scale),
                    kDemolishRedR, kDemolishRedG, kDemolishRedB,
                    kDemolishFillAlpha, kDemolishEdgeAlpha);
            } else {
                emitRect(rect[0], rect[1], rect[2], rect[3],
                    kDemolishGreenR, kDemolishGreenG, kDemolishGreenB, kDemolishFillAlpha);
            }
        }
    }

    // ---- 3. 放置/移动预览（精灵图集 sprite，其后叠占地框——图集纹理单独成段）----
    const bool previewUsable = p.previewSpriteVisible && p.atlasTexId != 0 &&
        previewFinite(p.preview);
    if (previewUsable) {
        // UV 向内收缩同旧 drawSprite（防图集邻居渗色）
        emit(p.atlasTexId,
            p.preview.spriteX, p.preview.spriteY, p.preview.spriteW, p.preview.spriteH,
            p.preview.u0 + kSceneUvEpsilon, p.preview.v0 + kSceneUvEpsilon,
            p.preview.u1 - kSceneUvEpsilon, p.preview.v1 - kSceneUvEpsilon,
            p.preview.r, p.preview.g, p.preview.b, p.preview.a);

        // ---- 4. 占地框（预览框）：绿=可放置 / 红=不可放置 ----
        if (p.previewBoxVisible) {
            const float br = p.previewValid ? kPreviewGreenR : kPreviewRedR;
            const float bg = p.previewValid ? kPreviewGreenG : kPreviewRedG;
            const float bb = p.previewValid ? kPreviewGreenB : kPreviewRedB;
            emitBox(p.preview.boxX, p.preview.boxY, p.preview.boxW, p.preview.boxH,
                overlayLineWidth(tileSizeF, p.scale), br, bg, bb,
                kPreviewBoxFillAlpha, kPreviewBoxEdgeAlpha);
        }
    }

    // ---- 5. 放置/移动模式全视口网格线（视口内逐列/逐行各一条薄矩形）----
    // 视口非法（0/负）整层跳过 = 旧 Kotlin drawGridOverlay 的同语义早退
    if (p.gridVisible && p.viewportW > 0 && p.viewportH > 0 &&
        p.cols > 0 && p.rows > 0 && p.tileSize > 0) {
        const float worldW = static_cast<float>(p.cols * p.tileSize);
        const float worldH = static_cast<float>(p.rows * p.tileSize);
        // 目标屏幕线宽 2px 折回世界坐标；下限 0.5 世界单位防退化 quad
        const float lineWidth = std::max(kGridLineWidthMinWorld, kGridLineWidthPx / scaleSafe);
        const int32_t firstCol = std::max(0, static_cast<int32_t>(p.camX / tileSizeF));
        const int32_t lastCol = std::min(p.cols,
            static_cast<int32_t>((p.camX + static_cast<float>(p.viewportW) / scaleSafe) / tileSizeF));
        const int32_t firstRow = std::max(0, static_cast<int32_t>(p.camY / tileSizeF));
        // 行范围按视口高 / scale 换算（**未乘俯视 Y 压缩系数**）——逐位复刻旧
        // Kotlin drawGridOverlay 现状（含与 Canvas 侧不一致的那部分），
        // 差异根因与处置见方案 §7.2 B11 段「前置缺陷 A」。
        const int32_t lastRow = std::min(p.rows,
            static_cast<int32_t>((p.camY + static_cast<float>(p.viewportH) / scaleSafe) / tileSizeF));

        for (int32_t col = firstCol; col <= lastCol; col++) {
            emitRect(static_cast<float>(col) * tileSizeF, 0.0f, lineWidth, worldH,
                kGridR, kGridG, kGridB, kGridAlpha);
        }
        for (int32_t row = firstRow; row <= lastRow; row++) {
            emitRect(0.0f, static_cast<float>(row) * tileSizeF, worldW, lineWidth,
                kGridR, kGridG, kGridB, kGridAlpha);
        }
    }

    flushBatch();
}

}  // namespace scene

