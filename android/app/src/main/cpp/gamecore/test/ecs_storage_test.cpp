#include <gtest/gtest.h>

#include <vector>

#include "gamecore/ecs/registry.h"
#include "gamecore/ecs/storage.h"
#include "gamecore/ecs/view.h"
#include "gamecore/ecs/world.h"

namespace gamecore::ecs {
namespace {

struct Pos {
    int x = 0;
    int y = 0;
};

struct Tag {};

// ── ComponentStorage：SoA 插入/查找/保序删除 ─────────────────────────
TEST(ComponentStorageTest, AddAndFindReturnsValue) {
    World world;
    const EntityId e = world.createEntity();
    auto& store = world.registry().storage<Pos>();
    store.addOrAssign(e, Pos{3, 4});
    ASSERT_NE(store.find(e), nullptr);
    EXPECT_EQ(store.find(e)->x, 3);
    EXPECT_EQ(store.find(e)->y, 4);
    EXPECT_TRUE(store.containsEntity(e));
    EXPECT_EQ(store.size(), 1u);
}

TEST(ComponentStorageTest, AddOrAssignOverwritesExisting) {
    World world;
    const EntityId e = world.createEntity();
    auto& store = world.registry().storage<Pos>();
    store.addOrAssign(e, Pos{1, 2});
    store.addOrAssign(e, Pos{9, 9});
    EXPECT_EQ(store.find(e)->x, 9);
    EXPECT_EQ(store.size(), 1u);  // 不重复建行
}

TEST(ComponentStorageTest, FindReturnsNullForMissingOrStaleHandle) {
    World world;
    const EntityId e = world.createEntity();
    auto& store = world.registry().storage<Pos>();
    EXPECT_EQ(store.find(e), nullptr);  // 无组件
    store.addOrAssign(e, Pos{1, 2});
    world.destroyEntity(e);             // 悬垂句柄
    EXPECT_EQ(store.find(e), nullptr);
}

TEST(ComponentStorageTest, EraseIsOrderPreserving) {
    World world;
    auto& store = world.registry().storage<Pos>();
    const std::vector<EntityId> es = {world.createEntity(), world.createEntity(),
                                      world.createEntity(), world.createEntity()};
    for (std::size_t i = 0; i < es.size(); ++i) store.addOrAssign(es[i], Pos{static_cast<int>(i), 0});

    // 删除第 2 个（index1）→ 剩余相对顺序 0,2,3 不变
    EXPECT_TRUE(store.eraseEntity(es[1]));
    const auto& ents = store.entityList();
    ASSERT_EQ(ents.size(), 3u);
    EXPECT_EQ(ents[0], es[0]);
    EXPECT_EQ(ents[1], es[2]);
    EXPECT_EQ(ents[2], es[3]);
    // 值列同步移位
    EXPECT_EQ(store.values()[1].x, 2);
    // 被删实体不再命中
    EXPECT_FALSE(store.containsEntity(es[1]));
}

TEST(ComponentStorageTest, SparseGrowsBeyondInitialCapacity) {
    World world;
    auto& store = world.registry().storage<Pos>();
    // 分配足够多实体，逼 sparse_ 扩容
    std::vector<EntityId> es;
    for (int i = 0; i < 2048; ++i) {
        es.push_back(world.createEntity());
        store.addOrAssign(es.back(), Pos{i, i});
    }
    EXPECT_EQ(store.size(), 2048u);
    EXPECT_EQ(store.find(es.front())->x, 0);
    EXPECT_EQ(store.find(es.back())->x, 2047);
    // 随机中段访问正确
    EXPECT_EQ(store.find(es[1024])->x, 1024);
}

TEST(ComponentStorageTest, ClearAllResets) {
    World world;
    auto& store = world.registry().storage<Pos>();
    for (int i = 0; i < 5; ++i) store.addOrAssign(world.createEntity(), Pos{i, 0});
    store.clearAll();
    EXPECT_EQ(store.size(), 0u);
}

// ── ComponentRegistry：类型存储注册/复用 ─────────────────────────────
TEST(ComponentRegistryTest, StorageCreatesAndReusesPerType) {
    ComponentRegistry registry;
    EXPECT_FALSE(registry.hasStorage<Pos>());
    auto& s1 = registry.storage<Pos>();
    EXPECT_TRUE(registry.hasStorage<Pos>());
    auto& s2 = registry.storage<Pos>();
    EXPECT_EQ(&s1, &s2);                       // 同一实例
    EXPECT_EQ(registry.storageCount(), 1u);
    EXPECT_EQ(registry.storageOrNull<Tag>(), nullptr);
    auto& s3 = registry.storage<Tag>();
    EXPECT_NE(static_cast<const void*>(&s1), static_cast<const void*>(&s3));
    EXPECT_EQ(registry.storageCount(), 2u);
}

// ── World：创建/销毁实体自动清理组件 ─────────────────────────────────
TEST(WorldTest, CreateEntityRegisteredInManager) {
    World world;
    const EntityId e = world.createEntity();
    EXPECT_TRUE(world.entities().isAlive(e));
}

TEST(WorldTest, DestroyEntityRemovesComponents) {
    World world;
    const EntityId e = world.createEntity();
    world.registry().storage<Pos>().addOrAssign(e, Pos{1, 2});
    world.registry().storage<Tag>().addOrAssign(e, Tag{});
    EXPECT_TRUE(world.entities().isAlive(e));
    EXPECT_EQ(world.registry().storage<Pos>().size(), 1u);

    EXPECT_TRUE(world.destroyEntity(e));
    EXPECT_FALSE(world.entities().isAlive(e));
    EXPECT_EQ(world.registry().storage<Pos>().size(), 0u);  // 组件已清
    EXPECT_EQ(world.registry().storage<Tag>().size(), 0u);
}

// ── View：组件子集查询/迭代（确定性：首组件插入序）───────────────────
TEST(ViewTest, IterateOrderMatchesPrimaryStorageInsertionOrder) {
    World world;
    auto& pos = world.registry().storage<Pos>();
    auto& tag = world.registry().storage<Tag>();
    const EntityId a = world.createEntity();
    const EntityId b = world.createEntity();
    const EntityId c = world.createEntity();
    pos.addOrAssign(a, Pos{1, 1});
    pos.addOrAssign(b, Pos{2, 2});
    pos.addOrAssign(c, Pos{3, 3});
    tag.addOrAssign(a, Tag{});   // a、c 有 Tag
    tag.addOrAssign(c, Tag{});

    View<Pos, Tag> view(world.registry());
    EXPECT_EQ(view.count(), 2u);

    std::vector<std::pair<EntityId, int>> seen;
    view.forEach([&](EntityId e, Pos& p, Tag&) {
        seen.emplace_back(e, p.x);
    });
    ASSERT_EQ(seen.size(), 2u);
    // 主序 = Pos 插入序 a,c（b 无 Tag 被过滤）——顺序稳定
    EXPECT_EQ(seen[0].first, a);
    EXPECT_EQ(seen[0].second, 1);
    EXPECT_EQ(seen[1].first, c);
    EXPECT_EQ(seen[1].second, 3);
}

TEST(ViewTest, ForEachAllowsMutableComponentWrite) {
    World world;
    auto& pos = world.registry().storage<Pos>();
    for (int i = 0; i < 3; ++i) {
        const EntityId e = world.createEntity();
        pos.addOrAssign(e, Pos{i, 0});
    }
    View<Pos> view(world.registry());
    view.forEach([](EntityId, Pos& p) { p.y = p.x * 10; });
    EXPECT_EQ(pos.values()[0].y, 0);
    EXPECT_EQ(pos.values()[1].y, 10);
    EXPECT_EQ(pos.values()[2].y, 20);
}

TEST(ViewTest, SingleComponentViewIteratesAll) {
    World world;
    auto& tag = world.registry().storage<Tag>();
    const EntityId a = world.createEntity();
    const EntityId b = world.createEntity();
    tag.addOrAssign(a, Tag{});
    tag.addOrAssign(b, Tag{});
    View<Tag> view(world.registry());
    EXPECT_EQ(view.count(), 2u);
}

}  // namespace
}  // namespace gamecore::ecs
