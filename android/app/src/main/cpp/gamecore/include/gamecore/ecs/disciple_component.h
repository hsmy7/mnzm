#pragma once

#include <cstddef>
#include <vector>

#include "gamecore/ecs/entity.h"
#include "gamecore/ecs/view.h"
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
// ## 桥接规范（所有 System 须遵守）
//   1. DiscipleStore 是弟子数据的**唯一权威**；本层是**派生**迭代域。
//   2. 任何 System 在 View<DiscipleRef> 迭代前必须先调 syncDiscipleEntities
//      ——校验"View 迭代序 == DiscipleStore 行序"不变量（数量一致 + 行号
//      严格升序 0..N-1），被破坏（弟子增删后实体集漂移）即全量重建恢复
//      row i ↔ entity i。不变量校验兜底，不靠调用方自觉。
//   3. 弟子行地址一律取自 DiscipleRef.row（组件字段）；其余顺序假设一律
//      禁止（含"dense 序必然 == 行序"的裸推断——校验外的信任即漂移入口）。
//   4. 消耗 RNG 的系统（丹药/突破/亲属赠送等）必须在行序迭代中处理弟子
//      （抽取序 == Kotlin ids 序）；同步完成后 View 序已保证 == 行序。
//   5. 迭代回调内禁止增删实体（View 约定）；结构变更（招募/死亡/叛逃）
//      留给下一旬 sync 重建——本旬内行结构不变是结算系统的既有契约。
// ============================================================
namespace gamecore::ecs {

/// 弟子实体上的引用组件（row = DiscipleStore 行索引）
struct DiscipleRef {
    std::size_t row = 0;
};

/// 销毁所有携带 DiscipleRef 的实体（副本迭代防迭代器失效）。
/// R1.6：swap-and-pop 批量销毁（destroyEntityUnordered）——本函数只服务
/// buildDiscipleEntities 的"先全清再按行序重建"场景（草稿装配/读档对齐/
/// sync 漂移自愈），销毁集合内实体相对序无任何观察点；整批复杂度
/// O(D²) → O(D)。需要保序单删的路径走 destroyDiscipleEntity（保序压缩）。
inline void destroyDiscipleEntities(World& world) {
    auto& storage = world.registry().storage<DiscipleRef>();
    const auto entityList = storage.entityList();  // 拷贝，避免边迭代边销毁
    for (const EntityId e : entityList) {
        world.destroyEntityUnordered(e);
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

/// 保序校验 + 惰性同步（桥接规范第 2 条的实现；View 迭代前置步骤）。
///
/// 校验不变量："View<DiscipleRef> 迭代序 == DiscipleStore 行序"，即
///   (a) 实体数 == rowCount，且
///   (b) 迭代序上 DiscipleRef.row 严格升序 0..N-1（position i 处 ref.row==i）。
/// 成立 → 原样返回按行序的实体表（entityByRow[i] 的 ref.row == i，零重建）；
/// 被破坏（招募/死亡/叛逃后实体集漂移）→ buildDiscipleEntities 全量重建。
///
/// 返回值即行序迭代域：position == 行号，组件字段为权威行地址。O(N) 一次
/// 线性校验，相对每旬核心批次成本可忽略；稳态（无弟子增删）零分配直通。
inline std::vector<EntityId> syncDiscipleEntities(World& world,
                                                  std::size_t rowCount) {
    auto& storage = world.registry().storage<DiscipleRef>();
    if (storage.size() == rowCount) {
        std::vector<EntityId> entityByRow;
        entityByRow.reserve(rowCount);
        bool ordered = true;
        std::size_t expect = 0;
        // View<DiscipleRef> 单组件查询：迭代序 == 该存储 dense 序（保序压缩，
        // 删除不重排）——与 View 对外承诺一致，这里即被校验的迭代域本体。
        View<DiscipleRef> view(world.registry());
        view.forEach([&](EntityId e, DiscipleRef& ref) {
            if (ref.row != expect) ordered = false;
            entityByRow.push_back(e);
            ++expect;
        });
        if (ordered) return entityByRow;
    }
    return buildDiscipleEntities(world, rowCount);
}

}  // namespace gamecore::ecs
