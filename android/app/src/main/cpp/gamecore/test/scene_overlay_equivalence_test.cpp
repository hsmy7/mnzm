#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "Rhi.h"
#include "SpriteBatcher.h"
#include "scene/scene_store.h"
#include "scene/scene_draw.h"

namespace {

using gamecore::map::computeGroundBoundary;
using gamecore::map::GroundBoundaryConfig;
using scene::buildBottomRockLayer;
using scene::buildGroundMeshLayer;
using scene::GroundBoundaryView;
using scene::groundBoundaryParse;
using scene::buildMapBatch;
using scene::buildOverlayLayers;
using scene::CropSmoothingState;
using scene::kBuildingStride;
using scene::kPreviewStride;
using scene::MapLayerParams;
using scene::OverlayParams;
using scene::PreviewState;
using scene::SceneStore;

// ============================================================
// 叠加层等价性守卫（重构方案 2026-09-17 R3.3/B11 验收门 5——本批硬证据）
//
// **证明目标**：C++ 生成的叠加层几何（scene_draw.h::buildOverlayLayers，由
// drawFrame 的 overlayFlags 模式位驱动）与旧 Kotlin 路径逐 rect 跨线
// （VulkanRenderBackend 的 drawSelectionHighlight / drawDemolishHighlight /
// drawPreviewHighlight / drawGridOverlay + NativeBridge.drawSprite）
// **逐顶点全等**：同矩形集合 + 同绘制序 + 同 px/py/u/v/r/g/b/a。
//
// 唯一被有意改变的是 **draw call 数**（本批量化目标 G4）：旧路径每个 rect 一次
// renderer->draw（= 一次 JNI + 一次 vkCmdDraw——VulkanBackend 逐 m_pendingDraws
// 条目发一条绘制命令），新路径按纹理合批。故断言两条：
//   ① 展平顶点流逐位全等（矩形内容与绘制序完全不变）；
//   ② 纹理段 run-length 序一致（合批只合并同纹理连续段）+ 新路径段数上限。
//
// 逐位等价的三处前提，缺一即漂移：
//   1. 顶点构造同式——drawRectModel/drawSpriteModel 转写自 NativeBridge.cpp，
//      与 SpriteBatcher::add 的 6 顶点序逐项一致；
//   2. 常量同源——build-atlas.mjs LAYOUT.overlay 单源生成 Kotlin
//      SpriteAtlasDef 与 C++ scene_uv_tables.h，旧路径以别名引用（值不可漂移）；
//   3. 位定义同值——scene::kOverlayBit* ↔ Kotlin OVERLAY_FLAG_*
//      （SceneUvTablesMirrorGuardTest 静态锁）。
//
// 覆盖矩阵（验收门 5）：四要素 × 相机三档位 × overlayFlags 组合
//   （单要素 × 3 档 + 四要素同帧 + 逐位单独点亮 + 抑制位/特性开关/图集未就绪 +
//    越界与空数据 + 占地表外回退 + 非有限值加固 + G4 量化 + 缺陷 A 现状锁定）。
// ============================================================

// 记录器后端：捕获 draw(verts, count, texId) 顶点流——每次 draw = 一次 vkCmdDraw
struct RecorderRenderer : public Renderer2D {
    struct DrawCall {
        uint32_t texId = 0;
        std::vector<SpriteVertex> verts;
    };
    std::vector<DrawCall> calls;

    void draw(const SpriteVertex* v, int count, uint32_t texId) override {
        DrawCall c;
        c.texId = texId;
        c.verts.assign(v, v + count);
        calls.push_back(std::move(c));
    }

    size_t rectCount() const {
        size_t verts = 0;
        for (const DrawCall& c : calls) verts += c.verts.size();
        return verts / 6;  // 每矩形 6 顶点（两臂同一构造）
    }

    // 其余 Rhi 接口本测试不触达（空实现）
    bool init(const RenderConfig&, void*) override { return true; }
    void shutdown() override {}
    bool resize(int, int) override { return true; }
    RenderInitError lastInitError() const override { return RenderInitError::NONE; }
    void beginFrame() override {}
    void endFrame() override {}
    bool isReady() const override { return true; }
    uint32_t uploadTexture(const void*, int, int) override { return 1; }
    void destroyTexture(uint32_t) override {}
    void setProjection(const float[16]) override {}
    void submitFrame() override {}
    void drawBackground(const SpriteVertex*, int, const SkyGradientParams&) override {}
};

// ── 夹具 ────────────────────────────────────────────────────

constexpr int kCols = 16;
constexpr int kRows = 16;
constexpr int kTileSize = 48;
constexpr uint32_t kAtlasTexId = 42;

/// 真实地图尺寸（GameConfig.SectMap.WORLD_WIDTH_CELLS=128 × TILE_SIZE=48）与
/// 手机视口——整岛可见最坏档用它算网格线根数（旧路径逐 rect 跨线的主体来源）
constexpr int kRealCols = 128;
constexpr int kRealRows = 128;
constexpr int kVpW = 1080;
constexpr int kVpH = 1920;

/// 相机三档位（近 2.0 / 中 1.0 / 远 0.3）——网格线根数随缩放变化
struct CameraView {
    float camX, camY, scale;
    int vpW, vpH;
};
constexpr CameraView kViews[3] = {
    {0.0f, 0.0f, 2.0f, kVpW, kVpH},
    {96.0f, 48.0f, 1.0f, kVpW, kVpH},
    {0.0f, 0.0f, 0.3f, kVpW, kVpH},
};

/// 叠加层输入用例（两臂共用的唯一事实源）
struct OverlayCase {
    CameraView view{0.0f, 0.0f, 1.0f, kVpW, kVpH};
    int cols = kCols;
    int rows = kRows;
    int tileSize = kTileSize;

