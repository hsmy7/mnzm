#pragma once

// 确保 Android 平台扩展可用（需在 vulkan.h 前定义）
#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR 1
#endif

#include "Rhi.h"
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <vector>
#include <array>

// ============================================================
// VulkanBackend — Vulkan 1.1+ 2D 渲染后端（Rhi.h 接口的现有实现；
// Metal/iOS 实现同接口接入，接入指南见 Rhi.h 头注释）
// 架构：
//   - 单 Pipeline（固定功能，无状态切换）
//   - 单 DescriptorSet（single texture atlas）
//   - 单 VBO（持久映射，每帧 memcpy）
//   - 三重缓冲交换链
//   - 构建时一次性分配所有 Vulkan 对象，运行时不创建/销毁
// ============================================================

class VulkanBackend final : public Renderer2D {
public:
    VulkanBackend() = default;
    ~VulkanBackend() override { shutdown(); }

    /** 最后一次成功读取的 Vulkan 驱动版本号（0 = 未知/未初始化） */
    static volatile int s_driverVersion;

    /** 最后一次成功读取的 Vulkan API 版本（VK_MAKE_VERSION 编码；0 = 未知） */
    static volatile int s_apiVersion;

    /** 最后一次成功读取的 GPU vendorID（0 = 未知/未初始化） */
    static volatile int s_vendorId;

    /** 最后一次成功读取的 GPU 设备名（"" = 未知；非 volatile——JNI 跨 init 边界读取已同步） */
    static char s_deviceName[256];

    // Renderer2D 接口实现
    bool init(const RenderConfig& config, void* nativeWindow) override;
    void shutdown() override;
    bool resize(int width, int height) override;
    void beginFrame() override;
    void endFrame() override;
    bool isReady() const override { return m_ready; }
    uint32_t uploadTexture(const void* pixels, int width, int height) override;
    void destroyTexture(uint32_t id) override;

    // === WP7 ASTC 压缩纹理（非虚——仅 Vulkan 路径使用，NativeBridge 经 dynamic_cast 调用） ===

    /**
     * 上传 ASTC 4x4 LDR 压缩纹理（KTX 数据区，已由 KtxLoader 校验；B.1 支持多 mip）。
     * 设备不支持 textureCompressionASTC_LDR、数据尺寸与块数不符、宽高非 4 倍数
     * 均返回 0（调用方回退 RGBA 路径）。staging 上传模式与 uploadTexture 相同。
     *
     * @param data KTX1 数据区（header 后，含逐级 [size4] 前缀）
     * @param dataSize 数据区总字节（含各级 size4 前缀）
     * @param width mip0 宽
     * @param height mip0 高
     * @param mipCount mip 层级数（>=1；1 = 单 mip，与 B.1 前行为一致）
     */
    uint32_t uploadCompressedTexture(const uint8_t* data, size_t dataSize,
                                     int width, int height, int mipCount);
    /**
     * 上传 REPEAT 采样地面纹理（宗门地图单一无缝地面整图铺）。
     * 与 uploadTexture 同 staging 上传，仅采样器地址模式为 REPEAT（UV 可超 [0,1] 循环平铺）。
     */
    uint32_t uploadRepeatTexture(const void* pixels, int width, int height);

    /**
     * 运行时纹理采样质量开关（B.1 + 自选清晰度联动；渲染线程调用）。
     * 按 ClarityMode 更新图集采样器：
     *  - mipmap：minFilter=LINEAR + mipmapMode=LINEAR（三线性 mip；图集多 mip 已带）；
     *  - anisotropy：仅当设备支持 samplerAnisotropy 特性时启用（否则强制关闭各向异性）。
     * 重建图集纹理采样器后重绑定描述符集（下帧生效）。白纹理采样器保持 NEAREST 单 mip 不变。
     *
     * @param anisotropyMax 最大各向异性（0 = 关闭；命名 X2/X4/X8 对应 2.0/4.0/8.0）
     * @param mipmap 是否启用 mipmap 三线性过滤
     */
    void setTextureQuality(float anisotropyMax, bool mipmap);
    void setProjection(const float mat[16]) override;
    void draw(const SpriteVertex* vertices, int count, uint32_t textureId) override;
    void submitFrame() override;

    // === 两阶段初始化（主流游戏做法） ===

    /** Phase 1: 仅创建设备和着色器（在加载界面阶段调用，无 Surface 依赖） */
    bool initDevice(const char* cacheDir, int worldW, int worldH, int tileSize);

    /** Phase 2: 创建 Surface/Swapchain/Pipeline（在 SurfaceView 就绪后调用） */
    bool initSurface(void* nativeWindow, int viewportW, int viewportH);

    /** 设备是否已初始化（供 NativeBridge 判断是否需要回退到完整 init） */
    bool isDeviceReady() const { return m_deviceReady; }

    // === render scale 离屏降采样渲染（平板/大屏省电，2026-08-14） ===

