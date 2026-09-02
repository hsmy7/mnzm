#include <gtest/gtest.h>

#include "gamecore/ecs/entity.h"

namespace gamecore::ecs {
namespace {

// ── EntityManager：创建/复用/回收/generation 防悬垂 ──────────────────
class EntityManagerTest : public ::testing::Test {
protected:
    EntityManager manager;
};

TEST_F(EntityManagerTest, CreateAssignsSequentialDenseIndices) {
    const EntityId a = manager.create();
    const EntityId b = manager.create();
    const EntityId c = manager.create();
    EXPECT_EQ(a.index, 0u);
    EXPECT_EQ(b.index, 1u);
    EXPECT_EQ(c.index, 2u);
    EXPECT_EQ(manager.aliveCount(), 3u);
    EXPECT_TRUE(manager.isAlive(a));
    EXPECT_TRUE(manager.isAlive(b));
    EXPECT_TRUE(manager.isAlive(c));
}

TEST_F(EntityManagerTest, DestroyBumpsGenerationAndReusesIndex) {
    const EntityId a = manager.create();
    const EntityId b = manager.create();
    EXPECT_TRUE(manager.destroy(a));
    EXPECT_FALSE(manager.isAlive(a));          // 老句柄失效
    EXPECT_FALSE(manager.destroy(a));          // 重复销毁返回 false

    const EntityId c = manager.create();       // freelist LIFO → 复用 index 0
    EXPECT_EQ(c.index, 0u);
    EXPECT_NE(c.generation, a.generation);     // 防悬垂代数递增
    EXPECT_TRUE(manager.isAlive(c));
    EXPECT_EQ(manager.aliveCount(), 2u);       // b + c
}

TEST_F(EntityManagerTest, DestroyMissingAndInvalidEntityReturnsFalse) {
    EXPECT_FALSE(manager.destroy(kNullEntity));
    const EntityId fictitious{kInvalidEntityIndex, 123};
    EXPECT_FALSE(manager.destroy(fictitious));
}

TEST_F(EntityManagerTest, HangingHandleInvalidAfterDestroy) {
    const EntityId a = manager.create();
    const EntityId b = manager.create();
    manager.destroy(a);
    // 用 a.index 重建（旧代数）应判为不存活
    const EntityId stale = manager.entityAtIndex(a.index);
    EXPECT_NE(stale.generation, a.generation);
    EXPECT_FALSE(manager.isAlive(stale));
    ASSERT_GE(stale.index, 0u);
    // 存活实体 index 重建仍有效
    const EntityId aliveRe = manager.entityAtIndex(b.index);
    EXPECT_TRUE(manager.isAlive(aliveRe));
}

TEST_F(EntityManagerTest, EntityAtIndexRespectsGenerationAfterReuse) {
    const EntityId a = manager.create();          // index 0 gen 1
    manager.destroy(a);
    const EntityId b = manager.create();          // reuse index 0 gen 2
    EXPECT_EQ(b.index, 0u);
    const EntityId rebuilt = manager.entityAtIndex(0u);
    EXPECT_EQ(rebuilt.generation, b.generation);
    EXPECT_TRUE(manager.isAlive(rebuilt));
}

TEST_F(EntityManagerTest, ClearResetsState) {
    manager.create();
    manager.create();
    manager.clear();
    EXPECT_EQ(manager.aliveCount(), 0u);
    EXPECT_EQ(manager.capacity(), 0u);
}

}  // namespace
}  // namespace gamecore::ecs
