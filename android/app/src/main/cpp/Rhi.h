#pragma once

#include <cstdint>

// ============================================================
// Rhi — Render Hardware Interface（渲染硬件抽象层）
//（计划 v2 阶段 6：Renderer2D → RHI 形式化；类名保留 Renderer2D，
//  Kotlin 侧平台契约 RenderBackend KDoc 同此对接说明）
//
// 层级规则：
//   上层（不得 include 任何图形 API 头）：NativeBridge（场景装配 +
//   gamecore 合成器消费）、SpriteBatcher——只面向本接口与顶点格式。
//   下层（RHI 实现）：VulkanBackend（现有）/ MetalBackend（iOS 预留）。
//
// 单一实现原则：一个顶点格式、一个 Pipeline、一张纹理图集。
// Metal 接入指南（iOS）：实现本接口全部纯虚函数，nativeWindow 传
// CAMetalLayer*，投影矩阵改用 Metal NDC（z∈[0,1]，Y 向上翻转由
// cameraProjMatrix 层适配）；swapchain 语义对应 submitFrame 内的
// present，纹理上传对应 uploadTexture（MTLTexture + MTKTextureLoader
// 语义对齐），其余上层代码（NativeBridge/SpriteBatcher/gamecore）
// 零改动即可运行——同一渲染循环直接复用。
// ============================================================

struct RenderConfig {
    int viewportW = 0;          // 视口宽度（像素）
    int viewportH = 0;          // 视口高度（像素）
    int worldWidth;             // 世界宽度（像素）
    int worldHeight;            // 世界高度（像素）
    int tileSize;               // 单格大小（像素）
    const char* atlasPath;      // 纹理图集路径
    float renderScale;          // 渲染分辨率缩放
};

// 顶点格式：位置(2) + UV(2) + 颜色(4) = 8 floats = 32 字节
struct alignas(4) SpriteVertex {
    float px, py;   // 世界坐标位置
    float u, v;     // 纹理 UV
    float r, g, b, a; // 顶点颜色
};

// 每帧最大精灵数（对应 48×48 地图的可见区域 + 放置模式网格线/预览——
// 2026-09 骁龙 8 Gen 2 实测放置模式瓦片+装饰+建筑+网格线超 4096 被丢弃
// 导致网格线缺失/空白区域，提升至 8192）
static constexpr int MAX_SPRITES_PER_FRAME = 8192;
static constexpr int VERTICES_PER_SPRITE = 6;   // 两个三角形
static constexpr int MAX_VERTICES = MAX_SPRITES_PER_FRAME * VERTICES_PER_SPRITE;

// 批量渲染命令
struct DrawBatch {
    uint32_t textureId;         // 纹理 ID
    int vertexOffset;           // VBO 偏移（顶点数）
    int vertexCount;            // 顶点数量
};

class Renderer2D {
public:
    virtual ~Renderer2D() = default;

    // === 生命周期 ===
    virtual bool init(const RenderConfig& config, void* nativeWindow) = 0;
    virtual void shutdown() = 0;
    virtual bool resize(int width, int height) = 0;

    // === 帧控制 ===
    virtual void beginFrame() = 0;
    virtual void endFrame() = 0;
    virtual bool isReady() const = 0;

    // === 纹理 ===
    virtual uint32_t uploadTexture(const void* pixels, int width, int height) = 0;
    virtual void destroyTexture(uint32_t id) = 0;

    // === 渲染 ===
    virtual void setProjection(const float mat[16]) = 0;
    virtual void draw(const SpriteVertex* vertices, int count, uint32_t textureId) = 0;
    virtual void submitFrame() = 0;
};

// ============================================================
// 数学工具（内联，零依赖）
// ============================================================

// 构建正交投影矩阵（Vulkan NDC: -1~1, Y-down）
inline void orthoProj(float mat[16], float left, float right,
                      float bottom, float top) {
    // 列主序矩阵
    mat[0]  = 2.0f / (right - left);
    mat[1]  = 0.0f;
    mat[2]  = 0.0f;
    mat[3]  = 0.0f;
    mat[4]  = 0.0f;
    mat[5]  = 2.0f / (bottom - top);
    mat[6]  = 0.0f;
    mat[7]  = 0.0f;
    mat[8]  = 0.0f;
    mat[9]  = 0.0f;
    mat[10] = 1.0f;
    mat[11] = 0.0f;
    mat[12] = -(right + left) / (right - left);
    mat[13] = -(bottom + top) / (bottom - top);
    mat[14] = 0.0f;
    mat[15] = 1.0f;
}

// 从相机参数构建投影矩阵
inline void cameraProjMatrix(float mat[16], float camX, float camY,
                             float scale, float vpW, float vpH) {
    float left   = camX;
    float right  = camX + vpW / scale;
    float top    = camY;                    // camY 是视口上沿（较小 Y 值）
    float bottom = camY + vpH / scale;      // camY+vpH/scale 是视口下沿（较大 Y 值）
    // Vulkan NDC: Y 向下，-1=屏幕顶部，+1=屏幕底部
    // 传入 orthoProj 的 bottom > top，使得 Y_world=top → NDC=-1，Y_world=bottom → NDC=+1
    orthoProj(mat, left, right, bottom, top);
}
