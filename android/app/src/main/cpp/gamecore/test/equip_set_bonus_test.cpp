// ============================================================
// equip_set_bonus_test — 套装效果档位守卫（B3 新增，方案 §6.1；
// 语义权威 = Kotlin EquipStatResolver.resolveSetBonus 逐位移植：
// 2/4/6 件档位达档即生效、可越级不叠加（穿满 6 件三档同时生效）、
// 同类百分比相加 0.2-7）
// ============================================================

#include "gtest/gtest.h"

#include <string>
#include <vector>

#include "gamecore/data/equip_set_db.h"
#include "gamecore/state/models.h"
#include "gamecore/system/disciple_stats.h"

namespace gamecore {
namespace {

namespace stats = gamecore::stats;

using gamecore::data::equipmentSetDefs;
using gamecore::state::EquipmentInstance;

EquipmentInstance piece(const std::string& setId, const std::string& part,
                        int32_t rarity = 1) {
    EquipmentInstance e;
    e.id = setId + "_" + part;
    e.name = "测试" + setId;
    e.setId = setId;
    e.part = part;
    e.meta.rarity = rarity;
    // 词条面留空（套装档位只按 setId 计件；主词条零值便于隔离断言）
    return e;
}

/// 六槽位指针集（空槽 = nullptr；disciple_stats 消费面）
std::vector<const EquipmentInstance*> slotPtrs(
        const std::vector<EquipmentInstance>& all) {
    std::vector<const EquipmentInstance*> out;
    for (const auto& e : all) out.push_back(&e);
    return out;
}

// ── 静态定义守卫（对齐 Kotlin EquipmentSetDatabase）──────────────

TEST(EquipSetDatabaseTest, TwoSetsWithSchoolAndTiers) {
    const auto& sets = equipmentSetDefs();
    ASSERT_EQ(2u, sets.size());
    // lietian 物理套：2 件 PHYSICAL_DAMAGE_PCT 0.10 / 4 件 CRIT_RATE 0.12 /
    // 6 件 PHYSICAL_DAMAGE_PCT 0.20
    EXPECT_EQ("lietian", sets[0].id);
    EXPECT_EQ("PHYSICAL", sets[0].school);
    ASSERT_EQ(1u, sets[0].bonus2.size());
    EXPECT_EQ("PHYSICAL_DAMAGE_PCT", sets[0].bonus2[0].stat);
    EXPECT_DOUBLE_EQ(0.10, sets[0].bonus2[0].value);
    ASSERT_EQ(1u, sets[0].bonus4.size());
    EXPECT_EQ("CRIT_RATE", sets[0].bonus4[0].stat);
    EXPECT_DOUBLE_EQ(0.12, sets[0].bonus4[0].value);
    ASSERT_EQ(1u, sets[0].bonus6.size());
    EXPECT_EQ("PHYSICAL_DAMAGE_PCT", sets[0].bonus6[0].stat);
    EXPECT_DOUBLE_EQ(0.20, sets[0].bonus6[0].value);
    // zifu 法术套：2 件 MAGIC_DAMAGE_PCT 0.10 / 4 件 CRIT_DAMAGE 0.25 /
    // 6 件 MAGIC_DAMAGE_PCT 0.20
    EXPECT_EQ("zifu", sets[1].id);
    EXPECT_EQ("MAGIC", sets[1].school);
    ASSERT_EQ(1u, sets[1].bonus2.size());
    EXPECT_EQ("MAGIC_DAMAGE_PCT", sets[1].bonus2[0].stat);
    EXPECT_DOUBLE_EQ(0.10, sets[1].bonus2[0].value);
    ASSERT_EQ(1u, sets[1].bonus4.size());
    EXPECT_EQ("CRIT_DAMAGE", sets[1].bonus4[0].stat);
    EXPECT_DOUBLE_EQ(0.25, sets[1].bonus4[0].value);
    ASSERT_EQ(1u, sets[1].bonus6.size());
    EXPECT_EQ("MAGIC_DAMAGE_PCT", sets[1].bonus6[0].stat);
    EXPECT_DOUBLE_EQ(0.20, sets[1].bonus6[0].value);
}

// ── 档位生效面（0..6 件全档扫描）────────────────────────────────

TEST(EquipSetBonusTest, LietianTiersByPieceCount0To6) {
    // 0/1 件无效果；2 件 bonus2；3 件仍 bonus2；4 件 bonus2+bonus4；
    // 5 件同 4；6 件三档同时生效（可越级不叠加口径：各档各算一次）
    for (int32_t count = 0; count <= 6; ++count) {
        std::vector<EquipmentInstance> worn;
        for (int32_t i = 0; i < count; ++i) {
            worn.push_back(piece("lietian", "WEAPON"));
        }
        const stats::EquipBonus bonus = stats::resolveSetBonus(slotPtrs(worn));
        const double type2 = count >= 2 ? 0.10 : 0.0;
        const double type4 = count >= 4 ? 0.12 : 0.0;
        const double type6 = count >= 6 ? 0.20 : 0.0;
        EXPECT_DOUBLE_EQ(type2 + type6, bonus.physicalDamageBonus)
            << "lietian count=" << count;
        EXPECT_DOUBLE_EQ(type4, bonus.critRate) << "lietian count=" << count;
        EXPECT_DOUBLE_EQ(0.0, bonus.magicDamageBonus) << "lietian count=" << count;
    }
}

TEST(EquipSetBonusTest, ZifuTiersByPieceCount0To6) {
    for (int32_t count = 0; count <= 6; ++count) {
        std::vector<EquipmentInstance> worn;
        for (int32_t i = 0; i < count; ++i) {
            worn.push_back(piece("zifu", "HEAD"));
        }
        const stats::EquipBonus bonus = stats::resolveSetBonus(slotPtrs(worn));
        const double type2 = count >= 2 ? 0.10 : 0.0;
        const double type4 = count >= 4 ? 0.25 : 0.0;
        const double type6 = count >= 6 ? 0.20 : 0.0;
        EXPECT_DOUBLE_EQ(type2 + type6, bonus.magicDamageBonus)
            << "zifu count=" << count;
        EXPECT_DOUBLE_EQ(type4, bonus.critDamage) << "zifu count=" << count;
        EXPECT_DOUBLE_EQ(0.0, bonus.physicalDamageBonus) << "zifu count=" << count;
    }
}

TEST(EquipSetBonusTest, MixedSetsCountIndependently) {
    // 双套混穿 3+3：各套只达 2 件档（3 < 4），互不串计
    std::vector<EquipmentInstance> worn;
    for (int i = 0; i < 3; ++i) worn.push_back(piece("lietian", "WEAPON"));
    for (int i = 0; i < 3; ++i) worn.push_back(piece("zifu", "HEAD"));
    const stats::EquipBonus bonus = stats::resolveSetBonus(slotPtrs(worn));
    EXPECT_DOUBLE_EQ(0.10, bonus.physicalDamageBonus);
    EXPECT_DOUBLE_EQ(0.10, bonus.magicDamageBonus);
    EXPECT_DOUBLE_EQ(0.0, bonus.critRate);
    EXPECT_DOUBLE_EQ(0.0, bonus.critDamage);
}

TEST(EquipSetBonusTest, FullRivenAndZifuStackSixEach) {
    // 6+6 混穿：两套各自三档齐发（穿满 6 件三档同时生效）
    std::vector<EquipmentInstance> worn;
    for (int i = 0; i < 6; ++i) worn.push_back(piece("lietian", "WEAPON"));
    for (int i = 0; i < 6; ++i) worn.push_back(piece("zifu", "HEAD"));
    const stats::EquipBonus bonus = stats::resolveSetBonus(slotPtrs(worn));
    EXPECT_DOUBLE_EQ(0.30, bonus.physicalDamageBonus);   // 0.10 + 0.20
    EXPECT_DOUBLE_EQ(0.12, bonus.critRate);
    EXPECT_DOUBLE_EQ(0.30, bonus.magicDamageBonus);
    EXPECT_DOUBLE_EQ(0.25, bonus.critDamage);
}

TEST(EquipSetBonusTest, EmptySetIdAndUnknownSetIgnored) {
    // setId 空（散件）与未知 setId 不产套装加成
    std::vector<EquipmentInstance> worn;
    for (int i = 0; i < 6; ++i) worn.push_back(piece("", "WEAPON"));
    for (int i = 0; i < 6; ++i) worn.push_back(piece("ghost-set", "HEAD"));
    const stats::EquipBonus bonus = stats::resolveSetBonus(slotPtrs(worn));
    EXPECT_DOUBLE_EQ(0.0, bonus.physicalDamageBonus);
    EXPECT_DOUBLE_EQ(0.0, bonus.magicDamageBonus);
}

TEST(EquipSetBonusTest, ResolveEquipBonusAppendsSetAfterInstanceBonus) {
    // resolveEquipBonus：实例词条 totalBonus + 套装档位（加法汇合）；
    // 主词条等级成长 mult = 1 + 0.10×(level-1)
    std::vector<EquipmentInstance> worn;
    auto inst = piece("lietian", "WEAPON", 1);
    inst.growth.level = 11;   // mult = 1 + 0.10×10 = 2.0
    inst.growth.affix.mainStat = gamecore::state::EquipStatValue{"ATTACK", 50.0};
    inst.growth.affix.subStats = {gamecore::state::EquipStatValue{"HP", 10.0}};
    inst.growth.affix.subRolls = {2};
    worn.push_back(inst);
    worn.push_back(piece("lietian", "HEAD"));
    const stats::EquipBonus bonus = stats::resolveEquipBonus(slotPtrs(worn));
    // 实例：ATTACK 50×2.0 = 100；HP 10×2 = 20
    EXPECT_DOUBLE_EQ(100.0, bonus.flatAttack);
    EXPECT_DOUBLE_EQ(20.0, bonus.flatHp);
    // 套装：2 件档 PHYSICAL_DAMAGE_PCT 0.10
    EXPECT_DOUBLE_EQ(0.10, bonus.physicalDamageBonus);
}

}  // namespace
}  // namespace gamecore
