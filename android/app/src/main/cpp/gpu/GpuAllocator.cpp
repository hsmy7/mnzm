#include "gpu/GpuAllocator.h"

// ============================================================
// GpuAllocator 实现 —— 全仓 VMA_IMPLEMENTATION 唯一编译单元（P2.1/D1 纪律，
// 其余包含点只声明；第二份实现 = 重复符号链接错误）。
// ============================================================

// vendored 单头在 NDK clang 下产生大量第三方告警（nullability-completeness
// 等 ~800 处）——按第三方代码惯例在本 TU 局部静音，不污染本仓告警审计
#ifdef __clang__
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wnullability-completeness"
#pragma clang diagnostic ignored "-Wignored-qualifiers"
#endif

// Android 链接面修正：libvulkan.so（minSdk 24）只静态导出 Vulkan 1.0 核心，
// VMA 默认 STATIC=1 会把 1.1 的 vkBindBufferMemory2 / vkGetDeviceImageMemory
// Requirements 等直连成未定义符号（链接期已实证）。改走动态解析——仅两个
// 1.0 入口（vkGetInstanceProcAddr/vkGetDeviceProcAddr）静态链接，其余由 VMA
// 在 vmaCreateAllocator 时经 proc-addr 运行时解析（设备过 MIN_VULKAN_API_
// VERSION=1.1 闸后必然可取，运行时缺失由 VMA 断言暴露而非静默）。
#undef VMA_STATIC_VULKAN_FUNCTIONS
#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1

#define VMA_IMPLEMENTATION
#include "third_party/vma/vk_mem_alloc.h"

#ifdef __clang__
#pragma clang diagnostic pop
#endif

#include <cstdio>

// 日志 shim：GpuAllocator 保持零 Android 依赖（不 include android/log.h）——
// Android 构建走 logcat，其他平台走 stderr（桌面调试可编译性）。
#ifdef __ANDROID__
#include <android/log.h>
#define GPU_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GpuAllocator", __VA_ARGS__)
#define GPU_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "GpuAllocator", __VA_ARGS__)
#define GPU_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GpuAllocator", __VA_ARGS__)
#else
#define GPU_LOGI(...) do { std::fprintf(stderr, "[GpuAllocator][I] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define GPU_LOGW(...) do { std::fprintf(stderr, "[GpuAllocator][W] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define GPU_LOGE(...) do { std::fprintf(stderr, "[GpuAllocator][E] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

GpuAllocator& GpuAllocator::get() {
    static GpuAllocator instance;
    return instance;
}

// 双轨开关门：默认 OFF（预发期旧裸分配路径；Kotlin NativeEngineFlag.memorySubsystem
// 经 JNI 投递 true 后收口新路径）
std::atomic<bool> GpuAllocator::s_gateEnabled{false};

bool GpuAllocator::init(VkDevice device, VkPhysicalDevice physicalDevice,
                        uint32_t vulkanApiVersion, bool budgetExtensionEnabled) {
    if (m_allocator != VK_NULL_HANDLE) return true;  // 幂等

    // 动态解析入口（STATIC=0 + DYNAMIC=1 时 VMA 要求显式传入这两个 1.0 核心
    // 函数，其余全部由它在创建期自行 fetch；仅创建调用期需要存活）
    VmaVulkanFunctions vulkanFunctions{};
    vulkanFunctions.vkGetInstanceProcAddr = vkGetInstanceProcAddr;
    vulkanFunctions.vkGetDeviceProcAddr = vkGetDeviceProcAddr;

    VmaAllocatorCreateInfo info{};
    info.physicalDevice = physicalDevice;
    info.device = device;
    info.instance = VK_NULL_HANDLE;  // Vulkan 1.1 面内不需要 instance（budget 走 device 扩展）
    info.vulkanApiVersion = vulkanApiVersion;
    info.pVulkanFunctions = &vulkanFunctions;
    if (budgetExtensionEnabled) {
        // 驱动预算口径：VmaBudget.usage/budget 来自 VK_EXT_memory_budget；
        // 该设备扩展须已由 VulkanBackend::createLogicalDevice 按支持情况启用
        info.flags |= VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT;
    }
    if (vmaCreateAllocator(&info, &m_allocator) != VK_SUCCESS) {
        m_allocator = VK_NULL_HANDLE;
        GPU_LOGE("vmaCreateAllocator failed");
        return false;
    }
    m_device = device;
    m_budgetExtensionEnabled = budgetExtensionEnabled;
    GPU_LOGI("VMA allocator created (budget ext %s)",
             budgetExtensionEnabled ? "on" : "off");
    return true;
}

