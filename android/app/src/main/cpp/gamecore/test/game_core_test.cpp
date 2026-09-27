#include <gtest/gtest.h>

#include "gamecore/game_core.h"

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
    // 单一时速语义：2000ms 墙钟 → 恰好 1 旬（msPerPhase=2000ms）
    EXPECT_TRUE(core.advance(2000L, clock_.nowMs()));
    EXPECT_EQ(1, core.state().gameData.gamePhase);
    // 超大 delta 被单 tick 上限 3 旬截断（100s 原计划 50 旬 → 恰 3 旬，
    // 中旬起步跨月进位一次：1年1月中旬 → 1年2月中旬）
    EXPECT_TRUE(core.advance(100'000'000L, clock_.nowMs()));
    EXPECT_EQ(2, core.state().gameData.gameMonth);
    EXPECT_EQ(1, core.state().gameData.gamePhase);
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

}  // namespace
}  // namespace gamecore
