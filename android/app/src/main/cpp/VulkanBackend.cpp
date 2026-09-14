#include "VulkanBackend.h"
#include "KtxLoader.h"
#include <android/native_window_jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <vector>
#include <set>
#include <thread>
#include <chrono>

// SkyBackground 天空管线 push-constant 结构（与 sky.vert / sky.frag 的 SkyPC 布局一致）：
//   mat4 proj(0..63) + topColor(64..79) + upperMidColor(80..95) + lowerMidColor(96..111) + bottomColor(112..127) = 128B
// 四段渐变：颜色取 .rgb；位置/强度编码在 alpha（topColor.a=upperMidT, upperMidColor.a=lowerMidT,
// lowerMidColor.a=strength），以控制在 minPushConstantsSize(=128B) 内。
struct alignas(16) SkyPushConstants {
    float proj[16];
    float topColor[4];
    float upperMidColor[4];
    float lowerMidColor[4];
    float bottomColor[4];
};
static_assert(sizeof(SkyPushConstants) == 128, "SkyPushConstants must be 128 bytes (minPushConstantsSize)");

/** Vulkan 驱动版本缓存（由 initDevice 设置，供 JNI getVulkanDriverVersion 读取） */
volatile int VulkanBackend::s_driverVersion = 0;
/** Vulkan API 版本（VK_MAKE_VERSION 编码）与 GPU vendorID/设备名缓存（selectPhysicalDevice 设置，供 JNI 上报量化阈值） */
volatile int VulkanBackend::s_apiVersion = 0;
volatile int VulkanBackend::s_vendorId = 0;
char VulkanBackend::s_deviceName[256] = {0};
#include <android/log.h>
#include <cstdio>
#include <signal.h>
#include <setjmp.h>

// SPIR-V 着色器字节码（由 gen_header.py 从 .spv 生成）
#include "shaders.h"

#define LOG_TAG "VulkanBackend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// ── SIGSEGV 信号保护（用于 vkCreateShaderModule 等驱动缺陷场景） ──
// 某些 GPU 驱动（TapTap 云游戏 Hook 层/部分 Mali 驱动）在创建 ShaderModule
// 时可能触发 SIGSEGV。使用 sigsetjmp/siglongjmp 捕获后优雅降级到软件渲染。
// 注意：Android API 30+ seccomp-bpf 可能限制 sigaction(SIGSEGV)，此保护
// 作为防御兜底而非主要方案。
static thread_local sigjmp_buf g_vk_jmpbuf;
static thread_local bool g_vk_jmpbuf_set = false;

static void vk_signal_handler(int sig) {
    if (g_vk_jmpbuf_set) {
        siglongjmp(g_vk_jmpbuf, sig);
    }
}

// Vulkan 最低 API 版本要求：1.1（VK_API_VERSION_1_1 = (1 << 22)）
// 参考 Unity Device Filtering 内置规则和 Flutter Impeller 的 Vulkan 选择逻辑
static constexpr uint32_t MIN_VULKAN_API_VERSION = VK_API_VERSION_1_1;

// ── vkAcquireNextImageKHR 有界化──
// UINT64_MAX 无界等待使「渲染线程卡 >2s」具备物理可能——这正是 Kotlin 侧
// skip-release 分支（2s join 截止后放弃回收）的触发条件。有界化后渲染线程
// 在 Surface 销毁后必然在有限时间（2s fence + 250ms×spin 上限）内退出。
static constexpr uint64_t ACQUIRE_TIMEOUT_NS = 250'000'000ULL;  // 单轮 250ms
static constexpr int ACQUIRE_SPIN_LIMIT = 8;                    // 8 轮 ≈ 2s 上限

// 有限等待 fence（定义见 submitOneTimeCommands 附近）
static bool waitForFenceBounded(VkDevice device, VkFence fence, uint64_t totalNs);

// 必需的 Vulkan 设备扩展列表
static const std::vector<const char*> REQUIRED_DEVICE_EXTENSIONS = {
    VK_KHR_SWAPCHAIN_EXTENSION_NAME
};

// ============================================================
// 初始化
// ============================================================

// 创建 1×1 白色纹理（供 drawRect 纯色矩形使用）
// 在纯色绘制时，shader 计算 outFrag = texture(white) * vertexColor = 1.0 * vertexColor = vertexColor
// 避免采样图集左上角像素导致颜色错误。
bool VulkanBackend::createWhiteTexture() {
    uint8_t whitePixel[4] = { 255, 255, 255, 255 };
    int w = 1, h = 1;

    Texture& outTex = m_whiteTexture;

    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imgInfo.extent = { (uint32_t)w, (uint32_t)h, 1 };
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_LINEAR;
    imgInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(m_device, &imgInfo, nullptr, &outTex.image) != VK_SUCCESS) {
        LOGE("Failed to create white texture image");
        return false;
    }

    VkMemoryRequirements memReq;
    vkGetImageMemoryRequirements(m_device, outTex.image, &memReq);

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(m_physDevice, &memProps);

    uint32_t memType = UINT32_MAX;
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((memReq.memoryTypeBits & (1u << i)) &&
            (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
            memType = i;
            break;
        }
    }
    if (memType == UINT32_MAX) { LOGE("No mem type for white texture"); return false; }

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = memType;

    if (vkAllocateMemory(m_device, &allocInfo, nullptr, &outTex.memory) != VK_SUCCESS) {
        LOGE("Failed to alloc white tex memory");
        return false;
    }
    vkBindImageMemory(m_device, outTex.image, outTex.memory, 0);

    void* mapped;
    vkMapMemory(m_device, outTex.memory, 0, VK_WHOLE_SIZE, 0, &mapped);
    VkSubresourceLayout layout;
    VkImageSubresource sub{};
    sub.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vkGetImageSubresourceLayout(m_device, outTex.image, &sub, &layout);
    memcpy((char*)mapped + layout.offset, whitePixel, 4);
    vkUnmapMemory(m_device, outTex.memory);

    // Layout transition: UNDEFINED → SHADER_READ_ONLY_OPTIMAL
    VkCommandBufferAllocateInfo cmdAlloc{};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = m_commandPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;
    VkCommandBuffer cmd;
    vkAllocateCommandBuffers(m_device, &cmdAlloc, &cmd);

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &beginInfo);

    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = outTex.image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &barrier);
    vkEndCommandBuffer(cmd);

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    VkFence fence;
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    vkCreateFence(m_device, &fenceInfo, nullptr, &fence);
    vkQueueSubmit(m_graphicsQueue, 1, &submit, fence);
    // 白纹创建与上传同用有限等待（此处失败走非致命日志路径）
    if (!waitForFenceBounded(m_device, fence, 2'000'000'000ULL /*2s*/)) {
        LOGE("white texture fence not signaled within 2s — suspect device lost");
        vkDestroyFence(m_device, fence, nullptr);
        vkFreeCommandBuffers(m_device, m_commandPool, 1, &cmd);
        return false;
    }
    vkDestroyFence(m_device, fence, nullptr);
    vkFreeCommandBuffers(m_device, m_commandPool, 1, &cmd);

    // ImageView
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = outTex.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (vkCreateImageView(m_device, &viewInfo, nullptr, &outTex.view) != VK_SUCCESS) {
        LOGE("Failed to create white texture view");
        return false;
    }

    VkSamplerCreateInfo sampInfo{};
    sampInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sampInfo.magFilter = VK_FILTER_NEAREST;
    sampInfo.minFilter = VK_FILTER_NEAREST;
    sampInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampInfo.anisotropyEnable = VK_FALSE;
    sampInfo.maxLod = 1.0f;
    if (vkCreateSampler(m_device, &sampInfo, nullptr, &outTex.sampler) != VK_SUCCESS) {
        LOGE("Failed to create white texture sampler");
        return false;
    }

    outTex.width = w;
    outTex.height = h;
    outTex.id = 0;  // 白色纹理 ID 固定为 0
    return true;
}

// ============================================================
// 两阶段初始化：Phase 1 — 设备 + 着色器（无 Surface 依赖）
// 在加载界面调用，创建后 initSurface 可跳过此阶段
// ============================================================

bool VulkanBackend::initDevice(const char* cacheDir, int worldW, int worldH, int tileSize) {
    LOGI("initDevice: world=%dx%d tile=%d cacheDir=%s",
         worldW, worldH, tileSize, cacheDir ? cacheDir : "(null)");

    // 保存缓存目录路径
    if (cacheDir) {
        strncpy(m_cacheDir, cacheDir, sizeof(m_cacheDir) - 1);
        m_cacheDir[sizeof(m_cacheDir) - 1] = '\0';
    }

    m_config.worldWidth = worldW;
    m_config.worldHeight = worldH;
    m_config.tileSize = tileSize;

    if (!createInstance()) { LOGE("initDevice: createInstance failed"); return false; }
    if (!selectPhysicalDevice()) { LOGE("initDevice: selectPhysicalDevice failed"); return false; }
    if (!createLogicalDevice()) { LOGE("initDevice: createLogicalDevice failed"); return false; }
    if (!loadShaders()) { LOGE("initDevice: loadShaders failed"); return false; }
    if (!loadPipelineCache()) { /* 无缓存文件正常，非致命 */ }

    m_deviceReady = true;
    LOGI("Vulkan device+shaders initialized successfully");
    return true;
}

// ============================================================
// 两阶段初始化：Phase 2 — Surface/Swapchain/Pipeline
// 在 SurfaceView 就绪后调用，依赖 initDevice 先完成
// ============================================================

bool VulkanBackend::initSurface(void* nativeWindow, int viewportW, int viewportH) {
    LOGI("initSurface(%dx%d, window=%p)", viewportW, viewportH, nativeWindow);

    if (!m_deviceReady) {
        LOGE("initSurface called without initDevice — do full init instead");
        // 兜底：如果 Device 未初始化，用默认参数做完整初始化
        if (!initDevice(nullptr, 0, 0, 0)) return false;
    }

    // 幂等防御：上一代表面资源未释放（skip-release 纪元 / 迟到的
    // init 成功）时先析构旧代再走正常创建链——覆盖式重建从此不可能泄漏。
    // Kotlin 侧 resolveDeferredRelease 保证渲染线程此时已停（或已降级 GLES），
    // 此处 vkDeviceWaitIdle 只等待 GPU 在途帧。
    if (m_surface != VK_NULL_HANDLE || m_swapchain != VK_NULL_HANDLE ||
        m_commandPool != VK_NULL_HANDLE) {
        LOGW("initSurface: stale surface generation detected — destroying first");
        destroySurfaceGeneration();
    }

    m_config.viewportW = viewportW;
    m_config.viewportH = viewportH;

    // Surface / Swapchain
    m_nativeWindow = static_cast<ANativeWindow*>(nativeWindow);
    if (m_nativeWindow) ANativeWindow_acquire(m_nativeWindow);

    if (!createSwapchain(viewportW, viewportH)) { LOGE("initSurface: createSwapchain failed"); return false; }
    if (!createRenderPass()) { LOGE("initSurface: createRenderPass failed"); return false; }
    if (!createOffscreenRenderPass()) { LOGE("initSurface: createOffscreenRenderPass failed"); return false; }
    if (!createFramebuffers()) { LOGE("initSurface: createFramebuffers failed"); return false; }
    if (!createOffscreenTargets()) { LOGE("initSurface: createOffscreenTargets failed"); return false; }

    // Pipeline（使用预创建的 ShaderModule + PipelineCache 加速）
    if (!createPipeline()) { LOGE("initSurface: createPipeline failed"); return false; }

    // 管线创建完成后保存 Pipeline Cache
    savePipelineCache();

    if (!createVertexBuffer()) { LOGE("initSurface: createVertexBuffer failed"); return false; }
    if (!createCommandObjects()) { LOGE("initSurface: createCommandObjects failed"); return false; }
    if (!createSynchronization()) { LOGE("initSurface: createSynchronization failed"); return false; }

    // 白色纹理
    if (!createWhiteTexture()) {
        LOGE("initSurface: white texture creation failed (non-fatal)");
    } else {
        updateTextureDescriptor(m_whiteTexture);
    }

    orthoProj(m_projMatrix, 0.0f, (float)viewportW, (float)viewportH, 0.0f);

    m_ready = true;
    LOGI("Vulkan surface initialized: %dx%d", viewportW, viewportH);
    return true;
}

// ============================================================
// init（向后兼容 — 全量初始化，等价于 initDevice + initSurface）
// ============================================================

bool VulkanBackend::init(const RenderConfig& config, void* nativeWindow) {
    m_config = config;

    // SkyBackground 屏幕正交投影（常量）：归一化屏幕坐标 (x∈[0,1] 左→右, y∈[0,1] 顶→底)
    // → Vulkan NDC（Y 向下：y=0 顶 → -1，y=1 底 → +1）。与相机矩阵/分辨率无关，仅算一次。
    orthoProj(m_screenOrtho, 0.0f, 1.0f, 1.0f, 0.0f);

    LOGI("init(%dx%d, window=%p, renderScale=%.2f)",
         config.viewportW, config.viewportH, nativeWindow, config.renderScale);

    // 消费 renderScale（离屏降采样目标在 initSurface → createOffscreenTargets 中按此创建）
    m_renderScale = std::isfinite(config.renderScale)
        ? std::max(0.5f, std::min(1.0f, config.renderScale))
        : 1.0f;

    if (m_deviceReady) {
        // 已经过 initDevice 预加载，只初始化 Surface
        return initSurface(nativeWindow, config.viewportW, config.viewportH);
    }

    // 完整链
    if (!initDevice(nullptr, config.worldWidth, config.worldHeight, config.tileSize)) {
        LOGE("init: initDevice failed");
        return false;
    }
    return initSurface(nativeWindow, config.viewportW, config.viewportH);
}

void VulkanBackend::destroySurfaceGeneration() {
    if (m_device == VK_NULL_HANDLE) return;

    // 先落闸挡住渲染线程新帧，再等待 GPU 在途帧完成
    // （调用契约：渲染线程已由 Kotlin 侧 join 停止——stopRenderThread /
    //  resolveDeferredRelease；waitIdle 只覆盖 GPU 侧在途工作。幂等：可重复调用）
    m_ready = false;
    vkDeviceWaitIdle(m_device);

    destroyOffscreenTargets();  // 离屏 framebuffer 引用离屏 renderPass——必须早于其销毁（VUID 00873）
    destroyGraphicsObjects();   // pipeline/layout/renderPass/descriptorPool（保留 ShaderModule——device 级）
    destroySwapchain();         // framebuffers/views/swapchain（不含 VkSurfaceKHR——surface 由本函数唯一销毁）

    // 清理白色纹理（每个 vkDestroy* 后立即置空，防止二次调用时双重释放）
    if (m_whiteTexture.view) { vkDestroyImageView(m_device, m_whiteTexture.view, nullptr); m_whiteTexture.view = VK_NULL_HANDLE; }
    if (m_whiteTexture.image) { vkDestroyImage(m_device, m_whiteTexture.image, nullptr); m_whiteTexture.image = VK_NULL_HANDLE; }
    if (m_whiteTexture.memory) { vkFreeMemory(m_device, m_whiteTexture.memory, nullptr); m_whiteTexture.memory = VK_NULL_HANDLE; }
    if (m_whiteTexture.sampler) { vkDestroySampler(m_device, m_whiteTexture.sampler, nullptr); m_whiteTexture.sampler = VK_NULL_HANDLE; }
    m_whiteTexture = {};

    for (auto& tex : m_textures) {
        if (tex.view) { vkDestroyImageView(m_device, tex.view, nullptr); tex.view = VK_NULL_HANDLE; }
        if (tex.image) { vkDestroyImage(m_device, tex.image, nullptr); tex.image = VK_NULL_HANDLE; }
        if (tex.memory) { vkFreeMemory(m_device, tex.memory, nullptr); tex.memory = VK_NULL_HANDLE; }
        if (tex.sampler) { vkDestroySampler(m_device, tex.sampler, nullptr); tex.sampler = VK_NULL_HANDLE; }
    }
    m_textures.clear();
    // 退役队列一并清空（纹理资源已由上方循环/白纹清理释放；waitIdle 后无在途采样）
    m_retiredTextures.clear();

    // 清理三缓冲 VBO
    for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        if (m_vertexMapped[i]) {
            vkUnmapMemory(m_device, m_vertexMemories[i]);
            m_vertexMapped[i] = nullptr;
        }
        if (m_vertexBuffers[i]) { vkDestroyBuffer(m_device, m_vertexBuffers[i], nullptr); m_vertexBuffers[i] = VK_NULL_HANDLE; }
        if (m_vertexMemories[i]) { vkFreeMemory(m_device, m_vertexMemories[i], nullptr); m_vertexMemories[i] = VK_NULL_HANDLE; }
    }

    for (auto& sem : m_imageAvailable) { if (sem) { vkDestroySemaphore(m_device, sem, nullptr); sem = VK_NULL_HANDLE; } }
    m_imageAvailable.clear();
    for (auto& sem : m_renderFinished) { if (sem) { vkDestroySemaphore(m_device, sem, nullptr); sem = VK_NULL_HANDLE; } }
    m_renderFinished.clear();
    for (auto& fence : m_inFlightFences) { if (fence) { vkDestroyFence(m_device, fence, nullptr); fence = VK_NULL_HANDLE; } }
    m_inFlightFences.clear();

    if (m_commandPool) {
        // 池销毁连带释放其 command buffer
        vkDestroyCommandPool(m_device, m_commandPool, nullptr);
        m_commandPool = VK_NULL_HANDLE;
    }
    m_commandBuffers.clear();

    // Surface 纪元唯一销毁点：ensureSurface 复用既有 surface，
    // 只有纪元析构（本函数）与 device 级 shutdown 销毁它
    if (m_surface) { vkDestroySurfaceKHR(m_instance, m_surface, nullptr); m_surface = VK_NULL_HANDLE; }

    // 释放 ANativeWindow 引用（与 initSurface 的 acquire 对称）
    if (m_nativeWindow) {
        ANativeWindow_release(m_nativeWindow);
        m_nativeWindow = nullptr;
    }

    m_currentFrame = 0;
    m_pendingDraws.clear();
    LOGI("VulkanBackend surface generation destroyed");
}

