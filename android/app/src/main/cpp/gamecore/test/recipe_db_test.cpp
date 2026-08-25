#include <gtest/gtest.h>

#include <set>
#include <string>

#include "gamecore/data/recipe_db.h"

namespace gamecore::data {
namespace {

// ============================================================
// 锻造/炼丹配方静态表守卫测试（批次 2 剩余子步）
//
// 守护目标：C++ 表（recipe_db.h）与 Kotlin ForgeRecipeDatabase /
// PillRecipeDatabase 的生成结果一致（72 锻造 + 732 丹药配方）。
// 数量断言对照 Kotlin 源码计数；代表性条目断言名称/品阶/时长/成功率/
// 材料/描述/效果字段。Kotlin 侧守卫见 TemplateRegistryGuardTest 模式
// （快照 recipe_db_sample.json ↔ Kotlin Registry，快照由
// scripts/gen-recipe-db.mjs 生成）。
// ============================================================

TEST(RecipeDbTest, ForgeRecipeCount) {
    // Kotlin ForgeRecipeDatabase：6 tier × 12 = 72
    const auto& recipes = forgeRecipes();
    EXPECT_EQ(72u, recipes.size());

    // 每 tier 12 条
    for (int tier = 1; tier <= 6; ++tier) {
        int count = 0;
        for (const auto& r : recipes) {
            if (r.tier == tier) ++count;
        }
        EXPECT_EQ(12, count) << "tier " << tier;
    }

    // 装备槽位覆盖
    int weapons = 0, armors = 0, boots = 0, accessories = 0;
    for (const auto& r : recipes) {
        if (r.type == "WEAPON") ++weapons;
        if (r.type == "ARMOR") ++armors;
        if (r.type == "BOOTS") ++boots;
        if (r.type == "ACCESSORY") ++accessories;
    }
    EXPECT_EQ(24, weapons);
    EXPECT_EQ(24, armors);
    EXPECT_EQ(12, boots);
    EXPECT_EQ(12, accessories);
}

TEST(RecipeDbTest, PillRecipeCount) {
    // Kotlin PillRecipeDatabase：修炼 138 + 战斗 288 + 功能 306 = 732
    const auto& recipes = pillRecipes();
    EXPECT_EQ(732u, recipes.size());

    int cultivation = 0, battle = 0, functional = 0;
    for (const auto& r : recipes) {
        if (r.category == "CULTIVATION") ++cultivation;
        if (r.category == "BATTLE") ++battle;
        if (r.category == "FUNCTIONAL") ++functional;
    }
    EXPECT_EQ(138, cultivation);
    EXPECT_EQ(288, battle);
    EXPECT_EQ(306, functional);

    // 每 tier 配方数（突破丹无 tier4 目标）
    // tier1:120 tier2:126 tier3:123 tier4:117 tier5:120 tier6:126
    const int expectedPerTier[7] = {0, 120, 126, 123, 117, 120, 126};
    for (int tier = 1; tier <= 6; ++tier) {
        int count = 0;
        for (const auto& r : recipes) {
            if (r.tier == tier) ++count;
        }
        EXPECT_EQ(expectedPerTier[tier], count) << "tier " << tier;
    }
}

TEST(RecipeDbTest, ForgeRecipeSample) {
    // tier 1 首条
    const auto f1 = forgeRecipeById("ironSword");
    ASSERT_TRUE(f1.has_value());
    EXPECT_EQ("精铁剑", f1->name);
    EXPECT_EQ("WEAPON", f1->type);
    EXPECT_EQ(1, f1->tier);
    EXPECT_EQ(1, f1->rarity);
    EXPECT_EQ("普通铁匠打造的精铁剑", f1->description);
    EXPECT_EQ(3, f1->duration);
    EXPECT_DOUBLE_EQ(0.70, f1->successRate);
    ASSERT_EQ(2u, f1->materials.size());
    EXPECT_EQ(3, f1->materials.at("tigerBlood0"));
    EXPECT_EQ(2, f1->materials.at("tigerTooth0"));

    // tier 5（4 材料）
    const auto f2 = forgeRecipeById("godSlayer");
    ASSERT_TRUE(f2.has_value());
    EXPECT_EQ("青莲剑", f2->name);
    EXPECT_EQ(5, f2->tier);
    EXPECT_EQ(72, f2->duration);
    EXPECT_DOUBLE_EQ(0.30, f2->successRate);
    ASSERT_EQ(4u, f2->materials.size());
    EXPECT_EQ(5, f2->materials.at("tigerBlood4"));
    EXPECT_EQ(4, f2->materials.at("tigerTooth4"));
    EXPECT_EQ(3, f2->materials.at("eagleClaw4"));
    EXPECT_EQ(2, f2->materials.at("tigerCore4"));

    // tier 6 顶级
    const auto f3 = forgeRecipeById("immortalSword");
    ASSERT_TRUE(f3.has_value());
    EXPECT_EQ("诛仙剑", f3->name);
    EXPECT_EQ(6, f3->tier);
    EXPECT_EQ(6, f3->rarity);
    EXPECT_EQ("上古仙人遗留的仙器", f3->description);
    EXPECT_EQ(120, f3->duration);
    EXPECT_DOUBLE_EQ(0.25, f3->successRate);
    EXPECT_EQ(8, f3->materials.at("tigerBlood5"));
    EXPECT_EQ(3, f3->materials.at("dragonHorn5"));
}

TEST(RecipeDbTest, PillRecipeCultivationSample) {
    // 修炼速度丹（下品）：速度百分比按 PillGrade.multiplier 折算
    const auto p1 = pillRecipeById("cultivationSpeed_1_low");
    ASSERT_TRUE(p1.has_value());
    EXPECT_EQ("引灵丹", p1->name);
    EXPECT_EQ(1, p1->tier);
    EXPECT_EQ(1, p1->rarity);
    EXPECT_EQ("CULTIVATION", p1->category);
    EXPECT_EQ("low", p1->grade);
    EXPECT_EQ("cultivationSpeed", p1->pillType);
    EXPECT_EQ("凡品下品修炼速度丹，提升境界修炼速度15%，持续9旬", p1->description);
    EXPECT_EQ(3, p1->duration);
    EXPECT_DOUBLE_EQ(0.75, p1->successRate);
    EXPECT_DOUBLE_EQ(0.15, p1->cultivationSpeedPercent);
    ASSERT_EQ(2u, p1->materials.size());
    EXPECT_EQ(2, p1->materials.at("spiritGrass1"));
    EXPECT_EQ(2, p1->materials.at("spiritFlower1"));

    // 中品：0.35 × 1.0 = 35%
    const auto p2 = pillRecipeById("cultivationSpeed_2_medium");
    ASSERT_TRUE(p2.has_value());
    EXPECT_EQ("聚灵丹", p2->name);
    EXPECT_EQ("灵品中品修炼速度丹，提升境界修炼速度35%，持续9旬", p2->description);
    EXPECT_DOUBLE_EQ(0.35, p2->cultivationSpeedPercent);
    EXPECT_EQ(6, p2->duration);
    EXPECT_DOUBLE_EQ(0.65, p2->successRate);

    // 加值丹：roundToInt 半进位验证（600×0.375=225 → 225×0.5=112.5 → 113）
    const auto p3 = pillRecipeById("cultivationAdd_1_low");
    ASSERT_TRUE(p3.has_value());
    EXPECT_EQ("增元丹", p3->name);
    EXPECT_EQ("凡品下品境界修为丹，立即增加113点境界修为", p3->description);
    EXPECT_EQ(113, p3->cultivationAdd);

    // 功法熟练丹
    const auto p4 = pillRecipeById("skillExpAdd_1_medium");
    ASSERT_TRUE(p4.has_value());
    EXPECT_EQ("悟道丹", p4->name);
    EXPECT_EQ("凡品中品功法熟练丹，立即增加60点功法熟练度", p4->description);
    EXPECT_EQ(60, p4->skillExpAdd);
    EXPECT_EQ(2, p4->materials.at("spiritFlower3"));
    EXPECT_EQ(2, p4->materials.at("spiritFruit3"));
}

TEST(RecipeDbTest, PillRecipeBreakthroughSample) {
    // 聚气丹：显式材料 + 炼气期
    const auto b1 = pillRecipeById("breakthrough_9_low");
    ASSERT_TRUE(b1.has_value());
    EXPECT_EQ("聚气丹", b1->name);
    EXPECT_EQ(1, b1->tier);
    EXPECT_EQ("增加炼气期突破成功率5%", b1->description);
    EXPECT_DOUBLE_EQ(0.05, b1->breakthroughChance);
    EXPECT_EQ(9, b1->targetRealm);
    ASSERT_EQ(2u, b1->materials.size());
    EXPECT_EQ(2, b1->materials.at("spiritGrass2"));
    EXPECT_EQ(2, b1->materials.at("spiritFruit2"));

    // 结婴丹（tier2，idx2）：herbs[4] + herbs[7]，tier<3 不加第三味
    const auto b2 = pillRecipeById("breakthrough_6_medium");
    ASSERT_TRUE(b2.has_value());
    EXPECT_EQ("结婴丹", b2->name);
    EXPECT_EQ(2, b2->tier);
    EXPECT_EQ("增加元婴期突破成功率12%", b2->description);
    EXPECT_DOUBLE_EQ(0.12, b2->breakthroughChance);
    EXPECT_EQ(6, b2->targetRealm);
    ASSERT_EQ(2u, b2->materials.size());
    EXPECT_EQ(2, b2->materials.at("spiritFlower5"));
    EXPECT_EQ(2, b2->materials.at("spiritFruit5"));

    // 登仙丹（tier6，idx2，tier≥3 追加第三味 herbs[(4+5)%9=0]）
    const auto b3 = pillRecipeById("breakthrough_0_high");
    ASSERT_TRUE(b3.has_value());
    EXPECT_EQ("登仙丹", b3->name);
    EXPECT_EQ(6, b3->tier);
    EXPECT_EQ("增加仙人期突破成功率20%", b3->description);
    EXPECT_DOUBLE_EQ(0.20, b3->breakthroughChance);
    EXPECT_EQ(0, b3->targetRealm);
    EXPECT_EQ(120, b3->duration);
    EXPECT_DOUBLE_EQ(0.20, b3->successRate);
    ASSERT_EQ(3u, b3->materials.size());
    EXPECT_EQ(2, b3->materials.at("spiritFlower17"));
    EXPECT_EQ(2, b3->materials.at("spiritFruit17"));
    EXPECT_EQ(2, b3->materials.at("spiritGrass16"));
}

TEST(RecipeDbTest, PillRecipeBattleSample) {
    // 单属性：物攻中品（12×0.375=4.5→5；5×1.0=5）
    const auto b1 = pillRecipeById("physicalAttack_1_medium");
    ASSERT_TRUE(b1.has_value());
    EXPECT_EQ("虎力丹", b1->name);
    EXPECT_EQ("BATTLE", b1->category);
    EXPECT_EQ("凡品中品物攻丹，增加5点物攻，持续9旬", b1->description);
    EXPECT_EQ(5, b1->physicalAttackAdd);
    ASSERT_EQ(2u, b1->materials.size());
    EXPECT_EQ(2, b1->materials.at("spiritGrass1"));
    EXPECT_EQ(2, b1->materials.at("spiritFlower2"));

    // 下品半进位（5×0.5=2.5 → 3）
    const auto b2 = pillRecipeById("physicalAttack_1_low");
    ASSERT_TRUE(b2.has_value());
    EXPECT_EQ("凡品下品物攻丹，增加3点物攻，持续9旬", b2->description);
    EXPECT_EQ(3, b2->physicalAttackAdd);

    // 双属性：hpMp 上品（格式陷阱：描述用英文属性键）
    const auto b3 = pillRecipeById("hpMp_1_high");
    ASSERT_TRUE(b3.has_value());
    EXPECT_EQ("生灵丹", b3->name);
    EXPECT_EQ("凡品上品生命灵力丹，增加54点hp和28点mp，持续9旬", b3->description);
    EXPECT_EQ(54, b3->hpAdd);
    EXPECT_EQ(28, b3->mpAdd);

    // 双属性：物攻物防下品
    const auto b4 = pillRecipeById("physicalAttackDefense_1_low");
    ASSERT_TRUE(b4.has_value());
    EXPECT_EQ("战体丹", b4->name);
    EXPECT_EQ("凡品下品物攻物防丹，增加2点physicalAttack和1点physicalDefense，持续9旬",
              b4->description);
    EXPECT_EQ(2, b4->physicalAttackAdd);
    EXPECT_EQ(1, b4->physicalDefenseAdd);

    // 暴击率下品（0.03×0.5=0.015 → 1.5 → 2%）
    const auto b5 = pillRecipeById("critRate_1_low");
    ASSERT_TRUE(b5.has_value());
    EXPECT_EQ("破击丹", b5->name);
    EXPECT_EQ("凡品下品暴击率丹，增加2%暴击率，持续9旬", b5->description);
    EXPECT_DOUBLE_EQ(0.015, b5->critRateAdd);
    ASSERT_EQ(2u, b5->materials.size());
    EXPECT_EQ(2, b5->materials.at("spiritGrass1"));
    EXPECT_EQ(2, b5->materials.at("spiritFlower3"));
}

TEST(RecipeDbTest, PillRecipeFunctionalSample) {
    // 延寿丹上品（20×2.0=40）
    const auto f1 = pillRecipeById("extendLife_3_high");
    ASSERT_TRUE(f1.has_value());
    EXPECT_EQ("长生丹", f1->name);
    EXPECT_EQ(3, f1->tier);
    EXPECT_EQ("FUNCTIONAL", f1->category);
    EXPECT_EQ("宝品上品延寿丹，增加40年寿元", f1->description);
    EXPECT_EQ(40, f1->extendLife);
    EXPECT_EQ(12, f1->duration);
    EXPECT_DOUBLE_EQ(0.60, f1->successRate);
    ASSERT_EQ(2u, f1->materials.size());
    EXPECT_EQ(2, f1->materials.at("spiritGrass7"));
    EXPECT_EQ(2, f1->materials.at("spiritFruit7"));

    // 双基础属性：智悟上品（20×0.6=12 → 12×2.0=24；描述英文键）
    const auto f2 = pillRecipeById("intelligenceComprehension_6_high");
    ASSERT_TRUE(f2.has_value());
    EXPECT_EQ("天悟丹", f2->name);
    EXPECT_EQ("天品上品智悟丹，永久增加24点intelligence和24点comprehension", f2->description);
    EXPECT_EQ(24, f2->intelligenceAdd);
    EXPECT_EQ(24, f2->comprehensionAdd);

    // 单基础属性：智力下品（3×0.5=1.5 → 2）
    const auto f3 = pillRecipeById("intelligence_1_low");
    ASSERT_TRUE(f3.has_value());
    EXPECT_EQ("慧根丹", f3->name);
    EXPECT_EQ("凡品下品智力丹，永久增加2点智力", f3->description);
    EXPECT_EQ(2, f3->intelligenceAdd);
}

TEST(RecipeDbTest, IdsUnique) {
    // 去重守卫：id 唯一（Kotlin Map 键语义）
    std::set<std::string> ids;
    for (const auto& r : forgeRecipes()) {
        EXPECT_TRUE(ids.insert(r.id).second) << "重复锻造配方 id: " << r.id;
    }
    for (const auto& r : pillRecipes()) {
        EXPECT_TRUE(ids.insert(r.id).second) << "重复丹药配方 id: " << r.id;
    }
}

TEST(RecipeDbTest, LookupHelpers) {
    // 存在查询
    EXPECT_TRUE(forgeRecipeById("immortalBoots").has_value());
    EXPECT_TRUE(pillRecipeById("nurtureSpeed_6_high").has_value());
    EXPECT_TRUE(pillRecipeById("breakthrough_3_medium").has_value());

    // 不存在查询返回空 optional
    EXPECT_FALSE(forgeRecipeById("does_not_exist").has_value());
    EXPECT_FALSE(pillRecipeById("does_not_exist").has_value());

    // byId 查询结果与遍历表一致
    const auto found = pillRecipeById("cultivationAdd_1_low");
    ASSERT_TRUE(found.has_value());
    bool seen = false;
    for (const auto& r : pillRecipes()) {
        if (r.id == "cultivationAdd_1_low") {
            seen = true;
            EXPECT_EQ(r.name, found->name);
            EXPECT_EQ(r.tier, found->tier);
            EXPECT_EQ(r.materials, found->materials);
            break;
        }
    }
    EXPECT_TRUE(seen);
}

TEST(RecipeDbTest, TierNameHelper) {
    // 对应 Kotlin PillRecipeDatabase.getTierName
    EXPECT_EQ("凡品", pillTierName(1));
    EXPECT_EQ("灵品", pillTierName(2));
    EXPECT_EQ("宝品", pillTierName(3));
    EXPECT_EQ("玄品", pillTierName(4));
    EXPECT_EQ("地品", pillTierName(5));
    EXPECT_EQ("天品", pillTierName(6));
    EXPECT_EQ("未知", pillTierName(0));
    EXPECT_EQ("未知", pillTierName(99));
}

}  // namespace
}  // namespace gamecore::data
