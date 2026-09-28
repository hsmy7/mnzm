#include <gtest/gtest.h>

#include "gamecore/game_core.h"
#include "gamecore/system/settlement.h"
#include "gamecore/system/time_system.h"

namespace gamecore {
namespace {

using gamecore::state::GameData;
using gamecore::system::SettlementEngine;

// ============================================================
// 时间系统测试
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

// ── 墙钟入口（两套时基统一后 = advanceByGameMs 唯一语义）────────────
// 旧 phaseCap 丢弃式累积器 advance(wallDeltaMs) 已随「两套时基」修复
// （方案 §3.4，B9）退役；本组用例按 INV-2/INV-3（结算改造方案 §2.4/§5.2）
// 锁定 shadow 臂与生产臂（PhaseClock + EngineLoop.iterate）共用的语义基准。

TEST(SettlementEngineTest, AdvanceByGameMsCapKeepsTime) {
    // 10s @1x = 5 个判定窗口，cap=3 只裁判定执行次数——时间全额入轴（INV-2）
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;
    state::GameState st;
    st.gameData = gd;
    SettlementEngine eng;
    const auto r = eng.advanceByGameMs(st, 10'000);
    EXPECT_EQ(10'000, r.deltaGameMs);
    EXPECT_EQ(5, r.windowsTotal);
    EXPECT_EQ(3, r.windowsExecuted);       // phaseCap 只作用于判定轨
    EXPECT_EQ(10'000, eng.elapsedGameMs()); // 权威轴不丢时间
    // 执行 3 旬：1年1月上旬 → 1年2月上旬
    EXPECT_EQ(1, st.gameData.gameYear);
    EXPECT_EQ(2, st.gameData.gameMonth);
    EXPECT_EQ(0, st.gameData.gamePhase);
    EXPECT_TRUE(r.monthChanged);

    // 再 advance 10s：窗口数按权威轴整数差累计（5+5=10），本段执行仍 cap=3；
    // 后续 advancePhases 不再受「超限丢弃」影响——余量不蒸发
    const auto r2 = eng.advanceByGameMs(st, 10'000);
    EXPECT_EQ(10'000, r2.deltaGameMs);
    EXPECT_EQ(5, r2.windowsTotal);
    EXPECT_EQ(3, r2.windowsExecuted);
    EXPECT_EQ(20'000, eng.elapsedGameMs());
    EXPECT_EQ(3, st.gameData.gameMonth);
}

TEST(SettlementEngineTest, AdvanceByGameMsPartialPhase) {
    // 不足 2000ms 无窗口可执行，但权威轴累积留存（跨段凑旬，INV-2）
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;
    state::GameState st;
    st.gameData = gd;
    SettlementEngine eng;
    const auto r1 = eng.advanceByGameMs(st, 1'500);
    EXPECT_EQ(1'500, r1.deltaGameMs);
    EXPECT_EQ(0, r1.windowsTotal);
    EXPECT_EQ(0, r1.windowsExecuted);
    const auto r2 = eng.advanceByGameMs(st, 1'000);   // 累积 2500ms → 1 窗口
    EXPECT_EQ(1'000, r2.deltaGameMs);
    EXPECT_EQ(1, r2.windowsTotal);
    EXPECT_EQ(1, r2.windowsExecuted);
    EXPECT_EQ(1, st.gameData.gameMonth);
    EXPECT_EQ(1, st.gameData.gamePhase);
}

TEST(SettlementEngineTest, AdvanceByGameMsNegativeDeltaClamped) {
    // 负增量（时钟回拨）防御钳制为 0——轴单调
    GameData gd;
    gd.gameYear = 1; gd.gameMonth = 1; gd.gamePhase = 0;
    state::GameState st;
    st.gameData = gd;
    SettlementEngine eng;
    const auto r = eng.advanceByGameMs(st, -5'000);
    EXPECT_EQ(0, r.deltaGameMs);
    EXPECT_EQ(0, eng.elapsedGameMs());
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
