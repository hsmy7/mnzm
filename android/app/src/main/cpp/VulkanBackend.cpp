#include "VulkanBackend.h"
#include <android/native_window_jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <cstring>
#include <algorithm>
#include <vector>
#include <set>
#include <thread>
#include <chrono>

/** Vulkan 驱动版本缓存（由 initDevice 设置，供 JNI getVulkanDriverVersion 读取） */
volatile int VulkanBackend::s_driverVersion = 0;
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
    vkWaitForFences(m_device, 1, &fence, VK_TRUE, UINT64_MAX);
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

    m_config.viewportW = viewportW;
    m_config.viewportH = viewportH;

    // Surface / Swapchain
    m_nativeWindow = static_cast<ANativeWindow*>(nativeWindow);
    if (m_nativeWindow) ANativeWindow_acquire(m_nativeWindow);

    if (!createSwapchain(viewportW, viewportH)) { LOGE("initSurface: createSwapchain failed"); return false; }
    if (!createRenderPass()) { LOGE("initSurface: createRenderPass failed"); return false; }
    if (!createFramebuffers()) { LOGE("initSurface: createFramebuffers failed"); return false; }

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
        bindTextureToDescriptor(m_whiteTexture);
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
    LOGI("init(%dx%d, window=%p)", config.viewportW, config.viewportH, nativeWindow);

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

void VulkanBackend::shutdown() {
    if (m_device == VK_NULL_HANDLE) return;
    vkDeviceWaitIdle(m_device);

    // 关机前保存 Pipeline Cache（可能在 LoadingScreen 阶段创建，也可能刚刚创建）
    savePipelineCache();

    // 销毁 Pipeline Cache
    if (m_pipelineCache) {
        vkDestroyPipelineCache(m_device, m_pipelineCache, nullptr);
        m_pipelineCache = VK_NULL_HANDLE;
    }

    destroyPipelineObjects();
    destroySwapchain();

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

    if (m_descriptorPool) { vkDestroyDescriptorPool(m_device, m_descriptorPool, nullptr); m_descriptorPool = VK_NULL_HANDLE; }
    if (m_descriptorSetLayout) { vkDestroyDescriptorSetLayout(m_device, m_descriptorSetLayout, nullptr); m_descriptorSetLayout = VK_NULL_HANDLE; }

    // 清理双缓冲 VBO
    for (int i = 0; i < 2; i++) {
        if (m_vertexMapped[i]) {
            vkUnmapMemory(m_device, m_vertexMemories[i]);
            m_vertexMapped[i] = nullptr;
        }
        if (m_vertexBuffers[i]) { vkDestroyBuffer(m_device, m_vertexBuffers[i], nullptr); m_vertexBuffers[i] = VK_NULL_HANDLE; }
        if (m_vertexMemories[i]) { vkFreeMemory(m_device, m_vertexMemories[i], nullptr); m_vertexMemories[i] = VK_NULL_HANDLE; }
    }

    // 清理 staging buffer
    if (m_stagingBuffer) { vkDestroyBuffer(m_device, m_stagingBuffer, nullptr); m_stagingBuffer = VK_NULL_HANDLE; }
    if (m_stagingMemory) { vkFreeMemory(m_device, m_stagingMemory, nullptr); m_stagingMemory = VK_NULL_HANDLE; }

    for (auto& sem : m_imageAvailable) { if (sem) { vkDestroySemaphore(m_device, sem, nullptr); sem = VK_NULL_HANDLE; } }
    m_imageAvailable.clear();
    for (auto& sem : m_renderFinished) { if (sem) { vkDestroySemaphore(m_device, sem, nullptr); sem = VK_NULL_HANDLE; } }
    m_renderFinished.clear();
    for (auto& fence : m_inFlightFences) { if (fence) { vkDestroyFence(m_device, fence, nullptr); fence = VK_NULL_HANDLE; } }
    m_inFlightFences.clear();

    if (m_commandPool) { vkDestroyCommandPool(m_device, m_commandPool, nullptr); m_commandPool = VK_NULL_HANDLE; }

    // 主句柄置空 —— 确保二次 shutdown() 调用幂等安全
    if (m_surface) { vkDestroySurfaceKHR(m_instance, m_surface, nullptr); m_surface = VK_NULL_HANDLE; }
    if (m_device) { vkDestroyDevice(m_device, nullptr); m_device = VK_NULL_HANDLE; }
    if (m_instance) { vkDestroyInstance(m_instance, nullptr); m_instance = VK_NULL_HANDLE; }

    // 释放 ANativeWindow 引用
    if (m_nativeWindow) {
        ANativeWindow_release(m_nativeWindow);
        m_nativeWindow = nullptr;
    }

    m_ready = false;
    m_deviceReady = false;
    m_pendingDraws.clear();
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
        LOGE("vkCreateInstance failed: %d", res);
        return false;
    }
    return true;
}