void GpuAllocator::destroy() {
    if (m_allocator == VK_NULL_HANDLE) return;  // 幂等
    if (m_stagingPool != VK_NULL_HANDLE) {
        vmaDestroyPool(m_allocator, m_stagingPool);
        m_stagingPool = VK_NULL_HANDLE;
    }
    vmaDestroyAllocator(m_allocator);
    m_allocator = VK_NULL_HANDLE;
    m_device = VK_NULL_HANDLE;
    m_budgetExtensionEnabled = false;
    GPU_LOGI("VMA allocator destroyed");
}

bool GpuAllocator::createImage(const VkImageCreateInfo& imageInfo, const char* tag,
                               uint64_t decodedBytes,
                               VkMemoryPropertyFlags preferredFlags,
                               VkMemoryPropertyFlags requiredFlags,
                               VkImage* outImage, VmaAllocation* outAllocation,
                               void** cpuWriteMapped) {
    if (m_allocator == VK_NULL_HANDLE) return false;

    VmaAllocationCreateInfo allocInfo{};
    // usage=AUTO + 显式 preferred/requiredFlags（方案 D1.4：VMA 按需求选型，
    // 杜绝旧手抄循环「不查 property flag 的假回退」）
    allocInfo.usage = VMA_MEMORY_USAGE_AUTO;
    allocInfo.preferredFlags = preferredFlags;
    allocInfo.requiredFlags = requiredFlags;
    // 大图 dedicated（GpuBudgetMath 阈值判定，桌面单测锁定）：独占块防池碎片
    if (gpu::shouldUseDedicatedAllocation(decodedBytes)) {
        allocInfo.flags |= VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT;
    }
    if (cpuWriteMapped) {
        // CPU 直写面（LINEAR 白纹理按布局偏移写 → RANDOM）：AUTO+MAPPED 须
        // 声明访问模式（VMA 3.x 要求），映射常驻分配生命周期
        allocInfo.flags |= VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT |
                           VMA_ALLOCATION_CREATE_MAPPED_BIT;
    }
    allocInfo.pUserData = const_cast<char*>(tag);

    VmaAllocationInfo outInfo{};
    if (vmaCreateImage(m_allocator, &imageInfo, &allocInfo,
                       outImage, outAllocation, &outInfo) != VK_SUCCESS) {
        GPU_LOGE("vmaCreateImage failed (%s)", tag ? tag : "untagged");
        return false;
    }
    if (cpuWriteMapped) *cpuWriteMapped = outInfo.pMappedData;
    return true;
}

bool GpuAllocator::createBuffer(const VkBufferCreateInfo& bufferInfo, const char* tag,
                                bool persistentMap,
                                VkMemoryPropertyFlags preferredFlags,
                                VkMemoryPropertyFlags requiredFlags,
                                VkBuffer* outBuffer, VmaAllocation* outAllocation,
                                void** outMappedData) {
    if (m_allocator == VK_NULL_HANDLE) return false;

    VmaAllocationCreateInfo allocInfo{};
    allocInfo.usage = VMA_MEMORY_USAGE_AUTO;
    allocInfo.preferredFlags = preferredFlags;
    allocInfo.requiredFlags = requiredFlags;
    allocInfo.pUserData = const_cast<char*>(tag);
    if (persistentMap) {
        // VMA 3.x：AUTO usage + MAPPED 必须二选一声明访问模式
        //（VBO 每帧 memcpy 写入 → SEQUENTIAL_WRITE）
        allocInfo.flags |= VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT |
                           VMA_ALLOCATION_CREATE_MAPPED_BIT;
    }

    VmaAllocationInfo outInfo{};
    if (vmaCreateBuffer(m_allocator, &bufferInfo, &allocInfo,
                        outBuffer, outAllocation, &outInfo) != VK_SUCCESS) {
        GPU_LOGE("vmaCreateBuffer failed (%s)", tag ? tag : "untagged");
        return false;
    }
    if (outMappedData) *outMappedData = outInfo.pMappedData;  // 非 MAPPED 时为 null
    return true;
}

