#pragma once

#include <algorithm>
#include <cstddef>
#include <memory>
#include <string>
#include <vector>

#include "gamecore/ecs/world.h"

// ============================================================
// ECS System 调度框架（phase ②）
//
// 目标是取代"过程式 system 函数操作大状态对象"（如 month_settlement.h 的
// 8 步编排 + SystemManager.onMonthlyEvent 按 @SystemPriority 升序扇出）。
// 这里提供显式依赖、可测、可组合、可确定性的 System 图：
//   - ISystem：持 name + priority，run(World&) 内以 View<...> 查询迭代组件。
//   - SystemScheduler：注册式 + 优先级调度，**先串行**（确定性红线——
//     System 迭代序 == 实体行序）；并行化仅限无共享写的独立 system（phase ③
//     JobSystem 埋点：@ isParallelizable + scheduleOn）。
//
// ## 确定性
//   attach 顺序 == 稳定序；调度按 (priority, attachOrder) 升序串行。
//   系统不得重排实体序、不得引入部分序——RNG 抽取序由现有系统保持。
// ============================================================
namespace gamecore::ecs {

/// 无状态系统基类（注册时声明优先级；run 内以 View 查询迭代）
class ISystem {
public:
    virtual ~ISystem() = default;

    /// 系统唯一名（日志/调试）
    virtual const char* name() const = 0;

    /// 调度优先级（升序执行；同优先级按挂载序）
    virtual int priority() const = 0;

    /// 是否可并行（无共享写的独立 system——phase ③ JobSystem 消费；默认 false）
    virtual bool isParallelizable() const { return false; }

    /// 主入口：对 World 上拥有所需组件的实体做批处理迭代
    virtual void run(World& world) = 0;
};

/// 优先级串行调度器（确定性）
class SystemScheduler {
public:
    /// 注册系统（attach 序即同优先级时的稳定序）
    void attach(std::unique_ptr<ISystem> system) {
        systems_.push_back(Entry{std::move(system), order_++});
        dirty_ = true;
    }

    /// 按 (priority, attachOrder) 升序串行执行全部系统
    void runAll(World& world) {
        ensureSorted();
        for (auto& entry : systems_) {
            entry.system->run(world);
        }
    }

    std::size_t size() const { return systems_.size(); }

    void clear() {
        systems_.clear();
        order_ = 0;
        dirty_ = false;
    }

private:
    struct Entry {
        std::unique_ptr<ISystem> system;
        std::size_t order;
    };

    void ensureSorted() {
        if (!dirty_) return;
        std::stable_sort(systems_.begin(), systems_.end(),
                         [](const Entry& a, const Entry& b) {
                             if (a.system->priority() != b.system->priority()) {
                                 return a.system->priority() < b.system->priority();
                             }
                             return a.order < b.order;
                         });
        dirty_ = false;
    }

    std::vector<Entry> systems_;
    std::size_t order_ = 0;
    bool dirty_ = true;
};

}  // namespace gamecore::ecs
