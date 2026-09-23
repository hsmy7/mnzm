# VMA（Vulkan Memory Allocator）vendoring 说明

本目录是 [Vulkan Memory Allocator](https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator)
的 vendored 单头（MIT）。**钉死版本，禁止随手升级**；升级属架构决策，须过门禁
（内存方案全局约束 + 真机 validation 抽检）。

## 版本锁定（memory-refactor D1 / P2.1）

| 项 | 值 |
|---|---|
| 上游 tag | `v3.3.0` |
| 上游 commit | `1d8f600fd424278486eade7ed3e877c99f0846b1` |
| 复制文件 | `include/vk_mem_alloc.h`（原样复制，仅文件顶部插入 7 行 provenance 注释） |
| License | MIT（文件头部原文保留） |
| 引入批次 | MR2 / P2.1（内存管理根治 Phase 2 GPU 子系统，2026-09-23） |

## 本仓消费约定

1. **`VMA_IMPLEMENTATION` 落唯一 TU**：`android/app/src/main/cpp/gpu/GpuAllocator.cpp`。
   其余包含点只 `#include "third_party/vma/vk_mem_alloc.h"` 取声明——两份实现会
   产生重复符号链接错误。
2. **`GpuAllocator` 是唯一消费者**：渲染 cpp（VulkanBackend.cpp 等）不得绕过
   `GpuAllocator` 直调 VMA 分配（守卫：desktop ctest `GpuAllocatorGuard`）。
3. **Vulkan 版本面**：VMA 3.x 最低要求 Vulkan 1.1；本仓 `MIN_VULKAN_API_VERSION
   = VK_API_VERSION_1_1`（VulkanBackend.cpp），NDK r27 头文件面内，无越界符号。
   `VK_EXT_memory_budget` 为可选设备扩展——支持则启用（预算统计），不支持按
   堆容量估算（`gpu/GpuBudgetMath.h` 纯函数，有桌面单测）。
4. **生命周期**：`VmaAllocator` 由 `GpuAllocator` 持有；`initDevice` 成功后创建，
   `destroySurfaceGeneration` / `shutdown` 按「先 cache 后 allocator」顺序销毁
   （MR3 TextureCache.clearEpoch 挂点顺序已在 threading-contract / 方案 D2 登记）。

## 升级流程（如确需）

1. `git log --oneline` 上游确认目标 tag 的 commit SHA；
2. 原样覆盖 `vk_mem_alloc.h`（保留顶部 provenance 注释块并更新 SHA）；
3. `git diff` 确认除 provenance 块外无本仓改动混入；
4. NDK arm64 编译 + 桌面 ctest + 真机 validation 抽检三项全绿后才可入库。