void GpuAllocator::destroyImage(VkImage* image, VmaAllocation* allocation) {
    if (m_allocator == VK_NULL_HANDLE || !image) return;
    vmaDestroyImage(m_allocator, *image, allocation ? *allocation : VK_NULL_HANDLE);
    *image = VK_NULL_HANDLE;
    if (allocation) *allocation = VK_NULL_HANDLE;
}

void GpuAllocator::destroyBuffer(VkBuffer* buffer, VmaAllocation* allocation) {
    if (m_allocator == VK_NULL_HANDLE || !buffer) return;
    vmaDestroyBuffer(m_allocator, *buffer, allocation ? *allocation : VK_NULL_HANDLE);
    *buffer = VK_NULL_HANDLE;
    if (allocation) *allocation = VK_NULL_HANDLE;
}

bool GpuAllocator::createStagingBuffer(VkDeviceSize size, const char* tag,
                                       VkBuffer* outBuffer, VmaAllocation* outAllocation,
                                       void** outMappedData) {
    if (m_allocator == VK_NULL_HANDLE) return false;

    // staging 专用 host pool 惰性创建（P2.3）：首传建池，trimHostPool 整池归还。
    // 用 FindMemoryTypeIndexForBufferInfo 定位 HOST_VISIBLE 类型——替代旧 6 份
    // 手抄 memory type 循环的最后一份（本类是唯一允许查表的地方，VMA 内部实现）。
    if (m_stagingPool == VK_NULL_HANDLE) {
        const VkBufferCreateInfo probeInfo{
            VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, nullptr, 0,
            256,  // 探针尺寸（仅决定 memory type 选型，不实际分配）
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, nullptr};
        VmaAllocationCreateInfo probeAlloc{};
        probeAlloc.usage = VMA_MEMORY_USAGE_AUTO;
        probeAlloc.requiredFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
        probeAlloc.preferredFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT |
                                    VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
        uint32_t memTypeIndex = UINT32_MAX;
        if (vmaFindMemoryTypeIndexForBufferInfo(m_allocator, &probeInfo, &probeAlloc,
                                                &memTypeIndex) != VK_SUCCESS) {
            GPU_LOGE("staging pool: no memory type for host-visible transfer src");
            return false;
        }
        VmaPoolCreateInfo poolInfo{};
        poolInfo.memoryTypeIndex = memTypeIndex;
        poolInfo.flags = VMA_POOL_CREATE_IGNORE_BUFFER_IMAGE_GRANULARITY_BIT;  // 纯 buffer 池
        poolInfo.blockSize = static_cast<VkDeviceSize>(kStagingPoolBlockSize);
        poolInfo.minBlockCount = 0;  // 不预占；trim 后可归零
        poolInfo.maxBlockCount = kStagingPoolMaxBlocks;
        if (vmaCreatePool(m_allocator, &poolInfo, &m_stagingPool) != VK_SUCCESS) {
            m_stagingPool = VK_NULL_HANDLE;
            GPU_LOGE("vmaCreatePool (staging host pool) failed");
            return false;
        }
        GPU_LOGI("staging host pool created (block=%lluMiB, max=%u)",
                 (unsigned long long)(kStagingPoolBlockSize / gpu::kOneMiB),
                 kStagingPoolMaxBlocks);
    }

    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = size;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    // 池内子分配：访问模式与映射声明（上传写 → SEQUENTIAL_WRITE + MAPPED）
    VmaAllocationCreateInfo allocInfo{};
    allocInfo.usage = VMA_MEMORY_USAGE_AUTO;
    allocInfo.pool = m_stagingPool;
    allocInfo.flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT |
                      VMA_ALLOCATION_CREATE_MAPPED_BIT;
    allocInfo.pUserData = const_cast<char*>(tag);

    VmaAllocationInfo outInfo{};
    if (vmaCreateBuffer(m_allocator, &bufInfo, &allocInfo,
                        outBuffer, outAllocation, &outInfo) != VK_SUCCESS) {
        GPU_LOGE("vmaCreateBuffer (staging, %llu bytes) failed",
                 (unsigned long long)size);
        return false;
    }
    if (outMappedData) *outMappedData = outInfo.pMappedData;
    return true;
}

