#include <gtest/gtest.h>

#include <algorithm>
#include <string>

#include <nlohmann/json.hpp>

#include "gamecore/game_core.h"
#include "gamecore/state/dirty_tracker.h"

namespace gamecore {

using state::DirtyTracker;
using state::Disciple;
using state::GameState;
using state::Pill;

namespace {

class DirtyTrackerTest : public ::testing::Test {
protected:
    FixedClock clock_;
    NullLogger logger_;

    static GameState sampleState() {
        GameState s;
        s.gameData.spiritStones = 1000;
        s.gameData.sectName = "青云宗";
        Pill p;
        p.id = "p-1";
        p.name = "聚气丹";
        p.quantity = 5;
        s.pills.push_back(p);
        return s;
    }
};

// ── DirtyTracker 单元测试 ─────────────────────────────────────────

TEST_F(DirtyTrackerTest, NoChangesProducesEmptyDiff) {
    DirtyTracker t;
    const GameState s = sampleState();
    t.resetBaseline(s);
    const auto j = nlohmann::json::parse(t.diffToJson(s));
    EXPECT_TRUE(j.at("changed").empty());
    EXPECT_TRUE(j.at("removed").empty());
    EXPECT_EQ(1u, j.at("version").get<uint64_t>());
}

TEST_F(DirtyTrackerTest, ScalarChangeReportedOnceThenClean) {
    DirtyTracker t;
    GameState s = sampleState();
    t.resetBaseline(s);
    s.gameData.spiritStones = 2500;
    const auto j = nlohmann::json::parse(t.diffToJson(s));
    // 只报变化字段，其余零噪音
    EXPECT_EQ(1u, j.at("changed").size());
    ASSERT_TRUE(j.at("changed").contains("gameData.spiritStones"));
    EXPECT_EQ(2500, j.at("changed").at("gameData.spiritStones").get<int64_t>());
    EXPECT_TRUE(j.at("removed").empty());
    // 基线已推进：再次 diff 为空
    const auto j2 = nlohmann::json::parse(t.diffToJson(s));
    EXPECT_TRUE(j2.at("changed").empty());
    EXPECT_EQ(2u, j2.at("version").get<uint64_t>());
}

TEST_F(DirtyTrackerTest, NestedContainerReplacedWholesale) {
    DirtyTracker t;
    GameState s = sampleState();
    t.resetBaseline(s);
    s.gameData.guideCounters["ftue_step"] = 3;
    const auto j = nlohmann::json::parse(t.diffToJson(s));
    ASSERT_TRUE(j.at("changed").contains("gameData.guideCounters"));
    EXPECT_EQ(1u, j.at("changed").at("gameData.guideCounters").size());
}

TEST_F(DirtyTrackerTest, IntegralDoubleNormalizedForKotlinx) {
    DirtyTracker t;
    GameState s = sampleState();
    t.resetBaseline(s);
    s.gameData.sectCultivation = 12000.0;  // 整数值 double → 必须输出为整数
    const auto j = nlohmann::json::parse(t.diffToJson(s));
    const auto& v = j.at("changed").at("gameData.sectCultivation");
    EXPECT_FALSE(v.is_number_float()) << "整数值 double 输出为 N.0 会被 kotlinx 流式解码器拒绝";
    EXPECT_EQ(12000, v.get<int64_t>());
}

TEST_F(DirtyTrackerTest, EntityUpsertAndRemoveTrackedById) {
    DirtyTracker t;
    GameState s = sampleState();
    t.resetBaseline(s);

    // 新增 + 修改 + 删除
    Pill added;
    added.id = "p-2";
    added.quantity = 1;
    s.pills.push_back(added);
    s.pills[0].quantity = 9;
    s.pills[0].isLocked = true;

    const auto j = nlohmann::json::parse(t.diffToJson(s));
    ASSERT_TRUE(j.at("changed").contains("pills"));
    // p-1（修改）与 p-2（新增）都按 id upsert
    EXPECT_EQ(2u, j.at("changed").at("pills").size());

    // 下一轮：删除 p-2
    GameState s2 = s;
    s2.pills.erase(
        std::remove_if(s2.pills.begin(), s2.pills.end(),
                       [](const Pill& p) { return p.id == "p-2"; }),
        s2.pills.end());
    const auto j2 = nlohmann::json::parse(t.diffToJson(s2));
    EXPECT_TRUE(j2.at("changed").empty());
    ASSERT_TRUE(j2.at("removed").contains("pills"));
    ASSERT_EQ(1u, j2.at("removed").at("pills").size());
    EXPECT_EQ("p-2", j2.at("removed").at("pills")[0].get<std::string>());
}

TEST_F(DirtyTrackerTest, DiscipleFieldChangeUpsertsWholeEntity) {
    DirtyTracker t;
    GameState s = sampleState();
    Disciple d;
    d.id = "d-1";
    d.cultivation = 1.0;
    s.disciples.appendDisciple(d);
    t.resetBaseline(s);

    s.disciples.cultivations[0] = 7.5;
    const auto j = nlohmann::json::parse(t.diffToJson(s));
    ASSERT_TRUE(j.at("changed").contains("disciples"));
    ASSERT_EQ(1u, j.at("changed").at("disciples").size());
    EXPECT_EQ(7.5, j.at("changed").at("disciples")[0].at("cultivation").get<double>());
}

TEST_F(DirtyTrackerTest, SyncBaselineToCurrentDoesNotBumpVersion) {
    // 反向增量（Kotlin → C++）应用后 syncBaselineToCurrent：基线推进但不递增
    // 版本——防反向应用值回流 forward 变更集（基线缓存实现回归守护：
    // sync 路径必须重建缓存树，与 resetBaseline 同语义）
    DirtyTracker t;
    GameState s = sampleState();
    t.resetBaseline(s);
    (void)t.diffToJson(s);  // v1（空）
    EXPECT_EQ(1u, t.version());

    s.gameData.spiritStones = 42;
    t.syncBaselineToCurrent(s);  // 反向应用后的基线同步（版本不动）
    const auto j = nlohmann::json::parse(t.diffToJson(s));
    EXPECT_TRUE(j.at("changed").empty());
    EXPECT_EQ(2u, j.at("version").get<uint64_t>());  // 仅本次 diff +1
}

// ── GameCore 集成（exportDirty / RNG 读档恢复） ─────────────

TEST_F(DirtyTrackerTest, GameCoreDirtyEmptyAfterInitAndAfterFullExport) {
    GameCore core(&clock_, &logger_);
    GameCoreConfig config;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));

