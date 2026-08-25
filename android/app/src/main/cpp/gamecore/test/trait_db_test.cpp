#include <gtest/gtest.h>

#include <optional>
#include <set>
#include <string>

#include "gamecore/data/trait_db.h"

namespace gamecore::data {
namespace {

// ============================================================
// 天赋/体质/词条静态表守卫测试（批次 2 剩余子步）
//
// 守护目标：C++ 表（trait_db.h，C++ 等价生成逻辑复刻 Kotlin 程序化生成）
// 与 Kotlin TalentDatabase/PhysiqueDatabase/AffixDatabase 的生成结果一致。
// 数量断言对照 Kotlin 源码计数；代表性条目断言名称/稀有度/数值/描述。
// Kotlin 侧守卫见 TemplateRegistryGuardTest 模式（快照 ↔ Kotlin Registry）。
// ============================================================

// 从 effects map 中取数值（不存在返回 nullptr，供 EXPECT 直接判断）
inline const double* effectOf(const std::map<std::string, double>& effects,
                              const std::string& key) {
    const auto it = effects.find(key);
    return it == effects.end() ? nullptr : &it->second;
}

TEST(TraitDbTest, TalentCount) {
    // Kotlin TalentDatabase：正面 104 + 负面 5 = 109
    const auto& tpls = talentTemplates();
    EXPECT_EQ(109u, tpls.size());

    int positive = 0, negative = 0;
    for (const auto& t : tpls) {
        if (t.isNegative) ++negative; else ++positive;
    }
    EXPECT_EQ(104, positive);
    EXPECT_EQ(5, negative);
}

TEST(TraitDbTest, TalentSample) {
    // 新天赋：物攻（1 阶）——name/rarity/effect/描述/模板/类型
    const auto t1 = talentById("r1_bat_phy_atk");
    ASSERT_TRUE(t1.has_value());
    EXPECT_EQ("勇武", t1->name);
    EXPECT_EQ(1, t1->rarity);
    EXPECT_EQ("物攻+6%", t1->description);
    EXPECT_FALSE(t1->isNegative);
    EXPECT_EQ("BAT_PHY_ATK", t1->type);
    EXPECT_EQ("bat_phy_atk", t1->tmpl);
    const double* atk = effectOf(t1->effects, "physicalAttack");
    ASSERT_NE(nullptr, atk);
    EXPECT_DOUBLE_EQ(0.06, *atk);

    // 旧天赋：修炼速度（6 阶 → talentGrade 映射为 3 品）——描述 % 精确
    const auto t2 = talentById("r6_cult_speed");
    ASSERT_TRUE(t2.has_value());
    EXPECT_EQ("灵脉流转", t2->name);
    EXPECT_EQ(3, t2->rarity);
    EXPECT_EQ("修炼速度+32%", t2->description);
    EXPECT_EQ("CULT_SPEED", t2->type);
    const double* cult = effectOf(t2->effects, "cultivationSpeed");
    ASSERT_NE(nullptr, cult);
    EXPECT_DOUBLE_EQ(0.32, *cult);

    // 旧天赋：突破概率（保留一位小数）——格式陷阱验证
    const auto t3 = talentById("r2_break_chance");
    ASSERT_TRUE(t3.has_value());
    EXPECT_EQ("突破概率+1.5%", t3->description);
    const double* bc = effectOf(t3->effects, "breakthroughChance");
    ASSERT_NE(nullptr, bc);
    EXPECT_DOUBLE_EQ(0.015, *bc);

    // 负面天赋——多效果 map + isNegative
    const auto t4 = talentById("neg_base_comprehension");
    ASSERT_TRUE(t4.has_value());
    EXPECT_EQ("神识迟钝", t4->name);
    EXPECT_EQ(0, t4->rarity);
    EXPECT_TRUE(t4->isNegative);
    EXPECT_EQ("悟性/智力/传道 -8", t4->description);
    const double* comp = effectOf(t4->effects, "comprehensionFlat");
    ASSERT_NE(nullptr, comp);
    EXPECT_DOUBLE_EQ(-8.0, *comp);
    const double* intel = effectOf(t4->effects, "intelligenceFlat");
    ASSERT_NE(nullptr, intel);
    EXPECT_DOUBLE_EQ(-8.0, *intel);
}

TEST(TraitDbTest, TalentPositionSample) {
    // 职务天赋：effects 为空，positionBonus 承载 slotType/数值
    const auto t = talentById("r3_pos_vice_sect_master");
    ASSERT_TRUE(t.has_value());
    EXPECT_EQ("辅政之才", t->name);
    EXPECT_EQ(3, t->rarity);
    EXPECT_EQ("政策效果加成+22%", t->description);
    EXPECT_EQ("POSITION_VICE_SECT_MASTER", t->type);
    EXPECT_EQ("pos_vice_sect_master", t->tmpl);
    EXPECT_TRUE(t->effects.empty());
    ASSERT_TRUE(t->positionBonus.has_value());
    EXPECT_EQ("VICE_SECT_MASTER", t->positionBonus->slotType);
    EXPECT_DOUBLE_EQ(0.22, t->positionBonus->effectBonus);
}

TEST(TraitDbTest, PhysiqueCount) {
    // Kotlin PhysiqueDatabase：正面 21 + 负面 3 = 24
    const auto& tpls = physiqueTemplates();
    EXPECT_EQ(24u, tpls.size());

    int positive = 0, negative = 0;
    for (const auto& p : tpls) {
        if (p.isNegative) ++negative; else ++positive;
    }
    EXPECT_EQ(21, positive);
    EXPECT_EQ(3, negative);
}

TEST(TraitDbTest, PhysiqueSample) {
    // 修炼速度体质（1 阶）
    const auto p1 = physiqueById("r1_phys_cult_speed");
    ASSERT_TRUE(p1.has_value());
    EXPECT_EQ("灵脉天成", p1->name);
    EXPECT_EQ(1, p1->rarity);
    EXPECT_EQ("修炼速度+8%", p1->description);
    EXPECT_DOUBLE_EQ(0.08, p1->cultivationSpeedBonus);
    EXPECT_EQ("CULT_SPEED", p1->type);

    // 混合进攻体质（3 阶）——双加成 + 全角逗号描述
    const auto p2 = physiqueById("r3_phys_hybrid_off");
    ASSERT_TRUE(p2.has_value());
    EXPECT_EQ("战魔之体", p2->name);
    EXPECT_EQ(3, p2->rarity);
    EXPECT_EQ("伤害加成+12%，暴击伤害+24%", p2->description);
    EXPECT_DOUBLE_EQ(0.12, p2->damageAmplification);
    EXPECT_DOUBLE_EQ(0.24, p2->critDamageBonus);

    // 负面体质（rarity=0）
    const auto p3 = physiqueById("neg_phys_offense");
    ASSERT_TRUE(p3.has_value());
    EXPECT_EQ(0, p3->rarity);
    EXPECT_TRUE(p3->isNegative);
    EXPECT_EQ("伤害加成-12%，暴击伤害-20%", p3->description);
    EXPECT_DOUBLE_EQ(-0.12, p3->damageAmplification);
    EXPECT_DOUBLE_EQ(-0.20, p3->critDamageBonus);
}

TEST(TraitDbTest, AffixCount) {
    // Kotlin AffixDatabase：正面 68 + 负面 3 = 71
    const auto& tpls = affixTemplates();
    EXPECT_EQ(71u, tpls.size());

    int positive = 0, negative = 0;
    for (const auto& a : tpls) {
        if (a.isNegative) ++negative; else ++positive;
    }
    EXPECT_EQ(68, positive);
    EXPECT_EQ(3, negative);
}

TEST(TraitDbTest, AffixSample) {
    // 战斗属性百分比词条：物攻/法攻共用一点
    const auto a1 = affixById("r1_aff_bat_atk");
    ASSERT_TRUE(a1.has_value());
    EXPECT_EQ("锐利", a1->name);
    EXPECT_EQ(1, a1->rarity);
    EXPECT_EQ("物攻+4%", a1->description);
    EXPECT_EQ("BAT_PCT", a1->type);
    const double* pAtk = effectOf(a1->effects, "physicalAttack");
    const double* mAtk = effectOf(a1->effects, "magicAttack");
    ASSERT_NE(nullptr, pAtk);
    ASSERT_NE(nullptr, mAtk);
    EXPECT_DOUBLE_EQ(0.04, *pAtk);
    EXPECT_DOUBLE_EQ(0.04, *mAtk);

    // 功法槽词条（固定 3 阶）
    const auto a2 = affixById("r3_aff_manual_slot");
    ASSERT_TRUE(a2.has_value());
    EXPECT_EQ("道藏", a2->name);
    EXPECT_EQ(3, a2->rarity);
    EXPECT_EQ("功法槽位+1", a2->description);
    const double* slot = effectOf(a2->effects, "manualSlot");
    ASSERT_NE(nullptr, slot);
    EXPECT_DOUBLE_EQ(1.0, *slot);

    // 职务词条（3 阶）——name 追加 "之印"
    const auto a3 = affixById("r3_aff_pos_herb_garden");
    ASSERT_TRUE(a3.has_value());
    EXPECT_EQ("灵田之印", a3->name);
    EXPECT_EQ("灵药成熟速度加成+20%", a3->description);
    EXPECT_EQ("POSITION", a3->type);
    ASSERT_TRUE(a3->positionBonus.has_value());
    EXPECT_EQ("HERB_GARDEN", a3->positionBonus->slotType);
    EXPECT_DOUBLE_EQ(0.20, a3->positionBonus->effectBonus);

    // 负面词条
    const auto a4 = affixById("neg_aff_battle");
    ASSERT_TRUE(a4.has_value());
    EXPECT_EQ(0, a4->rarity);
    EXPECT_TRUE(a4->isNegative);
    EXPECT_EQ("物攻/法攻/气血 -8%", a4->description);
    const double* hp = effectOf(a4->effects, "maxHp");
    ASSERT_NE(nullptr, hp);
    EXPECT_DOUBLE_EQ(-0.08, *hp);
}

TEST(TraitDbTest, IdsUnique) {
    // 去重守卫：id 唯一（Kotlin Map 键语义）
    std::set<std::string> ids;
    for (const auto& t : talentTemplates()) {
        EXPECT_TRUE(ids.insert(t.id).second) << "重复天赋 id: " << t.id;
    }
    for (const auto& p : physiqueTemplates()) {
        EXPECT_TRUE(ids.insert(p.id).second) << "重复体质 id: " << p.id;
    }
    for (const auto& a : affixTemplates()) {
        EXPECT_TRUE(ids.insert(a.id).second) << "重复词条 id: " << a.id;
    }
}

TEST(TraitDbTest, LookupHelpers) {
    // 存在查询
    EXPECT_TRUE(talentById("r3_bat_crit").has_value());
    EXPECT_TRUE(physiqueById("r2_phys_dmg_reduce").has_value());
    EXPECT_TRUE(affixById("r3_aff_win_growth").has_value());

    // 不存在查询返回空 optional
    EXPECT_FALSE(talentById("does_not_exist").has_value());
    EXPECT_FALSE(physiqueById("does_not_exist").has_value());
    EXPECT_FALSE(affixById("does_not_exist").has_value());

    // byId 查询结果与遍历表一致
    const auto& t = talentTemplates();
    const auto found = talentById("r2_base_int");
    ASSERT_TRUE(found.has_value());
    bool seen = false;
    for (const auto& e : t) {
        if (e.id == "r2_base_int") {
            seen = true;
            EXPECT_EQ(e.name, found->name);
            EXPECT_EQ(e.rarity, found->rarity);
            break;
        }
    }
    EXPECT_TRUE(seen);
}

}  // namespace
}  // namespace gamecore::data
