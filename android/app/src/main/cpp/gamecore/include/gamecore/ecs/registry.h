#pragma once

#include <cstdint>
#include <memory>
#include <unordered_map>
#include <utility>

#include "gamecore/ecs/component.h"
#include "gamecore/ecs/entity.h"
#include "gamecore/ecs/storage.h"

// ============================================================
// ECS ComponentRegistry（phase ①：通用 ECS 骨架）
//
// 组件类型 → 类型擦除存储（IStorage*，实际经 static_cast 下转为
// ComponentStorage<T>*——无 RTTI 兼容）。仅持有存储指针，不放实体/迭代。
// 查询走 ComponentStorage<T> 的 O(1) find/dense 迭代。
// ============================================================
namespace gamecore::ecs {

class ComponentRegistry {
public:
    /// 获取（首次访问则创建）组件存储
    template <typename T>
    ComponentStorage<T>& storage() {
        const ComponentTypeId id = componentTypeId<T>();
        auto& slot = storages_[id];
        if (!slot) slot = std::make_unique<ComponentStorage<T>>();
        return *static_cast<ComponentStorage<T>*>(slot.get());
    }

    /// 只读获取；不存在返回 nullptr
    template <typename T>
    const ComponentStorage<T>* storageOrNull() const {
        const ComponentTypeId id = componentTypeId<T>();
        const auto it = storages_.find(id);
        if (it == storages_.end() || !it->second) return nullptr;
        return static_cast<const ComponentStorage<T>*>(it->second.get());
    }

    template <typename T>
    ComponentStorage<T>* storageOrNull() {
        const ComponentTypeId id = componentTypeId<T>();
        const auto it = storages_.find(id);
        if (it == storages_.end() || !it->second) return nullptr;
        return static_cast<ComponentStorage<T>*>(it->second.get());
    }

    /// 是否已创建某组件存储
    template <typename T>
    bool hasStorage() const {
        return storageOrNull<T>() != nullptr;
    }

    /// 从所有已存在组件存储中移除实体（destroyEntity 用）
    void eraseFromAll(EntityId e) {
        for (auto& [id, storage] : storages_) {
            (void)id;
            if (storage) storage->eraseEntity(e);
        }
    }

    std::size_t storageCount() const { return storages_.size(); }

    void clearAll() {
        for (auto& [id, storage] : storages_) {
            (void)id;
            if (storage) storage->clearAll();
        }
    }

private:
    std::unordered_map<ComponentTypeId, std::unique_ptr<IStorage>> storages_;
};

}  // namespace gamecore::ecs