    const auto j = nlohmann::json::parse(core.exportDirtyJson());
    EXPECT_TRUE(j.at("changed").empty());
    EXPECT_TRUE(j.at("removed").empty());

    // 全量导出重置基线 → 变更集仍为空
    ASSERT_TRUE(core.exportStateJson() != "{}");
    const auto j2 = nlohmann::json::parse(core.exportDirtyJson());
    EXPECT_TRUE(j2.at("changed").empty());
}

TEST_F(DirtyTrackerTest, GameCoreDirtyReportsAdvanceThenClearsOnFullExport) {
    GameCore core(&clock_, &logger_);
    GameCoreConfig config;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));

    // 全量导出建立基线 → 推进产生变更集
    ASSERT_TRUE(core.exportStateJson() != "{}");
    ASSERT_GT(core.advancePhases(5).phasesAdvanced, 0);
    // 显式消耗一次 RNG（时间推进本身不消耗）——rngStates 变化必须进变更集
    core.rng().getRng(rng::RngPartition::kBattle).nextInt();

    const auto dirty = nlohmann::json::parse(core.exportDirtyJson());
    EXPECT_FALSE(dirty.at("changed").empty())
        << "时间推进后变更集必须非空（至少含 gameData 时间字段）";
    EXPECT_TRUE(dirty.at("changed").contains("gameData.rngStates"))
        << "RNG 推进后 rngStates 必须进入变更集（镜像侧确定性依赖）";

    // 全量导出后基线重置 → 变更集为空
    ASSERT_TRUE(core.exportStateJson() != "{}");
    const auto clean = nlohmann::json::parse(core.exportDirtyJson());
    EXPECT_TRUE(clean.at("changed").empty());
}

TEST_F(DirtyTrackerTest, ImportRestoresRngPartitionStates) {
    // 回归守护："存档→读档→继续"与不中断的随机序列逐位一致
    //（importStateJson 必须恢复 rngStates，否则读档后从种子态重新开始）。
    GameCoreConfig config;
    config.systemSeed = 42;
    config.seedInitialized = true;

    GameCore source(&clock_, &logger_);
    ASSERT_TRUE(source.initialize(config));
    source.rng().getRng(rng::RngPartition::kSystem).nextInt();
    source.rng().getRng(rng::RngPartition::kSystem).nextInt();

    // 先导出快照（rngStates 已随导出同步），再取源侧下一次抽取作为期望值
    const std::string snapshot = source.exportStateJson();
    const int32_t expectedNext = source.rng().getRng(rng::RngPartition::kSystem).nextInt();

    GameCore restored(&clock_, &logger_);
    ASSERT_TRUE(restored.initialize(config));
    ASSERT_TRUE(restored.importStateJson(snapshot));
    EXPECT_EQ(expectedNext,
              restored.rng().getRng(rng::RngPartition::kSystem).nextInt())
        << "读档后 RNG 分区状态未恢复（C-13）——后续推演将与存档前不一致";
}

}  // namespace
}  // namespace gamecore
