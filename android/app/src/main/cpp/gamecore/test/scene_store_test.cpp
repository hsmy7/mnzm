#include <gtest/gtest.h>

#include <vector>

#include "scene/scene_store.h"

namespace {

using scene::kBuildingStride;
using scene::kCliffStride;
using scene::kCloudStride;
using scene::kCropStride;
using scene::SceneStore;

// ============================================================
// SceneStore 单元测试（重构方案 2026-09-17 R3.1/B10）
//
// 覆盖：六类场景要素（地形/道路/建筑/作物/云/崖壁）的导入-读取逐值往返、
//      整表替换、清空语义（nullptr/零计数 = 层清空）、无效入参防御、
//      reset 纪元复位、步长常量与 Kotlin 侧协议一致锚点。
// 等价性红线：本模块只做逐值存储——读回值必须与导入值逐位一致
// （旧 drawAllTiles 路径每次读同一数组；新路径读 SceneStore 副本，
//  两者等价的前提即此处锁定的"零改动搬运"）。
// ============================================================

TEST(SceneStoreTest, TerrainRoundTripsExactly) {
    SceneStore store;
    std::vector<int32_t> tiles(64);
    for (size_t i = 0; i < tiles.size(); i++) {
        tiles[i] = static_cast<int32_t>(i % 11);
    }
    store.setTerrain(tiles.data(), static_cast<int64_t>(tiles.size()), 8, 8, 48);

    ASSERT_TRUE(store.hasTerrain());
    EXPECT_EQ(8, store.cols());
    EXPECT_EQ(8, store.rows());
    EXPECT_EQ(48, store.tileSize());
    ASSERT_EQ(static_cast<int64_t>(tiles.size()), store.terrainCount());
    const int32_t* out = store.terrainData();
    for (size_t i = 0; i < tiles.size(); i++) {
        ASSERT_EQ(tiles[i], out[i]) << "地形必须逐值往返 index=" << i;
    }
}

TEST(SceneStoreTest, TerrainReplaceIsWholeTable) {
    // 地图切换/建筑占位变化 = 整表替换（非增量合并）——旧表值不得残留
    SceneStore store;
    const int32_t first[4] = {0, 1, 2, 3};
    store.setTerrain(first, 4, 2, 2, 48);
    const int32_t second[9] = {9, 9, 9, 9, 9, 9, 9, 9, 9};
    store.setTerrain(second, 9, 3, 3, 32);

    ASSERT_EQ(9, store.terrainCount());
    EXPECT_EQ(3, store.cols());
    EXPECT_EQ(3, store.rows());
    EXPECT_EQ(32, store.tileSize());
    for (int i = 0; i < 9; i++) {
        EXPECT_EQ(9, store.terrainData()[i]);
    }
}

TEST(SceneStoreTest, TerrainInvalidInputClearsLayer) {
    SceneStore store;
    const int32_t tiles[4] = {0, 1, 2, 3};
    store.setTerrain(tiles, 4, 2, 2, 48);

    // 无效入参（null/零计数/非法网格）= 防御性清空，不残留旧地形
    store.setTerrain(nullptr, 4, 2, 2, 48);
    EXPECT_FALSE(store.hasTerrain());
    store.setTerrain(tiles, 4, 2, 2, 48);
    store.setTerrain(tiles, 0, 2, 2, 48);
    EXPECT_FALSE(store.hasTerrain());
    store.setTerrain(tiles, 4, 0, 2, 48);
    EXPECT_FALSE(store.hasTerrain());
    store.setTerrain(tiles, 4, 2, 2, 0);
    EXPECT_FALSE(store.hasTerrain());
}

TEST(SceneStoreTest, RoadsReplaceAndClear) {
    SceneStore store;
    const int32_t masks[8] = {0, 1, 3, 7, 15, 5, 9, 6};
    store.updateRoads(masks, 8);
    ASSERT_TRUE(store.hasRoads());
    ASSERT_EQ(8, store.roadsCount());
    for (int i = 0; i < 8; i++) {
        EXPECT_EQ(masks[i], store.roadsData()[i]);
    }

    // 清空 = 层跳过（等价旧路径 roadData=null）
    store.updateRoads(nullptr, 8);
    EXPECT_FALSE(store.hasRoads());
    store.updateRoads(masks, 8);
    store.updateRoads(masks, 0);
    EXPECT_FALSE(store.hasRoads());
}

TEST(SceneStoreTest, BuildingsKeepFiveFloatStride) {
    SceneStore store;
    // 2 栋建筑 + 1 条多余尾部（调用方 claim 数 < 数组容量时钳制——
    // 与旧路径 effectiveCount = min(buildingCount, arrLen/5) 同语义）
    const float data[] = {
        1.0f, 2.0f, 4.0f, 3.0f, 0.0f,    // 建筑 0
        5.0f, 6.0f, 2.0f, 2.0f, 19.0f,   // 建筑 1（固定结构 nameIdx）
        9.0f, 9.0f, 9.0f, 9.0f, 9.0f,    // 尾部：超出 claim 被忽略
    };
    store.updateBuildings(data, 2);
    ASSERT_EQ(2, store.buildingCount());
    const float* out = store.buildingsData();
    for (int b = 0; b < 2; b++) {
        for (int f = 0; f < kBuildingStride; f++) {
            ASSERT_EQ(data[b * kBuildingStride + f], out[b * kBuildingStride + f])
                << "建筑 " << b << " 字段 " << f << " 必须逐位往返";
        }
    }

    store.updateBuildings(data, 0);
    EXPECT_FALSE(store.hasBuildings());
    store.updateBuildings(nullptr, 2);
    EXPECT_FALSE(store.hasBuildings());
}

TEST(SceneStoreTest, CropsKeepThreeFloatStride) {
    SceneStore store;
    const float data[] = {1.0f, 1.0f, 0.25f, 2.0f, 2.0f, 0.6666667f, 3.0f, 3.0f, 1.0f};
    store.updateCrops(data, 3);
    ASSERT_EQ(3, store.cropCount());
    for (int i = 0; i < 3 * kCropStride; i++) {
        EXPECT_EQ(data[i], store.cropsData()[i]);
    }

    store.updateCrops(data, 0);
    EXPECT_FALSE(store.hasCrops());
}

TEST(SceneStoreTest, CloudsKeepSixFloatStride) {
    SceneStore store;
    const float data[] = {
        10.0f, 20.0f, 300.0f, 80.0f, 0.0f, 0.8f,
        40.0f, 50.0f, 200.0f, 60.0f, 2.0f, 0.5f,
    };
    store.updateClouds(data, 2);
    ASSERT_EQ(2, store.cloudCount());
    for (int i = 0; i < 2 * kCloudStride; i++) {
        EXPECT_EQ(data[i], store.cloudsData()[i]);
    }

    store.updateClouds(data, 0);
    EXPECT_FALSE(store.hasClouds());
}

TEST(SceneStoreTest, CliffLayoutRoundTripsTenFloatStride) {
    SceneStore store;
    // 2 个布局条目（texIdx 为 int 语义，存储面与 Kotlin 侧 FloatArray 同形）
    const float data[] = {
        0.0f, 0.0f, 100.0f, 48.0f, 96.0f, 0.0f, 0.0f, 0.5f, 0.25f, 0.0f,
        3.0f, 48.0f, 100.0f, 48.0f, 192.0f, 0.5f, 0.0f, 1.0f, 0.25f, 1.0f,
    };
    store.setCliffLayout(data, 2);
    ASSERT_EQ(2, store.cliffPieceCount());
    for (int i = 0; i < 2 * kCliffStride; i++) {
        EXPECT_EQ(data[i], store.cliffsData()[i]);
    }

    store.setCliffLayout(nullptr, 2);
    EXPECT_FALSE(store.hasCliffs());
}

TEST(SceneStoreTest, ResetClearsAllLayersForSurfaceEpoch) {
    // shutdownRenderer 纪元复位：六层全清，防跨 surface 代际残留
    SceneStore store;
    const int32_t tiles[4] = {0, 1, 2, 3};
    const int32_t roads[2] = {0, 1};
    const float buildings[5] = {0, 0, 1, 1, 0};
    const float crops[3] = {0, 0, 0.5f};
    const float clouds[6] = {0, 0, 1, 1, 0, 1};
    const float cliffs[10] = {0, 0, 0, 1, 1, 0, 0, 1, 1, 0};
    store.setTerrain(tiles, 4, 2, 2, 48);
    store.updateRoads(roads, 2);
    store.updateBuildings(buildings, 1);
    store.updateCrops(crops, 1);
    store.updateClouds(clouds, 1);
    store.setCliffLayout(cliffs, 1);

    store.reset();
    EXPECT_FALSE(store.hasTerrain());
    EXPECT_FALSE(store.hasRoads());
    EXPECT_FALSE(store.hasBuildings());
    EXPECT_FALSE(store.hasCrops());
    EXPECT_FALSE(store.hasClouds());
    EXPECT_FALSE(store.hasCliffs());
    EXPECT_EQ(0, store.cols());
    EXPECT_EQ(0, store.rows());
    EXPECT_EQ(0, store.tileSize());
}

TEST(SceneStoreTest, ProtocolStridesMatchLegacyJniFace) {
    // 步长常量 = 旧 drawAllTiles 17 参数协议面的数组步长（Kotlin 侧同值）——
    // 端口协议漂移即红（新增场景字段必须同步 Kotlin 生产者与本表）
    EXPECT_EQ(5, kBuildingStride);
    EXPECT_EQ(3, kCropStride);
    EXPECT_EQ(6, kCloudStride);
    EXPECT_EQ(10, kCliffStride);
}

}  // namespace