void GpuAllocator::trimHostPool() {
    if (m_allocator == VK_NULL_HANDLE) return;
    if (m_stagingPool == VK_NULL_HANDLE) return;  // 从未上传过：无水位可降

    VmaDetailedStatistics before{};
    vmaCalculatePoolStatistics(m_allocator, m_stagingPool, &before);

    // VMA 3.x 移除了 vmaTrimPool/vmaClearFreeMemory——池级收缩唯一确定性
    // 手段 = 整池销毁重建。安全性前提（锁纪律，见 GpuAllocator.h 头注释）：
    // staging 分配全部生存在 VulkanBackend::m_gpuMutex 上传临界区内
    // （建→map→memcpy→unmap→submit+fence→毁），本函数在同一互斥域内调用
    // ⇒ 调用时池内必然零存活分配，销毁不悬垂。
    vmaDestroyPool(m_allocator, m_stagingPool);
    m_stagingPool = VK_NULL_HANDLE;

    GPU_LOGI("trimHostPool: released staging host pool, freed %llu KiB (blocks=%u)",
             (unsigned long long)(before.statistics.blockBytes / 1024),
             before.statistics.blockCount);
}

GpuStats GpuAllocator::stats() const {
    GpuStats out{};
    if (m_allocator == VK_NULL_HANDLE) return out;

    VmaTotalStatistics total{};
    vmaCalculateStatistics(m_allocator, &total);
    out.blockCount = total.total.statistics.blockCount;
    out.allocCount = total.total.statistics.allocationCount;
    out.usedBytes = static_cast<uint64_t>(total.total.statistics.blockBytes);

    if (m_budgetExtensionEnabled) {
        // budget 扩展口径：逐堆 VmaBudget 求和（usage 含 VMA 之外占用：
        // 交换链/管线/driver 隐式对象——比 blockBytes 更接近真实水位）
        const VkPhysicalDeviceMemoryProperties* memProps = nullptr;
        vmaGetMemoryProperties(m_allocator, &memProps);
        VmaBudget budgets[VK_MAX_MEMORY_HEAPS]{};
        vmaGetHeapBudgets(m_allocator, budgets);
        uint64_t budgetSum = 0;
        uint64_t usageSum = 0;
        for (uint32_t i = 0; i < memProps->memoryHeapCount; ++i) {
            budgetSum += static_cast<uint64_t>(budgets[i].budget);
            usageSum += static_cast<uint64_t>(budgets[i].usage);
        }
        out.budget = budgetSum;
        out.usedBytes = usageSum;
    } else {
        // 兜底估算（GpuBudgetMath 纯函数，桌面单测锁定）：DEVICE_LOCAL 堆容量和
        const VkPhysicalDeviceMemoryProperties* memProps = nullptr;
        vmaGetMemoryProperties(m_allocator, &memProps);
        uint64_t heapSizes[VK_MAX_MEMORY_HEAPS] = {};
        uint32_t heapFlags[VK_MAX_MEMORY_HEAPS] = {};
        for (uint32_t i = 0; i < memProps->memoryHeapCount; ++i) {
            heapSizes[i] = static_cast<uint64_t>(memProps->memoryHeaps[i].size);
            heapFlags[i] = memProps->memoryHeaps[i].flags;
        }
        out.budget = gpu::estimateBudgetFromHeaps(memProps->memoryHeapCount,
                                                  heapSizes, heapFlags);
    }
    return out;
}