bool VulkanBackend::selectPhysicalDevice() {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(m_instance, &count, nullptr);
    if (count == 0) { LOGE("No Vulkan devices"); return false; }

    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(m_instance, &count, devices.data());

    // 优先选独立 GPU，回退到第一个可用
    for (auto& dev : devices) {
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(dev, &props);

        // ── Vulkan API 版本安全检查 ──
        // 验证驱动程序版本 >= 1.1，排除 1.0 的不完整实现
        // 参考：Unity 内置最低规格（ARM Mali 要求 >= 1.0.61, 但 1.0 实现普遍不可靠）
        uint32_t apiMajor = VK_API_VERSION_MAJOR(props.apiVersion);
        uint32_t apiMinor = VK_API_VERSION_MINOR(props.apiVersion);
        s_driverVersion = static_cast<int>(props.driverVersion);
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
    features.samplerAnisotropy = VK_FALSE;
    features.textureCompressionASTC_LDR = supportedFeatures.textureCompressionASTC_LDR
        ? VK_TRUE : VK_FALSE;
    features.textureCompressionETC2 = supportedFeatures.textureCompressionETC2
        ? VK_TRUE : VK_FALSE;

    VkDeviceCreateInfo devInfo{};
    devInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    devInfo.queueCreateInfoCount = 1;
    devInfo.pQueueCreateInfos = &queueInfo;
    devInfo.enabledExtensionCount = 1;
    devInfo.ppEnabledExtensionNames = extensions;
    devInfo.pEnabledFeatures = &features;

    if (vkCreateDevice(m_physDevice, &devInfo, nullptr, &m_device) != VK_SUCCESS) {
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

bool VulkanBackend::createSwapchain(int width, int height) {
    VkAndroidSurfaceCreateInfoKHR surfInfo{};
    surfInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfInfo.window = m_nativeWindow;

    if (vkCreateAndroidSurfaceKHR(m_instance, &surfInfo, nullptr, &m_surface) != VK_SUCCESS) {
        LOGE("Failed to create Android surface");
        return false;
    }

    // 查询 surface 格式
    uint32_t fmtCount = 0;
    VkResult fmtRes = vkGetPhysicalDeviceSurfaceFormatsKHR(m_physDevice, m_surface, &fmtCount, nullptr);
    if (fmtRes != VK_SUCCESS || fmtCount == 0) {
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
    swapInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    swapInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    swapInfo.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    swapInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    swapInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;  // VSYNC 对齐
    swapInfo.clipped = VK_TRUE;

    VkResult scRes = vkCreateSwapchainKHR(m_device, &swapInfo, nullptr, &m_swapchain);
    if (scRes != VK_SUCCESS) {
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
            LOGE("Failed to create swapchain image view");
            return false;
        }
    }

    // ImageView 创建后不立即创建 Framebuffer —— 此时 m_renderPass 尚未初始化，
    // framebuffer 需在 createRenderPass() 之后通过 createFramebuffers() 创建。

    LOGI("Swapchain created: %dx%d, %d images, format=%d",
         m_swapchainExtent.width, m_swapchainExtent.height,
         imgCount, m_swapchainFormat);
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
        LOGE("createFramebuffers: no swapchain views or render pass not ready");
        return false;
    }
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
            LOGE("Failed to create framebuffer %u", i);
            return false;
        }
    }
    LOGI("Framebuffers created: %u (%dx%d)", imgCount,
         m_swapchainExtent.width, m_swapchainExtent.height);
    return true;
}

bool VulkanBackend::resize(int width, int height) {
    if (m_device == VK_NULL_HANDLE) return false;
    vkDeviceWaitIdle(m_device);

    destroySwapchain();
    destroyGraphicsObjects();  // 保留 ShaderModule（它们不依赖 Surface）

    m_config.viewportW = width;
    m_config.viewportH = height;

    if (!createSwapchain(width, height)) return false;
    if (!createRenderPass()) return false;
    if (!createFramebuffers()) return false;
    // 跳过 loadShaders() — ShaderModule 在 initDevice 时已创建，跨 resize 复用
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
        LOGE("Failed to create render pass");
        return false;
    }
    return true;
}

