#pragma once

// ============================================================
// GpuBudgetMath — GpuAllocator 的桌面可测纯逻辑面（MR2-P2.1/D1）
//
// 本头文件【零 Vulkan 依赖】（不 include vulkan.h / vk_mem_alloc.h，
// 参数全部用 uint64_t 原语），使 desktop ctest（无 Vulkan 头/无设备）
// 能对预算估算与大图 dedicated 阈值判定做行为单测。
// 依赖 Vulkan 类型的分配面在 GpuAllocator.h/.cpp；本文件只承载其中
// 可提纯的判定逻辑，两处常量/公式必须单源于此。
// 线程契约：无状态纯函数，任意线程可用（GpuAllocator 渲染线程独占，
// 见 docs/threading-contract.md 表四 MemoryStats 读通道条目）。
// ============================================================

#include <cstdint>

namespace gpu {

/** 单元（MiB→字节）换算，仅用于下方常量定义，避免魔法数字直写 */
inline constexpr uint64_t kOneMiB = 1024ull * 1024ull;

/**
 * 大图 dedicated allocation 阈值（字节）：单张解码像素量 ≥ 此值的
 * createImage 使用 VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT——
 * 独占一块 VkDeviceMemory，不进子分配池，避免大图反复上传/释放把
 * 池碎片化（方案 D1.5；对齐审计 A-5 实测量级）：
 *   - bg_horizontal 4096×2300 RGBA8 解码 ≈ 37.7 MiB
 *   - ui_button      3828×1384 RGBA8 解码 ≈ 21.2 MiB
 *   - 主图集 2048²    RGBA8 解码 = 16.0 MiB（mip 链 ≈ 21.3 MiB）
 * 16 MiB 取「主图集及以上必 dedicated、零散 UI 小纹理仍走池」的分界。
 */
inline constexpr uint64_t kDedicatedImageThresholdBytes = 16 * kOneMiB;

/**
 * 判断一次 createImage 是否应使用 dedicated allocation。
 *
 * @param decodedBytes 该图像按像素格式展开后的解码字节量
 *   （RGBA8 = w*h*4*级数；ASTC 4x4 = ⌈w/4⌉*⌈h/4⌉*16*级数——由调用方
 *   GpuAllocator 按同一公式计算，本函数只做阈值比较）
 */
inline bool shouldUseDedicatedAllocation(uint64_t decodedBytes) {
    return decodedBytes >= kDedicatedImageThresholdBytes;
}

/** 内存堆类型标志位（与 VkMemoryHeapFlagBits::VK_MEMORY_HEAP_DEVICE_LOCAL_BIT
 *  同值——此处不复用 Vulkan 枚举以维持零 Vulkan 依赖，GpuAllocator 传参时
 *  直接传入 VkMemoryHeap::flags 原值，值域由 Vulkan 规范保证一致） */
inline constexpr uint32_t kHeapFlagDeviceLocal = 0x1;

/**
 * 估算 GPU 预算（字节）：VK_EXT_memory_budget 不可用时的兜底口径
 * （方案 D1.6）——对全部 DEVICE_LOCAL 堆容量求和。
 *
 * 这是「估算下界偏乐观」的近似：真实可用预算受系统占用/其他进程影响，
 * 仅用于 GpuStats.budget 的可观测兜底，不参与分配决策（分配决策全在 VMA）。
 *
 * @param heapCount  物理设备内存堆数量（VkPhysicalDeviceMemoryProperties.memoryHeapCount）
 * @param heapSizes  逐堆容量（VkMemoryHeap::size，字节）
 * @param heapFlags  逐堆标志（VkMemoryHeap::flags 原值）
 * @return DEVICE_LOCAL 堆容量之和；无 DEVICE_LOCAL 堆（统一内存架构且未
 *   标志化时）回退为全部堆容量之和——零预算会让 stats 消费方误判 OOM。
 */
inline uint64_t estimateBudgetFromHeaps(uint32_t heapCount,
                                        const uint64_t* heapSizes,
                                        const uint32_t* heapFlags) {
    uint64_t deviceLocal = 0;
    uint64_t total = 0;
    for (uint32_t i = 0; i < heapCount; ++i) {
        const uint64_t size = heapSizes ? heapSizes[i] : 0;
        total += size;
        if (heapFlags && (heapFlags[i] & kHeapFlagDeviceLocal) != 0) {
            deviceLocal += size;
        }
    }
    return deviceLocal != 0 ? deviceLocal : total;
}

}  // namespace gpu
