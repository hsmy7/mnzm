#pragma once

#include <cstdint>
#include <vector>

// ============================================================
// ECS Entity 句柄 + EntityManager（phase ①：通用 ECS 骨架）
//
// 确定性红线（RNG 对拍命门）：
//   1. create() 按 0..N 递增分配稠密 index（不随机、不并行），
//      allocate 序 == 代码序 == 实体行序——与 DiscipleStore 行序红线一致。
//   2. 迭代序 == 稠密数组序（见 storage.h 的 ComponentStorage dense 顺序）；
//      禁止 unordered_map 参与业务迭代（id→row 仅用于 O(1) 查找）。
//   3. Entity 是 ID 不是对象；destroy 后 index 进入 freelist（LIFO），
//      generation 递增防悬垂（悬垂句柄 isAlive=false）。
//
// 与 json_codec / rng / DiscipleStore 完全解耦：本文件零存档协议依赖。
// ============================================================
namespace gamecore::ecs {

/// 无效实体哨兵（index == UINT32_MAX）
inline constexpr std::uint32_t kInvalidEntityIndex = 0xFFFFFFFFu;

/// 类型安全实体句柄：index（稠密下标）+ generation（防悬垂代数）
struct EntityId {
    std::uint32_t index = kInvalidEntityIndex;
    std::uint32_t generation = 0;

    bool operator==(const EntityId&) const = default;
    bool operator!=(const EntityId&) const = default;
};

/// 无效实体常量
inline constexpr EntityId kNullEntity{kInvalidEntityIndex, 0};

/// 判断句柄是否是有效实体（非 kNullEntity）
inline bool isValid(EntityId e) { return e.index != kInvalidEntityIndex; }

/// 实体生成/回收管理器：分配 0..N 稠密 index，destroy 进 freelist + generation 递增
class EntityManager {
public:
    /// 分配一个新实体（index 从 0 递增；复用 freelance — LIFO，确定性的分配序）
    EntityId create() {
        std::size_t index;
        if (!freeList_.empty()) {
            index = freeList_.back();
            freeList_.pop_back();
        } else {
            index = generations_.size();
            generations_.push_back(1);
            alive_.push_back(1);
        }
        alive_[index] = 1;
        ++aliveCount_;
        return EntityId{static_cast<std::uint32_t>(index), generations_[index]};
    }

    /// 销毁实体（generation 递增 + 入 freelist），悬垂句柄随之失效
    /// @return 原实体存活时才销毁并返回 true，否则 false
    bool destroy(EntityId e) {
        if (!isAlive(e)) return false;
        alive_[e.index] = 0;
        ++generations_[e.index];  // 代数递增，老句柄失效
        freeList_.push_back(e.index);
        --aliveCount_;
        return true;
    }

    /// 句柄是否存活（index 在界内 && 存活 && generation 匹配）
    bool isAlive(EntityId e) const {
        if (e.index >= alive_.size()) return false;
        return alive_[e.index] != 0 && generations_[e.index] == e.generation;
    }

    /// 当前存活实体数
    std::size_t aliveCount() const { return aliveCount_; }

    /// 由稠密 index 重建当前代数实体句柄（index 界内则返回，否则断言有效语义由调用方保证）
    EntityId entityAtIndex(std::size_t index) const {
        return EntityId{static_cast<std::uint32_t>(index), generations_[index]};
    }

    /// 最大 index 容量（已分配过的顶点数；含已销毁）
    std::size_t capacity() const { return generations_.size(); }

    /// 清空全部（测试用）
    void clear() {
        generations_.clear();
        alive_.clear();
        freeList_.clear();
        aliveCount_ = 0;
    }

private:
    std::vector<std::uint32_t> generations_;  // 每个 index 当前代数
    std::vector<std::uint8_t> alive_;         // 0/1 存活标
    std::vector<std::size_t> freeList_;       // 可复用 index（LIFO）
    std::size_t aliveCount_ = 0;
};

}  // namespace gamecore::ecs
