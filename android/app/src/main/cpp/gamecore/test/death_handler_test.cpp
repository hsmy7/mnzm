#include <gtest/gtest.h>

#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/death_handler.h"

namespace gamecore::system {
namespace {

using gamecore::state::Disciple;
using gamecore::state::DiscipleStore;

/// 构造最小弟子（其余列走默认值；仅标记相关字段有意义）
Disciple makeDisciple(const std::string& id, bool alive = true) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.isAlive = alive;
    d.currentHp = 100;
    return d;
}

DiscipleStore makeStore(const std::vector<Disciple>& list) {
    DiscipleStore store;
    store.loadFromVector(list);
    return store;
}

std::size_t rowOf(const DiscipleStore& store, const std::string& id) {
    return *store.rowOf(id);
}

// ── G07：markDead = 重伤（HP=1 存活，不计年报死亡） ──────────────

TEST(DeathHandlerTest, MarkDeadWritesInjuryKeepsAlive) {
    auto store = makeStore({makeDisciple("1"), makeDisciple("2")});
    int32_t deceased = 0;
    const auto r = markDead(store, "1", 10, deceased);
    EXPECT_TRUE(r.marked);
    EXPECT_EQ(store.currentHps[rowOf(store, "1")], kInjuredHp);
    EXPECT_EQ(store.isAlive[rowOf(store, "1")], 1);
    EXPECT_NE(store.statuses[rowOf(store, "1")], kDeadStatusName);
    EXPECT_EQ(store.deathYears[rowOf(store, "1")], kDeathYearNone);
    EXPECT_EQ(deceased, 0);  // 重伤不计年报死亡
    EXPECT_EQ(store.isAlive[rowOf(store, "2")], 1);
    EXPECT_EQ(store.currentHps[rowOf(store, "2")], 100);
}

TEST(DeathHandlerTest, MarkDeadDoesNotIncrementCount) {
    auto store = makeStore({makeDisciple("1"), makeDisciple("2")});
    int32_t deceased = 5;
    markDead(store, "1", 10, deceased);
    markDead(store, "2", 10, deceased);
    EXPECT_EQ(deceased, 5);
}

TEST(DeathHandlerTest, MarkDeadRestoresAliveIfPreMarked) {
    auto store = makeStore({makeDisciple("1", /*alive=*/false)});
    store.isAlive[rowOf(store, "1")] = 0;
    int32_t deceased = 0;
    EXPECT_TRUE(markDead(store, "1", 10, deceased).marked);
    EXPECT_EQ(store.isAlive[rowOf(store, "1")], 1);
    EXPECT_EQ(store.currentHps[rowOf(store, "1")], kInjuredHp);
}

TEST(DeathHandlerTest, MarkDeadMissingDiscipleSkips) {
    auto store = makeStore({makeDisciple("1")});
    int32_t deceased = 0;
    const auto r = markDead(store, "999", 10, deceased);
    EXPECT_FALSE(r.marked);
    EXPECT_EQ(deceased, 0);
    EXPECT_EQ(store.size(), 1);
}

TEST(DeathHandlerTest, MarkAllDeadInjuresParseableIds) {
    auto store = makeStore({makeDisciple("1"), makeDisciple("2"), makeDisciple("3")});
    int32_t deceased = 0;
    const int32_t count = markAllDead(store, {"1", "bad", "3"}, 7, deceased);
    EXPECT_EQ(count, 2);
    EXPECT_EQ(deceased, 0);
    EXPECT_EQ(store.currentHps[rowOf(store, "1")], kInjuredHp);
    EXPECT_EQ(store.currentHps[rowOf(store, "3")], kInjuredHp);
    EXPECT_EQ(store.isAlive[rowOf(store, "2")], 1);
}

TEST(DeathHandlerTest, BackfillDeathYearsOnlyForAlreadyDead) {
    auto store = makeStore({
        makeDisciple("1", false),
        makeDisciple("2", false),
        makeDisciple("3", true),
    });
    store.deathYears[rowOf(store, "2")] = 5;
    std::vector<Disciple> list;
    list.push_back(store.materialize(rowOf(store, "1")));
    list.push_back(store.materialize(rowOf(store, "2")));
    list.push_back(store.materialize(rowOf(store, "3")));
    EXPECT_EQ(backfillDeathYears(store, list, 10), 1);
    EXPECT_EQ(store.deathYears[rowOf(store, "1")], 10);
    EXPECT_EQ(store.deathYears[rowOf(store, "2")], 5);
    EXPECT_EQ(store.deathYears[rowOf(store, "3")], kDeathYearNone);
}

}  // namespace
}  // namespace gamecore::system
