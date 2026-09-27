// ============================================================
// 时间单位常量栈测试（结算改造方案 2026-09-27 §2.2/§5.1）
//
// Kotlin 同源锚点：GameTimeUnitsParityTest（GameConfig.Time 同名常量
// 同值同公式）——两测互为锚点，改值须双端同步。
// 覆盖：常量值锁定 / 换算公式 / 日历投影（INV-1）与精确互逆 / 负输入钳制。
// ============================================================
#include <gtest/gtest.h>

#include "gamecore/system/time_system.h"
#include "gamecore/system/time_units.h"

namespace {

using gamecore::system::calendarToGameMs;
using gamecore::system::kGameMsPerPhase;
using gamecore::system::kGameSecondsPerMonth;
using gamecore::system::kGameSecondsPerPhase;
using gamecore::system::kGameSecondsPerYear;
using gamecore::system::kMonthsPerYear;
using gamecore::system::kMsPerGameSecond;
using gamecore::system::kPhasesPerMonth;
using gamecore::system::perMonthToPerGameSecond;
using gamecore::system::perPhaseToPerGameSecond;
using gamecore::system::perYearToPerGameSecond;
using gamecore::system::projectCalendar;

TEST(TimeUnitsTest, ConstantsMatchDocumentedValues) {
    EXPECT_EQ(1000, kMsPerGameSecond);
    EXPECT_DOUBLE_EQ(2.0, kGameSecondsPerPhase);
    EXPECT_DOUBLE_EQ(6.0, kGameSecondsPerMonth);
    EXPECT_DOUBLE_EQ(72.0, kGameSecondsPerYear);
    // 派生一致性：1 旬 = 2 游戏秒 = 2000 游戏毫秒
    EXPECT_EQ(2000, kGameMsPerPhase);
    EXPECT_EQ(static_cast<int64_t>(kGameSecondsPerPhase * kMsPerGameSecond), kGameMsPerPhase);
    // 旬/月/年秒数与旬/月、月/年整除关系一致（time_system.h 日历常量锚）
    EXPECT_DOUBLE_EQ(kPhasesPerMonth * kGameSecondsPerPhase, kGameSecondsPerMonth);
    EXPECT_DOUBLE_EQ(kMonthsPerYear * kGameSecondsPerMonth, kGameSecondsPerYear);
}

TEST(TimeUnitsTest, ConversionFormulasDivideByPeriodLength) {
    EXPECT_DOUBLE_EQ(9.5, perPhaseToPerGameSecond(19.0));
    EXPECT_DOUBLE_EQ(500.0, perMonthToPerGameSecond(3000.0));
    EXPECT_DOUBLE_EQ(10.0, perYearToPerGameSecond(720.0));
    // 恒等锚：1.0 每秒 = 2.0 每旬 = 6.0 每月 = 72.0 每年
    EXPECT_NEAR(1.0, perPhaseToPerGameSecond(2.0), 1e-12);
    EXPECT_NEAR(1.0, perMonthToPerGameSecond(6.0), 1e-12);
    EXPECT_NEAR(1.0, perYearToPerGameSecond(72.0), 1e-12);
}

TEST(TimeUnitsTest, CalendarOriginProjectsToZeroGameMs) {
    EXPECT_EQ(0, calendarToGameMs(1, 1, 0));
    int32_t year = 0, month = 0, phase = 0;
    projectCalendar(0, year, month, phase);
    EXPECT_EQ(1, year);
    EXPECT_EQ(1, month);
    EXPECT_EQ(0, phase);
}

TEST(TimeUnitsTest, CalendarAndProjectionAreExactInverses) {
    for (int32_t year = 1; year <= 5; ++year) {
        for (int32_t month = 1; month <= kMonthsPerYear; ++month) {
            for (int32_t phase = 0; phase < kPhasesPerMonth; ++phase) {
                const int64_t gameMs = calendarToGameMs(year, month, phase);
                int32_t py = 0, pm = 0, pp = 0;
                projectCalendar(gameMs, py, pm, pp);
                EXPECT_EQ(year, py) << "y" << year << " m" << month << " p" << phase;
                EXPECT_EQ(month, pm) << "y" << year << " m" << month << " p" << phase;
                EXPECT_EQ(phase, pp) << "y" << year << " m" << month << " p" << phase;
            }
        }
    }
}

TEST(TimeUnitsTest, OnePhaseOfGameMsAdvancesExactlyOnePhase) {
    int32_t year = 0, month = 0, phase = 0;
    projectCalendar(kGameMsPerPhase, year, month, phase);
    EXPECT_EQ(1, year);
    EXPECT_EQ(1, month);
    EXPECT_EQ(1, phase);
    // 一年 = 72 游戏秒 = 36 旬 = 72000 游戏毫秒
    EXPECT_EQ(72000, calendarToGameMs(2, 1, 0));
}

TEST(TimeUnitsTest, NegativeInputClampedToCalendarOrigin) {
    int32_t year = 0, month = 0, phase = 0;
    projectCalendar(-12345, year, month, phase);
    EXPECT_EQ(1, year);
    EXPECT_EQ(1, month);
    EXPECT_EQ(0, phase);
    EXPECT_GE(calendarToGameMs(1, 1, 0), 0);
}

}  // namespace
