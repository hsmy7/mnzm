// ============================================================
// equip_set_bonus_test — 套装效果档位守卫（四部位体系 6 套；
// 语义权威 = Kotlin EquipmentSetDef.activeBonuses 逐位移植：
// 2/4 件档位达档即生效、可越级不叠加（穿满 4 件两档同时生效）、
// 同类百分比相加 0.2-7；六套同构骨架 = 2 件本系 +10% / 4 件暴击率
// +12% + 本系 +20%；满套 = 本系 +30% 且暴击率 +12%）
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

TEST(EquipSetDatabaseTest, SixSetsWithSchoolAndTiers) {
    const auto& sets = equipmentSetDefs();
    ASSERT_EQ(6u, sets.size());
    // 六套同构骨架：id/流派/本系伤害词条 逐套断言
    const char* kIds[] = {"lietian", "gengjin", "qingmu", "xuanshui", "lihuo", "houtu"};
    const char* kSchools[] = {"PHYSICAL", "METAL", "WOOD", "WATER", "FIRE", "EARTH"};
    const char* kDmg[] = {"PHYSICAL_DAMAGE_PCT", "METAL_DAMAGE_PCT", "WOOD_DAMAGE_PCT",
                          "WATER_DAMAGE_PCT", "FIRE_DAMAGE_PCT", "EARTH_DAMAGE_PCT"};
    for (int i = 0; i < 6; ++i) {
        EXPECT_EQ(kIds[i], sets[i].id) << "set " << i;
        EXPECT_EQ(kSchools[i], sets[i].school) << "set " << i;
        ASSERT_EQ(1u, sets[i].bonus2.size());
        EXPECT_EQ(kDmg[i], sets[i].bonus2[0].stat);
        EXPECT_DOUBLE_EQ(0.10, sets[i].bonus2[0].value);
        ASSERT_EQ(1u, sets[i].bonus4.size());
        EXPECT_EQ("CRIT_RATE", sets[i].bonus4[0].stat);
        EXPECT_DOUBLE_EQ(0.12, sets[i].bonus4[0].value);
        ASSERT_EQ(1u, sets[i].bonusFull.size());
        EXPECT_EQ(kDmg[i], sets[i].bonusFull[0].stat);
        EXPECT_DOUBLE_EQ(0.20, sets[i].bonusFull[0].value);
    }
}

// ── 档位生效面（六套 × 0..6 件全档扫描，E7）─────────────────────

TEST(EquipSetBonusTest, AllSixSetsTiersByPieceCount0To6) {
    // 0/1 件无效果；2 件 bonus2；3 件仍 bonus2；4 件（满套）= bonus2+bonus4+bonusFull；
    // 5/6 件同 4（可越级不叠加口径：各档各算一次）
    const char* kIds[] = {"lietian", "gengjin", "qingmu", "xuanshui", "lihuo", "houtu"};
    const std::string kDmgFields[] = {"physicalDamageBonus", "metalDamageBonus",
                                      "woodDamageBonus", "waterDamageBonus",
                                      "fireDamageBonus", "earthDamageBonus"};
    for (int set = 0; set < 6; ++set) {
        for (int32_t count = 0; count <= 6; ++count) {
            std::vector<EquipmentInstance> worn;
            for (int32_t i = 0; i < count; ++i) {
                worn.push_back(piece(kIds[set], "HANDS"));
            }
            const stats::EquipBonus bonus = stats::resolveSetBonus(slotPtrs(worn));
            const double type2 = count >= 2 ? 0.10 : 0.0;
            const double crit = count >= 4 ? 0.12 : 0.0;
            const double typeFull = count >= 4 ? 0.20 : 0.0;
            const double expectedBonus = type2 + typeFull;
            // 逐套只核对"本系"桶（其它 5 系与物理桶必须为 0——E7 跨套不串扰）
            const double physical = set == 0 ? expectedBonus : 0.0;
            const double metal = set == 1 ? expectedBonus : 0.0;
            const double wood = set == 2 ? expectedBonus : 0.0;
            const double water = set == 3 ? expectedBonus : 0.0;
            const double fire = set == 4 ? expectedBonus : 0.0;
            const double earth = set == 5 ? expectedBonus : 0.0;
            EXPECT_DOUBLE_EQ(physical, bonus.physicalDamageBonus)
                << kIds[set] << " count=" << count;
            EXPECT_DOUBLE_EQ(metal, bonus.metalDamageBonus)
                << kIds[set] << " count=" << count;
            EXPECT_DOUBLE_EQ(wood, bonus.woodDamageBonus)
                << kIds[set] << " count=" << count;
            EXPECT_DOUBLE_EQ(water, bonus.waterDamageBonus)
                << kIds[set] << " count=" << count;
            EXPECT_DOUBLE_EQ(fire, bonus.fireDamageBonus)
                << kIds[set] << " count=" << count;
            EXPECT_DOUBLE_EQ(earth, bonus.earthDamageBonus)
                << kIds[set] << " count=" << count;
            EXPECT_DOUBLE_EQ(crit, bonus.critRate) << kIds[set] << " count=" << count;
            (void)kDmgFields;
        }
    }
}

TEST(EquipSetBonusTest, MixedSetsCountIndependently) {
    // 双套混穿 3+3：各套只达 2 件档（3 < 4），互不串计
    std::vector<EquipmentInstance> worn;
    for (int i = 0; i < 3; ++i) worn.push_back(piece("lietian", "HANDS"));
    for (int i = 0; i < 3; ++i) worn.push_back(piece("lihuo", "HEAD"));
    const stats::EquipBonus bonus = stats::resolveSetBonus(slotPtrs(worn));
    EXPECT_DOUBLE_EQ(0.10, bonus.physicalDamageBonus);
    EXPECT_DOUBLE_EQ(0.10, bonus.fireDamageBonus);
    EXPECT_DOUBLE_EQ(0.0, bonus.critRate);
    EXPECT_DOUBLE_EQ(0.0, bonus.woodDamageBonus);
}

TEST(EquipSetBonusTest, FullLietianAndLihuoStackSixEach) {
    // 6+6 混穿（件数超出满套 4）：两套各自两档齐发（满套档不随件数再变）
    std::vector<EquipmentInstance> worn;
    for (int i = 0; i < 6; ++i) worn.push_back(piece("lietian", "HANDS"));
    for (int i = 0; i < 6; ++i) worn.push_back(piece("lihuo", "HEAD"));
    const stats::EquipBonus bonus = stats::resolveSetBonus(slotPtrs(worn));
    EXPECT_DOUBLE_EQ(0.30, bonus.physicalDamageBonus);   // 0.10 + 0.20
    EXPECT_DOUBLE_EQ(0.30, bonus.fireDamageBonus);       // 0.10 + 0.20
    EXPECT_DOUBLE_EQ(0.24, bonus.critRate);              // 两套 4 件档各 0.12 相加
}

TEST(EquipSetBonusTest, EmptySetIdAndUnknownSetIgnored) {
    // setId 空（散件）与未知 setId 不产套装加成
    std::vector<EquipmentInstance> worn;
    for (int i = 0; i < 6; ++i) worn.push_back(piece("", "HANDS"));
    for (int i = 0; i < 6; ++i) worn.push_back(piece("ghost-set", "HEAD"));
    const stats::EquipBonus bonus = stats::resolveSetBonus(slotPtrs(worn));
    EXPECT_DOUBLE_EQ(0.0, bonus.physicalDamageBonus);
    EXPECT_DOUBLE_EQ(0.0, bonus.fireDamageBonus);
    EXPECT_DOUBLE_EQ(0.0, bonus.earthDamageBonus);
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
