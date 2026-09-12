#include <gtest/gtest.h>

#include <map>
#include <string>

#include "gamecore/state/models.h"
#include "gamecore/system/economy.h"

namespace gamecore::system {
namespace {

using state::GameData;

// ── SpiritStoneExchange ─────────────────────────────────────────

TEST(SpiritStoneExchangeTest, EffectiveRatioIs8000) {
    EXPECT_EQ(SpiritStoneExchange::kEffectiveRatio, 8000);
}

TEST(SpiritStoneExchangeTest, ToLowGrade) {
    EXPECT_EQ(SpiritStoneExchange::toLowGrade(0, SpiritStoneGrade::LOW), 0);
    EXPECT_EQ(SpiritStoneExchange::toLowGrade(5, SpiritStoneGrade::LOW), 5);
    EXPECT_EQ(SpiritStoneExchange::toLowGrade(2, SpiritStoneGrade::MID), 16000);
    EXPECT_EQ(SpiritStoneExchange::toLowGrade(3, SpiritStoneGrade::HIGH), 192000000);
}

TEST(SpiritStoneExchangeTest, FromLowGrade) {
    const auto mid = SpiritStoneExchange::fromLowGrade(17000, SpiritStoneGrade::MID);
    EXPECT_EQ(mid.first, 2);   // 17000 / 8000
    EXPECT_EQ(mid.second, 1000);

    const auto high = SpiritStoneExchange::fromLowGrade(64000000, SpiritStoneGrade::HIGH);
    EXPECT_EQ(high.first, 1);
    EXPECT_EQ(high.second, 0);
}

TEST(SpiritStoneExchangeTest, ExchangeCrossGrade) {
    // 1 上品 → 中品：6400 万下品 → 8000 中品
    const auto mid = SpiritStoneExchange::exchange(1, SpiritStoneGrade::HIGH,
                                                   SpiritStoneGrade::MID);
    EXPECT_EQ(mid.first, 8000);
    EXPECT_EQ(mid.second, 0);

    // 1 中品 → 上品：8000 下品不够 6400 万 → 0 上品，剩余 8000 下品折算回中品 = 1
    const auto high = SpiritStoneExchange::exchange(1, SpiritStoneGrade::MID,
                                                    SpiritStoneGrade::HIGH);
    EXPECT_EQ(high.first, 0);
    EXPECT_EQ(high.second, 1);
}

TEST(SpiritStoneExchangeTest, TotalSellValue) {
    EXPECT_EQ(SpiritStoneExchange::totalSellValue(100, 2, 0), 100 + 16000);
    EXPECT_EQ(SpiritStoneExchange::totalSellValue(0, 1, 1),
              8000 + 64000000);
}

TEST(SpiritStoneExchangeTest, SplitToGrades) {
    const auto m = SpiritStoneExchange::splitToGrades(64000000 + 16000 + 100);
    EXPECT_EQ(m.at(SpiritStoneGrade::HIGH), 1);
    EXPECT_EQ(m.at(SpiritStoneGrade::MID), 2);
    EXPECT_EQ(m.at(SpiritStoneGrade::LOW), 100);
}

// ── SpiritStoneWallet.add ───────────────────────────────────────

TEST(SpiritStoneWalletTest, AddIncreasesBalance) {
    GameData gd;
    gd.spiritStones = 1000;
    const int64_t newBalance = SpiritStoneWallet::add(gd, 500, SpiritStoneGrade::LOW);
    EXPECT_EQ(newBalance, 1500);
    EXPECT_EQ(gd.spiritStones, 1500);
}

TEST(SpiritStoneWalletTest, AddNonPositiveReturnsCurrent) {
    GameData gd;
    gd.spiritStones = 1000;
    EXPECT_EQ(SpiritStoneWallet::add(gd, 0, SpiritStoneGrade::LOW), 1000);
    EXPECT_EQ(SpiritStoneWallet::add(gd, -5, SpiritStoneGrade::LOW), 1000);
    EXPECT_EQ(gd.spiritStones, 1000);
}

TEST(SpiritStoneWalletTest, AddOverflowClampsToMax) {
    GameData gd;
    gd.spiritStones = INT64_MAX - 10;
    const int64_t newBalance = SpiritStoneWallet::add(gd, 100, SpiritStoneGrade::LOW);
    EXPECT_EQ(newBalance, INT64_MAX);
}

TEST(SpiritStoneWalletTest, AddMidGrade) {
    GameData gd;
    gd.midGradeSpiritStones = 5;
    EXPECT_EQ(SpiritStoneWallet::add(gd, 3, SpiritStoneGrade::MID), 8);
    EXPECT_EQ(gd.midGradeSpiritStones, 8);
}

TEST(SpiritStoneWalletTest, AddRecordsAnnualIncome) {
    GameData gd;
    SpiritStoneWallet::add(gd, 1000, SpiritStoneGrade::LOW, "Battle");
    EXPECT_EQ(gd.annualTotalIncome, 1000);
    EXPECT_EQ(gd.annualIncomeBySource["Battle"], 1000);
}

// ── SpiritStoneWallet.deduct ────────────────────────────────────

TEST(SpiritStoneWalletTest, DeductSuccess) {
    GameData gd;
    gd.spiritStones = 1000;
    const auto result = SpiritStoneWallet::deduct(gd, 300, SpiritStoneGrade::LOW);
    EXPECT_EQ(result.status, DeductStatus::kSuccess);
    EXPECT_EQ(result.balanceAfter, 700);
    EXPECT_EQ(gd.spiritStones, 700);
}

TEST(SpiritStoneWalletTest, DeductInvalidForNonPositive) {
    GameData gd;
    gd.spiritStones = 1000;
    const auto result = SpiritStoneWallet::deduct(gd, 0, SpiritStoneGrade::LOW);
    EXPECT_EQ(result.status, DeductStatus::kInvalid);
    EXPECT_EQ(gd.spiritStones, 1000);
}

TEST(SpiritStoneWalletTest, DeductInsufficientWithoutAutoConvert) {
    GameData gd;
    gd.spiritStones = 100;
    const auto result = SpiritStoneWallet::deduct(gd, 500, SpiritStoneGrade::LOW,
                                                  "Purchase", "Internal", false);
    EXPECT_EQ(result.status, DeductStatus::kInsufficient);
    EXPECT_EQ(result.balance, 100);
    EXPECT_EQ(result.required, 500);
    EXPECT_EQ(gd.spiritStones, 100);
}

TEST(SpiritStoneWalletTest, DeductAutoConvertMid) {
    GameData gd;
    gd.spiritStones = 100;
    gd.midGradeSpiritStones = 1;   // 值 8000 下品
    gd.autoSellMidGradeForPurchase = true;
    const auto result = SpiritStoneWallet::deduct(gd, 500, SpiritStoneGrade::LOW);
    EXPECT_EQ(result.status, DeductStatus::kSuccess);
    EXPECT_EQ(gd.midGradeSpiritStones, 0);
    EXPECT_EQ(gd.spiritStones, 100 + 8000 - 500);
}

TEST(SpiritStoneWalletTest, DeductAutoConvertDisabledFlags) {
    GameData gd;
    gd.spiritStones = 100;
    gd.midGradeSpiritStones = 1;
    gd.autoSellMidGradeForPurchase = false;
    const auto result = SpiritStoneWallet::deduct(gd, 500, SpiritStoneGrade::LOW);
    EXPECT_EQ(result.status, DeductStatus::kInsufficient);
    EXPECT_EQ(gd.midGradeSpiritStones, 1);
}

TEST(SpiritStoneWalletTest, DeductAutoConvertHighLast) {
    GameData gd;
    gd.spiritStones = 100;
    gd.highGradeSpiritStones = 1;  // 值 6400 万下品
    gd.autoSellHighGradeForPurchase = true;
    const auto result = SpiritStoneWallet::deduct(gd, 500, SpiritStoneGrade::LOW);
    EXPECT_EQ(result.status, DeductStatus::kSuccess);
    EXPECT_EQ(gd.highGradeSpiritStones, 0);
    EXPECT_EQ(gd.spiritStones, 100 + 64000000 - 500);
}

TEST(SpiritStoneWalletTest, DeductRecordsAnnualExpenditure) {
    GameData gd;
    gd.spiritStones = 1000;
    SpiritStoneWallet::deduct(gd, 400, SpiritStoneGrade::LOW, "Purchase");
    EXPECT_EQ(gd.annualTotalExpenditure, 400);
    EXPECT_EQ(gd.annualExpenditureByReason["Purchase"], 400);
}

// ── SpiritStoneWallet.batch ─────────────────────────────────────

TEST(SpiritStoneWalletTest, BatchEmpty) {
    GameData gd;
    const auto result = SpiritStoneWallet::batch(gd, {});
    EXPECT_EQ(result.successCount, 0);
    EXPECT_EQ(result.failedCount, 0);
}

TEST(SpiritStoneWalletTest, BatchMixedOperations) {
    GameData gd;
    gd.spiritStones = 1000;
    gd.midGradeSpiritStones = 10;
    std::vector<SpiritStoneOperation> ops = {
        {500, SpiritStoneGrade::LOW, "Quest", "Quest"},
        {-200, SpiritStoneGrade::LOW, "Purchase", "Purchase"},
        {3, SpiritStoneGrade::MID, "Quest", "Quest"},
    };
    const auto result = SpiritStoneWallet::batch(gd, ops);
    EXPECT_EQ(result.successCount, 3);
    EXPECT_EQ(result.failedCount, 0);
    EXPECT_EQ(gd.spiritStones, 1000 + 500 - 200);
    EXPECT_EQ(gd.midGradeSpiritStones, 13);
}

TEST(SpiritStoneWalletTest, BatchRollsBackOnPrecheckFailure) {
    GameData gd;
    gd.spiritStones = 1000;
    std::vector<SpiritStoneOperation> ops = {
        {500, SpiritStoneGrade::LOW, "Quest", "Quest"},
        {-5000, SpiritStoneGrade::LOW, "Purchase", "Purchase"},
    };
    const auto result = SpiritStoneWallet::batch(gd, ops, /*autoConvert=*/false);
    EXPECT_EQ(result.successCount, 0);
    EXPECT_EQ(result.failedCount, 2);
    // 整体回滚：第一笔 +500 也应回滚
    EXPECT_EQ(gd.spiritStones, 1000);
}

TEST(SpiritStoneWalletTest, BatchAutoConvertInPrecheck) {
    GameData gd;
    gd.spiritStones = 100;
    gd.midGradeSpiritStones = 2;   // 值 16000
    gd.autoSellMidGradeForPurchase = true;
    std::vector<SpiritStoneOperation> ops = {
        {-500, SpiritStoneGrade::LOW, "Purchase", "Purchase"},
    };
    const auto result = SpiritStoneWallet::batch(gd, ops, /*autoConvert=*/true);
    EXPECT_EQ(result.successCount, 1);
    // Kotlin calculateAutoSell：sellMidCount = ceil(shortfall/8000) = ceil(400/8000) = 1
    EXPECT_EQ(gd.midGradeSpiritStones, 1);
    EXPECT_EQ(gd.spiritStones, 100 + 8000 - 500);
}

TEST(SpiritStoneWalletTest, BatchInvalidMinValue) {
    GameData gd;
    gd.spiritStones = 1000;
    std::vector<SpiritStoneOperation> ops = {
        {INT64_MIN, SpiritStoneGrade::LOW, "Purchase", "Purchase"},
    };
    const auto result = SpiritStoneWallet::batch(gd, ops);
    EXPECT_EQ(result.successCount, 0);
    EXPECT_EQ(result.failedCount, 1);
    EXPECT_EQ(result.results[0].status, DeductStatus::kInvalid);
}

// ── 品阶辅助 ───────────────────────────────────────────────────

TEST(SpiritStoneGradeTest, NameRoundTrip) {
    EXPECT_STREQ(spiritStoneGradeName(SpiritStoneGrade::LOW), "LOW");
    EXPECT_STREQ(spiritStoneGradeName(SpiritStoneGrade::MID), "MID");
    EXPECT_STREQ(spiritStoneGradeName(SpiritStoneGrade::HIGH), "HIGH");
    EXPECT_EQ(spiritStoneGradeFromName("MID"), SpiritStoneGrade::MID);
    EXPECT_EQ(spiritStoneGradeFromName("HIGH"), SpiritStoneGrade::HIGH);
    EXPECT_EQ(spiritStoneGradeFromName("UNKNOWN"), SpiritStoneGrade::LOW);
}

}  // namespace
}  // namespace gamecore::system
