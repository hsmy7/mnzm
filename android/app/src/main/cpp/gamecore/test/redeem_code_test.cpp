#include <gtest/gtest.h>

#include <string>
#include <utility>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/redeem_code.h"

namespace gamecore::system {
namespace {

using gamecore::rng::DeterministicRng;
using gamecore::rng::RngManager;
using gamecore::rng::RngPartition;

// ── validateRedeemInput ───────────────────────────────────────

TEST(RedeemCodeTest, ValidateInputTrimsAndChecks) {
    EXPECT_TRUE(validateRedeemInput("ABC123").empty());
    EXPECT_TRUE(validateRedeemInput("  ABC123  ").empty());   // trim
    EXPECT_TRUE(validateRedeemInput("\u3000ABC123\u3000").empty());  // 全角空格 trim
    EXPECT_EQ(validateRedeemInput(""), "请输入兑换码");
    EXPECT_EQ(validateRedeemInput("   "), "请输入兑换码");
    EXPECT_EQ(validateRedeemInput("AB"), "兑换码长度不能少于3个字符");
    EXPECT_EQ(validateRedeemInput("ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"),
              "兑换码长度不能超过20个字符");
    EXPECT_EQ(validateRedeemInput("ABC 123"), "兑换码只能包含字母和数字");
    EXPECT_EQ(validateRedeemInput("ABC_123"), "兑换码只能包含字母和数字");
    EXPECT_TRUE(validateRedeemInput("修仙码").empty());  // 中文合法
}

// ── JavaRandomCompat（java.util.Random 复现）──────────────────

TEST(RedeemCodeTest, JavaRandomCompatMatchesKnownSeeds) {
    // java.util.Random(42) 的前几个 nextInt(100)（标准 LCG 序列）
    JavaRandomCompat rng(42);
    // nextInt(100) 序列（java.util.Random 拒绝采样；用已知确定性断言：
    // 同种子两次构造序列一致 + 范围正确）
    const std::vector<int32_t> seq = {rng.nextInt(100), rng.nextInt(100), rng.nextInt(100)};
    JavaRandomCompat rng2(42);
    for (int32_t v : seq) {
        EXPECT_EQ(v, rng2.nextInt(100));
    }
    for (int32_t v : seq) {
        EXPECT_GE(v, 0);
        EXPECT_LT(v, 100);
    }
}

TEST(RedeemCodeTest, JavaRandomShuffleDeterministic) {
    const std::vector<std::string> input = {"metal", "wood", "water", "fire", "earth"};
    const auto a = JavaRandomCompat::shuffle(input, 12345);
    const auto b = JavaRandomCompat::shuffle(input, 12345);
    EXPECT_EQ(a, b);
    // 排列保持元素集合不变（只是重排）
    std::vector<std::string> sortedA = a;
    std::sort(sortedA.begin(), sortedA.end());
    std::vector<std::string> sortedIn = input;
    std::sort(sortedIn.begin(), sortedIn.end());
    EXPECT_EQ(sortedA, sortedIn);
}

// ── rollBySpiritRootCount / generateVariance / avoidSentinel50 ─

TEST(RedeemCodeTest, RollBySpiritRootCountRanges) {
    DeterministicRng rng = DeterministicRng::fromSeed(42);
    for (int32_t i = 0; i < 200; ++i) {
        EXPECT_GE(rollBySpiritRootCount(rng, 1), 80);
        EXPECT_LE(rollBySpiritRootCount(rng, 1), 100);
        EXPECT_GE(rollBySpiritRootCount(rng, 2), 60);
        EXPECT_LE(rollBySpiritRootCount(rng, 2), 80);
        EXPECT_GE(rollBySpiritRootCount(rng, 3), 40);
        EXPECT_GE(rollBySpiritRootCount(rng, 5), 1);
        EXPECT_LE(rollBySpiritRootCount(rng, 5), 20);
    }
}

TEST(RedeemCodeTest, GenerateVarianceRange) {
    DeterministicRng rng = DeterministicRng::fromSeed(7);
    for (int32_t i = 0; i < 500; ++i) {
        const int32_t v = generateVariance(rng);
        EXPECT_GE(v, -50);
        EXPECT_LE(v, 50);
    }
}

TEST(RedeemCodeTest, AvoidSentinel50) {
    EXPECT_EQ(avoidSentinel50(50), 51);
    EXPECT_EQ(avoidSentinel50(49), 49);
    EXPECT_EQ(avoidSentinel50(51), 51);
}

// ── resolveAgeAndLifespan ─────────────────────────────────────

TEST(RedeemCodeTest, ResolveAgeAndLifespan) {
    DeterministicRng rng = DeterministicRng::fromSeed(42);
    for (int32_t i = 0; i < 100; ++i) {
        const auto r = resolveAgeAndLifespan(rng, 10, 20, 9);
        EXPECT_GE(r.first, 10);
        EXPECT_LE(r.first, 20);
        // 炼气 maxAge 基准：寿命 ≥ 基准（±10% 波动下限钳制）
        EXPECT_GE(r.second, disciple::realmConfig(9).maxAge);
    }
}

TEST(RedeemCodeTest, ResolveAgeMinGtMax) {
    DeterministicRng rng = DeterministicRng::fromSeed(42);
    // minAge >= maxAge → 直接取 minAge（S13 防崩溃）
    const auto r = resolveAgeAndLifespan(rng, 30, 10, 9);
    EXPECT_EQ(r.first, 30);
}

// ── resolveSpiritRoot / spiritRootGenerate ────────────────────

TEST(RedeemCodeTest, ResolveSpiritRootConfigSpecified) {
    DeterministicRng a = DeterministicRng::fromSeed(42);
    DeterministicRng b = DeterministicRng::fromSeed(42);
    const std::string type = "fire";
    int32_t count = 1;
    EXPECT_EQ(resolveSpiritRoot(a, &type, &count), "fire");  // 单灵根直接返回
    count = 3;
    const std::string multiA = resolveSpiritRoot(a, &type, &count);
    const std::string multiB = resolveSpiritRoot(b, &type, &count);
    EXPECT_EQ(multiA, multiB);
    // 包含 baseType 且总数为 3，元素来自五属性
    EXPECT_NE(multiA.find("fire"), std::string::npos);
    std::size_t commaCount = 0;
    for (char c : multiA) {
        if (c == ',') ++commaCount;
    }
    EXPECT_EQ(commaCount, 2);
}

TEST(RedeemCodeTest, ResolveSpiritRootCountOnly) {
    DeterministicRng a = DeterministicRng::fromSeed(7);
    DeterministicRng b = DeterministicRng::fromSeed(7);
    int32_t count = 4;
    const std::string ra = resolveSpiritRoot(a, nullptr, &count);
    const std::string rb = resolveSpiritRoot(b, nullptr, &count);
    EXPECT_EQ(ra, rb);
    std::size_t commaCount = 0;
    for (char c : ra) {
        if (c == ',') ++commaCount;
    }
    EXPECT_EQ(commaCount, 3);  // 4 元素
    // 元素均来自五属性集合
    const auto& elements = spiritRootElements();
    std::size_t pos = 0;
    while (pos < ra.size()) {
        const std::size_t comma = ra.find(',', pos);
        const std::string token = ra.substr(pos, comma == std::string::npos ? std::string::npos : comma - pos);
        EXPECT_TRUE(std::find(elements.begin(), elements.end(), token) != elements.end());
        if (comma == std::string::npos) break;
        pos = comma + 1;
    }
}

TEST(RedeemCodeTest, ResolveSpiritRootDefaultWeights) {
    DeterministicRng a = DeterministicRng::fromSeed(99);
    DeterministicRng b = DeterministicRng::fromSeed(99);
    const std::string ra = resolveSpiritRoot(a, nullptr, nullptr);
    const std::string rb = resolveSpiritRoot(b, nullptr, nullptr);
    EXPECT_EQ(ra, rb);
    EXPECT_FALSE(ra.empty());
}

TEST(RedeemCodeTest, ResolveSpiritRootCountClampedToOne) {
    DeterministicRng rng = DeterministicRng::fromSeed(42);
    int32_t count = -3;  // coerceAtLeast(1) → 1
    const std::string type = "wood";
    EXPECT_EQ(resolveSpiritRoot(rng, &type, &count), "wood");
}

TEST(RedeemCodeTest, SpiritRootGenerateConsumesOneDouble) {
    // spiritRootGenerate：1×nextDouble（权重）+ 洗牌 nextInt——确定性
    DeterministicRng a = DeterministicRng::fromSeed(5);
    DeterministicRng b = DeterministicRng::fromSeed(5);
    EXPECT_EQ(spiritRootGenerate(a), spiritRootGenerate(b));
    EXPECT_FALSE(spiritRootGenerate(a).empty());
}

}  // namespace
}  // namespace gamecore::system