void VulkanBackend::shutdown() {
    if (m_device == VK_NULL_HANDLE) return;

    // Surface 纪元资源（幂等析构——与 initSurface 复用路径共用同一清理）
    destroySurfaceGeneration();

    // 关机前保存 Pipeline Cache（可能在 LoadingScreen 阶段创建，也可能刚刚创建）
    savePipelineCache();

    // 销毁 Pipeline Cache
    if (m_pipelineCache) {
        vkDestroyPipelineCache(m_device, m_pipelineCache, nullptr);
        m_pipelineCache = VK_NULL_HANDLE;
    }

    // device 级资源：ShaderModule + staging buffer + device/instance
    destroyShaderModules();

    // 清理 staging buffer（device 级，跨 Surface 纪元保留——此处最终释放）
    if (m_stagingBuffer) { vkDestroyBuffer(m_device, m_stagingBuffer, nullptr); m_stagingBuffer = VK_NULL_HANDLE; }
    if (m_stagingMemory) { vkFreeMemory(m_device, m_stagingMemory, nullptr); m_stagingMemory = VK_NULL_HANDLE; }

    // 主句柄置空 —— 确保二次 shutdown() 调用幂等安全
    if (m_device) { vkDestroyDevice(m_device, nullptr); m_device = VK_NULL_HANDLE; }
    if (m_instance) { vkDestroyInstance(m_instance, nullptr); m_instance = VK_NULL_HANDLE; }

    m_deviceReady = false;
    LOGI("VulkanBackend shutdown");
}

// ============================================================
// Instance / Device
// ============================================================

bool VulkanBackend::createInstance() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "XianxiaSect";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "NativeRenderer2D";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    const char* extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
    };

    VkInstanceCreateInfo instInfo{};
    instInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instInfo.pApplicationInfo = &appInfo;
    instInfo.enabledExtensionCount = 2;
    instInfo.ppEnabledExtensionNames = extensions;

    // 不启用验证层（发布版本）
    VkResult res = vkCreateInstance(&instInfo, nullptr, &m_instance);
    if (res != VK_SUCCESS) {
        m_lastInitError = RenderInitError::VK_INSTANCE;
        LOGE("vkCreateInstance failed: %d", res);
        return false;
    }
    return true;
}

bool VulkanBackend::selectPhysicalDevice() {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(m_instance, &count, nullptr);
    if (count == 0) {
        m_lastInitError = RenderInitError::VK_PHYSICAL_DEVICE;
        LOGE("No Vulkan devices"); return false;
    }

    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(m_instance, &count, devices.data());

    // 选首个通过能力检测的设备（移动端均为单集显——原「优先独立 GPU」注释与
    // 实现不符，修正；桌面多卡场景不在本项目目标范围）
    for (auto& dev : devices) {
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(dev, &props);

        // 跳过 CPU 软件设备（debuggable 构建下系统可能暴露 SwiftShader
        // ICD——生产低概率，纯防御；移动端正常设备不命中。设备类型 + 设备名双信号）
        const char* dn = props.deviceName;
        if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU ||
            strstr(dn, "swiftshader") || strstr(dn, "llvmpipe")) {
            LOGW("Skipping CPU ICD physical device: %s", dn);
            continue;
        }

        // ── Vulkan API 版本安全检查 ──
        // 验证驱动程序版本 >= 1.1，排除 1.0 的不完整实现
        // 参考：Unity 内置最低规格（ARM Mali 要求 >= 1.0.61, 但 1.0 实现普遍不可靠）
        uint32_t apiMajor = VK_API_VERSION_MAJOR(props.apiVersion);
        uint32_t apiMinor = VK_API_VERSION_MINOR(props.apiVersion);
        s_driverVersion = static_cast<int>(props.driverVersion);
        s_apiVersion = static_cast<int>(props.apiVersion);
        s_vendorId = static_cast<int>(props.vendorID);
        std::snprintf(s_deviceName, sizeof(s_deviceName), "%s", props.deviceName);
        LOGI("GPU: %s | Vulkan %u.%u.%u (driver 0x%x)",
             props.deviceName,
             apiMajor, apiMinor, VK_API_VERSION_PATCH(props.apiVersion),
             props.driverVersion);

        if (props.apiVersion < MIN_VULKAN_API_VERSION) {
            LOGE("  -> Vulkan %u.%u < minimum 1.1, SKIPPING",
                 apiMajor, apiMinor);
            continue;
        }

        VkPhysicalDeviceFeatures features;
        vkGetPhysicalDeviceFeatures(dev, &features);

        // 确保支持纹理压缩（所有 Mali/Adreno 都支持 ETC2）
        if (!features.textureCompressionETC2 &&
            !features.textureCompressionASTC_LDR) {
            LOGE("  -> No ETC2/ASTC texture compression, SKIPPING");
            continue;
        }

        // ── 必需扩展检查 ──
        uint32_t extCount = 0;
        vkEnumerateDeviceExtensionProperties(dev, nullptr, &extCount, nullptr);
        std::vector<VkExtensionProperties> availableExts(extCount);
        vkEnumerateDeviceExtensionProperties(dev, nullptr, &extCount, availableExts.data());

        bool allExtsFound = true;
        for (const auto& req : REQUIRED_DEVICE_EXTENSIONS) {
            bool found = false;
            for (const auto& av : availableExts) {
                if (strcmp(av.extensionName, req) == 0) { found = true; break; }
            }
            if (!found) {
                LOGE("  -> Missing required extension: %s", req);
                allExtsFound = false;
            }
        }
        if (!allExtsFound) continue;

        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> queues(qCount);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, queues.data());

        for (uint32_t i = 0; i < qCount; i++) {
            if (queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
                m_graphicsQueueIndex = i;
                m_physDevice = dev;
                LOGI("Selected GPU: %s (queue %d, Vulkan %u.%u.%u)",
                     props.deviceName, i,
                     apiMajor, apiMinor, VK_API_VERSION_PATCH(props.apiVersion));
                return true;
            }
        }
    }

    LOGE("No suitable GPU found");
    m_lastInitError = RenderInitError::VK_PHYSICAL_DEVICE;
    return false;
}

bool VulkanBackend::createLogicalDevice() {
    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = m_graphicsQueueIndex;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &queuePriority;

    const char* extensions[] = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME
    };

    // 只启用 GPU 实际支持的功能（部分 Adreno 驱动在请求不支持的功能时 SIGSEGV）
    VkPhysicalDeviceFeatures supportedFeatures;
    vkGetPhysicalDeviceFeatures(m_physDevice, &supportedFeatures);

    VkPhysicalDeviceFeatures features{};
    // B.1 各向异性：仅当设备实际支持 samplerAnisotropy 时才启用（不支持自动回退关闭）
    features.samplerAnisotropy = supportedFeatures.samplerAnisotropy ? VK_TRUE : VK_FALSE;
    features.textureCompressionASTC_LDR = supportedFeatures.textureCompressionASTC_LDR
        ? VK_TRUE : VK_FALSE;
    features.textureCompressionETC2 = supportedFeatures.textureCompressionETC2
        ? VK_TRUE : VK_FALSE;

    // 记录 ASTC LDR 支持状态（压缩图集上传前置条件，不支持时 Kotlin 回退 RGBA）
    m_astcSupported = supportedFeatures.textureCompressionASTC_LDR == VK_TRUE;
    // 记录各向异性支持状态（B.1：setTextureQuality / 采样器创建前置条件）
    m_anisoSupported = supportedFeatures.samplerAnisotropy == VK_TRUE;

    VkDeviceCreateInfo devInfo{};
    devInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    devInfo.queueCreateInfoCount = 1;
    devInfo.pQueueCreateInfos = &queueInfo;
    devInfo.enabledExtensionCount = 1;
    devInfo.ppEnabledExtensionNames = extensions;
    devInfo.pEnabledFeatures = &features;

    if (vkCreateDevice(m_physDevice, &devInfo, nullptr, &m_device) != VK_SUCCESS) {
        m_lastInitError = RenderInitError::VK_LOGICAL_DEVICE;
        LOGE("Failed to create logical device");
        return false;
    }

    // ── Safe vkGetDeviceQueue with retry ──
    // Some Adreno GPU drivers (especially on Chinese OEM ROMs) have a race condition
    // where the device queue handle isn't fully initialized immediately after vkCreateDevice.
    // Retry with a small delay to allow the driver to finish internal initialization.
    // While this can't prevent all SIGSEGV crashes (which are signal-level), the delay
    // significantly reduces the window for the race on affected drivers.
    m_graphicsQueue = VK_NULL_HANDLE;
    for (int retry = 0; retry < 3; retry++) {
        // Small delay before first call too, to let the driver settle
        if (retry > 0) std::this_thread::sleep_for(std::chrono::milliseconds(2)); // 2ms between retries
        vkGetDeviceQueue(m_device, m_graphicsQueueIndex, 0, &m_graphicsQueue);
        if (m_graphicsQueue != VK_NULL_HANDLE) break;
        LOGW("vkGetDeviceQueue returned VK_NULL_HANDLE (attempt %d/3)", retry + 1);
    }

    if (m_graphicsQueue == VK_NULL_HANDLE) {
        m_lastInitError = RenderInitError::VK_QUEUE;
        LOGE("Failed to get device queue after 3 attempts — likely Adreno driver race condition");
        vkDestroyDevice(m_device, nullptr);
        m_device = VK_NULL_HANDLE;
        return false;
    }

    m_presentQueue = m_graphicsQueue;

    LOGI("Logical device created (ASTC=%d, ETC2=%d)",
         features.textureCompressionASTC_LDR,
         features.textureCompressionETC2);
    return true;
}

// ============================================================
// Swapchain
// ============================================================

bool VulkanBackend::ensureSurface() {
    // 幂等：surface 已存在直接复用——resize/清晰度切换不再新建
    // VkSurfaceKHR（每纪元恰好一个 surface，销毁权在 destroySurfaceGeneration）
    if (m_surface != VK_NULL_HANDLE) return true;

    VkAndroidSurfaceCreateInfoKHR surfInfo{};
    surfInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfInfo.window = m_nativeWindow;

    if (vkCreateAndroidSurfaceKHR(m_instance, &surfInfo, nullptr, &m_surface) != VK_SUCCESS) {
        m_lastInitError = RenderInitError::VK_SURFACE;
        LOGE("Failed to create Android surface");
        return false;
    }
    return true;
}

