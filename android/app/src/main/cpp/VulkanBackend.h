#pragma once

// 确保 Android 平台扩展可用（需在 vulkan.h 前定义）
#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR 1
#endif

#include "Rhi.h"
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <atomic>
#include <vector>
#include <array>
#include <mutex>

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
    RenderInitError lastInitError() const override { return m_lastInitError; }
    void beginFrame() override;
    void endFrame() override;
    bool isReady() const override { return m_ready; }
    uint32_t uploadTexture(const void* pixels, int width, int height) override;
    void destroyTexture(uint32_t id) override;

    // === ASTC 压缩纹理（非虚——仅 Vulkan 路径使用，NativeBridge 经 dynamic_cast 调用） ===

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
    uint32_t uploadRepeatTexture(const void* pixels, int width, int height) override;

    /**
     * 上传 RGBA **mip 链**纹理（2.3：RGBA 回退路径真 mip；非 Vulkan 后端不经此路径）。
     *
     * @param pixels level-major 紧凑像素（首级 = 完整 width×height，后续各级 50% 等比，
     *               尺寸 max(1, base>>level)；RGBA8 每级 4 字节/像素，bufferOffset
     *               逐级累积恒 4 字节对齐，满足 VUID-VkBufferImageCopy 对齐约束）
     * @param mipCount mip 层级数（含首级；2048² 图集 = 11 级，2048→2）
     * @return 纹理 ID；0 = 校验失败/上传失败（调用方单级回退 uploadTexture）
     */
    uint32_t uploadMipChainTexture(const void* pixels, int width, int height, int mipCount);

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
    void drawBackground(const SpriteVertex* vertices, int count, const SkyGradientParams& params) override;
    void submitFrame() override;

    // === 两阶段初始化（主流游戏做法） ===

    /** Phase 1: 仅创建设备和着色器（在加载界面阶段调用，无 Surface 依赖） */
    bool initDevice(const char* cacheDir, int worldW, int worldH, int tileSize);

    /** Phase 2: 创建 Surface/Swapchain/Pipeline（在 SurfaceView 就绪后调用） */
    bool initSurface(void* nativeWindow, int viewportW, int viewportH);

    /** 设备是否已初始化（供 NativeBridge 判断是否需要回退到完整 init） */
    bool isDeviceReady() const { return m_deviceReady; }

    // === render scale 离屏降采样渲染（平板/大屏省电） ===

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
    /** 创建 VkSurfaceKHR（m_surface 已存在则直接复用——resize/清晰度切换不重建
     *  surface，destroySurfaceGeneration 持有 surface 的唯一销毁权） */
    bool ensureSurface();
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
    /**
     * Surface 纪元资源析构（幂等，可重复调用）：m_ready 置 false + vkDeviceWaitIdle
     * 后销毁 pipeline/offscreen/swapchain/framebuffer/renderPass/VBO/commandPool/
     * 同步对象/白纹/m_textures/m_retiredTextures/descriptorPool/VkSurfaceKHR/
     * ANativeWindow 引用。保留 device 级资源（instance/device/shaderModules/
     * pipelineCache/staging）。initSurface 入口与 shutdown 共用——任何
     * 「上一代未释放」路径（skip-release 纪元/迟到的 init 成功）走到这里都不泄漏
     * （幂等不变量）。
     */
    void destroySurfaceGeneration();
    void destroySwapchain();
    void destroyGraphicsObjects();   // 仅销毁 Pipeline/RenderPass/Layout（保留 ShaderModule）
    void destroyShaderModules();     // 仅销毁 Shader Module

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
    /** SkyBackground 天空管线 layout：mat4 proj + 三段颜色/位置/强度（128B，VERTEX|FRAGMENT push constant） */
    VkPipelineLayout m_skyPipelineLayout = VK_NULL_HANDLE;
    VkPipeline m_pipeline = VK_NULL_HANDLE;
    /** SkyBackground 屏幕空间渐变管线（sky.vert + sky.frag：分段 smoothstep 解析渐变；创建失败则回退主管线） */
    VkPipeline m_skyPipeline = VK_NULL_HANDLE;

    // 同步对象（三重缓冲）
    static constexpr int MAX_FRAMES_IN_FLIGHT = 3;

    // ★ VBO 三缓冲：与
    //   MAX_FRAMES_IN_FLIGHT 对齐、按 m_currentFrame 索引——双缓冲下，
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
        /** 独立描述符集（上传时分配+更新，submitFrame 仅 bind——
         *  command buffer 记录期间多次 vkUpdateDescriptorSets 共享集违反规范，
         *  会导致放置模式白屏） */
        VkDescriptorSet descSet = VK_NULL_HANDLE;
    };
    std::vector<Texture> m_textures;
    /**
     * GPU 全局互斥（防 Tile 短暂纯色/白纹回退）。
     * m_textures 是无锁 vector：上传（Kotlin 主线程经 JNI push_back，扩容重分配）
     * 与渲染线程 submitFrame 遍历并发时渲染线程读到悬垂内存 → 查表失败回退白纹
     * → 部分/全部 Tile 单帧纯色（下一帧竞态消失即"恢复正常"）。同时 VkQueue/
     * VkCommandPool 为 externally-synchronized 对象——主线程上传提交
     * （submitOneTimeCommands）与渲染线程帧提交（submitFrame）并发使用同一
     * m_graphicsQueue 违反规范，可产生单帧损坏。本锁序列化：
     * uploadTextureImpl / uploadCompressedTexture 全程、submitFrame 纹理查表段
     * 与队列提交段（两个独立临界区，不嵌套）、setTextureQuality、resize、
     * setRenderScale、destroyTexture（入队）。beginFrame/endFrame/draw 仅写映射
     * VBO 与 m_pendingDraws，不触碰共享表，不参与本锁（避免每帧无谓串行化）。
     */
    mutable std::mutex m_gpuMutex;

    // ── 纹理延迟释放（destroyTexture 空实现 → 契约补齐）──
    /** 退役纹理（可能正被在途帧采样——不能立即销毁，等 fence 确认后释放） */
    struct RetiredTexture { uint32_t id; uint64_t retiredAtFrame; };
    std::vector<RetiredTexture> m_retiredTextures;   // GUARDED_BY(m_gpuMutex)
    /** 帧计数（submitFrame 末尾递增；退役纹理在超过 MAX_FRAMES_IN_FLIGHT 帧后释放） */
    uint64_t m_frameCounter = 0;

    /** 上传连续超时计数（疑似 device lost 熔断；成功清零，≥3 → m_ready=false 停止
     *  提交——安全黑屏而非无限冻结，surface 重建/应用重启恢复）。GUARDED_BY(m_gpuMutex) */
    int m_uploadTimeoutCount = 0;

    /** 上传结果熔断记录（uploadTextureImpl/uploadCompressedTexture 持锁内调用） */
    void noteUploadResult(bool ok);
    /** 释放退役纹理的 Vulkan 资源（view/image/memory/sampler + m_textures erase）。
     *  调用方须已确认在途采样结束（submitFrame fence 之后）并持 m_gpuMutex。 */
    void freeTextureResources(uint32_t id);

    uint32_t m_atlasTextureId = 0;  // 主图集纹理
    Texture m_whiteTexture{};       // 1×1 白色纹理（用于纯色矩形绘制）

    // 描述符
    VkDescriptorPool m_descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSetLayout m_descriptorSetLayout = VK_NULL_HANDLE;

    // 为纹理分配/更新独立描述符集（upload/setTextureQuality/重建时调用；
    // 非 command buffer 记录期间调用——submitFrame 仅 bind，见 Texture.descSet）
    void updateTextureDescriptor(Texture& tex);

    /** 描述符池容量超限重建：按需扩容新建池并重分配白纹+全部
     *  注册纹理的 descSet。调用契约：upload 路径（已持 m_gpuMutex）——内部
     *  先 m_ready 落闸 + vkDeviceWaitIdle 排空在途帧再释放旧池（descSet
     *  随池销毁释放，必须无在途引用）。 */
    void rebuildDescriptorPool(uint32_t minSets);

    // 纹理上传共享实现（CLAMP/REPEAT 由 addressMode 参数化；uploadTexture/uploadRepeatTexture 复用）。
    // mipLevels > 1 时走"逐级从 staging 拷贝"分支（2.3 RGBA mip 链；pixels 为 level-major 紧凑布局）
    uint32_t uploadTextureImpl(const void* pixels, int width, int height,
                               VkSamplerAddressMode addressMode, int mipLevels = 1);

    // 创建 1×1 白色纹理（供纯色矩形绘制），在 init 中调用
    bool createWhiteTexture();

    // 确保 staging buffer 有足够大小
    bool ensureStagingBuffer(size_t requiredSize);

    // 着色器
    VkShaderModule m_vertShader = VK_NULL_HANDLE;
    VkShaderModule m_fragShader = VK_NULL_HANDLE;
    /** SkyBackground 屏幕空间渐变顶点着色器（sky.vert：归一化屏幕→NDC，输出 inUV.v） */
    VkShaderModule m_skyVertShader = VK_NULL_HANDLE;
    /** SkyBackground 屏幕空间渐变片元着色器（sky.frag：分段 smoothstep 解析渐变 + 有序抖动） */
    VkShaderModule m_skyFragShader = VK_NULL_HANDLE;

    // Pipeline Cache（加速管线创建，跨会话持久化）
    VkPipelineCache m_pipelineCache = VK_NULL_HANDLE;
    char m_cacheDir[256] = {};          // 应用缓存目录（用于保存 Pipeline Cache）
    /** initDevice 是否已完成。std::atomic：JNI init 线程写、prewarm/渲染线程读，
     *  非原子读写是数据竞争（m_ready/m_deviceReady 全面原子化） */
    std::atomic<bool> m_deviceReady{false};

    // 渲染配置
    RenderConfig m_config{};
    float m_projMatrix[16]{};

    // === SkyBackground 屏幕空间背景（2026 天幕组件） ===
    // 屏幕正交投影（归一化屏幕坐标 → Vulkan NDC，Y 向下：y=0 顶 → -1，y=1 底 → +1）。
    // 常量矩阵，不随相机矩阵/分辨率/旋转变化——Camera 平移缩放不影响背景。
    float m_screenOrtho[16]{};
    // 本帧屏幕背景顶点数（drawBackground 写入 VBO 头部 offset=0；submitFrame 最先绘制）
    int m_backgroundVertexCount = 0;
    // 本帧天空渐变参数（drawBackground 传入；submitFrame 经 push-constant 推给 sky.frag）
    SkyGradientParams m_skyParams{};

    /** 设备是否支持 ASTC LDR 压缩纹理（createLogicalDevice 记录） */
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
    /** 渲染提交闸门。std::atomic：渲染线程 submitFrame 与 Kotlin 主线程生命周期
     *  调用（initSurface/resize/setRenderScale → shutdownRenderer）跨线程读写，
     *  非 bool 字段的读写竞争由 m_ready/m_deviceReady 全面原子化防护。submitFrame 的
     *  「wait fence / acquire / 提交前复查 m_ready」守卫依赖本值的可见性 */
    std::atomic<bool> m_ready{false};

    /** 最近一次初始化失败阶段（initRenderer 只回 boolean，失败点坍缩——
     *  本错误码经 lastInitError() 在对象 delete 前被 NativeBridge 收割上报） */
    RenderInitError m_lastInitError = RenderInitError::NONE;

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
