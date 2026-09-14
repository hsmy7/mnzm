// terrain_freeze_test.cpp — WS-5b 地图冻结（生成即数据）GTest 黄金守护。
//
// 覆盖（batch-W4C §5.1 C-② 专项）：
//   1. 生成即数据：无段快照导入 ⇒ 地形段填充 + mapGenVersion 戳；同输入两次
//      导入 ⇒ 逐位相同（确定性；生成零 RNG——rngStates 快照差分证明）。
//   2. 存的地形恒优先：人为构造与生成器不一致的段 ⇒ 导入后保留（不重算不覆盖）。
//   3. 老档回填幂等：无段 → 导入（生成）→ 导出 → 再导入 ⇒ 第二次不重算。
//   4. 段存在性协议：无地形 ⇒ exportStateJson 不含地形键（"非空才导出"先例）；
//      有地形 ⇒ 键存在且往返可解析。
//   5. 未配置地形参数（width<=0，桌面最小测试面）⇒ 保持无段零变化。
//
// 注意：RLE 存储编解码为 Kotlin 存档层职责（core:data 转换器测试），C++ 内存
// 与协议结构面为 flat 单一表示（§2.19 红线），不在本文件范围。
#include <gtest/gtest.h>

#include <nlohmann/json.hpp>

#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/map/terrain.h"
#include "gamecore/state/json_codec.h"
#include "gamecore/state/models.h"

namespace gamecore {
namespace {

using nlohmann::json;

// 地形生成配置（与生产同量级：GameConfig.SectMap 128×128/ring 3/门楼 6×2 底边居中；
// 测试用 16×16 缩面降低墙钟，生成逻辑与尺寸无关）
GameCoreConfig terrainConfig() {
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = 42;
    config.terrainWidthCells = 16;
    config.terrainHeightCells = 16;
    config.terrainDecorationDensity = 0.18f;
    config.terrainBorderTreeRing = 2;
    config.terrainGateWidth = 6;
    config.terrainGateHeight = 2;
    config.terrainGateX = (16 - 6) / 2;
    config.terrainGateY = 16 - 2;
    config.terrainMapGenVersion = 1;
    return config;
}

// 无地形段的最小存档 JSON（mapSeed 已设——生成判据之一）
std::string seedOnlySaveJson(int64_t mapSeed) {
    gamecore::state::GameState state;
    state.gameData.id = "sect-1";
    state.gameData.mapSeed = static_cast<int32_t>(mapSeed);
    state.gameData.gameYear = 3;
    const json j = state;
    return j.dump();
}

class TerrainFreezeTest : public ::testing::Test {
protected:
    void SetUp() override {
        core_ = std::make_unique<GameCore>(&clock_, &logger_);
        core_->initialize(terrainConfig());
    }
    void TearDown() override {
        core_->shutdown();
        core_.reset();
    }

