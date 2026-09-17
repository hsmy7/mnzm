#include <gtest/gtest.h>

#include <set>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/state/column_dirty.h"
#include "gamecore/state/dirty_tracker.h"

// ============================================================
// column_dirty_test — R1.4 列级写屏障守卫
//
// 守护目标（重构方案 R1.4：各 SoA 列 dirty 位图 + 集合 tombstone，
// 导出仅序列化脏列；全量树 diff（DirtyTracker）保持为对拍显式依赖的
// 全量模式，本层为显式 opt-in 的列级模式，两者互补不互替）：
//   1. 列枚举 ↔ Disciple to_json 协议字段双射（新增列不同步扩展即红）；
//   2. 写屏障挂点（append/eraseAt 行位移/swapRows 旋转/clear）标脏正确；
//   3. 导出仅含脏行 × 脏列（id 键恒携带）、tombstone/gameData 域粒度；
//   4. 导出即消费 + 版本单调；tombstone 撤销规则（upsert 旋转不复删）；
//   5. 与全量树 diff（DirtyTracker）脚本化变更序列的等价性
//      （changed 键集相等 + removed 逐位一致 + 共享键值相等）。
// ============================================================

namespace gamecore {
namespace {

using state::ColumnDirtyTracker;
using state::Disciple;
using state::DiscipleColumn;
using state::DiscipleStore;
using state::DirtyTracker;
using state::GameData;
using state::GameState;

using nlohmann::json;

/// 最小弟子（字段值带区分度，供列级导出断言）
Disciple makeDisciple(const std::string& id, const std::string& name) {
    Disciple d;
    d.id = id;
    d.name = name;
    d.realm = 9;
    d.realmLayer = 1;
    d.cultivation = 10.0;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.talentIds = {"t1"};
    return d;
}

/// 挂载追踪器的 store + tracker 对（含 gameData 取值源与导出助手）
struct AttachedStore {
    DiscipleStore store;
    GameData gameData;
    ColumnDirtyTracker tracker;

    AttachedStore() { store.attachColumnDirtyTracker(&tracker); }

    json exportJson() {
        return json::parse(tracker.exportDirtyJson(store, gameData));
    }
};

// ── 守卫 1：列枚举 ↔ 协议字段双射 ────────────────────────────────

TEST(ColumnDirtyTest, ColumnEnumBijectionWithProtocolFields) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("1", "甲"));

    const json full = store.materialize(0);  // Disciple to_json（ADL）
    std::set<std::string> protocolFields;
    for (auto it = full.begin(); it != full.end(); ++it) {
        protocolFields.insert(it.key());
    }

    // 枚举 → 协议字段：每个枚举列名必须在 to_json 键集中，且无重复
    std::set<std::string> enumNames;
    for (uint16_t c = 0; c < state::kDiscipleColumnCount; ++c) {
        const auto col = static_cast<DiscipleColumn>(c);
        const char* name = state::discipleColumnName(col);
        ASSERT_NE(nullptr, name) << "列 " << c << " 缺协议字段名";
        EXPECT_TRUE(protocolFields.count(name) > 0)
            << "列枚举字段 " << name << " 不在 Disciple to_json 键集中"
            << "（新增/更名列时须同步 column_dirty.h 枚举与序列化）";
        EXPECT_TRUE(enumNames.insert(name).second) << "列名重复：" << name;
    }

    // 协议字段 → 枚举：唯一无列支撑的协议字段 = deathYear（Disciple 结构
    // 字段，物化路径不回填——非 SoA 列，见 disciple_store.h deathYears 注释）
    std::set<std::string> expected(protocolFields);
    expected.erase("deathYear");
    EXPECT_EQ(expected, enumNames);
}

// ── 守卫 2/3：写屏障挂点与列级导出 ──────────────────────────────