bool VulkanBackend::createSwapchain(int width, int height) {
    if (!ensureSurface()) return false;

    // 查询 surface 格式
    uint32_t fmtCount = 0;
    VkResult fmtRes = vkGetPhysicalDeviceSurfaceFormatsKHR(m_physDevice, m_surface, &fmtCount, nullptr);
    if (fmtRes != VK_SUCCESS || fmtCount == 0) {
        m_lastInitError = RenderInitError::VK_SWAPCHAIN;
        LOGE("No surface formats available (res=%d, count=%u)", fmtRes, fmtCount);
        return false;
    }
    std::vector<VkSurfaceFormatKHR> formats(fmtCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(m_physDevice, m_surface, &fmtCount, formats.data());

    // 从可用格式中优先选择 gralloc 确定支持的格式。
    // Adreno 驱动有时将 A2B10G10R10 (59) 列为首个格式，
    // 但高通 gralloc 模块无法为此格式分配帧缓冲 → GetSize unrecognized + BAD_VALUE。
    // 安全格式优先级：R8G8B8A8_UNORM > B8G8R8A8_UNORM > R8G8B8A8_SRGB > B8G8R8A8_SRGB。
    {
        const VkFormat SAFE_FORMATS[] = {
            VK_FORMAT_R8G8B8A8_UNORM,    // 37 — 最广泛兼容
            VK_FORMAT_B8G8R8A8_UNORM,    // 44 — 部分设备优选
            VK_FORMAT_R8G8B8A8_SRGB,     // 43 — sRGB 变体
            VK_FORMAT_B8G8R8A8_SRGB,     // 50 — sRGB 变体
        };
        bool found = false;
        for (const auto& surfaceFmt : formats) {
            for (VkFormat safe : SAFE_FORMATS) {
                if (surfaceFmt.format == safe) {
                    m_swapchainFormat = surfaceFmt.format;
                    m_swapchainColorSpace = surfaceFmt.colorSpace;
                    found = true;
                    break;
                }
            }
            if (found) break;
        }
        if (!found) {
            // 回退：使用设备报告的首个格式
            m_swapchainFormat = formats[0].format;
            m_swapchainColorSpace = formats[0].colorSpace;
        }
    }
    LOGI("Swapchain format: %d, colorSpace=%d, total=%u, selected from %u available",
         m_swapchainFormat, m_swapchainColorSpace, fmtCount, fmtCount);

    // 设置 extent，确保非零
    uint32_t safeW = (uint32_t)std::max(width, 1);
    uint32_t safeH = (uint32_t)std::max(height, 1);
    m_swapchainExtent = { safeW, safeH };

    VkSwapchainCreateInfoKHR swapInfo{};
    swapInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    swapInfo.surface = m_surface;
    swapInfo.minImageCount = MAX_FRAMES_IN_FLIGHT;
    swapInfo.imageFormat = m_swapchainFormat;
    swapInfo.imageColorSpace = m_swapchainColorSpace;
    swapInfo.imageExtent = m_swapchainExtent;
    swapInfo.imageArrayLayers = 1;
    // TRANSFER_DST：render scale 离屏渲染路径将降采样图像 blit 上采样到交换链
    swapInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    swapInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    swapInfo.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    swapInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    swapInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;  // VSYNC 对齐
    swapInfo.clipped = VK_TRUE;

    VkResult scRes = vkCreateSwapchainKHR(m_device, &swapInfo, nullptr, &m_swapchain);
    if (scRes != VK_SUCCESS) {
        m_lastInitError = RenderInitError::VK_SWAPCHAIN;
        LOGE("vkCreateSwapchainKHR failed: %d (format=%d, %dx%d)",
             scRes, m_swapchainFormat, safeW, safeH);
        return false;
    }

    // 获取 swapchain 图像
    uint32_t imgCount = 0;
    vkGetSwapchainImagesKHR(m_device, m_swapchain, &imgCount, nullptr);
    m_swapchainImages.resize(imgCount);
    vkGetSwapchainImagesKHR(m_device, m_swapchain, &imgCount, m_swapchainImages.data());

    // 创建 ImageView
    m_swapchainViews.resize(imgCount);
    for (uint32_t i = 0; i < imgCount; i++) {
        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = m_swapchainImages[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = m_swapchainFormat;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.layerCount = 1;

        if (vkCreateImageView(m_device, &viewInfo, nullptr, &m_swapchainViews[i]) != VK_SUCCESS) {
            m_lastInitError = RenderInitError::VK_SWAPCHAIN;
            LOGE("Failed to create swapchain image view");
            return false;
        }
    }

    // ImageView 创建后不立即创建 Framebuffer —— 此时 m_renderPass 尚未初始化，
    // framebuffer 需在 createRenderPass() 之后通过 createFramebuffers() 创建。

    // blit 能力守卫：交换链格式不支持 BLIT_DST 时 render scale 强制回退 1.0（直渲路径）。
    // 行货 GPU 的 RGBA8/BGRA8 交换链格式均支持，此守卫仅针对极端定制驱动。
    VkFormatProperties formatProps;
    vkGetPhysicalDeviceFormatProperties(m_physDevice, m_swapchainFormat, &formatProps);
    m_blitSupported = (formatProps.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0;
    if (!m_blitSupported) {
        m_renderScale = 1.0f;
        LOGW("Swapchain format %d lacks BLIT_DST — render scale disabled", m_swapchainFormat);
    }

    LOGI("Swapchain created: %dx%d, %d images, format=%d, blit=%d",
         m_swapchainExtent.width, m_swapchainExtent.height,
         imgCount, m_swapchainFormat, m_blitSupported ? 1 : 0);
    return true;
}

void VulkanBackend::destroySwapchain() {
    for (auto& fb : m_framebuffers)
        if (fb) vkDestroyFramebuffer(m_device, fb, nullptr);
    m_framebuffers.clear();
    for (auto& view : m_swapchainViews)
        if (view) vkDestroyImageView(m_device, view, nullptr);
    m_swapchainViews.clear();
    m_swapchainImages.clear();
    if (m_swapchain) vkDestroySwapchainKHR(m_device, m_swapchain, nullptr);
    m_swapchain = VK_NULL_HANDLE;
}

bool VulkanBackend::createFramebuffers() {
    uint32_t imgCount = (uint32_t)m_swapchainViews.size();
    if (imgCount == 0 || m_renderPass == VK_NULL_HANDLE) {
        m_lastInitError = RenderInitError::VK_RENDER_PASS;
        LOGE("createFramebuffers: no swapchain views or render pass not ready");
        return false;
    }
    // 幂等入口：清晰度切换（setRenderScale）不经 destroySwapchain
    // 直达本函数——旧 framebuffer 残留即每帧泄漏；先销毁旧代再建（幂等不变量）
    for (auto& fb : m_framebuffers)
        if (fb) vkDestroyFramebuffer(m_device, fb, nullptr);
    m_framebuffers.clear();
    m_framebuffers.resize(imgCount);
    for (uint32_t i = 0; i < imgCount; i++) {
        VkFramebufferCreateInfo fbInfo{};
        fbInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fbInfo.renderPass = m_renderPass;
        fbInfo.attachmentCount = 1;
        fbInfo.pAttachments = &m_swapchainViews[i];
        fbInfo.width = m_swapchainExtent.width;
        fbInfo.height = m_swapchainExtent.height;
        fbInfo.layers = 1;

        if (vkCreateFramebuffer(m_device, &fbInfo, nullptr, &m_framebuffers[i]) != VK_SUCCESS) {
            m_lastInitError = RenderInitError::VK_RENDER_PASS;
            LOGE("Failed to create framebuffer %u", i);
            return false;
        }
    }
    LOGI("Framebuffers created: %u (%dx%d)", imgCount,
         m_swapchainExtent.width, m_swapchainExtent.height);
    return true;
}

// ============================================================
// Render scale 离屏降采样目标（平板/大屏省电）
// ============================================================

void VulkanBackend::destroyOffscreenTargets() {
    for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        if (m_offscreenFramebuffers[i]) vkDestroyFramebuffer(m_device, m_offscreenFramebuffers[i], nullptr);
        m_offscreenFramebuffers[i] = VK_NULL_HANDLE;
        if (m_offscreenViews[i]) vkDestroyImageView(m_device, m_offscreenViews[i], nullptr);
        m_offscreenViews[i] = VK_NULL_HANDLE;
        if (m_offscreenImages[i]) vkDestroyImage(m_device, m_offscreenImages[i], nullptr);
        m_offscreenImages[i] = VK_NULL_HANDLE;
        if (m_offscreenMemories[i]) vkFreeMemory(m_device, m_offscreenMemories[i], nullptr);
        m_offscreenMemories[i] = VK_NULL_HANDLE;
    }
    m_offscreenExtent = {};
    m_usingOffscreen = false;
}

bool VulkanBackend::createOffscreenTargets() {
    // 缩放 1.0 或设备不支持 blit → 直渲路径（不创建离屏目标）
    if (m_renderScale >= 1.0f || !m_blitSupported ||
        m_offscreenRenderPass == VK_NULL_HANDLE) {
        m_usingOffscreen = false;
        return true;
    }

    // 降采样尺寸：round(物理 × renderScale)，下限 1px
    uint32_t offW = (uint32_t)std::max(1, (int)llround(m_swapchainExtent.width * m_renderScale));
    uint32_t offH = (uint32_t)std::max(1, (int)llround(m_swapchainExtent.height * m_renderScale));
    m_offscreenExtent = { offW, offH };

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(m_physDevice, &memProps);

    for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        VkImageCreateInfo imgInfo{};
        imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imgInfo.imageType = VK_IMAGE_TYPE_2D;
        imgInfo.format = m_swapchainFormat;  // 与交换链同格式（blit 格式兼容性最安全）
        imgInfo.extent = { offW, offH, 1 };
        imgInfo.mipLevels = 1;
        imgInfo.arrayLayers = 1;
        imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imgInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

        if (vkCreateImage(m_device, &imgInfo, nullptr, &m_offscreenImages[i]) != VK_SUCCESS) {
            LOGE("createOffscreenTargets: vkCreateImage[%d] failed (%ux%u)", i, offW, offH);
            destroyOffscreenTargets();
            m_renderScale = 1.0f;  // 回退直渲
            return true;
        }

        VkMemoryRequirements memReq;
        vkGetImageMemoryRequirements(m_device, m_offscreenImages[i], &memReq);

        uint32_t memType = UINT32_MAX;
        for (uint32_t j = 0; j < memProps.memoryTypeCount; j++) {
            if ((memReq.memoryTypeBits & (1u << j)) &&
                (memProps.memoryTypes[j].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
                memType = j;
                break;
            }
        }
        if (memType == UINT32_MAX) {
            LOGE("createOffscreenTargets: no DEVICE_LOCAL memory type for offscreen image");
            destroyOffscreenTargets();
            m_renderScale = 1.0f;
            return true;
        }

        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memReq.size;
        allocInfo.memoryTypeIndex = memType;

        if (vkAllocateMemory(m_device, &allocInfo, nullptr, &m_offscreenMemories[i]) != VK_SUCCESS) {
            LOGE("createOffscreenTargets: vkAllocateMemory[%d] failed", i);
            destroyOffscreenTargets();
            m_renderScale = 1.0f;
            return true;
        }
        vkBindImageMemory(m_device, m_offscreenImages[i], m_offscreenMemories[i], 0);

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = m_offscreenImages[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = m_swapchainFormat;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.layerCount = 1;

        if (vkCreateImageView(m_device, &viewInfo, nullptr, &m_offscreenViews[i]) != VK_SUCCESS) {
            LOGE("createOffscreenTargets: vkCreateImageView[%d] failed", i);
            destroyOffscreenTargets();
            m_renderScale = 1.0f;
            return true;
        }

        VkFramebufferCreateInfo fbInfo{};
        fbInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        // ★ 用离屏专用 renderPass（finalLayout=TRANSFER_SRC_OPTIMAL）创建——主
        //   renderPass 的 PRESENT_SRC 布局对非交换链图像非法，且重建时序上
        //   主 renderPass 可能在 framebuffer 之前被销毁（VUID 00873）
        fbInfo.renderPass = m_offscreenRenderPass;
        fbInfo.attachmentCount = 1;
        fbInfo.pAttachments = &m_offscreenViews[i];
        fbInfo.width = offW;
        fbInfo.height = offH;
        fbInfo.layers = 1;

        if (vkCreateFramebuffer(m_device, &fbInfo, nullptr, &m_offscreenFramebuffers[i]) != VK_SUCCESS) {
            LOGE("createOffscreenTargets: vkCreateFramebuffer[%d] failed", i);
            destroyOffscreenTargets();
            m_renderScale = 1.0f;
            return true;
        }
    }

    m_usingOffscreen = true;
    LOGI("Offscreen render targets created: %ux%u (scale=%.2f, %d frames in flight)",
         offW, offH, m_renderScale, MAX_FRAMES_IN_FLIGHT);
    return true;
}

float VulkanBackend::setRenderScale(float scale) {
    // NaN/Inf 消毒为直渲；越界钳制到 [0.5, 1.0]
    if (!std::isfinite(scale)) scale = 1.0f;
    scale = std::max(0.5f, std::min(1.0f, scale));

    if (!m_blitSupported) {
        m_renderScale = 1.0f;
        return m_renderScale;
    }
    if (std::fabs(scale - m_renderScale) < 0.001f) return m_renderScale;
    if (m_device == VK_NULL_HANDLE || m_swapchain == VK_NULL_HANDLE) {
        // surface 未就绪：仅记录，initSurface/resize 的 createOffscreenTargets 时生效
        m_renderScale = scale;
        return m_renderScale;
    }

    // 同 resize 语义：挡住渲染线程 + 等待 GPU 空闲后重建
    m_ready = false;
    vkDeviceWaitIdle(m_device);

    // ★ 持锁重建（m_gpuMutex）：本函数销毁/重建描述符池并遍历 m_textures 重建
    //   descSet——与主线程上传 push_back 并发即 vector 悬垂（见头文件注）。
    //   m_ready=false 已先行挡住渲染线程新帧，锁只与上传线程互斥。
    std::lock_guard<std::mutex> gpuLock(m_gpuMutex);

    // 旧离屏目标先销毁（其 framebuffers 引用 m_offscreenRenderPass——
    // 必须早于 renderPass 销毁，VUID-vkDestroyRenderPass-renderPass-00873）
    destroyOffscreenTargets();
    m_renderScale = scale;
    const bool willUseOffscreen = scale < 1.0f;

    // 重建顺序约束：offscreen framebuffer 不得以旧 renderPass 创建——
    //   destroyGraphicsObjects 会销毁该 renderPass
    //   （违反 VUID-vkDestroyRenderPass-renderPass-00873）。顺序必须为：
    //   先重建 graphics 对象（管线 viewport 按 m_renderScale 现场推导，
    //   不依赖离屏目标创建时机），最后创建离屏目标（framebuffer 绑定新
    //   m_offscreenRenderPass）。
    //   任何 scale 变化都改变 viewport extent（直渲值仅 1.0 且被 fabs 提前返回拦截），
    //   故无条件重建 graphics（Pipeline Cache 命中，~ms 级）。
    destroyGraphicsObjects();
    if (!createRenderPass() || !createOffscreenRenderPass() ||
        !createFramebuffers() || !createPipeline()) {
        LOGE("setRenderScale: graphics rebuild failed — falling back to direct render");
        m_renderScale = 1.0f;
        destroyGraphicsObjects();
        createRenderPass();
        createOffscreenRenderPass();
        createFramebuffers();
        createPipeline();
    }
    // 离屏目标最后创建（内部失败自动回退直渲，此时管线 viewport 恒为直渲尺寸）
    createOffscreenTargets();
    if (willUseOffscreen && !m_usingOffscreen) {
        // createOffscreenTargets 失败回退：重建管线为直渲 viewport
        destroyGraphicsObjects();
        createRenderPass();
        createOffscreenRenderPass();
        createFramebuffers();
        createPipeline();
    }
    // ★ 重建独立描述符集（池/layout 随管线重建，纹理 descSet 已被 destroyGraphicsObjects
    //   置空）——非 command buffer 记录期，合法
    updateTextureDescriptor(m_whiteTexture);
    for (auto& tex : m_textures) updateTextureDescriptor(tex);

    m_ready = true;
    LOGI("Render scale set to %.2f (offscreen=%d)", m_renderScale, m_usingOffscreen ? 1 : 0);
    return m_renderScale;
}

bool VulkanBackend::resize(int width, int height) {
    if (m_device == VK_NULL_HANDLE) return false;

    // 置 false 挡住渲染线程 submitFrame——vkDeviceWaitIdle 只等
    // GPU 空闲、不等渲染线程；不置位时渲染线程可在 swapchain 销毁后
    // vkAcquireNextImageKHR 命中已销毁句柄（DEVICE_LOST/SIGSEGV）。
    // 重建成功恢复 true；失败保持 false（渲染停止提交，安全黑屏而非崩溃）
    m_ready = false;
    vkDeviceWaitIdle(m_device);

    // ★ 持锁重建（m_gpuMutex）：destroyGraphicsObjects 遍历 m_textures 置空
    //   descSet、重建后再次遍历分配——与主线程上传 push_back 并发即 vector
    //   悬垂（见头文件注）。m_ready=false 已先行挡住渲染线程新帧。
    std::lock_guard<std::mutex> gpuLock(m_gpuMutex);

    destroySwapchain();
    destroyOffscreenTargets();  // 离屏目标尺寸基于旧 swapchain extent，一并重建
    destroyGraphicsObjects();  // 保留 ShaderModule（它们不依赖 Surface）

    m_config.viewportW = width;
    m_config.viewportH = height;

    if (!createSwapchain(width, height)) return false;
    if (!createRenderPass()) return false;
    if (!createOffscreenRenderPass()) return false;
    if (!createFramebuffers()) return false;
    // 跳过 loadShaders() — ShaderModule 在 initDevice 时已创建，跨 resize 复用
    if (!createOffscreenTargets()) return false;  // 内部失败自动回退直渲（返回 true）
    if (!createPipeline()) return false;

    // 保存 Pipeline Cache（可能已有新优化数据）
    savePipelineCache();

    // 重建 CommandBuffer
    for (auto& cmd : m_commandBuffers)
        if (cmd) vkFreeCommandBuffers(m_device, m_commandPool, 1, &cmd);
    m_commandBuffers.clear();
    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = m_commandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = (uint32_t)m_swapchainImages.size();
    m_commandBuffers.resize(m_swapchainImages.size());
    if (vkAllocateCommandBuffers(m_device, &allocInfo, m_commandBuffers.data()) != VK_SUCCESS) {
        LOGE("Failed to reallocate command buffers");
        return false;
    }

    orthoProj(m_projMatrix, 0.0f, (float)width, (float)height, 0.0f);
    // ★ 重建独立描述符集（池/layout 随管线重建）——非 command buffer 记录期，合法
    updateTextureDescriptor(m_whiteTexture);
    for (auto& tex : m_textures) updateTextureDescriptor(tex);
    m_ready = true;  // 重建完成恢复渲染（见 resize 开头注释）
    LOGI("Resized to %dx%d", width, height);
    return true;
}

// ============================================================
// RenderPass / Pipeline
// ============================================================

bool VulkanBackend::createRenderPass() {
    if (m_renderPass) vkDestroyRenderPass(m_device, m_renderPass, nullptr);

    VkAttachmentDescription colorAtt{};
    colorAtt.format = m_swapchainFormat;
    colorAtt.samples = VK_SAMPLE_COUNT_1_BIT;
    colorAtt.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    colorAtt.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    colorAtt.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    colorAtt.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    colorAtt.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    colorAtt.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    VkAttachmentReference colorRef{};
    colorRef.attachment = 0;
    colorRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorRef;

    VkSubpassDependency dep{};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.srcAccessMask = 0;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo rpInfo{};
    rpInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    rpInfo.attachmentCount = 1;
    rpInfo.pAttachments = &colorAtt;
    rpInfo.subpassCount = 1;
    rpInfo.pSubpasses = &subpass;
    rpInfo.dependencyCount = 1;
    rpInfo.pDependencies = &dep;

    if (vkCreateRenderPass(m_device, &rpInfo, nullptr, &m_renderPass) != VK_SUCCESS) {
        m_lastInitError = RenderInitError::VK_RENDER_PASS;
        LOGE("Failed to create render pass");
        return false;
    }
    return true;
}

bool VulkanBackend::createOffscreenRenderPass() {
    if (m_offscreenRenderPass) vkDestroyRenderPass(m_device, m_offscreenRenderPass, nullptr);

    // 离屏图像不是交换链图像，
    //   finalLayout = PRESENT_SRC_KHR 对非 swapchain 图像非法（Vulkan 规范），
    //   renderPass 结束布局转换未定义 → blit 读到未初始化数据 →
    //   每帧随机噪点。离屏 pass 的 finalLayout 使用
    //   TRANSFER_SRC_OPTIMAL（blit 源布局），与主 pass 附件描述一致（管线兼容）。
    VkAttachmentDescription colorAtt{};
    colorAtt.format = m_swapchainFormat;
    colorAtt.samples = VK_SAMPLE_COUNT_1_BIT;
    colorAtt.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    colorAtt.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    colorAtt.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    colorAtt.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    colorAtt.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    colorAtt.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

    VkAttachmentReference colorRef{};
    colorRef.attachment = 0;
    colorRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorRef;

    VkSubpassDependency dep{};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.srcAccessMask = 0;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo rpInfo{};
    rpInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    rpInfo.attachmentCount = 1;
    rpInfo.pAttachments = &colorAtt;
    rpInfo.subpassCount = 1;
    rpInfo.pSubpasses = &subpass;
    rpInfo.dependencyCount = 1;
    rpInfo.pDependencies = &dep;

    if (vkCreateRenderPass(m_device, &rpInfo, nullptr, &m_offscreenRenderPass) != VK_SUCCESS) {
        m_lastInitError = RenderInitError::VK_OFFSCREEN;
        LOGE("Failed to create offscreen render pass");
        return false;
    }
    return true;
}

bool VulkanBackend::loadShaders() {
    // 从构建时生成的 C 头文件中加载 SPIR-V 字节码
    m_vertShader = compileShader(sprite_vert_spv, sprite_vert_spv_size);
    m_fragShader = compileShader(sprite_frag_spv, sprite_frag_spv_size);
    // SkyBackground 天空管线（sky.vert + sky.frag：分段 smoothstep 解析渐变；失败回退主管线）
    m_skyVertShader = compileShader(sky_vert_spv, sky_vert_spv_size);
    m_skyFragShader = compileShader(sky_frag_spv, sky_frag_spv_size);

    if (!m_vertShader || !m_fragShader || !m_skyVertShader || !m_skyFragShader) {
        m_lastInitError = RenderInitError::VK_SHADERS;
        LOGE("Failed to compile shaders");
        return false;
    }

    LOGI("Shaders loaded from embedded SPIR-V (vert=%zu, frag=%zu, skyVert=%zu, skyFrag=%zu bytes)",
         sprite_vert_spv_size, sprite_frag_spv_size, sky_vert_spv_size, sky_frag_spv_size);
    return true;
}

VkShaderModule VulkanBackend::compileShader(const uint32_t* code, size_t size) {
    // 空 device 守卫 — 避免驱动缺陷前附加检查
    if (m_device == VK_NULL_HANDLE) {
        LOGE("compileShader: m_device is null");
        return VK_NULL_HANDLE;
    }
    if (code == nullptr || size == 0) {
        LOGE("compileShader: invalid SPIR-V code (null or empty)");
        return VK_NULL_HANDLE;
    }

    VkShaderModuleCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    info.codeSize = size;
    info.pCode = code;
    VkShaderModule module;

    // ── SIGSEGV 信号捕获保护 ──
    // 某些 GPU 驱动（云游戏 Hook 层/部分 Mali 驱动）在 vkCreateShaderModule 中
    // 存在内存访问越界缺陷。使用信号处理捕获后返回 VK_NULL_HANDLE 而非崩溃。
    // 注意：Android API 30+ seccomp-bpf 可能限制 sigaction(SIGSEGV)，
    // 此时信号保护不可用但代码正常降级（跳过信号保护直接调用）。
    struct sigaction old_act, new_act;
    memset(&new_act, 0, sizeof(new_act));
    new_act.sa_handler = vk_signal_handler;
    sigemptyset(&new_act.sa_mask);
    bool signal_installed = (sigaction(SIGSEGV, &new_act, &old_act) == 0);

    if (signal_installed) {
        g_vk_jmpbuf_set = true;
        if (sigsetjmp(g_vk_jmpbuf, 1) == 0) {
            VkResult result = vkCreateShaderModule(
                m_device, &info, nullptr, &module);
            sigaction(SIGSEGV, &old_act, nullptr);
            g_vk_jmpbuf_set = false;
            if (result != VK_SUCCESS) {
                LOGE("Failed to create shader module: %d", result);
                return VK_NULL_HANDLE;
            }
            return module;
        } else {
            LOGE("SIGSEGV caught in vkCreateShaderModule");
            sigaction(SIGSEGV, &old_act, nullptr);
            g_vk_jmpbuf_set = false;
            return VK_NULL_HANDLE;
        }
    }
    // signal not installed (API 30+ seccomp) — call directly
    VkResult result = vkCreateShaderModule(m_device, &info, nullptr, &module);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create shader module: %d", result);
        return VK_NULL_HANDLE;
    }
    return module;
}

bool VulkanBackend::createPipeline() {
    // ── 新增管线指引 ──
    //
    // 本函数在 initSurface() 和 resize() 中调用，此时：
    //   - ShaderModule 已在 initDevice()（加载界面阶段）预编译好
    //   - m_pipelineCache 已加载（或新建），自动缓存每次管线创建结果
    //
    // 新增管线只需两步：
    //   1. loadShaders() 中加载额外 ShaderModule（在加载界面完成）
    //   2. 本函数末尾新增 vkCreateGraphicsPipelines，传入 m_pipelineCache
    //
    // Pipeline Cache 在首次创建后自动保存到磁盘，下次启动复用。
    // resize 时 ShaderModule 跨 Surface 复用，不走重新编译。
    // ───────────────────

    // 描述符集布局（1个 combined image sampler）
    VkDescriptorSetLayoutBinding bind{};
    bind.binding = 0;
    bind.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bind.descriptorCount = 1;
    bind.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutCreateInfo dslInfo{};
    dslInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslInfo.bindingCount = 1;
    dslInfo.pBindings = &bind;

    if (vkCreateDescriptorSetLayout(m_device, &dslInfo, nullptr, &m_descriptorSetLayout) != VK_SUCCESS) {
        m_lastInitError = RenderInitError::VK_DESCRIPTOR_POOL;
        LOGE("Failed to create descriptor set layout");
        return false;
    }

    // Pipeline Layout（1 个 push constant mat4）
    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
    pushRange.offset = 0;
    pushRange.size = sizeof(float) * 16;

    VkPipelineLayoutCreateInfo plInfo{};
    plInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plInfo.setLayoutCount = 1;
    plInfo.pSetLayouts = &m_descriptorSetLayout;
    plInfo.pushConstantRangeCount = 1;
    plInfo.pPushConstantRanges = &pushRange;

    if (vkCreatePipelineLayout(m_device, &plInfo, nullptr, &m_pipelineLayout) != VK_SUCCESS) {
        m_lastInitError = RenderInitError::VK_PIPELINE_LAYOUT;
        LOGE("Failed to create pipeline layout");
        return false;
    }

    // SkyBackground 天空管线 layout：mat4 proj(VERTEX) + 三段颜色/位置/强度(FRAGMENT)，
    // 共 128B push constant（Vulkan 保证 minPushConstantsSize >= 128B）。
    VkPushConstantRange skyPushRange{};
    skyPushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    skyPushRange.offset = 0;
    skyPushRange.size = 16 * sizeof(float) + 4 * 4 * sizeof(float);  // mat4 + 4×vec4 = 128B

    VkPipelineLayoutCreateInfo skyPlInfo{};
    skyPlInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    skyPlInfo.setLayoutCount = 1;
    skyPlInfo.pSetLayouts = &m_descriptorSetLayout;
    skyPlInfo.pushConstantRangeCount = 1;
    skyPlInfo.pPushConstantRanges = &skyPushRange;

    if (vkCreatePipelineLayout(m_device, &skyPlInfo, nullptr, &m_skyPipelineLayout) != VK_SUCCESS) {
        LOGE("Failed to create sky pipeline layout — sky will fall back to main pipeline");
        m_skyPipelineLayout = VK_NULL_HANDLE;
    }

    // 描述符池 — 每纹理独立描述符集（白纹 + 图集 + 地面 +
    // RGBA 回退图集等），不得回退单共享集——
    // command buffer 记录期间 vkUpdateDescriptorSets 改写共享集违反规范。
    // maxSets 动态化：原硬编码 16，纹理注册表超限后
    // vkAllocateDescriptorSets 失败 → 白纹回退；上限随注册表容量伸缩
    // （超限后的运行时重建见 updateTextureDescriptor → rebuildDescriptorPool）
    const uint32_t descriptorMaxSets = std::max(16u, (uint32_t)m_textures.capacity() + 2);
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSize.descriptorCount = descriptorMaxSets;

    VkDescriptorPoolCreateInfo dpInfo{};
    dpInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpInfo.maxSets = descriptorMaxSets;
    dpInfo.poolSizeCount = 1;
    dpInfo.pPoolSizes = &poolSize;

    if (vkCreateDescriptorPool(m_device, &dpInfo, nullptr, &m_descriptorPool) != VK_SUCCESS) {
        m_lastInitError = RenderInitError::VK_DESCRIPTOR_POOL;
        LOGE("Failed to create descriptor pool");
        return false;
    }

    // 顶点输入状态
    VkVertexInputBindingDescription vxBind{};
    vxBind.binding = 0;
    vxBind.stride = sizeof(SpriteVertex);
    vxBind.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

    VkVertexInputAttributeDescription vxAttrs[3]{};
    vxAttrs[0].location = 0;  // pos
    vxAttrs[0].binding = 0;
    vxAttrs[0].format = VK_FORMAT_R32G32_SFLOAT;
    vxAttrs[0].offset = offsetof(SpriteVertex, px);

    vxAttrs[1].location = 1;  // uv
    vxAttrs[1].binding = 0;
    vxAttrs[1].format = VK_FORMAT_R32G32_SFLOAT;
    vxAttrs[1].offset = offsetof(SpriteVertex, u);

    vxAttrs[2].location = 2;  // color
    vxAttrs[2].binding = 0;
    vxAttrs[2].format = VK_FORMAT_R32G32B32A32_SFLOAT;
    vxAttrs[2].offset = offsetof(SpriteVertex, r);

    VkPipelineVertexInputStateCreateInfo vxInput{};
    vxInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    vxInput.vertexBindingDescriptionCount = 1;
    vxInput.pVertexBindingDescriptions = &vxBind;
    vxInput.vertexAttributeDescriptionCount = 3;
    vxInput.pVertexAttributeDescriptions = vxAttrs;

    // 输入装配
    VkPipelineInputAssemblyStateCreateInfo inputAssem{};
    inputAssem.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    inputAssem.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    // 视口 — render scale 离屏模式用降采样 extent；投影矩阵仍为物理尺寸，
    // NDC→viewport 映射自动把同一世界范围压进更少像素（缩放仅此一处公式适配）。
    // extent 按 m_renderScale 现场推导（不依赖 m_usingOffscreen/
    //   m_offscreenExtent 的设置时机——setRenderScale 中管线重建先于离屏目标创建）
    VkExtent2D rtExtent = m_swapchainExtent;
    if (m_renderScale < 1.0f) {
        rtExtent.width = (uint32_t)std::max(1, (int)llround(m_swapchainExtent.width * m_renderScale));
        rtExtent.height = (uint32_t)std::max(1, (int)llround(m_swapchainExtent.height * m_renderScale));
    }
    VkViewport viewport{};
    viewport.x = 0.0f;
    viewport.y = 0.0f;
    viewport.width = (float)rtExtent.width;
    viewport.height = (float)rtExtent.height;
    viewport.minDepth = 0.0f;
    viewport.maxDepth = 1.0f;

    VkRect2D scissor{};
    scissor.offset = {0, 0};
    scissor.extent = rtExtent;

    VkPipelineViewportStateCreateInfo vpState{};
    vpState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    vpState.viewportCount = 1;
    vpState.pViewports = &viewport;
    vpState.scissorCount = 1;
    vpState.pScissors = &scissor;

    // 光栅化
    VkPipelineRasterizationStateCreateInfo raster{};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;

    // 多重采样（禁用）
    VkPipelineMultisampleStateCreateInfo msaa{};
    msaa.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    msaa.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    // 深度/模板（禁用，2D 不需要）
    VkPipelineDepthStencilStateCreateInfo depth{};
    depth.sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
    depth.depthTestEnable = VK_FALSE;
    depth.depthWriteEnable = VK_FALSE;

    // 颜色混合（支持透明度）
    VkPipelineColorBlendAttachmentState blend{};
    blend.blendEnable = VK_TRUE;
    blend.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
    blend.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    blend.colorBlendOp = VK_BLEND_OP_ADD;
    blend.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
    blend.dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
    blend.alphaBlendOp = VK_BLEND_OP_ADD;
    blend.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                           VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;

    VkPipelineColorBlendStateCreateInfo blendState{};
    blendState.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blendState.attachmentCount = 1;
    blendState.pAttachments = &blend;

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = m_vertShader;
    stages[0].pName = "main";

    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = m_fragShader;
    stages[1].pName = "main";

    VkGraphicsPipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pipeInfo.stageCount = 2;
    pipeInfo.pStages = stages;
    pipeInfo.pVertexInputState = &vxInput;
    pipeInfo.pInputAssemblyState = &inputAssem;
    pipeInfo.pViewportState = &vpState;
    pipeInfo.pRasterizationState = &raster;
    pipeInfo.pMultisampleState = &msaa;
    pipeInfo.pDepthStencilState = &depth;
    pipeInfo.pColorBlendState = &blendState;
    pipeInfo.layout = m_pipelineLayout;
    pipeInfo.renderPass = m_renderPass;
    pipeInfo.subpass = 0;

    VkPipelineCache cache = m_pipelineCache ? m_pipelineCache : VK_NULL_HANDLE;
    if (vkCreateGraphicsPipelines(m_device, cache,
                                  1, &pipeInfo, nullptr, &m_pipeline)
        != VK_SUCCESS) {
        m_lastInitError = RenderInitError::VK_PIPELINE;
        LOGE("Failed to create graphics pipeline");
        return false;
    }

    // ── SkyBackground 屏幕空间渐变管线 ──
    // 与主管线共用顶点输入 / 视口 / 光栅化 / 混合；layout/着色器不同：
    //   - vertex：sky.vert（归一化屏幕→NDC，输出 inUV.v）
    //   - fragment：sky.frag（分段 smoothstep 解析渐变 + 有序抖动，参数经 push-constant）
    // 失败置空 → submitFrame 回退主管线（用顶点色三段渐变，无平滑/无抖动）。
    if (m_skyPipelineLayout != VK_NULL_HANDLE) {
        VkPipelineShaderStageCreateInfo skyStages[2]{};
        skyStages[0] = stages[0];
        skyStages[0].module = m_skyVertShader;
        skyStages[1] = stages[1];
        skyStages[1].module = m_skyFragShader;
        VkGraphicsPipelineCreateInfo skyInfo = pipeInfo;
        skyInfo.pStages = skyStages;
        skyInfo.stageCount = 2;
        skyInfo.layout = m_skyPipelineLayout;
        if (vkCreateGraphicsPipelines(m_device, cache, 1, &skyInfo, nullptr, &m_skyPipeline)
            != VK_SUCCESS) {
            LOGE("Failed to create sky pipeline — sky will fall back to main pipeline");
            m_skyPipeline = VK_NULL_HANDLE;
        }
    } else {
        LOGE("Sky pipeline layout not created — sky will fall back to main pipeline");
        m_skyPipeline = VK_NULL_HANDLE;
    }

    LOGI("Pipeline created successfully");
    return true;
}

void VulkanBackend::updateTextureDescriptor(Texture& tex) {
    if (m_descriptorPool == VK_NULL_HANDLE || m_descriptorSetLayout == VK_NULL_HANDLE) {
        LOGE("updateTextureDescriptor: pool/layout not ready");
        return;
    }
    // 独立描述符集：未分配则先分配（池/布局在 createPipeline 中创建）
    if (tex.descSet == VK_NULL_HANDLE) {
        VkDescriptorSetAllocateInfo alloc{};
        alloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        alloc.descriptorPool = m_descriptorPool;
        alloc.descriptorSetCount = 1;
        alloc.pSetLayouts = &m_descriptorSetLayout;
        if (vkAllocateDescriptorSets(m_device, &alloc, &tex.descSet) != VK_SUCCESS) {
            tex.descSet = VK_NULL_HANDLE;
            // 池容量超限：按需扩容重建池后重试一次——
            // 重建失败（vkCreateDescriptorPool 失败置空池）则回退白纹（既有语义）
            rebuildDescriptorPool((uint32_t)m_textures.size() + 2);
            if (m_descriptorPool == VK_NULL_HANDLE) {
                LOGE("updateTextureDescriptor: descriptor pool rebuild failed — tex %u falls back to white", tex.id);
                return;
            }
            if (vkAllocateDescriptorSets(m_device, &alloc, &tex.descSet) != VK_SUCCESS) {
                tex.descSet = VK_NULL_HANDLE;
                LOGE("updateTextureDescriptor: failed to allocate set for tex %u even after pool rebuild", tex.id);
                return;
            }
        }
    }

    VkDescriptorImageInfo descImg{};
    descImg.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    descImg.imageView = tex.view;
    descImg.sampler = tex.sampler;

    VkWriteDescriptorSet write{};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = tex.descSet;
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &descImg;

    vkUpdateDescriptorSets(m_device, 1, &write, 0, nullptr);
}

void VulkanBackend::rebuildDescriptorPool(uint32_t minSets) {
    if (m_device == VK_NULL_HANDLE || m_descriptorSetLayout == VK_NULL_HANDLE) return;
    // 调用契约：upload 路径（已持 m_gpuMutex）。落闸挡住渲染线程新帧提交，
    // waitIdle 排空在途帧——旧池销毁连带释放的 descSet 必须无在途引用。
    const bool wasReady = m_ready.exchange(false);
    vkDeviceWaitIdle(m_device);

    if (m_descriptorPool) {
        vkDestroyDescriptorPool(m_device, m_descriptorPool, nullptr);
        m_descriptorPool = VK_NULL_HANDLE;
    }
    // 旧池 descSet 已随池释放——白纹 + 全部注册纹理重分配
    m_whiteTexture.descSet = VK_NULL_HANDLE;
    for (auto& t : m_textures) t.descSet = VK_NULL_HANDLE;

    const uint32_t sets = std::max(minSets, std::max(16u, (uint32_t)m_textures.capacity() + 2));
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSize.descriptorCount = sets;
    VkDescriptorPoolCreateInfo dpInfo{};
    dpInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpInfo.maxSets = sets;
    dpInfo.poolSizeCount = 1;
    dpInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(m_device, &dpInfo, nullptr, &m_descriptorPool) != VK_SUCCESS) {
        m_descriptorPool = VK_NULL_HANDLE;
        LOGE("rebuildDescriptorPool: vkCreateDescriptorPool failed (%u sets)", sets);
        return;  // m_ready 保持 false——安全黑屏，surface 重建恢复（同 resize 失败语义）
    }
    updateTextureDescriptor(m_whiteTexture);
    for (auto& t : m_textures) updateTextureDescriptor(t);
    if (wasReady) m_ready = true;
    LOGI("Descriptor pool rebuilt: maxSets=%u", sets);
}

void VulkanBackend::destroyGraphicsObjects() {
    // 仅销毁依赖 Surface 的图形对象，保留 ShaderModule 和 PipelineCache
    if (m_pipeline) vkDestroyPipeline(m_device, m_pipeline, nullptr);
    m_pipeline = VK_NULL_HANDLE;
    if (m_skyPipeline) vkDestroyPipeline(m_device, m_skyPipeline, nullptr);
    m_skyPipeline = VK_NULL_HANDLE;
    if (m_pipelineLayout) vkDestroyPipelineLayout(m_device, m_pipelineLayout, nullptr);
    m_pipelineLayout = VK_NULL_HANDLE;
    if (m_skyPipelineLayout) vkDestroyPipelineLayout(m_device, m_skyPipelineLayout, nullptr);
    m_skyPipelineLayout = VK_NULL_HANDLE;
    if (m_renderPass) vkDestroyRenderPass(m_device, m_renderPass, nullptr);
    m_renderPass = VK_NULL_HANDLE;
    if (m_offscreenRenderPass) vkDestroyRenderPass(m_device, m_offscreenRenderPass, nullptr);
    m_offscreenRenderPass = VK_NULL_HANDLE;
    // descriptorPool 销毁时自动释放其 descriptorSet——resize 高频重建管线时
    // 防止 pool+set 泄漏累积显存。
    if (m_descriptorPool) vkDestroyDescriptorPool(m_device, m_descriptorPool, nullptr);
    m_descriptorPool = VK_NULL_HANDLE;
    // ★ 池销毁后所有纹理的 descSet 悬空——置空待重建（createPipeline 后
    //   updateTextureDescriptor 重新分配，upload 后同理）
    m_whiteTexture.descSet = VK_NULL_HANDLE;
    for (auto& tex : m_textures) tex.descSet = VK_NULL_HANDLE;
    if (m_descriptorSetLayout) vkDestroyDescriptorSetLayout(m_device, m_descriptorSetLayout, nullptr);
    m_descriptorSetLayout = VK_NULL_HANDLE;
}

void VulkanBackend::destroyShaderModules() {
    if (m_vertShader) vkDestroyShaderModule(m_device, m_vertShader, nullptr);
    m_vertShader = VK_NULL_HANDLE;
    if (m_fragShader) vkDestroyShaderModule(m_device, m_fragShader, nullptr);
    m_fragShader = VK_NULL_HANDLE;
    if (m_skyVertShader) vkDestroyShaderModule(m_device, m_skyVertShader, nullptr);
    m_skyVertShader = VK_NULL_HANDLE;
    if (m_skyFragShader) vkDestroyShaderModule(m_device, m_skyFragShader, nullptr);
    m_skyFragShader = VK_NULL_HANDLE;
}

// ============================================================
// Pipeline Cache 持久化
// 主流游戏做法：跨会话缓存已编译的管线，显著加速下次启动
// ============================================================

bool VulkanBackend::loadPipelineCache() {
    if (m_cacheDir[0] == '\0') return false;

    char path[320];
    snprintf(path, sizeof(path), "%s/%s", m_cacheDir, PIPELINE_CACHE_FILENAME);

    FILE* f = fopen(path, "rb");
    if (!f) {
        LOGI("Pipeline cache not found (%s), will create fresh", path);
        // 创建空 PipelineCache，后续 vkCreateGraphicsPipelines 会自动填充
        VkPipelineCacheCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
        vkCreatePipelineCache(m_device, &info, nullptr, &m_pipelineCache);
        return false;
    }

    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);

    if (size <= 0) {
        fclose(f);
        VkPipelineCacheCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
        vkCreatePipelineCache(m_device, &info, nullptr, &m_pipelineCache);
        return false;
    }

    std::vector<uint8_t> data(static_cast<size_t>(size));
    fread(data.data(), 1, static_cast<size_t>(size), f);
    fclose(f);

    VkPipelineCacheCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    info.initialDataSize = data.size();
    info.pInitialData = data.data();

    VkResult res = vkCreatePipelineCache(m_device, &info, nullptr, &m_pipelineCache);
    if (res != VK_SUCCESS) {
        LOGE("vkCreatePipelineCache from saved data failed (%d), creating fresh", res);
        VkPipelineCacheCreateInfo emptyInfo{};
        emptyInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
        vkCreatePipelineCache(m_device, &emptyInfo, nullptr, &m_pipelineCache);
        return false;
    }

    LOGI("Pipeline cache loaded: %ld bytes from %s", size, path);
    return true;
}

