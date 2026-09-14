#pragma once

#include <cstdint>
#include <cstddef>

// ============================================================
// 浮空岛崖壁布局合成器（渲染合成单一权威，地图边缘系统）
//
// 纯函数、零 Android 依赖、桌面 GTest 直接覆盖。给定地图尺寸与纹理尺寸表，
// 产出世界坐标绘制操作序列（含 UV）。双端渲染路径只消费操作序列：
//   - C++ Vulkan/GLES：NativeBridge.drawIslandCliffs（操作 → SpriteBatcher）
//   - Kotlin Canvas：SoftwareCanvasBackend.drawIslandCliffs（操作 → Bitmap 源矩形）
//
// ## 为何走独立纹理而非图集
// 崖壁是 7 张整块素材（最大 1180×3552），**超出 4096² 图集容量**；且 Vulkan
// 核心只保证 maxImageDimension2D ≥ 4096。故每个变体一张独立纹理：
//   - 纹理下标写进布局条目（texIdx）
//   - UV 逐条目携带（独立纹理各自归一化，且需承载镜像与裁剪）
//
// ## 素材语义（实测）
//   left_1/2/3  ：侧崖壁三变体——**岩左、草右**；右缘为草带（贴地图）
//   bottom_1/2  ：下崖壁两变体——草上、岩下；顶边为草带（贴地图）
//   corner_bl   ：左下角——内缘（右端）贴 x=0，内容自内缘向左展开，
//                 实体岩体集中在内缘向内 [~0.36, ~0.95] 宽（实测列覆盖率剖面）
//   corner_br   ：右下角——内缘（左端）贴 x=mapW，向左镜像同构
//
// ## 锚定约定（世界 Y 向下；地图地面 [0,mapW]×[0,mapH]）
//   左环：素材**右缘贴 x=0**（草带贴地图），顶边贴 y=0；沿 +y 依次拼接
//   右环：素材**左缘贴 x=mapW**（草带贴地图），水平镜像；沿 +y 依次拼接
//   下环：素材**顶边贴 y=mapH**（草带贴地图下边）；沿 +x 依次拼接
//   左下角：内缘贴 x=0、顶边贴 y=mapH
//   右下角：内缘贴 x=mapW、顶边贴 y=mapH
//
// 绘制序：左 → 右 → 下 → 角。**角块最后绘制**——覆盖两侧边环与下环在角区的
// 越界部分（构造性保证角部无缝隙、无错位；角区重叠由角块覆盖）。
//
// ## 下环与转角的水平衔接
// 转角贴角点后横向占据约一个地图宽（实测 1820/1828px ≈ 0.38×mapW@128格）；
// 下环在 `[bottomStartRatio, 1-bottomEndRatio]`（纹理宽比例，经 2·cornerW 折算为
// 像素）区间内铺装。默认 0.5 使下环起铺点落在转角实体岩体之后（实测转角
// 90% 不透明内容集中在内缘向内 0.36~0.93 宽），且角块最后绘制覆盖重叠 ——
// 该值只影响"重叠多少"，不影响"是否出现空隙"。
//
// ## 翻转（左右共用素材）
// 左右两侧素材在源文件中是**镜像对**，素材阶段已按「岩左草右」统一朝向并去重，
// 故右侧崖壁水平镜像复用左侧纹理：flags bit0 置位表示镜像，
// 渲染端把 u0/u1 视为 [min,max] 区间两端（**不依赖 u0<u1**）。
//
// ## 裁剪
// 末块越出地图范围时截断，并**按截断比例收缩 UV**（不拉伸素材）：
//   横截 → u1 = u0 + (u1-u0) × (截断宽/原宽)
//   纵截 → v1 = v0 + (v1-v0) × (截断高/原高)
// 截断部分恰为素材自身透明留白（实测：侧环末块截 40px、下环末块截 74px）。
//
// ## 变体确定性
//   hash(seed, pool, seq) % poolSize，且不与上一块重复（防相邻重复纹理）。
//   纯整数哈希，与引擎 RNG 分区无关（渲染数据不参与确定性命中回放对拍）。
//
// ## 纹理缺失降级
//   `textureMask` 表达「哪些纹理已成功上传」；引用未上传纹理的条目**不产出**
//   （而非画白/崩溃）——单张上传失败只丢对应侧/对应变体，其余照常显示。
//
// ## 与底座衔接
//   本合成器只产「草地 → 悬崖」三侧环；外缘线（左环 x = -纹理宽、
//   下环 y = mapH + 纹理高）由布局条目直接可读，底座系统无需耦合本文件。
// ============================================================
namespace gamecore::map {

/// 绘制池（顺序 = 产出顺序 = 绘制序）
enum class IslandCliffPool : int32_t {
    LEFT = 0,        ///< 左环（沿 +y 拼接，右缘贴 x=0）
    RIGHT = 1,       ///< 右环（沿 +y 拼接，左缘贴 x=mapW，水平镜像）
    BOTTOM = 2,      ///< 下环（沿 +x 拼接，顶边贴 y=mapH）
    CORNER_BL = 3,   ///< 左下角（单块，内缘贴 x=0）
    CORNER_BR = 4,   ///< 右下角（单块，内缘贴 x=mapW）
};

inline constexpr int32_t kIslandCliffPoolCount = 5;

/// 布局条目步长（扁平输出 [texIdx, x, y, w, h, u0, v0, u1, v1, flags]）
inline constexpr int32_t kIslandCliffStride = 10;

/// 条目 flags 位
inline constexpr int32_t kIslandCliffFlagMirrorX = 1;  ///< bit0：水平镜像（u0/u1 视作区间两端）

/// 崖壁合成器配置（Kotlin 侧由纹理尺寸表装配，单一数据源）
struct IslandCliffConfig {
    int32_t cols = 0;         ///< 地图列数（格）
    int32_t rows = 0;         ///< 地图行数（格）
    int32_t tileSize = 0;     ///< 单格像素（GameConfig.SectMap.TILE_SIZE=48）
    int32_t seed = 0;         ///< 变体种子（= 地图种子；确定性变体）

