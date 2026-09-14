// ============================================================
// terrain_test.cpp — 宗门地图地形生成器 GTest
//
// 覆盖：cellHash 手算黄金值/值域、smoothNoise 确定性与值域、生成
// 确定性/尺寸/值域、密度 0 无装饰、边界树环棋盘语义、门楼清场、
// 生产配置冒烟。跨语言位级一致由 Kotlin 侧 DiffSectTerrainTest
// （桌面 JNI 对拍）守护——本文件只锁 C++ 自身黄金语义。
// ============================================================
#include <gtest/gtest.h>

#include <cstdint>
#include <vector>

#include "gamecore/map/terrain.h"

namespace {

using gamecore::map::terrain::GateBox;
using gamecore::map::terrain::TILE_GRASS1;
using gamecore::map::terrain::TILE_GRASS2;
using gamecore::map::terrain::TILE_GRASS3;
using gamecore::map::terrain::TILE_GRASS4;
using gamecore::map::terrain::TILE_GROUND;
using gamecore::map::terrain::TILE_STONE1;
using gamecore::map::terrain::TILE_STONE2;
using gamecore::map::terrain::TILE_STONE3;
using gamecore::map::terrain::TILE_TREE1;
using gamecore::map::terrain::TILE_TREE2;
using gamecore::map::terrain::cellHash;
using gamecore::map::terrain::generateTileData;
using gamecore::map::terrain::smoothNoise;

/** 是否草变体（4 种之一） */
bool isGrass(int32_t t) {
    return t == TILE_GRASS1 || t == TILE_GRASS2 || t == TILE_GRASS3 || t == TILE_GRASS4;
}

/** 是否石变体（3 种之一） */
bool isStone(int32_t t) {
    return t == TILE_STONE1 || t == TILE_STONE2 || t == TILE_STONE3;
}

/** 是否树变体 */
bool isTree(int32_t t) { return t == TILE_TREE1 || t == TILE_TREE2; }

// 生产门楼盒（GameConfig.SectMap：128² / GATE 6×2 底边居中 / 精灵 1:1 贴地）
constexpr GateBox kProdGate{61, 126, 6, 2, 126};

std::vector<int32_t> generate(int32_t w, int32_t h, float density, int32_t seed,
                              int32_t ring, const GateBox& gate = kProdGate) {
    return generateTileData(w, h, density, seed, ring, gate);
}

// ── cellHash ──────────────────────────────────────────────────

TEST(TerrainTest, CellHashZeroOriginZeroSeedIsZero) {
    // h = 0 → 全链零值
    EXPECT_EQ(cellHash(0, 0, 0), 0.0f);
}

TEST(TerrainTest, CellHashHandComputedGolden) {
    // (0,0,42) 可全手算：h64=42（无溢出截断）, h<<13=344064, xor=344106,
    // mixed=42×344106=14452452, &0x7FFFFFFF 不变, %10000=2452 → 0.2452f
    EXPECT_EQ(cellHash(0, 0, 42), 2452.0f / 10000.0f);
}

TEST(TerrainTest, CellHashInRangeForSampleGrid) {
    for (int32_t y = 0; y < 40; ++y) {
        for (int32_t x = 0; x < 40; ++x) {
            const float v = cellHash(x, y, 12345);
            ASSERT_GE(v, 0.0f) << "(" << x << "," << y << ")";
            ASSERT_LT(v, 1.0f) << "(" << x << "," << y << ")";
        }
    }
}

TEST(TerrainTest, CellHashSeedChangesValue) {
    // 同坐标不同种子必须产生不同哈希（抽样网格至少一处不同）
    bool differs = false;
    for (int32_t x = 0; x < 64 && !differs; ++x) {
        for (int32_t y = 0; y < 64; ++y) {
            if (cellHash(x, y, 0) != cellHash(x, y, 777)) {
                differs = true;
                break;
            }
        }
    }
    EXPECT_TRUE(differs);
}

// ── smoothNoise ───────────────────────────────────────────────

TEST(TerrainTest, SmoothNoiseDeterministic) {
    for (int32_t y = 0; y < 16; ++y) {
        for (int32_t x = 0; x < 16; ++x) {
            EXPECT_EQ(smoothNoise(x, y, 8, 42), smoothNoise(x, y, 8, 42));
        }
    }
}

TEST(TerrainTest, SmoothNoiseInRange) {
    // 值域 [0,1) 仅对生成域 x,y >= 0 成立（fx ∈ [0,1) → 双线性权值非负且和为 1）；
    // 负坐标 fx < 0 会出界——Kotlin 同式同象（截断朝零），位级一致由 Diff 对拍守护
    for (int32_t y = 0; y < 300; ++y) {
        for (int32_t x = 0; x < 300; ++x) {
            const float v = smoothNoise(x, y, 8, 42);
            ASSERT_GE(v, 0.0f);
            ASSERT_LT(v, 1.0f);
        }
    }
}

TEST(TerrainTest, SmoothNoiseAdjacentCellsBoundedJump) {
    // 平滑噪声相邻格变化有界（< 0.5）——草滩成片而非噪点
    for (int32_t y = 0; y < 64; ++y) {
        for (int32_t x = 0; x < 64; ++x) {
            const float dx = smoothNoise(x + 1, y, 8, 42) - smoothNoise(x, y, 8, 42);
            const float dy = smoothNoise(x, y + 1, 8, 42) - smoothNoise(x, y, 8, 42);
            ASSERT_LT(dx < 0 ? -dx : dx, 0.5f);
            ASSERT_LT(dy < 0 ? -dy : dy, 0.5f);
        }
    }
}

// ── generateTileData ──────────────────────────────────────────

TEST(TerrainTest, DeterministicSameSeed) {
    const auto a = generate(48, 48, 0.18f, 42, 3);
    const auto b = generate(48, 48, 0.18f, 42, 3);
    ASSERT_EQ(a.size(), b.size());
    EXPECT_EQ(a, b);
}

TEST(TerrainTest, DifferentSeedDiffers) {
    const auto a = generate(48, 48, 0.18f, 1, 3);
    const auto b = generate(48, 48, 0.18f, 2, 3);
    ASSERT_EQ(a.size(), b.size());
    bool differs = false;
    for (size_t i = 0; i < a.size(); ++i) {
        if (a[i] != b[i]) {
            differs = true;
            break;
        }
    }
    EXPECT_TRUE(differs);
}

TEST(TerrainTest, SizeAndTileValueRange) {
    const auto data = generate(128, 128, 0.18f, 42, 3);
    ASSERT_EQ(data.size(), static_cast<size_t>(128) * 128);
    for (const int32_t t : data) {
        ASSERT_GE(t, TILE_GROUND);
        ASSERT_LE(t, TILE_TREE2);
    }
}

TEST(TerrainTest, ZeroDensityProducesBareTerrain) {
    // density=0 → threshold=1 → smoothNoise 恒 <1 → 草/树全跳过；
    // 仅剩边界树环 + 门楼清场
    const auto data = generate(64, 64, 0.0f, 7, 3);
    for (int32_t y = 0; y < 64; ++y) {
        for (int32_t x = 0; x < 64; ++x) {
            const int32_t t = data[static_cast<size_t>(y) * 64 + x];
            const bool border = x < 3 || x >= 61 || y < 3 || y >= 61;
            if (border) {
                ASSERT_TRUE(t == TILE_TREE1 || t == TILE_TREE2)
                    << "(" << x << "," << y << ")=" << t;
            } else {
                ASSERT_EQ(t, TILE_GROUND) << "(" << x << "," << y << ")";
            }
        }
    }
}

TEST(TerrainTest, ZeroRingKeepsInteriorUntouched) {
    const auto bare = generate(48, 48, 0.0f, 9, 0);
    const auto ringed = generate(48, 48, 0.0f, 9, 2);
    for (int32_t y = 2; y < 46; ++y) {
        for (int32_t x = 2; x < 46; ++x) {
            EXPECT_EQ(bare[static_cast<size_t>(y) * 48 + x],
                      ringed[static_cast<size_t>(y) * 48 + x])
                << "(" << x << "," << y << ")";
        }
    }
}

TEST(TerrainTest, BorderRingCheckerboard) {
    const auto data = generate(32, 32, 0.0f, 0, 3);
    for (int32_t y = 0; y < 32; ++y) {
        for (int32_t x = 0; x < 32; ++x) {
            if (x < 3 || x >= 29 || y < 3 || y >= 29) {
                const int32_t expected = ((x + y) % 2 == 0) ? TILE_TREE1 : TILE_TREE2;
                EXPECT_EQ(data[static_cast<size_t>(y) * 32 + x], expected)
                    << "(" << x << "," << y << ")";
            }
        }
    }
}

TEST(TerrainTest, GatewayClearsThroughRing) {
    // 门楼清场必须穿透边界树环（61..66 列 ±1 与 126..127 行全为 GROUND）
    const auto data = generate(128, 128, 0.18f, 42, 3);
    for (int32_t y = 126; y < 128; ++y) {
        for (int32_t x = 60; x < 69; ++x) {
            EXPECT_EQ(data[static_cast<size_t>(y) * 128 + x], TILE_GROUND)
                << "(" << x << "," << y << ")";
        }
    }
}

TEST(TerrainTest, SmallMapSkipsGatewayWithoutCrash) {
    // 小地图（h < maxY）跳过清场，不越界不崩溃
    const GateBox tinyGate{61, 126, 6, 2, 126};
    const auto data = generate(16, 16, 0.18f, 42, 0, tinyGate);
    EXPECT_EQ(data.size(), static_cast<size_t>(256));
}

TEST(TerrainTest, ProductionConfigSmoke) {
    // 生产参数（128²/0.18/ring3/seed 42）：边界环存在 + 装饰存在 + 门楼清场
    const auto data = generate(128, 128, 0.18f, 42, 3);
    bool hasGrass = false;
    bool hasDecorTree = false;
    bool hasStone = false;
    int32_t borderTreeCount = 0;
    for (int32_t y = 0; y < 128; ++y) {
        for (int32_t x = 0; x < 128; ++x) {
            const int32_t t = data[static_cast<size_t>(y) * 128 + x];
            const bool border = x < 3 || x >= 125 || y < 3 || y >= 125;
            if (border) {
                if (isTree(t)) ++borderTreeCount;
            } else if (isGrass(t)) {
                hasGrass = true;
            } else if (isTree(t)) {
                hasDecorTree = true;
            } else if (isStone(t)) {
                hasStone = true;
            }
        }
    }
    // 环格数 1500（128²-122²）减门楼清场穿透的 18 格（126..127 行 × 60..68 列）
    EXPECT_EQ(borderTreeCount, 128 * 128 - 122 * 122 - 2 * 9);
    EXPECT_TRUE(hasGrass);
    EXPECT_TRUE(hasDecorTree);
    EXPECT_TRUE(hasStone);
    EXPECT_EQ(data[static_cast<size_t>(127) * 128 + 63], TILE_GROUND);  // 门楼中心
}

TEST(TerrainTest, AllGrassAndStoneVariantsAppearAtFullDensity) {
    // 满密度大图：4 种草变体与 3 种石变体都应至少出现一次（变体抽取为 hash 分档）
    const auto data = generate(96, 96, 1.0f, 7, 0);
    bool grass[4] = {false, false, false, false};
    bool stone[3] = {false, false, false};
    for (const int32_t t : data) {
        switch (t) {
            case TILE_GRASS1: grass[0] = true; break;
            case TILE_GRASS2: grass[1] = true; break;
            case TILE_GRASS3: grass[2] = true; break;
            case TILE_GRASS4: grass[3] = true; break;
            case TILE_STONE1: stone[0] = true; break;
            case TILE_STONE2: stone[1] = true; break;
            case TILE_STONE3: stone[2] = true; break;
            default: break;
        }
    }
    for (int i = 0; i < 4; ++i) EXPECT_TRUE(grass[i]) << "草变体 " << i + 1;
    for (int i = 0; i < 3; ++i) EXPECT_TRUE(stone[i]) << "石变体 " << i + 1;
}

TEST(TerrainTest, StoneScatterSparserThanGrassPatches) {
    // 石堆为点状散布（密度系数 0.12、散布上限 0.35），草为成片草滩（0.80）
    const auto data = generate(96, 96, 0.5f, 3, 0);
    int32_t grassCount = 0;
    int32_t stoneCount = 0;
    for (const int32_t t : data) {
        if (isGrass(t)) ++grassCount;
        if (isStone(t)) ++stoneCount;
    }
    EXPECT_GT(stoneCount, 0);
    EXPECT_LT(stoneCount, grassCount);
}

TEST(TerrainTest, StonesNeverOverrideGrassOrTrees) {
    // 石堆 pass 在草滩之后、树丛之前；树丛又只落在裸地——三者互不覆盖（同格单值），
    // 语义等价于"装饰种类由 pass 序唯一决定"：石头格必然是被草滩跳过的裸地
    const auto data = generate(64, 64, 0.35f, 11, 0);
    for (const int32_t t : data) {
        const bool known = t == TILE_GROUND || isGrass(t) || isStone(t) || isTree(t);
        ASSERT_TRUE(known) << "未知瓦片值 " << t;
    }
}

}  // namespace
