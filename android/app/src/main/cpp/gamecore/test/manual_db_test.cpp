#include <gtest/gtest.h>

#include <set>

#include "gamecore/data/manual_db.h"

namespace gamecore::data {
namespace {

TEST(ManualDbTest, CountMatchesKotlinSource) {
    // Kotlin ManualDatabase 数据源 manuals.json：108 + 162 + 234 + 36 = 540
    EXPECT_EQ(manualTemplates().size(), 540u);
}

TEST(ManualDbTest, RepresentativeEntries) {
    const auto* attack = manualById("common_phys_single_1");
    ASSERT_NE(attack, nullptr);
    EXPECT_EQ(attack->name, "青冥剑诀");
    EXPECT_EQ(attack->type, "ATTACK");
    EXPECT_EQ(attack->rarity, 1);
    EXPECT_EQ(attack->skillName, "青冥斩");
    EXPECT_EQ(attack->skillDamageMultiplier, 2.0);
    EXPECT_EQ(attack->skillCooldown, 3);
    EXPECT_EQ(attack->skillMpCost, 10);
    EXPECT_FALSE(attack->skillIsAoe);
    EXPECT_EQ(attack->minRealm, 9);
    // stats map
    EXPECT_EQ(attack->stats.at("physicalAttack"), 9);

    const auto* heal = manualById("new_hp_pct_self_1");
    ASSERT_NE(heal, nullptr);
    EXPECT_EQ(heal->name, "回春术");
    EXPECT_EQ(heal->type, "SUPPORT");
    EXPECT_DOUBLE_EQ(heal->skillHealPercent, 0.3);
    EXPECT_EQ(heal->skillHealType, "hp");
    EXPECT_EQ(heal->skillTargetScope, "self");
}

TEST(ManualDbTest, BuffListRoundTrip) {
    const auto* weaken = manualById("new_atkred_enemy_1");
    ASSERT_NE(weaken, nullptr);
    EXPECT_EQ(weaken->name, "弱化术");
    ASSERT_EQ(weaken->skillBuffs.size(), 2u);
    EXPECT_EQ(weaken->skillBuffs[0].type, "physical_attack_reduce");
    EXPECT_DOUBLE_EQ(weaken->skillBuffs[0].value, 0.2);
    EXPECT_EQ(weaken->skillBuffs[0].duration, 3);
    EXPECT_EQ(weaken->skillBuffs[1].type, "magic_attack_reduce");
    EXPECT_EQ(weaken->skillTargetScope, "enemy");
}

TEST(ManualDbTest, NoDuplicateIds) {
    std::set<std::string> seen;
    for (const auto& m : manualTemplates()) {
        EXPECT_TRUE(seen.insert(m.id).second) << "duplicate id " << m.id;
    }
}

TEST(ManualDbTest, TypeDistribution) {
    // 与 manuals.json 四类数量一致
    size_t attack = 0, defense = 0, support = 0, mind = 0;
    for (const auto& m : manualTemplates()) {
        if (m.type == "ATTACK") attack++;
        else if (m.type == "DEFENSE") defense++;
        else if (m.type == "SUPPORT") support++;
        else if (m.type == "MIND") mind++;
    }
    EXPECT_EQ(attack, 108u);
    EXPECT_EQ(defense, 162u);
    EXPECT_EQ(support, 234u);
    EXPECT_EQ(mind, 36u);
}

TEST(ManualDbTest, LookupHelpers) {
    EXPECT_EQ(manualById("missing"), nullptr);
    const auto* m = manualById("common_phys_single_1");
    ASSERT_NE(m, nullptr);
    EXPECT_EQ(m->skillDamageType, "physical");
}

}  // namespace
}  // namespace gamecore::data