    int32_t textureCount = 0;                  ///< 纹理总数（生产 = 7）
    const float* textureW = nullptr;           ///< [textureCount] 每纹理宽（像素）
    const float* textureH = nullptr;           ///< [textureCount] 每纹理高（像素）

    /// 每池变体表（元素 = 纹理下标；-1 = 该池无可用纹理）
    ///   [0]=LEFT 变体  [1]=RIGHT 变体（通常与 LEFT 同表，靠镜像位区分）
    ///   [2]=BOTTOM 变体  [3]=CORNER_BL  [4]=CORNER_BR
    int32_t poolBase[kIslandCliffPoolCount] = {0, 0, 0, 0, 0};
    int32_t poolCountArr[kIslandCliffPoolCount] = {0, 0, 0, 0, 0};
    const int32_t* poolFlat = nullptr;         ///< 池平铺表（长度 = 各池数量之和）

    /// 左右/下环「岛面顶线」内缩（像素）：素材顶部有一段草沿（实测 46~67px），
    /// 贴合地图边时该段叠进地图。置 0 = 素材顶边直接贴地图边（当前口径——
    /// 草沿落在 3 格边界树环内，视觉可接受）。调大即把草沿线对齐地图边（观感微调点）。
    float topInset = 0.0f;

    /// 下环铺装区间（占**转角纹理宽**的比例，自各自内缘向内量）。
    /// 0.5 → 起铺点/终点落在地图角内 0.5×cornerW 处（实测转角实体岩体
    /// 集中在内缘向内 0.36~0.93 宽，故 0.5 落在实体岩体起始之后）。
    /// 只影响与转角的重叠量，不影响是否出现空隙（角块最后绘制覆盖重叠）。
    float bottomStartRatio = 0.5f;
    float bottomEndRatio = 0.5f;

    /// 纹理上传位掩码：bit i = 纹理 i 已成功上传。引用未上传纹理的条目被跳过。
    uint32_t textureMask = 0xFFFFFFFFu;

    int32_t mapW() const { return cols * tileSize; }
    int32_t mapH() const { return rows * tileSize; }

