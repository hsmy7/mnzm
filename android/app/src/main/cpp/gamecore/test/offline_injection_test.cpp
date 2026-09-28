// ============================================================
// offline_injection_test — B7 离线收益注入守护
//
// 守护目标（结算改造 2026-09-27 §10 B7，方案 §2.3 离线行「离线时段 ∩ 上限
// 注入连续积分轨；日历投影同步跳变」）：
//   1. 三轴一致：注入后 PhaseClock 真相轴 / SettlementEngine 已积分轴 /
//      GameData 旬投影逐位一致，日历 = projectCalendar(新轴)（INV-1）
//   2. 积分闭式等价（经济总量对拍口径）：一次性注入 X 与连续臂分帧
//      ΣΔt = X 的积分型状态逐位一致（整数分子制，§5.2 第 2 类口径）
//   3. 判定轨/事件轨零发生：注入零 RNG 消耗、零判定窗口
//   4. 基准推进：lastSettleGameMs / spiritMineLastSettledGameMs 推到新轴
//      （防回在线后重复结算）
//   5. 边界：0 / 负 / 不足一旬 / 非整旬 floor 全部零副作用或按旬网格防御
//
// 上限/速率口径（12h 全额 + 50% 至 24h 硬顶）是 Kotlin 折算层职责
//（GameConfig.Time.offlineGameMs），本文件只守护引擎侧注入语义。
// ============================================================

#include "gtest/gtest.h"

#include <memory>

#include "gamecore/game_core.h"
#include "gamecore/system/month_settlement.h"
#include "gamecore/system/time_system.h"

namespace {

using namespace gamecore;
using gamecore::state::Disciple;
using gamecore::state::SpiritMineSlot;

constexpr int64_t kPhaseMs = 2000;          // 1 旬
constexpr int64_t kMonthMs = 6000;          // 1 月
/// 每矿工基础产出（month_accrual_test 同 fixture 同期望：224/月）
constexpr int64_t kExpectedMonthlyRate = 224;

constexpr int kRngBreakthrough = 1;   // rng::RngPartition::kBreakthrough
constexpr int kRngSystem = 3;         // rng::RngPartition::kSystem
constexpr int kRngAiSectMirror = 9;   // rng::RngPartition::kAiSectMirror

/// 构建已初始化 GameCore（钩子已注册），种子固定
std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

/// 最小存活弟子（高境界避免低境界数值钳制干扰）
Disciple baseDisciple(const std::string& id) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = 9;
    d.realmLayer = 1;
    d.isAlive = true;
    d.spiritRootType = "metal";
    return d;
}

// ── 1. 三轴一致 + 日历投影跳变 ─────────────────────────────────────

TEST(OfflineInjectionTest, ThreeAxesAndCalendarJumpTogether) {
    auto core = makeCore(42);
    auto& st = core->state();

    // 先推进 5 旬建立非零起点（advancePhases 只推进 GameData 旬投影轴——
    // 会话轴（PhaseClock/SettlementEngine）在生产中由墙钟帧驱动，与
    // GameData 轴本就存在 <1 旬的粒度偏差，故断言按「注入增量三轴一致」）
    core->advancePhases(5);

    const int64_t phaseBefore = core->loop().time().elapsedGameMs();
    const int64_t settleBefore = core->settlement().elapsedGameMs();
    const int64_t gdBefore = st.gameData.elapsedGameMs;

    const int64_t injectMs = 300 * kPhaseMs;   // 100 游戏年（整旬）
    const int64_t returned = core->injectOfflineGameMs(injectMs);

    // 返回值 = 新权威轴；GameData 轴与日历投影一致（INV-1）
    EXPECT_EQ(gdBefore + injectMs, returned);
    EXPECT_EQ(gdBefore + injectMs, st.gameData.elapsedGameMs);
    int32_t y = 0, m = 0, p = 0;
    system::projectCalendar(st.gameData.elapsedGameMs, y, m, p);
    EXPECT_EQ(y, st.gameData.gameYear);
    EXPECT_EQ(m, st.gameData.gameMonth);
    EXPECT_EQ(p, st.gameData.gamePhase);

    // PhaseClock 真相轴 / SettlementEngine 已积分轴同步跳变（增量一致）
    EXPECT_EQ(phaseBefore + injectMs, core->loop().time().elapsedGameMs());
    EXPECT_EQ(settleBefore + injectMs,
              core->settlement().elapsedGameMs());

    // 月结幂等基准推进（防回在线后重复结算）
    EXPECT_EQ(st.gameData.elapsedGameMs, st.gameData.lastSettleGameMs);
}

