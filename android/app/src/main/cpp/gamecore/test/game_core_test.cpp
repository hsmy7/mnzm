#include <gtest/gtest.h>

#include "gamecore/game_core.h"
#include "gamecore/system/time_system.h"

namespace gamecore {
namespace {

class GameCoreTest : public ::testing::Test {
protected:
    void SetUp() override {
        clock_.setNowMs(1'700'000'000'000L);
    }

    FixedClock clock_;
    NullLogger logger_;
};

TEST_F(GameCoreTest, InitAndShutdown) {
    GameCore core(&clock_, &logger_);
    GameCoreConfig config;
    config.systemSeed = 42;
    config.seedInitialized = true;

    EXPECT_FALSE(core.isInitialized());
    EXPECT_TRUE(core.initialize(config));
    EXPECT_TRUE(core.isInitialized());

    // 重复初始化拒绝
    EXPECT_FALSE(core.initialize(config));

    core.shutdown();
    EXPECT_FALSE(core.isInitialized());
    // 幂等关闭
    core.shutdown();
}

TEST_F(GameCoreTest, AdvanceRequiresInit) {
    GameCore core(&clock_, &logger_);
    EXPECT_FALSE(core.advance(100'000'000L, clock_.nowMs()));
}

TEST_F(GameCoreTest, AdvanceWorksWhenInitialized) {
    GameCore core(&clock_, &logger_);
    GameCoreConfig config;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));
    EXPECT_TRUE(core.advance(100'000'000L, clock_.nowMs()));
}

TEST_F(GameCoreTest, ExecuteBeforeInitReturnsFailure) {
    GameCore core(&clock_, &logger_);
    const auto result = core.execute(1000, "{}", clock_.nowMs());
    EXPECT_NE(std::string::npos, result.find("kInternal"));
}

TEST_F(GameCoreTest, ExecuteNotImplementedReturnsFailure) {
    GameCore core(&clock_, &logger_);
    GameCoreConfig config;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));
    // 未注册动作 → NOT_IMPLEMENTED
    const auto result = core.execute(999999, "{}", clock_.nowMs());
    EXPECT_NE(std::string::npos, result.find("NOT_IMPLEMENTED"));
}

TEST_F(GameCoreTest, RngSeededFromConfig) {
    GameCore core(&clock_, &logger_);
    GameCoreConfig config;
    config.systemSeed = 42;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));

    // 与 RngManager 直接播种同种子对比（分区 BATTLE 种子 = 42+0）
    rng::RngManager ref;
    ref.initSystemSeed(42);
    EXPECT_EQ(core.rng().getRng(rng::RngPartition::kBattle).nextInt(100),
              ref.getRng(rng::RngPartition::kBattle).nextInt(100));
}

TEST_F(GameCoreTest, SnapshotImportExportRoundTrip) {
    GameCore core(&clock_, &logger_);
    GameCoreConfig config;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));

    // 未初始化时导出为空对象
    GameCore uninit(&clock_, &logger_);
    EXPECT_EQ("{}", uninit.exportStateJson());
    EXPECT_FALSE(uninit.importStateJson("{}"));

    // 已初始化：导入合法快照成功，导出包含 gameData
    const std::string snapshot = R"({"gameData":{"gameYear":5,"spiritStones":777}})";
    EXPECT_TRUE(core.importStateJson(snapshot));
    const auto exported = core.exportStateJson();
    EXPECT_NE(std::string::npos, exported.find("\"gameData\""));
    EXPECT_NE(std::string::npos, exported.find("\"gameYear\":5"));

    // 非法 JSON 导入失败且不破坏状态
    EXPECT_FALSE(core.importStateJson("{not valid json"));
    EXPECT_NE(std::string::npos, core.exportStateJson().find("\"gameYear\":5"));

    // 变更集仍为骨架形状
    EXPECT_NE(std::string::npos, core.exportDirtyJson().find("\"version\""));
}

// ── 双轨时间权威轴回填（结算改造 2026-09-27 B3，方案 §4.1）─────────────

// 旧档（无 elapsedGameMs 键）：导入即按日历回填权威轴 + 槽位毫秒孪生；
// 新档口径（有 elapsedGameMs 键）：有值不覆盖（幂等）
TEST_F(GameCoreTest, LegacyImportBackfillsBaselineTimeAxis) {
    GameCore core(&clock_, &logger_);
    GameCoreConfig config;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));

    // 旧档形态：只有日历与槽位旧字段（12 年 7 月中旬 = calendarToGameMs(12,7,1)）
    const std::string legacy =
        R"({"gameData":{"gameYear":12,"gameMonth":7,"gamePhase":1,)"
        R"("spiritMineLastSettledMonth":145,)"
        R"("productionSlots":[{"id":"s1","slotIndex":0,"buildingType":"ALCHEMY",)"
        R"("buildingId":"alchemy","status":"WORKING","startYear":3,"startMonth":2,)"
        R"("duration":5,"completionMonth":44,"completionPhase":1}]}})";
    ASSERT_TRUE(core.importStateJson(legacy));

    // 导入后镜像立即可见（回填发生在 resetBaseline 之前 ⇒ 前向镜像零载荷）
    core.importStateJson(legacy);   // 二次导入幂等（有值不覆盖）
    const auto exported = core.exportStateJson();
    const int64_t expectedAxis = system::calendarToGameMs(12, 7, 1);
    EXPECT_NE(std::string::npos,
              exported.find("\"elapsedGameMs\":" + std::to_string(expectedAxis)));
    EXPECT_NE(std::string::npos,
              exported.find("\"lastSettleGameMs\":" + std::to_string(expectedAxis)));

    // 灵矿孪生：绝对月 145（口径 year*12+month）⇒ (145-1)/12=12 年 + (145-1)%12+1=1 月
    const int64_t expectedMine = system::calendarToGameMs(12, 1, 0);
    EXPECT_NE(std::string::npos,
              exported.find("\"spiritMineLastSettledGameMs\":" + std::to_string(expectedMine)));

    // 槽位：startedAt = calendarToGameMs(3,2,0)；completeAt = started + 5×6000ms
    const int64_t expectedStart = system::calendarToGameMs(3, 2, 0);
    EXPECT_NE(std::string::npos,
              exported.find("\"startedAtGameMs\":" + std::to_string(expectedStart)));
    EXPECT_NE(std::string::npos,
              exported.find("\"completeAtGameMs\":" +
                            std::to_string(expectedStart + 5 * 6000)));
}

// 新档口径：快照带权威轴值 ⇒ 导入不覆盖（有值恒优先）
TEST_F(GameCoreTest, ImportKeepsExistingTimeAxis) {
    GameCore core(&clock_, &logger_);
    GameCoreConfig config;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));

    const std::string snapshot =
        R"({"gameData":{"gameYear":2,"gameMonth":1,"gamePhase":0,)"
        R"("elapsedGameMs":72500,"lastSettleGameMs":72000}})";
    ASSERT_TRUE(core.importStateJson(snapshot));
    const auto exported = core.exportStateJson();
    EXPECT_NE(std::string::npos, exported.find("\"elapsedGameMs\":72500"));
    EXPECT_NE(std::string::npos, exported.find("\"lastSettleGameMs\":72000"));
}

}  // namespace
}  // namespace gamecore
