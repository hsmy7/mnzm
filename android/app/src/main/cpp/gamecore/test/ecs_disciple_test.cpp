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

}  // namespace
}  // namespace gamecore::ecs
