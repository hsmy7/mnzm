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
// ★ 2026-09 天空缩放：缩小看到整座浮空岛时整张地图（128×128 = 16384 格）可见，
//   单批 8192 会让后半地面瓦片被丢弃、岛屿残缺不全。提升到 20480 以容纳整岛地面
//   （地面 16384 + 建筑/作物/道路余量；装饰层在 scale<0.6 由 LOD 跳过，不占批）。
//   VBO 相应扩容（在 VulkanBackend 按 MAX_VERTICES 计算）。
static constexpr int MAX_SPRITES_PER_FRAME = 20480;
static constexpr int VERTICES_PER_SPRITE = 6;   // 两个三角形
static constexpr int MAX_VERTICES = MAX_SPRITES_PER_FRAME * VERTICES_PER_SPRITE;

// 批量渲染命令
struct DrawBatch {
    uint32_t textureId;         // 纹理 ID
    int vertexOffset;           // VBO 偏移（顶点数）
    int vertexCount;            // 顶点数量
};

// ============================================================
// SkyBackground 渐变参数（片元解析渐变：四段颜色 + 两个内部停靠位置 + 强度）
// 由上层 SkyBackground 持有配置、每帧交给 Renderer2D::drawBackground；
// 后端用 push-constant/uniform 传给天空片元着色器逐像素平滑计算，对应着色器中的
// uTopColor / uUpperMidColor / uLowerMidColor / uBottomColor。
// 停靠点：topColor@0 → upperMidColor@upperMidT → lowerMidColor@lowerMidT → bottomColor@1。
// ============================================================
struct SkyGradientParams {
    float topColor[3];        // 顶部颜色（@0）
    float upperMidColor[3];   // 上中部（@upperMidT）
    float lowerMidColor[3];   // 下中部（@lowerMidT）
    float bottomColor[3];     // 底部颜色（@1）
    float upperMidT;          // 上中部停靠位置（0=顶, 1=底）
    float lowerMidT;          // 下中部停靠位置（0=顶, 1=底）
    float strength;           // 渐变强度（0=平铺为顶色, 1=全渐变）
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

    // === 屏幕空间渐变背景（SkyBackground，2026 天幕组件） ===
    // 绘制屏幕空间全屏背景渐变，作为本帧**最底图层**，位于所有世界空间绘制之下。
    //
    // 与相机的关系（硬性约束）：
    //   本绘制不使用 setProjection 注入的相机矩阵，而用后端内部维护的常量
    //   "屏幕正交矩阵"把 vertices（归一化屏幕坐标）映射到自身 NDC——
    //   因此 Camera 的平移、缩放**均不影响**背景（Screen Space / Background Layer）。
    //
    // 顶点约定（对上层统一，后端自行做 NDC 映射）：
    //   vertices 用**归一化屏幕坐标**：x ∈ [0,1]（左→右），y ∈ [0,1]（顶→底）。
    //   顶点色为三段渐变（作为天空片元管线创建失败时的**回退**）；主路径由 [params]
    //   经天空片元着色器逐像素平滑（分段 smoothstep）计算，消除顶点插值的中段折痕
    //   与 8-bit 色带（片元内有序抖动）。
    //
    // 每次帧首最多调用一次（由 NativeBridge.drawSky 触发）；后端将顶点缓冲在帧首
    // 复制进 VBO 头部并记录，submitFrame 时最先绘制。
    virtual void drawBackground(const SpriteVertex* vertices, int count,
                                const SkyGradientParams& params) = 0;
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
