#include <gtest/gtest.h>

#include "gamecore/game_core.h"
#include "gamecore/system/settlement.h"
#include "gamecore/system/time_system.h"

namespace gamecore {
namespace {

using gamecore::state::GameData;
using gamecore::system::SettlementEngine;

// ============================================================
// 时间系统测试（批次 3）
// 黄金场景与 Kotlin TimeSystemPureLogicTest 一致（双端锚定 TimeSystem 语义）
// ============================================================

TEST(TimeSystemTest, Constants) {
    EXPECT_EQ(3, system::kPhasesPerMonth);
    EXPECT_EQ(12, system::kMonthsPerYear);
}

TEST(TimeSystemTest, AdvancePhaseIncrementsFrom0) {
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;
    system::advancePhase(gd);
    EXPECT_EQ(1, gd.gameYear);
    EXPECT_EQ(1, gd.gameMonth);
    EXPECT_EQ(1, gd.gamePhase);
}

TEST(TimeSystemTest, AdvancePhaseIncrementsFrom1) {
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 1;
    system::advancePhase(gd);
    EXPECT_EQ(2, gd.gamePhase);
}

TEST(TimeSystemTest, AdvancePhaseMonthRollsOver) {
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 2;
    system::advancePhase(gd);
    EXPECT_EQ(1, gd.gameYear);
    EXPECT_EQ(2, gd.gameMonth);
    EXPECT_EQ(0, gd.gamePhase);
}

TEST(TimeSystemTest, AdvancePhaseYearRollsOver) {
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 12; gd.gamePhase = 2;
    system::advancePhase(gd);
    EXPECT_EQ(2, gd.gameYear);
    EXPECT_EQ(1, gd.gameMonth);
    EXPECT_EQ(0, gd.gamePhase);
}

TEST(TimeSystemTest, TotalPhasesEncoding) {
    // Kotlin: year*12*3 + (month-1)*3 + phase；year=3 month=5 phase=2 → 122
    GameData gd;
    gd.gameYear = 3; gd.gameMonth = 5; gd.gamePhase = 2;
    EXPECT_EQ(122, system::totalPhases(gd));
}

// ── SettlementEngine ────────────────────────────────────────────────

TEST(SettlementEngineTest, AdvancePhasesMovesTime) {
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;
    state::GameState st;
    st.gameData = gd;
    SettlementEngine eng;
    const auto r = eng.advancePhases(st, 5);
    EXPECT_EQ(5, r.phasesAdvanced);
    // 5 旬：phase 0→2（3 旬进 1 月）→ 第 6 旬开始第 2 月 phase 0？
    // 推进 5 旬 = 5 次 +1：0→1→2→(月2,0)→1→2 → 1年2月下旬
    EXPECT_EQ(1, st.gameData.gameYear);
    EXPECT_EQ(2, st.gameData.gameMonth);
    EXPECT_EQ(2, st.gameData.gamePhase);
    EXPECT_TRUE(r.monthChanged);   // 发生过月变
    EXPECT_FALSE(r.yearChanged);
}

TEST(SettlementEngineTest, AdvancePhasesYearBoundary) {
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 12; gd.gamePhase = 1;
    state::GameState st;
    st.gameData = gd;
    SettlementEngine eng;
    const auto r = eng.advancePhases(st, 2);   // 12月下旬 → 2年1月上旬
    EXPECT_EQ(2, st.gameData.gameYear);
    EXPECT_EQ(1, st.gameData.gameMonth);
    EXPECT_EQ(0, st.gameData.gamePhase);
    EXPECT_TRUE(r.monthChanged);
    EXPECT_TRUE(r.yearChanged);
}

TEST(SettlementEngineTest, AdvancePhasesNoBoundary) {
    GameData gd;
    gd.gameYear = 5; gd.gameMonth = 3; gd.gamePhase = 0;
    state::GameState st;
    st.gameData = gd;
    SettlementEngine eng;
    const auto r = eng.advancePhases(st, 2);
    EXPECT_EQ(5, st.gameData.gameYear);
    EXPECT_EQ(3, st.gameData.gameMonth);
    EXPECT_EQ(2, st.gameData.gamePhase);
    EXPECT_FALSE(r.monthChanged);
    EXPECT_FALSE(r.yearChanged);
}

TEST(SettlementEngineTest, AdvanceAccumulatorConsumes) {
    // GameTimeClock 语义：msPerPhase=2000ms@1x；单 tick 上限 3 旬（超限丢弃余量）
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;
    state::GameState st;
    st.gameData = gd;
    SettlementEngine eng;
    auto r = eng.advance(st, 10'000);   // 10s = 5 旬，cap=3 → 3 旬
    EXPECT_EQ(3, r.phasesAdvanced);
    // 3 旬：1年1月上旬 → 1年2月上旬
    EXPECT_EQ(1, st.gameData.gameYear);
    EXPECT_EQ(2, st.gameData.gameMonth);
    EXPECT_EQ(0, st.gameData.gamePhase);
    EXPECT_TRUE(r.monthChanged);

    // 超限丢弃余量：再 advance 10s → 又 3 旬（accumulator 已清 0）
    r = eng.advance(st, 10'000);
    EXPECT_EQ(3, r.phasesAdvanced);
    EXPECT_EQ(3, st.gameData.gameMonth);
    EXPECT_EQ(0, st.gameData.gamePhase);
}

TEST(SettlementEngineTest, AdvanceSpeedScaling) {
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;
    state::GameState st;
    st.gameData = gd;
    SettlementEngine eng;
    eng.setSpeed(2);                       // 2x：10s = 10 旬，cap=6
    const auto r = eng.advance(st, 10'000);
    EXPECT_EQ(6, r.phasesAdvanced);
    // 6 旬：1年1月上旬 → 1年3月上旬（2 月进位）
    EXPECT_EQ(1, st.gameData.gameYear);
    EXPECT_EQ(3, st.gameData.gameMonth);
    EXPECT_EQ(0, st.gameData.gamePhase);
}

TEST(SettlementEngineTest, AdvancePaused) {
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;
    state::GameState st;
    st.gameData = gd;
    SettlementEngine eng;
    eng.setSpeed(0);                       // 暂停
    const auto r = eng.advance(st, 10'000);
    EXPECT_EQ(0, r.phasesAdvanced);
    EXPECT_EQ(1, st.gameData.gameYear);
    EXPECT_EQ(1, st.gameData.gameMonth);
    EXPECT_EQ(0, st.gameData.gamePhase);
}

TEST(SettlementEngineTest, AdvancePartialPhase) {
    // 不足 2000ms 不推进（累积留存）
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;
    state::GameState st;
    st.gameData = gd;
    SettlementEngine eng;
    EXPECT_EQ(0, eng.advance(st, 1'500).phasesAdvanced);
    EXPECT_EQ(1, eng.advance(st, 1'000).phasesAdvanced);   // 累积 2500ms → 1 旬
    EXPECT_EQ(1, st.gameData.gameMonth);
    EXPECT_EQ(1, st.gameData.gamePhase);
}

TEST(SettlementEngineTest, HooksFiredOnBoundaries) {
    int phaseCount = 0, monthCount = 0, yearCount = 0;
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 12; gd.gamePhase = 1;
    state::GameState st;
    st.gameData = gd;
    SettlementEngine eng;
    eng.onPhaseSettle = [&](state::GameState&, state::GameData&) { ++phaseCount; };
    eng.onMonthChange = [&](state::GameState&, state::GameData&) { ++monthCount; };
    eng.onYearChange = [&](state::GameState&, state::GameData&) { ++yearCount; };
    eng.advancePhases(st, 4);   // 12月下→2年1月上（2 次月变：12月→1月... 实际 4 旬 = 12月下旬,2年1月1旬）
    EXPECT_EQ(4, phaseCount);
    EXPECT_EQ(1, monthCount);
    EXPECT_EQ(1, yearCount);
}

TEST(GameCoreTimeTest, AdvancePhasesViaCore) {
    GameCore core(nullptr, nullptr);
    GameCoreConfig config;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));
    core.state().gameData.gameYear = 1;
    core.state().gameData.gameMonth = 1;
    core.state().gameData.gamePhase = 0;
    const auto r = core.advancePhases(36);   // 一整年 = 36 旬
    EXPECT_EQ(2, core.state().gameData.gameYear);
    EXPECT_EQ(1, core.state().gameData.gameMonth);
    EXPECT_EQ(0, core.state().gameData.gamePhase);
    EXPECT_TRUE(r.yearChanged);
}

}  // namespace
}  // namespace gamecore
