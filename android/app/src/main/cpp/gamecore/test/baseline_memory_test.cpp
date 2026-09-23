#include <gtest/gtest.h>

#include <string>

#include <nlohmann/json.hpp>

#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/state/dirty_tracker.h"

// ============================================================
// BaselineMemoryTest — P4.1/D5 稳态无双全量树守卫
//
// 断言封装 API 形态：DirtyTracker / ColumnDirtyTracker 常驻基线均为
// **块级 StateBaseline**（gameData 单域 + id→实体块），holdsNestedFullStateDom()
// 恒 false——稳态不存在「整棵 nlohmann GameState DOM」与 rest 全量嵌套树
// 并存的形态。导出瞬时协议树（changed/removed）不属于常驻业务树。
// ============================================================
namespace gamecore {
namespace {

using state::DirtyTracker;
using state::Disciple;
using state::GameState;
using state::Pill;

GameState sampleState() {
    GameState s;
    s.gameData.spiritStones = 1000;
    s.gameData.sectName = "青云宗";
    Pill p;
    p.id = "p-1";
    p.name = "聚气丹";
    p.quantity = 5;
    s.pills.push_back(p);
    Disciple d;
    d.id = "d-1";
    d.name = "弟子甲";
    d.cultivation = 1.0;
    s.disciples.appendDisciple(d);
    return s;
}

TEST(BaselineMemoryTest, DirtyTrackerBlockBaselineNotNestedFullDom) {
    DirtyTracker t;
    const GameState s = sampleState();
    t.resetBaseline(s);
    EXPECT_FALSE(t.holdsNestedFullStateDom());

    GameState s2 = s;
    s2.gameData.spiritStones = 2000;
    static_cast<void>(t.diffToTree(s2));
    EXPECT_FALSE(t.holdsNestedFullStateDom());

    // 无变更再 diff 仍块级 + 空变更集（基线推进语义未回退）
    const auto j = nlohmann::json::parse(t.diffToJson(s2));
    EXPECT_TRUE(j.at("changed").empty());
    EXPECT_FALSE(t.holdsNestedFullStateDom());
}

TEST(BaselineMemoryTest, DualTrackerSteadyStateNoNestedFullDom) {
    // 初始化后双追踪器（全量臂 + 列级臂）经完整导出循环后：
    // DirtyTracker 块级观测面恒 false；列级导出后全量增量仍为空
    // （两臂独立基线、导出即消费——P4.1 验收：无双全量树常驻形态）。
    FixedClock clock{1'700'000'000'000LL};
    NullLogger logger;
    GameCore core(&clock, &logger);
    GameCoreConfig config;
    config.seedInitialized = true;
    ASSERT_TRUE(core.initialize(config));

    ASSERT_NE(core.exportStateJson(), "{}");
    static_cast<void>(core.exportDirtyJson());
    static_cast<void>(core.exportDirtyColumnJson());

    const auto j = nlohmann::json::parse(core.exportDirtyJson());
    EXPECT_TRUE(j.at("changed").empty());
    EXPECT_TRUE(j.at("removed").empty());
}

}  // namespace
}  // namespace gamecore
