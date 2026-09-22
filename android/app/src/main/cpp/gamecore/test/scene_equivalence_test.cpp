#include <gtest/gtest.h>

#include <cstdint>
#include <vector>

#include "Rhi.h"
#include "SpriteBatcher.h"
#include "scene/scene_store.h"
#include "scene/scene_draw.h"
#include "gamecore/map/ground_boundary.h"

namespace {

using gamecore::map::computeGroundBoundary;
using gamecore::map::GroundBoundaryConfig;
using scene::buildBottomRockLayer;
using scene::buildGroundMeshLayer;
using scene::buildMapBatch;
using scene::GroundBoundaryView;
using scene::groundBoundaryParse;
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

/// 边界层独立纹理 id（底部岩石 / 地皮草——非图集，fake id 仅用于断言配对）
constexpr uint32_t kBoundaryRockTexId = 99;
constexpr uint32_t kBoundaryGroundTexId = 98;

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
    // 弯曲地皮轮廓复合数据（ground_boundary.h 产出；空 = 无边界）
    std::vector<float> boundary;

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

    // 弯曲地皮轮廓：真实合成器产出（生产同参 128²×48、深度 768）——
    // 夹具即权威输出，两臂从同一份数据分流（夹具直用 / SceneStore 往返）
    GroundBoundaryConfig cfg;
    cfg.cols = kCols;
    cfg.rows = kRows;
    cfg.tileSize = kTileSize;
    cfg.bottomDepth = 768.0f;
    computeGroundBoundary(cfg, fx.boundary);
    return fx;
}

