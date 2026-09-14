#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "gamecore/ecs/entity.h"
#include "gamecore/ecs/view.h"
#include "gamecore/ecs/world.h"

// ============================================================
// ECS NPC 组件族（为 NPC 移动系统供数）
//
// NPC 是 ECS 的第一个"纯组件"实体族（方案 X：弟子走 DiscipleRef 桥接，
// NPC 无 SoA 权威存储——组件即数据本体）。组件为**纯运行态**：不进
// JSON/存档协议（零序列化），渲染经紧凑原始类型数组通道
// （id,x,y,spriteId,animFrame）出 C++，Kotlin 渲染线程按 alpha 插值。
//
// ## 组件族
//   - NpcId        稳定 id（渲染通道标识；C++ 侧生成，每存档会话内唯一）
//   - NpcPosition  逻辑坐标（瓦片单位 float；渲染插值基准）
//   - NpcVelocity  直线速度（NPC 移动系统消费）
//   - NpcPath      A* 路径缓存（路点=瓦片坐标；建筑放置/拆除增量失效）
//   - NpcPathIndex 当前路点游标（kNoWaypoint = 无路径/已到达）
//   - NpcSpriteId  占位精灵 id（程序化生成小人占位，正式美术后替换）
//   - NpcAnimState 行走帧动画状态（帧号/朝向/移动中——渲染层 UV 切换消费）
//
// ## 迭代确定性（沿 E1 桥接规范同源约定）
//   View 迭代序 == 首组件插入序（== spawn 序）。NPC 移动系统
//   若消耗 RNG 必须固定迭代序；syncDiscipleEntities 式校验不适用于 NPC
//   （无外部权威行序——组件插入序即权威）。
// ============================================================
namespace gamecore::ecs {

/// 当前路点游标哨兵（无路径 / 路径走完）
inline constexpr std::size_t kNoWaypoint = static_cast<std::size_t>(-1);

/// NPC 稳定 id 组件（渲染通道 id 字段）
struct NpcId {
    std::uint32_t id = 0;
};

/// 逻辑坐标（瓦片单位；float 供渲染线程 alpha 插值）
struct NpcPosition {
    float x = 0.0f;
    float y = 0.0f;
};

/// 直线速度（瓦片/逻辑 tick；NPC 移动系统消费）
struct NpcVelocity {
    float vx = 0.0f;
    float vy = 0.0f;
};

/// 路点（瓦片坐标；与 NpcPosition 同单位）
struct NpcWaypoint {
    float x = 0.0f;
    float y = 0.0f;
};

/// A* 路径缓存（按需生成 + 建筑占位变更时增量失效）
struct NpcPath {
    std::vector<NpcWaypoint> waypoints;
};

/// 当前路点游标（NpcPath.waypoints 下标；kNoWaypoint = 无路径/已到达）
struct NpcPathIndex {
    std::size_t index = kNoWaypoint;
};

/// 占位精灵 id（程序化小人占位；正式美术后替换映射）
struct NpcSpriteId {
    std::uint32_t spriteId = 0;
};

/// 行走帧动画状态（渲染层 SpriteBatcher UV 帧切换消费）
struct NpcAnimState {
    std::uint8_t frame = 0;    // 当前帧号（循环）
    std::uint8_t facing = 0;   // 朝向（0=下 1=左 2=右 3=上，与地图精灵惯例对齐）
    bool moving = false;       // 是否移动中（静止回第 0 帧）
};

/// NPC 生成参数（未列组件由默认构造——按需 addOrAssign 补挂）
struct NpcSpawnParams {
    std::uint32_t id = 0;
    float x = 0.0f;
    float y = 0.0f;
    std::uint32_t spriteId = 0;
};

/// 生成 NPC 实体并装配全套组件（单入口——组件族完整性由构造保证）
inline EntityId spawnNpc(World& world, const NpcSpawnParams& params) {
    const EntityId e = world.createEntity();
    auto& registry = world.registry();
    registry.storage<NpcId>().addOrAssign(e, NpcId{params.id});
    registry.storage<NpcPosition>().addOrAssign(e, NpcPosition{params.x, params.y});
    registry.storage<NpcVelocity>().addOrAssign(e, NpcVelocity{});
    registry.storage<NpcPath>().addOrAssign(e, NpcPath{});
    registry.storage<NpcPathIndex>().addOrAssign(e, NpcPathIndex{});
    registry.storage<NpcSpriteId>().addOrAssign(e, NpcSpriteId{params.spriteId});
    registry.storage<NpcAnimState>().addOrAssign(e, NpcAnimState{});
    return e;
}

/// 销毁 NPC 实体（World 级组件清理——registry.onEntityDestroyed 全存储擦除）
inline void destroyNpc(World& world, EntityId e) { world.destroyEntity(e); }

/// 销毁全部 NPC（副本迭代防迭代器失效；读档重置/会话重建用）
inline void destroyAllNpcs(World& world) {
    auto& storage = world.registry().storage<NpcId>();
    const auto entityList = storage.entityList();  // 拷贝
    for (const EntityId e : entityList) {
        world.destroyEntity(e);
    }
}

/// NPC 存活数（NpcId 存储行数——spawnNpc 恒挂 NpcId，与实体数一致）
inline std::size_t countNpcs(World& world) {
    return world.registry().storage<NpcId>().size();
}

/// 按 NpcId 查找实体（O(N) 线性——NPC 数量上限为玩法设计小量级）。
/// 未命中返回 kNullEntity。
inline EntityId findNpcById(World& world, std::uint32_t id) {
    const auto& storage = world.registry().storage<NpcId>();
    for (const EntityId e : storage.entityList()) {
        const NpcId* c = storage.find(e);
        if (c != nullptr && c->id == id) return e;
    }
    return kNullEntity;
}

/// 渲染通道快照行（紧凑原始类型数组：id,x,y,spriteId,animFrame——
/// 仅供测试与消费方约定形状；每旬/tick 一次性填充）
struct NpcRenderRow {
    std::uint32_t id = 0;
    float x = 0.0f;
    float y = 0.0f;
    std::uint32_t spriteId = 0;
    std::uint8_t animFrame = 0;
};

/// 收集渲染通道快照（View<NpcId, NpcPosition, NpcSpriteId, NpcAnimState> 迭代，
/// 插入序 == spawn 序——渲染层无重排假设）
inline std::vector<NpcRenderRow> collectNpcRenderRows(World& world) {
    std::vector<NpcRenderRow> rows;
    View<NpcId, NpcPosition, NpcSpriteId, NpcAnimState> view(world.registry());
    view.forEach([&rows](EntityId, const NpcId& id, const NpcPosition& pos,
                         const NpcSpriteId& sprite, const NpcAnimState& anim) {
        rows.push_back(NpcRenderRow{id.id, pos.x, pos.y, sprite.spriteId, anim.frame});
    });
    return rows;
}

}  // namespace gamecore::ecs
