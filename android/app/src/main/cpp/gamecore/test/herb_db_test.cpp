#include <gtest/gtest.h>

#include <set>
#include <string>

#include "gamecore/data/herb_db.h"

namespace gamecore::data {
namespace {

// ============================================================
// 灵草/种子静态表守卫测试（计划 v2 阶段 3 补齐预存缺口）
//
// 守护目标：C++ 灵草/种子表（herb_db.h，由 scripts/gen-templates.mjs 从
// scripts/data/herb_db_sample.json 生成）抽样断言代表性条目；数量断言
// 全覆盖。Kotlin 侧守卫见 HerbRegistryGuardTest（快照 ↔ Kotlin Registry）。
//
// 背景：architecture.md T-CPP-2 原文称"双端守卫已覆盖 6 类"，实际灵草/种子
// 双端均无守卫——本测试补齐 C++ 侧（Kotlin 侧同步新增 HerbRegistryGuardTest）。
// ============================================================

TEST(HerbDbTest, HerbCount) {
    // Kotlin HerbDatabase：54 灵草（含各 tier 代表性品种）
    EXPECT_EQ(54u, herbTemplates().size());
}

TEST(HerbDbTest, SeedCount) {
    // Kotlin HerbDatabase：54 种子（每种灵草一种子）
    EXPECT_EQ(54u, seedTemplates().size());
}

TEST(HerbDbTest, Tier1HerbSample) {
    const auto& tpls = herbTemplates();
    const auto* spiritGrass1 = [&]() -> const HerbTemplate* {
        for (const auto& t : tpls)
            if (t.id == "spiritGrass1") return &t;
        return nullptr;
    }();
    ASSERT_NE(nullptr, spiritGrass1);
    EXPECT_EQ("聚灵草", spiritGrass1->name);
    EXPECT_EQ(1, spiritGrass1->tier);
    EXPECT_EQ(1, spiritGrass1->rarity);
}

TEST(HerbDbTest, HighTierHerbSample) {
    const auto& tpls = herbTemplates();
    const auto* spiritFlower18 = [&]() -> const HerbTemplate* {
        for (const auto& t : tpls)
            if (t.id == "spiritFlower18") return &t;
        return nullptr;
    }();
    ASSERT_NE(nullptr, spiritFlower18);
    EXPECT_EQ("造化神花", spiritFlower18->name);
    EXPECT_EQ(6, spiritFlower18->tier);
    EXPECT_EQ(6, spiritFlower18->rarity);
}

TEST(HerbDbTest, SeedSampleAndHerbMapping) {
    const auto& seeds = seedTemplates();
    const auto* spiritGrass1Seed = [&]() -> const SeedTemplate* {
        for (const auto& s : seeds)
            if (s.id == "spiritGrass1Seed") return &s;
        return nullptr;
    }();
    ASSERT_NE(nullptr, spiritGrass1Seed);
    EXPECT_EQ("聚灵草种", spiritGrass1Seed->name);
    EXPECT_EQ(1, spiritGrass1Seed->tier);
    // 种子 id → 灵草 id（去 "Seed" 后缀）
    EXPECT_EQ("spiritGrass1", herbIdFromSeedId("spiritGrass1Seed"));
    EXPECT_TRUE(herbIdFromSeedId("noSuffix").empty());
}

TEST(HerbDbTest, IdsUnique) {
    const auto& herbs = herbTemplates();
    const auto& seeds = seedTemplates();
    std::set<std::string> herbIds;
    for (const auto& h : herbs) EXPECT_TRUE(herbIds.insert(h.id).second);
    std::set<std::string> seedIds;
    for (const auto& s : seeds) EXPECT_TRUE(seedIds.insert(s.id).second);
}

}  // namespace
}  // namespace gamecore::data
