#pragma once

#include <cstdint>

// ============================================================
// Renderer2D — 跨平台 2D 渲染抽象接口
// 单一实现原则：一个顶点格式、一个 Pipeline、一张纹理图集
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

// 每帧最大精灵数（对应 48×48 地图的可见区域）
static constexpr int MAX_SPRITES_PER_FRAME = 4096;
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