TEST(OfflineInjectionTest, InjectedCalendarEqualsPhaseByPhaseAdvance) {
    // X 整旬 ⇒ 日历 set 与逐旬 advancePhase 推进逐位等价（注入编排的等价性前提）
    auto a = makeCore(42);
    auto b = makeCore(42);
    a->advancePhases(7);
    b->advancePhases(7);

    const int64_t injectMs = 1234 * kPhaseMs;
    a->injectOfflineGameMs(injectMs);
    b->advancePhases(1234);

    EXPECT_EQ(b->state().gameData.gameYear, a->state().gameData.gameYear);
    EXPECT_EQ(b->state().gameData.gameMonth, a->state().gameData.gameMonth);
    EXPECT_EQ(b->state().gameData.gamePhase, a->state().gameData.gamePhase);
    EXPECT_EQ(b->state().gameData.elapsedGameMs,
              a->state().gameData.elapsedGameMs);
}

// ── 2. 经济总量对拍：一次性注入 vs 分帧 accrue（积分轨闭式等价） ────

TEST(OfflineInjectionTest, InjectOnceEqualsFrameByFrameAccrual) {
    // 两侧同 fixture 同种子；注入臂一次性 X = 30 个月；分帧臂每帧 200ms
    //（在线 tick 粒度）连续 accrue 同 X。积分型状态（政策月扣/灵矿/丹药衰减）
    // 整数分子制下必须逐位一致；判定窗口的判定轨差异属 B7 预期语义
    //（离线无判定），不在对拍面。
    const int64_t totalMs = 30 * kMonthMs;

    auto injected = makeCore(42);
    auto& si = injected->state();
    si.gameData.sectPolicies.alchemyIncentive = true;   // 3000/月
    Disciple miner = baseDisciple("1");
    miner.mining = 80;
    si.disciples.appendDisciple(miner);
    SpiritMineSlot slot;
    slot.index = 0;
    slot.discipleId = "1";
    si.gameData.spiritMineSlots.push_back(slot);
    si.gameData.spiritStones = 1'000'000;   // wallet 初始余额（政策扣费输入）

    const int64_t returned = injected->injectOfflineGameMs(totalMs);
    ASSERT_EQ(totalMs, returned);

    auto framed = makeCore(42);
    auto& sf = framed->state();
    sf.gameData.spiritStones = 1'000'000;
    sf.gameData.sectPolicies.alchemyIncentive = true;
    Disciple minerF = baseDisciple("1");
    minerF.mining = 80;
    sf.disciples.appendDisciple(minerF);
    SpiritMineSlot slotF;
    slotF.index = 0;
    slotF.discipleId = "1";
    sf.gameData.spiritMineSlots.push_back(slotF);
    sf.gameData.spiritStones = 1'000'000;

    for (int64_t done = 0; done < totalMs; done += 200) {
        framed->accrue(200, true);
    }

    // 经济总量对拍（§10 B7 验收：在容差内——整数分子制下逐位）
    EXPECT_EQ(sf.gameData.spiritStones, si.gameData.spiritStones);
    EXPECT_EQ(sf.gameData.spiritMineLastSettledGameMs,
              si.gameData.spiritMineLastSettledGameMs);
    // 日历终态一致（分帧臂经窗口循环推进，注入臂经投影 set）
    EXPECT_EQ(sf.gameData.gameYear, si.gameData.gameYear);
    EXPECT_EQ(sf.gameData.gameMonth, si.gameData.gameMonth);
    EXPECT_EQ(sf.gameData.gamePhase, si.gameData.gamePhase);
}

// ── 3. 判定轨/事件轨零发生 ─────────────────────────────────────────

