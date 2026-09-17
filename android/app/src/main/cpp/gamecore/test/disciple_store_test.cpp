#include <gtest/gtest.h>

#include "gamecore/state/models.h"
#include "gamecore/system/settlement_detail.h"  // toIntOrNull 口径对齐守卫

namespace gamecore {
namespace {

// ============================================================
// DiscipleStore 测试（实体存储数据导向化）
//
// 覆盖：追加/物化往返 / 行序保持（RNG 红线）/ upsert 原位覆盖 / 删除保序 /
// 全量装载 / 按 id 查找 / 同 id 保留最后 / 清空。
// ============================================================

using state::Disciple;
using state::DiscipleStore;

Disciple makeDisciple(const std::string& id, const std::string& name,
                      int32_t realm = 9, double cultivation = 10.0) {
    Disciple d;
    d.id = id;
    d.name = name;
    d.realm = realm;
    d.cultivation = cultivation;
    d.isAlive = true;
    return d;
}

TEST(DiscipleStoreTest, AppendAndMaterializeRoundTrip) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("1", "甲", 9, 12.5));
    store.appendDisciple(makeDisciple("2", "乙", 8, 20.0));

    ASSERT_EQ(2u, store.size());
    EXPECT_EQ("1", store.idAt(0));
    EXPECT_EQ("2", store.idAt(1));
    const Disciple d0 = store.materialize(0);
    EXPECT_EQ("甲", d0.name);
    EXPECT_DOUBLE_EQ(12.5, d0.cultivation);
    EXPECT_EQ(9, d0.realm);
    EXPECT_TRUE(d0.isAlive);
    const Disciple d1 = store.materialize(1);
    EXPECT_EQ("乙", d1.name);
    EXPECT_DOUBLE_EQ(20.0, d1.cultivation);
}

TEST(DiscipleStoreTest, RowOrderPreservedAcrossUpsert) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("1", "甲"));
    store.appendDisciple(makeDisciple("2", "乙"));
    store.appendDisciple(makeDisciple("3", "丙"));

    // 原位覆盖弟子 2（行序保持——RNG 对拍红线）
    store.upsertDisciple(makeDisciple("2", "乙改", 7, 33.0));

    ASSERT_EQ(3u, store.size());
    EXPECT_EQ("1", store.idAt(0));
    EXPECT_EQ("2", store.idAt(1));
    EXPECT_EQ("3", store.idAt(2));
    EXPECT_EQ("乙改", store.materialize(1).name);
    EXPECT_DOUBLE_EQ(33.0, store.materialize(1).cultivation);
    EXPECT_EQ(7, store.materialize(1).realm);
    // 未覆盖弟子不受影响
    EXPECT_EQ("甲", store.materialize(0).name);
}

TEST(DiscipleStoreTest, AppendNewDiscipleGoesToEnd) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("1", "甲"));
    store.appendDisciple(makeDisciple("5", "戊"));

    ASSERT_EQ(2u, store.size());
    EXPECT_EQ("5", store.idAt(1));   // 新弟子追加末尾（Kotlin max+1 升序）
}

TEST(DiscipleStoreTest, RemoveByIdPreservesOrder) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("1", "甲"));
    store.appendDisciple(makeDisciple("2", "乙"));
    store.appendDisciple(makeDisciple("3", "丙"));

    store.removeById("2");

    ASSERT_EQ(2u, store.size());
    EXPECT_EQ("1", store.idAt(0));
    EXPECT_EQ("3", store.idAt(1));
    EXPECT_FALSE(store.contains("2"));
    EXPECT_EQ(1u, store.rowOf("3").value());   // "3" 在删除后位于行 1
}

TEST(DiscipleStoreTest, RemoveMissingIdIsNoOp) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("1", "甲"));
    store.removeById("999");
    ASSERT_EQ(1u, store.size());
    EXPECT_EQ("1", store.idAt(0));
}

TEST(DiscipleStoreTest, LoadFromVectorAndClear) {
    DiscipleStore store;
    std::vector<Disciple> vec = {
        makeDisciple("1", "甲"), makeDisciple("2", "乙")};
    store.loadFromVector(vec);
    ASSERT_EQ(2u, store.size());

    std::vector<Disciple> vec2 = {makeDisciple("9", "壬")};
    store.loadFromVector(vec2);
    ASSERT_EQ(1u, store.size());
    EXPECT_EQ("9", store.idAt(0));

    store.clear();
    EXPECT_EQ(0u, store.size());
    EXPECT_FALSE(store.contains("9"));
}

TEST(DiscipleStoreTest, RowOfAndContains) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("7", "庚"));
    EXPECT_TRUE(store.contains("7"));
    EXPECT_EQ(0u, store.rowOf("7").value());
    EXPECT_FALSE(store.contains("8"));
    EXPECT_FALSE(store.rowOf("8").has_value());
}

TEST(DiscipleStoreTest, SameIdKeepsLast) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("1", "甲"));
    store.appendDisciple(makeDisciple("1", "甲改"));   // 同 id：原位覆盖（SparseArray 语义）
    ASSERT_EQ(1u, store.size());
    EXPECT_EQ("1", store.idAt(0));
    EXPECT_EQ("甲改", store.materialize(0).name);
}

