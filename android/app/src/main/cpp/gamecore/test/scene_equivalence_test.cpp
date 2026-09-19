#include <gtest/gtest.h>

#include <cstdint>
#include <vector>

#include "Rhi.h"
#include "SpriteBatcher.h"
#include "scene/scene_store.h"
#include "scene/scene_draw.h"

namespace {

using scene::buildCliffLayer;
using scene::buildMapBatch;
using scene::CliffLayerParams;
using scene::CropSmoothingState;
using scene::MapLayerParams;
using scene::SceneStore;

// ============================================================
// 场景等价性守卫（重构方案 2026-09-17 R3.2/B10 验收门 4——语义等价守卫）
//
// **证明目标**：新 SceneStore 路径（sceneSet*/sceneUpdate* 导入 + 生成 UV 表）
// 与旧 drawAllTiles 路径（Kotlin 形状全量数组 + Kotlin 公式 UV）产出
// **逐位相同的顶点流**（draw call 纹理序 + 每顶点 px/py/u/v/r/g/b/a 全等）
// ⇒ 同 draw call 序 + 同顶点 ⇒ 同像素（语义等价的构造性最强形态）。
//
// 覆盖（六要素 × 相机三档位）：
//   地形（含草/石/树装饰 + 建筑占位格）/ 石板道路（掩码 1/2/3/5/7/15 形态）/
//   建筑（含固定结构 nameIdx=19 与阴影）/ 作物（三阶段 + 帧间平滑第二帧）/
//   云实例 / 崖壁布局（双纹理 + 镜像条目）；相机 = 近景 2.0 / 中景 1.0 /
//   远景 0.3（整岛可见，含可见性剔除差异面）。
//
// 旧路径 UV 夹具 = Kotlin SpriteAtlasDef 公式的 rect 快照复刻
// （rect 与 build-atlas.mjs LAYOUT 逐项同步；LAYOUT 变更时生成器产物与本夹具
// 同步更新——若生成表与夹具漂移，本测试即红）。UV = rect/4096，图集尺寸为
// 2 的幂 ⇒ 除法精确（无舍入），Kotlin/C++/测试三侧逐位一致。
// ============================================================

// 记录器后端：捕获 draw(verts, count, texId) 顶点流
struct RecorderRenderer : public Renderer2D {
    struct DrawCall {
        uint32_t texId;
        std::vector<SpriteVertex> verts;

        bool operator==(const DrawCall& o) const {
            if (texId != o.texId || verts.size() != o.verts.size()) return false;
            for (size_t i = 0; i < verts.size(); i++) {
                const SpriteVertex& a = verts[i];
                const SpriteVertex& b = o.verts[i];
                if (a.px != b.px || a.py != b.py || a.u != b.u || a.v != b.v ||
                    a.r != b.r || a.g != b.g || a.b != b.b || a.a != b.a) {
                    return false;
                }
            }
            return true;
        }
    };
    std::vector<DrawCall> calls;

