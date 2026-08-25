#include <gtest/gtest.h>

#include <set>

#include "gamecore/data/beast_material_db.h"

namespace gamecore::data {
namespace {

TEST(BeastMaterialDbTest, CountMatchesKotlinSource) {
    // Kotlin BeastMaterialDatabase 实际 192 条（8 妖兽 × 4 材料 × 6 品阶）
    EXPECT_EQ(beastMaterialTemplates().size(), 192u);
}

TEST(BeastMaterialDbTest, RepresentativeEntries) {
    const auto* tigerHide = beastMaterialById("tigerHide0");
    ASSERT_NE(tigerHide, nullptr);
    EXPECT_EQ(tigerHide->name, "凡虎皮");
    EXPECT_EQ(tigerHide->tier, 1);
    EXPECT_EQ(tigerHide->rarity, 1);
    EXPECT_EQ(tigerHide->category, "hide");
    EXPECT_EQ(tigerHide->materialCategory, "BEAST_HIDE");
    EXPECT_DOUBLE_EQ(tigerHide->dropWeight, 1.0);

    const auto* turtleCore = beastMaterialById("turtleCore5");
    ASSERT_NE(turtleCore, nullptr);
    EXPECT_EQ(turtleCore->name, "天龟内丹");
    EXPECT_EQ(turtleCore->tier, 6);
    EXPECT_EQ(turtleCore->rarity, 6);
    EXPECT_EQ(turtleCore->materialCategory, "BEAST_CORE");
    EXPECT_DOUBLE_EQ(turtleCore->dropWeight, 0.06);
    // price 派生：rarity 6 → materialBasePrice 2688000
    EXPECT_EQ(turtleCore->price, 2688000);
}

TEST(BeastMaterialDbTest, DerivedPriceMatchesRarityTable) {
    // rarity → materialBasePrice（GameConfig.Rarity）
    const std::map<int32_t, int32_t> kExpected = {
        {1, 400}, {2, 1600}, {3, 8000}, {4, 48000}, {5, 336000}, {6, 2688000},
    };
    for (const auto& m : beastMaterialTemplates()) {
        const auto it = kExpected.find(m.rarity);
        ASSERT_NE(it, kExpected.end()) << "unexpected rarity " << m.rarity;
        EXPECT_EQ(m.price, it->second) << "price mismatch for " << m.id;
    }
}

TEST(BeastMaterialDbTest, NoDuplicateIds) {
    std::set<std::string> seen;
    for (const auto& m : beastMaterialTemplates()) {
        EXPECT_TRUE(seen.insert(m.id).second) << "duplicate id " << m.id;
    }
}

TEST(BeastMaterialDbTest, LookupHelpers) {
    // 按妖兽类型前缀查询（Kotlin getMaterialsByBeastType：tiger → 24 条）
    const auto tiger = beastMaterialsByBeastType("tiger");
    EXPECT_EQ(tiger.size(), 24u);
    // 每种材料品类 6 品阶
    std::set<std::string> categories;
    for (const auto* m : tiger) categories.insert(m->category);
    EXPECT_EQ(categories.size(), 4u);

    // 不存在的妖兽 → 空
    EXPECT_TRUE(beastMaterialsByBeastType("nonexistent").empty());
    EXPECT_EQ(beastMaterialById("missing"), nullptr);
}

}  // namespace
}  // namespace gamecore::data