TEST(DiscipleStoreTest, ColumnWriteReflectedInMaterialize) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("1", "甲"));
    // 列直写（热路径模式）
    store.cultivations[0] = 42.0;
    store.currentHps[0] = 99;
    store.isAlive[0] = 0;
    const Disciple d = store.materialize(0);
    EXPECT_DOUBLE_EQ(42.0, d.cultivation);
    EXPECT_EQ(99, d.currentHp);
    EXPECT_FALSE(d.isAlive);
}

// ── R1.3 dense 索引：numericIds 列与 numericIdToRow 维护守卫 ─────────

TEST(DiscipleStoreTest, NumericIdColumnParsesOnAppend) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("1", "甲"));
    store.appendDisciple(makeDisciple("42", "乙"));
    store.appendDisciple(makeDisciple("not_a_number", "丙"));
    store.appendDisciple(makeDisciple("", "丁"));

    ASSERT_EQ(4u, store.size());
    ASSERT_EQ(4u, store.numericIds.size());
    ASSERT_EQ(4u, store.hasNumericIds.size());
    EXPECT_EQ(1, store.numericIdAt(0).value());
    EXPECT_EQ(42, store.numericIdAt(1).value());
    // 非法/空 id → 无数值语义（toIntOrNull 同口径），数值置 0
    EXPECT_FALSE(store.numericIdAt(2).has_value());
    EXPECT_EQ(0, store.numericIds[2]);
    EXPECT_EQ(0, store.hasNumericIds[2]);
    EXPECT_FALSE(store.numericIdAt(3).has_value());
}

TEST(DiscipleStoreTest, RowOfNumberMirrorsIdToRow) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("7", "庚"));
    store.appendDisciple(makeDisciple("8", "辛"));

    EXPECT_EQ(0u, store.rowOfNumber(7).value());
    EXPECT_EQ(1u, store.rowOfNumber(8).value());
    EXPECT_FALSE(store.rowOfNumber(9).has_value());
}

TEST(DiscipleStoreTest, NumericIndexSurvivesUpsertRotation) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("1", "甲"));
    store.appendDisciple(makeDisciple("2", "乙"));
    store.appendDisciple(makeDisciple("3", "丙"));
    // 原位覆盖弟子 2（eraseAt + 末尾追加 + 保序旋转——索引维护命门路径）
    store.upsertDisciple(makeDisciple("2", "乙改"));

    ASSERT_EQ(3u, store.size());
    EXPECT_EQ(0u, store.rowOfNumber(1).value());
    EXPECT_EQ(1u, store.rowOfNumber(2).value());
    EXPECT_EQ(2u, store.rowOfNumber(3).value());
    EXPECT_EQ("乙改", store.materialize(store.rowOfNumber(2).value()).name);
}

TEST(DiscipleStoreTest, NumericIndexSurvivesRemove) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("1", "甲"));
    store.appendDisciple(makeDisciple("2", "乙"));
    store.appendDisciple(makeDisciple("3", "丙"));
    store.removeById("2");

    ASSERT_EQ(2u, store.size());
    EXPECT_EQ(0u, store.rowOfNumber(1).value());
    EXPECT_EQ(1u, store.rowOfNumber(3).value());
    EXPECT_FALSE(store.rowOfNumber(2).has_value());
    // 行删除后数值列与 id 列仍逐行一致
    EXPECT_EQ(1, store.numericIdAt(0).value());
    EXPECT_EQ(3, store.numericIdAt(1).value());
}

TEST(DiscipleStoreTest, NumericIndexClearAndReload) {
    DiscipleStore store;
    store.appendDisciple(makeDisciple("1", "甲"));
    const std::vector<Disciple> vec = {makeDisciple("9", "壬"),
                                       makeDisciple("4", "肆")};
    store.loadFromVector(vec);

    ASSERT_EQ(2u, store.size());
    EXPECT_EQ(0u, store.rowOfNumber(9).value());
    EXPECT_EQ(1u, store.rowOfNumber(4).value());
    EXPECT_FALSE(store.rowOfNumber(1).has_value());
    store.clear();
    EXPECT_TRUE(store.numericIdToRow.empty());
    EXPECT_TRUE(store.numericIds.empty());
    EXPECT_TRUE(store.hasNumericIds.empty());
}

TEST(DiscipleStoreTest, ParseParityWithSettleUtilToIntOrNull) {
    // 数值列解析与 settle_util::toIntOrNull 单点同实现——边界样本对齐守卫
    const std::vector<std::string> samples = {
        "", "abc", "12a", " 12", "12 ", "+12", "-5", "007",
        "2147483647", "99999999999",
    };
    for (const std::string& s : samples) {
        DiscipleStore store;
        store.appendDisciple(makeDisciple(s, "甲"));
        const auto viaColumn = store.numericIdAt(0);
        const auto viaUtil =
            gamecore::system::settle_util::toIntOrNull(s);
        ASSERT_EQ(viaUtil.has_value(), viaColumn.has_value()) << s;
        if (viaUtil.has_value()) {
            EXPECT_EQ(*viaUtil, *viaColumn) << s;
        }
    }
}

}  // namespace
}  // namespace gamecore
