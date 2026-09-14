#include <gtest/gtest.h>

#include <vector>

#include "gamecore/ecs/disciple_component.h"
#include "gamecore/ecs/npc_component.h"

namespace gamecore::ecs {
namespace {

TEST(NpcComponentTest, SpawnAssemblesFullComponentFamily) {
    World world;
    const EntityId e = spawnNpc(world, NpcSpawnParams{.id = 7u, .x = 3.0f, .y = 4.0f, .spriteId = 42u});
    auto& registry = world.registry();
    EXPECT_EQ(registry.storage<NpcId>().find(e)->id, 7u);
    const NpcPosition* pos = registry.storage<NpcPosition>().find(e);
    ASSERT_NE(pos, nullptr);
    EXPECT_FLOAT_EQ(pos->x, 3.0f);
    EXPECT_FLOAT_EQ(pos->y, 4.0f);
    EXPECT_EQ(registry.storage<NpcVelocity>().find(e)->vx, 0.0f);
    EXPECT_TRUE(registry.storage<NpcPath>().find(e)->waypoints.empty());
    EXPECT_EQ(registry.storage<NpcPathIndex>().find(e)->index, kNoWaypoint);
    EXPECT_EQ(registry.storage<NpcSpriteId>().find(e)->spriteId, 42u);
    const NpcAnimState* anim = registry.storage<NpcAnimState>().find(e);
    ASSERT_NE(anim, nullptr);
    EXPECT_EQ(anim->frame, 0);
    EXPECT_FALSE(anim->moving);
}

TEST(NpcComponentTest, ViewIteratesInSpawnOrder) {
    World world;
    spawnNpc(world, NpcSpawnParams{.id = 1u, .x = 0.0f, .y = 0.0f, .spriteId = 1u});
    spawnNpc(world, NpcSpawnParams{.id = 2u, .x = 1.0f, .y = 0.0f, .spriteId = 1u});
    spawnNpc(world, NpcSpawnParams{.id = 3u, .x = 2.0f, .y = 0.0f, .spriteId = 1u});

    std::vector<std::uint32_t> ids;
    View<NpcId, NpcPosition> view(world.registry());
    EXPECT_EQ(view.count(), 3u);
    view.forEach([&ids](EntityId, const NpcId& id, const NpcPosition&) {
        ids.push_back(id.id);
    });
    // 插入序 == spawn 序（NPC 组件插入序即权威；NPC 移动系统固定迭代序前提）
    EXPECT_EQ(ids, (std::vector<std::uint32_t>{1u, 2u, 3u}));
}

TEST(NpcComponentTest, DestroyRemovesFromAllComponentStorages) {
    World world;
    const EntityId a = spawnNpc(world, NpcSpawnParams{.id = 1u});
    const EntityId b = spawnNpc(world, NpcSpawnParams{.id = 2u});
    destroyNpc(world, a);

    EXPECT_EQ(countNpcs(world), 1u);
    View<NpcId> view(world.registry());
    EXPECT_EQ(view.count(), 1u);
    view.forEach([&b](EntityId e, const NpcId&) { EXPECT_EQ(e, b); });
    // 全组件存储均已擦除（destroyEntity 级联）
    EXPECT_FALSE(world.registry().storage<NpcPosition>().containsEntity(a));
    EXPECT_FALSE(world.registry().storage<NpcAnimState>().containsEntity(a));
}

TEST(NpcComponentTest, DestroyAllClearsField) {
    World world;
    for (std::uint32_t i = 0; i < 5; ++i) {
        spawnNpc(world, NpcSpawnParams{.id = i});
    }
    destroyAllNpcs(world);
    EXPECT_EQ(countNpcs(world), 0u);
    View<NpcId, NpcPathIndex> view(world.registry());
    EXPECT_EQ(view.count(), 0u);
}

TEST(NpcComponentTest, ComponentFamilyCoexistsWithDiscipleEntities) {
    World world;
    // 弟子实体（DiscipleRef 桥）与 NPC 纯组件实体正交共存
    const auto discipleByRow = buildDiscipleEntities(world, 2);
    const EntityId npc = spawnNpc(world, NpcSpawnParams{.id = 9u});

    View<DiscipleRef> dv(world.registry());
    EXPECT_EQ(dv.count(), 2u);
    View<NpcId> nv(world.registry());
    EXPECT_EQ(nv.count(), 1u);
    nv.forEach([&npc](EntityId e, const NpcId& id) {
        EXPECT_EQ(e, npc);
        EXPECT_EQ(id.id, 9u);
    });
    EXPECT_EQ(discipleByRow.size(), 2u);
}

TEST(NpcComponentTest, FindByIdHitsAndMisses) {
    World world;
    const EntityId e = spawnNpc(world, NpcSpawnParams{.id = 11u});
    EXPECT_EQ(findNpcById(world, 11u), e);
    EXPECT_FALSE(isValid(findNpcById(world, 12u)));
}

TEST(NpcComponentTest, CollectRenderRowsMatchesSpawnOrderAndFields) {
    World world;
    spawnNpc(world, NpcSpawnParams{.id = 1u, .x = 1.5f, .y = 2.5f, .spriteId = 10u});
    const EntityId second = spawnNpc(world, NpcSpawnParams{.id = 2u, .x = 3.5f, .y = 4.5f, .spriteId = 20u});
    // 改动第二只 NPC 的帧号，验证快照读取的是活组件
    world.registry().storage<NpcAnimState>().find(second)->frame = 3;

    const auto rows = collectNpcRenderRows(world);
    ASSERT_EQ(rows.size(), 2u);
    EXPECT_EQ(rows[0].id, 1u);
    EXPECT_FLOAT_EQ(rows[0].x, 1.5f);
    EXPECT_FLOAT_EQ(rows[0].y, 2.5f);
    EXPECT_EQ(rows[0].spriteId, 10u);
    EXPECT_EQ(rows[0].animFrame, 0);
    EXPECT_EQ(rows[1].id, 2u);
    EXPECT_FLOAT_EQ(rows[1].x, 3.5f);
    EXPECT_EQ(rows[1].spriteId, 20u);
    EXPECT_EQ(rows[1].animFrame, 3);
}

TEST(NpcComponentTest, PathIndexCursorSemantics) {
    World world;
    const EntityId e = spawnNpc(world, NpcSpawnParams{.id = 1u});
    auto& paths = world.registry().storage<NpcPath>();
    auto& cursors = world.registry().storage<NpcPathIndex>();

    // 初始无路径
    EXPECT_EQ(cursors.find(e)->index, kNoWaypoint);
    // 装配路点 + 游标归零（寻路写入形状约定）
    paths.find(e)->waypoints = {{1.0f, 0.0f}, {2.0f, 0.0f}, {3.0f, 0.0f}};
    cursors.find(e)->index = 0;
    EXPECT_EQ(paths.find(e)->waypoints.size(), 3u);
    EXPECT_EQ(cursors.find(e)->index, 0u);
    // 走完置哨兵
    cursors.find(e)->index = kNoWaypoint;
    EXPECT_EQ(cursors.find(e)->index, kNoWaypoint);
}

}  // namespace
}  // namespace gamecore::ecs
