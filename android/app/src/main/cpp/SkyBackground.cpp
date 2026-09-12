#include "SkyBackground.h"
#include <algorithm>
#include <cfloat>

namespace {

/** 钳制到 [0,1] */
inline float clamp01(float v) {
    return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
}

/** 线性插值：out = a*(1-t) + b*t */
inline void lerp3(float out[3], const float a[3], const float b[3], float t) {
    out[0] = a[0] + (b[0] - a[0]) * t;
    out[1] = a[1] + (b[1] - a[1]) * t;
    out[2] = a[2] + (b[2] - a[2]) * t;
}

/**
 * 单像素行颜色（四段渐变：top@0 → upperMid@t1 → lowerMid@t2 → bottom@1，
 * 分段线性 + 强度向顶部色混合）。t ∈ [0,1]，0=顶 1=底。
 */
void gradientColor(float t, const float top[3], const float upperMid[3],
                   const float lowerMid[3], const float bottom[3],
                   float t1, float t2, float strength, float out[3]) {
    float seg[3];
    if (t <= t1) {
        const float f = (t1 > 1e-6f) ? clamp01(t / t1) : 0.0f;
        lerp3(seg, top, upperMid, f);
    } else if (t <= t2) {
        const float span = t2 - t1;
        const float f = (span > 1e-6f) ? clamp01((t - t1) / span) : 0.0f;
        lerp3(seg, upperMid, lowerMid, f);
    } else {
        const float span = 1.0f - t2;
        const float f = (span > 1e-6f) ? clamp01((t - t2) / span) : 0.0f;
        lerp3(seg, lowerMid, bottom, f);
    }
    // 强度：向顶部色混合（strength=0 → 平铺顶色；=1 → 全渐变）
    lerp3(out, top, seg, strength);
}

}  // namespace

SkyBackground::SkyBackground() {
    resetToDefault();
}

void SkyBackground::setTopColor(float r, float g, float b) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_topColor[0] = clamp01(r);
    m_topColor[1] = clamp01(g);
    m_topColor[2] = clamp01(b);
    setDirty();
}

void SkyBackground::setUpperMidColor(float r, float g, float b) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_upperMidColor[0] = clamp01(r);
    m_upperMidColor[1] = clamp01(g);
    m_upperMidColor[2] = clamp01(b);
    setDirty();
}

void SkyBackground::setLowerMidColor(float r, float g, float b) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_lowerMidColor[0] = clamp01(r);
    m_lowerMidColor[1] = clamp01(g);
    m_lowerMidColor[2] = clamp01(b);
    setDirty();
}

void SkyBackground::setBotColor(float r, float g, float b) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_bottomColor[0] = clamp01(r);
    m_bottomColor[1] = clamp01(g);
    m_bottomColor[2] = clamp01(b);
    setDirty();
}

void SkyBackground::setStops(float upperMidT, float lowerMidT) {
    std::lock_guard<std::mutex> lock(m_mutex);
    // 防御：非有限/越界 → 归一化回合法区间（NaN 比较恒 false 一并拦截）
    m_upperMidT = (upperMidT >= 0.0f && upperMidT <= 1.0f) ? upperMidT : 0.33f;
    m_lowerMidT = (lowerMidT >= 0.0f && lowerMidT <= 1.0f) ? lowerMidT : 0.66f;
    // 保证 upperMid < lowerMid（内部停靠点必须单调，否则渐变回退）
    if (m_upperMidT > m_lowerMidT) std::swap(m_upperMidT, m_lowerMidT);
    setDirty();
}

void SkyBackground::setStrength(float s) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_strength = clamp01(s);
    setDirty();
}