    /// 纹理 i 是否可用（下标越界 = 不可用；掩码只覆盖前 32 张）
    bool textureUsable(int32_t i) const {
        if (i < 0 || i >= textureCount) return false;
        if (i >= 32) return true;
        const uint32_t self = textureMask;
        return (self & (1u << static_cast<uint32_t>(i))) != 0u;
    }
};

/// 布局条目（字段序见 kIslandCliffStride）
struct IslandCliffPiece {
    int32_t texIdx;
    float x, y, w, h;      ///< 世界像素矩形
    float u0, v0, u1, v1;  ///< 归一化 UV（镜像时 u0>u1，消费端取 min/max）
    int32_t flags;
};

/// 布局输出上界（预分配容量）：每边最坏 = 最小纹理尺寸铺满 + 1，加两角与缓冲。
inline int32_t islandCliffMaxPieces(const IslandCliffConfig& cfg) {
    if (cfg.cols <= 0 || cfg.rows <= 0 || cfg.tileSize <= 0) return 0;
    auto minOf = [&](int32_t pool, bool wantWidth) -> float {
        const int32_t n = cfg.poolCountArr[pool];
        if (!cfg.textureW || !cfg.textureH || !cfg.poolFlat || n <= 0) return 1.0f;
        float v = 0.0f;
        for (int32_t i = 0; i < n; i++) {
            const int32_t base = cfg.poolBase[pool];
            if (base < 0) continue;
            const int32_t t = cfg.poolFlat[base + i];
            if (t < 0 || t >= cfg.textureCount) continue;
            const float sz = wantWidth ? cfg.textureW[t] : cfg.textureH[t];
            if (v == 0.0f || sz < v) v = sz;
        }
        return v > 0.0f ? v : 1.0f;
    };
    const float w = static_cast<float>(cfg.mapW());
    const float h = static_cast<float>(cfg.mapH());
    const int32_t leftCount = static_cast<int32_t>(h / minOf(0, false)) + 2;
    const int32_t rightCount = static_cast<int32_t>(h / minOf(1, false)) + 2;
    const int32_t bottomCount = static_cast<int32_t>(w / minOf(2, true)) + 2;
    return leftCount + rightCount + bottomCount + 2 /* 两角 */ + 4 /* 缓冲 */;
}

/// 变体确定性哈希（splitmix32 派生；与引擎 RNG 分区无关）
inline uint32_t islandCliffHash(uint32_t seed, uint32_t a, uint32_t b) {
    uint32_t h = seed * 0x9E3779B9u;
    h ^= (a + 0x85EBCA6Bu + (h << 6) + (h >> 2));
    h ^= (b + 0xC2B2AE35u + (h << 6) + (h >> 2));
    h ^= h >> 16;
    h *= 0x85EBCA6Bu;
    h ^= h >> 13;
    h *= 0xC2B2AE35u;
    h ^= h >> 16;
    return h;
}

/// 池内首张可用变体的纹理宽（用于把角宽比例折算为像素；无变体返回 0）
inline float poolFirstWidth(const IslandCliffConfig& cfg, int32_t pool) {
    const int32_t base = cfg.poolBase[pool];
    const int32_t cnt = cfg.poolCountArr[pool];
    if (!cfg.poolFlat || base < 0 || cnt <= 0) return 0.0f;
    const int32_t t = cfg.poolFlat[base];
    return (t >= 0 && t < cfg.textureCount) ? cfg.textureW[t] : 0.0f;
}

/// 汇编崖壁布局（追加写入 out；返回条目数，0 = 非法输入/全纹理不可用）。
/// 输出顺序（绘制序，双端一致）：左环 → 右环 → 下环 → 左下角 → 右下角。
inline int32_t computeIslandCliffLayout(const IslandCliffConfig& cfg,
                                        IslandCliffPiece* out,
                                        int32_t maxPieces) {
    if (out == nullptr || maxPieces <= 0) return 0;
    if (cfg.cols <= 0 || cfg.rows <= 0 || cfg.tileSize <= 0) return 0;
    if (cfg.textureW == nullptr || cfg.textureH == nullptr ||
        cfg.poolFlat == nullptr || cfg.textureCount <= 0) {
        return 0;
    }

    const float W = static_cast<float>(cfg.mapW());
    const float H = static_cast<float>(cfg.mapH());
    int32_t n = 0;

    auto wOf = [&](int32_t t) -> float {
        return (t >= 0 && t < cfg.textureCount) ? cfg.textureW[t] : 1.0f;
    };
    auto hOf = [&](int32_t t) -> float {
        return (t >= 0 && t < cfg.textureCount) ? cfg.textureH[t] : 1.0f;
    };

    /// 追加条目（不可用纹理跳过但继续布局；容量不足返回 false 终止）
    auto push = [&](int32_t tex, float x, float y, float w, float h,
                    float u0, float v0, float u1, float v1, int32_t flags) -> bool {
        if (!cfg.textureUsable(tex)) return true;
        if (n >= maxPieces) return false;
        out[n] = {tex, x, y, w, h, u0, v0, u1, v1, flags};
        n++;
        return true;
    };

    /// 变体选择：hash(seed, pool, seq) % count；与上一块相同则 +1（防相邻重复）
    auto poolPick = [&](int32_t pool, int32_t seq, int32_t& prevLocal) -> int32_t {
        const int32_t base = cfg.poolBase[pool];
        const int32_t cnt = cfg.poolCountArr[pool];
        if (cnt <= 0 || base < 0) return -1;
        uint32_t k = islandCliffHash(static_cast<uint32_t>(cfg.seed),
                                     static_cast<uint32_t>(pool),
                                     static_cast<uint32_t>(seq)) % static_cast<uint32_t>(cnt);
        if (static_cast<int32_t>(k) == prevLocal && cnt > 1) {
            k = (k + 1) % static_cast<uint32_t>(cnt);
        }
        prevLocal = static_cast<int32_t>(k);
        return cfg.poolFlat[base + static_cast<int32_t>(k)];
    };

    const int32_t leftPool = static_cast<int32_t>(IslandCliffPool::LEFT);
    const int32_t rightPool = static_cast<int32_t>(IslandCliffPool::RIGHT);
    const int32_t bottomPool = static_cast<int32_t>(IslandCliffPool::BOTTOM);
    const int32_t blPool = static_cast<int32_t>(IslandCliffPool::CORNER_BL);
    const int32_t brPool = static_cast<int32_t>(IslandCliffPool::CORNER_BR);

    // ── 左环：右缘贴 x=0，顶边贴 y=0，沿 +y 拼接；末块按 H 截断 ──
    {
        const float inset = cfg.topInset;
        float pos = 0.0f;
        int32_t seq = 0, prev = -1;
        while (pos < H) {
            const int32_t tex = poolPick(leftPool, seq++, prev);
            if (tex < 0) break;
            const float tw = wOf(tex), th = hOf(tex);
            float drawH = th;
            float v1 = 1.0f;
            if (pos + drawH > H) {           // 纵截：收缩 UV（不拉伸素材）
                drawH = H - pos;
                v1 = th > 0.0f ? (drawH / th) : 1.0f;
            }
            if (!push(tex, -tw, pos + inset, tw, drawH, 0.0f, 0.0f, 1.0f, v1, 0)) return n;
            pos += drawH;
        }
    }
    // ── 右环：左缘贴 x=mapW，顶边贴 y=0，水平镜像（u0>u1）──
    {
        const float inset = cfg.topInset;
        float pos = 0.0f;
        int32_t seq = 0, prev = -1;
        while (pos < H) {
            const int32_t tex = poolPick(rightPool, seq++, prev);
            if (tex < 0) break;
            const float tw = wOf(tex), th = hOf(tex);
            float drawH = th;
            float v1 = 1.0f;
            if (pos + drawH > H) {
                drawH = H - pos;
                v1 = th > 0.0f ? (drawH / th) : 1.0f;
            }
            if (!push(tex, W, pos + inset, tw, drawH, 1.0f, 0.0f, 0.0f, v1,
                      kIslandCliffFlagMirrorX)) {
                return n;
            }
            pos += drawH;
        }
    }
    // ── 下环：顶边贴 y=mapH，铺装区间自两角内缘按 cornerW 比例内缩；末块横截 ──
    {
        // 角纹理宽（两侧取各自池首张变体）——用于把比例折算为像素
        const float blW = poolFirstWidth(cfg, blPool);
        const float brW = poolFirstWidth(cfg, brPool);
        const float startX = blW * cfg.bottomStartRatio;
        const float endX = W - brW * cfg.bottomEndRatio;
        float pos = startX;
        int32_t seq = 0, prev = -1;
        while (pos < endX) {
            const int32_t tex = poolPick(bottomPool, seq++, prev);
            if (tex < 0) break;
            const float tw = wOf(tex), th = hOf(tex);
            float drawW = tw;
            float u1 = 1.0f;
            if (pos + drawW > endX) {        // 横截：收缩 UV
                drawW = endX - pos;
                u1 = tw > 0.0f ? (drawW / tw) : 1.0f;
            }
            if (!push(tex, pos, H, drawW, th, 0.0f, 0.0f, u1, 1.0f, 0)) return n;
            pos += drawW;
        }
    }
    // ── 两角（最后绘制，覆盖边环/下环在角区的越界部分）──
    {
        int32_t ignored = -1;
        const int32_t bl = poolPick(blPool, 0, ignored);
        ignored = -1;
        const int32_t br = poolPick(brPool, 0, ignored);
        if (bl >= 0) {
            const float tw = wOf(bl), th = hOf(bl);
            // 左下角内缘（素材右端）贴 x=0 → 素材向左展开
            if (!push(bl, -tw, H, tw, th, 0.0f, 0.0f, 1.0f, 1.0f, 0)) return n;
        }
        if (br >= 0) {
            const float tw = wOf(br), th = hOf(br);
            // 右下角内缘（素材左端）贴 x=mapW → 素材向右展开
            if (!push(br, W, H, tw, th, 0.0f, 0.0f, 1.0f, 1.0f, 0)) return n;
        }
    }
    return n;
}

}  // namespace gamecore::map
