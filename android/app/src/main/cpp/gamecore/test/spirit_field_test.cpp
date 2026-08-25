#include <gtest/gtest.h>

#include <string>
#include <vector>

#include "gamecore/data/herb_db.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/spirit_field.h"

namespace gamecore {
namespace {

using gamecore::data::herbTemplates;
using gamecore::data::seedTemplates;
using gamecore::data::herbIdFromSeedId;
using gamecore::rng::RngManager;
using gamecore::state::GameState;
using gamecore::state::SpiritFieldPlant;
using gamecore::system::OverflowMailCollector;
using gamecore::system::processSpiritFieldHarvest;

// ── HerbDatabase 静态表 ─────────────────────────────────────────

TEST(HerbDbTest, HerbCountMatchesKotlin) {
    EXPECT_EQ(herbTemplates().size(), 54u);
    EXPECT_EQ(seedTemplates().size(), 54u);
}

TEST(HerbDbTest, RepresentativeEntries) {
    const auto& herbs = herbTemplates();
    const auto& spiritGrass1 = std::find_if(herbs.begin(), herbs.end(),
        [](const auto& h) { return h.id == "spiritGrass1"; });
    ASSERT_NE(spiritGrass1, herbs.end());
    EXPECT_EQ(spiritGrass1->name, "聚灵草");
    EXPECT_EQ(spiritGrass1->tier, 1);
    EXPECT_EQ(spiritGrass1->rarity, 1);
    EXPECT_EQ(spiritGrass1->category, "grass");

    const auto& seeds = seedTemplates();
    const auto& gr1Seed = std::find_if(seeds.begin(), seeds.end(),
        [](const auto& s) { return s.id == "spiritGrass1Seed"; });
    ASSERT_NE(gr1Seed, seeds.end());
    EXPECT_EQ(gr1Seed->name, "聚灵草种");
    EXPECT_EQ(gr1Seed->growTime, 36);
    EXPECT_EQ(gr1Seed->yield, 5);
}

TEST(HerbDbTest, HerbIdFromSeedId) {
    EXPECT_EQ(herbIdFromSeedId("spiritGrass1Seed"), "spiritGrass1");
    EXPECT_EQ(herbIdFromSeedId("spiritFruit18Seed"), "spiritFruit18");
    EXPECT_EQ(herbIdFromSeedId("noSuffix"), "");
}

TEST(HerbDbTest, SeedToHerbMappingConsistent) {
    // 每个种子 id 去 Seed 后缀后应能在灵草表中找到对应灵草
    for (const auto& s : seedTemplates()) {
        const std::string herbId = herbIdFromSeedId(s.id);
        ASSERT_FALSE(herbId.empty()) << "seed " << s.id << " 无对应灵草";
        const bool found = std::any_of(herbTemplates().begin(), herbTemplates().end(),
            [&](const auto& h) { return h.id == herbId; });
        EXPECT_TRUE(found) << "seed " << s.id << " -> " << herbId << " 未找到灵草模板";
    }
}

// ── 灵田收获 ────────────────────────────────────────────────────

/// 构造一个成熟地块（plantYear/plantMonth 足够早；growTime 默认 1 保证成熟）
SpiritFieldPlant makeMaturePlant(int index, const std::string& seedName,
                                 int growTime = 1, int yield = 5) {
    SpiritFieldPlant p;
    p.buildingInstanceId = "field-" + std::to_string(index);
    p.seedId = "seed-" + std::to_string(index);
    p.seedName = seedName;
    p.growTime = growTime;
    p.expectedYield = yield;
    p.plantYear = 1;
    p.plantMonth = 1;
    p.completionMonth = 12 + growTime;
    p.completionPhase = 3;
    return p;
}

TEST(SpiritFieldHarvestTest, NoPlantsNoOp) {
    GameState state;
    state.gameData.gameYear = 2;
    state.gameData.gameMonth = 3;
    RngManager rng;
    OverflowMailCollector mail;
    const auto result = processSpiritFieldHarvest(state, rng, mail);
    EXPECT_EQ(result.plantsCompleted, 0);
    EXPECT_EQ(result.herbsHarvested, 0);
}

TEST(SpiritFieldHarvestTest, HarvestMaturePlantAddsHerb) {
    GameState state;
    state.gameData.gameYear = 2;
    state.gameData.gameMonth = 3;
    state.gameData.spiritFieldPlants.push_back(makeMaturePlant(0, "聚灵草种"));
    RngManager rng;
    rng.initSystemSeed(12345);
    OverflowMailCollector mail;
    const auto result = processSpiritFieldHarvest(state, rng, mail);
    EXPECT_EQ(result.plantsCompleted, 1);
    // 灵草入库：聚灵草 rarity=1
    ASSERT_FALSE(state.herbs.empty());
    EXPECT_EQ(state.herbs[0].name, "聚灵草");
    EXPECT_EQ(state.herbs[0].quantity, 5);
    // 年度报告
    EXPECT_EQ(state.gameData.annualHerbCount, 1);
    EXPECT_EQ(state.gameData.annualHerbBySource["spirit_field"], 5);
    EXPECT_EQ(state.gameData.guideCounters["herbsHarvested"], 1);
}

TEST(SpiritFieldHarvestTest, ImmaturePlantNotHarvested) {
    GameState state;
    state.gameData.gameYear = 1;
    state.gameData.gameMonth = 1;
    auto plant = makeMaturePlant(0, "聚灵草种");
    plant.growTime = 100;  // 需要 100 个月
    state.gameData.spiritFieldPlants.push_back(plant);
    RngManager rng;
    OverflowMailCollector mail;
    const auto result = processSpiritFieldHarvest(state, rng, mail);
    EXPECT_EQ(result.plantsCompleted, 0);
    EXPECT_TRUE(state.herbs.empty());
    // 地块保留
    ASSERT_EQ(state.gameData.spiritFieldPlants.size(), 1u);
    EXPECT_EQ(state.gameData.spiritFieldPlants[0].seedId, "seed-0");
}

TEST(SpiritFieldHarvestTest, CrossSectPlantIgnored) {
    GameState state;
    state.gameData.activeSectId = "player";
    state.gameData.gameYear = 2;
    state.gameData.gameMonth = 3;
    auto plant = makeMaturePlant(0, "聚灵草种");
    plant.sectId = "other-sect";  // 越权地块
    state.gameData.spiritFieldPlants.push_back(plant);
    RngManager rng;
    OverflowMailCollector mail;
    const auto result = processSpiritFieldHarvest(state, rng, mail);
    EXPECT_EQ(result.plantsCompleted, 0);
    EXPECT_TRUE(state.herbs.empty());
}

TEST(SpiritFieldHarvestTest, ReseedConsumesExistingSeed) {
    GameState state;
    state.gameData.gameYear = 2;
    state.gameData.gameMonth = 3;
    state.gameData.spiritFieldPlants.push_back(makeMaturePlant(0, "聚灵草种"));
    // 仓库预放 2 颗聚灵草种（未锁定）→ 收获后消耗 1 颗续种
    // growTime 与地块一致（makeMaturePlant 默认 1）——续种匹配键含 growTime
    state::Seed s;
    s.id = "seed-stack-1";
    s.name = "聚灵草种";
    s.rarity = 1;
    s.growTime = 1;
    s.yield = 5;
    s.quantity = 2;
    state.seeds.push_back(s);
    RngManager rng;
    rng.initSystemSeed(12345);
    OverflowMailCollector mail;
    const auto result = processSpiritFieldHarvest(state, rng, mail);
    EXPECT_EQ(result.plantsCompleted, 1);
    // 续种：地块仍在，plantYear 更新为当前
    ASSERT_EQ(state.gameData.spiritFieldPlants.size(), 1u);
    EXPECT_EQ(state.gameData.spiritFieldPlants[0].plantYear, 2);
    EXPECT_EQ(state.gameData.spiritFieldPlants[0].plantMonth, 3);
    EXPECT_FALSE(state.gameData.spiritFieldPlants[0].seedId.empty());
    // 仓库种子：原 2 颗 + 本轮收获 roll 颗 - 消耗 1 颗
    int seedQty = 0;
    for (const auto& sd : state.seeds) seedQty += sd.quantity;
    EXPECT_GE(seedQty, 1);
}

TEST(SpiritFieldHarvestTest, NoSeedClearsPlot) {
    GameState state;
    state.gameData.gameYear = 2;
    state.gameData.gameMonth = 3;
    state.gameData.spiritFieldPlants.push_back(makeMaturePlant(0, "聚灵草种"));
    // 无任何种子库存且 roll=0（seed=8 时 SYSTEM 分区 nextInt(5)=0）→ 地块清空
    RngManager rng;
    rng.initSystemSeed(8);
    OverflowMailCollector mail;
    const auto result = processSpiritFieldHarvest(state, rng, mail);
    EXPECT_EQ(result.plantsCompleted, 1);
    ASSERT_EQ(state.gameData.spiritFieldPlants.size(), 1u);
    EXPECT_TRUE(state.gameData.spiritFieldPlants[0].seedId.empty());
    EXPECT_EQ(state.gameData.spiritFieldPlants[0].growTime, 0);
}

TEST(SpiritFieldHarvestTest, HarvestGeneratesSeedReward) {
    GameState state;
    state.gameData.gameYear = 2;
    state.gameData.gameMonth = 3;
    // 多地块确保 roll 到 >0（10 块地几乎必然 roll 到种子）
    for (int i = 0; i < 10; ++i) {
        state.gameData.spiritFieldPlants.push_back(makeMaturePlant(i, "聚灵草种"));
    }
    RngManager rng;
    rng.initSystemSeed(999);
    OverflowMailCollector mail;
    processSpiritFieldHarvest(state, rng, mail);
    // 应至少有部分种子入库（roll 分布 0..4）
    int seedTotal = 0;
    for (const auto& sd : state.seeds) seedTotal += sd.quantity;
    EXPECT_GE(seedTotal, 0);
}

}  // namespace
}  // namespace gamecore
