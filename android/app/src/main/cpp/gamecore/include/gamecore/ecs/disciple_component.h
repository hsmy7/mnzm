#pragma once

#include <cstddef>
#include <vector>

#include "gamecore/ecs/entity.h"
#include "gamecore/ecs/world.h"

// ============================================================
// ECS 弟子接入（phase ④：弟子组件化——方案 A 落地边界）
//
// 目标：在不改动 DiscipleStore（权威行序 SoA，RNG 红线）与其 JSON 协议的
// 前提下，把弟子作为**实体**接入 ECS，从而能与其他组件组合、做多组件视图
// 迭代（如"弟子 + 战斗角色 + 筛选标记"），并保持迭代序 == 弟子行序。
//
// 方案：实体携带 `DiscipleRef{row}`（row = DiscipleStore 行索引，纯运行态、
// 不进 JSON 协议）。构建函数把"行序"映射为"实体序"（row i ↔ entity i），
// 使 ECS 实体迭代序天然等于 DiscipleStore 行序（RNG 红线不破）。
//
// ## 边界（诚实声明）
//   DiscipleStore 仍是弟子数据的**唯一权威**；本层是**派生**实体集。
//   死亡/移除弟子时由调用方调 destroyDiscipleEntity(row)；重建走
//   buildDiscipleEntities（草稿场景一次性装配）。
// ============================================================
namespace gamecore::ecs {

/// 弟子实体上的引用组件（row = DiscipleStore 行索引）
struct DiscipleRef {
    std::size_t row = 0;
};

/// 销毁所有携带 DiscipleRef 的实体（副本迭代防迭代器失效）
inline void destroyDiscipleEntities(World& world) {
    auto& storage = world.registry().storage<DiscipleRef>();
    const auto entityList = storage.entityList();  // 拷贝，避免边迭代边销毁
    for (const EntityId e : entityList) {
        world.destroyEntity(e);
    }
}

/// 按行序装配弟子实体（row i ↔ entity i，每实体挂 DiscipleRef{row}）。
/// 先销毁既有 DiscipleRef 实体，再按 rowCount 建新——用于草稿装配/读档对齐。
inline std::vector<EntityId> buildDiscipleEntities(World& world, std::size_t rowCount) {
    destroyDiscipleEntities(world);
    std::vector<EntityId> entityByRow;
    entityByRow.reserve(rowCount);
    for (std::size_t row = 0; row < rowCount; ++row) {
        const EntityId e = world.createEntity();
        world.registry().storage<DiscipleRef>().addOrAssign(e, DiscipleRef{row});
        entityByRow.push_back(e);
    }
    return entityByRow;
}

/// 移除单个弟子实体（row → entity）
inline void destroyDiscipleEntity(World& world, std::size_t row) {
    auto& storage = world.registry().storage<DiscipleRef>();
    // 以行序逐个查找（确定性；row 通常较小）
    const auto& entities = storage.entityList();
    for (const EntityId e : entities) {
        const DiscipleRef* ref = storage.find(e);
        if (ref && ref->row == row) {
            world.destroyEntity(e);
            return;
        }
    }
}

}  // namespace gamecore::ecs
