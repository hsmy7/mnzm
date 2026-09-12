#include <gtest/gtest.h>

#include "gamecore/state/models.h"

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

}  // namespace
}  // namespace gamecore
