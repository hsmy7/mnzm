#include <gtest/gtest.h>

#include <vector>

#include "gamecore/ecs/ecs.h"

namespace gamecore::ecs {
namespace {

struct BattleFlag {
    bool engaged = false;
};

TEST(DiscipleEntityTest, BuildCreatesEntityPerRowInOrder) {
    World world;
    const auto entityByRow = buildDiscipleEntities(world, 5);
    ASSERT_EQ(entityByRow.size(), 5u);
    auto& store = world.registry().storage<DiscipleRef>();
    EXPECT_EQ(store.size(), 5u);
    for (std::size_t row = 0; row < 5; ++row) {
        const DiscipleRef* ref = store.find(entityByRow[row]);
        ASSERT_NE(ref, nullptr);
        EXPECT_EQ(ref->row, row);         // row ↔ entity 对齐
    }
    EXPECT_EQ(world.entities().aliveCount(), 5u);
}

TEST(DiscipleEntityTest, ViewIteratesInDiscipleRowOrder) {
    World world;
    const auto entityByRow = buildDiscipleEntities(world, 4);
    View<DiscipleRef> view(world.registry());
    EXPECT_EQ(view.count(), 4u);

    std::vector<std::size_t> rows;
    view.forEach([&](EntityId e, DiscipleRef& ref) {
        rows.push_back(ref.row);
        EXPECT_TRUE(world.entities().isAlive(e));
    });
    // 行序 0,1,2,3（== DiscipleStore 行序红线）
    EXPECT_EQ(rows, (std::vector<std::size_t>{0, 1, 2, 3}));
}

TEST(DiscipleEntityTest, MultiComponentFilterLimitsView) {
    World world;
    const auto entityByRow = buildDiscipleEntities(world, 6);
    auto& flags = world.registry().storage<BattleFlag>();
    // 行 1、3、5 打上战斗标记
    flags.addOrAssign(entityByRow[1], BattleFlag{true});
    flags.addOrAssign(entityByRow[3], BattleFlag{true});
    flags.addOrAssign(entityByRow[5], BattleFlag{true});

    View<DiscipleRef, BattleFlag> view(world.registry());
    EXPECT_EQ(view.count(), 3u);
    std::vector<std::size_t> rows;
    view.forEach([&](EntityId, DiscipleRef& ref, BattleFlag&) { rows.push_back(ref.row); });
    EXPECT_EQ(rows, (std::vector<std::size_t>{1, 3, 5}));  // 保持行序，仅过滤
}

TEST(DiscipleEntityTest, DestroySingleDiscipleEntity) {
    World world;
    const auto entityByRow = buildDiscipleEntities(world, 3);
    destroyDiscipleEntity(world, 1);
    auto& store = world.registry().storage<DiscipleRef>();
    EXPECT_EQ(store.size(), 2u);
    EXPECT_FALSE(world.entities().isAlive(entityByRow[1]));
    EXPECT_TRUE(world.entities().isAlive(entityByRow[0]));
    EXPECT_TRUE(world.entities().isAlive(entityByRow[2]));
}

TEST(DiscipleEntityTest, RebuildDestroysPreviousAndRecreates) {
    World world;
    const auto before = buildDiscipleEntities(world, 3);
    // 给其中一个加别的组件，重建后该实体销毁
    world.registry().storage<BattleFlag>().addOrAssign(before[0], BattleFlag{true});
    const auto after = buildDiscipleEntities(world, 2);
    EXPECT_EQ(after.size(), 2u);
    EXPECT_EQ(world.registry().storage<DiscipleRef>().size(), 2u);
    EXPECT_EQ(world.entities().aliveCount(), 2u);
    // 旧实体全部失效
    EXPECT_FALSE(world.entities().isAlive(before[0]));
    EXPECT_FALSE(world.entities().isAlive(before[1]));
    EXPECT_FALSE(world.entities().isAlive(before[2]));
}

TEST(DiscipleEntityTest, DestroyAllClearsDiscipleEntities) {
    World world;
    buildDiscipleEntities(world, 4);
    EXPECT_EQ(world.entities().aliveCount(), 4u);
    destroyDiscipleEntities(world);
    EXPECT_EQ(world.registry().storage<DiscipleRef>().size(), 0u);
    EXPECT_EQ(world.entities().aliveCount(), 0u);
}

// ── R1.6：swap-and-pop 批量销毁（重建场景去 O(D²)）──

TEST(DiscipleEntityTest, BatchDestroyThenRebuildKeepsRowOrderInvariant) {
    // 重建场景（buildDiscipleEntities 先全清再按行序重建）经 swap-and-pop
    // 批量销毁后，新实体集的"View 序 == 行序"不变量必须成立（sync 零重建
    // 直通）——swap 带来的 dense 重排不得泄漏到重建后的迭代域。
    World world;
    const auto before = buildDiscipleEntities(world, 64);
    destroyDiscipleEntities(world);  // O(D) 批量销毁（R1.6 swap-and-pop）
    auto& store = world.registry().storage<DiscipleRef>();
    EXPECT_EQ(store.size(), 0u);
    for (const EntityId e : before) {
        EXPECT_FALSE(world.entities().isAlive(e));
        EXPECT_EQ(store.find(e), nullptr);  // 组件已清 + 句柄失效
    }

    const auto after = buildDiscipleEntities(world, 48);
    // 不变量自检：syncDiscipleEntities 校验"View 序 == 行序 0..N-1"
    // 成立 ⇒ 零重建原样返回（若 swap 破坏重建序，此处返回的将是新集）
    const auto synced = syncDiscipleEntities(world, 48);
    ASSERT_EQ(synced.size(), 48u);
    for (std::size_t i = 0; i < 48; ++i) {
        EXPECT_EQ(synced[i], after[i]);
        EXPECT_EQ(store.find(synced[i])->row, i);
    }
}

// ── 保序验证：syncDiscipleEntities（桥接规范红线） ──

TEST(DiscipleEntityTest, SyncReturnsSameEntitiesWhenInvariantHolds) {
    World world;
    const auto built = buildDiscipleEntities(world, 5);
    const auto synced = syncDiscipleEntities(world, 5);
    // 不变量成立：原实体句柄按行序原样返回（稳态零重建）
    EXPECT_EQ(synced, built);
    auto& store = world.registry().storage<DiscipleRef>();
    for (std::size_t i = 0; i < synced.size(); ++i) {
        EXPECT_EQ(store.find(synced[i])->row, i);
    }
}

TEST(DiscipleEntityTest, StorageErasePreservesRelativeOrder) {
    // ComponentStorage erase 为保序压缩（非 swap-and-pop）——删除中间实体
    // 后，剩余实体相对序不变；这是"View 序 == 行序"在删除场景下的物理基础。
    World world;
    const auto built = buildDiscipleEntities(world, 5);
    world.destroyEntity(built[2]);
    View<DiscipleRef> view(world.registry());
    std::vector<std::size_t> rows;
    view.forEach([&](EntityId, DiscipleRef& ref) { rows.push_back(ref.row); });
    EXPECT_EQ(rows, (std::vector<std::size_t>{0, 1, 3, 4}));
}

TEST(DiscipleEntityTest, SyncRebuildsOnCountDrift) {
    World world;
    const auto built = buildDiscipleEntities(world, 4);
    world.destroyEntity(built[1]);   // 实体集漂移（弟子仍在 store：数量不匹配）
    const auto synced = syncDiscipleEntities(world, 4);
    ASSERT_EQ(synced.size(), 4u);
    auto& store = world.registry().storage<DiscipleRef>();
    std::vector<std::size_t> rows;
    for (const EntityId e : synced) {
        ASSERT_TRUE(world.entities().isAlive(e));
        rows.push_back(store.find(e)->row);
    }
    EXPECT_EQ(rows, (std::vector<std::size_t>{0, 1, 2, 3}));  // row i ↔ entity i 恢复
}

TEST(DiscipleEntityTest, SyncRebuildsOnRowOrderDrift) {
    World world;
    buildDiscipleEntities(world, 3);
    // 数量匹配但行号升序被破坏（手工追加 row=1 的重复实体）
    auto& store = world.registry().storage<DiscipleRef>();
    const EntityId extra = world.createEntity();
    store.addOrAssign(extra, DiscipleRef{1});
    ASSERT_EQ(store.size(), 4u);

    const auto synced = syncDiscipleEntities(world, 4);
    ASSERT_EQ(synced.size(), 4u);
    std::vector<std::size_t> rows;
    for (const EntityId e : synced) rows.push_back(store.find(e)->row);
    EXPECT_EQ(rows, (std::vector<std::size_t>{0, 1, 2, 3}));  // 重建后严格升序
}

TEST(DiscipleEntityTest, SyncRebuildsOnBothEmpty) {
    World world;
    const auto synced = syncDiscipleEntities(world, 0);
    EXPECT_TRUE(synced.empty());
    // 空 store + 空 World：不变量成立路径（零重建），再次调用仍为空
    EXPECT_EQ(syncDiscipleEntities(world, 0).size(), 0u);
}

TEST(DiscipleEntityTest, SyncRealignsAfterDiscipleRemoval) {
    // 生产等价场景：store removeById（行前移）+ 旧实体集未重建 → sync 全量
    // 重建，View 序恢复 == 新行序（行 i 的 ref.row == i）。
    World world;
    buildDiscipleEntities(world, 5);
    destroyDiscipleEntity(world, 2);   // 旧实体集只删了 4 个，行数已对不上
    const auto synced = syncDiscipleEntities(world, 4);
    ASSERT_EQ(synced.size(), 4u);
    auto& store = world.registry().storage<DiscipleRef>();
    std::vector<std::size_t> rows;
    for (const EntityId e : synced) rows.push_back(store.find(e)->row);
    EXPECT_EQ(rows, (std::vector<std::size_t>{0, 1, 2, 3}));
}

}  // namespace
}  // namespace gamecore::ecs