bool VulkanBackend::loadShaders() {
    // 从构建时生成的 C 头文件中加载 SPIR-V 字节码
    m_vertShader = compileShader(sprite_vert_spv, sprite_vert_spv_size);
    m_fragShader = compileShader(sprite_frag_spv, sprite_frag_spv_size);

    if (!m_vertShader || !m_fragShader) {
        LOGE("Failed to compile shaders");
        return false;
    }

    LOGI("Shaders loaded from embedded SPIR-V (vert=%zu, frag=%zu bytes)",
         sprite_vert_spv_size, sprite_frag_spv_size);
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
        LOGE("Failed to create pipeline layout");
        return false;
    }

    // 描述符池
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSize.descriptorCount = 1;

    VkDescriptorPoolCreateInfo dpInfo{};
    dpInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpInfo.maxSets = 1;
    dpInfo.poolSizeCount = 1;
    dpInfo.pPoolSizes = &poolSize;

    if (vkCreateDescriptorPool(m_device, &dpInfo, nullptr, &m_descriptorPool) != VK_SUCCESS) {
        LOGE("Failed to create descriptor pool");
        return false;
    }

    VkDescriptorSetAllocateInfo dsAlloc{};
    dsAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsAlloc.descriptorPool = m_descriptorPool;
    dsAlloc.descriptorSetCount = 1;
    dsAlloc.pSetLayouts = &m_descriptorSetLayout;

    if (vkAllocateDescriptorSets(m_device, &dsAlloc, &m_descriptorSet) != VK_SUCCESS) {
        LOGE("Failed to allocate descriptor set");
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

    // 视口
    VkViewport viewport{};
    viewport.x = 0.0f;
    viewport.y = 0.0f;
    viewport.width = (float)m_swapchainExtent.width;
    viewport.height = (float)m_swapchainExtent.height;
    viewport.minDepth = 0.0f;
    viewport.maxDepth = 1.0f;

    VkRect2D scissor{};
    scissor.offset = {0, 0};
    scissor.extent = m_swapchainExtent;

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
        LOGE("Failed to create graphics pipeline");
        return false;
    }

    LOGI("Pipeline created successfully");
    return true;
}

void VulkanBackend::bindTextureToDescriptor(const Texture& tex) {
    VkDescriptorImageInfo descImg{};
    descImg.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    descImg.imageView = tex.view;
    descImg.sampler = tex.sampler;

    VkWriteDescriptorSet write{};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = m_descriptorSet;
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &descImg;

    vkUpdateDescriptorSets(m_device, 1, &write, 0, nullptr);
}

void VulkanBackend::destroyGraphicsObjects() {
    // 仅销毁依赖 Surface 的图形对象，保留 ShaderModule 和 PipelineCache
    if (m_pipeline) vkDestroyPipeline(m_device, m_pipeline, nullptr);
    m_pipeline = VK_NULL_HANDLE;
    if (m_pipelineLayout) vkDestroyPipelineLayout(m_device, m_pipelineLayout, nullptr);
    m_pipelineLayout = VK_NULL_HANDLE;
    if (m_renderPass) vkDestroyRenderPass(m_device, m_renderPass, nullptr);
    m_renderPass = VK_NULL_HANDLE;
    if (m_descriptorSetLayout) vkDestroyDescriptorSetLayout(m_device, m_descriptorSetLayout, nullptr);
    m_descriptorSetLayout = VK_NULL_HANDLE;
}

void VulkanBackend::destroyShaderModules() {
    if (m_vertShader) vkDestroyShaderModule(m_device, m_vertShader, nullptr);
    m_vertShader = VK_NULL_HANDLE;
    if (m_fragShader) vkDestroyShaderModule(m_device, m_fragShader, nullptr);
    m_fragShader = VK_NULL_HANDLE;
}