void SkyBackground::resetToDefault() {
    std::lock_guard<std::mutex> lock(m_mutex);
    // 默认四段天幕（已确定）：深蔚蓝 #4B9FD1 → #62B0D8 → #78C0DF → 淡蓝白 #A9DCE8
    m_topColor[0] = 0.294f; m_topColor[1] = 0.624f; m_topColor[2] = 0.820f;
    m_upperMidColor[0] = 0.384f; m_upperMidColor[1] = 0.690f; m_upperMidColor[2] = 0.847f;
    m_lowerMidColor[0] = 0.471f; m_lowerMidColor[1] = 0.753f; m_lowerMidColor[2] = 0.875f;
    m_bottomColor[0] = 0.663f; m_bottomColor[1] = 0.863f; m_bottomColor[2] = 0.910f;
    m_upperMidT = 0.33f;
    m_lowerMidT = 0.66f;
    m_strength = 1.0f;
    setDirty();
}

const SpriteVertex* SkyBackground::getScreenVertices(int& outCount) {
    if (m_dirty.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lock(m_mutex);
        rebuildLocked();
        m_dirty.store(false, std::memory_order_release);
    }
    outCount = m_vertCount;
    return m_verts;
}

const SkyGradientParams& SkyBackground::getGradientParams() {
    if (m_dirty.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lock(m_mutex);
        rebuildLocked();
        m_dirty.store(false, std::memory_order_release);
    }
    return m_params;
}

void SkyBackground::rebuildLocked() {
    // 断点行 y 值（归一化），含 0 与 1（覆盖全屏），去重并保持单调顺序
    float ys[4] = { 0.0f, m_upperMidT, m_lowerMidT, 1.0f };
    int n = 1;  // ys[0]
    for (int i = 1; i < 4; ++i) {
        const float y = ys[i];
        const float prev = ys[n - 1];
        if (y > prev + 1e-5f) ys[n++] = y;
    }

    int count = 0;
    for (int i = 0; i < n - 1; ++i) {
        const float ya = ys[i];
        const float yb = ys[i + 1];
        float ca[3], cb[3];
        gradientColor(ya, m_topColor, m_upperMidColor, m_lowerMidColor, m_bottomColor,
                      m_upperMidT, m_lowerMidT, m_strength, ca);
        gradientColor(yb, m_topColor, m_upperMidColor, m_lowerMidColor, m_bottomColor,
                      m_upperMidT, m_lowerMidT, m_strength, cb);

        SpriteVertex* v = &m_verts[count];
        // SpriteVertex = { px, py, u, v, r, g, b, a }；v(UV.Y) = 该行归一化 Y（0=顶,1=底），
        // 供 sky.vert 透传给 sky.frag 的 inUV.v 做纵向渐变（Screen Space）。
        v[0] = { 0.0f,  ya, 0.0f, ya, ca[0], ca[1], ca[2], 1.0f };
        v[1] = { 1.0f,  ya, 0.0f, ya, ca[0], ca[1], ca[2], 1.0f };
        v[2] = { 0.0f,  yb, 0.0f, yb, cb[0], cb[1], cb[2], 1.0f };
        v[3] = { 1.0f,  ya, 0.0f, ya, ca[0], ca[1], ca[2], 1.0f };
        v[4] = { 1.0f,  yb, 0.0f, yb, cb[0], cb[1], cb[2], 1.0f };
        v[5] = { 0.0f,  yb, 0.0f, yb, cb[0], cb[1], cb[2], 1.0f };
        count += 6;
    }
    m_vertCount = count;

    // 供天空片元着色器的渐变参数（主路径逐像素平滑计算）
    for (int i = 0; i < 3; ++i) {
        m_params.topColor[i] = m_topColor[i];
        m_params.upperMidColor[i] = m_upperMidColor[i];
        m_params.lowerMidColor[i] = m_lowerMidColor[i];
        m_params.bottomColor[i] = m_bottomColor[i];
    }
    m_params.upperMidT = m_upperMidT;
    m_params.lowerMidT = m_lowerMidT;
    m_params.strength = m_strength;
}

void SkyBackground::setDirty() {
    m_dirty.store(true, std::memory_order_release);
}
