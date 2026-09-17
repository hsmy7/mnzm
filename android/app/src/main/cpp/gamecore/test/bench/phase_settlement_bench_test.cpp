#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <new>

#include "gamecore/ecs/world.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/phase_settlement.h"

// ============================================================
// PhaseSettlementBench — 每旬结算（e2e）malloc 计数 + 耗时基准
// （重构方案 2026-09-17 R1 收官 bench，G1 证明 + §"CI 与度量执法"第 1 条）
//
// G1（方案 §0.2）：每旬结算堆分配次数（5000 弟子）基线 ~15 万
// （committedDisciples 物化 + 逐实体 map 重建，R1.1/R1.2/R1.3 已退役该
// 形状）→ 目标 **< 1 万次/旬**。
//
// 计数原理：本翻译单元定义全局 operator new/delete 替换函数（malloc 后端
// + 原子计数）——进程级生效；gtest_discover_tests 每用例独立进程，计数
// 天然隔离。堆分配次数对固定代码路径是**确定性**的（非计时抖动），故
// G1 断言可作硬门禁；耗时仅打印供人工观测（CI 抖动不设断言）。
//
// 场景：runPhaseSettlement 全八步（0 自动装备 → 1-5 核心批次 → 6 自动
// 丹药 → 7 突破+亲属赠送）——与生产 AUTHORITATIVE 每旬结算同构（核心
// 批次为串行版；并行版只改迭代域不改分配形状）。
//
// ## 口径（G1 基线同族可比）
// G1 基线 ~15 万 = committedDisciples 物化（R1.2 退役）+ 每旬/逐实体
// map 重建（R1.1/R1.3 退役，含每旬入口 indexById O(D) 全量重建——R1.3
// numericIdToRow 免重建缓存收尾清除）。故 G1 门禁场景 = 与 Kotlin
// Phase0 基线同族的修炼热路径结算（5000 弟子，无实例清单）——
// MallocCountPerPhase5000 硬门禁。
// 带实例清单（每弟子 1 功法 + 2 装备）的真实快照形态另测（MallocCountScaling
// 信息观测 + TimingPerPhase）：其残差分配 = 桶视图节点与熟练度/孕养
// pending 提交管线的**数据驱动**每旬分配（随实际在册实例数伸缩，非
// materialize/map 重建形状），登记为后续形状观察项（方案 §7.2），
// 不在本批 G1 门口径内。
// ============================================================

// ── 全局 operator new/delete 替换（malloc 后端 + 堆分配计数）────────
// 计数为 relaxed 原子（bench 串行；对潜在后台线程的乱序不敏感——只求
// 计数不丢，不求精确顺序）。对齐分配走库默认通道（引擎无 over-aligned
// 类型，与本替换无交叉）。
namespace gamecore {
std::atomic<std::uint64_t> g_heapAllocCount{0};
}

void* operator new(std::size_t size) {
    gamecore::g_heapAllocCount.fetch_add(1, std::memory_order_relaxed);
    if (void* p = std::malloc(size)) return p;
    throw std::bad_alloc();
}

void* operator new[](std::size_t size) {
    gamecore::g_heapAllocCount.fetch_add(1, std::memory_order_relaxed);
    if (void* p = std::malloc(size)) return p;
    throw std::bad_alloc();
}

void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { std::free(p); }

