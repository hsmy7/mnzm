#include <gtest/gtest.h>

#include <cmath>
#include <map>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/sect_decision.h"
#include "gamecore/system/sect_power.h"
#include "gamecore/system/sect_trade.h"
#include "gamecore/system/rarity_progression.h"

namespace gamecore::system {
namespace {

using gamecore::rng::DeterministicRng;
using gamecore::rng::RngManager;
using gamecore::rng::RngPartition;

// ── sectDecisionChance：四因素基础 ────────────────────────────

TEST(SectDiplomacyTest, DecisionChanceBasic) {
    const auto& profile = attackDecisionProfile();
    // 战力 5x + 全胜 + 仇恨 → 高分（上限 0.95）
    const double chance = sectDecisionChance(profile, 5.0, 2, 0, 3, 0, 0 /*HOSTILE*/, -1);
    EXPECT_GT(chance, 0.5);
    EXPECT_LE(chance, 0.95);
}

TEST(SectDiplomacyTest, DecisionChanceHardThreshold) {
    const auto& profile = attackDecisionProfile();
    // 战力低于硬门槛 0.5 → 0
    EXPECT_EQ(sectDecisionChance(profile, 0.4, 2, 0, 3, 0, 0, -1), 0.0);
    EXPECT_EQ(sectDecisionChance(profile, 0.49, 2, 0, 3, 0, 0, -1), 0.0);
}

TEST(SectDiplomacyTest, DecisionChanceNaNInfDefense) {
    const auto& profile = attackDecisionProfile();
    EXPECT_EQ(sectDecisionChance(profile, std::nan(""), 1, 0, 1, 0, 0, -1), 0.0);
    EXPECT_EQ(sectDecisionChance(profile, std::numeric_limits<double>::infinity(), 1, 0, 1, 0, 0, -1), 0.0);
}

TEST(SectDiplomacyTest, DecisionChanceFavorZeroBlocks) {
    const auto& profile = attackDecisionProfile();
    // 攻击场景 FRIENDLY/INTIMATE 好感度分值 0 且权重 > 0 → 直接 0
    EXPECT_EQ(sectDecisionChance(profile, 5.0, 2, 0, 3, 0, 3 /*FRIENDLY*/, -1), 0.0);
    EXPECT_EQ(sectDecisionChance(profile, 5.0, 2, 0, 3, 0, 4 /*INTIMATE*/, -1), 0.0);
    // 结盟场景 HOSTILE 分值 0 → 0
    EXPECT_EQ(sectDecisionChance(allianceDecisionProfile(), 5.0, 2, 0, 3, 0, 0, -1), 0.0);
}

TEST(SectDiplomacyTest, DecisionChancePersonalityModifier) {
    const auto& profile = attackDecisionProfile();
    const double base = sectDecisionChance(profile, 2.0, 0, 0, 1, 0, 0, -1);
    const double aggressive = sectDecisionChance(profile, 2.0, 0, 0, 1, 0, 0, 0 /*AGGRESSIVE*/);
    const double reclusive = sectDecisionChance(profile, 2.0, 0, 0, 1, 0, 0, 3 /*RECLUSIVE*/);
    EXPECT_DOUBLE_EQ(aggressive, std::min(base * 1.20, 0.95));
    EXPECT_DOUBLE_EQ(reclusive, std::min(base * 0.60, 0.95));
    // 附属无个性修正
    const double vassal = sectDecisionChance(vassalDecisionProfile(), 2.0, 0, 0, 1, 0, 0, 0);
    EXPECT_DOUBLE_EQ(vassal, sectDecisionChance(vassalDecisionProfile(), 2.0, 0, 0, 1, 0, 0, 3));
}

TEST(SectDiplomacyTest, DecisionChanceClampsMax) {
    const auto& profile = attackDecisionProfile();
    // 极端优势 → 裁剪到 maxChance 0.95
    const double chance = sectDecisionChance(profile, 100.0, 100, 0, 100, 0, 0, 0);
    EXPECT_DOUBLE_EQ(chance, 0.95);
}

// ── sectBreakawayChance ───────────────────────────────────────

TEST(SectDiplomacyTest, BreakawayChanceInverseLogic) {
    // 玩家战力碾压（5x）→ 低脱离概率；玩家弱 → 高脱离
    const double strong = sectBreakawayChance(5.0, 0, 0, 0, 0, 4 /*INTIMATE*/);
    const double weak = sectBreakawayChance(1.0, 0, 0, 0, 0, 0 /*HOSTILE*/);
    EXPECT_LT(strong, weak);
    EXPECT_LE(weak, 0.40);
    EXPECT_DOUBLE_EQ(strong, 0.0 + 0.0 + 0.0 + 0.0 * 0.15);
}

TEST(SectDiplomacyTest, BreakawayChanceNaN) {
    EXPECT_EQ(sectBreakawayChance(std::nan(""), 0, 0, 0, 0, 0), 0.0);
}

// ── sectPowerScore ────────────────────────────────────────────

TEST(SectDiplomacyTest, PowerScoreTiers) {
    EXPECT_DOUBLE_EQ(sectPowerScore(5.0, 0.40), 0.40);
    EXPECT_DOUBLE_EQ(sectPowerScore(3.0, 0.40), 0.30);
    EXPECT_DOUBLE_EQ(sectPowerScore(2.0, 0.40), 0.20);
    EXPECT_DOUBLE_EQ(sectPowerScore(1.5, 0.40), 0.10);
    EXPECT_DOUBLE_EQ(sectPowerScore(1.0, 0.40), 0.0);
    // 权重缩放：结盟 powerWeight 0.20 → 分数减半
    EXPECT_DOUBLE_EQ(sectPowerScore(5.0, 0.20), 0.20);
}

// ── 战力计算 ──────────────────────────────────────────────────

TEST(SectDiplomacyTest, DiscipleCombatPowerFormula) {
    // (100+80)*5 + 1000*4 + (60+50)*3 + 40*2 = 900 + 4000 + 330 + 80 = 5310
    EXPECT_EQ(discipleCombatPower(100, 80, 1000, 60, 50, 40), 5310);
    EXPECT_EQ(discipleCombatPower(0, 0, 0, 0, 0, 0), 0);
}

TEST(SectDiplomacyTest, BeastCombatPowerClampsNegative) {
    EXPECT_EQ(beastCombatPower(-5, 10, 10, 5, 5, 2), (10 + 10) * 5 + 0 * 4 + (5 + 5) * 3 + 2 * 2);
    EXPECT_EQ(beastCombatPower(0, -1, -1, -1, -1, -1), 0);
}

TEST(SectDiplomacyTest, FingerprintDeterministic) {
    const std::vector<std::string> talents = {"t1", "t2"};
    const BloodRefinementPctTotalCpp blood = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6};
    const int32_t a = sectPowerFingerprint(5, 3, 1, 2, 3, 4, 5, 6, talents, &blood);
    const int32_t b = sectPowerFingerprint(5, 3, 1, 2, 3, 4, 5, 6, talents, &blood);
    EXPECT_EQ(a, b);
    // 任一字段变化 → 指纹变化
    EXPECT_NE(a, sectPowerFingerprint(5, 3, 1, 2, 3, 4, 5, 7, talents, &blood));
    EXPECT_NE(a, sectPowerFingerprint(5, 3, 1, 2, 3, 4, 5, 6, {"t1"}, &blood));
    EXPECT_NE(a, sectPowerFingerprint(5, 3, 1, 2, 3, 4, 5, 6, talents, nullptr));
    // 无血炼与有血炼（全 0）不同（data class 含 6 个 0.0 字段的哈希）
    const BloodRefinementPctTotalCpp zeroBlood = {};
    EXPECT_NE(a, sectPowerFingerprint(5, 3, 1, 2, 3, 4, 5, 6, talents, &zeroBlood));
}

// ── 品阶时间曲线 ──────────────────────────────────────────────

TEST(SectDiplomacyTest, RarityMaxForYearSegments) {
    EXPECT_EQ(maxRarityForYear(1), 1);
    EXPECT_EQ(maxRarityForYear(19), 1);
    EXPECT_EQ(maxRarityForYear(20), 2);
    EXPECT_EQ(maxRarityForYear(79), 2);
    EXPECT_EQ(maxRarityForYear(80), 3);
    EXPECT_EQ(maxRarityForYear(299), 3);
    EXPECT_EQ(maxRarityForYear(300), 4);
    EXPECT_EQ(maxRarityForYear(500), 5);
    EXPECT_EQ(maxRarityForYear(1500), 6);
    EXPECT_EQ(maxRarityForYear(99999), 6);
    EXPECT_EQ(maxRarityForYear(-5), 1);  // 防御
}

TEST(SectDiplomacyTest, RarityPityForYear) {
    EXPECT_EQ(pityRarityForYear(60), 3);   // 凡~灵段 → 保底宝品
    EXPECT_EQ(pityRarityForYear(1), 2);    // 凡品段 → 保底灵品
    EXPECT_EQ(pityRarityForYear(2000), 6); // 末段取自身
}

TEST(SectDiplomacyTest, RarityWeightsNormalizedAndSegmentStart) {
    // 第 20 年（凡~灵段起点）：凡品 0.9、灵品 0.1（归一化和 1）
    const auto w20 = rarityWeightsForYear(20);
    double sum = 0.0;
    for (const auto& [r, p] : w20) sum += p;
    EXPECT_NEAR(sum, 1.0, 1e-12);
    EXPECT_NEAR(w20.at(1), 0.9, 1e-9);
    EXPECT_NEAR(w20.at(2), 0.1, 1e-9);
    // 段内线性插值：79 年 t=(79-20)/60=59/60 → 凡品 0.9-0.8*59/60 = 0.113333…
    const auto w79 = rarityWeightsForYear(79);
    EXPECT_NEAR(w79.at(1), 0.9 - 0.8 * 59.0 / 60.0, 1e-9);
    // 3000 年后天品爬升但仍 ≤ 上限
    const auto w4000 = rarityWeightsForYear(4000);
    EXPECT_LE(w4000.at(6), 0.02 + 1e-9);
}

TEST(SectDiplomacyTest, RollRarityConsumesOneDoubleAndDeterministic) {
    DeterministicRng a = DeterministicRng::fromSeed(42);
    DeterministicRng b = DeterministicRng::fromSeed(42);
    for (int32_t year : {1, 50, 200, 1000}) {
        const int32_t ra = rollRarity(a, year);
        const int32_t rb = rollRarity(b, year);
        EXPECT_EQ(ra, rb);
        EXPECT_GE(ra, 1);
        EXPECT_LE(ra, maxRarityForYear(year));
    }
}

// ── 宗门交易确定性核心 ────────────────────────────────────────

TEST(SectDiplomacyTest, TradeSeedDeterministic) {
    EXPECT_EQ(sectTradeSeed("ai-1", 50), sectTradeSeed("ai-1", 50));
    EXPECT_NE(sectTradeSeed("ai-1", 50), sectTradeSeed("ai-2", 50));
    EXPECT_NE(sectTradeSeed("ai-1", 50), sectTradeSeed("ai-1", 51));
    // 中文 sectId：Java String.hashCode 按 UTF-16 code unit（UTF-8 解码后逐 code unit）
    EXPECT_EQ(sectTradeSeed("万剑宗", 100), 19872685 + 100);
    // 增补平面字符（4 字节 UTF-8 → surrogate pair 2 个 code unit）
    EXPECT_EQ(sectTradeSeed("\xF0\x9F\x92\xA0", 1), 1772547 + 1);  // U+1F4A0 💠
}

TEST(SectDiplomacyTest, TradeStockRanges) {
    DeterministicRng rng = DeterministicRng::fromSeed(42);
    for (int32_t i = 0; i < 200; ++i) {
        // 消耗品（草药）档位区间
        const int32_t herbHigh = sectTradeStock(rng, "herb", 6);
        EXPECT_GE(herbHigh, 3);
        EXPECT_LE(herbHigh, 7);
        const int32_t herbMid = sectTradeStock(rng, "material", 3);
        EXPECT_GE(herbMid, 5);
        EXPECT_LE(herbMid, 12);
        const int32_t herbLow = sectTradeStock(rng, "seed", 1);
        EXPECT_GE(herbLow, 7);
        EXPECT_LE(herbLow, 15);
        // 耐用品档位区间
        const int32_t durableHigh = sectTradeStock(rng, "equipment", 6);
        EXPECT_GE(durableHigh, 1);
        EXPECT_LE(durableHigh, 3);
        const int32_t durableLow = sectTradeStock(rng, "manual", 1);
        EXPECT_GE(durableLow, 1);
        EXPECT_LE(durableLow, 5);
    }
}

TEST(SectDiplomacyTest, TradePriceFluctuationRange) {
    DeterministicRng rng = DeterministicRng::fromSeed(7);
    for (int32_t i = 0; i < 500; ++i) {
        const int64_t price = sectTradePriceFluctuation(10000, rng);
        EXPECT_GE(price, 1);
        EXPECT_GE(price, static_cast<int64_t>(10000 * 0.8));   // 下限约 -20%
        EXPECT_LE(price, static_cast<int64_t>(10000 * 1.2));   // 上限约 +20%
    }
}

TEST(SectDiplomacyTest, TradeSpiritStoneMapping) {
    // 稀有度 >= 4 → 上品（品阶 4，价 RATIO²=1e8）；否则中品（品阶 3，价 RATIO=1e4）
    const auto high = sectTradeSpiritStone(5, 1500);
    ASSERT_TRUE(high.has_value());
    EXPECT_EQ(high->first, 4);
    EXPECT_EQ(high->second, 100000000L);
    const auto mid = sectTradeSpiritStone(2, 1500);
    ASSERT_TRUE(mid.has_value());
    EXPECT_EQ(mid->first, 3);
    EXPECT_EQ(mid->second, 10000L);
    // 年份上限判定：第 1 年只出凡品 → 中品(3) 超限跳过；第 20 年可出灵品(2)
    // 但中品灵石映射品阶 3 > 2 仍跳过；第 80 年可出宝品(3) → 中品灵石可出
    EXPECT_FALSE(sectTradeSpiritStone(2, 1).has_value());
    EXPECT_FALSE(sectTradeSpiritStone(2, 20).has_value());
    EXPECT_TRUE(sectTradeSpiritStone(2, 80).has_value());
}

}  // namespace
}  // namespace gamecore::system