/** 单要素裁剪：按启用层从全要素夹具派生（terrain 恒保留——层依赖基座） */
SceneFixture fixtureWith(bool roads_, bool buildings_, bool crops_, bool clouds_, bool boundary_) {
    SceneFixture fx = fullFixture();
    if (!roads_) fx.roads.assign(static_cast<size_t>(kCols) * kRows, 0);
    if (!buildings_) { fx.buildings.clear(); fx.buildingCount = 0; }
    if (!crops_) { fx.crops.clear(); fx.cropCount = 0; }
    if (!clouds_) { fx.clouds.clear(); fx.cloudCount = 0; }
    if (!boundary_) { fx.boundary.clear(); }
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

    // 边界解析（夹具臂：直接解析夹具复合数据；失败 = 数据坏，整层跳过）
    GroundBoundaryView fxBoundary;
    const bool fxBoundaryOk = groundBoundaryParse(
        fx.boundary.data(), static_cast<int>(fx.boundary.size()), &fxBoundary);

    CropSmoothingState cropState;
    for (int f = 0; f < frames; f++) {
        // 边界两层（生产层序：天空 → 底部岩石 → 地皮 → 地图批）
        if (fxBoundaryOk) {
            buildBottomRockLayer(fxBoundary, kBoundaryRockTexId, view.fadeAlpha,
                [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
                    if (count > 0) rec.draw(verts, count, texId);
                });
            buildGroundMeshLayer(fxBoundary, kBoundaryGroundTexId, view.fadeAlpha,
                [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
                    if (count > 0) rec.draw(verts, count, texId);
                });
        }

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
        p.tileMask = fxBoundaryOk ? fxBoundary.mask : nullptr;
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
    store.setGroundBoundary(fx.boundary.data(),
                            static_cast<int>(fx.boundary.size()));

    // 边界解析（新臂：从 SceneStore 往返数据解析——存储面纳入等价证明）
    GroundBoundaryView storeBoundary;
    const bool storeBoundaryOk = store.hasGroundBoundary() &&
        groundBoundaryParse(store.groundBoundaryData(),
                            store.groundBoundaryFloats(), &storeBoundary);

    CropSmoothingState cropState;
    for (int f = 0; f < frames; f++) {
        // 边界两层（生产层序：天空 → 底部岩石 → 地皮 → 地图批）
        if (storeBoundaryOk) {
            buildBottomRockLayer(storeBoundary, kBoundaryRockTexId, view.fadeAlpha,
                [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
                    if (count > 0) rec.draw(verts, count, texId);
                });
            buildGroundMeshLayer(storeBoundary, kBoundaryGroundTexId, view.fadeAlpha,
                [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
                    if (count > 0) rec.draw(verts, count, texId);
                });
        }

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
        p.tileMask = storeBoundaryOk ? storeBoundary.mask : nullptr;
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

TEST_F(SceneEquivalenceTest, BoundaryLayersAllCameras) {
    // 弯曲地皮轮廓（地图边缘 v2）：底部岩石 + 地皮草两层随相机三档位等价
    SceneFixture fx = fixtureWith(false, false, false, false, true);
    LayerFlags flags;
    for (const CameraView& view : kViews) {
        std::string what = "boundary cam(scale=" + std::to_string(view.scale) + ")";
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

    SceneFixture boundary = fixtureWith(false, false, false, false, true);
    auto boundaryCalls = runOldPath(boundary, mid, flags, 1);
    EXPECT_GT(boundaryCalls.size(), 0u);
    // 两层独立纹理提交齐全：岩石（99）+ 地皮（98），顶点流非空
    bool hasRock = false, hasGround = false;
    for (const auto& call : boundaryCalls) {
        if (call.texId == kBoundaryRockTexId && !call.verts.empty()) hasRock = true;
        if (call.texId == kBoundaryGroundTexId && !call.verts.empty()) hasGround = true;
    }
    EXPECT_TRUE(hasRock) << "底部岩石层必须产出顶点流";
    EXPECT_TRUE(hasGround) << "地皮层必须产出顶点流";
}

// ============================================================
// 弯曲地皮轮廓守卫（地图边缘系统 v2，替代 R3.5 远景地面路径组）
//
// 底色与边界的契约：地皮 mesh（独立草纹理 REPEAT）承担轮廓内全部底色，
// 底部岩石 mesh（独立岩石纹理 REPEAT）沿同源折线向下挤出；地图批不再含
// 逐格底色、只按掩码叠加 GRASS1..4 变体。本组锁定：
//   ① 两层独立提交齐全且纹理 id 正确（岩石/地皮各自一次 draw）；
//   ② 无边界数据时两层整体跳过、地图批不受影响（降级而非黑屏）；
//   ③ 掩码门控——掩码清零时变体格从地图批消失（边界带不越轮廓），
//     移除量为整格（6 顶点/格）的整数倍。
// ============================================================

namespace {

/// 组装 MapLayerParams（给定夹具 + 掩码 + 相机）——单点维护，避免测试内重复
MapLayerParams paramsFor(const SceneFixture& fx, const float* tileMask,
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
    p.tileMask = tileMask;
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

/// 解析夹具边界（无边界返回 false）
bool parseBoundary(const SceneFixture& fx, GroundBoundaryView* out) {
    return groundBoundaryParse(fx.boundary.data(),
                               static_cast<int>(fx.boundary.size()), out);
}

}  // namespace

// ① 边界两层：独立纹理提交齐全（岩石/地皮各一次 draw，顶点流非空，
//    淡入 alpha 乘入顶点色——与 quad 的 fadeAlpha 同语义）
TEST_F(SceneEquivalenceTest, BoundaryLayersSubmitRockThenGround) {
    SceneFixture fx = fullFixture();
    GroundBoundaryView v;
    ASSERT_TRUE(parseBoundary(fx, &v));

    RecorderRenderer rec;
    buildBottomRockLayer(v, kBoundaryRockTexId, /*fadeAlpha=*/0.5f,
        [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
            if (count > 0) rec.draw(verts, count, texId);
        });
    buildGroundMeshLayer(v, kBoundaryGroundTexId, /*fadeAlpha=*/0.5f,
        [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
            if (count > 0) rec.draw(verts, count, texId);
        });

    ASSERT_EQ(rec.calls.size(), 2u) << "必须恰好岩石/地皮各一次 draw";
    EXPECT_EQ(rec.calls[0].texId, kBoundaryRockTexId);
    EXPECT_EQ(rec.calls[1].texId, kBoundaryGroundTexId);
    for (const auto& call : rec.calls) {
        ASSERT_GT(call.verts.size(), 0u);
        EXPECT_EQ(call.verts.size() % 3u, 0u) << "三角列表顶点数须为 3 的倍数";
        for (const auto& vert : call.verts) {
            EXPECT_FLOAT_EQ(vert.a, 0.5f) << "淡入 alpha 必须乘入顶点色";
        }
    }
}

// ② 无边界数据：两层整体跳过（纹理 id=0 / 空 mesh 同守卫），不崩溃不画白
TEST_F(SceneEquivalenceTest, BoundaryAbsentSkipsBothLayers) {
    SceneFixture fx = fixtureWith(false, false, false, false, false);
    GroundBoundaryView v;
    ASSERT_FALSE(parseBoundary(fx, &v));

    RecorderRenderer rec;
    buildBottomRockLayer(v, kBoundaryRockTexId, 1.0f,
        [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
            if (count > 0) rec.draw(verts, count, texId);
        });
    buildGroundMeshLayer(v, kBoundaryGroundTexId, 1.0f,
        [&rec](uint32_t texId, const SpriteVertex* verts, int count) {
            if (count > 0) rec.draw(verts, count, texId);
        });
    buildBottomRockLayer(v, 0, 1.0f,
        [&rec](uint32_t, const SpriteVertex*, int) { FAIL() << "texId=0 不得提交"; });
    EXPECT_EQ(rec.calls.size(), 0u) << "无边界/无纹理时两层必须整体跳过";
}

// ③ 掩码门控：掩码清零 ⇒ 变体格退出地图批（差 = 整格顶点倍数）；
//    掩码 = 真实轮廓 ⇒ 变体格保留（边界带才被剔除）
TEST_F(SceneEquivalenceTest, TileMaskGatesGroundVariants) {
    const CameraView& view = kViews[2];  // 远景：整岛可见，边界带格全部在场
    SceneFixture fx = fullFixture();
    GroundBoundaryView v;
    ASSERT_TRUE(parseBoundary(fx, &v));

    float proj[16];
    float bounds[4];
    SpriteBatcher batcher;
    CropSmoothingState cropState;

    MapLayerParams pReal = paramsFor(fx, v.mask, view, proj, bounds);
    const int realVerts = buildMapBatch(batcher, pReal, proj, cropState);

    std::vector<float> zeroMask(static_cast<size_t>(kCols) * kRows, 0.0f);
    MapLayerParams pZero = paramsFor(fx, zeroMask.data(), view, proj, bounds);
    const int zeroVerts = buildMapBatch(batcher, pZero, proj, cropState);

    EXPECT_GT(realVerts, 0) << "真实掩码臂主批必须非空";
    EXPECT_LT(zeroVerts, realVerts) << "清零掩码必须剔掉全部变体格";
    const int removed = realVerts - zeroVerts;
    EXPECT_EQ(removed % 6, 0) << "变体移除量须为整格（6 顶点/格）的整数倍";
    EXPECT_GT(removed, 0);
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