TEST(ColumnDirtyTest, SingleColumnMarkExportsOnlyThatColumnPlusIdKey) {
    AttachedStore s;
    s.store.appendDisciple(makeDisciple("1", "甲"));
    s.tracker.resetBaseline();

    s.store.cultivations[0] = 99.0;  // 直写（挂点外——写点标脏为 R2 接线）
    s.tracker.markColumn(DiscipleColumn::Cultivation, 0);

    const json out = s.exportJson();
    const json& upserts = out.at("changed").at("disciples");
    ASSERT_EQ(1u, upserts.size());
    EXPECT_EQ("1", upserts.at(0).at("id").get<std::string>());
    // 仅 id 键 + 脏列两个字段
    EXPECT_EQ(2u, upserts.at(0).size());
    EXPECT_DOUBLE_EQ(99.0, upserts.at(0).at("cultivation").get<double>());
}

TEST(ColumnDirtyTest, AppendMarksNewRowAllColumnsFullEntity) {
    AttachedStore s;
    s.tracker.resetBaseline();
    const Disciple d = makeDisciple("7", "乙");
    s.store.appendDisciple(d);

    const json out = s.exportJson();
    const json& upserts = out.at("changed").at("disciples");
    ASSERT_EQ(1u, upserts.size());

    // 全列标脏 ⇒ 导出行 == 物化全实体（唯一豁免：deathYear 无列支撑）
    json expected = s.store.materialize(0);
    expected.erase("deathYear");
    EXPECT_EQ(expected, upserts.at(0));
}

TEST(ColumnDirtyTest, RemoveByIdProducesTombstoneInRemoved) {
    AttachedStore s;
    s.store.appendDisciple(makeDisciple("1", "甲"));
    s.store.appendDisciple(makeDisciple("2", "乙"));
    s.tracker.resetBaseline();

    s.store.removeById("2");  // 删末行：无位移段 ⇒ changed 空、removed 记账
                              // （中间行删除的位移标脏见 EraseShiftMarks… 用例）

    const json out = s.exportJson();
    EXPECT_TRUE(out.at("changed").empty());
    const json& removed = out.at("removed").at("disciples");
    ASSERT_EQ(1u, removed.size());
    EXPECT_EQ("2", removed.at(0).get<std::string>());
}

TEST(ColumnDirtyTest, EraseShiftMarksShiftedRowsConservatively) {
    AttachedStore s;
    for (int i = 1; i <= 4; ++i) {
        s.store.appendDisciple(makeDisciple(std::to_string(i), "甲" + std::to_string(i)));
    }
    s.tracker.resetBaseline();

    s.store.removeById("2");  // 行 1 删除 ⇒ 行 1..2（原行 2..3）内容左移

    const json out = s.exportJson();
    // 保守多报：位移段整行重发（值 = 当前店内容），removed 携带被删 id
    const json& upserts = out.at("changed").at("disciples");
    ASSERT_EQ(2u, upserts.size());
    EXPECT_EQ("3", upserts.at(0).at("id").get<std::string>());
    EXPECT_EQ("4", upserts.at(1).at("id").get<std::string>());
    EXPECT_EQ("2", out.at("removed").at("disciples").at(0).get<std::string>());
    // 值随当前行：原行 3（现行 1）= upserts.at(0)，逐字段一致
    // （deathYear 无列支撑，豁免比对——口径同 AppendMarksNewRowAllColumnsFullEntity）
    json expectedShifted = s.store.materialize(1);
    expectedShifted.erase("deathYear");
    EXPECT_EQ(expectedShifted, upserts.at(0));
}

