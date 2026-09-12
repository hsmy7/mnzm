#pragma once

#include <cstddef>
#include <tuple>
#include <utility>

#include "gamecore/ecs/component.h"
#include "gamecore/ecs/entity.h"
#include "gamecore/ecs/registry.h"
#include "gamecore/ecs/storage.h"

// ============================================================
// ECS View（phase ②：ECS System 调度框架的核心访问入口）
//
// View<Cs...> = 按组件集查询/迭代"同时拥有 Cs... 组件"的实体集合。
// - 迭代以**首个组件存储的 dense 行序**为主序（== 实体插入序，确定性红线），
//   其余组件仅做存在性过滤——因此迭代序稳定、可复现，不重排。
// - forEach 回调接收实体与各组件**可变引用**（只读由调用方收 `const T&`）。
//
// ## 确定性
//   迭代序 == 首组件插入序；与 RNG 行序红线一致。
//   forEach 回调内**禁止增删实体**（否则迭代器/引用失效）——文档约定。
// ============================================================
namespace gamecore::ecs {

template <typename... Cs>
class View {
public:
    explicit View(ComponentRegistry& registry) {
        init(registry, std::index_sequence_for<Cs...>{});
    }

    /// 命中实体数（同时拥有全部 Cs 组件）
    std::size_t count() const {
        const auto& first = *std::get<0>(storages_);
        const auto& ents = first.entityList();
        std::size_t n = 0;
        for (const auto& e : ents) {
            if (containsAll(e, std::index_sequence_for<Cs...>{})) ++n;
        }
        return n;
    }

    /// 迭代：fn(EntityId, Cs&...)。回调内禁止增删实体；只读请收 `const T&`。
    template <typename Fn>
    void forEach(Fn&& fn) {
        forEachImpl(std::forward<Fn>(fn), std::index_sequence_for<Cs...>{});
    }

private:
    std::tuple<ComponentStorage<Cs>*...> storages_;

    template <typename Fn, std::size_t... Is>
    void forEachImpl(Fn&& fn, std::index_sequence<Is...>) {
        const auto& first = *std::get<0>(storages_);
        const auto& ents = first.entityList();
        const auto present = [&](EntityId e) {
            return containsAll(e, std::index_sequence_for<Cs...>{});
        };
        for (std::size_t i = 0; i < ents.size(); ++i) {
            const EntityId e = ents[i];
            if (present(e)) {
                fn(e, (*std::get<Is>(storages_)->find(e))...);
            }
        }
    }

    template <std::size_t... Is>
    void init(ComponentRegistry& r, std::index_sequence<Is...>) {
        ((std::get<Is>(storages_) = &r.storage<Cs>()), ...);
    }

    template <std::size_t... Is>
    bool containsAll(EntityId e, std::index_sequence<Is...>) const {
        return (std::get<Is>(storages_)->containsEntity(e) && ...);
    }
};

}  // namespace gamecore::ecs
