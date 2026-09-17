#pragma once

#include <cstdint>
#include <cstddef>
#include <limits>
#include <optional>
#include <utility>
#include <vector>

#include "gamecore/ecs/component.h"
#include "gamecore/ecs/entity.h"

// ============================================================
// ECS ComponentStorage（phase ①：通用 ECS 骨架）
//
// 每组件一个"列向量（SoA）+ 保序 SparseSet"：
//   - values_    ：组件列向量（dense，行序 == 实体插入序）
//   - entities_  ：每个 dense 行对应的实体（与 values_ 并行）
//   - sparse_    ：按实体 index 索引 → dense 行（O(1) 查找，无哈希、无迭代污染）
//
// ## 确定性（RNG 红线对齐）
//   1. dense 行序 == 实体插入序（append-only 直至 erase）。
//   2. erase 采用**保序压缩**（从被删行起整体左移）——保持剩余实体相对顺序不变，
//      与 DiscipleStore 的 vector.erase 原位语义一致；代价 O(N)，换取确定性。
//   3. eraseEntityUnordered（R1.6）：swap-and-pop 变体——尾行换入被删行 + 弹尾，
//      O(1)。**仅限顺序无观察点场景**（重建/批量删除：buildDiscipleEntities
//      先销毁全部再按行序重建，销毁集合内相对序无消费者）；确定性迭代序
//      需要保留的路径一律走保序 eraseEntity。
//   4. 禁用 unordered_map 参与迭代；sparse_ 仅用于 O(1) 实体↔行查找。
//
// ## 类型擦除
//   实现 IStorage 基类（virtual eraseEntity/containsEntity/clearAll/typeId），
//   供 ComponentRegistry 统一管理（无需 RTTI——下转为 static_cast）。
// ============================================================
namespace gamecore::ecs {

/// 类型擦除存储基类（registry 统一持有；erase/contains 走虚调用）
class IStorage {
public:
    virtual ~IStorage() = default;
    virtual ComponentTypeId typeId() const = 0;
    virtual bool containsEntity(EntityId e) const = 0;
    virtual bool eraseEntity(EntityId e) = 0;
    /// swap-and-pop 删除（R1.6；顺序无观察点场景专用，见类注释第 3 条）
    virtual bool eraseEntityUnordered(EntityId e) = 0;
    virtual void clearAll() = 0;
    virtual std::size_t size() const = 0;
};

/// 单一组件类型的列存储（SoA + 保序 SparseSet）
template <typename T>
class ComponentStorage : public IStorage {
public:
    static constexpr std::size_t kInvalid = std::numeric_limits<std::size_t>::max();

    ComponentTypeId typeId() const override { return componentTypeId<T>(); }

    // ── 查询 ──
    std::size_t size() const override { return entities_.size(); }

    bool containsEntity(EntityId e) const override { return rowOf(e).has_value(); }

    /// 组件值指针；实体无该组件或悬垂句柄返回 nullptr
    T* find(EntityId e) {
        const auto r = rowOf(e);
        return r ? &values_[*r] : nullptr;
    }

    const T* find(EntityId e) const {
        const auto r = rowOf(e);
        return r ? &values_[*r] : nullptr;
    }

    /// 实体插入有序列表（dense 行序 == 插入序；并行读 values_）
    const std::vector<EntityId>& entityList() const { return entities_; }

    /// 组件列（dense，行序与 entityList() 对齐）
    const std::vector<T>& values() const { return values_; }

    // ── 写 ──
    /// 确保实体存在组件并赋值；已存在则覆盖，否则末尾追加（保持插入序）
    template <typename... Args>
    T& addOrAssign(EntityId e, Args&&... args) {
        if (const auto r = rowOf(e)) {
            values_[*r] = T(std::forward<Args>(args)...);
            return values_[*r];
        }
        ensureSparse(e.index);
        sparse_[e.index] = entities_.size();
        entities_.push_back(e);
        values_.emplace_back(std::forward<Args>(args)...);
        return values_.back();
    }

    /// 删除实体组件（保序压缩；不存在返回 false）
    bool eraseEntity(EntityId e) override {
        const auto r = rowOf(e);
        if (!r) return false;
        const std::size_t row = *r;
        const std::size_t n = entities_.size();
        // 从被删行起整体左移，保持剩余实体相对顺序
        for (std::size_t i = row; i + 1 < n; ++i) {
            values_[i] = std::move(values_[i + 1]);
            entities_[i] = entities_[i + 1];
            sparse_[entities_[i].index] = i;
        }
        values_.pop_back();
        entities_.pop_back();
        if (e.index < sparse_.size() && sparse_[e.index] == row) {
            sparse_[e.index] = kInvalid;
        }
        return true;
    }

    /// 删除实体组件（swap-and-pop：O(1)；**仅限顺序无观察点场景**——
    /// 尾行换入被删行，剩余实体相对序不保留。不存在返回 false）
    bool eraseEntityUnordered(EntityId e) override {
        const auto r = rowOf(e);
        if (!r) return false;
        const std::size_t row = *r;
        const std::size_t last = entities_.size() - 1;
        if (row != last) {
            // 尾行换入被删行（实体与组件同步搬移 + sparse 重锚）
            values_[row] = std::move(values_[last]);
            entities_[row] = entities_[last];
            sparse_[entities_[row].index] = row;
        }
        values_.pop_back();
        entities_.pop_back();
        sparse_[e.index] = kInvalid;
        return true;
    }

    void clearAll() override {
        sparse_.clear();
        entities_.clear();
        values_.clear();
    }

private:
    /// 实体 → dense 行（不存在/悬垂返回 nullopt）
    std::optional<std::size_t> rowOf(EntityId e) const {
        if (e.index >= sparse_.size()) return std::nullopt;
        const std::size_t row = sparse_[e.index];
        if (row == kInvalid) return std::nullopt;
        if (entities_[row] != e) return std::nullopt;  // 代数不匹配（悬垂句柄）
        return row;
    }

    void ensureSparse(std::uint32_t index) {
        if (index + 1u > sparse_.size()) sparse_.resize(index + 1u, kInvalid);
    }

    std::vector<std::size_t> sparse_;   // 按实体 index → dense 行
    std::vector<EntityId> entities_;    // dense 实体列表（插入序）
    std::vector<T> values_;             // 组件列（与 entities_ 并行）
};

}  // namespace gamecore::ecs
