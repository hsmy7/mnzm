#include <gtest/gtest.h>

#include "gamecore/data/equipment_db.h"

namespace gamecore::data {
namespace {

// ============================================================
// 装备静态表守卫测试
//
// 守护目标：C++ 装备表（equipment_db.h，由 scripts/gen-templates.mjs 生成）
// 与提取快照（test/data/equipment_db_sample.json）一致——防手改漂移。
// 抽样断言代表性条目；数量断言全覆盖。
// Kotlin 侧守卫见 TemplateRegistryGuardTest（快照 ↔ Kotlin Registry）。
// ============================================================

TEST(EquipmentDbTest, TemplateCount) {
    // Kotlin EquipmentDatabase：weapons 24 + armors 24 + boots 12 + accessories 12 = 72
    EXPECT_EQ(72u, equipmentTemplates().size());
}

TEST(EquipmentDbTest, WeaponSample) {
    const auto& tpls = equipmentTemplates();
    const auto* ironSword = [&]() -> const EquipmentTemplate* {
        for (const auto& t : tpls)
            if (t.id == "ironSword") return &t;
        return nullptr;
    }();
    ASSERT_NE(nullptr, ironSword);
    EXPECT_EQ("精铁剑", ironSword->name);
    EXPECT_EQ("WEAPON", ironSword->slot);
    EXPECT_EQ(1, ironSword->rarity);
    EXPECT_EQ(15, ironSword->physicalAttack);
    EXPECT_EQ(0, ironSword->magicAttack);
    EXPECT_DOUBLE_EQ(0.03, ironSword->critChance);
    EXPECT_EQ(4000, ironSword->price);
}

TEST(EquipmentDbTest, HighRaritySample) {
    const auto& tpls = equipmentTemplates();
    const auto* godSlayer = [&]() -> const EquipmentTemplate* {
        for (const auto& t : tpls)
            if (t.id == "godSlayer") return &t;
        return nullptr;
    }();
    ASSERT_NE(nullptr, godSlayer);
    EXPECT_EQ("青莲剑", godSlayer->name);
    EXPECT_EQ(5, godSlayer->rarity);
    EXPECT_EQ(1920, godSlayer->physicalAttack);
    // Kotlin 字面量 0.22499999999999998（IEEE double 同一值）
    EXPECT_DOUBLE_EQ(0.22499999999999998, godSlayer->critChance);
    EXPECT_EQ(3360000, godSlayer->price);
}

TEST(EquipmentDbTest, ArmorBootsAccessorySample) {
    const auto& tpls = equipmentTemplates();
    int weapons = 0, armors = 0, boots = 0, accessories = 0;
    for (const auto& t : tpls) {
        if (t.slot == "WEAPON") ++weapons;
        if (t.slot == "ARMOR") ++armors;
        if (t.slot == "BOOTS") ++boots;
        if (t.slot == "ACCESSORY") ++accessories;
    }
    EXPECT_EQ(24, weapons);
    EXPECT_EQ(24, armors);
    EXPECT_EQ(12, boots);
    EXPECT_EQ(12, accessories);

    const auto* immortalBoots = [&]() -> const EquipmentTemplate* {
        for (const auto& t : tpls)
            if (t.id == "immortalBoots") return &t;
        return nullptr;
    }();
    ASSERT_NE(nullptr, immortalBoots);
    EXPECT_EQ("BOOTS", immortalBoots->slot);
    EXPECT_EQ(3072, immortalBoots->speed);
    EXPECT_EQ(39300, immortalBoots->hp);
}

TEST(EquipmentDbTest, IdsUnique) {
    // 去重守卫：id 唯一（Kotlin Map 键语义）
    const auto& tpls = equipmentTemplates();
    std::set<std::string> ids;
    for (const auto& t : tpls) {
        EXPECT_TRUE(ids.insert(t.id).second) << "重复 id: " << t.id;
    }
}

}  // namespace
}  // namespace gamecore::data
