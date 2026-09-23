#pragma once

// ============================================================
// GpuAllocator — GPU 内存单一分配入口（VMA 子分配；memory-refactor D1/MR2）
//
// 结构根因（memory-audit M-P0-2/M-P0-3）：渲染 cpp 此前 6 站点手抄
// vkAllocateMemory + memory type 循环，回退不查 property flag，一对象一
// VkDeviceMemory，无统计/预算，staging 棘轮无收缩。本类收口为唯一分配面：
//   - VMA（third_party/vma/vk_mem_alloc.h，钉死 v3.3.0）子分配复用；
//   - 大图 dedicated allocation（GpuBudgetMath::kDedicatedImageThresholdBytes）；
//   - staging 专用 host pool，trimHostPool 可收缩高水位（D3 CRITICAL 动作）；
//   - VK_EXT_memory_budget 可用即用，不可用按堆容量估算（GpuBudgetMath）。
//
// 【零 Android 依赖】（不 include android/*、JNI 头）——与 gamecore 同纪律，
// 保持渲染 C++ 层桌面可移植（iOS 侧 Metal 对等实现挂同一接口面，
// platform-abilities 已登记 MTLHeap 对应项）。
//
// 【线程契约】（docs/threading-contract.md 表四「内存子系统」条目，2026-09-23
// 预登记）：本类由**渲染线程独占**——create/destroy/trim/stats 全部只允许
// 渲染线程（VulkanBackend 上传路径历史上在 Kotlin 主线程执行，但全程持
// VulkanBackend::m_gpuMutex 且与渲染线程帧提交互斥，构成互斥执行域；
// stats 快照经 VulkanBackend 发布后任意线程只读）。禁止并发调用。
//
// 【开关双轨】NativeEngineFlag.memorySubsystem OFF（预发默认）时 VulkanBackend
// 走旧裸分配路径，本类不被调用；ON 时所有分配经此处。守卫：
// desktop ctest GpuAllocatorGuard（渲染 cpp 裸 vkAllocateMemory 仅允许出现在
// 本 TU 的 VMA 实现体内）。
// ============================================================

#include "third_party/vma/vk_mem_alloc.h"
#include "gpu/GpuBudgetMath.h"

#include <vulkan/vulkan.h>
#include <atomic>
#include <cstdint>

/**
 * GPU 分配统计快照（方案 D1 GpuStats 契约）。
 * VkDeviceSize 换为 uint64_t：与 GpuBudgetMath 零 Vulkan 依赖口径一致，
 * 且统计值跨 JNI 发布（nativeGetGpuStats 预留）时无符号宽度歧义。
 */
struct GpuStats {
    /** 预算字节：budget 扩展可用 = 驱动上报用量口径的堆预算和；不可用 = 堆容量估算 */
    uint64_t budget = 0;
    /** 已用字节：VMA 已绑定内存（分配层口径，非驱动全局用量） */
    uint64_t usedBytes = 0;
    /** 当前存活的 VkDeviceMemory 块数（含 dedicated） */
    uint32_t blockCount = 0;
    /** 当前存活的分配（子分配）数 */
    uint32_t allocCount = 0;
};

/**
 * 单一 GPU 分配入口（Meyers 单例，渲染线程互斥域内调用）。
 *
 * 生命周期：VulkanBackend::initDevice 成功 → init(device, physDevice)；
 * VulkanBackend::destroySurfaceGeneration / shutdown → destroy()
 * （与 TextureCache.clearEpoch 的「先 cache 后 allocator」顺序由调用方
 * VulkanBackend 保证——方案 D2.2；本类自身幂等：未 init 时全部 no-op /
 * 返回失败，destroy 重复调用安全）。
 */
class GpuAllocator {
public:
    static GpuAllocator& get();

    // ── 双轨开关门（NativeEngineFlag.memorySubsystem 的 native 侧生效面）──
    // Kotlin 侧经 NativeBridge.nativeSetMemorySubsystem 在库加载后立即投递
    //（进程生命周期内恒定——BuildConfig 注入默认，无运行时写者）。
    // OFF（预发默认）= 渲染 cpp 走旧裸分配路径，本类不被调用；
    // ON = 全部分配收口本类。initDevice 中本类 init 失败时门自动落回 OFF
    //（VMA 不兼容兜底 = 关断回旧路径，方案风险表第一行）——
    // 这意味着 native 生效门与 Kotlin 旗标可短暂不一致（本进程内降级），
    // 真值以 isGateEnabled() 为准。
    static void setGateEnabled(bool enabled) { s_gateEnabled.store(enabled, std::memory_order_relaxed); }
    static bool isGateEnabled() { return s_gateEnabled.load(std::memory_order_relaxed); }

    GpuAllocator(const GpuAllocator&) = delete;
    GpuAllocator& operator=(const GpuAllocator&) = delete;

