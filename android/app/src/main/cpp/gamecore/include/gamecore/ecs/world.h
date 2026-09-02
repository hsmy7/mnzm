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

private:
    EntityManager entities_;
    ComponentRegistry registry_;
};

}  // namespace gamecore::ecs