bool VulkanBackend::savePipelineCache() {
    if (!m_pipelineCache || m_cacheDir[0] == '\0') return false;

    size_t dataSize;
    VkResult res = vkGetPipelineCacheData(m_device, m_pipelineCache, &dataSize, nullptr);
    if (res != VK_SUCCESS || dataSize == 0) return false;

    std::vector<uint8_t> data(dataSize);
    res = vkGetPipelineCacheData(m_device, m_pipelineCache, &dataSize, data.data());
    if (res != VK_SUCCESS) return false;

    char path[320];
    snprintf(path, sizeof(path), "%s/%s", m_cacheDir, PIPELINE_CACHE_FILENAME);

    FILE* f = fopen(path, "wb");
    if (!f) return false;

    fwrite(data.data(), 1, data.size(), f);
    fclose(f);

    LOGI("Pipeline cache saved: %zu bytes", data.size());
    return true;
}

// ============================================================
// 缓冲区
// ============================================================

bool VulkanBackend::createVertexBuffer() {
    // 创建三缓冲 VBO（按 m_currentFrame 轮转写入，与 MAX_FRAMES_IN_FLIGHT
    // 对齐避免 GPU 读 CPU 写冲突/顶点撕裂）
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = m_vertexBufferSize / 2;  // 每个 buffer 为总大小的一半
    bufInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(m_physDevice, &memProps);

    for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        if (vkCreateBuffer(m_device, &bufInfo, nullptr, &m_vertexBuffers[i]) != VK_SUCCESS) {
            m_lastInitError = RenderInitError::VK_DEVICE_MEMORY;
            LOGE("Failed to create vertex buffer %d", i);
            return false;
        }

        VkMemoryRequirements memReq;
        vkGetBufferMemoryRequirements(m_device, m_vertexBuffers[i], &memReq);

        uint32_t memType = UINT32_MAX;
        for (uint32_t j = 0; j < memProps.memoryTypeCount; j++) {
            if ((memReq.memoryTypeBits & (1 << j)) &&
                (memProps.memoryTypes[j].propertyFlags &
                 (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                  VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
                (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                 VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                memType = j;
                break;
            }
        }

        if (memType == UINT32_MAX) {
            m_lastInitError = RenderInitError::VK_DEVICE_MEMORY;
            LOGE("No suitable memory type for VBO %d", i); return false;
        }

        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memReq.size;
        allocInfo.memoryTypeIndex = memType;

        if (vkAllocateMemory(m_device, &allocInfo, nullptr, &m_vertexMemories[i]) != VK_SUCCESS) {
            m_lastInitError = RenderInitError::VK_DEVICE_MEMORY;
            LOGE("Failed to allocate vertex memory %d", i);
            return false;
        }

        vkBindBufferMemory(m_device, m_vertexBuffers[i], m_vertexMemories[i], 0);
        vkMapMemory(m_device, m_vertexMemories[i], 0, VK_WHOLE_SIZE, 0, &m_vertexMapped[i]);

        LOGI("Vertex buffer %d: %llu bytes (mapped)", i, (unsigned long long)bufInfo.size);
    }
    return true;
}

// ============================================================
// Command / Sync
// ============================================================

bool VulkanBackend::createCommandObjects() {
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = m_graphicsQueueIndex;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;

    if (vkCreateCommandPool(m_device, &poolInfo, nullptr, &m_commandPool) != VK_SUCCESS) {
        m_lastInitError = RenderInitError::VK_COMMAND_POOL;
        LOGE("Failed to create command pool");
        return false;
    }

    uint32_t imgCount = (uint32_t)m_swapchainImages.size();
    m_commandBuffers.resize(imgCount);

    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = m_commandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = imgCount;

    if (vkAllocateCommandBuffers(m_device, &allocInfo, m_commandBuffers.data()) != VK_SUCCESS) {
        m_lastInitError = RenderInitError::VK_COMMAND_POOL;
        LOGE("Failed to allocate command buffers");
        return false;
    }
    return true;
}

bool VulkanBackend::createSynchronization() {
    m_imageAvailable.resize(MAX_FRAMES_IN_FLIGHT);
    m_renderFinished.resize(MAX_FRAMES_IN_FLIGHT);
    m_inFlightFences.resize(MAX_FRAMES_IN_FLIGHT);

    VkSemaphoreCreateInfo semInfo{};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;

    for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        if (vkCreateSemaphore(m_device, &semInfo, nullptr, &m_imageAvailable[i]) != VK_SUCCESS ||
            vkCreateSemaphore(m_device, &semInfo, nullptr, &m_renderFinished[i]) != VK_SUCCESS ||
            vkCreateFence(m_device, &fenceInfo, nullptr, &m_inFlightFences[i]) != VK_SUCCESS) {
            m_lastInitError = RenderInitError::VK_FENCE;
            LOGE("Failed to create sync objects");
            return false;
        }
    }
    return true;
}

// ============================================================
// 纹理 — OPTIMAL tiling + staging buffer 标准做法
// ============================================================

static uint32_t s_nextTextureId = 1;

/** 确保 staging buffer 有足够空间，不足则重新分配 */
bool VulkanBackend::ensureStagingBuffer(size_t requiredSize) {
    if (m_stagingBufferSize >= requiredSize) return true;

    // 销毁旧的 staging buffer
    if (m_stagingBuffer) vkDestroyBuffer(m_device, m_stagingBuffer, nullptr);
    if (m_stagingMemory) vkFreeMemory(m_device, m_stagingMemory, nullptr);
    m_stagingBuffer = VK_NULL_HANDLE;
    m_stagingMemory = VK_NULL_HANDLE;
    m_stagingBufferSize = 0;

    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = requiredSize;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(m_device, &bufInfo, nullptr, &m_stagingBuffer) != VK_SUCCESS) {
        LOGE("Failed to create staging buffer (%zu bytes)", requiredSize);
        return false;
    }

    VkMemoryRequirements memReq;
    vkGetBufferMemoryRequirements(m_device, m_stagingBuffer, &memReq);

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(m_physDevice, &memProps);

    uint32_t memType = UINT32_MAX;
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((memReq.memoryTypeBits & (1 << i)) &&
            (memProps.memoryTypes[i].propertyFlags &
             (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
              VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
            (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
             VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            memType = i;
            break;
        }
    }

    if (memType == UINT32_MAX) {
        LOGE("No suitable memory type for staging buffer");
        return false;
    }

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = memType;

    if (vkAllocateMemory(m_device, &allocInfo, nullptr, &m_stagingMemory) != VK_SUCCESS) {
        LOGE("Failed to allocate staging memory");
        return false;
    }

    vkBindBufferMemory(m_device, m_stagingBuffer, m_stagingMemory, 0);
    m_stagingBufferSize = requiredSize;
    LOGI("Staging buffer allocated: %zu bytes", requiredSize);
    return true;
}

/** 有限等待 fence：device lost 时 UINT64_MAX 等待会让持 m_gpuMutex
 *  的上传线程永久阻塞 → 渲染线程 submitFrame 抢同一锁 → 整条渲染管线静默冻结。
 *  100ms 步进轮询至 totalNs 上限。 */
static bool waitForFenceBounded(VkDevice device, VkFence fence, uint64_t totalNs) {
    constexpr uint64_t kStepNs = 100'000'000ULL;   // 100ms 步进
    for (uint64_t waited = 0; waited < totalNs; waited += kStepNs) {
        if (vkWaitForFences(device, 1, &fence, VK_TRUE, kStepNs) == VK_SUCCESS) return true;
    }
    return false;
}

/** 提交一次性 command buffer（用于 staging upload + layout transition）并等待完成。
 *  fence 等待有 2s 上限——超时按「疑似 device lost」放弃上传（调用方走 fail 清理
 *  路径，锁正常释放；熔断计数由调用方维护）。 */
static bool submitOneTimeCommands(
    VkDevice device, VkCommandPool pool, VkQueue queue,
    VkCommandBuffer cmd) {

    vkEndCommandBuffer(cmd);

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;

    VkFence fence;
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    vkCreateFence(device, &fenceInfo, nullptr, &fence);

    VkResult res = vkQueueSubmit(queue, 1, &submit, fence);
    if (res != VK_SUCCESS) {
        vkDestroyFence(device, fence, nullptr);
        vkFreeCommandBuffers(device, pool, 1, &cmd);
        return false;
    }

    if (!waitForFenceBounded(device, fence, 2'000'000'000ULL /*2s*/)) {
        LOGE("upload fence not signaled within 2s — suspect device lost, abandoning upload");
        vkDestroyFence(device, fence, nullptr);
        vkFreeCommandBuffers(device, pool, 1, &cmd);
        return false;
    }
    vkDestroyFence(device, fence, nullptr);
    vkFreeCommandBuffers(device, pool, 1, &cmd);
    return true;
}

uint32_t VulkanBackend::uploadTextureImpl(const void* pixels, int width, int height,
                                          VkSamplerAddressMode addressMode, int mipLevels) {
    if (!m_device || !pixels) return 0;
    if (mipLevels < 1) mipLevels = 1;  // 消毒（单级兼容）
    if (mipLevels > 32) { LOGE("uploadTextureImpl: mipLevels 越界 %d", mipLevels); return 0; }

    // ★ 全程持锁（m_gpuMutex）：本函数触碰 staging buffer / commandPool /
    //   m_graphicsQueue（vkQueueSubmit 后 fence 等待）并 push_back m_textures——
    //   与渲染线程 submitFrame 的查表/提交并发时即"Tile 短暂纯色"根因（见头文件注）。
    //   持锁跨越 fence 等待：上传期间渲染线程最多跳一帧（入口期仅画天空底色，可接受）。
    //   注：锁声明先于任何 goto，fail: 仍在锁作用域内——跳转合法，析构正常执行。
    std::lock_guard<std::mutex> gpuLock(m_gpuMutex);

    Texture tex;
    tex.width = width;
    tex.height = height;
    // 记录地址模式——setTextureQuality 重建采样器须按原模式（地面 REPEAT 不可变
    // CLAMP，否则整图铺 UV>1 被钳制为边缘单色 → 地面全黑）
    tex.addressMode = addressMode;

    // 逐级几何（2.3 RGBA mip 链）：level k 尺寸 = max(1, base>>k)；
    // level-major 紧凑布局总字节 = Σ levelSize（mipLevels=1 时即 w*h*4，即单级情形）
    uint64_t totalBytes = 0;
    for (int k = 0; k < mipLevels; k++) {
        const uint32_t lw = (uint32_t)std::max(1, width >> k);
        const uint32_t lh = (uint32_t)std::max(1, height >> k);
        totalBytes += (uint64_t)lw * lh * 4;
    }

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(m_physDevice, &memProps);

    // ---- Step 1: 创建 OPTIMAL tiling 图像 ----
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imgInfo.extent = { (uint32_t)width, (uint32_t)height, 1 };
    imgInfo.mipLevels = (uint32_t)mipLevels;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;   // OPTIMAL tiling 确保 REPEAT 兼容
    imgInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                    VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(m_device, &imgInfo, nullptr, &tex.image) != VK_SUCCESS) {
        LOGE("Failed to create OPTIMAL texture image");
        tex.image = VK_NULL_HANDLE;
        goto fail;
    }

    // 分配 DEVICE_LOCAL 内存
    {
        VkMemoryRequirements memReq;
        vkGetImageMemoryRequirements(m_device, tex.image, &memReq);

        uint32_t memType = UINT32_MAX;
        for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
            if ((memReq.memoryTypeBits & (1 << i)) &&
                (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
                memType = i;
                break;
            }
        }
        if (memType == UINT32_MAX) {
            // 回退到 HOST_VISIBLE（部分 Mali GPU 无纯 DEVICE_LOCAL 可选）
            for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
                if (memReq.memoryTypeBits & (1 << i)) {
                    memType = i;
                    break;
                }
            }
        }
        if (memType == UINT32_MAX) { LOGE("No memory type for texture image"); goto fail; }

        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memReq.size;
        allocInfo.memoryTypeIndex = memType;

        if (vkAllocateMemory(m_device, &allocInfo, nullptr, &tex.memory) != VK_SUCCESS) {
            LOGE("Failed to allocate texture memory"); goto fail;
        }
        vkBindImageMemory(m_device, tex.image, tex.memory, 0);
    }

    // ---- Step 2: 通过 staging buffer 上传像素数据（level-major 紧凑，一次 memcpy） ----
    {
        if (!ensureStagingBuffer((size_t)totalBytes)) goto fail;

        void* mapped = nullptr;
        if (vkMapMemory(m_device, m_stagingMemory, 0, totalBytes, 0, &mapped) != VK_SUCCESS ||
            !mapped) {
            // 映射失败 → memcpy 到空指针 SIGSEGV（device lost 等罕见路径）
            LOGE("uploadTexture: staging map failed");
            goto fail;
        }
        memcpy(mapped, pixels, (size_t)totalBytes);
        vkUnmapMemory(m_device, m_stagingMemory);
    }

    // ---- Step 3: 提交 vkCmdCopyBufferToImage + Layout Transition ----
    {
        VkCommandBufferAllocateInfo cmdAlloc{};
        cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cmdAlloc.commandPool = m_commandPool;
        cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cmdAlloc.commandBufferCount = 1;

        VkCommandBuffer cmd;
        if (vkAllocateCommandBuffers(m_device, &cmdAlloc, &cmd) != VK_SUCCESS) {
            LOGE("Failed to alloc command buffer for texture upload"); goto fail;
        }

        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &beginInfo);

        // UNDEFINED → TRANSFER_DST_OPTIMAL（全 mip 层；单级时 levelCount=1 与旧行为一致）
        VkImageMemoryBarrier preBarrier{};
        preBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        preBarrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        preBarrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        preBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        preBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        preBarrier.image = tex.image;
        preBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        preBarrier.subresourceRange.levelCount = (uint32_t)mipLevels;
        preBarrier.subresourceRange.layerCount = 1;
        preBarrier.srcAccessMask = 0;
        preBarrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &preBarrier);

        // Copy: staging buffer → image（mipLevels>1 时逐级 VkBufferImageCopy——
        // level-major 紧凑布局，bufferOffset 逐级累积；RGBA8 每级尺寸 4 字节倍数，
        // 累积偏移恒 4 字节对齐，满足 VUID-VkBufferImageCopy-bufferOffset 对齐约束）
        std::vector<VkBufferImageCopy> regions;
        regions.reserve(mipLevels);
        uint64_t offset = 0;
        for (int k = 0; k < mipLevels; k++) {
            VkBufferImageCopy r{};
            r.bufferOffset = offset;
            r.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            r.imageSubresource.mipLevel = (uint32_t)k;
            r.imageSubresource.baseArrayLayer = 0;
            r.imageSubresource.layerCount = 1;
            r.imageExtent = { (uint32_t)std::max(1, width >> k),
                              (uint32_t)std::max(1, height >> k), 1 };
            regions.push_back(r);
            offset += (uint64_t)r.imageExtent.width * r.imageExtent.height * 4;
        }
        vkCmdCopyBufferToImage(cmd, m_stagingBuffer, tex.image,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                               (uint32_t)regions.size(), regions.data());

        // TRANSFER_DST → SHADER_READ_ONLY_OPTIMAL（全 mip 层）
        VkImageMemoryBarrier postBarrier{};
        postBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        postBarrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        postBarrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        postBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        postBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        postBarrier.image = tex.image;
        postBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        postBarrier.subresourceRange.levelCount = (uint32_t)mipLevels;
        postBarrier.subresourceRange.layerCount = 1;
        postBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        postBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &postBarrier);

        if (!submitOneTimeCommands(m_device, m_commandPool, m_graphicsQueue, cmd)) {
            LOGE("Failed to submit texture upload commands");
            noteUploadResult(false);   // 熔断计数
            goto fail;
        }
        noteUploadResult(true);
    }

    // ---- Step 4: ImageView（全 mip 层） ----
    {
        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = tex.image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = (uint32_t)mipLevels;
        viewInfo.subresourceRange.layerCount = 1;

        if (vkCreateImageView(m_device, &viewInfo, nullptr, &tex.view) != VK_SUCCESS) {
            LOGE("Failed to create texture view"); goto fail;
        }
    }

    // ---- Step 5: Sampler（图集与地面均 LINEAR 双线性平滑——图集建筑槽位
    // 放大倍数可达 1.5x，NEAREST 会产生像素颗粒感，改 LINEAR 平滑；
    // 地面整图铺 LINEAR 亦消除 REPEAT 环绕点纹理边界跳变的暗接缝。
    // B.1：经 createSampler 按当前 mipmap/各向异性质量创建，双端一致） ----
    if (!createSampler(tex.sampler, addressMode)) { LOGE("Failed to create sampler"); goto fail; }

    {
        uint32_t id = s_nextTextureId++;
        tex.id = id;
        m_textures.push_back(tex);
        // ★ 分配独立描述符集并写入（非 command buffer 记录期——上传在主线程）
        updateTextureDescriptor(m_textures.back());
        LOGI("Texture %dx%d uploaded (id=%u, OPTIMAL, mips=%d)", width, height, id, mipLevels);
        return id;
    }

