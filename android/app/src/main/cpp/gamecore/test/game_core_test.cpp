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
    // 批次 9：未注册动作 → NOT_IMPLEMENTED
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

    // 变更集/事件仍为骨架形状
    EXPECT_NE(std::string::npos, core.exportDirtyJson().find("\"version\""));
    EXPECT_EQ("[]", core.pollEventsJson());
}

}  // namespace
}  // namespace gamecore
