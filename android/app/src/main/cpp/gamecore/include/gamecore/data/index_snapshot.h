#pragma once

#include <atomic>
#include <cstddef>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

// ============================================================
// IdIndexSnapshot —— data 层按 id 查询的失效自检索引快照
// （2026-09-20 收尾批根治：函数级静态指针索引的悬挂 UAF）
//
// ## 缺陷背景（桌面全量单进程直跑段错误的根因）
// talentById / physiqueById / affixById / forgeRecipeById / pillRecipeById
// 原为函数级 `static const std::map<std::string, const T*> kIndex` 一次性
// 构建，缓存指向数据向量元素的**裸指针**。数据注入（data_inject 对向量整体
// move 替换）或测试复位（resetGameDataStoreForTest + 重注入）后旧索引全部
// 悬挂——"索引先于注入被构建 + 注入后再查询"即 use-after-free（读已释放块
// 的 freelist 编码 → bad_alloc / 段错误，随堆复用状态显形为不同症状）。
// ctest 按用例分进程跑掩盖了它（每进程索引重建），单进程直跑必现。
//
// ## 语义
// 构建时记录源向量 data()+size()；每次查询前比对，不一致（向量被替换）
// 即重建。稳态（注入后 data_store「表容器地址不再变」契约下）比对恒相等、
// 零重建，仅多一次 acquire load 与两次整数比较。
//
// ## 并发与内存模型（RCU 发布模式）
// 读路径无锁：原子指针 load 快照后只读。重建路径持互斥（双检），新快照
// 构建完整后 release-store 发布。旧快照**不释放**、由进程生命周期的静态
// 持有列表保管——替换在生产上至多发生一次（初始化期数据注入），测试每
// 用例重注入也仅累积 KB 级（map + 指针），以不释放换"读者永不见悬垂"，
// 免去 hazard pointer/引用计数的复杂度。JobSystem worker 并行核心批次
// （hpMpEffectsFor → talentEffectsFor → talentById）会并发查询：多个
// 读者并发 load 同一快照安全；并发检测到失效时互斥保证单次重建、后到
// 者复用先到者成果。
//
// ## 为什么不用 std::atomic<std::shared_ptr>
// 标准类型，但 llvm-mingw libc++ 未提供非平凡特化（C++20 门槛外的实现
// 差异）；本模式只用 atomic<T*> 与 mutex，三大标准库行为一致。
// ============================================================

namespace gamecore::data::detail {

/// 默认 id 提取器：行类型含 `std::string id` 成员（data 层全部表满足）。
struct DefaultIdOf {
    template <typename Row>
    std::string operator()(const Row& row) const {
        return row.id;
    }
};

template <typename Row, typename IdOf = DefaultIdOf>
class IdIndexSnapshot {
public:
    /// 查询行指针；[rows] 必须是索引对应的权威向量（每次调用传入，比对用）。
    /// 返回 nullptr = id 不存在。返回指针仅在 rows 未被整体替换前有效
    /// （与 data_store 指针稳定性契约同一生命周期）。
    const Row* find(const std::vector<Row>& rows, const std::string& id) {
        const Snapshot* snap = snap_.load(std::memory_order_acquire);
        if (snap == nullptr || snap->base != rows.data() || snap->count != rows.size()) {
            snap = rebuildLocked(rows);
        }
        const auto it = snap->byId.find(id);
        return it == snap->byId.end() ? nullptr : it->second;
    }

private:
    struct Snapshot {
        std::map<std::string, const Row*> byId;
        const Row* base = nullptr;
        std::size_t count = 0;
    };

    /// 重建（互斥双检）：新快照完整构建后发布；旧快照移交静态持有列表
    /// （永不释放——见文件头内存模型说明）。
    const Snapshot* rebuildLocked(const std::vector<Row>& rows) {
        std::lock_guard<std::mutex> lock(rebuildMutex_);
        const Snapshot* current = snap_.load(std::memory_order_acquire);
        if (current != nullptr && current->base == rows.data() &&
            current->count == rows.size()) {
            return current;  // 并发重建者已抢先完成
        }
        auto owned = std::make_unique<Snapshot>();
        owned->base = rows.data();
        owned->count = rows.size();
        for (const auto& row : rows) owned->byId.emplace(IdOf{}(row), &row);
        const Snapshot* published = owned.get();
        retired_.push_back(std::move(owned));  // 静态生命周期持有，读者安全
        snap_.store(published, std::memory_order_release);
        return published;
    }

    std::atomic<const Snapshot*> snap_{nullptr};
    std::mutex rebuildMutex_;
    /// 全部历史快照的持有列表（rebuildMutex_ 内变更；读者从不触及本容器，
    /// 只经 snap_ 原子指针读取其中的快照对象）。
    std::vector<std::unique_ptr<Snapshot>> retired_;
};

}  // namespace gamecore::data::detail