    /**
     * 设置渲染缩放（0.5–1.0；NaN/越界消毒为安全值）。渲染线程调用——内部
     * 重建离屏渲染目标（vkDeviceWaitIdle + 资源重建，语义同 resize）。
     * 设备不支持 blit 时强制 1.0（直渲路径）。
     *
     * @return 实际生效的渲染缩放值
     */
    float setRenderScale(float scale);

private:
    // === 初始化辅助 ===
    bool createInstance();
    bool selectPhysicalDevice();
    bool createLogicalDevice();
    bool createSwapchain(int width, int height);
    bool createRenderPass();
    /** 离屏渲染 pass：attachment finalLayout = TRANSFER_SRC_OPTIMAL（供 blit 读取；PRESENT_SRC 仅对交换链图像合法） */
    bool createOffscreenRenderPass();
    bool createPipeline();
    bool createVertexBuffer();
    bool createCommandObjects();
    bool createSynchronization();
    bool loadShaders();
    bool createFramebuffers();  // 必须在 createRenderPass() 之后调用
    VkShaderModule compileShader(const uint32_t* code, size_t size);

    // === render scale 离屏目标 ===
    bool createOffscreenTargets();   // 必须在 createRenderPass() + createFramebuffers() 之后
    void destroyOffscreenTargets();

    // === Pipeline Cache 持久化 ===
    static constexpr const char* PIPELINE_CACHE_FILENAME = "vulkan_pipeline_cache.bin";
    bool loadPipelineCache();
    bool savePipelineCache();

    // === 资源管理 ===
    void destroySwapchain();
    void destroyGraphicsObjects();   // 仅销毁 Pipeline/RenderPass/Layout（保留 ShaderModule）
    void destroyShaderModules();     // 仅销毁 Shader Module
    void destroyPipelineObjects();   // 销毁所有图形对象（含 Shader）— 仅供 shutdown

    // === Vulkan 对象 ===
    VkInstance m_instance = VK_NULL_HANDLE;
    VkPhysicalDevice m_physDevice = VK_NULL_HANDLE;
    VkDevice m_device = VK_NULL_HANDLE;
    VkQueue m_graphicsQueue = VK_NULL_HANDLE;
    VkQueue m_presentQueue = VK_NULL_HANDLE;
    uint32_t m_graphicsQueueIndex = UINT32_MAX;

    VkSurfaceKHR m_surface = VK_NULL_HANDLE;
    VkSwapchainKHR m_swapchain = VK_NULL_HANDLE;
    VkFormat m_swapchainFormat = VK_FORMAT_R8G8B8A8_UNORM;
    VkColorSpaceKHR m_swapchainColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    VkExtent2D m_swapchainExtent{};
    std::vector<VkImage> m_swapchainImages;
    std::vector<VkImageView> m_swapchainViews;
    std::vector<VkFramebuffer> m_framebuffers;

    VkRenderPass m_renderPass = VK_NULL_HANDLE;
    /** 离屏渲染 pass（finalLayout=TRANSFER_SRC_OPTIMAL；与主 pass 附件描述一致，管线兼容） */
    VkRenderPass m_offscreenRenderPass = VK_NULL_HANDLE;
    VkPipelineLayout m_pipelineLayout = VK_NULL_HANDLE;
    VkPipeline m_pipeline = VK_NULL_HANDLE;

    // 同步对象（三重缓冲）
    static constexpr int MAX_FRAMES_IN_FLIGHT = 3;

    // ★ VBO 三缓冲（2026-09 骁龙 8 Gen 2 放置模式白屏根因修复）：与
    //   MAX_FRAMES_IN_FLIGHT 对齐、按 m_currentFrame 索引——此前仅双缓冲，
    //   帧 N+2 写入 buffer A 时 GPU 帧 N 仍在读 buffer A（fence 只保护 3 帧前）
    //   → 顶点数据撕裂 → 放置模式高频渲染时画面随机白屏
    VkBuffer m_vertexBuffers[MAX_FRAMES_IN_FLIGHT] = { VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE };
    VkDeviceMemory m_vertexMemories[MAX_FRAMES_IN_FLIGHT] = { VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE };
    void* m_vertexMapped[MAX_FRAMES_IN_FLIGHT] = { nullptr, nullptr, nullptr };
    VkDeviceSize m_vertexBufferSize = MAX_VERTICES * sizeof(SpriteVertex) * 2;

    VkCommandPool m_commandPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> m_commandBuffers;  // per swapchain image

    std::vector<VkSemaphore> m_imageAvailable;
    std::vector<VkSemaphore> m_renderFinished;
    std::vector<VkFence> m_inFlightFences;
    int m_currentFrame = 0;