    std::vector<float> buildings;  // [gx,gy,sw,sh,nameIdx]×N
    int buildingCount = 0;
    std::vector<uint8_t> markers;  // 逐建筑拆除标记（与 buildings 同序）
    int selectionIndex = -1;
    PreviewState preview;
    uint32_t atlasTexId = kAtlasTexId;

    /// drawFrame 的 overlayFlags 掩码（bit5/bit6 = 0 表示总线脏帧抑制）
    int32_t flags = 0;
    /// RenderFlags.selectionHighlight（旧路径 Kotlin 判定 / 新路径 setRenderFlags 通道）
    bool selectionHighlightFlag = true;
};

/// 三栋建筑：灵田（1×1，nameIdx=2）、灵矿场（4×4，nameIdx=0）、
/// 固定结构门楼（nameIdx=19 → 高亮层占地表外，旧 Kotlin 回退 2×2）
std::vector<float> fixtureBuildings() {
    return {
        3.0f, 2.0f, 1.0f, 1.0f, 2.0f,
        7.0f, 5.0f, 4.0f, 4.0f, 0.0f,
        1.0f, 0.0f, 6.0f, 2.0f, 19.0f,
    };
}

PreviewState fixturePreview() {
    PreviewState p;
    p.boxX = 240.0f;
    p.boxY = 132.0f;
    p.boxW = 96.0f;
    p.boxH = 144.0f;
    p.spriteX = 246.5f;
    p.spriteY = 150.25f;
    p.spriteW = 83.0f;
    p.spriteH = 120.5f;
    p.u0 = 1044.0f / 4096.0f;
    p.v0 = 516.0f / 4096.0f;
    p.u1 = 1172.0f / 4096.0f;
    p.v1 = 644.0f / 4096.0f;
    p.r = 1.0f;
    p.g = 1.0f;
    p.b = 1.0f;
    p.a = 1.0f;
    return p;
}

OverlayCase baseCase(const CameraView& view) {
    OverlayCase c;
    c.view = view;
    c.buildings = fixtureBuildings();
    c.buildingCount = 3;
    c.preview = fixturePreview();
    return c;
}

constexpr int32_t kAllOverlayBits = scene::kOverlayBitGridVisible |
    scene::kOverlayBitPreviewSprite | scene::kOverlayBitPreviewBox |
    scene::kOverlayBitPreviewValid | scene::kOverlayBitSelection |
    scene::kOverlayBitDemolish;

// ── 旧路径模型（逐 rect 一次 draw）───────────────────────────
// 逐式转写自 VulkanRenderBackend.kt（R3.3 开工前现状；回滚臂原样保留）：
//   drawSelectionHighlight / drawDemolishHighlight / drawPreviewHighlight /
//   drawGridOverlay + NativeBridge.drawSprite 调用点。
// 顶点构造转写自 NativeBridge.cpp 的 drawRect / drawSprite。

/** NativeBridge.drawRect 的 6 顶点（纯色矩形，u=v=0） */
void drawRectModel(RecorderRenderer& rec, float x, float y, float w, float h,
                   float r, float g, float b, float a) {
    SpriteVertex verts[6]{};
    for (int i = 0; i < 6; i++) verts[i] = {0, 0, 0, 0, r, g, b, a};
    verts[0] = {x, y, 0, 0, r, g, b, a};
    verts[1] = {x + w, y, 0, 0, r, g, b, a};
    verts[2] = {x, y + h, 0, 0, r, g, b, a};
    verts[3] = {x + w, y, 0, 0, r, g, b, a};
    verts[4] = {x + w, y + h, 0, 0, r, g, b, a};
    verts[5] = {x, y + h, 0, 0, r, g, b, a};
    rec.draw(verts, 6, 0);
}

/** NativeBridge.drawSprite 的 6 顶点（UV 已含 kUvEpsilon 收缩） */
void drawSpriteModel(RecorderRenderer& rec, const OverlayCase& c) {
    const PreviewState& p = c.preview;
    const float su0 = p.u0 + scene::kUvEpsilon;
    const float sv0 = p.v0 + scene::kUvEpsilon;
    const float su1 = p.u1 - scene::kUvEpsilon;
    const float sv1 = p.v1 - scene::kUvEpsilon;
    const float x = p.spriteX;
    const float y = p.spriteY;
    const float w = p.spriteW;
    const float h = p.spriteH;
    SpriteVertex verts[6]{};
    for (int i = 0; i < 6; i++) verts[i] = {0, 0, 0, 0, p.r, p.g, p.b, p.a};
    verts[0] = {x, y, su0, sv0, p.r, p.g, p.b, p.a};
    verts[1] = {x + w, y, su1, sv0, p.r, p.g, p.b, p.a};
    verts[2] = {x, y + h, su0, sv1, p.r, p.g, p.b, p.a};
    verts[3] = {x + w, y, su1, sv0, p.r, p.g, p.b, p.a};
    verts[4] = {x + w, y + h, su1, sv1, p.r, p.g, p.b, p.a};
    verts[5] = {x, y + h, su0, sv1, p.r, p.g, p.b, p.a};
    rec.draw(verts, 6, c.atlasTexId);
}

/// 旧 Kotlin 占地口径：`FOOTPRINT_BY_NAME_INDEX.getOrElse(nameIdx) { 2 to 2 }`
/// （固定结构 nameIdx=19 在表外 ⇒ 回退 2×2，与地图建筑层的结构分支不同）
void footprintModel(int32_t nameIdx, int32_t* outW, int32_t* outH) {
    const int32_t count =
        static_cast<int32_t>(sizeof(scene::kFootprintW) / sizeof(scene::kFootprintW[0]));
    if (nameIdx >= 0 && nameIdx < count) {
        *outW = scene::kFootprintW[nameIdx];
        *outH = scene::kFootprintH[nameIdx];
    } else {
        *outW = 2;
        *outH = 2;
    }
}

float modelScale(const OverlayCase& c) {
    return std::max(c.view.scale, scene::kOverlayMinScale);
}

/// 旧 Kotlin 线宽：maxOf(2f, tileSize × 0.06f × scale) / scale
float lineWidthModel(float tileSizeF, float scale) {
    const float scaleSafe = std::max(scale, scene::kOverlayMinScale);
    return std::max(scene::kHighlightLineMinPx,
        tileSizeF * scene::kHighlightLineWidthTiles * scaleSafe) / scaleSafe;
}

/** 旧 Kotlin：填充 → 上边 → 下边 → 左边 → 右边（5 次 drawRect） */
void drawBoxModel(RecorderRenderer& rec, float x, float y, float w, float h, float lw,
                  float r, float g, float b, float fillAlpha, float edgeAlpha) {
    drawRectModel(rec, x, y, w, h, r, g, b, fillAlpha);
    drawRectModel(rec, x, y, w, lw, r, g, b, edgeAlpha);
    drawRectModel(rec, x, (y + h) - lw, w, lw, r, g, b, edgeAlpha);
    drawRectModel(rec, x, y, lw, h, r, g, b, edgeAlpha);
    drawRectModel(rec, (x + w) - lw, y, lw, h, r, g, b, edgeAlpha);
}

/// 建筑条目占地矩形（旧 Kotlin：buildingData[i*5].toInt() × tileSize）
void footprintRectModel(const OverlayCase& c, int32_t index, float* out) {
    const int32_t base = index * kBuildingStride;
    const int32_t gx = static_cast<int32_t>(c.buildings[base]);
    const int32_t gy = static_cast<int32_t>(c.buildings[base + 1]);
    const int32_t nameIdx = static_cast<int32_t>(c.buildings[base + 4]);
    int32_t fpW = 2;
    int32_t fpH = 2;
    footprintModel(nameIdx, &fpW, &fpH);
    const float tileSizeF = static_cast<float>(c.tileSize);
    out[0] = static_cast<float>(gx) * tileSizeF;
    out[1] = static_cast<float>(gy) * tileSizeF;
    out[2] = static_cast<float>(fpW) * tileSizeF;
    out[3] = static_cast<float>(fpH) * tileSizeF;
}

void drawOverlaySelectionModel(RecorderRenderer& rec, const OverlayCase& c) {
    const bool busWasDirty = (c.flags & scene::kOverlayBitSelection) == 0;
    if (busWasDirty || !c.selectionHighlightFlag) return;
    const int32_t index = c.selectionIndex;
    if (c.buildings.empty() || index < 0 || index >= c.buildingCount) return;
    float rect[4];
    footprintRectModel(c, index, rect);
    drawBoxModel(rec, rect[0], rect[1], rect[2], rect[3],
        lineWidthModel(static_cast<float>(c.tileSize), modelScale(c)),
        scene::kGoldR, scene::kGoldG, scene::kGoldB,
        scene::kHighlightFillAlpha, scene::kHighlightEdgeAlpha);
}

void drawOverlayDemolishModel(RecorderRenderer& rec, const OverlayCase& c) {
    const bool busWasDirty = (c.flags & scene::kOverlayBitDemolish) == 0;
    if (busWasDirty || c.markers.empty() || c.buildings.empty()) return;
    const float lw = lineWidthModel(static_cast<float>(c.tileSize), modelScale(c));
    const int32_t count = std::min(c.buildingCount, static_cast<int32_t>(c.markers.size()));
    for (int32_t i = 0; i < count; i++) {
        const uint8_t marker = c.markers[i];
        if (marker == scene::kDemolishMarkNone) continue;
        float rect[4];
        footprintRectModel(c, i, rect);
        if (marker == scene::kDemolishMarkSelected) {
            drawBoxModel(rec, rect[0], rect[1], rect[2], rect[3], lw,
                scene::kDemolishRedR, scene::kDemolishRedG, scene::kDemolishRedB,
                scene::kDemolishFillAlpha, scene::kDemolishEdgeAlpha);
        } else {
            drawRectModel(rec, rect[0], rect[1], rect[2], rect[3],
                scene::kDemolishGreenR, scene::kDemolishGreenG, scene::kDemolishGreenB,
                scene::kDemolishFillAlpha);
        }
    }
}

void drawOverlayPreviewModel(RecorderRenderer& rec, const OverlayCase& c) {
    if ((c.flags & scene::kOverlayBitPreviewSprite) == 0 || c.atlasTexId == 0) return;
    drawSpriteModel(rec, c);
    if ((c.flags & scene::kOverlayBitPreviewBox) == 0) return;
    const bool valid = (c.flags & scene::kOverlayBitPreviewValid) != 0;
    const float br = valid ? scene::kPreviewGreenR : scene::kPreviewRedR;
    const float bg = valid ? scene::kPreviewGreenG : scene::kPreviewRedG;
    const float bb = valid ? scene::kPreviewGreenB : scene::kPreviewRedB;
    drawBoxModel(rec, c.preview.boxX, c.preview.boxY, c.preview.boxW, c.preview.boxH,
        lineWidthModel(static_cast<float>(c.tileSize), modelScale(c)),
        br, bg, bb, scene::kPreviewBoxFillAlpha, scene::kPreviewBoxEdgeAlpha);
}

/// 网格线（旧 Kotlin drawGridOverlay 逐式转写）。
/// `yCompressLastRow` = false 复刻旧 Vulkan 生产现状（**前置缺陷 A**：行范围
/// 漏乘俯视 Y 压缩系数）；true = Canvas/投影口径（SoftwareCanvasBackend 同式）。
void drawOverlayGridModel(RecorderRenderer& rec, const OverlayCase& c, bool yCompressLastRow) {
    if ((c.flags & scene::kOverlayBitGridVisible) == 0) return;
    if (c.view.vpW <= 0 || c.view.vpH <= 0) return;
    const float tileSizeF = static_cast<float>(c.tileSize);
    const float scale = modelScale(c);
    const float worldW = static_cast<float>(c.cols * c.tileSize);
    const float worldH = static_cast<float>(c.rows * c.tileSize);
    const float lineWidth =
        std::max(scene::kGridLineWidthMinWorld, scene::kGridLineWidthPx / scale);
    const int32_t firstCol = std::max(0, static_cast<int32_t>(c.view.camX / tileSizeF));
    const int32_t lastCol = std::min(c.cols,
        static_cast<int32_t>((c.view.camX + static_cast<float>(c.view.vpW) / scale) / tileSizeF));
    const int32_t firstRow = std::max(0, static_cast<int32_t>(c.view.camY / tileSizeF));
    const float viewHeightWorld = yCompressLastRow
        ? static_cast<float>(c.view.vpH) / (scale * scene::kTopdownYScale)
        : static_cast<float>(c.view.vpH) / scale;
    const int32_t lastRow = std::min(c.rows,
        static_cast<int32_t>((c.view.camY + viewHeightWorld) / tileSizeF));
    for (int32_t col = firstCol; col <= lastCol; col++) {
        drawRectModel(rec, static_cast<float>(col) * tileSizeF, 0.0f, lineWidth, worldH,
            scene::kGridR, scene::kGridG, scene::kGridB, scene::kGridAlpha);
    }
    for (int32_t row = firstRow; row <= lastRow; row++) {
        drawRectModel(rec, 0.0f, static_cast<float>(row) * tileSizeF, worldW, lineWidth,
            scene::kGridR, scene::kGridG, scene::kGridB, scene::kGridAlpha);
    }
}

/// 旧路径臂：五段按 Kotlin renderFrame 的层序逐条 draw（每 rect 一次跨线）
std::vector<RecorderRenderer::DrawCall> runOldModel(const OverlayCase& c) {
    RecorderRenderer rec;
    drawOverlaySelectionModel(rec, c);
    drawOverlayDemolishModel(rec, c);
    drawOverlayPreviewModel(rec, c);
    // true ⇒ 行范围按投影可见带（B11 前置缺陷 A 修复后的 Kotlin 回滚臂口径，
    // 与 C++ buildOverlayLayers 同式）
    drawOverlayGridModel(rec, c, true);
    return std::move(rec.calls);
}

/// 新路径臂：SceneStore 叠加层状态导入（与生产端口同一存储路径）+
/// buildOverlayLayers 生成几何
std::vector<RecorderRenderer::DrawCall> runNewModel(const OverlayCase& c) {
    SceneStore store;
    store.setSelection(c.selectionIndex);
    store.setDemolishMarkers(c.markers.empty() ? nullptr : c.markers.data(),
        static_cast<int>(c.markers.size()));
    float values[kPreviewStride] = {
        c.preview.boxX, c.preview.boxY, c.preview.boxW, c.preview.boxH,
        c.preview.spriteX, c.preview.spriteY, c.preview.spriteW, c.preview.spriteH,
        c.preview.u0, c.preview.v0, c.preview.u1, c.preview.v1,
        c.preview.r, c.preview.g, c.preview.b, c.preview.a
    };
    store.setPreview(values);

    RecorderRenderer rec;
    float proj[16];
    cameraProjMatrix(proj, c.view.camX, c.view.camY, c.view.scale,
        static_cast<float>(c.view.vpW), static_cast<float>(c.view.vpH), scene::kTopdownYScale);

    OverlayParams p;
    p.camX = c.view.camX;
    p.camY = c.view.camY;
    p.scale = c.view.scale;
    p.viewportW = c.view.vpW;
    p.viewportH = c.view.vpH;
    p.cols = c.cols;
    p.rows = c.rows;
    p.tileSize = c.tileSize;
    p.buildings = c.buildings.empty() ? nullptr : c.buildings.data();
    p.buildingCount = c.buildingCount;
    p.markers = store.markersData();
    p.markerCount = store.markerCount();
    p.selectionIndex = store.selectionIndex();
    p.preview = store.preview();
    p.atlasTexId = c.atlasTexId;
    p.gridVisible = (c.flags & scene::kOverlayBitGridVisible) != 0;
    p.previewSpriteVisible = (c.flags & scene::kOverlayBitPreviewSprite) != 0;
    p.previewBoxVisible = (c.flags & scene::kOverlayBitPreviewBox) != 0;
    p.previewValid = (c.flags & scene::kOverlayBitPreviewValid) != 0;
    p.selectionEnabled = (c.flags & scene::kOverlayBitSelection) != 0;
    p.demolishEnabled = (c.flags & scene::kOverlayBitDemolish) != 0;
    p.selectionHighlightFlag = c.selectionHighlightFlag;

    SpriteBatcher batcher;
    buildOverlayLayers(batcher, proj, p,
        [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
            rec.draw(verts, count, texId);
        });
    return std::move(rec.calls);
}

// ── 断言 ────────────────────────────────────────────────────

std::vector<SpriteVertex> flatten(const std::vector<RecorderRenderer::DrawCall>& calls) {
    std::vector<SpriteVertex> out;
    for (const auto& c : calls) out.insert(out.end(), c.verts.begin(), c.verts.end());
    return out;
}

/// 纹理段 run-length 序（合批只合并同纹理的连续段，切换序必须一致）
std::vector<uint32_t> textureRuns(const std::vector<RecorderRenderer::DrawCall>& calls) {
    std::vector<uint32_t> runs;
    for (const auto& c : calls) {
        if (c.verts.empty()) continue;
        if (runs.empty() || runs.back() != c.texId) runs.push_back(c.texId);
    }
    return runs;
}

size_t rectsOf(const std::vector<RecorderRenderer::DrawCall>& calls) {
    return flatten(calls).size() / 6;
}

/// 修复前的旧 Vulkan 网格口径（行范围漏乘俯视 Y 压缩系数）——只用于缺陷 A 的
/// 修复前后对照证据（GridRowRangeFollowsProjectedViewportBand）
size_t gridRectsWithUncompressedRowRange(const OverlayCase& c) {
    RecorderRenderer rec;
    drawOverlayGridModel(rec, c, false);
    return rectsOf(std::move(rec.calls));
}

/** 两臂顶点流逐位对照 + 纹理段序一致（本守卫的核心断言） */
void expectEquivalent(const OverlayCase& c, const std::string& what) {
    const auto oldCalls = runOldModel(c);
    const auto newCalls = runNewModel(c);
    const auto oldVerts = flatten(oldCalls);
    const auto newVerts = flatten(newCalls);

    ASSERT_EQ(oldVerts.size(), newVerts.size())
        << what << ": 顶点总数不一致（旧 " << oldVerts.size() << " 新 " << newVerts.size() << "）";
    for (size_t i = 0; i < oldVerts.size(); i++) {
        const SpriteVertex& a = oldVerts[i];
        const SpriteVertex& b = newVerts[i];
        ASSERT_EQ(a.px, b.px) << what << ": 顶点 " << i << " px";
        ASSERT_EQ(a.py, b.py) << what << ": 顶点 " << i << " py";
        ASSERT_EQ(a.u, b.u) << what << ": 顶点 " << i << " u";
        ASSERT_EQ(a.v, b.v) << what << ": 顶点 " << i << " v";
        ASSERT_EQ(a.r, b.r) << what << ": 顶点 " << i << " r";
        ASSERT_EQ(a.g, b.g) << what << ": 顶点 " << i << " g";
        ASSERT_EQ(a.b, b.b) << what << ": 顶点 " << i << " b";
        ASSERT_EQ(a.a, b.a) << what << ": 顶点 " << i << " a";
    }
    EXPECT_EQ(textureRuns(oldCalls), textureRuns(newCalls)) << what << ": 纹理段序不一致";
}

// ── 测试 ────────────────────────────────────────────────────

class SceneOverlayEquivalenceTest : public ::testing::Test {};

// 1. 选中高亮：三档位 × 逐建筑（含占地表外的固定结构回退 2×2）
TEST_F(SceneOverlayEquivalenceTest, SelectionAllCameras) {
    for (const CameraView& view : kViews) {
        for (int32_t idx = 0; idx < 3; idx++) {
            OverlayCase c = baseCase(view);
            c.selectionIndex = idx;
            c.flags = scene::kOverlayBitBuildingVisible | scene::kOverlayBitSelection |
                scene::kOverlayBitDemolish;
            const std::string what =
                "selection scale=" + std::to_string(view.scale) + " idx=" + std::to_string(idx);
            ASSERT_NO_FATAL_FAILURE(expectEquivalent(c, what));
        }
    }
}

// 2. 拆除高亮：NONE/GREEN/SELECTED 三态混排（每建筑 1 填充 或 5 描边矩形）
TEST_F(SceneOverlayEquivalenceTest, DemolishMarksAllCameras) {
    const uint8_t patterns[3][3] = {
        {1, 0, 2},
        {2, 2, 2},
        {0, 0, 1},
    };
    for (const CameraView& view : kViews) {
        for (int pi = 0; pi < 3; pi++) {
            OverlayCase c = baseCase(view);
            c.markers.assign(patterns[pi], patterns[pi] + 3);
            c.flags = scene::kOverlayBitBuildingVisible | scene::kOverlayBitDemolish;
            const std::string what =
                "demolish scale=" + std::to_string(view.scale) + " pattern=" + std::to_string(pi);
            ASSERT_NO_FATAL_FAILURE(expectEquivalent(c, what));
        }
    }
}

// 3. 预览：精灵 + 占地框（可放置绿 / 不可放置红），三档位
TEST_F(SceneOverlayEquivalenceTest, PreviewSpriteAndBoxAllCameras) {
    for (const CameraView& view : kViews) {
        for (int valid = 0; valid <= 1; valid++) {
            OverlayCase c = baseCase(view);
            c.flags = scene::kOverlayBitBuildingVisible | scene::kOverlayBitPreviewSprite |
                scene::kOverlayBitPreviewBox |
                (valid != 0 ? scene::kOverlayBitPreviewValid : 0);
            const std::string what =
                "preview scale=" + std::to_string(view.scale) + " valid=" + std::to_string(valid);
            ASSERT_NO_FATAL_FAILURE(expectEquivalent(c, what));
        }
    }
}

// 4. 网格线：三档位（列/行根数随缩放变化 = 旧路径逐 rect 跨线的主体）
TEST_F(SceneOverlayEquivalenceTest, GridAllCameras) {
    for (const CameraView& view : kViews) {
        OverlayCase c = baseCase(view);
        c.flags = scene::kOverlayBitBuildingVisible | scene::kOverlayBitGridVisible;
        const std::string what = "grid scale=" + std::to_string(view.scale);
        ASSERT_NO_FATAL_FAILURE(expectEquivalent(c, what));
    }
}

// 5. 四要素同帧叠加（层序：选中 → 拆除 → 精灵 → 占地框 → 网格线）
TEST_F(SceneOverlayEquivalenceTest, AllFourLayersStackedAllCameras) {
    for (const CameraView& view : kViews) {
        OverlayCase c = baseCase(view);
        c.selectionIndex = 1;
        c.markers = {1, 2, 0};
        c.flags = scene::kOverlayBitBuildingVisible | kAllOverlayBits |
            scene::kOverlayBitPreviewValid;
        const std::string what = "stack scale=" + std::to_string(view.scale);
        ASSERT_NO_FATAL_FAILURE(expectEquivalent(c, what));

        // 合批形态：颜色段（选中+拆除）→ 图集段（预览精灵）→ 颜色段（框+网格）
        const auto newCalls = runNewModel(c);
        ASSERT_EQ(3u, newCalls.size()) << what;
        EXPECT_EQ(0u, newCalls[0].texId) << what;
        EXPECT_EQ(kAtlasTexId, newCalls[1].texId) << what;
        EXPECT_EQ(0u, newCalls[2].texId) << what;
    }
}

// 6. overlayFlags 逐位单独点亮（含"该位为 0 即整层不画"）
TEST_F(SceneOverlayEquivalenceTest, EachFlagBitAlone) {
    const int32_t bits[] = {
        scene::kOverlayBitGridVisible,
        scene::kOverlayBitPreviewSprite,
        scene::kOverlayBitPreviewBox,
        scene::kOverlayBitSelection,
        scene::kOverlayBitDemolish,
    };
    for (const int32_t bit : bits) {
        OverlayCase c = baseCase(kViews[1]);
        c.selectionIndex = 0;
        c.markers = {1, 1, 1};
        c.flags = scene::kOverlayBitBuildingVisible | bit;
        const std::string what = "bit " + std::to_string(bit);
        ASSERT_NO_FATAL_FAILURE(expectEquivalent(c, what));
    }
    // 全 0 标志：两臂皆空
    OverlayCase none = baseCase(kViews[1]);
    none.selectionIndex = 0;
    none.markers = {1, 1, 1};
    none.flags = 0;
    ASSERT_NO_FATAL_FAILURE(expectEquivalent(none, "flags=0"));
    EXPECT_TRUE(runNewModel(none).empty());
}

// 7. 抑制位与开关：总线脏帧（bit5/bit6 清）+ selectionHighlight 关闭 + 图集未就绪
TEST_F(SceneOverlayEquivalenceTest, SuppressedFramesAndFeatureOff) {
    // 7a. 建筑数据刚被即时通道替换 → 两处高亮本帧抑制（旧路径同语义跳帧）
    OverlayCase c = baseCase(kViews[1]);
    c.selectionIndex = 2;
    c.markers = {2, 1, 0};
    c.flags = scene::kOverlayBitBuildingVisible | scene::kOverlayBitGridVisible |
        scene::kOverlayBitPreviewSprite | scene::kOverlayBitPreviewBox |
        scene::kOverlayBitPreviewValid;
    ASSERT_NO_FATAL_FAILURE(expectEquivalent(c, "busWasDirty"));

    // 7b. 选中高亮特性开关关闭（新路径经 setRenderFlags 通道，旧路径 Kotlin 判定）
    c.flags |= scene::kOverlayBitSelection | scene::kOverlayBitDemolish;
    c.selectionHighlightFlag = false;
    ASSERT_NO_FATAL_FAILURE(expectEquivalent(c, "selectionHighlight off"));

    // 7c. 图集未就绪（0）：预览精灵与占地框整段跳过，网格/高亮仍画（旧路径同守卫）
    c.selectionHighlightFlag = true;
    c.atlasTexId = 0;
    ASSERT_NO_FATAL_FAILURE(expectEquivalent(c, "atlas not ready"));
}

// 8. 越界/空数据输入：两臂同判跳过该层（其余层不受影响）
TEST_F(SceneOverlayEquivalenceTest, OutOfRangeAndEmptyInputs) {
    for (int32_t idx : std::vector<int32_t>{-1, 3, 99}) {
        OverlayCase c = baseCase(kViews[1]);
        c.selectionIndex = idx;
        c.markers = {1, 1, 1};
        c.flags = scene::kOverlayBitBuildingVisible | kAllOverlayBits;
        const std::string what = "selectionIndex=" + std::to_string(idx);
        ASSERT_NO_FATAL_FAILURE(expectEquivalent(c, what));
        EXPECT_GT(rectsOf(runNewModel(c)), 6u) << what << "：其余层应仍在";
    }
    // 无建筑 + 无标记：只剩网格与预览
    OverlayCase empty = baseCase(kViews[1]);
    empty.buildings.clear();
    empty.buildingCount = 0;
    empty.selectionIndex = 0;
    empty.flags = scene::kOverlayBitBuildingVisible | kAllOverlayBits;
    ASSERT_NO_FATAL_FAILURE(expectEquivalent(empty, "no buildings"));
    // 视口 0×0：网格整层跳过（旧 Kotlin 早退同语义）
    OverlayCase zeroVp = baseCase(kViews[1]);
    zeroVp.view.vpW = 0;
    zeroVp.view.vpH = 0;
    zeroVp.selectionIndex = 1;
    zeroVp.flags = scene::kOverlayBitBuildingVisible | kAllOverlayBits;
    ASSERT_NO_FATAL_FAILURE(expectEquivalent(zeroVp, "viewport 0"));
    // 建筑数为 0 但数组非空（claim 与容量不一致的钳制面）
    OverlayCase zeroClaim = baseCase(kViews[1]);
    zeroClaim.buildingCount = 0;
    zeroClaim.selectionIndex = 0;
    zeroClaim.markers = {1, 1, 1};
    zeroClaim.flags = scene::kOverlayBitBuildingVisible | kAllOverlayBits;
    ASSERT_NO_FATAL_FAILURE(expectEquivalent(zeroClaim, "buildingCount=0"));
}

// 9. 要素在场证明（防"两臂一致地什么都不画"的退化等价）
TEST_F(SceneOverlayEquivalenceTest, EachLayerActuallyEmitsVertices) {
    const int32_t bitOf[] = {
        scene::kOverlayBitSelection, scene::kOverlayBitDemolish,
        scene::kOverlayBitPreviewSprite, scene::kOverlayBitGridVisible
    };
    const char* nameOf[] = {"selection", "demolish", "preview", "grid"};
    for (int i = 0; i < 4; i++) {
        OverlayCase c = baseCase(kViews[1]);
        c.selectionIndex = 1;
        c.markers = {1, 2, 1};
        c.flags = scene::kOverlayBitBuildingVisible | bitOf[i];
        if (bitOf[i] == scene::kOverlayBitPreviewSprite) {
            c.flags |= scene::kOverlayBitPreviewBox | scene::kOverlayBitPreviewValid;
        }
        const auto calls = runNewModel(c);
        EXPECT_GT(rectsOf(calls), 0u) << nameOf[i] << " 层未产出任何矩形（夹具退化）";
    }
}

// 10. 预览数值加固：非有限值整段跳过（旧路径会把 NaN 送进顶点流）
TEST_F(SceneOverlayEquivalenceTest, NonFinitePreviewIsSkippedNotDrawn) {
    OverlayCase c = baseCase(kViews[1]);
    c.flags = scene::kOverlayBitBuildingVisible | scene::kOverlayBitGridVisible |
        scene::kOverlayBitPreviewSprite | scene::kOverlayBitPreviewBox |
        scene::kOverlayBitPreviewValid;
    c.preview.boxX = std::nanf("");
    const auto dirtyCalls = runNewModel(c);
    ASSERT_EQ(1u, dirtyCalls.size()) << "非有限预览应整段跳过，只剩网格一段";
    EXPECT_EQ(0u, dirtyCalls[0].texId);

    OverlayCase ok = baseCase(kViews[1]);
    ok.flags = c.flags;
    const auto okCalls = runNewModel(ok);
    // 段序：图集段（预览精灵）→ 颜色段（占地框 + 网格线）
    ASSERT_EQ(2u, okCalls.size());
    EXPECT_GT(rectsOf(okCalls), rectsOf(dirtyCalls)) << "健康路径应多出精灵 + 占地框 6 rect";
}

// 11. G4 量化门（本批核心目标）：放置模式每帧叠加层 draw call 数前后对照
TEST_F(SceneOverlayEquivalenceTest, PlacementModeDrawCallBudget) {
    // 最坏档 = 真实 128×128 地图整岛可见（旧路径网格线根数 = (128+1)×2 = 258，
    // 即方案 §1「网格线 258 次 drawRect/帧」病灶），四要素全开
    OverlayCase c = baseCase(CameraView{0.0f, 0.0f, 0.17f, kVpW, kVpH});
    c.cols = kRealCols;
    c.rows = kRealRows;
    c.selectionIndex = 1;
    c.markers = {1, 2, 1};
    c.flags = scene::kOverlayBitBuildingVisible | kAllOverlayBits;

    const auto oldCalls = runOldModel(c);
    const auto newWorst = runNewModel(c);
    std::printf(
        "[G4 overlay] 放置模式最坏帧（128×128 整岛）：叠加层 draw call 旧 %zu（每 rect 一次"
        " 跨线/vkCmdDraw）→ 新 %zu；矩形数两臂同为 %zu\n",
        oldCalls.size(), newWorst.size(), rectsOf(oldCalls));

    EXPECT_EQ(258u + 5u + 7u + 6u, rectsOf(oldCalls)) << "旧路径叠加层 rect 数（网格 258 为主体）";
    EXPECT_GT(oldCalls.size(), 250u) << "旧路径应处于 ~258 量级（方案 §1 基线）";
    // 新路径叠加层本体 ≤3 段（颜色/图集/颜色）——整帧 <15 的预算留天空 + 崖壁 + 地图层
    EXPECT_LE(newWorst.size(), 3u);
    EXPECT_EQ(rectsOf(oldCalls), rectsOf(newWorst)) << "两臂矩形数必须相同（等价前提）";
}

// 12. 前置缺陷 A 修复（独立于 R3.3 等价重构的行为变更批）：网格线行范围按
// **投影可见带**（视口高 ÷ (scale × TOPDOWN_Y_SCALE)）计算——
//   ① C++ 生成几何 == Canvas/投影口径（旧 Vulkan 漏乘压缩系数的行为已改）；
//   ② 修复后两臂（C++ 新路径 / Kotlin 回滚臂）仍逐位等价（同一口径同时改）；
//   ③ 逐缩放档打印「修复前 → 修复后」横线根数差（未达世界边界的档实测缺 6/13/26
//      根横线；达到世界边界的档被钳住故无差异——差异面真实存在且已闭合）。
TEST_F(SceneOverlayEquivalenceTest, GridRowRangeFollowsProjectedViewportBand) {
    const float scales[] = {2.0f, 1.0f, 0.5f, 0.3f, 0.17f};
    int presetsThatWereMissingRows = 0;
    for (const float scale : scales) {
        OverlayCase c = baseCase(CameraView{0.0f, 0.0f, scale, kVpW, kVpH});
        c.cols = kRealCols;
        c.rows = kRealRows;
        c.flags = scene::kOverlayBitGridVisible;

        // Canvas / 投影口径（修复后的正确形态）
        RecorderRenderer projectedRec;
        drawOverlayGridModel(projectedRec, c, true);
        const auto projectedCalls = std::move(projectedRec.calls);

        const auto newCalls = runNewModel(c);
        const auto legacyArmCalls = runOldModel(c);  // 修复后的 Kotlin 回滚臂建模
        const size_t before = gridRectsWithUncompressedRowRange(c);
        if (rectsOf(projectedCalls) > before) presetsThatWereMissingRows++;

        ASSERT_EQ(rectsOf(projectedCalls), rectsOf(newCalls))
            << "scale=" << scale << "：C++ 行范围未按投影可见带";
        ASSERT_EQ(rectsOf(projectedCalls), rectsOf(legacyArmCalls))
            << "scale=" << scale << "：回滚臂与新路径口径不一致（灰度等价红线）";
        // 逐顶点核对（不只比根数）
        ASSERT_NO_FATAL_FAILURE(expectEquivalent(c, "grid projected band"));
        std::printf(
            "[缺陷 A 修复] 128×128 地图 scale=%.2f：修复前 %zu rect → 修复后 %zu rect"
            "（补回 %zu 条底部横线%s）\n",
            scale, before, rectsOf(newCalls), rectsOf(newCalls) - before,
            rectsOf(newCalls) > before ? "" : "，该档被世界边界钳住");
    }
    EXPECT_GT(presetsThatWereMissingRows, 0)
        << "缺陷 A 的差异面必须在部分档位真实存在（否则本修复无的放矢）";
}

// 13. G4 整帧口径：放置模式最坏帧的**全帧** draw call 组成实测
//（叠加层 3 + 地图层 1 + 崖壁逐纹理连续段最坏 8 = 12；另有天空背景 1 条
//  vkCmdDraw（VulkanBackend::drawBackground → submitFrame 单发）= 13 < 15 目标）。
// 崖壁按 8 张纹理交替排列构造最坏切段数（真实布局同纹理连续，段数更少）。
TEST_F(SceneOverlayEquivalenceTest, PlacementModeFullFrameDrawCallBudget) {
    RecorderRenderer rec;
    const CameraView view{0.0f, 0.0f, 0.17f, kVpW, kVpH};
    float proj[16];
    cameraProjMatrix(proj, view.camX, view.camY, view.scale,
        static_cast<float>(view.vpW), static_cast<float>(view.vpH), scene::kTopdownYScale);

    // 地图层（真实 128×128 整岛 + 若干装饰/建筑；整层图集单批 = 1 次 draw）
    SceneStore store;
    const int cellCount = kRealCols * kRealRows;
    std::vector<int32_t> tiles(static_cast<size_t>(cellCount), 0);
    for (int i = 0; i < 400; i++) {
        tiles[static_cast<size_t>(i * 7) % cellCount] = 8;  // 树（立体层装饰）
    }
    store.setTerrain(tiles.data(), cellCount, kRealCols, kRealRows, kTileSize);
    std::vector<float> b = fixtureBuildings();
    store.updateBuildings(b.data(), 3);

    OverlayParams op;
    op.camX = view.camX;
    op.camY = view.camY;
    op.scale = view.scale;
    op.viewportW = view.vpW;
    op.viewportH = view.vpH;
    op.cols = kRealCols;
    op.rows = kRealRows;
    op.tileSize = kTileSize;
    op.buildings = store.buildingsData();
    op.buildingCount = store.buildingCount();
    op.selectionIndex = 1;
    op.atlasTexId = kAtlasTexId;
    op.preview = fixturePreview();
    op.gridVisible = true;
    op.previewSpriteVisible = true;
    op.previewBoxVisible = true;
    op.previewValid = true;
    op.selectionEnabled = true;
    op.demolishEnabled = false;

    MapLayerParams mp;
    mp.viewLeft = view.camX;
    mp.viewTop = view.camY;
    mp.viewRight = view.camX + static_cast<float>(view.vpW) / view.scale;
    mp.viewBottom = view.camY + static_cast<float>(view.vpH) / (view.scale * scene::kTopdownYScale);
    mp.scale = view.scale;
    mp.fadeAlpha = 1.0f;
    mp.frameAlpha = 1.0f;
    mp.buildingVisible = true;
    mp.tiles = store.terrainData();
    mp.tileCount = store.terrainCount();
    mp.cols = store.cols();
    mp.rows = store.rows();
    mp.tileSize = store.tileSize();
    mp.atlasTexId = kAtlasTexId;
    mp.tileUv = scene::kTileUv;
    mp.tileUvCount = scene::kTileUvCount;
    mp.buildings = store.buildingsData();
    mp.buildingClaim = store.buildingCount();
    mp.buildingDataFloats = store.buildingCount() * scene::kBuildingStride;
    mp.buildingUv = scene::kBuildingUv;
    mp.buildingUvCount = scene::kBuildingUvCount;

    SpriteBatcher mapBatcher;
    CropSmoothingState cropState;
    buildMapBatch(mapBatcher, mp, proj, cropState);
    if (mapBatcher.vertexCount > 0) {
        rec.draw(mapBatcher.vertices, mapBatcher.vertexCount, kAtlasTexId);
    }

    // 弯曲地皮轮廓两层（地图边缘 v2）：真实合成器产出（生产同参 128²×48），
    // 岩石带与地皮 mesh 各自单纹理一次提交
    GroundBoundaryConfig bcfg;
    bcfg.cols = 128;
    bcfg.rows = 128;
    bcfg.tileSize = 48;
    bcfg.bottomDepth = 768.0f;
    std::vector<float> boundary;
    computeGroundBoundary(bcfg, boundary);
    GroundBoundaryView bv;
    ASSERT_TRUE(groundBoundaryParse(boundary.data(),
                                    static_cast<int>(boundary.size()), &bv));
    buildBottomRockLayer(bv, 18, 1.0f,
        [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
            rec.draw(verts, count, texId);
        });
    buildGroundMeshLayer(bv, 18, 1.0f,
        [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
            rec.draw(verts, count, texId);
        });

    // 叠加层（选中高亮 + 预览精灵 + 占地框 + 网格线）
    SpriteBatcher overlayBatcher;
    buildOverlayLayers(overlayBatcher, proj, op,
        [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
            rec.draw(verts, count, texId);
        });

    const size_t worldCalls = rec.calls.size();
    std::printf("[G4 整帧] 放置模式最坏帧世界内容 draw call = %zu（地图 1 + 底部岩石 1 + 地皮 1 + 叠加层 3）"
                "，另加天空 1 = %zu，目标 < 15\n",
        worldCalls, worldCalls + 1);
    EXPECT_EQ(6u, worldCalls);
    EXPECT_LT(worldCalls + 1, 15u) << "G4 vkCmdDraw 目标";
}

}  // namespace
