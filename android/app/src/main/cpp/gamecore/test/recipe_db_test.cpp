#include <gtest/gtest.h>

#include <set>
#include <string>

#include "gamecore/data/recipe_db.h"

namespace gamecore::data {
namespace {

// ============================================================
// 锻造/炼丹配方静态表守卫测试
//
// 守护目标：C++ 表（recipe_db.h）与 Kotlin ForgeRecipeDatabase /
// PillRecipeDatabase 的生成结果一致（24 锻造 + 624 丹药配方；
// 660→624 = R11 孕养类加成丹药退役移除 36 条；锻造 72→24 =
// 四部位化 F3 收缩 6 套 × 4 部位）。
// 数量断言对照 Kotlin 源码计数；代表性条目断言 id/pieceId/setId/part/
// 名称/描述/六档材料。Kotlin 侧守卫见 RecipeRegistryGuardTest
// （快照 recipe_db_sample.json ↔ Kotlin Registry，快照由
// scripts/gen-recipe-db.mjs 生成）。
// ============================================================

TEST(RecipeDbTest, ForgeRecipeCount) {
    // Kotlin ForgeRecipeDatabase：6 套 × 4 部位 = 24（四部位化 F3）
    const auto& recipes = forgeRecipes();
    EXPECT_EQ(24u, recipes.size());

    // 每套恰 4 条（HEAD/BODY/HANDS/FEET 各一）
    const char* const kSetIds[6] = {"lietian", "gengjin", "qingmu",
                                    "xuanshui", "lihuo", "houtu"};
    const char* const kParts[4] = {"HEAD", "BODY", "HANDS", "FEET"};
    for (const char* setId : kSetIds) {
        int count = 0;
        for (const auto& r : recipes) {
            if (r.setId == setId) ++count;
        }
        EXPECT_EQ(4, count) << "setId " << setId;
    }
    // 每部位恰 6 条（6 套各一）
    for (const char* part : kParts) {
        int count = 0;
        for (const auto& r : recipes) {
            if (r.part == part) ++count;
        }
        EXPECT_EQ(6, count) << "part " << part;
    }

    // 退役部位（四部位化 F3：WEAPON/LEGS 编号退役禁复用；旧配方体系
    // WEAPON/ARMOR/BOOTS/ACCESSORY 形状条目零残留）
    for (const auto& r : recipes) {
        const bool partValid = r.part == "HEAD" || r.part == "BODY" ||
                               r.part == "HANDS" || r.part == "FEET";
        EXPECT_TRUE(partValid) << "非法部位: " << r.id << " part=" << r.part;
    }
    // id 规则 = "forge_{pieceId}"，setId = pieceId 首段
    for (const auto& r : recipes) {
        EXPECT_EQ("forge_" + r.pieceId, r.id) << r.id;
        EXPECT_EQ(r.setId, r.pieceId.substr(0, r.pieceId.find('_'))) << r.id;
        EXPECT_EQ(6u, r.tierMaterials.size()) << r.id << " 应有 6 档材料表";
    }
}

TEST(RecipeDbTest, ForgeRecipeSample) {
    // 首条（lietian 头冠；tier1 两味兽材）
    const auto f1 = forgeRecipeById("forge_lietian_HEAD");
    ASSERT_TRUE(f1.has_value());
    EXPECT_EQ("lietian_HEAD", f1->pieceId);
    EXPECT_EQ("lietian", f1->setId);
    EXPECT_EQ("HEAD", f1->part);
    EXPECT_EQ("裂天罡煞·头冠", f1->name);
    EXPECT_EQ("裂天罡煞套装头冠，罡煞之气护持识海", f1->description);
    ASSERT_EQ(6u, f1->tierMaterials.size());
    EXPECT_EQ(2u, forgeMaterialsFor(*f1, 1).size());
    EXPECT_EQ(3, forgeMaterialsFor(*f1, 1).at("bearHide0"));
    EXPECT_EQ(2, forgeMaterialsFor(*f1, 1).at("bearBone0"));

    // tier6 档（4 材料，含龙材）
    const auto& t6 = forgeMaterialsFor(*f1, 6);
    EXPECT_EQ(4u, t6.size());
    EXPECT_EQ(8, t6.at("bearHide5"));
    EXPECT_EQ(3, t6.at("dragonClaw5"));

    // 末条（houtu 云履；.setName 派生自部件模板）
    const auto f2 = forgeRecipeById("forge_houtu_FEET");
    ASSERT_TRUE(f2.has_value());
    EXPECT_EQ("houtu_FEET", f2->pieceId);
    EXPECT_EQ("FEET", f2->part);
    EXPECT_EQ("厚土镇岳·云履", f2->name);
    EXPECT_EQ(5, forgeMaterialsFor(*f2, 4).at("wolfHide3"));

    // 派生时长（kTierDuration 同源：1→3、6→120；越界回退 2）
    EXPECT_EQ(3, detail::forgeDurationByTier(1));
    EXPECT_EQ(120, detail::forgeDurationByTier(6));
    EXPECT_EQ(2, detail::forgeDurationByTier(0));
    EXPECT_EQ(2, detail::forgeDurationByTier(7));

    // 旧 72 条形状条目零残留（退役 id 一律失配）
    EXPECT_FALSE(forgeRecipeById("ironSword").has_value());
    EXPECT_FALSE(forgeRecipeById("immortalBoots").has_value());
}

TEST(RecipeDbTest, PillRecipeCount) {
    // Kotlin PillRecipeDatabase：修炼 102 + 战斗 288 + 功能 234 = 624
    //（R11 孕养丹退役：修炼类原 138 中 nurtureSpeed/nurtureAdd 两系 36 条已移除）
    const auto& recipes = pillRecipes();
    EXPECT_EQ(624u, recipes.size());

    int cultivation = 0, battle = 0, functional = 0;
    for (const auto& r : recipes) {
        if (r.category == "CULTIVATION") ++cultivation;
        if (r.category == "BATTLE") ++battle;
        if (r.category == "FUNCTIONAL") ++functional;
    }
    EXPECT_EQ(102, cultivation);
    EXPECT_EQ(288, battle);
    EXPECT_EQ(234, functional);

    // 每 tier 配方数（突破丹无 tier4 目标；R11 后每 tier 修炼类减 6）
    // tier1:102 tier2:108 tier3:105 tier4:99 tier5:102 tier6:108
    const int expectedPerTier[7] = {0, 102, 108, 105, 99, 102, 108};
    for (int tier = 1; tier <= 6; ++tier) {
        int count = 0;
        for (const auto& r : recipes) {
            if (r.tier == tier) ++count;
        }
        EXPECT_EQ(expectedPerTier[tier], count) << "tier " << tier;
    }
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
    EXPECT_EQ(5, b1->attackAdd);
    ASSERT_EQ(2u, b1->materials.size());
    EXPECT_EQ(2, b1->materials.at("spiritGrass1"));
    EXPECT_EQ(2, b1->materials.at("spiritFlower2"));

    // 下品半进位（5×0.5=2.5 → 3）
    const auto b2 = pillRecipeById("physicalAttack_1_low");
    ASSERT_TRUE(b2.has_value());
    EXPECT_EQ("凡品下品物攻丹，增加3点物攻，持续9旬", b2->description);
    EXPECT_EQ(3, b2->attackAdd);

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
    EXPECT_EQ(2, b4->attackAdd);
    EXPECT_EQ(1, b4->defenseAdd);

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
    EXPECT_TRUE(forgeRecipeById("forge_qingmu_HANDS").has_value());
    EXPECT_TRUE(pillRecipeById("breakthrough_3_medium").has_value());

    // R11 孕养丹退役：两类配方零产出（派发件验收判据 ④）
    EXPECT_FALSE(pillRecipeById("nurtureSpeed_6_high").has_value());
    EXPECT_FALSE(pillRecipeById("nurtureAdd_1_low").has_value());

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
