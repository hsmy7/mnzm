#pragma once

#include "Rhi.h"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>
#include <vector>
#include <atomic>

// ============================================================
// GlesBackend — Android GPU OpenGL ES 中间渲染层（Rhi.h 接口的第二实现）
//
// 背景（2026-09 渲染路径结构性修正）：行业降级链均为 Vulkan→GPU GLES→
// 软件渲染(仅兜底)，而本项目此前为 Vulkan→CPU Canvas 且关闭系统硬件加速，
// 缺失行业标配的 GPU GLES 中间层。本后端补齐该层，使 Android 降级链变为
//   Vulkan → GPU GLES → CPU Canvas。
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
    void beginFrame() override;
    void endFrame() override;
    bool isReady() const override { return m_ready.load(); }
    uint32_t uploadTexture(const void* pixels, int width, int height) override;
    void destroyTexture(uint32_t id) override;
    void setProjection(const float mat[16]) override;
    void draw(const SpriteVertex* vertices, int count, uint32_t textureId) override;
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

    // 纹理管理
    std::vector<Tex> m_textures;
    uint32_t m_nextTexId = 1;

    // ★ 2026-09 线程模型修复：待上传队列（uploadTexture 在任意线程入队，
    //   submitFrame 在渲染线程持上下文后真实执行 GL 上传）
    struct PendingUpload {
        uint32_t id;
        int width;
        int height;
        std::vector<uint8_t> pixels;
    };
    std::vector<PendingUpload> m_pendingUploads;

    // 帧绘制状态（CPU 侧暂存，submitFrame 整批上传）
    struct DrawCommand { int vertexOffset; int count; uint32_t textureId; };
    std::vector<DrawCommand> m_pendingDraws;
    std::vector<SpriteVertex> m_vertexBuffer;

    // 配置 / 投影 / 视口
    RenderConfig m_config{};
    float m_projMatrix[16]{};
    int m_viewportW = 0;
    int m_viewportH = 0;
};