TEST(OfflineInjectionTest, ZeroRngConsumptionAndZeroJudgementWindows) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));

    const int64_t systemRngBefore = core->rngSnapshotPartition(kRngSystem);
    const int64_t breakthroughRngBefore =
        core->rngSnapshotPartition(kRngBreakthrough);
    const int64_t aiRngBefore = core->rngSnapshotPartition(kRngAiSectMirror);
    const int64_t before = st.gameData.elapsedGameMs;

    core->injectOfflineGameMs(90'000 * kPhaseMs);   // 3 万游戏年量级

    // RNG 分区零消耗（离线无判定/事件——INV-3 判定次数语义）
    EXPECT_EQ(systemRngBefore, core->rngSnapshotPartition(kRngSystem));
    EXPECT_EQ(breakthroughRngBefore,
              core->rngSnapshotPartition(kRngBreakthrough));
    EXPECT_EQ(aiRngBefore, core->rngSnapshotPartition(kRngAiSectMirror));
    // 时间轴仍全额推进（判定零发生 ≠ 时间丢弃，INV-2）
    EXPECT_EQ(before + 90'000 * kPhaseMs, st.gameData.elapsedGameMs);
}

// ── 4. 灵矿毫秒差分 + 政策月扣的注入闭式 ───────────────────────────

TEST(OfflineInjectionTest, SpiritMineDifferentialClosedForm) {
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple miner = baseDisciple("1");
    miner.mining = 80;                      // (80-70)×0.02 = 0.2
    miner.morality = 90;                    // 执事：(90-80)×0.01 = 0.1
    st.disciples.appendDisciple(miner);
    st.gameData.spiritStones = 0;
    SpiritMineSlot slot;
    slot.index = 0;
    slot.discipleId = "1";
    st.gameData.spiritMineSlots.push_back(slot);
    st.gameData.elderSlots.spiritMineDeaconDisciples.push_back(
        [&] {
            state::DirectDiscipleSlot deacon;
            deacon.index = 0;
            deacon.discipleId = "1";
            return deacon;
        }());

    const int64_t injectMs = 90 * kMonthMs;   // 90 个月
    core->injectOfflineGameMs(injectMs);

    // 灵矿闭式：月产率 × span / 月长（整除后入账）
    EXPECT_EQ(kExpectedMonthlyRate * 90, st.gameData.spiritStones);
    EXPECT_EQ(st.gameData.elapsedGameMs,
              st.gameData.spiritMineLastSettledGameMs);   // 差分基准 = 新轴
}

TEST(OfflineInjectionTest, PolicyCostClosedFormOnInjection) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 500'000;
    st.gameData.sectPolicies.alchemyIncentive = true;   // 3000/月

    core->injectOfflineGameMs(10 * kMonthMs);
    // 闭式：10 个月 × 3000 = 30000；余额充足 → 政策保持
    EXPECT_EQ(500'000 - 30'000, st.gameData.spiritStones);
    EXPECT_TRUE(st.gameData.sectPolicies.alchemyIncentive);
}

// ── 5. 边界：0 / 负 / 不足一旬 / 非整旬 floor ──────────────────────

TEST(OfflineInjectionTest, BoundaryInputsAreInertOrFloored) {
    auto core = makeCore(42);
    auto& st = core->state();
    core->advancePhases(3);
    const int64_t base = st.gameData.elapsedGameMs;
    const int64_t rngBefore = core->rngSnapshotPartition(kRngSystem);

    // 0 / 负：零副作用
    EXPECT_EQ(base, core->injectOfflineGameMs(0));
    EXPECT_EQ(base, core->injectOfflineGameMs(-4000));
    EXPECT_EQ(base, st.gameData.elapsedGameMs);

    // 不足一旬（<2000ms）：floor 后为 0 → 零副作用
    EXPECT_EQ(base, core->injectOfflineGameMs(1999));
    EXPECT_EQ(base, st.gameData.elapsedGameMs);

    // 非整旬输入：floor 到旬网格（1999ms 残留被防御性丢弃）
    const int64_t returned = core->injectOfflineGameMs(2 * kPhaseMs + 1999);
    EXPECT_EQ(base + 2 * kPhaseMs, returned);
    EXPECT_EQ(0, returned % kPhaseMs);

    EXPECT_EQ(rngBefore, core->rngSnapshotPartition(kRngSystem));
}

TEST(OfflineInjectionTest, InjectionBeforeInitializationIsInert) {
    // 未初始化 GameCore：注入返回 0 轴值且不崩溃（防御契约）
    GameCore rawCore(nullptr, nullptr);
    EXPECT_EQ(0, rawCore.injectOfflineGameMs(6000));
}

}  // namespace
