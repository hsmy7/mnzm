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

// ── markDead：三字段写入 + 计数 ────────────────────────────────

TEST(DeathHandlerTest, MarkDeadWritesThreeFields) {
    auto store = makeStore({makeDisciple("1"), makeDisciple("2")});
    int32_t deceased = 0;
    const auto r = markDead(store, "1", 10, deceased);
    EXPECT_TRUE(r.marked);
    EXPECT_FALSE(r.hadEquipment);
    EXPECT_EQ(store.isAlive[rowOf(store, "1")], 0);
    EXPECT_EQ(store.statuses[rowOf(store, "1")], kDeadStatusName);
    EXPECT_EQ(store.deathYears[rowOf(store, "1")], 10);
    EXPECT_EQ(deceased, 1);
    // 其他弟子不受影响
    EXPECT_EQ(store.isAlive[rowOf(store, "2")], 1);
    EXPECT_EQ(store.deathYears[rowOf(store, "2")], kDeathYearNone);
}

TEST(DeathHandlerTest, MarkDeadIncrementsCountUnconditionally) {
    auto store = makeStore({makeDisciple("1"), makeDisciple("2")});
    int32_t deceased = 5;
    markDead(store, "1", 10, deceased);
    markDead(store, "2", 10, deceased);
    EXPECT_EQ(deceased, 7);  // 无条件计数（洞府预标记路径不漏计）
}

TEST(DeathHandlerTest, MarkDeadWithEquipmentFlags) {
    auto store = makeStore({makeDisciple("1"), makeDisciple("2"), makeDisciple("3")});
    store.weaponIds[rowOf(store, "1")] = "w1";
    store.accessoryIds[rowOf(store, "2")] = "a2";
    int32_t deceased = 0;
    EXPECT_TRUE(markDead(store, "1", 10, deceased).hadEquipment);
    EXPECT_TRUE(markDead(store, "2", 10, deceased).hadEquipment);
    EXPECT_FALSE(markDead(store, "3", 10, deceased).hadEquipment);
    // 装备断言不阻断标记
    EXPECT_EQ(store.deathYears[rowOf(store, "1")], 10);
}

TEST(DeathHandlerTest, MarkDeadMissingDiscipleSkips) {
    auto store = makeStore({makeDisciple("1")});
    int32_t deceased = 0;
    const auto r = markDead(store, "999", 10, deceased);
    EXPECT_FALSE(r.marked);
    EXPECT_EQ(deceased, 0);
    EXPECT_EQ(store.size(), 1);
    EXPECT_EQ(store.deathYears[rowOf(store, "1")], kDeathYearNone);
}

// ── markAllDead：批量 ──────────────────────────────────────────

TEST(DeathHandlerTest, MarkAllDeadBatch) {
    auto store = makeStore({makeDisciple("1"), makeDisciple("2"), makeDisciple("3")});
    int32_t deceased = 0;
    const int32_t count = markAllDead(store, {"1", "3", "999"}, 7, deceased);
    EXPECT_EQ(count, 2);        // 不存在的 id 静默跳过
    EXPECT_EQ(deceased, 2);
    EXPECT_EQ(store.deathYears[rowOf(store, "1")], 7);
    EXPECT_EQ(store.deathYears[rowOf(store, "3")], 7);
    EXPECT_EQ(store.deathYears[rowOf(store, "2")], kDeathYearNone);
}

// ── backfillDeathYears：补写不覆盖 ─────────────────────────────

TEST(DeathHandlerTest, BackfillDeathYearsFillsMissingOnly) {
    auto store = makeStore({makeDisciple("1", /*alive=*/false),
                            makeDisciple("2", /*alive=*/false),
                            makeDisciple("3", /*alive=*/true)});
    int32_t deceased = 0;
    markDead(store, "2", 5, deceased);  // "2" 已有记录
    const std::vector<Disciple> list = {
        makeDisciple("1", /*alive=*/false),
        makeDisciple("2", /*alive=*/false),
        makeDisciple("3", /*alive=*/true),
    };
    const int32_t count = backfillDeathYears(store, list, 10);
    EXPECT_EQ(count, 1);
    EXPECT_EQ(store.deathYears[rowOf(store, "1")], 10);   // 缺失补写
    EXPECT_EQ(store.deathYears[rowOf(store, "2")], 5);    // 已有记录不覆盖
    EXPECT_EQ(store.deathYears[rowOf(store, "3")], kDeathYearNone);  // 存活跳过
}

TEST(DeathHandlerTest, BackfillDeathYearsSkipsAliveListEntries) {
    auto store = makeStore({makeDisciple("1", /*alive=*/false)});
    // 快照列表中的存活条目不得触发补写
    const std::vector<Disciple> list = {makeDisciple("1", /*alive=*/true)};
    EXPECT_EQ(backfillDeathYears(store, list, 10), 0);
    EXPECT_EQ(store.deathYears[rowOf(store, "1")], kDeathYearNone);
}

// ── deathYears 列同步（append/erase/swap 一致性） ──────────────

TEST(DeathHandlerTest, DeathYearsColumnSynchronizedOnRemove) {
    auto store = makeStore({makeDisciple("1"), makeDisciple("2"), makeDisciple("3")});
    int32_t deceased = 0;
    markDead(store, "2", 9, deceased);
    store.removeById("2");
    EXPECT_EQ(store.size(), 2);
    EXPECT_EQ(store.deathYears.size(), 2);  // 列长随 erase 同步
    EXPECT_EQ(store.deathYears[rowOf(store, "1")], kDeathYearNone);
    EXPECT_EQ(store.deathYears[rowOf(store, "3")], kDeathYearNone);
}

TEST(DeathHandlerTest, UpsertPreservesDeathYears) {
    auto store = makeStore({makeDisciple("1"), makeDisciple("2")});
    int32_t deceased = 0;
    markDead(store, "1", 12, deceased);
    Disciple updated = makeDisciple("1");  // alive=true：列表快照值覆盖 isAlive（Kotlin replaceAll 同语义）
    updated.name = "新名字";
    store.upsertDisciple(updated);  // 原位覆盖（Kotlin replaceAll 恢复语义：仅 deathYears 单独保留）
    EXPECT_EQ(store.size(), 2);
    EXPECT_EQ(store.deathYears[rowOf(store, "1")], 12);
    EXPECT_EQ(store.isAlive[rowOf(store, "1")], 1);  // isAlive 按列表值覆盖（非 deathYears 通道）
}

TEST(DeathHandlerTest, DeathYearsColumnSynchronizedOnUpsertSwap) {
    // 旋转路径：upsert 中段行 → 新行从末尾旋转回原位，deathYears 跟随行
    auto store = makeStore({makeDisciple("1"), makeDisciple("2"), makeDisciple("3")});
    int32_t deceased = 0;
    markDead(store, "2", 8, deceased);
    Disciple updated = makeDisciple("2");
    updated.name = "改名";
    store.upsertDisciple(updated);
    EXPECT_EQ(store.deathYears[rowOf(store, "2")], 8);   // swap 同步保行
    EXPECT_EQ(store.deathYears[rowOf(store, "1")], kDeathYearNone);
    EXPECT_EQ(store.deathYears[rowOf(store, "3")], kDeathYearNone);
}

}  // namespace
}  // namespace gamecore::system