fail:
    // 失败时清理已创建的资源
    if (tex.view) vkDestroyImageView(m_device, tex.view, nullptr);
    if (tex.image) vkDestroyImage(m_device, tex.image, nullptr);
    if (tex.memory) vkFreeMemory(m_device, tex.memory, nullptr);
    if (tex.sampler) vkDestroySampler(m_device, tex.sampler, nullptr);
    tex = {};
    return 0;
}

uint32_t VulkanBackend::uploadTexture(const void* pixels, int width, int height) {
    return uploadTextureImpl(pixels, width, height, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
}

uint32_t VulkanBackend::uploadRepeatTexture(const void* pixels, int width, int height) {
    return uploadTextureImpl(pixels, width, height, VK_SAMPLER_ADDRESS_MODE_REPEAT);
}

uint32_t VulkanBackend::uploadMipChainTexture(const void* pixels, int width, int height,
                                              int mipCount) {
    // 几何防御：首级尺寸合法 + 级数与尺寸匹配（2^k 链至少到 2×2——
    // level-major 紧凑布局由 Kotlin encodeBitmapToRgbaMipChain 产出，逐级尺寸
    // max(1, base>>k)，与 uploadTextureImpl 的逐级拷贝几何一致）
    if (width <= 0 || height <= 0) {
        LOGE("uploadMipChainTexture: 尺寸非法 %dx%d", width, height);
        return 0;
    }
    if (mipCount < 1) {
        LOGE("uploadMipChainTexture: mipCount 非法 %d", mipCount);
        return 0;
    }
    return uploadTextureImpl(pixels, width, height, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, mipCount);
}

/**
 * 按当前采样质量（m_anisotropyMax / m_mipmapEnabled）+ 地址模式创建采样器（B.1）。
 * 供上传与 setTextureQuality 复用，保证三线性 mip / 各向异性在双端一致生效。
 * 单 mip 纹理（RGBA/地面）即使开启 mipmap 也安全（无更深层即钳制到 level 0）。
 */
bool VulkanBackend::createSampler(VkSampler& out, VkSamplerAddressMode addressMode) {
    VkSamplerCreateInfo sampInfo{};
    sampInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sampInfo.magFilter = VK_FILTER_LINEAR;
    if (m_mipmapEnabled) {
        // 三线性 mip 过滤（Vulkan 无 VK_FILTER_LINEAR_MIPMAP_LINEAR——mip 过滤由
        // mipmapMode + minFilter 组合表达，minFilter 取 LINEAR 即可）
        sampInfo.minFilter = VK_FILTER_LINEAR;
        sampInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
        sampInfo.minLod = 0.0f;
        sampInfo.maxLod = VK_LOD_CLAMP_NONE;  // 允许全 mip 链（实际层级受图像 mipCount 钳制）
    } else {
        sampInfo.minFilter = VK_FILTER_LINEAR;
        sampInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        sampInfo.minLod = 0.0f;
        sampInfo.maxLod = 1.0f;
    }
    sampInfo.addressModeU = addressMode;
    sampInfo.addressModeV = addressMode;
    if (m_anisoSupported && m_anisotropyMax > 0.0f) {
        sampInfo.anisotropyEnable = VK_TRUE;
        sampInfo.maxAnisotropy = m_anisotropyMax;
    } else {
        sampInfo.anisotropyEnable = VK_FALSE;
    }
    if (vkCreateSampler(m_device, &sampInfo, nullptr, &out) != VK_SUCCESS) {
        LOGE("Failed to create sampler (quality=%s aniso=%.1f)",
             m_mipmapEnabled ? "mip" : "linear", m_anisotropyMax);
        return false;
    }
    return true;
}

void VulkanBackend::setTextureQuality(float anisotropyMax, bool mipmap) {
    // 消毒（NaN/负数/越界 → 安全值）
    if (!(anisotropyMax >= 0.0f) || !(anisotropyMax <= 16.0f)) anisotropyMax = 2.0f;
    if (mipmap == m_mipmapEnabled && anisotropyMax == m_anisotropyMax) {
        LOGI("setTextureQuality: 无变化 (aniso=%.1f mip=%d), 跳过", m_anisotropyMax, m_mipmapEnabled);
        return;
    }
    // ★ 持锁（m_gpuMutex）：遍历 m_textures 销毁/重建采样器并改写 descSet——
    //   与主线程上传 push_back 并发即 vector 悬垂（见头文件注）。
    std::lock_guard<std::mutex> gpuLock(m_gpuMutex);
    m_anisotropyMax = anisotropyMax;
    m_mipmapEnabled = mipmap;
    // 重建所有已上传纹理的采样器（图集/地面/图集 RGBA 回退同通道），白纹理保持 NEAREST 单 mip 不变。
    // ★ 采样器重建后须重新写入各纹理的独立描述符集（vkUpdateDescriptorSets 在本函数
    //   调用期——渲染循环内、command buffer 未记录——合法；submitFrame 仅 bind）
    for (auto& tex : m_textures) {
        if (!tex.sampler) continue;
        vkDestroySampler(m_device, tex.sampler, nullptr);
        tex.sampler = VK_NULL_HANDLE;
        // ★ 按纹理原始地址模式重建（地面 REPEAT / 图集 CLAMP）——硬编码 CLAMP
        //   会把地面整图铺采样 UV>1 钳制为边缘单色 → 地面全黑
        if (!createSampler(tex.sampler, tex.addressMode)) {
            LOGE("setTextureQuality: 重建采样器失败 tex=%u, 回退单 mip 线性", tex.id);
            // 兜底：单 mip 线性采样，不中断
            VkSamplerCreateInfo fallback{};
            fallback.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
            fallback.magFilter = VK_FILTER_LINEAR;
            fallback.minFilter = VK_FILTER_LINEAR;
            fallback.addressModeU = tex.addressMode;
            fallback.addressModeV = tex.addressMode;
            fallback.maxLod = 1.0f;
            vkCreateSampler(m_device, &fallback, nullptr, &tex.sampler);
        }
        updateTextureDescriptor(tex);
    }
    LOGI("setTextureQuality: aniso=%.1f mip=%d 已应用（%zu 个纹理采样器重建）",
         m_anisotropyMax, m_mipmapEnabled, m_textures.size());
}

// ============================================================
// ASTC 压缩纹理上传（KTX 数据段，已由 KtxLoader 校验头）
// 与 uploadTexture 同 staging 上传模式，仅图像格式/数据布局不同：
//   - 格式 VK_FORMAT_ASTC_4x4_UNORM_BLOCK（压缩块，非 RGBA 线性）
//   - staging 数据 = 块数 × 16 字节（非 w*h*4）
//   - 设备无 textureCompressionASTC_LDR 特性时返回 0（Kotlin 回退 RGBA 图集）
// ============================================================
uint32_t VulkanBackend::uploadCompressedTexture(const uint8_t* data, size_t dataSize,
                                                int width, int height, int mipCount) {
    if (!m_device || !data || !m_astcSupported) return 0;
    if (width <= 0 || height <= 0 ||
        width % ktx1::ASTC_BLOCK != 0 || height % ktx1::ASTC_BLOCK != 0) {
        LOGE("uploadCompressedTexture: 尺寸非法 %dx%d（需 4 的倍数）", width, height);
        return 0;
    }
    // 尺寸上限（与 KtxLoader 同约束，防御纵深——本方法可被独立调用）
    if ((uint32_t)width > ktx1::MAX_TEXTURE_DIMENSION ||
        (uint32_t)height > ktx1::MAX_TEXTURE_DIMENSION) {
        LOGE("uploadCompressedTexture: 尺寸超上限 %dx%d", width, height);
        return 0;
    }
    if (mipCount < 1) mipCount = 1;  // 消毒（B.1 单 mip 兼容）

    // ★ 全程持锁（m_gpuMutex）：与 uploadTextureImpl 同理由——staging/commandPool/
    //   queue/m_textures 跨线程互斥（21MB ASTC 上传期间渲染线程最多跳一帧）。
    std::lock_guard<std::mutex> gpuLock(m_gpuMutex);

    // 逐级数据几何校验（与 KtxLoader/build-atlas.mjs 同式）——防越界/截断。
    // 每级尺寸 = max(ASTC_BLOCK, base >> level)；到 4×4 块下限为止。
    // 64 位算术防 32 位 size_t 回绕
    uint64_t expectedTotal = 0;
    for (int i = 0; i < mipCount; i++) {
        uint32_t lw = (uint32_t)width >> i;
        uint32_t lh = (uint32_t)height >> i;
        if (lw < ktx1::ASTC_BLOCK) lw = ktx1::ASTC_BLOCK;
        if (lh < ktx1::ASTC_BLOCK) lh = ktx1::ASTC_BLOCK;
        expectedTotal += (uint64_t)(lw / ktx1::ASTC_BLOCK) * (uint64_t)(lh / ktx1::ASTC_BLOCK) *
                         ktx1::ASTC_BLOCK_BYTES;
    }
    // data 为 KTX1 数据区（含逐级 [size4] 前缀）——总字节 = 各级 [size4] + 数据
    uint64_t expectedRegion = expectedTotal + (uint64_t)mipCount * ktx1::DATA_SIZE_FIELD;
    if ((uint64_t)dataSize != expectedRegion) {
        LOGE("uploadCompressedTexture: 数据区尺寸不符 %zu != %llu (mips=%d)",
             dataSize, (unsigned long long)expectedRegion, mipCount);
        return 0;
    }

    Texture tex;
    tex.width = width;
    tex.height = height;

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(m_physDevice, &memProps);

    // ---- Step 1: 创建 OPTIMAL tiling 压缩图像（多 mip，B.1） ----
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_ASTC_4x4_UNORM_BLOCK;
    imgInfo.extent = { (uint32_t)width, (uint32_t)height, 1 };
    imgInfo.mipLevels = (uint32_t)mipCount;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                    VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(m_device, &imgInfo, nullptr, &tex.image) != VK_SUCCESS) {
        LOGE("Failed to create ASTC texture image");
        tex.image = VK_NULL_HANDLE;
        goto fail;
    }

    // 分配 DEVICE_LOCAL 内存
    {
        VkMemoryRequirements memReq;
        vkGetImageMemoryRequirements(m_device, tex.image, &memReq);

        uint32_t memType = UINT32_MAX;
        for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
            if ((memReq.memoryTypeBits & (1 << i)) &&
                (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
                memType = i;
                break;
            }
        }
        if (memType == UINT32_MAX) {
            // 回退到 HOST_VISIBLE（部分 Mali GPU 无纯 DEVICE_LOCAL 可选）
            for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
                if (memReq.memoryTypeBits & (1 << i)) {
                    memType = i;
                    break;
                }
            }
        }
        if (memType == UINT32_MAX) { LOGE("No memory type for ASTC texture"); goto fail; }

        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memReq.size;
        allocInfo.memoryTypeIndex = memType;

        if (vkAllocateMemory(m_device, &allocInfo, nullptr, &tex.memory) != VK_SUCCESS) {
            LOGE("Failed to allocate ASTC texture memory"); goto fail;
        }
        vkBindImageMemory(m_device, tex.image, tex.memory, 0);
    }

    // ---- Step 2: 通过 staging buffer 上传全部 mip 数据区 ----
    {
        if (!ensureStagingBuffer(dataSize)) goto fail;

        void* mapped = nullptr;
        if (vkMapMemory(m_device, m_stagingMemory, 0, dataSize, 0, &mapped) != VK_SUCCESS ||
            !mapped) {
            // 映射失败 → memcpy 到空指针 SIGSEGV
            LOGE("uploadCompressedTexture: staging map failed");
            goto fail;
        }
        // 数据布局约束：逐级跳过 KTX [size4]
        //   前缀紧凑拷贝纯块数据——bufferOffset 必须保持 16 字节（ASTC 块大小）
        //   倍数，违反 VUID-VkBufferImageCopy-bufferOffset-00193 会导致
        //   块级错位、图集内容错乱
        size_t dstOffset = 0;
        size_t srcCursor = 0;
        for (int i = 0; i < mipCount; i++) {
            uint32_t lw = (uint32_t)width >> i;
            uint32_t lh = (uint32_t)height >> i;
            if (lw < ktx1::ASTC_BLOCK) lw = ktx1::ASTC_BLOCK;
            if (lh < ktx1::ASTC_BLOCK) lh = ktx1::ASTC_BLOCK;
            const size_t levelSize =
                (size_t)(lw / ktx1::ASTC_BLOCK) * (size_t)(lh / ktx1::ASTC_BLOCK) *
                ktx1::ASTC_BLOCK_BYTES;
            memcpy((char*)mapped + dstOffset,
                   data + srcCursor + ktx1::DATA_SIZE_FIELD, levelSize);
            dstOffset += levelSize;
            srcCursor += ktx1::DATA_SIZE_FIELD + levelSize;
        }
        vkUnmapMemory(m_device, m_stagingMemory);
    }

    // ---- Step 3: 提交 vkCmdCopyBufferToImage（逐级 mip）+ Layout Transition ----
    {
        VkCommandBufferAllocateInfo cmdAlloc{};
        cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cmdAlloc.commandPool = m_commandPool;
        cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cmdAlloc.commandBufferCount = 1;

        VkCommandBuffer cmd;
        if (vkAllocateCommandBuffers(m_device, &cmdAlloc, &cmd) != VK_SUCCESS) {
            LOGE("Failed to alloc command buffer for ASTC upload"); goto fail;
        }

        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &beginInfo);

        // UNDEFINED → TRANSFER_DST_OPTIMAL（全 mip 层）
        VkImageMemoryBarrier preBarrier{};
        preBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        preBarrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        preBarrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        preBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        preBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        preBarrier.image = tex.image;
        preBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        preBarrier.subresourceRange.levelCount = (uint32_t)mipCount;
        preBarrier.subresourceRange.layerCount = 1;
        preBarrier.srcAccessMask = 0;
        preBarrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &preBarrier);

        // 逐级 Copy: staging buffer → image（staging 已为纯块数据紧凑布局——
        //   bufferOffset = 各前缀级数据长度和，恒为 16 字节块对齐）
        std::vector<VkBufferImageCopy> regions;
        size_t cursor = 0;  // staging 纯数据游标（16 字节对齐）
        for (int i = 0; i < mipCount; i++) {
            uint32_t lw = (uint32_t)width >> i;
            uint32_t lh = (uint32_t)height >> i;
            if (lw < ktx1::ASTC_BLOCK) lw = ktx1::ASTC_BLOCK;
            if (lh < ktx1::ASTC_BLOCK) lh = ktx1::ASTC_BLOCK;
            const uint32_t levelSize =
                (lw / ktx1::ASTC_BLOCK) * (lh / ktx1::ASTC_BLOCK) * ktx1::ASTC_BLOCK_BYTES;
            VkBufferImageCopy r{};
            r.bufferOffset = cursor;  // 纯块数据偏移（恒 16 字节对齐，满足 VUID 00193）
            r.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            r.imageSubresource.mipLevel = (uint32_t)i;
            r.imageSubresource.baseArrayLayer = 0;
            r.imageSubresource.layerCount = 1;
            r.imageExtent = { lw, lh, 1 };
            regions.push_back(r);
            cursor += levelSize;
        }
        vkCmdCopyBufferToImage(cmd, m_stagingBuffer, tex.image,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                               (uint32_t)regions.size(), regions.data());

        // TRANSFER_DST → SHADER_READ_ONLY_OPTIMAL（全 mip 层）
        VkImageMemoryBarrier postBarrier{};
        postBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        postBarrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        postBarrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        postBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        postBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        postBarrier.image = tex.image;
        postBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        postBarrier.subresourceRange.levelCount = (uint32_t)mipCount;
        postBarrier.subresourceRange.layerCount = 1;
        postBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        postBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &postBarrier);

        if (!submitOneTimeCommands(m_device, m_commandPool, m_graphicsQueue, cmd)) {
            LOGE("Failed to submit ASTC upload commands");
            noteUploadResult(false);   // 熔断计数
            goto fail;
        }
        noteUploadResult(true);
    }

    // ---- Step 4: ImageView（压缩格式，多 mip，B.1） ----
    {
        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = tex.image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = VK_FORMAT_ASTC_4x4_UNORM_BLOCK;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = (uint32_t)mipCount;
        viewInfo.subresourceRange.layerCount = 1;

        if (vkCreateImageView(m_device, &viewInfo, nullptr, &tex.view) != VK_SUCCESS) {
            LOGE("Failed to create ASTC texture view"); goto fail;
        }
    }

    // ---- Step 5: Sampler（按当前 mipmap/各向异性质量，B.1） ----
    if (!createSampler(tex.sampler, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)) {
        LOGE("Failed to create ASTC sampler"); goto fail;
    }

    {
        uint32_t id = s_nextTextureId++;
        tex.id = id;
        m_textures.push_back(tex);
        // ★ 分配独立描述符集并写入（非 command buffer 记录期——上传在主线程）
        updateTextureDescriptor(m_textures.back());
        LOGI("ASTC texture %dx%d uploaded (id=%u, %zu bytes, mips=%d)",
             width, height, id, dataSize, mipCount);
        return id;
    }