    /** 创建 VmaAllocator（budget 扩展按设备支持条件启用）。重复 init 幂等（返回 true）。 */
    bool init(VkDevice device, VkPhysicalDevice physicalDevice,
              uint32_t vulkanApiVersion, bool budgetExtensionEnabled);

    /** 销毁 VmaAllocator（要求全部 buffer/image 分配已先行 destroy——
     *  VulkanBackend 生命周期顺序保证）。幂等。 */
    void destroy();

    bool isInitialized() const { return m_allocator != VK_NULL_HANDLE; }

    /**
     * 分配并绑定 image（= vkCreateImage + allocate + bindImageMemory 三合一）。
     *
     * @param decodedBytes 解码像素量（GpuBudgetMath 阈值判定 dedicated 用；
     *        ASTC 由调用方按块计）——≥ 大图阈值时加 DEDICATED_MEMORY 位
     * @param preferredFlags 偏好内存性质（如 DEVICE_LOCAL / HOST_VISIBLE）——
     *        VMA usage=AUTO 下尽力满足但不作为必需（回退不再是无 flag 假回退，
     *        必需性质走 requiredFlags）
     * @param requiredFlags 必需内存性质（0 = 无；HOST_VISIBLE 类需求必须显式传）
     * @param cpuWriteMapped 非空 = 需 CPU 直写（白纹理 LINEAR 图像）：加
     *        HOST_ACCESS_RANDOM|MAPPED 位并回传常驻映射指针
     */
    bool createImage(const VkImageCreateInfo& imageInfo, const char* tag,
                     uint64_t decodedBytes,
                     VkMemoryPropertyFlags preferredFlags,
                     VkMemoryPropertyFlags requiredFlags,
                     VkImage* outImage, VmaAllocation* outAllocation,
                     void** cpuWriteMapped = nullptr);

    /**
     * 分配并绑定 buffer（= vkCreateBuffer + allocate + bindBufferMemory 三合一）。
     *
     * @param persistentMap true 时加 MAPPED 位并回传 pMappedData（VBO 持久映射；
     *        staging 每次上传自建自毁，不需要持久映射常驻）
     */
    bool createBuffer(const VkBufferCreateInfo& bufferInfo, const char* tag,
                      bool persistentMap,
                      VkMemoryPropertyFlags preferredFlags,
                      VkMemoryPropertyFlags requiredFlags,
                      VkBuffer* outBuffer, VmaAllocation* outAllocation,
                      void** outMappedData);

    void destroyImage(VkImage* image, VmaAllocation* allocation);
    void destroyBuffer(VkBuffer* buffer, VmaAllocation* allocation);

    /**
     * staging 专用 host pool 收缩（D3 CRITICAL 渲染面动作，帧边界调用）。
     * VMA 3.x 移除了 vmaTrimPool——池级收缩唯一确定性手段 = 整池销毁重建
     * （安全性前提见 GpuAllocator.cpp trimHostPool 注释）；销毁即释放全部
     * block 归还驱动。水位差写入日志（trim 后高水位可降的观测口径）。
     */
    void trimHostPool();

    /**
     * 从 staging 专用 host pool 分配上传缓冲（P2.3：替代旧「单 staging 棘轮」。
     * 每次上传自建自毁——池内子分配复用 block，trimHostPool 可整块归还）。
     * 调用方负责上传完成（fence wait）后 destroyBuffer。
     */
    bool createStagingBuffer(VkDeviceSize size, const char* tag,
                             VkBuffer* outBuffer, VmaAllocation* outAllocation,
                             void** outMappedData);

    /** 当前统计快照（budget 扩展不可用时 usedBytes 仍真实，budget 为估算值） */
    GpuStats stats() const;

private:
    GpuAllocator() = default;
    ~GpuAllocator() = default;

    /** 双轨开关门（静态；见类头注释——native 生效真值） */
    static std::atomic<bool> s_gateEnabled;

    /** staging 专用 host pool（HOST_VISIBLE|COHERENT 堆；linear 语义由
     *  每上传自建自毁 + 整块回收达成——池算法用 VMA 默认 TLSF，
     *  适配上传尺寸跨度 4KB~22MB，见 trimHostPool 注释） */
    VmaPool m_stagingPool = VK_NULL_HANDLE;
    /** staging pool block 上限（个）：BLOCK × 上限 = 池水位硬顶，
     *  防 22MB 级 ASTC 多路并发上传把 host 内存顶穿 */
    static constexpr uint32_t kStagingPoolMaxBlocks = 4;
    /** staging pool 单 block 字节：覆盖主图集 mip 链（≈21.3MB）+ 余量，
     *  32MB 对齐 VMA block 内部管理开销后仍单块容纳典型上传 */
    static constexpr uint64_t kStagingPoolBlockSize = 32 * gpu::kOneMiB;

    VmaAllocator m_allocator = VK_NULL_HANDLE;
    VkDevice m_device = VK_NULL_HANDLE;
    /** budget 扩展是否已启用（createLogicalDevice 按设备支持决定并传入） */
    bool m_budgetExtensionEnabled = false;
};
