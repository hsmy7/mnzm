#pragma once

// 确保 Android 平台扩展可用（需在 vulkan.h 前定义）
#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR 1
#endif

#include "Renderer2D.h"
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <vector>
#include <array>

// ============================================================
// VulkanBackend — Vulkan 1.1+ 2D 渲染后端
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

    // Renderer2D 接口实现
    bool init(const RenderConfig& config, void* nativeWindow) override;
    void shutdown() override;
    bool resize(int width, int height) override;
    void beginFrame() override;
    void endFrame() override;
    bool isReady() const override { return m_ready; }
    uint32_t uploadTexture(const void* pixels, int width, int height) override;
    void destroyTexture(uint32_t id) override;
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

private:
    // === 初始化辅助 ===
    bool createInstance();
    bool selectPhysicalDevice();
    bool createLogicalDevice();
    bool createSwapchain(int width, int height);
    bool createRenderPass();
    bool createPipeline();
    bool createVertexBuffer();
    bool createCommandObjects();
    bool createSynchronization();
    bool loadShaders();
    bool createFramebuffers();  // 必须在 createRenderPass() 之后调用
    VkShaderModule compileShader(const uint32_t* code, size_t size);

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
    VkPipelineLayout m_pipelineLayout = VK_NULL_HANDLE;
    VkPipeline m_pipeline = VK_NULL_HANDLE;

    // 双缓冲 VBO（交替写入，避免 GPU 读 CPU 写冲突）
    VkBuffer m_vertexBuffers[2] = { VK_NULL_HANDLE, VK_NULL_HANDLE };
    VkDeviceMemory m_vertexMemories[2] = { VK_NULL_HANDLE, VK_NULL_HANDLE };
    void* m_vertexMapped[2] = { nullptr, nullptr };
    VkDeviceSize m_vertexBufferSize = MAX_VERTICES * sizeof(SpriteVertex) * 2;

    VkCommandPool m_commandPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> m_commandBuffers;  // per swapchain image

    // 同步对象（三重缓冲）
    static constexpr int MAX_FRAMES_IN_FLIGHT = 3;
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
    };
    std::vector<Texture> m_textures;
    uint32_t m_atlasTextureId = 0;  // 主图集纹理
    Texture m_whiteTexture{};       // 1×1 白色纹理（用于纯色矩形绘制）

    // 描述符
    VkDescriptorPool m_descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSetLayout m_descriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorSet m_descriptorSet = VK_NULL_HANDLE;

    // 将描述符集指向指定的纹理（shader 中 binding=0 的 sampler2D）
    void bindTextureToDescriptor(const Texture& tex);

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

    // 帧绘制状态
    struct DrawCommand {
        uint32_t vertexOffset;  // VBO 中的顶点偏移（单位：顶点数）
        int count;              // 顶点数
        uint32_t textureId;     // 纹理 ID
    };
    std::vector<DrawCommand> m_pendingDraws;

    // VBO 双缓冲偏移
    int m_vboOffset = 0;                            // 当前帧 VBO 写入位置（字节偏移）
    int m_activeBuffer = 0;                         // 当前活动 VBO 索引

    // Staging buffer（用于 OPTIMAL tiling 纹理上传）
    VkBuffer m_stagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory m_stagingMemory = VK_NULL_HANDLE;
    size_t m_stagingBufferSize = 0;

    ANativeWindow* m_nativeWindow = nullptr;
    bool m_ready = false;
};