fail:
    // 失败时清理已创建的资源
    if (tex.view) vkDestroyImageView(m_device, tex.view, nullptr);
    if (tex.image) vkDestroyImage(m_device, tex.image, nullptr);
    if (tex.memory) vkFreeMemory(m_device, tex.memory, nullptr);
    if (tex.sampler) vkDestroySampler(m_device, tex.sampler, nullptr);
    tex = {};
    return 0;
}

/** 上传结果熔断记录：连续超时 ≥3 判定疑似 device lost——m_ready=false
 *  停止提交，渲染停止、安全黑屏而非无限冻结；surface 重建/应用重启恢复。 */
void VulkanBackend::noteUploadResult(bool ok) {
    if (ok) {
        m_uploadTimeoutCount = 0;
        return;
    }
    if (++m_uploadTimeoutCount >= 3) {
        LOGE("device lost suspected (%d consecutive upload failures) — disabling submits",
             m_uploadTimeoutCount);
        m_ready = false;
    }
}

/** 释放退役纹理资源（调用方须持 m_gpuMutex 且在途采样已由 fence 确认结束） */
void VulkanBackend::freeTextureResources(uint32_t id) {
    for (auto it = m_textures.begin(); it != m_textures.end(); ++it) {
        if (it->id != id) continue;
        if (it->view) vkDestroyImageView(m_device, it->view, nullptr);
        if (it->image) vkDestroyImage(m_device, it->image, nullptr);
        if (it->memory) vkFreeMemory(m_device, it->memory, nullptr);
        if (it->sampler) vkDestroySampler(m_device, it->sampler, nullptr);
        m_textures.erase(it);
        return;
    }
}