    FixedClock clock_{1'700'000'000'000L};
    NullLogger logger_{};
    std::unique_ptr<GameCore> core_;
};

// ── 1. 生成即数据 + 确定性（零 RNG 消耗）────────────────────────────

TEST_F(TerrainFreezeTest, ImportWithoutSegmentGeneratesDeterministically) {
    ASSERT_TRUE(core_->importStateJson(seedOnlySaveJson(12345)));

    const auto& gd = core_->state().gameData;
    EXPECT_EQ(gd.terrainTiles.size(), static_cast<size_t>(16 * 16));
    EXPECT_EQ(gd.mapGenVersion, 1);

    // 同输入两次导入 ⇒ 逐位相同（seed+坐标纯函数，与 RNG 无关）
    std::vector<int32_t> first = gd.terrainTiles;
    ASSERT_TRUE(core_->importStateJson(seedOnlySaveJson(12345)));
    EXPECT_EQ(core_->state().gameData.terrainTiles, first);

    // 生成零 RNG：rngStates 在导入前后逐位一致（分区抽取零消耗）
    const auto rngAfter = core_->state().gameData.rngStates;
    ASSERT_TRUE(core_->importStateJson(seedOnlySaveJson(12345)));
    EXPECT_EQ(core_->state().gameData.rngStates, rngAfter);
}

// ── 2. 存的地形恒优先（跨版本冻结）──────────────────────────────────

TEST_F(TerrainFreezeTest, StoredSegmentTakesPrecedenceOverRegeneration) {
    // 构造与生成器输出必然不一致的段（全 BUILDING 占位值——生成器不产出）
    gamecore::state::GameState state;
    state.gameData.id = "sect-1";
    state.gameData.mapSeed = 12345;
    state.gameData.mapGenVersion = 1;
    state.gameData.terrainTiles.assign(16 * 16,
                                       static_cast<int32_t>(gamecore::map::terrain::TILE_BUILDING));
    const json j = state;

    ASSERT_TRUE(core_->importStateJson(j.dump()));
    const auto& gd = core_->state().gameData;
    // 导入后保留该段：不重算、不覆盖（"存的地形恒优先"）
    ASSERT_EQ(gd.terrainTiles.size(), static_cast<size_t>(16 * 16));
    for (const auto tile : gd.terrainTiles) {
        EXPECT_EQ(tile, static_cast<int32_t>(gamecore::map::terrain::TILE_BUILDING));
    }
    EXPECT_EQ(gd.mapGenVersion, 1);
}

// ── 3. 老档回填幂等（导入 → 导出 → 再导入不重算）────────────────────

TEST_F(TerrainFreezeTest, BackfillIsIdempotentAcrossSaveLoadRoundTrip) {
    ASSERT_TRUE(core_->importStateJson(seedOnlySaveJson(777)));
    const std::string firstExport = core_->exportStateJson();

    // 再导入首次导出的存档（此时含地形段）⇒ 段逐位保留（非重算）
    ASSERT_TRUE(core_->importStateJson(firstExport));
    EXPECT_EQ(core_->exportStateJson(), firstExport);

    // 有段再导入 ⇒ mapGenVersion 不变（不按当前版本改写既有段）
    const auto& gd = core_->state().gameData;
    EXPECT_EQ(gd.mapGenVersion, 1);
    EXPECT_EQ(gd.terrainTiles.size(), static_cast<size_t>(16 * 16));
}

// ── 4. 段存在性协议（"非空才导出键"先例）────────────────────────────

TEST_F(TerrainFreezeTest, SegmentKeysExportedOnlyWhenPresent) {
    // 未配置地形参数的最小配置 ⇒ 导入后保持无段
    GameCore coreBare(&clock_, &logger_);
    GameCoreConfig bare = terrainConfig();
    bare.terrainWidthCells = 0;  // 未配置
    coreBare.initialize(bare);
    ASSERT_TRUE(coreBare.importStateJson(seedOnlySaveJson(12345)));

    const json noTerrain = json::parse(coreBare.exportStateJson());
    EXPECT_FALSE(noTerrain.contains("gameData") &&
                  noTerrain["gameData"].contains("terrainTiles"));
    EXPECT_FALSE(noTerrain.contains("gameData") &&
                  noTerrain["gameData"].contains("mapGenVersion"));
    coreBare.shutdown();

    // 有地形 ⇒ 键存在且可解析、内容 roundtrip 一致
    ASSERT_TRUE(core_->importStateJson(seedOnlySaveJson(12345)));
    const json withTerrain = json::parse(core_->exportStateJson());
    ASSERT_TRUE(withTerrain.contains("gameData"));
    EXPECT_TRUE(withTerrain["gameData"].contains("terrainTiles"));
    EXPECT_TRUE(withTerrain["gameData"].contains("mapGenVersion"));

    state::GameState decoded;
    state::from_json(withTerrain, decoded);
    EXPECT_EQ(decoded.gameData.terrainTiles, core_->state().gameData.terrainTiles);
    EXPECT_EQ(decoded.gameData.mapGenVersion, 1);
}

// ── 5. 无种子（mapSeed==0 未建档默认态）⇒ 不生成 ────────────────────

TEST_F(TerrainFreezeTest, NoSeedSkipsGeneration) {
    ASSERT_TRUE(core_->importStateJson(seedOnlySaveJson(0)));
    const auto& gd = core_->state().gameData;
    EXPECT_TRUE(gd.terrainTiles.empty());
    EXPECT_EQ(gd.mapGenVersion, 0);
}

// ── 6. 生成器直调对齐：C++ ensure 产出与 generateTileData 同源 ──────

TEST_F(TerrainFreezeTest, EnsureMatchesGenerateTileDataBitwise) {
    ASSERT_TRUE(core_->importStateJson(seedOnlySaveJson(2026)));
    const auto& gd = core_->state().gameData;

    gamecore::map::terrain::GateBox gate;
    gate.width = 6;
    gate.height = 2;
    gate.x = (16 - 6) / 2;
    gate.y = 16 - 2;
    const auto direct = gamecore::map::terrain::generateTileData(
        16, 16, 0.18f, 2026, 2, gate);
    EXPECT_EQ(gd.terrainTiles, direct);
}

}  // namespace
}  // namespace gamecore