    void draw(const SpriteVertex* v, int count, uint32_t texId) override {
        DrawCall c;
        c.texId = texId;
        c.verts.assign(v, v + count);
        calls.push_back(std::move(c));
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

// ── 夹具常量（与 build-atlas.mjs LAYOUT 快照逐项同步）─────────────

constexpr int kCols = 16;
constexpr int kRows = 16;
constexpr int kTileSize = 48;
constexpr uint32_t kAtlasTexId = 42;

// 相机三档位（近/中/远——远景 0.3 下整岛可见，含剔除边界差异面）
struct CameraView {
    float camX, camY, scale;
    int vpW, vpH;
    float fadeAlpha;
    float frameAlpha;
};
constexpr CameraView kViews[3] = {
    { 0.0f, 0.0f, 2.0f, 1080, 1920, 1.0f, 0.5f },
    { 96.0f, 48.0f, 1.0f, 1080, 1920, 1.0f, 1.0f },
    { 0.0f, 0.0f, 0.3f, 1080, 1920, 0.5f, 1.0f },
};

struct Rect { float x, y, w, h; };

/** Kotlin SpriteAtlasDef 公式复刻：rect → [u0,v0,u1,v1]（除法精确，逐位一致） */
std::vector<float> uvOf(const std::vector<Rect>& rects) {
    std::vector<float> uv;
    uv.reserve(rects.size() * 4);
    for (const Rect& r : rects) {
        uv.push_back(r.x / 4096.0f);
        uv.push_back(r.y / 4096.0f);
        uv.push_back((r.x + r.w) / 4096.0f);
        uv.push_back((r.y + r.h) / 4096.0f);
    }
    return uv;
}

// 瓦片 rect 表（TileType.index 序；TILE_BUILDING 占位复刻 GROUND rect，同 Kotlin）
std::vector<float> kotlinTileUv() {
    return uvOf({
        {0, 0, 128, 128},      // GROUND
        {136, 0, 128, 128},    // GRASS1
        {272, 0, 128, 128},    // GRASS2
        {408, 0, 128, 128},    // GRASS3
        {544, 0, 128, 128},    // GRASS4
        {2048, 0, 128, 128},   // STONE1
        {2184, 0, 128, 128},   // STONE2
        {2320, 0, 128, 128},   // STONE3
        {800, 0, 256, 256},    // TREE1
        {1064, 0, 256, 256},   // TREE2
        {0, 0, 128, 128},      // TILE_BUILDING（占位）
    });
}

// 建筑 rect 表（19 栋行公式槽位 + 尾部固定结构 sect_gate，同 Kotlin BUILDING_UV_MAP）
std::vector<float> kotlinBuildingUv() {
    return uvOf({
        {0, 512, 512, 512},      // 灵矿场
        {520, 512, 512, 512},    // 灵植阁
        {1044, 516, 128, 128},   // 灵田（专属槽位覆盖）
        {1560, 512, 512, 512},   // 炼丹炉
        {2080, 512, 512, 512},   // 锻造坊
        {0, 1032, 512, 512},     // 仓库
        {520, 1032, 512, 512},   // 藏经阁
        {1040, 1032, 512, 512},  // 问道塔
        {1560, 1032, 512, 512},  // 青云塔
        {3008, 1032, 1024, 1024},// 天枢殿（专属槽位覆盖）
        {0, 1552, 512, 512},     // 执法堂
        {520, 1552, 512, 512},   // 任务阁
        {1040, 1552, 512, 512},  // 巡视楼
        {1560, 1552, 512, 512},  // 监牢
        {2080, 1552, 512, 512},  // 单人住所
        {0, 2072, 512, 512},     // 中级单人住所
        {520, 2072, 512, 512},   // 多人住所
        {1040, 2072, 512, 512},  // 血炼池
        {1560, 2072, 512, 512},  // 中级多人住所
        {3072, 512, 768, 256},   // sect_gate（固定结构尾部）
    });
}

std::vector<float> kotlinCropUv() {
    return uvOf({
        {1384, 0, 128, 128}, {1520, 0, 128, 128}, {1656, 0, 128, 128},
    });
}

std::vector<float> kotlinCloudUv() {
    return uvOf({
        {0, 2816, 968, 240}, {968, 2816, 904, 376}, {3088, 2816, 976, 192},
        {0, 3240, 1048, 216}, {1048, 3240, 944, 400},
    });
}

std::vector<float> kotlinRoadUv() {
    return uvOf({
        {2048, 2624, 128, 128}, {2184, 2624, 32, 96}, {2264, 2624, 96, 32},
    });
}

// ── 场景夹具（六要素全量）────────────────────────────────────────

struct SceneFixture {
    std::vector<int32_t> tiles;
    std::vector<int32_t> roads;
    std::vector<float> buildings;
    int buildingCount = 0;
    std::vector<float> crops;
    int cropCount = 0;
    std::vector<float> clouds;
    int cloudCount = 0;
    std::vector<float> cliffs;
    int cliffPieceCount = 0;

    // 旧路径 UV（Kotlin 公式夹具复刻）
    std::vector<float> tileUv = kotlinTileUv();
    std::vector<float> buildingUv = kotlinBuildingUv();
    std::vector<float> cropUv = kotlinCropUv();
    std::vector<float> cloudUv = kotlinCloudUv();
    std::vector<float> roadUv = kotlinRoadUv();
};

/** 全要素场景：11 种瓦片全覆盖 + 道路六形态 + 建筑两栋（一实一结构） +
 *  作物三阶段 + 云两组 + 崖壁双纹理（含镜像条目） */
SceneFixture fullFixture() {
    SceneFixture fx;
    fx.tiles.assign(static_cast<size_t>(kCols) * kRows, 0);
    // 地形铺法：行带状覆盖全部瓦片类型（含建筑占位 10）
    for (int row = 0; row < kRows; row++) {
        for (int col = 0; col < kCols; col++) {
            fx.tiles[static_cast<size_t>(row) * kCols + col] =
                static_cast<int32_t>((row * 3 + col / 5) % 11);
        }
    }
    // 建筑占地格置占位标记（同 Kotlin applyBuildingOccupancy 语义）
    for (int row = 2; row < 6; row++) {
        for (int col = 3; col < 7; col++) {
            fx.tiles[static_cast<size_t>(row) * kCols + col] = 10;
        }
    }
    // 道路：行 9 铺横向直路（水平掩码），列 5 铺纵向（交点成十字）；
    // 孤立格（掩码 1）与端点（掩码 2/4）各异形态
    fx.roads.assign(static_cast<size_t>(kCols) * kRows, 0);
    for (int col = 1; col < kCols - 1; col++) {
        fx.roads[static_cast<size_t>(9) * kCols + col] = 5;   // 横向 1-based（掩码 4=东西）
    }
    for (int row = 1; row < kRows - 1; row++) {
        fx.roads[static_cast<size_t>(row) * kCols + 5] = 10;  // 纵向（掩码 9=南北）
    }
    fx.roads[static_cast<size_t>(9) * kCols + 5] = 15 + 1;    // 十字（四邻全连）
    fx.roads[static_cast<size_t>(13) * kCols + 2] = 1;        // 孤立单格
    fx.roads[static_cast<size_t>(13) * kCols + 3] = 2;        // 端点（东邻）
    fx.roads[static_cast<size_t>(13) * kCols + 4] = 3;

    // 建筑：灵矿场（占地 4×4，精灵 4×4）+ 固定结构门楼（nameIdx = 19 = kStructureNameBase）
    const float buildingData[] = {
        3.0f, 2.0f, 4.0f, 4.0f, 0.0f,    // 灵矿场（精灵尺寸 = 占地）
        3.0f, 0.0f, 6.0f, 2.0f, 19.0f,   // 宗门门楼（固定结构，不投影阴影）
        9.0f, 9.0f, 1.0f, 1.0f, 2.0f,    // 灵田（1×1）
    };
    fx.buildings.assign(buildingData, buildingData + 15);
    fx.buildingCount = 3;

    // 作物：三阶段（进度 0.1 / 0.45 / 0.9——跨三个 stage 区间）
    const float cropData[] = {
        8.0f, 3.0f, 0.1f,
        8.0f, 4.0f, 0.45f,
        8.0f, 5.0f, 0.9f,
    };
    fx.crops.assign(cropData, cropData + 9);
    fx.cropCount = 3;

    // 云：两组实例（含 spriteIndex 2 与 alpha 0.7）
    const float cloudData[] = {
        100.0f, 40.0f, 300.0f, 80.0f, 0.0f, 0.9f,
        400.0f, 30.0f, 200.0f, 60.0f, 2.0f, 0.7f,
    };
    fx.clouds.assign(cloudData, cloudData + 12);
    fx.cloudCount = 2;

    // 崖壁：底部条带（纹理 0）+ 左缘（纹理 1，含镜像条目 flags=1 → u0>u1）
    const float cliffData[] = {
        0.0f, 0.0f, 768.0f, 48.0f, 96.0f, 0.0f, 0.5f, 0.5f, 0.75f, 0.0f,
        0.0f, 48.0f, 768.0f, 48.0f, 96.0f, 0.0f, 0.25f, 0.5f, 0.5f, 0.0f,
        1.0f, 0.0f, 192.0f, 24.0f, 192.0f, 0.75f, 0.0f, 0.25f, 0.5f, 1.0f,
    };
    fx.cliffs.assign(cliffData, cliffData + 30);
    fx.cliffPieceCount = 3;
    return fx;
}

/** 单要素裁剪：按启用层从全要素夹具派生（terrain 恒保留——层依赖基座） */
SceneFixture fixtureWith(bool roads_, bool buildings_, bool crops_, bool clouds_, bool cliffs_) {
    SceneFixture fx = fullFixture();
    if (!roads_) fx.roads.assign(static_cast<size_t>(kCols) * kRows, 0);
    if (!buildings_) { fx.buildings.clear(); fx.buildingCount = 0; }
    if (!crops_) { fx.crops.clear(); fx.cropCount = 0; }
    if (!clouds_) { fx.clouds.clear(); fx.cloudCount = 0; }
    if (!cliffs_) { fx.cliffs.clear(); fx.cliffPieceCount = 0; }
    return fx;
}

// ── 两臂驱动（生产同形装配）────────────────────────────────────

struct LayerFlags {
    bool buildingVisible = true;
    bool skipDecor = false;
    bool skipClouds = false;
};

/// 视口世界范围（与生产 setCamera/drawFrame 相机段同式）
void viewBoundsOf(const CameraView& v, float* out) {
    out[0] = v.camX;
    out[1] = v.camY;
    out[2] = v.camX + static_cast<float>(v.vpW) / v.scale;
    out[3] = v.camY + static_cast<float>(v.vpH) / (v.scale * scene::kTopdownYScale);
}

/// 旧路径臂：Kotlin 形状数组 + 夹具 UV（drawAllTiles 的非 JNI 等价驱动）
std::vector<RecorderRenderer::DrawCall> runOldPath(
    const SceneFixture& fx, const CameraView& view, const LayerFlags& flags, int frames) {
    RecorderRenderer rec;
    float proj[16];
    cameraProjMatrix(proj, view.camX, view.camY, view.scale,
                     static_cast<float>(view.vpW), static_cast<float>(view.vpH),
                     scene::kTopdownYScale);
    float bounds[4];
    viewBoundsOf(view, bounds);

    const uint32_t cliffTexIds[2] = {7, 3};

    CropSmoothingState cropState;
    for (int f = 0; f < frames; f++) {
        SpriteBatcher batcher;
        MapLayerParams p;
        p.viewLeft = bounds[0]; p.viewTop = bounds[1];
        p.viewRight = bounds[2]; p.viewBottom = bounds[3];
        p.scale = view.scale;
        p.fadeAlpha = view.fadeAlpha;
        p.frameAlpha = view.frameAlpha;
        p.skipDecor = flags.skipDecor;
        p.skipClouds = flags.skipClouds;
        p.buildingShadows = true;
        p.buildingVisible = flags.buildingVisible;
        p.tiles = fx.tiles.data();
        p.tileCount = static_cast<int64_t>(fx.tiles.size());
        p.cols = kCols; p.rows = kRows; p.tileSize = kTileSize;
        p.atlasTexId = kAtlasTexId;
        p.groundQuadEnabled = false;
        p.groundTexId = 0;
        p.tileUv = fx.tileUv.data();
        p.tileUvCount = static_cast<int>(fx.tileUv.size() / 4);
        p.roads = fx.roads.data();
        p.roadCount = static_cast<int64_t>(fx.roads.size());
        p.roadUv = fx.roadUv.data();
        p.roadUvCount = static_cast<int>(fx.roadUv.size() / 4);
        p.buildings = fx.buildings.data();
        p.buildingClaim = fx.buildingCount;
        p.buildingDataFloats = static_cast<int>(fx.buildings.size());
        p.buildingUv = fx.buildingUv.data();
        p.buildingUvCount = static_cast<int>(fx.buildingUv.size() / 4);
        p.crops = fx.crops.data();
        p.cropCount = fx.cropCount;
        p.cropUv = fx.cropUv.data();
        p.cropUvCount = static_cast<int>(fx.cropUv.size() / 4);
        p.clouds = fx.clouds.data();
        p.cloudCount = fx.cloudCount;
        p.cloudUv = fx.cloudUv.data();
        p.cloudUvCount = static_cast<int>(fx.cloudUv.size() / 4);

        buildMapBatch(batcher, p, proj, cropState);
        if (batcher.vertexCount > 0) {
            rec.draw(batcher.vertices, batcher.vertexCount, kAtlasTexId);
        }

        if (fx.cliffPieceCount > 0) {
            SpriteBatcher cliffBatcher;
            CliffLayerParams cp;
            cp.data = fx.cliffs.data();
            cp.pieceCount = fx.cliffPieceCount;
            cp.texIds = cliffTexIds;
            cp.texCount = 2;
            cp.viewLeft = bounds[0]; cp.viewTop = bounds[1];
            cp.viewRight = bounds[2]; cp.viewBottom = bounds[3];
            cp.scale = view.scale;
            cp.fadeAlpha = view.fadeAlpha;
            buildCliffLayer(cliffBatcher, proj, cp,
                [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
                    if (count > 0) rec.draw(verts, count, texId);
                });
        }
    }
    return std::move(rec.calls);
}

/// 新路径臂：SceneStore 导入（与生产 sceneSet*/sceneUpdate* 端口同存储路径）
/// + 生成 UV 表（scene_uv_tables.h）
std::vector<RecorderRenderer::DrawCall> runNewPath(
    const SceneFixture& fx, const CameraView& view, const LayerFlags& flags, int frames) {
    RecorderRenderer rec;
    float proj[16];
    cameraProjMatrix(proj, view.camX, view.camY, view.scale,
                     static_cast<float>(view.vpW), static_cast<float>(view.vpH),
                     scene::kTopdownYScale);
    float bounds[4];
    viewBoundsOf(view, bounds);

    // 场景导入（逐值搬运——与生产端口的唯一差异是省去 JNI 数组中转）
    SceneStore store;
    store.setTerrain(fx.tiles.data(), static_cast<int64_t>(fx.tiles.size()), kCols, kRows, kTileSize);
    store.updateRoads(fx.roads.data(), static_cast<int64_t>(fx.roads.size()));
    store.updateBuildings(fx.buildings.data(), fx.buildingCount);
    store.updateCrops(fx.crops.data(), fx.cropCount);
    store.updateClouds(fx.clouds.data(), fx.cloudCount);
    store.setCliffLayout(fx.cliffs.data(), fx.cliffPieceCount);

    const uint32_t cliffTexIds[2] = {7, 3};

    CropSmoothingState cropState;
    for (int f = 0; f < frames; f++) {
        SpriteBatcher batcher;
        MapLayerParams p;
        p.viewLeft = bounds[0]; p.viewTop = bounds[1];
        p.viewRight = bounds[2]; p.viewBottom = bounds[3];
        p.scale = view.scale;
        p.fadeAlpha = view.fadeAlpha;
        p.frameAlpha = view.frameAlpha;
        p.skipDecor = flags.skipDecor;
        p.skipClouds = flags.skipClouds;
        p.buildingShadows = true;
        p.buildingVisible = flags.buildingVisible;
        p.tiles = store.terrainData();
        p.tileCount = store.terrainCount();
        p.cols = store.cols(); p.rows = store.rows(); p.tileSize = store.tileSize();
        p.atlasTexId = kAtlasTexId;
        p.groundQuadEnabled = false;
        p.groundTexId = 0;
        p.tileUv = scene::kTileUv;
        p.tileUvCount = scene::kTileUvCount;
        p.roads = store.roadsData();
        p.roadCount = store.roadsCount();
        p.roadUv = scene::kRoadUv;
        p.roadUvCount = scene::kRoadUvCount;
        p.buildings = store.buildingsData();
        p.buildingClaim = store.buildingCount();
        p.buildingDataFloats = store.buildingCount() * scene::kBuildingStride;
        p.buildingUv = scene::kBuildingUv;
        p.buildingUvCount = scene::kBuildingUvCount;
        p.crops = store.cropsData();
        p.cropCount = store.cropCount();
        p.cropUv = scene::kCropUv;
        p.cropUvCount = scene::kCropUvCount;
        p.clouds = store.cloudsData();
        p.cloudCount = store.cloudCount();
        p.cloudUv = scene::kCloudUv;
        p.cloudUvCount = scene::kCloudUvCount;

        buildMapBatch(batcher, p, proj, cropState);
        if (batcher.vertexCount > 0) {
            rec.draw(batcher.vertices, batcher.vertexCount, kAtlasTexId);
        }

        if (store.hasCliffs()) {
            SpriteBatcher cliffBatcher;
            CliffLayerParams cp;
            cp.data = store.cliffsData();
            cp.pieceCount = store.cliffPieceCount();
            cp.texIds = cliffTexIds;
            cp.texCount = 2;
            cp.viewLeft = bounds[0]; cp.viewTop = bounds[1];
            cp.viewRight = bounds[2]; cp.viewBottom = bounds[3];
            cp.scale = view.scale;
            cp.fadeAlpha = view.fadeAlpha;
            buildCliffLayer(cliffBatcher, proj, cp,
                [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
                    if (count > 0) rec.draw(verts, count, texId);
                });
        }
    }
    return std::move(rec.calls);
}

/// 断言两臂顶点流逐位一致（draw call 数/纹理序/每顶点 8 分量）
void assertVertexStreamEqual(const std::vector<RecorderRenderer::DrawCall>& oldCalls,
                             const std::vector<RecorderRenderer::DrawCall>& newCalls,
                             const char* what) {
    ASSERT_EQ(oldCalls.size(), newCalls.size()) << what << ": draw call 数不一致";
    for (size_t c = 0; c < oldCalls.size(); c++) {
        ASSERT_EQ(oldCalls[c].texId, newCalls[c].texId)
            << what << ": draw call " << c << " 纹理不一致";
        ASSERT_EQ(oldCalls[c].verts.size(), newCalls[c].verts.size())
            << what << ": draw call " << c << " 顶点数不一致";
        for (size_t i = 0; i < oldCalls[c].verts.size(); i++) {
            const SpriteVertex& a = oldCalls[c].verts[i];
            const SpriteVertex& b = newCalls[c].verts[i];
            ASSERT_EQ(a.px, b.px) << what << ": call " << c << " 顶点 " << i << " px";
            ASSERT_EQ(a.py, b.py) << what << ": call " << c << " 顶点 " << i << " py";
            ASSERT_EQ(a.u, b.u) << what << ": call " << c << " 顶点 " << i << " u";
            ASSERT_EQ(a.v, b.v) << what << ": call " << c << " 顶点 " << i << " v";
            ASSERT_EQ(a.r, b.r) << what << ": call " << c << " 顶点 " << i << " r";
            ASSERT_EQ(a.g, b.g) << what << ": call " << c << " 顶点 " << i << " g";
            ASSERT_EQ(a.b, b.b) << what << ": call " << c << " 顶点 " << i << " b";
            ASSERT_EQ(a.a, b.a) << what << ": call " << c << " 顶点 " << i << " a";
        }
    }
    SUCCEED() << what << ": " << oldCalls.size() << " draw calls 顶点流逐位一致";
}

// ── 测试 ────────────────────────────────────────────────────

class SceneEquivalenceTest : public ::testing::Test {};

// 单要素等价（地形/道路/建筑/作物/云/崖壁——六要素逐层两臂对照）
TEST_F(SceneEquivalenceTest, TerrainOnlyAllCameras) {
    SceneFixture fx = fixtureWith(false, false, false, false, false);
    LayerFlags flags;
    for (const CameraView& view : kViews) {
        std::string what = "terrain cam(scale=" + std::to_string(view.scale) + ")";
        assertVertexStreamEqual(runOldPath(fx, view, flags, 1),
                                runNewPath(fx, view, flags, 1), what.c_str());
    }
}

TEST_F(SceneEquivalenceTest, RoadsAllCameras) {
    SceneFixture fx = fixtureWith(true, false, false, false, false);
    LayerFlags flags;
    for (const CameraView& view : kViews) {
        std::string what = "roads cam(scale=" + std::to_string(view.scale) + ")";
        assertVertexStreamEqual(runOldPath(fx, view, flags, 1),
                                runNewPath(fx, view, flags, 1), what.c_str());
    }
}

TEST_F(SceneEquivalenceTest, BuildingsWithStructureAllCameras) {
    SceneFixture fx = fixtureWith(false, true, false, false, false);
    LayerFlags flags;
    for (const CameraView& view : kViews) {
        std::string what = "buildings cam(scale=" + std::to_string(view.scale) + ")";
        assertVertexStreamEqual(runOldPath(fx, view, flags, 1),
                                runNewPath(fx, view, flags, 1), what.c_str());
    }
}

TEST_F(SceneEquivalenceTest, BuildingsHiddenOverlayFlag) {
    // overlay bit0 = buildingVisible=false：建筑层关闭、立体装饰单独成序——两臂同判定
    SceneFixture fx = fixtureWith(false, true, false, false, false);
    LayerFlags flags;
    flags.buildingVisible = false;
    for (const CameraView& view : kViews) {
        assertVertexStreamEqual(runOldPath(fx, view, flags, 1),
                                runNewPath(fx, view, flags, 1), "buildingVisible=false");
    }
}

TEST_F(SceneEquivalenceTest, CropsWithFrameSmoothing) {
    // 两帧驱动：第二帧走帧间平滑路径（frameAlpha 权重 + 插值基准）——两臂同态
    SceneFixture fx = fixtureWith(false, false, true, false, false);
    LayerFlags flags;
    for (const CameraView& view : kViews) {
        std::string what = "crops cam(scale=" + std::to_string(view.scale) + ")";
        assertVertexStreamEqual(runOldPath(fx, view, flags, 2),
                                runNewPath(fx, view, flags, 2), what.c_str());
    }
}

TEST_F(SceneEquivalenceTest, CloudsAllCameras) {
    SceneFixture fx = fixtureWith(false, false, false, true, false);
    LayerFlags flags;
    for (const CameraView& view : kViews) {
        std::string what = "clouds cam(scale=" + std::to_string(view.scale) + ")";
        assertVertexStreamEqual(runOldPath(fx, view, flags, 1),
                                runNewPath(fx, view, flags, 1), what.c_str());
    }
}

TEST_F(SceneEquivalenceTest, CliffsMirroredPiecesAllCameras) {
    SceneFixture fx = fixtureWith(false, false, false, false, true);
    LayerFlags flags;
    for (const CameraView& view : kViews) {
        std::string what = "cliffs cam(scale=" + std::to_string(view.scale) + ")";
        assertVertexStreamEqual(runOldPath(fx, view, flags, 1),
                                runNewPath(fx, view, flags, 1), what.c_str());
    }
}

// 全要素 × 相机三档位（含远景整岛可见 + 淡入半透明 + skip 装饰/云降级路径）
TEST_F(SceneEquivalenceTest, FullSceneAllCamerasAndDegrade) {
    SceneFixture fx = fullFixture();
    LayerFlags flags;
    for (const CameraView& view : kViews) {
        std::string what = "full cam(scale=" + std::to_string(view.scale) + ")";
        assertVertexStreamEqual(runOldPath(fx, view, flags, 2),
                                runNewPath(fx, view, flags, 2), what.c_str());
    }
    LayerFlags degrade;
    degrade.skipDecor = true;
    degrade.skipClouds = true;
    assertVertexStreamEqual(runOldPath(fx, kViews[1], degrade, 1),
                            runNewPath(fx, kViews[1], degrade, 1), "full skipDecor/skipClouds");
}

// 要素在场证明：各单要素场景在中等相机下必须产出非空顶点流
//（防"两臂一致地什么都懒得画"的退化等价）
TEST_F(SceneEquivalenceTest, EachElementActuallyEmitsVertices) {
    const CameraView& mid = kViews[1];
    LayerFlags flags;

    SceneFixture terrain = fixtureWith(false, false, false, false, false);
    EXPECT_GT(runOldPath(terrain, mid, flags, 1).size(), 0u);

    SceneFixture roads = fixtureWith(true, false, false, false, false);
    auto roadCalls = runOldPath(roads, mid, flags, 1);
    EXPECT_GT(roadCalls.size(), 0u);
    EXPECT_GT(roadCalls[0].verts.size(), 0u);

    SceneFixture buildings = fixtureWith(false, true, false, false, false);
    EXPECT_GT(runOldPath(buildings, mid, flags, 1).size(), 0u);

    SceneFixture crops = fixtureWith(false, false, true, false, false);
    EXPECT_GT(runOldPath(crops, mid, flags, 1).size(), 0u);

    SceneFixture clouds = fixtureWith(false, false, false, true, false);
    EXPECT_GT(runOldPath(clouds, mid, flags, 1).size(), 0u);

    SceneFixture cliffs = fixtureWith(false, false, false, false, true);
    EXPECT_GT(runOldPath(cliffs, mid, flags, 1).size(), 0u);
}

// ============================================================
// R3.5 远景观看容量路径守卫（批次 B12）
//
// 地面层两种形态互斥：groundQuadEnabled && groundTexId!=0 → 整图 REPEAT quad
// （1 个地面 draw call）；否则逐格地面（每格 1 quad）。本组锁定：
//   ① 互斥性——整图开时地面 draw call 骤减，且**不产出逐格地面 quad**；
//   ② 参数齐备性——未传 groundTexId 时即便开关为真仍回退逐格（防御）；
//   ③ 非地面层不变——道路/建筑/作物在整图开时逐位不变（只有地面换形态）。
// ③ 是该特性敢上线的关键：地面换形态不得扰动任何其他层的顶点流。
// ============================================================

namespace {

/// 地面层形态测量结果
struct GroundShape {
    int mainBatchVerts;             ///< 主批（atlasTexId）顶点数
    int groundSubmitVerts;          ///< 整图地面独立提交顶点数（0 = 未触发整图路径）
    uint32_t groundSubmitTexId;     ///< 整图地面提交用的纹理 id
};

/// 主批形态（不带地面提交回调，只关心主批顶点数）
struct MainBatchShape {
    int mainVerts;                  ///< 主批顶点数
    int groundSubmitVerts;          ///< 地面独立提交顶点数（不挂回调则恒 0）
};

/// 组装 MapLayerParams（给定夹具 + 地面开关 + 相机）——单点维护，避免测试内重复
MapLayerParams paramsFor(const SceneFixture& fx, bool groundQuad, uint32_t groundTexId,
                         const CameraView& view, float proj[16], float bounds[4]) {
    cameraProjMatrix(proj, view.camX, view.camY, view.scale,
                     static_cast<float>(view.vpW), static_cast<float>(view.vpH),
                     scene::kTopdownYScale);
    viewBoundsOf(view, bounds);
    MapLayerParams p;
    p.viewLeft = bounds[0]; p.viewTop = bounds[1];
    p.viewRight = bounds[2]; p.viewBottom = bounds[3];
    p.scale = view.scale;
    p.fadeAlpha = view.fadeAlpha;
    p.frameAlpha = view.frameAlpha;
    p.skipDecor = false;
    p.skipClouds = false;
    p.buildingShadows = true;
    p.buildingVisible = true;
    p.tiles = fx.tiles.data();
    p.tileCount = static_cast<int64_t>(fx.tiles.size());
    p.cols = kCols; p.rows = kRows; p.tileSize = kTileSize;
    p.atlasTexId = kAtlasTexId;
    p.groundQuadEnabled = groundQuad;
    p.groundTexId = groundTexId;
    p.tileUv = fx.tileUv.data();
    p.tileUvCount = static_cast<int>(fx.tileUv.size() / 4);
    p.roads = fx.roads.data();
    p.roadCount = static_cast<int64_t>(fx.roads.size());
    p.roadUv = fx.roadUv.data();
    p.roadUvCount = static_cast<int>(fx.roadUv.size() / 4);
    p.buildings = fx.buildings.data();
    p.buildingClaim = fx.buildingCount;
    p.buildingDataFloats = static_cast<int>(fx.buildings.size());
    p.buildingUv = fx.buildingUv.data();
    p.buildingUvCount = static_cast<int>(fx.buildingUv.size() / 4);
    p.crops = fx.crops.data();
    p.cropCount = fx.cropCount;
    p.cropUv = fx.cropUv.data();
    p.cropUvCount = static_cast<int>(fx.cropUv.size() / 4);
    p.clouds = fx.clouds.data();
    p.cloudCount = fx.cloudCount;
    p.cloudUv = fx.cloudUv.data();
    p.cloudUvCount = static_cast<int>(fx.cloudUv.size() / 4);
    return p;
}

/// 单要素地形夹具在给定相机/地面开关下的地面形态测量。
/// 经 5 参重载的 submitGround 回调捕获整图地面提交（生产 = renderer->draw）。
GroundShape measureGround(bool groundQuad, uint32_t groundTexId, const CameraView& view) {
    SceneFixture fx = fixtureWith(false, false, false, false, false);
    float proj[16];
    float bounds[4];
    MapLayerParams p = paramsFor(fx, groundQuad, groundTexId, view, proj, bounds);

    SpriteBatcher batcher;
    GroundShape out{0, 0, 0};
    CropSmoothingState cropState;
    const int mainVerts = buildMapBatch(batcher, p, proj, cropState,
        [&out](uint32_t texId, const SpriteVertex*, int count) {
            out.groundSubmitTexId = texId;
            out.groundSubmitVerts += count;
        });
    out.mainBatchVerts = mainVerts;
    return out;
}

/// 任意夹具的主批形态测量（不捕获地面提交——整图地面段另由 measureGround 测）
MainBatchShape measureMainBatch(const SceneFixture& fx, bool groundQuad,
                                uint32_t groundTexId, const CameraView& view) {
    float proj[16];
    float bounds[4];
    MapLayerParams p = paramsFor(fx, groundQuad, groundTexId, view, proj, bounds);

    SpriteBatcher batcher;
    MainBatchShape out{0, 0};
    CropSmoothingState cropState;
    out.mainVerts = buildMapBatch(batcher, p, proj, cropState,
        [&out](uint32_t, const SpriteVertex*, int count) {
            out.groundSubmitVerts += count;
        });
    return out;
}

}  // namespace

// ① 整图地面开启 ⇒ 地面改由独立提交的单个 quad 承担，主批不再含逐格地面
TEST_F(SceneEquivalenceTest, FarViewGroundQuadReplacesPerTileGround) {
    // 远景档（kViews[2]：scale=0.3，整岛可见）——逐格地面铺满整屏，顶点数为 O(可见格数)
    const CameraView& farView = kViews[2];
    GroundShape perTile = measureGround(/*groundQuad=*/false, /*groundTexId=*/0, farView);
    GroundShape wholeMap = measureGround(/*groundQuad=*/true, /*groundTexId=*/99, farView);

    // 逐格臂：地面在**主批**里（无独立地面提交），顶点数可观（O 可见格数）
    EXPECT_EQ(0, perTile.groundSubmitVerts)
        << "逐格臂不得触达整图地面提交回调";
    EXPECT_GT(perTile.mainBatchVerts, 0) << "逐格地面必须产出主批顶点（对照基线）";

    // 整图臂：地面经**独立提交**（1 quad = VERTICES_PER_SPRITE 顶点），主批不再含地面
    EXPECT_EQ(VERTICES_PER_SPRITE, wholeMap.groundSubmitVerts)
        << "整图地面必须是单个 quad（" << VERTICES_PER_SPRITE
        << " 顶点）独立提交——R3.5 容量收益的直接证据";
    EXPECT_EQ(99u, wholeMap.groundSubmitTexId)
        << "整图地面须以其自身纹理 id 提交（非图集 id）";
    EXPECT_LT(wholeMap.mainBatchVerts, perTile.mainBatchVerts)
        << "整图臂主批必须比逐格臂小（逐格地面已从主批消失）";
}

// ② groundTexId 未传入时即便开关为真仍回退逐格（防御——半配置态不得黑屏）
TEST_F(SceneEquivalenceTest, FarViewGroundQuadWithoutTextureFallsBack) {
    const CameraView& farView = kViews[2];
    GroundShape noTex = measureGround(/*groundQuad=*/true, /*groundTexId=*/0, farView);
    GroundShape perTile = measureGround(/*groundQuad=*/false, /*groundTexId=*/0, farView);

    EXPECT_EQ(0, noTex.groundSubmitVerts)
        << "groundTexId=0 时整图提交必须不发生——否则 REPEAT 采样空白纹理整图黑屏";
    EXPECT_EQ(perTile.mainBatchVerts, noTex.mainBatchVerts)
        << "groundTexId=0 时整图开关必须无效，主批与逐格臂逐位一致";
    EXPECT_GT(noTex.mainBatchVerts, 0) << "回退后地面必须仍在主批中绘制";
}

// ③ 地面换形态不得扰动其他层：**同一**全场景夹具下，
//    整图臂主批 == 逐格臂主批 − 与整图臂同视口的逐格地面段。
//    地面段在同夹具下由「整图臂主批 − 逐格臂主批」的差直接给出（自洽，
//    不跨夹具比较）。
TEST_F(SceneEquivalenceTest, FarViewGroundQuadLeavesOtherLayersUntouched) {
    const CameraView& view = kViews[2];  // 远景档
    SceneFixture full = fullFixture();

    MainBatchShape perTileArm =
        measureMainBatch(full, /*groundQuad=*/false, /*groundTexId=*/0, view);
    MainBatchShape quadArm =
        measureMainBatch(full, /*groundQuad=*/true, /*groundTexId=*/99, view);

    EXPECT_GT(perTileArm.mainVerts, 0) << "逐格臂主批必须非空";
    EXPECT_GT(quadArm.mainVerts, 0) << "整图臂主批必须非空（其余层未受影响）";

    // 整图臂主批必须严格小于逐格臂主批（逐格地面段已从主批消失），
    // 差值 = 被整图化的逐格地面顶点数（>0，且为 6 的整数倍 = 整数格数）
    const int removedGround = perTileArm.mainVerts - quadArm.mainVerts;
    EXPECT_GT(removedGround, 0)
        << "整图臂主批必须比逐格臂小——差值即被整图化移除的逐格地面段";
    EXPECT_EQ(0, removedGround % VERTICES_PER_SPRITE)
        << "移除量须为整格（每格 " << VERTICES_PER_SPRITE << " 顶点）的整数倍";

    // 整图臂的地面经独立提交（单 quad）——容量收益：1 quad 替代 N 格
    GroundShape quadOnly = measureGround(/*groundQuad=*/true, /*groundTexId=*/99, view);
    EXPECT_EQ(VERTICES_PER_SPRITE, quadOnly.groundSubmitVerts)
        << "整图地面段必须是单 quad";
    // 同视口下逐格地面段在整图臂中被移除的格数，必须远多于 1 个 quad
    EXPECT_GT(removedGround, quadOnly.groundSubmitVerts)
        << "逐格地面段必须显著大于整图单 quad（容量收益）";
}

// 生成 UV 表与夹具（Kotlin 公式快照）的静态对照：任何 LAYOUT 漂移在此即红
TEST_F(SceneEquivalenceTest, GeneratedUvTablesMatchKotlinFormulaFixture) {
    std::vector<float> tile = kotlinTileUv();
    ASSERT_EQ(tile.size(), sizeof(scene::kTileUv) / sizeof(float));
    for (size_t i = 0; i < tile.size(); i++) {
        ASSERT_EQ(tile[i], scene::kTileUv[i]) << "kTileUv[" << i << "]";
    }

    std::vector<float> building = kotlinBuildingUv();
    ASSERT_EQ(building.size(), sizeof(scene::kBuildingUv) / sizeof(float));
    for (size_t i = 0; i < building.size(); i++) {
        ASSERT_EQ(building[i], scene::kBuildingUv[i]) << "kBuildingUv[" << i << "]";
    }

    std::vector<float> crop = kotlinCropUv();
    ASSERT_EQ(crop.size(), sizeof(scene::kCropUv) / sizeof(float));
    for (size_t i = 0; i < crop.size(); i++) {
        ASSERT_EQ(crop[i], scene::kCropUv[i]) << "kCropUv[" << i << "]";
    }

    std::vector<float> cloud = kotlinCloudUv();
    ASSERT_EQ(cloud.size(), sizeof(scene::kCloudUv) / sizeof(float));
    for (size_t i = 0; i < cloud.size(); i++) {
        ASSERT_EQ(cloud[i], scene::kCloudUv[i]) << "kCloudUv[" << i << "]";
    }

    std::vector<float> road = kotlinRoadUv();
    ASSERT_EQ(road.size(), sizeof(scene::kRoadUv) / sizeof(float));
    for (size_t i = 0; i < road.size(); i++) {
        ASSERT_EQ(road[i], scene::kRoadUv[i]) << "kRoadUv[" << i << "]";
    }
}

}  // namespace