void VulkanBackend::destroyTexture(uint32_t id) {
    if (id == 0) return;  // 白纹理永不退役
    // 延迟释放：纹理可能正被在途帧采样——立即 vkDestroy* 会破坏
    // 在途帧。入队退役，submitFrame 在 fence 确认且超过 MAX_FRAMES_IN_FLIGHT 帧
    // 后真实释放（id==0 白纹理除外）。任意线程可调（与 upload 同线程契约）。
    std::lock_guard<std::mutex> gpuLock(m_gpuMutex);
    m_retiredTextures.push_back({ id, m_frameCounter });
}

// ============================================================
// 帧渲染
// ============================================================

void VulkanBackend::setProjection(const float mat[16]) {
    memcpy(m_projMatrix, mat, sizeof(m_projMatrix));
}

void VulkanBackend::draw(const SpriteVertex* vertices, int count,
                          uint32_t textureId) {
    if (!m_ready || count == 0) return;

    // 直接写入当前帧 VBO（三缓冲：按 m_currentFrame 索引——与
    //   MAX_FRAMES_IN_FLIGHT 对齐，GPU 消费完同索引前帧（fence 保证）才覆写，
    //   根除双缓冲+3 in-flight 的顶点撕裂）
    size_t copySize = count * sizeof(SpriteVertex);
    if (m_vboOffset + (int)copySize > (int)(m_vertexBufferSize / 2)) {
        LOGE("VBO overflow: %d + %zu > %llu",
             m_vboOffset, copySize, (unsigned long long)(m_vertexBufferSize / 2));
        return;
    }

    memcpy((char*)m_vertexMapped[m_currentFrame] + m_vboOffset,
           vertices, copySize);

    m_pendingDraws.push_back({
        (uint32_t)(m_vboOffset / sizeof(SpriteVertex)),
        count,
        textureId
    });
    m_vboOffset += (int)copySize;
}

void VulkanBackend::drawBackground(const SpriteVertex* vertices, int count,
                                   const SkyGradientParams& params) {
    if (!m_ready || count == 0 || !vertices) return;

    // 屏幕空间背景：写入当前帧 VBO 头部（offset=0）。注意: 必须在所有世界 draw()
    // 之前调用（NativeBridge.drawSky 帧首触发）——本函数推进 m_vboOffset，使后续世界
    // 顶点追加在背景之后；背景绘制命令单独记录，submitFrame 最先绘制。
    size_t copySize = count * sizeof(SpriteVertex);
    if (m_vboOffset + (int)copySize > (int)(m_vertexBufferSize / 2)) {
        LOGE("VBO overflow (background): %d + %zu > %llu",
             m_vboOffset, copySize, (unsigned long long)(m_vertexBufferSize / 2));
        return;
    }

    memcpy((char*)m_vertexMapped[m_currentFrame] + m_vboOffset, vertices, copySize);
    m_backgroundVertexCount = count;
    m_skyParams = params;   // 供 submitFrame 经 push-constant 推给 sky.frag
    m_vboOffset += (int)copySize;
}