void VulkanBackend::destroyPipelineObjects() {
    destroyGraphicsObjects();
    destroyShaderModules();
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
    // 创建双缓冲 VBO（交替写入，避免 GPU 读 CPU 写冲突）
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = m_vertexBufferSize / 2;  // 每个 buffer 为总大小的一半
    bufInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(m_physDevice, &memProps);

    for (int i = 0; i < 2; i++) {
        if (vkCreateBuffer(m_device, &bufInfo, nullptr, &m_vertexBuffers[i]) != VK_SUCCESS) {
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

        if (memType == UINT32_MAX) { LOGE("No suitable memory type for VBO %d", i); return false; }

        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memReq.size;
        allocInfo.memoryTypeIndex = memType;

        if (vkAllocateMemory(m_device, &allocInfo, nullptr, &m_vertexMemories[i]) != VK_SUCCESS) {
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

/** 提交一次性 command buffer（用于 staging upload + layout transition）并等待完成 */
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

    vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX);
    vkDestroyFence(device, fence, nullptr);
    vkFreeCommandBuffers(device, pool, 1, &cmd);
    return true;
}

uint32_t VulkanBackend::uploadTexture(const void* pixels, int width, int height) {
    if (!m_device || !pixels) return 0;

    Texture tex;
    tex.width = width;
    tex.height = height;

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(m_physDevice, &memProps);

    // ---- Step 1: 创建 OPTIMAL tiling 图像 ----
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imgInfo.extent = { (uint32_t)width, (uint32_t)height, 1 };
    imgInfo.mipLevels = 1;
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

    // ---- Step 2: 通过 staging buffer 上传像素数据 ----
    {
        size_t pixelDataSize = (size_t)width * height * 4;
        if (!ensureStagingBuffer(pixelDataSize)) goto fail;

        void* mapped;
        vkMapMemory(m_device, m_stagingMemory, 0, pixelDataSize, 0, &mapped);
        memcpy(mapped, pixels, pixelDataSize);
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

        // UNDEFINED → TRANSFER_DST_OPTIMAL
        VkImageMemoryBarrier preBarrier{};
        preBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        preBarrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        preBarrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        preBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        preBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        preBarrier.image = tex.image;
        preBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        preBarrier.subresourceRange.levelCount = 1;
        preBarrier.subresourceRange.layerCount = 1;
        preBarrier.srcAccessMask = 0;
        preBarrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &preBarrier);

        // Copy: staging buffer → image
        VkBufferImageCopy copyRegion{};
        copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.imageSubresource.layerCount = 1;
        copyRegion.imageExtent = { (uint32_t)width, (uint32_t)height, 1 };
        vkCmdCopyBufferToImage(cmd, m_stagingBuffer, tex.image,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                               1, &copyRegion);

        // TRANSFER_DST → SHADER_READ_ONLY_OPTIMAL
        VkImageMemoryBarrier postBarrier{};
        postBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        postBarrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        postBarrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        postBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        postBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        postBarrier.image = tex.image;
        postBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        postBarrier.subresourceRange.levelCount = 1;
        postBarrier.subresourceRange.layerCount = 1;
        postBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        postBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &postBarrier);

        if (!submitOneTimeCommands(m_device, m_commandPool, m_graphicsQueue, cmd)) {
            LOGE("Failed to submit texture upload commands"); goto fail;
        }
    }

    // ---- Step 4: ImageView ----
    {
        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = tex.image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.layerCount = 1;

        if (vkCreateImageView(m_device, &viewInfo, nullptr, &tex.view) != VK_SUCCESS) {
            LOGE("Failed to create texture view"); goto fail;
        }
    }

    // ---- Step 5: Sampler（CLAMP_TO_EDGE — 图集 UV 始终在 [0,1] 内） ----
    {
        VkSamplerCreateInfo sampInfo{};
        sampInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        sampInfo.magFilter = VK_FILTER_NEAREST;
        sampInfo.minFilter = VK_FILTER_NEAREST;
        sampInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sampInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sampInfo.anisotropyEnable = VK_FALSE;
        sampInfo.maxLod = 1.0f;

        if (vkCreateSampler(m_device, &sampInfo, nullptr, &tex.sampler) != VK_SUCCESS) {
            LOGE("Failed to create sampler"); goto fail;
        }
    }

    {
        uint32_t id = s_nextTextureId++;
        tex.id = id;
        m_textures.push_back(tex);
        LOGI("Texture %dx%d uploaded (id=%u, OPTIMAL)", width, height, id);
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

void VulkanBackend::destroyTexture(uint32_t id) {
    // 纹理在 shutdown 时统一清理
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

    // 直接写入当前活动的 VBO（不再存储裸指针）
    // 数据在当前帧的 beginFrame 之后到 submitFrame 之前写入
    size_t copySize = count * sizeof(SpriteVertex);
    if (m_vboOffset + (int)copySize > (int)(m_vertexBufferSize / 2)) {
        LOGE("VBO overflow: %d + %zu > %llu",
             m_vboOffset, copySize, (unsigned long long)(m_vertexBufferSize / 2));
        return;
    }

    memcpy((char*)m_vertexMapped[m_activeBuffer] + m_vboOffset,
           vertices, copySize);

    m_pendingDraws.push_back({
        (uint32_t)(m_vboOffset / sizeof(SpriteVertex)),
        count,
        textureId
    });
    m_vboOffset += (int)copySize;
}

void VulkanBackend::beginFrame() {
    m_pendingDraws.clear();
    // 双缓冲交替：切换 VBO 并重置偏移
    m_activeBuffer = (m_activeBuffer + 1) % 2;
    m_vboOffset = 0;
}

void VulkanBackend::endFrame() {
    // 空实现 — 所有工作在 submitFrame 中完成
}

void VulkanBackend::submitFrame() {
    if (!m_ready) return;

    // 等待前帧完成
    vkWaitForFences(m_device, 1, &m_inFlightFences[m_currentFrame],
                    VK_TRUE, UINT64_MAX);
    // ★ 守卫：等待 fence 期间 shutdown() 可能已将 m_ready 置 false 并开始销毁资源
    if (!m_ready) return;

    // 获取下一张 swapchain 图像
    uint32_t imageIndex;
    VkResult result = vkAcquireNextImageKHR(
        m_device, m_swapchain, UINT64_MAX,
        m_imageAvailable[m_currentFrame], VK_NULL_HANDLE, &imageIndex);
    // ★ 守卫：获取图像期间 surfaceDestroyed 可能已经触发，swapchain 可能已失效
    if (!m_ready) return;

    if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
        LOGI("Swapchain out of date, need resize");
        // ★ 守卫：out-of-date 发生在 shutdown 竞态中时，fence 可能已被销毁
        if (!m_ready) return;
        // 不重置 fence — fence 保持 signaled 状态，下一帧可正常等待。
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

    VkClearValue clearColor = { { { 0.95f, 0.93f, 0.89f, 1.0f } } }; // #F2EDE4

    VkRenderPassBeginInfo rpBegin{};
    rpBegin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpBegin.renderPass = m_renderPass;
    rpBegin.framebuffer = m_framebuffers[imageIndex];
    rpBegin.renderArea.offset = {0, 0};
    rpBegin.renderArea.extent = m_swapchainExtent;
    rpBegin.clearValueCount = 1;
    rpBegin.pClearValues = &clearColor;

    vkCmdBeginRenderPass(cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);

    // 有绘制内容时绑定管线并提交 draw calls，空帧则仅清除颜色缓冲
    if (!m_pendingDraws.empty()) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                m_pipelineLayout, 0, 1, &m_descriptorSet, 0, nullptr);

        // 设置投影矩阵
        vkCmdPushConstants(cmd, m_pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,
                           0, sizeof(m_projMatrix), m_projMatrix);

        VkBuffer vertexBuffers[] = { m_vertexBuffers[m_activeBuffer] };
        VkDeviceSize offsets[] = { 0 };
        vkCmdBindVertexBuffers(cmd, 0, 1, vertexBuffers, offsets);

        // 提交所有 pending draw calls，按纹理 ID 切换描述符集
        // 数据已在 draw() 调用时直接写入 VBO，此处只需提交 draw calls
        uint32_t currentBoundTexId = UINT32_MAX;
        for (auto& draw : m_pendingDraws) {
            if (draw.count <= 0) continue;

            // 纹理切换：找到对应纹理并更新描述符集
            if (draw.textureId != currentBoundTexId) {
                currentBoundTexId = draw.textureId;
                if (draw.textureId == 0) {
                    bindTextureToDescriptor(m_whiteTexture);
                } else {
                    bool found = false;
                    for (const auto& tex : m_textures) {
                        if (tex.id == draw.textureId) {
                            bindTextureToDescriptor(tex);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        // 纹理未找到时回退到白色纹理，避免描述符集指向错误数据
                        bindTextureToDescriptor(m_whiteTexture);
                        currentBoundTexId = 0;  // 下次遇到 ID≠0 会重新查找
                    }
                }
                // 重新绑定描述符集到 command buffer
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                        m_pipelineLayout, 0, 1, &m_descriptorSet,
                                        0, nullptr);
            }

            // 直接使用 VBO 中已有的数据（已在 draw() 中写入）
            vkCmdDraw(cmd, draw.count, 1, draw.vertexOffset, 0);
        }
    }

    vkCmdEndRenderPass(cmd);
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

    m_currentFrame = (m_currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
}
