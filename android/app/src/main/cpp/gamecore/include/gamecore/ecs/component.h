#pragma once

#include <atomic>
#include <cstdint>

// ============================================================
// ECS Component 类型标签（phase ①：通用 ECS 骨架）
//
// 每个组件类型注册一个唯一、进程内稳定的 ComponentTypeId（EnTT 家族模式）。
// 该 id 仅用于存储注册/查询索引，**不进入 JSON/存档协议**（零序列化）。
// 组件 id 的分配顺序由代码结构决定（首次访问即编号），在确定性流程内恒定。
// ============================================================
namespace gamecore::ecs {

using ComponentTypeId = std::uint32_t;

/// 全局单调递增的类型 id 分配器（进程内唯一顺序）
inline ComponentTypeId nextComponentTypeId() {
    static std::atomic<ComponentTypeId> counter{0};
    return counter.fetch_add(1, std::memory_order_relaxed);
}

/// 编译期/首次访问惰性绑定：每个 T 得到唯一、恒定的 ComponentTypeId
template <typename T>
inline ComponentTypeId componentTypeId() {
    static const ComponentTypeId id = nextComponentTypeId();
    return id;
}

}  // namespace gamecore::ecs