void VulkanBackend::beginFrame() {
    m_pendingDraws.clear();
    // 三缓冲按 m_currentFrame 轮转（submitFrame 末尾递增），此处仅重置写入偏移
    m_vboOffset = 0;
    m_backgroundVertexCount = 0;
}

void VulkanBackend::endFrame() {
    // 空实现 — 所有工作在 submitFrame 中完成
}

void VulkanBackend::submitFrame() {
    if (!m_ready) return;

    // 有限等待前帧完成（渲染线程 vk 调用全面有界——UINT64_MAX 等待
    // 在 device lost 时使「渲染线程卡 >2s」具备物理可能，即 Kotlin skip-release
    // 的触发条件）。超时视为疑似 device lost：落闸停提交（安全黑屏，surface
    // 重建/应用重启恢复），语义与上传熔断（noteUploadResult）一致。
    if (!waitForFenceBounded(m_device, m_inFlightFences[m_currentFrame],
                             2'000'000'000ULL /*2s*/)) {
        LOGE("submitFrame: fence not signaled within 2s — suspect device lost, stopping submit");
        m_ready = false;
        return;
    }
    // ★ 守卫：等待 fence 期间 shutdown() 可能已将 m_ready 置 false 并开始销毁资源
    if (!m_ready) return;

    // ── 退役纹理释放：本帧 fence 已确认，更早帧的采样必已结束——
    //    超过 MAX_FRAMES_IN_FLIGHT 帧的退役纹理此时销毁安全。destroyTexture
    //    （任意线程）可能并发入队 → 持 m_gpuMutex 短临界区。
    {
        std::lock_guard<std::mutex> gpuLock(m_gpuMutex);
        for (auto it = m_retiredTextures.begin(); it != m_retiredTextures.end(); ) {
            if (m_frameCounter - it->retiredAtFrame > (uint64_t)MAX_FRAMES_IN_FLIGHT) {
                freeTextureResources(it->id);
                it = m_retiredTextures.erase(it);
            } else {
                ++it;
            }
        }
    }

    // 获取下一张 swapchain 图像（有界化——单轮 250ms，spin 上限 8 轮，
    // 每轮检查 m_ready；Surface 销毁后渲染线程必然在有限时间内退出本函数）
    uint32_t imageIndex = 0;
    VkResult result = VK_TIMEOUT;
    for (int spin = 0; spin < ACQUIRE_SPIN_LIMIT && m_ready; ++spin) {
        result = vkAcquireNextImageKHR(
            m_device, m_swapchain, ACQUIRE_TIMEOUT_NS,
            m_imageAvailable[m_currentFrame], VK_NULL_HANDLE, &imageIndex);
        if (result != VK_TIMEOUT) break;
    }
    // 有界放弃（VK_TIMEOUT = swapchain 无可用图像超限）或纪元已销毁——线程必然退出
    if (result == VK_TIMEOUT || !m_ready) return;

    if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
        LOGI("Swapchain out of date, need resize");
        // ★ 守卫：out-of-date 发生在 shutdown 竞态中时，fence 可能已被销毁
        if (!m_ready) return;
        // 不重置 fence — fence 保持 signaled 状态，下一帧可正常等待。
        return;
    }
    // 其他错误（SURFACE_LOST/DEVICE_LOST 等）时 imageIndex 未定义，
    // 继续 vkResetFences + 用垃圾 imageIndex 索引 framebuffers 会二次损坏——
    // 直接放弃本帧（fence 保持 signaled，渲染由外层生命周期重建）
    if (result != VK_SUCCESS) {
        LOGE("submitFrame: vkAcquireNextImageKHR failed (%d) — skipping frame", result);
        return;
    }

    // 成功获取图像后才重置 fence（防止 out-of-date 提前返回后 fence 未被 signal）
    vkResetFences(m_device, 1, &m_inFlightFences[m_currentFrame]);

    VkCommandBuffer cmd = m_commandBuffers[imageIndex];

    // 记录 Command Buffer
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

    vkBeginCommandBuffer(cmd, &beginInfo);

    // ★ 纯黑清屏：淡入期间（fadeAlpha<1）瓦片半透明
    //   会透出清屏色，清屏色必须为纯黑（米白清屏色会呈"全屏半透明白色覆盖"，
    //   与 Canvas 路径同一问题）。
    VkClearValue clearColor = { { { 0.0f, 0.0f, 0.0f, 1.0f } } };

    // render scale 离屏模式：渲染进降采样目标（offscreen），提交前 blit 上采样到交换链
    const bool offscreen = m_usingOffscreen;
    VkRenderPassBeginInfo rpBegin{};
    rpBegin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    // ★ 离屏用独立 renderPass（finalLayout=TRANSFER_SRC_OPTIMAL，对非交换链图像合法）
    rpBegin.renderPass = offscreen ? m_offscreenRenderPass : m_renderPass;
    rpBegin.framebuffer = offscreen ? m_offscreenFramebuffers[m_currentFrame] : m_framebuffers[imageIndex];
    rpBegin.renderArea.offset = {0, 0};
    rpBegin.renderArea.extent = offscreen ? m_offscreenExtent : m_swapchainExtent;
    rpBegin.clearValueCount = 1;
    rpBegin.pClearValues = &clearColor;

    vkCmdBeginRenderPass(cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);

    // ── SkyBackground 屏幕空间背景（最先绘制 — 最底图层）──────────────────
    // 用"屏幕正交投影"（m_screenOrtho）而非相机矩阵(m_projMatrix)，Camera 平移/缩放
    // 完全不作用于背景；渐变顶点色经天空管线（sky.frag：顶点色 + 有序抖动去色带）渲染，
    // 若天空管线创建失败则回退主管线（白纹理 × 顶点色，无抖动）。VBO offset=0，
    // 顶点已在 drawBackground() 写入当前帧 VBO 头部。
    if (m_backgroundVertexCount > 0) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                          m_skyPipeline ? m_skyPipeline : m_pipeline);
        if (m_skyPipeline) {
            // 天空管线：push-constant = 屏幕正交投影(mat4) + 四段颜色（color 取 .rgb，
            // 位置/强度编码在 alpha：c0.a=t1, c1.a=t2, c2.a=strength），共 128B VERTEX|FRAGMENT。
            // 片元按 inUV.v 分段 smoothstep 解析渐变。
            SkyPushConstants skyPC{};
            memcpy(skyPC.proj, m_screenOrtho, sizeof(skyPC.proj));
            skyPC.topColor[0] = m_skyParams.topColor[0]; skyPC.topColor[1] = m_skyParams.topColor[1];
            skyPC.topColor[2] = m_skyParams.topColor[2]; skyPC.topColor[3] = m_skyParams.upperMidT;
            skyPC.upperMidColor[0] = m_skyParams.upperMidColor[0]; skyPC.upperMidColor[1] = m_skyParams.upperMidColor[1];
            skyPC.upperMidColor[2] = m_skyParams.upperMidColor[2]; skyPC.upperMidColor[3] = m_skyParams.lowerMidT;
            skyPC.lowerMidColor[0] = m_skyParams.lowerMidColor[0]; skyPC.lowerMidColor[1] = m_skyParams.lowerMidColor[1];
            skyPC.lowerMidColor[2] = m_skyParams.lowerMidColor[2]; skyPC.lowerMidColor[3] = m_skyParams.strength;
            skyPC.bottomColor[0] = m_skyParams.bottomColor[0]; skyPC.bottomColor[1] = m_skyParams.bottomColor[1];
            skyPC.bottomColor[2] = m_skyParams.bottomColor[2]; skyPC.bottomColor[3] = 1.0f;
            vkCmdPushConstants(cmd, m_skyPipelineLayout,
                               VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                               0, sizeof(skyPC), &skyPC);
        } else {
            // 回退主管线：仅投影（顶点色三段渐变，无平滑/无抖动）
            vkCmdPushConstants(cmd, m_pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,
                               0, sizeof(m_screenOrtho), m_screenOrtho);
        }

        VkBuffer vertexBuffers[] = { m_vertexBuffers[m_currentFrame] };
        VkDeviceSize offsets[] = { 0 };
        vkCmdBindVertexBuffers(cmd, 0, 1, vertexBuffers, offsets);

        if (m_whiteTexture.descSet != VK_NULL_HANDLE) {
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    m_pipelineLayout, 0, 1, &m_whiteTexture.descSet,
                                    0, nullptr);
        }
        vkCmdDraw(cmd, m_backgroundVertexCount, 1, 0, 0);
    }

    // 有绘制内容时绑定管线并提交 draw calls，空帧则仅清除颜色缓冲
    if (!m_pendingDraws.empty()) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_pipeline);

        // 设置投影矩阵（相机矩阵——世界空间绘制）
        vkCmdPushConstants(cmd, m_pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,
                           0, sizeof(m_projMatrix), m_projMatrix);

        VkBuffer vertexBuffers[] = { m_vertexBuffers[m_currentFrame] };
        VkDeviceSize offsets[] = { 0 };
        vkCmdBindVertexBuffers(cmd, 0, 1, vertexBuffers, offsets);

        // 提交所有 pending draw calls，按纹理 ID 切换独立描述符集。
        // 仅 vkCmdBindDescriptorSets 切换（set 在 upload/
        //   setTextureQuality 时已分配+更新）——command buffer 记录期间
        //   vkUpdateDescriptorSets 改写共享集违反规范，会导致
        //   放置模式全屏白（普通模式单纹理不触发切换故无感）
        uint32_t currentBoundTexId = UINT32_MAX;
        VkDescriptorSet currentSet = VK_NULL_HANDLE;
        for (auto& draw : m_pendingDraws) {
            if (draw.count <= 0) continue;

            // 纹理切换：仅切换预分配的独立描述符集
            if (draw.textureId != currentBoundTexId) {
                currentBoundTexId = draw.textureId;
                VkDescriptorSet target = VK_NULL_HANDLE;
                if (draw.textureId == 0) {
                    target = m_whiteTexture.descSet;
                } else {
                    // ★ 短临界区（m_gpuMutex）：m_textures 查表——上传线程可能正在
                    //   push_back（vector 扩容重分配），无锁遍历读到悬垂内存即
                    //   "查表失败回退白纹 → Tile 纯色单帧"根因。查到 target 后
                    //   立即释放（描述符集句柄本身不受 push_back 影响）。
                    {
                        std::lock_guard<std::mutex> gpuLock(m_gpuMutex);
                        for (const auto& tex : m_textures) {
                            if (tex.id == draw.textureId) {
                                target = tex.descSet;
                                break;
                            }
                        }
                    }
                    if (target == VK_NULL_HANDLE) {
                        // 纹理未找到/描述符集未就绪时回退到白色纹理
                        target = m_whiteTexture.descSet;
                        currentBoundTexId = 0;  // 下次遇到 ID≠0 会重新查找
                    }
                }
                if (target != VK_NULL_HANDLE && target != currentSet) {
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                            m_pipelineLayout, 0, 1, &target,
                                            0, nullptr);
                    currentSet = target;
                }
            }

            // 直接使用 VBO 中已有的数据（已在 draw() 中写入；offset 已含背景段偏移）
            vkCmdDraw(cmd, draw.count, 1, draw.vertexOffset, 0);
        }
    }

    vkCmdEndRenderPass(cmd);

    // render scale 离屏模式：offscreen → swapchain 硬件双线性上采样 blit。
    // 屏障序列：offscreen（renderPass 结束态）→ TRANSFER_SRC；swapchain acquire 后
    // 布局为 UNDEFINED → TRANSFER_DST → blit → PRESENT_SRC。
    // 直渲模式（renderScale=1.0）零改动：renderPass finalLayout 直接 PRESENT_SRC。
    if (offscreen) {
        const uint32_t swapIdx = imageIndex;

        VkImageMemoryBarrier offscreenToBlit{};
        offscreenToBlit.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        offscreenToBlit.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        offscreenToBlit.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        // ★ 离屏 renderPass finalLayout 即 TRANSFER_SRC_OPTIMAL——此处仅做 access
        //   同步（layout 不转换；PRESENT_SRC 对非交换链图像非法，见 createOffscreenRenderPass）
        offscreenToBlit.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        offscreenToBlit.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        offscreenToBlit.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        offscreenToBlit.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        offscreenToBlit.image = m_offscreenImages[m_currentFrame];
        offscreenToBlit.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        offscreenToBlit.subresourceRange.levelCount = 1;
        offscreenToBlit.subresourceRange.layerCount = 1;

        VkImageMemoryBarrier swapToBlitDst{};
        swapToBlitDst.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        swapToBlitDst.srcAccessMask = 0;  // UNDEFINED 布局：丢弃旧内容
        swapToBlitDst.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        swapToBlitDst.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        swapToBlitDst.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        swapToBlitDst.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        swapToBlitDst.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        swapToBlitDst.image = m_swapchainImages[swapIdx];
        swapToBlitDst.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        swapToBlitDst.subresourceRange.levelCount = 1;
        swapToBlitDst.subresourceRange.layerCount = 1;

        VkImageMemoryBarrier barriers[2] = { offscreenToBlit, swapToBlitDst };
        vkCmdPipelineBarrier(cmd,
                             VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 0, nullptr, 2, barriers);

        VkImageBlit blitRegion{};
        blitRegion.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blitRegion.srcSubresource.layerCount = 1;
        blitRegion.srcOffsets[0] = { 0, 0, 0 };
        blitRegion.srcOffsets[1] = {
            (int32_t)m_offscreenExtent.width, (int32_t)m_offscreenExtent.height, 1 };
        blitRegion.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blitRegion.dstSubresource.layerCount = 1;
        blitRegion.dstOffsets[0] = { 0, 0, 0 };
        blitRegion.dstOffsets[1] = {
            (int32_t)m_swapchainExtent.width, (int32_t)m_swapchainExtent.height, 1 };

        vkCmdBlitImage(cmd,
                       m_offscreenImages[m_currentFrame], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       m_swapchainImages[swapIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                       1, &blitRegion, VK_FILTER_LINEAR);

        VkImageMemoryBarrier swapToPresent{};
        swapToPresent.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        swapToPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        swapToPresent.dstAccessMask = 0;
        swapToPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        swapToPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        swapToPresent.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        swapToPresent.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        swapToPresent.image = m_swapchainImages[swapIdx];
        swapToPresent.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        swapToPresent.subresourceRange.levelCount = 1;
        swapToPresent.subresourceRange.layerCount = 1;

        vkCmdPipelineBarrier(cmd,
                             VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &swapToPresent);
    }

    vkEndCommandBuffer(cmd);

    // 提交 GPU
    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.waitSemaphoreCount = 1;
    submitInfo.pWaitSemaphores = &m_imageAvailable[m_currentFrame];
    submitInfo.pWaitDstStageMask = &waitStage;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmd;
    submitInfo.signalSemaphoreCount = 1;
    submitInfo.pSignalSemaphores = &m_renderFinished[m_currentFrame];

    // ★ 持锁提交（m_gpuMutex）：vkQueueSubmit/vkQueuePresentKHR 与主线程纹理上传
    //   （submitOneTimeCommands）并发使用同一 m_graphicsQueue——VkQueue 为
    //   externally-synchronized 对象，双线程并发提交违反规范可产生单帧损坏。
    //   与查表段是两个独立临界区（不嵌套），无死锁风险；vkQueueSubmit 为异步
    //   入队（非 fence 等待），持锁时长微秒级。
    {
        std::lock_guard<std::mutex> gpuLock(m_gpuMutex);
        vkQueueSubmit(m_graphicsQueue, 1, &submitInfo, m_inFlightFences[m_currentFrame]);

        // Present
        VkPresentInfoKHR presentInfo{};
        presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &m_renderFinished[m_currentFrame];
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &m_swapchain;
        presentInfo.pImageIndices = &imageIndex;

        vkQueuePresentKHR(m_presentQueue, &presentInfo);
    }

    m_currentFrame = (m_currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
    m_frameCounter++;   // 退役纹理老化基准
}