namespace gamecore {
namespace {

using state::Disciple;
using state::EquipmentInstance;
using state::GameState;
using state::ManualInstance;

constexpr int32_t kBenchDisciples = 5000;
/// G1 门禁（方案 §0.2）：每旬结算堆分配 < 1 万次（5000 弟子）
constexpr std::uint64_t kG1MallocBudget = 10000;

/// 最小存活弟子（炼气一层；修炼远未满——每旬累积路径活跃）
Disciple makeBenchDisciple(int32_t n) {
    Disciple d;
    d.id = std::to_string(n);
    d.name = "弟子" + std::to_string(n);          // ≤15 字节（跨工具链 SSO）
    d.realm = 9;
    d.realmLayer = 1;
    d.cultivation = 10.0;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.age = 16;
    d.lifespan = 80;
    return d;
}

/// 全量场景：每弟子 1 功法实例 + 2 件已装备实例（实例 id 全局唯一——
/// 实例 id 唯一不变量；修炼/熟练度/孕养全链路活跃）
void populateInstances(GameState& state) {
    state.disciples.manualIds.resize(kBenchDisciples);
    state.disciples.weaponIds.resize(kBenchDisciples);
    state.disciples.armorIds.resize(kBenchDisciples);
    for (int32_t n = 1; n <= kBenchDisciples; ++n) {
        const std::string id = std::to_string(n);

        ManualInstance mn;
        mn.id = "m" + id;
        mn.name = "功法" + id;
        mn.type = "MIND";
        mn.ownerId = id;
        mn.isLearned = true;
        state.manualInstances.push_back(mn);
        state.disciples.manualIds[n - 1] = {mn.id};

        EquipmentInstance w;
        w.id = "w" + id;
        w.name = "剑" + id;
        w.slot = "WEAPON";
        w.ownerId = id;
        w.isEquipped = true;
        state.equipmentInstances.push_back(w);
        state.disciples.weaponIds[n - 1] = w.id;

        EquipmentInstance a;
        a.id = "a" + id;
        a.name = "甲" + id;
        a.slot = "ARMOR";
        a.ownerId = id;
        a.isEquipped = true;
        state.equipmentInstances.push_back(a);
        state.disciples.armorIds[n - 1] = a.id;
    }
}

/// 场景装配（discipleCount ≤ kBenchDisciples）
GameState makeSettlementState(int32_t discipleCount, bool withInstances) {
    GameState state;
    for (int32_t n = 1; n <= discipleCount; ++n) {
        state.disciples.appendDisciple(makeBenchDisciple(n));
    }
    if (withInstances) populateInstances(state);
    return state;
}

/// 单旬结算 e2e（与生产每旬结算同构；核心批次串行版）
void runOnePhase(GameState& state, rng::RngManager& rng, ecs::World& world) {
    system::runPhaseSettlement(state, rng, world);
}

/// 采样取最小值（warmup 预热缓存/分配器）
template <typename F>
double bestOfUs(int warmup, int samples, F&& block) {
    for (int i = 0; i < warmup; ++i) block();
    double best = 1e18;
    for (int i = 0; i < samples; ++i) {
        const auto t0 = std::chrono::steady_clock::now();
        block();
        const auto t1 = std::chrono::steady_clock::now();
        const double us =
            std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count() / 1000.0;
        if (us < best) best = us;
    }
    return best;
}

}  // namespace

// ── G1 硬门禁：5000 弟子修炼热路径结算（G1 基线同族场景）────────────
TEST(PhaseSettlementBench, MallocCountPerPhase5000) {
    GameState state = makeSettlementState(kBenchDisciples, false);
    rng::RngManager rng;
    rng.initSystemSeed(42);
    ecs::World world;

    // 预热一轮（分配器缓存；预热轮计数弃置）——预热后状态已推进一旬，
    // 计数轮与其同构（稳态每旬形态）
    runOnePhase(state, rng, world);

    g_heapAllocCount.store(0, std::memory_order_relaxed);
    const auto t0 = std::chrono::steady_clock::now();
    runOnePhase(state, rng, world);
    const auto t1 = std::chrono::steady_clock::now();
    const std::uint64_t allocs = g_heapAllocCount.load(std::memory_order_relaxed);
    const double us =
        std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count() / 1000.0;

    std::printf(
        "[PhaseSettlementBench] runPhaseSettlement D=5000 core: %llu mallocs, %.0f us\n",
        static_cast<unsigned long long>(allocs), us);
    // G1 硬门禁（确定性计数，非计时抖动）——超限即本断言红
    EXPECT_LT(allocs, kG1MallocBudget);
}

// ── 信息观测：带实例清单的真实快照形态（每弟子 1 功法 + 2 装备）────
// 残差 = 桶节点 + 熟练度/孕养 pending 提交管线的每旬分配（数据驱动，
// 随在册实例数伸缩）——方案 §7.2 登记的后续形状观察项，无门禁断言。
TEST(PhaseSettlementBench, MallocCountScaling) {
    GameState state = makeSettlementState(kBenchDisciples, true);
    rng::RngManager rng;
    rng.initSystemSeed(42);
    ecs::World world;

    runOnePhase(state, rng, world);  // 预热（计数弃置）

    g_heapAllocCount.store(0, std::memory_order_relaxed);
    runOnePhase(state, rng, world);
    const std::uint64_t allocs = g_heapAllocCount.load(std::memory_order_relaxed);
    std::printf(
        "[PhaseSettlementBench] runPhaseSettlement D=5000 full-inventory: %llu mallocs\n",
        static_cast<unsigned long long>(allocs));
}

// ── 耗时三档（打印对照，无断言）────────────────────────────────────
TEST(PhaseSettlementBench, TimingPerPhase) {
    const int32_t counts[] = {100, 1000, 5000};
    for (const int32_t n : counts) {
        GameState state = makeSettlementState(n, true);
        rng::RngManager rng;
        rng.initSystemSeed(42);
        ecs::World world;
        const double us = bestOfUs(2, 5, [&] {
            runOnePhase(state, rng, world);
        });
        std::printf("[PhaseSettlementBench] runPhaseSettlement D=%d: %.1f us (%.3f us/disciple)\n",
                    n, us, us / n);
    }
    std::printf("[PhaseSettlementBench] baseline: Kotlin Phase0 1189us@5000 (core only)\n");
}

}  // namespace gamecore
