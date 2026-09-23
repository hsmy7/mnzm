#include <gtest/gtest.h>

#include <cstdint>
#include <vector>

#include "gpu/GpuBudgetMath.h"

// ============================================================
// gpu_budget_math_test — GpuAllocator 桌面可测纯逻辑面（MR2-P2.1/D1）
//
// D1 验收「stats 单测自洽」的桌面腿：desktop ctest 无 Vulkan 头/无设备，
// GpuBudgetMath 刻意零 Vulkan 依赖（uint64_t 原语参数），使预算估算兜底与
// 大图 dedicated 阈值判定在此行为锁定。VMA 设备路径（vmaGetHeapBudgets 等）
// 的实测归 pending-device（附录 A.4 对照位）。
// ============================================================

namespace gamecore {
namespace {

// ── 大图 dedicated 阈值（方案 D1.5；审计 A-5 量级） ──────────────

TEST(GpuBudgetMathTest, DedicatedThresholdIsSixteenMiB) {
    EXPECT_EQ(gpu::kDedicatedImageThresholdBytes, 16ull * 1024 * 1024)
        << "大图 dedicated 阈值应为 16MiB（主图集解码 16.8MiB 起判 dedicated）";
}

TEST(GpuBudgetMathTest, DedicatedThresholdBoundary) {
    // 4096×2300 RGBA8 单级 = 37.7MiB（审计 A-5 bg_horizontal）→ dedicated
    EXPECT_TRUE(gpu::shouldUseDedicatedAllocation(4096ull * 2300 * 4));
    // 2048² 单级 = 16MiB 整（主图集 mip0）→ 恰达阈值 → dedicated
    EXPECT_TRUE(gpu::shouldUseDedicatedAllocation(2048ull * 2048 * 4));
    // 2048² − 1 → 不判 dedicated（子分配池仍可容纳）
    EXPECT_FALSE(gpu::shouldUseDedicatedAllocation(2048ull * 2048 * 4 - 1));
    // 零尺寸防御
    EXPECT_FALSE(gpu::shouldUseDedicatedAllocation(0));
}

// ── 预算估算兜底（D1.6：budget 扩展不可用时按堆容量估算） ─────────

TEST(GpuBudgetMathTest, EstimateSumsDeviceLocalHeapsOnly) {
    // 典型 Android UMA：1× DEVICE_LOCAL（含系统占用）+ 1× host only
    const std::vector<uint64_t> sizes = {2ull * 1024 * 1024 * 1024, 512ull * 1024 * 1024};
    const std::vector<uint32_t> flags = {gpu::kHeapFlagDeviceLocal, 0};
    EXPECT_EQ(gpu::estimateBudgetFromHeaps(2, sizes.data(), flags.data()),
              2ull * 1024 * 1024 * 1024);
}

TEST(GpuBudgetMathTest, EstimateSumsAllDeviceLocalHeaps) {
    const std::vector<uint64_t> sizes = {1ull << 30, 2ull << 30};  // 1GiB + 2GiB
    const std::vector<uint32_t> flags = {gpu::kHeapFlagDeviceLocal, gpu::kHeapFlagDeviceLocal};
    EXPECT_EQ(gpu::estimateBudgetFromHeaps(2, sizes.data(), flags.data()), 3ull << 30);
}

TEST(GpuBudgetMathTest, EstimateFallsBackToTotalWhenNoDeviceLocalFlag) {
    // 统一内存架构未标志化（个别驱动）：零 DEVICE_LOCAL → 回退全堆和，
    // 避免零预算让 stats 消费方误判 OOM
    const std::vector<uint64_t> sizes = {1ull << 30, 512ull << 20};
    const std::vector<uint32_t> flags = {0, 0};
    EXPECT_EQ(gpu::estimateBudgetFromHeaps(2, sizes.data(), flags.data()),
              (1ull << 30) + (512ull << 20));
}

TEST(GpuBudgetMathTest, EstimateHandlesNullFlagsAndEmptyHeaps) {
    const std::vector<uint64_t> sizes = {1ull << 30};
    // flags 缺失（防御）→ 无 DEVICE_LOCAL 信息 → 回退 total
    EXPECT_EQ(gpu::estimateBudgetFromHeaps(1, sizes.data(), nullptr), 1ull << 30);
    // 零堆 → 0（调用方 GpuAllocator 用真实 heapCount，此为纯函数防御面）
    EXPECT_EQ(gpu::estimateBudgetFromHeaps(0, sizes.data(), nullptr), 0);
}

}  // namespace
}  // namespace gamecore
