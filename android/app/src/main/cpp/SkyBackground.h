#pragma once

#include "Rhi.h"
#include <atomic>
#include <mutex>

// ============================================================
// SkyBackground — 程序绘制天空渐变背景模块（C++，零图形 API 依赖）
//
// 职责：只做"可配置四段渐变（c0@0→c1@t1→c2@t2→c3@1）的颜色插值 + 屏幕空间四边形顶点装配"。
// 自包含、可复用，不接触任何渲染 API；由 NativeBridge 持有实例，渲染线程每帧经
// getScreenVertices 取顶点交给 Renderer2D::drawBackground 绘制（后端以常量屏幕正交
// 投影映射到 NDC，相机平移/缩放不影响背景）。
//
// 可配置参数（对应需求）：
//   SkyTopColor / SkyUpperMidColor / SkyLowerMidColor / SkyBottomColor
//                                                    → setTopColor/setUpperMidColor/setLowerMidColor/setBotColor
//   渐变位置（两个内部归一化位置，0=屏幕顶，1=屏幕底）→ setStops(upperMidT, lowerMidT)
//   渐变强度（0=整面平铺为顶色，1=全渐变）             → setStrength(s)
//
// 扩展性（天气/时间系统，未来）：
//   晴天/傍晚/夜晚/阴天只需修改颜色/位置/强度参数，无需改动地图/建筑/Camera 渲染。
//   setXxx 任意时刻调用（Compose 线程），渲染线程下一帧自动生效，零重建地图。
//
// 线程模型：Compose 线程写（setXxx），RenderThread 读（getScreenVertices）。
//   用 std::mutex 保护配置 + std::atomic<bool> 脏标志：配置变化时才加锁重建顶点，
//   否则返回缓存（无锁、无分配）——满足"尽量避免每帧临时对象"。
// ============================================================

class SkyBackground {
public:
    SkyBackground();

    // ── 可配置参数（Compose 线程调用；原子/加锁保护，下一帧生效） ──
    void setTopColor(float r, float g, float b);       // topColor @ 0（顶部）
    void setUpperMidColor(float r, float g, float b);  // upperMidColor @ upperMidT
    void setLowerMidColor(float r, float g, float b);  // lowerMidColor @ lowerMidT
    void setBotColor(float r, float g, float b);       // bottomColor @ 1（底部）
    void setStops(float upperMidT, float lowerMidT);   // 两个内部停靠位置（0=顶, 1=底）
    void setStrength(float s);

    /** 恢复默认配置（surface 重建/降级链切换后，防代际残留——仿 shutdownRenderer 清理语义） */
    void resetToDefault();

    /** 配置是否已变化（render 线程读；变化后需重建顶点并调用 markDrawn） */
    bool isDirty() const { return m_dirty.load(std::memory_order_acquire); }

    /**
     * 取屏幕空间渐变 quad 顶点（归一化屏幕坐标，x∈[0,1] 左→右，y∈[0,1] 顶→底）。
     * 内部缓冲复用；仅配置变化时重建，否则返回缓存。帧首调用一次。
     * 顶点色为三段渐变——作为天空片元管线失败时的**回退**（主路径由 getGradientParams
     * 经天空片元着色器逐像素平滑计算）。
     *
     * @param outCount 输出顶点数（6 的倍数，最多 24）
     * @return 顶点数组（由本对象持有，调用方不得释放；下一帧仍有效）
     */
    const SpriteVertex* getScreenVertices(int& outCount);

    /** 取当前渐变参数（供 Renderer2D::drawBackground 推送到天空片元着色器）。配置变化时重建缓存 */
    const SkyGradientParams& getGradientParams();

    /** 标记已绘制（清除脏标志；drawSky 在成功绘制后调用） */
    void markDrawn() { m_dirty.store(false, std::memory_order_release); }

private:
    void rebuildLocked();
    void setDirty();

    // 配置（由 m_mutex 保护；仅重建时加锁读取）
    float m_topColor[3];        // 顶部颜色（@0）
    float m_upperMidColor[3];   // 上中部（@m_upperMidT）
    float m_lowerMidColor[3];   // 下中部（@m_lowerMidT）
    float m_bottomColor[3];     // 底部颜色（@1）
    float m_upperMidT;          // 上中部停靠位置（0=顶, 1=底）
    float m_lowerMidT;          // 下中部停靠位置（0=顶, 1=底）
    float m_strength;

    std::mutex m_mutex;
    std::atomic<bool> m_dirty{true};

    // 缓存的渐变参数（供片元着色器；配置变化时随顶点一起重建）
    SkyGradientParams m_params{};

    // 缓存顶点（复用固定缓冲——最多 5 个断点行 × 每子quad 6 顶点）
    SpriteVertex m_verts[4 * 6];
    int m_vertCount = 0;
};