    // 纹理管理
    struct Texture {
        VkImage image = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkSampler sampler = VK_NULL_HANDLE;
        int width = 0, height = 0;
        uint32_t id = 1;  // 纹理 ID（1+ 为上传纹理，0 为白色纹理）
        /** 采样器地址模式（上传时确定——地面 REPEAT/图集 CLAMP；setTextureQuality 重建采样器须保留原模式） */
        VkSamplerAddressMode addressMode = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        /** 独立描述符集（★ 2026-09 根因修复：上传时分配+更新，submitFrame 仅 bind——
         *  此前在 command buffer 记录期间多次 vkUpdateDescriptorSets 共享集（规范非法，
         *  骁龙 8 Gen 2 放置模式白屏根因）） */
        VkDescriptorSet descSet = VK_NULL_HANDLE;
    };
    std::vector<Texture> m_textures;
    uint32_t m_atlasTextureId = 0;  // 主图集纹理
    Texture m_whiteTexture{};       // 1×1 白色纹理（用于纯色矩形绘制）

    // 描述符
    VkDescriptorPool m_descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSetLayout m_descriptorSetLayout = VK_NULL_HANDLE;

    // 为纹理分配/更新独立描述符集（upload/setTextureQuality/重建时调用；
    // 非 command buffer 记录期间调用——submitFrame 仅 bind，见 Texture.descSet）
    void updateTextureDescriptor(Texture& tex);

    // 纹理上传共享实现（CLAMP/REPEAT 由 addressMode 参数化；uploadTexture/uploadRepeatTexture 复用）
    uint32_t uploadTextureImpl(const void* pixels, int width, int height,
                               VkSamplerAddressMode addressMode);

    // 创建 1×1 白色纹理（供纯色矩形绘制），在 init 中调用
    bool createWhiteTexture();

    // 确保 staging buffer 有足够大小
    bool ensureStagingBuffer(size_t requiredSize);

    // 着色器
    VkShaderModule m_vertShader = VK_NULL_HANDLE;
    VkShaderModule m_fragShader = VK_NULL_HANDLE;

    // Pipeline Cache（加速管线创建，跨会话持久化）
    VkPipelineCache m_pipelineCache = VK_NULL_HANDLE;
    char m_cacheDir[256] = {};          // 应用缓存目录（用于保存 Pipeline Cache）
    bool m_deviceReady = false;         // initDevice 是否已完成

    // 渲染配置
    RenderConfig m_config{};
    float m_projMatrix[16]{};

    /** 设备是否支持 ASTC LDR 压缩纹理（createLogicalDevice 记录，WP7） */
    bool m_astcSupported = false;

    // === B.1 纹理采样质量（mipmap + 各向异性；setTextureQuality 运行时更新） ===
    bool m_anisoSupported = false;   // 设备是否支持采样器各向异性（createLogicalDevice 记录）
    float m_anisotropyMax = 2.0f;    // 最大各向异性倍率（0 = 关闭；ClarityMode 默认中 X2）
    bool m_mipmapEnabled = true;     // 是否启用 mipmap 三线性过滤（ClarityMode 默认中=开）

    /**
     * 按当前采样质量（m_anisotropyMax/m_mipmapEnabled）+ 地址模式创建采样器。
     * 供 uploadCompressedTexture / uploadTextureImpl / setTextureQuality 复用，双端一致。
     */
    bool createSampler(VkSampler& out, VkSamplerAddressMode addressMode);

    // 帧绘制状态
    struct DrawCommand {
        uint32_t vertexOffset;  // VBO 中的顶点偏移（单位：顶点数）
        int count;              // 顶点数
        uint32_t textureId;     // 纹理 ID
    };
    std::vector<DrawCommand> m_pendingDraws;

    // VBO 双缓冲偏移
    int m_vboOffset = 0;                            // 当前帧 VBO 写入位置（字节偏移）

    // Staging buffer（用于 OPTIMAL tiling 纹理上传）
    VkBuffer m_stagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory m_stagingMemory = VK_NULL_HANDLE;
    size_t m_stagingBufferSize = 0;

    ANativeWindow* m_nativeWindow = nullptr;
    bool m_ready = false;

    // === render scale 离屏降采样渲染状态 ===
    float m_renderScale = 1.0f;                              // 当前渲染缩放（1.0 = 直渲）
    bool m_usingOffscreen = false;                           // 是否启用离屏渲染目标
    bool m_blitSupported = true;                             // 交换链格式是否支持 BLIT_DST（不支持自动回退直渲）
    VkExtent2D m_offscreenExtent{};                          // 离屏目标尺寸 = round(swapchain × renderScale)
    VkImage m_offscreenImages[MAX_FRAMES_IN_FLIGHT] = { VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE };
    VkImageView m_offscreenViews[MAX_FRAMES_IN_FLIGHT] = { VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE };
    VkDeviceMemory m_offscreenMemories[MAX_FRAMES_IN_FLIGHT] = { VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE };
    VkFramebuffer m_offscreenFramebuffers[MAX_FRAMES_IN_FLIGHT] = { VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE };
};
