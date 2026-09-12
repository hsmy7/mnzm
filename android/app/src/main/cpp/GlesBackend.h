#pragma once

#include "Rhi.h"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>
#include <mutex>
#include <vector>
#include <atomic>

// ============================================================
// GlesBackend — Android GPU OpenGL ES 中间渲染层（Rhi.h 接口的第二实现）
//
// 背景：Android 降级链为
//   Vulkan → GPU GLES → CPU Canvas（软件渲染仅兜底）。
// 本后端承载 GPU GLES 中间层。
//
// 架构（与 VulkanBackend 同构，仅图形 API 不同，最大化复用上层组合逻辑）：
//   - 单 Pipeline（固定功能）+ 单图集纹理 + 白色纹理（id=0，纯色矩形）
//   - 动态 VBO（每次 submitFrame 整批上传 + 按纹理分组 glDrawArrays）
//   - 两阶段初始化：init(config, nativeWindow) 内 EGL 建链 + 管线创建
//
// 与 VulkanBackend 的差异（这些 Vulkan 专属能力在 GLES 上不支持，NativeBridge
// 经 dynamic_cast 返回空/0 自动回退到通用路径，行为 = 特性关闭而非错误）：
//   - uploadCompressedTexture（ASTC/KTX）→ 不支持，走 RGBA 图集
//   - uploadRepeatTexture（地面无缝纹理）→ 不支持，回退逐格地面（g_groundTexId==0）
//   - setRenderScale（离屏降采样）→ 不支持，恒 1.0（直渲全分辨率）
//   - initDevice/initSurface 两阶段（prewarm）→ 不需要，GLES 在 init 内一次性建链
//
// 坐标约定（与 Vulkan 管道保持一致的可视结果）：
//   - 投影矩阵（cameraProjMatrix 产出的 Vulkan NDC 约定）在 setProjection 内做
//     Y 翻转（GLES NDC Y 向上），使世界 Y-down 正确映射。
//   - UV 在顶点着色器内做 V 翻转（GLES 纹理 V=0 为图像底行），匹配图集
//     top-left-origin 语义。
//   - 图集以 RGBA 经 glTexImage2D 上传（与 Vulkan 一致的目标像素布局）。
// ============================================================

class GlesBackend final : public Renderer2D {
public:
    GlesBackend() = default;
    ~GlesBackend() override { shutdown(); }

    // Renderer2D 接口实现
    bool init(const RenderConfig& config, void* nativeWindow) override;
    void shutdown() override;
    bool resize(int width, int height) override;
    RenderInitError lastInitError() const override { return m_lastInitError; }
    void beginFrame() override;
    void endFrame() override;
    bool isReady() const override { return m_ready.load(); }
    uint32_t uploadTexture(const void* pixels, int width, int height) override;
    void destroyTexture(uint32_t id) override;
    // Rhi.h 接口实现（drawBackground 见下）
    void setProjection(const float mat[16]) override;
    void draw(const SpriteVertex* vertices, int count, uint32_t textureId) override;
    void drawBackground(const SpriteVertex* vertices, int count, const SkyGradientParams& params) override;
    void submitFrame() override;

private:
    bool initEgl(void* nativeWindow);
    bool initPipeline();
    void destroyPipeline();

    /** 消费待上传队列（渲染线程 submitFrame 开头调用——GL 调用须在有上下文的线程） */
    void drainUploads();
    /** 把 EGL 上下文绑定到当前线程（跨线程迁移须先由原线程释放；渲染线程首次接管） */
    bool ensureContextCurrent();

    // 纹理 id → GL 纹理；id=0 恒为白色纹理
    struct Tex { GLuint gl = 0; uint32_t id = 0; };
    GLuint glFor(uint32_t id) const;

    std::atomic<bool> m_ready{false};

    /** 最近一次初始化失败阶段（经 lastInitError() 在对象 delete 前收割上报） */
    RenderInitError m_lastInitError = RenderInitError::NONE;

    // EGL
    EGLDisplay m_display = EGL_NO_DISPLAY;
    EGLSurface m_surface = EGL_NO_SURFACE;
    EGLContext m_context = EGL_NO_CONTEXT;
    ANativeWindow* m_window = nullptr;

    // GL 管线
    GLuint m_program = 0;
    GLint m_projLoc = -1;
    GLuint m_vbo = 0;
    GLuint m_whiteTex = 0;
    /** SkyBackground 屏幕空间渐变程序（sky.vert 复用 + kSkyFragSrc 解析渐变+抖动；0 = 回退主管线） */
    GLuint m_skyProgram = 0;
    GLint m_skyProjLoc = -1;
    GLint m_skyTopLoc = -1;
    GLint m_skyUpperMidLoc = -1;
    GLint m_skyLowerMidLoc = -1;
    GLint m_skyBottomLoc = -1;
    /** 本帧天空渐变参数（drawBackground 传入；submitFrame 经 uniform 推给 kSkyFragSrc） */
    SkyGradientParams m_skyParams{};

    // ── 跨线程状态契约（与 VulkanBackend::m_gpuMutex 同构，见 VulkanBackend.h
    //    m_gpuMutex 事故史注：历史「Tile 短暂纯色」即同类缺锁）──
    // 主线程（图集上传 JNI）与渲染线程（submitFrame/drainUploads）共享的可变状态
    // 必须经 m_stateMutex 访问；m_textures 为渲染线程独占（drainUploads 写 / glFor 读，
    // destroyTexture 仅入队不直接操作）。新增任何成员必须归类到三者之一：
    // m_stateMutex 保护 / 渲染线程独占 / 初始化期独占（init/shutdown 前渲染线程未运行）。
    std::mutex m_stateMutex;

    // 纹理管理
    std::vector<Tex> m_textures;      // 渲染线程独占（drainUploads 写 / glFor 读）
    uint32_t m_nextTexId = 1;         // GUARDED_BY(m_stateMutex)

    // 跨线程待上传队列（uploadTexture 在任意线程入队，
    //   submitFrame 在渲染线程持上下文后真实执行 GL 上传）
    struct PendingUpload {
        uint32_t id;
        int width;
        int height;
        std::vector<uint8_t> pixels;
    };
    std::vector<PendingUpload> m_pendingUploads;   // GUARDED_BY(m_stateMutex)
    /** 待删纹理队列（destroyTexture 任意线程入队；渲染线程 drainUploads 持上下文删除——
     *  无 EGL 上下文的线程上直接调 GL 会静默无效） */
    std::vector<uint32_t> m_pendingDestroys;       // GUARDED_BY(m_stateMutex)

    // 帧绘制状态（CPU 侧暂存，submitFrame 整批上传）
    struct DrawCommand { int vertexOffset; int count; uint32_t textureId; };
    std::vector<DrawCommand> m_pendingDraws;
    std::vector<SpriteVertex> m_vertexBuffer;

    // 配置 / 投影 / 视口
    RenderConfig m_config{};
    float m_projMatrix[16]{};
    int m_viewportW = 0;
    int m_viewportH = 0;

    // === SkyBackground 屏幕空间背景（2026 天幕组件） ===
    // 屏幕正交投影（归一化屏幕坐标 → GLES NDC，Y 向上：y=0 顶 → +1，y=1 底 → -1）。
    // 常量矩阵，不随相机矩阵/分辨率变化——Camera 平移缩放不影响背景。
    float m_screenOrtho[16]{};
    // 本帧屏幕背景顶点数（drawBackground 写入 m_vertexBuffer 头部；submitFrame 最先绘制）
    int m_backgroundVertexCount = 0;
};
