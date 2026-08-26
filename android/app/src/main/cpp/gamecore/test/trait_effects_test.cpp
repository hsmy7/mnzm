#include <gtest/gtest.h>

#include <map>
#include <string>
#include <vector>

#include "gamecore/data/trait_db.h"
#include "gamecore/state/models.h"
#include "gamecore/system/disciple_stats.h"

namespace gamecore::stats {
namespace {

// ============================================================
// T2.4a 填表单元测试：trait_db.h → 三聚合函数（talentEffectsFor /
// affixEffectsFor / physiqueCultivationBonusFor）+ comprehension
// 词条合并分叉修复回归。
//
// 全量遍历 204 条注册表条目验证聚合管道逐字段一致；数值锚点与
// Kotlin TalentDatabase/AffixDatabase/PhysiqueDatabase 配置梯度一致。
// ============================================================

using gamecore::data::affixTemplates;
using gamecore::data::physiqueTemplates;
using gamecore::data::talentTemplates;

// ── Kotlin 配置梯度锚点 ─────────────────────────────────────────────
constexpr double kMaxHpR1Talent = 0.10;          // r1_bat_hp 体健 气血+10%
constexpr double kMaxHpNegSurvival = -0.15;      // neg_battle_survival 气血
constexpr double kCultSpeedR6Talent = 0.32;      // r6_cult_speed 灵脉流转
constexpr double kMagicAtkAffR3 = 0.16;          // r3_aff_bat_atk 锐利 法攻
constexpr double kMagicAtkAffR1 = 0.04;          // r1_aff_bat_atk 锐利 1 阶
constexpr double kPhysCultSpeedR1 = 0.08;        // r1_phys_cult_speed
constexpr double kHybridOffR3Amp = 0.12;         // r3_phys_hybrid_off 伤害加成
constexpr double kHybridOffR3Crit = 0.24;        // r3_phys_hybrid_off 暴伤
constexpr double kCompFlatR1Talent = 4.0;        // r1_base_comp 悟性+4
constexpr double kCompFlatR2Talent = 10.0;       // r2_base_comp 悟性+10
constexpr double kCompFlatR3Talent = 18.0;       // r3_base_comp 悟性+18
constexpr double kCompFlatR1Aff = 3.0;           // r1_aff_base_comp 词条悟性+3
constexpr double kCompFlatR2Aff = 7.0;           // r2_aff_base_comp 词条悟性+7
constexpr double kCompFlatNegTalent = -8.0;      // neg_base_comprehension
constexpr double kCompFlatNegAff = -5.0;         // neg_aff_base 词条愚钝
constexpr double kLifespanR6 = 0.60;             // r6_lifespan 寿元绵长
constexpr double kLifespanAffR3 = 0.28;          // r3_aff_lifespan 延年
constexpr double kLifespanAffNeg = -0.15;        // neg_aff_lifespan 夭折
constexpr int32_t kDefaultComprehension = 50;    // Disciple 默认悟性
constexpr int32_t kRealm8Gain = 50;              // lifespanGainForRealm(8)
const char* const kUnknownId = "definitely_not_registered";

/// 单 id 聚合结果 == 该条目自身 effects map（全量遍历共用）
void expectEffectsEqual(const std::map<std::string, double>& tpl,
                        const std::map<std::string, double>& agg,
                        const std::string& entryId) {
    ASSERT_EQ(tpl.size(), agg.size()) << "entry: " << entryId;
    for (const auto& [key, value] : tpl) {
        const auto it = agg.find(key);
        ASSERT_TRUE(it != agg.end()) << "entry: " << entryId
                                     << " missing key: " << key;
        EXPECT_DOUBLE_EQ(value, it->second) << "entry: " << entryId
                                            << " key: " << key;
    }
}

// ── 天赋聚合 ────────────────────────────────────────────────────────

TEST(TraitEffectsTest, TalentAggregationCoversAllEntries) {
    for (const auto& t : talentTemplates()) {
        expectEffectsEqual(t.effects, talentEffectsFor({t.id}), t.id);
    }
}

TEST(TraitEffectsTest, TalentSpotValuesMatchKotlinGradients) {
    // 新天赋气血 1 阶 +10%
    const auto hp = talentEffectsFor({"r1_bat_hp"});
    EXPECT_DOUBLE_EQ(kMaxHpR1Talent, effectValue(hp, "maxHp"));
    // 旧天赋修炼速度 6 阶 +32%（DEPRECATED 类型仍参与旧存档解析）
    const auto cult = talentEffectsFor({"r6_cult_speed"});
    EXPECT_DOUBLE_EQ(kCultSpeedR6Talent, effectValue(cult, "cultivationSpeed"));
    // 职务天赋 effects 为空 → 聚合恒空
    EXPECT_TRUE(talentEffectsFor({"r3_pos_vice_sect_master"}).empty());
}

TEST(TraitEffectsTest, TalentUnknownIdSkippedAndEmptyInputEmptyOutput) {
    EXPECT_TRUE(talentEffectsFor({}).empty());
    EXPECT_TRUE(talentEffectsFor({kUnknownId}).empty());
    // 已知 + 未知混合：未知跳过，已知正常聚合
    const auto mixed = talentEffectsFor({"r1_bat_hp", kUnknownId});
    EXPECT_DOUBLE_EQ(kMaxHpR1Talent, effectValue(mixed, "maxHp"));
}

TEST(TraitEffectsTest, TalentDuplicateIdsAccumulate) {
    const auto doubled = talentEffectsFor({"r1_bat_hp", "r1_bat_hp"});
    EXPECT_DOUBLE_EQ(kMaxHpR1Talent * 2.0, effectValue(doubled, "maxHp"));
}

TEST(TraitEffectsTest, TalentSameKeyAccumulatesAcrossEntries) {
    // r1_bat_hp(maxHp+0.10) 与 neg_battle_survival(maxHp-0.15) 同 key 相加
    const auto merged =
        talentEffectsFor({"r1_bat_hp", "neg_battle_survival"});
    EXPECT_DOUBLE_EQ(kMaxHpR1Talent + kMaxHpNegSurvival,
                     effectValue(merged, "maxHp"));
    // 同条目的其余 key 不受影响
    constexpr double kMaxMpNegSurvival = -0.08;   // neg_battle_survival 法力
    EXPECT_DOUBLE_EQ(kMaxMpNegSurvival, effectValue(merged, "maxMp"));
}

// ── 词条聚合 ────────────────────────────────────────────────────────

TEST(TraitEffectsTest, AffixAggregationCoversAllEntries) {
    for (const auto& a : affixTemplates()) {
        expectEffectsEqual(a.effects, affixEffectsFor({a.id}), a.id);
    }
}

TEST(TraitEffectsTest, AffixSpotValuesAndBoundaries) {
    // 锐利词条双 key（物攻/法攻同梯度值）
    const auto atk = affixEffectsFor({"r3_aff_bat_atk"});
    EXPECT_DOUBLE_EQ(kMagicAtkAffR3, effectValue(atk, "physicalAttack"));
    EXPECT_DOUBLE_EQ(kMagicAtkAffR3, effectValue(atk, "magicAttack"));
    // 空输入 / 未知 id
    EXPECT_TRUE(affixEffectsFor({}).empty());
    EXPECT_TRUE(affixEffectsFor({kUnknownId}).empty());
    // 职务词条 effects 为空
    EXPECT_TRUE(affixEffectsFor({"r1_aff_pos_preaching"}).empty());
}

// ── 体质聚合 ────────────────────────────────────────────────────────

TEST(TraitEffectsTest, PhysiqueAggregationCoversAllEntries) {
    for (const auto& p : physiqueTemplates()) {
        const auto agg = physiqueEffectsFor({p.id});
        EXPECT_DOUBLE_EQ(p.cultivationSpeedBonus, agg.cultivationSpeedBonus) << p.id;
        EXPECT_DOUBLE_EQ(p.damageAmplification, agg.damageAmplification) << p.id;
        EXPECT_DOUBLE_EQ(p.damageReduction, agg.damageReduction) << p.id;
        EXPECT_DOUBLE_EQ(p.critDamageBonus, agg.critDamageBonus) << p.id;
        EXPECT_DOUBLE_EQ(p.defenseBonus, agg.defenseBonus) << p.id;
        // cultivationSpeedBonus 分量签名与全量版一致
        EXPECT_DOUBLE_EQ(agg.cultivationSpeedBonus,
                         physiqueCultivationBonusFor({p.id}));
    }
}

TEST(TraitEffectsTest, PhysiqueSpotValuesAndBoundaries) {
    // 灵脉天成 1 阶修炼速度 +8%；负面体质 -20%
    EXPECT_DOUBLE_EQ(kPhysCultSpeedR1,
                     physiqueCultivationBonusFor({"r1_phys_cult_speed"}));
    constexpr double kNegPhysCultSpeed = -0.20;   // neg_phys_cult 经脉堵塞
    EXPECT_DOUBLE_EQ(kNegPhysCultSpeed,
                     physiqueCultivationBonusFor({"neg_phys_cult"}));
    // 混合进攻体质：伤害加成与暴伤同条并存，防御分量为零
    const auto hybrid = physiqueEffectsFor({"r3_phys_hybrid_off"});
    EXPECT_DOUBLE_EQ(kHybridOffR3Amp, hybrid.damageAmplification);
    EXPECT_DOUBLE_EQ(kHybridOffR3Crit, hybrid.critDamageBonus);
    EXPECT_DOUBLE_EQ(0.0, hybrid.defenseBonus);
    // 空输入 / 未知 id → 各分量 0
    const auto empty = physiqueEffectsFor({});
    EXPECT_DOUBLE_EQ(0.0, empty.cultivationSpeedBonus);
    EXPECT_DOUBLE_EQ(0.0, physiqueCultivationBonusFor({kUnknownId}));
}

// ── mergeEffects 合并 ───────────────────────────────────────────────

TEST(TraitEffectsTest, MergeEffectsUnionsAndSumsKeys) {
    const auto talents = talentEffectsFor({"r1_bat_hp", "r1_base_comp"});
    const auto affixes =
        affixEffectsFor({"r1_aff_bat_atk", "r1_aff_base_comp"});
    const auto merged = mergeEffects(talents, affixes);
    // 天赋独有 key 保留
    EXPECT_DOUBLE_EQ(kMaxHpR1Talent, effectValue(merged, "maxHp"));
    // 词条独有 key 并入（r1_aff_bat_atk 1 阶 0.04）
    EXPECT_DOUBLE_EQ(kMagicAtkAffR1, effectValue(merged, "magicAttack"));
    // 同名 key（comprehensionFlat：天赋+4 / 词条+3）相加
    EXPECT_DOUBLE_EQ(kCompFlatR1Talent + kCompFlatR1Aff,
                     effectValue(merged, "comprehensionFlat"));
}

// ── baseComprehension 分叉修复回归（t2-1-review:77） ─────────────────

TEST(TraitEffectsTest, BaseComprehensionIncludesTalentFlatOnly) {
    gamecore::state::Disciple d;   // 默认悟性 50
    d.talentIds = {"r3_base_comp"};
    EXPECT_EQ(kDefaultComprehension +
                  static_cast<int32_t>(kCompFlatR3Talent),
              baseComprehension(d));
}

TEST(TraitEffectsTest, BaseComprehensionMergesAffixFlatRegression) {
    gamecore::state::Disciple d;
    d.talentIds = {"r3_base_comp"};
    d.affixIds = {"r2_aff_base_comp"};
    // 分叉修复前：只算天赋 flat=68；修复后合并词条 flat=75
    EXPECT_EQ(kDefaultComprehension +
                  static_cast<int32_t>(kCompFlatR3Talent + kCompFlatR2Aff),
              baseComprehension(d));
}

TEST(TraitEffectsTest, BaseComprehensionNegativeFlatsTruncateTowardZero) {
    gamecore::state::Disciple d;
    d.talentIds = {"neg_base_comprehension"};   // -8
    d.affixIds = {"neg_aff_base"};              // -5（词条负面并入）
    EXPECT_EQ(kDefaultComprehension -
                  static_cast<int32_t>(-kCompFlatNegTalent - kCompFlatNegAff),
              baseComprehension(d));
}

TEST(TraitEffectsTest, BaseComprehensionIgnoresUnknownIds) {
    gamecore::state::Disciple d;
    d.talentIds = {kUnknownId};
    d.affixIds = {kUnknownId};
    EXPECT_EQ(kDefaultComprehension, baseComprehension(d));
}

// ── 突破寿命增益（天赋+词条 lifespan 合并） ──────────────────────────

TEST(TraitEffectsTest, BreakthroughLifespanGainMergesTalentsAndAffixes) {
    // r6_lifespan(+0.60) + r3_aff_lifespan(+0.28) = +0.88 → 50 + trunc(44.0)
    const int32_t boosted = calculateBreakthroughLifespanGain(
        8, {"r6_lifespan"}, {"r3_aff_lifespan"});
    EXPECT_EQ(kRealm8Gain +
                  static_cast<int32_t>(kRealm8Gain *
                                       (kLifespanR6 + kLifespanAffR3)),
              boosted);
}

TEST(TraitEffectsTest, BreakthroughLifespanGainNegativeAffixReduces) {
    // 仅负面词条 -0.15 → 50 + trunc(-7.5)（向零截断）= 43
    const int32_t reduced =
        calculateBreakthroughLifespanGain(8, {}, {"neg_aff_lifespan"});
    EXPECT_EQ(kRealm8Gain + static_cast<int32_t>(kRealm8Gain * kLifespanAffNeg),
              reduced);
}

TEST(TraitEffectsTest, BreakthroughLifespanGainZeroBonusReturnsBase) {
    EXPECT_EQ(kRealm8Gain, calculateBreakthroughLifespanGain(8, {}, {}));
    // 未知 id 同样走零加成路径
    EXPECT_EQ(kRealm8Gain,
              calculateBreakthroughLifespanGain(8, {kUnknownId}, {kUnknownId}));
}

}  // namespace
}  // namespace gamecore::stats
