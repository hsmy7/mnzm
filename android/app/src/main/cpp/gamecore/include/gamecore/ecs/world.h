#pragma once

#include "gamecore/ecs/entity.h"
#include "gamecore/ecs/registry.h"

// ============================================================
// ECS World（phase ①：通用 ECS 骨架）
//
// EntityManager + ComponentRegistry 的组合门面。实体是 ID、组件是
// 纯数据列；World 不存任何"行为"，只维护实体生命周期与组件归属。
// ============================================================
namespace gamecore::ecs {

class World {
public:
    EntityManager& entities() { return entities_; }
    const EntityManager& entities() const { return entities_; }

    ComponentRegistry& registry() { return registry_; }
    const ComponentRegistry& registry() const { return registry_; }

    /// 创建实体
    EntityId createEntity() { return entities_.create(); }

    /// 销毁实体（先清组件再回收句柄）；存活才返回 true
    bool destroyEntity(EntityId e) {
        registry_.eraseFromAll(e);
        return entities_.destroy(e);
    }

    /// 销毁实体（swap-and-pop 变体；R1.6——重建/批量删除等顺序无观察点
    /// 场景专用，把逐实体销毁从 O(N) 降到 O(1)、整批从 O(N²) 降到 O(N)。
    /// 需要保留确定性迭代序的路径一律走 destroyEntity）
    bool destroyEntityUnordered(EntityId e) {
        registry_.eraseFromAllUnordered(e);
        return entities_.destroy(e);
    }

private:
    EntityManager entities_;
    ComponentRegistry registry_;
};

}  // namespace gamecore::ecs
