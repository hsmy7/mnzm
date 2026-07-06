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

    // === 资源管理 ===
    void destroySwapchain();
    void destroyPipelineObjects();

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

    VkBuffer m_vertexBuffer = VK_NULL_HANDLE;
    VkDeviceMemory m_vertexMemory = VK_NULL_HANDLE;
    void* m_vertexMapped = nullptr;
    VkDeviceSize m_vertexBufferSize = MAX_VERTICES * sizeof(SpriteVertex);

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

    // 着色器
    VkShaderModule m_vertShader = VK_NULL_HANDLE;
    VkShaderModule m_fragShader = VK_NULL_HANDLE;

    // 渲染配置
    RenderConfig m_config{};
    float m_projMatrix[16]{};

    // 帧绘制状态
    struct DrawCommand {
        const SpriteVertex* vertices;
        int count;
        uint32_t textureId;
    };
    std::vector<DrawCommand> m_pendingDraws;

    ANativeWindow* m_nativeWindow = nullptr;
    bool m_ready = false;
};