TEST(ColumnDirtyTest, UpsertRotationEmitsUpsertWithoutTombstone) {
    AttachedStore s;
    s.store.appendDisciple(makeDisciple("1", "甲"));
    s.store.appendDisciple(makeDisciple("2", "乙"));
    s.tracker.resetBaseline();

    Disciple changed = makeDisciple("1", "甲改");
    changed.realm = 8;
    s.store.upsertDisciple(changed);  // 保序旋转：删→追→旋回行 0

    const json out = s.exportJson();
    // 同 id 删→回 ⇒ 撤销 removed、行内容由行级脏标记承载
    EXPECT_TRUE(out.at("removed").empty());
    const json& upserts = out.at("changed").at("disciples");
    // 行 0 = "1" 全列标脏；被位移的行 "2" 保守多报（幂等应用安全）
    const json* row1 = nullptr;
    for (const auto& r : upserts) {
        if (r.at("id") == "1") row1 = &r;
    }
    ASSERT_NE(row1, nullptr) << "变更行缺 id=1";
    EXPECT_EQ("甲改", row1->at("name").get<std::string>());
    EXPECT_EQ(8, row1->at("realm").get<int32_t>());
}

TEST(ColumnDirtyTest, UpsertWithIdenticalValuesStillUpsertsConservatively) {
    AttachedStore s;
    s.store.appendDisciple(makeDisciple("1", "甲"));
    s.tracker.resetBaseline();

    s.store.upsertDisciple(makeDisciple("1", "甲"));  // 值相同——屏障无值感知

    const json out = s.exportJson();
    const json& upserts = out.at("changed").at("disciples");
    ASSERT_EQ(1u, upserts.size());
    // 保守多报（幂等应用安全）：值与当前店内容一致
    json expected = s.store.materialize(0);
    expected.erase("deathYear");
    EXPECT_EQ(expected, upserts.at(0));
}

TEST(ColumnDirtyTest, ClearTombstonesAllIds) {
    AttachedStore s;
    s.store.appendDisciple(makeDisciple("1", "甲"));
    s.store.appendDisciple(makeDisciple("2", "乙"));
    s.tracker.resetBaseline();

    s.store.clear();

    const json out = s.exportJson();
    EXPECT_TRUE(out.at("changed").empty());
    const json& removed = out.at("removed").at("disciples");
    ASSERT_EQ(2u, removed.size());
    EXPECT_EQ("1", removed.at(0).get<std::string>());
    EXPECT_EQ("2", removed.at(1).get<std::string>());
}

TEST(ColumnDirtyTest, GameDataFieldMarkExportsOnlyMarkedFields) {
    AttachedStore s;
    s.gameData.spiritStones = 1000;
    s.gameData.sectName = "青云宗";
    s.tracker.resetBaseline();

    s.gameData.spiritStones = 2500;
    s.tracker.markGameDataField("spiritStones");

    const json out = s.exportJson();
    ASSERT_EQ(1u, out.at("changed").size());
    EXPECT_EQ(2500, out.at("changed").at("gameData.spiritStones").get<int64_t>());
    EXPECT_TRUE(out.at("removed").empty());
}

TEST(ColumnDirtyTest, ExportConsumesMarksAndVersionIsMonotonic) {
    AttachedStore s;
    s.store.appendDisciple(makeDisciple("1", "甲"));
    s.tracker.resetBaseline();
    ASSERT_EQ(0u, s.tracker.version());

    s.tracker.markColumn(DiscipleColumn::Age, 0);
    static_cast<void>(s.tracker.exportDirtyJson(s.store, s.gameData));
    EXPECT_EQ(1u, s.tracker.version());

    // 导出即消费：无新变更 ⇒ 空变更集，版本仍单调
    const json second = s.exportJson();
    EXPECT_TRUE(second.at("changed").empty());
    EXPECT_TRUE(second.at("removed").empty());
    EXPECT_EQ(2u, second.at("version").get<uint64_t>());
}

// ── 守卫 4：与全量树 diff 的脚本化等价 ──────────────────────────

TEST(ColumnDirtyTest, ScriptedMutationSequenceMatchesTreeDiff) {
    GameState state;
    ColumnDirtyTracker columnTracker;
    state.disciples.attachColumnDirtyTracker(&columnTracker);
    DirtyTracker treeTracker;

    Disciple a = makeDisciple("1", "甲");
    a.age = 20;
    Disciple b = makeDisciple("2", "乙");
    b.cultivation = 30.0;
    state.disciples.appendDisciple(a);
    state.disciples.appendDisciple(b);
    state.disciples.appendDisciple(makeDisciple("3", "丙"));
    treeTracker.resetBaseline(state);
    columnTracker.resetBaseline();

    // 脚本化变更序列（固定确定性——不用 RNG）：改值/旋转/删/增/域标脏
    state.disciples.cultivations[0] = 40.0;
    columnTracker.markColumn(DiscipleColumn::Cultivation, 0);  // 直写 → 手动标脏（R2 前接线口径）

    Disciple rotated = makeDisciple("3", "丙改");
    rotated.realm = 7;
    state.disciples.upsertDisciple(rotated);  // 保序旋转（挂点自动标脏）

    state.disciples.removeById("2");          // 挂点：tombstone + 位移标脏
    state.disciples.appendDisciple(makeDisciple("4", "丁"));  // 挂点：整行标脏
    state.gameData.spiritStones = 777;
    columnTracker.markGameDataField("spiritStones");

    const json columnOut = json::parse(
        columnTracker.exportDirtyJson(state.disciples, state.gameData));
    const json treeOut = json::parse(treeTracker.diffToJson(state));

    // removed 逐位一致
    ASSERT_EQ(treeOut.at("removed").size(), columnOut.at("removed").size());
    if (treeOut.at("removed").contains("disciples")) {
        EXPECT_EQ(treeOut.at("removed").at("disciples"),
                  columnOut.at("removed").at("disciples"));
    }

    // changed 键集：本脚本中列级挂点 + 显式标脏覆盖了全部变更面 ⇒ 恰为等集
    std::set<std::string> treeKeys;
    for (auto it = treeOut.at("changed").begin(); it != treeOut.at("changed").end(); ++it) {
        treeKeys.insert(it.key());
    }
    std::set<std::string> columnKeys;
    for (auto it = columnOut.at("changed").begin(); it != columnOut.at("changed").end(); ++it) {
        columnKeys.insert(it.key());
    }
    EXPECT_EQ(treeKeys, columnKeys);

    // gameData 键值相等
    if (treeOut.at("changed").contains("gameData.spiritStones")) {
        EXPECT_EQ(treeOut.at("changed").at("gameData.spiritStones"),
                  columnOut.at("changed").at("gameData.spiritStones"));
    }

    // disciples：树 diff 的每个变更 id 都出现在列级行集（无漏报），且
    // 列级行的每个键值 == 当前店物化全实体对应值
    std::set<std::string> columnIds;
    for (const auto& row : columnOut.at("changed").at("disciples")) {
        columnIds.insert(row.at("id").get<std::string>());
    }
    std::set<std::string> treeIds;
    for (const auto& entity : treeOut.at("changed").at("disciples")) {
        const std::string id = entity.at("id").get<std::string>();
        treeIds.insert(id);
        EXPECT_TRUE(columnIds.count(id) > 0)
            << "列级导出漏报变更弟子 " << id;
    }
    EXPECT_EQ(treeIds, columnIds);
    for (const auto& row : columnOut.at("changed").at("disciples")) {
        const std::string id = row.at("id").get<std::string>();
        const auto rowIdx = state.disciples.rowOf(id);
        ASSERT_TRUE(rowIdx.has_value());
        const json full = state.disciples.materialize(*rowIdx);
        for (auto it = row.begin(); it != row.end(); ++it) {
            ASSERT_TRUE(full.contains(it.key())) << "列 " << it.key() << " 无协议字段";
            EXPECT_EQ(full.at(it.key()), it.value())
                << "弟子 " << id << " 字段 " << it.key() << " 值与全量导出不一致";
        }
    }
}

}  // namespace
}  // namespace gamecore
