#pragma once

// ============================================================
// FloatTextPool — 浮动文字对象池 + 动画核心（重构方案 2026-09-17 R3.8/B13）
//
// **设计目标**（批次 R3.8-② 原文）：
//   - 固定容量池（常量显式命名），实例 = 世界空间锚点 + 词条/数字资产索引
//     + 颜色/样式档 + 出生时刻 + 动画参数；
//   - 池满**覆盖最旧**（循环缓冲语义，守卫锁定）；
//   - 动画（上浮 / 淡出 / 暴击弹跳）**全部 C++ 时间驱动、零每帧 JNI**；
//   - `shutdownRenderer` 后池纪元复位；
//   - 池满/溢出遥测接 R0.3 三计数器口径。
//
// **零每帧 JNI 的生成纪律**：本类不接受任何"每帧喂数据"的接口——唯一写入
// 入口是低频事件驱动的 `spawn()`（对应 JNI `sceneSpawnFloatingText`）；动画
// 推进只用调用方传入的时间标量（`advance(nowSeconds)`），不查系统时钟、
// 不跨线、不分配（全部固定数组）。
//
// **依赖边界**：本头零 Android 依赖（仅 <cstdint>/<cmath>/<cstring>），
// 桌面 GTest 可直测——与 scene_store.h / scene_draw.h 同层。
// 唯一例外是生成表 `scene_uv_tables.h`（纯生成常量，同样零 Android 依赖），
// 用于让**样式档数/索引**保持单一来源（见下 `static_assert`）。
//
// **与 Canvas 兜底的关系**：本池状态**不经** `RenderFrame` 契约、不进
// `SoftwareCanvasBackend`——Canvas 兜底零改动（浮字为 GPU 路径增强特性）。
// ============================================================

#include <cmath>
#include <cstdint>

#include "scene_uv_tables.h"   // kFloatStyleCount / kFloatStyle*（单一来源）

namespace scene {

// ── 容量与预算常量（显式命名；改容量须同步守卫与批次登记）──

/** 浮字池容量（实例数）。256 覆盖同屏最坏（群体战斗每单位一条 + 词条提示）。 */
inline constexpr int kFloatPoolCapacity = 256;

/** 池满覆盖时的"最旧"判定：单调递增的 spawn 序号（循环缓冲语义）。 */
inline constexpr int kFloatMaxHistory = kFloatPoolCapacity;

/**
 * 动画时长（秒）——上浮 + 淡出总时长；到期即回收（不占池位）。
 * 1.1s ≈ 移动端浮字观感惯例（够读、不拥堵）。
 */
inline constexpr float kFloatLifetimeSeconds = 1.1f;

/** 上浮速度（世界单位/秒；世界单位 = 格）。浮字在寿命内上升约 1.1 格。 */
inline constexpr float kFloatRisePerSecond = 1.0f;

/** 淡出起点（寿命归一化进度）：进度 < 此值保持不透明，之后线性淡出。 */
inline constexpr float kFloatFadeStart = 0.45f;

/** 暴击弹跳：起始放大倍数（出生瞬间放大后回落到 1.0）。 */
inline constexpr float kFloatCritBounceScale = 1.45f;

/** 暴击弹跳回落时长（秒）：出生后这段时间内缩放从 bounce 收到 1.0。 */
inline constexpr float kFloatCritBounceSeconds = 0.18f;

/** 浮字基准世界尺寸（格）：字形资产 224px 格 → 世界高度 0.5 格。 */
inline constexpr float kFloatBaseWorldHeight = 0.5f;

/** 词条/字形的世界宽高比基准（kFloatCellW / kFloatCellH，来自生成表）。 */
inline constexpr float kFloatCellAspect = 329.0f / 224.0f;

/** 非法输入防御：坐标绝对值上界（超出即判非法、不入池）。 */
inline constexpr float kFloatMaxAbsCoord = 1.0e7f;

/** 非法输入防御：时间标量合法区间（负值/NaN/超大值一律按 0 处理）。 */
inline constexpr float kFloatMaxTimeSeconds = 1.0e6f;

/**
 * 浮字样式档（与生成表 scene_uv_tables.h 的 kFloatStyle* 一一对应）。
 *
 * 颜色在这里而非生成表：字形素材是**白色**，运行期按档位整体染色——
 * 避免为每种颜色各烘焙一份字形（图集容量 + 一致性双重理由）。
 * 索引值必须与生成表常量同值（守卫锁定）。
 */
struct FloatStyle {
    float r;
    float g;
    float b;
    float a;
};

/** 样式档颜色表（索引 = kFloatStyle*；顺序即档位序） */
inline constexpr FloatStyle kFloatStyles[] = {
    {1.0f, 1.0f, 1.0f, 1.0f},      // 0 普通伤害（白）
    {1.0f, 0.843f, 0.0f, 1.0f},    // 1 暴击（金 #FFD700）
    {0.298f, 0.686f, 0.314f, 1.0f},// 2 治疗（绿 #4CAF50）
    {0.957f, 0.267f, 0.212f, 1.0f},// 3 警告/减益（红 #F44336）
};

// 档位数与档位索引**单一来源**是生成表 scene_uv_tables.h（`kFloatStyleCount` /
// `kFloatStyleNormal|Crit|Heal|Warn`）——本头不重复定义（重复定义会触发 ODR
// 冲突；单源也保证着色与索引永不漂移）。此处仅断言颜色表长度与生成表一致：
static_assert(
    sizeof(kFloatStyles) / sizeof(kFloatStyles[0]) == kFloatStyleCount,
    "kFloatStyles 长度与生成表 kFloatStyleCount 不一致——重跑 build-atlas.mjs --codegen");

/**
 * 单个浮字实例（POD，零堆分配——整池为固定数组）。
 *
 * 字段语义：
 *   - `worldX/worldY`  ：世界空间锚点（格坐标；渲染时经相机投影）
 *   - `assetIndex`     ：Tier1 资产索引（0..kFloatWordCount-1 词条 /
 *                        kFloatGlyphBaseIndex.. 单字形）——多字形数字串
 *                        由 `charCount` + 连续索引表达（0-9 在字形段内连续）
 *   - `styleIndex`     ：样式档（染色）
 *   - `spawnTime`      ：出生时刻（秒，调用方时基）
 *   - `spawnSeq`       ：单调递增序号（池满覆盖最旧的判据）
 *   - `scale`          ：附加缩放（暴击等事件可放大）
 *   - `riseScale`      ：上浮速度倍率（词条提示可放慢）
 *   - `bounce`         ：是否播放暴击弹跳
 *   - `alive`          ：活跃标记（显式，不靠哨兵值）
 */
struct FloatTextInstance {
    float worldX = 0.0f;
    float worldY = 0.0f;
    int32_t assetIndex = 0;
    int32_t charCount = 1;
    int32_t styleIndex = 0;
    float spawnTime = 0.0f;
    uint32_t spawnSeq = 0;
    float scale = 1.0f;
    float riseScale = 1.0f;
    bool bounce = false;
    bool alive = false;
};

/**
 * 池满/溢出遥测计数（沿 R0.3 三计数器口径——精灵溢出与之同一语义层）。
 *
 * `droppedTotal` = 池满被覆盖丢弃的浮字累计数；
 * `overflowFrames` = 发生过覆盖的帧数（`advance` 调用次数中的子集）；
 * `degradeFrames` = 降级生效帧数（浮字层整体跳过）。
 * 本池**恒定容量、不降级到丢弃之外**——`degradeFrames` 保留为 0
 * （`resetStats` 后可观测），与 R0.3 口径对齐但不虚构降级行为。
 */
struct FloatTextStats {
    int64_t droppedTotal = 0;
    int64_t overflowFrames = 0;
    int64_t degradeFrames = 0;
    int64_t spawnTotal = 0;
};

/**
 * 浮字对象池 + 动画核心（全 C++、零每帧 JNI）。
 *
 * 线程模型：与 SceneStore 同层——由渲染线程独占访问（JNI spawn 与 drawFrame
 * 同一线程），无锁、无原子（沿用既有渲染路径约束）。
 */
class FloatTextPool {
public:
    FloatTextPool() = default;

    /**
     * 生成一条浮字（**低频事件驱动**——对应 JNI `sceneSpawnFloatingText`）。
     *
     * 池满时**覆盖最旧**（`spawnSeq` 最小者），并计入溢出遥测。
     * 非法输入（非有限坐标 / 越界资产索引 / 越界样式档 / 非正字数）一律
     * **拒绝入池**（返回 false，不崩溃、不残留脏实例）。
     *
     * @param worldX/worldY 世界锚点（格）
     * @param assetIndex    Tier1 资产索引（0..kFloatAssetCount-1）
     * @param charCount     连续字形数（词条 = FLOAT_WORD_LENGTH，数字串 = 位数）
     * @param styleIndex    样式档（0..kFloatStyleCount-1）
     * @param nowSeconds    当前时基（秒）
     * @param assetCount    Tier1 资产总数（调用方从生成表传入，避免本头依赖生成表）
     * @param scale         附加缩放（缺省 1）
     * @param riseScale     上浮速度倍率（缺省 1）
     * @param bounce        是否播放暴击弹跳（缺省 false）
     * @returns 是否成功入池（false = 参数非法被拒）
     */
    bool spawn(float worldX, float worldY, int32_t assetIndex, int32_t charCount,
               int32_t styleIndex, float nowSeconds, int32_t assetCount,
               float scale = 1.0f, float riseScale = 1.0f, bool bounce = false) {
        if (!finiteCoord(worldX) || !finiteCoord(worldY)) return false;
        if (assetIndex < 0 || assetIndex >= assetCount) return false;
        if (styleIndex < 0 || styleIndex >= kFloatStyleCount) return false;
        if (charCount <= 0 || charCount > kFloatMaxCharCount) return false;
        // 多字形串：起始索引 + 字数不得越过资产表尾（防越界取 UV）
        if (assetIndex + charCount > assetCount) return false;
        if (!std::isfinite(scale) || scale <= 0.0f || scale > kFloatMaxScale) return false;
        if (!std::isfinite(riseScale) || riseScale <= 0.0f || riseScale > kFloatMaxScale) return false;

        const float t = sanitizeTime(nowSeconds);

        // 找槽位：空槽优先；池满则覆盖 spawnSeq 最小者（最旧）
        int slot = findFreeSlot();
        if (slot < 0) {
            slot = findOldestSlot();
            if (slot < 0) return false;   // 理论不可达（容量 > 0 且全活跃）
            stats_.droppedTotal++;
            stats_.overflowFrames++;
            overflowThisFrame_ = true;
        }

        FloatTextInstance& inst = slots_[slot];
        inst.worldX = worldX;
        inst.worldY = worldY;
        inst.assetIndex = assetIndex;
        inst.charCount = charCount;
        inst.styleIndex = styleIndex;
        inst.spawnTime = t;
        inst.spawnSeq = ++nextSeq_;
        inst.scale = scale;
        inst.riseScale = riseScale;
        inst.bounce = bounce;
        inst.alive = true;
        stats_.spawnTotal++;
        return true;
    }

    /**
     * 推进动画并回收到期实例（**每帧调用，零跨线**）。
     *
     * 时间标量由调用方提供（drawFrame 的参数 / 本类自身累加器），不查系统时钟。
     * 到期实例标记 `alive = false` 归还池位。
     *
     * @param nowSeconds 当前时基（秒）；非有限/负值按 0 处理
     * @returns 本帧活跃实例数
     */
    int advance(float nowSeconds) {
        const float t = sanitizeTime(nowSeconds);
        int alive = 0;
        for (int i = 0; i < kFloatPoolCapacity; i++) {
            FloatTextInstance& inst = slots_[i];
            if (!inst.alive) continue;
            // 出生时刻晚于当前（时钟回拨/乱序）→ 视为刚出生，不立即回收
            const float age = t - inst.spawnTime;
            if (std::isfinite(age) && age < 0.0f) {
                inst.spawnTime = t;
                alive++;
                continue;
            }
            if (age >= kFloatLifetimeSeconds) {
                inst.alive = false;
                continue;
            }
            alive++;
        }
        activeCount_ = alive;
        overflowThisFrame_ = false;
        return alive;
    }

    /** 活跃实例数（最近一次 advance 的结果） */
    int activeCount() const { return activeCount_; }

    /**
     * 取第 i 个池槽（只读；`validate()` 语义：调用方自行判 `alive`）。
     * 供场景绘制核心遍历（避免暴露可写内部数组）。
     */
    const FloatTextInstance& slot(int i) const { return slots_[i]; }

    /** 池容量（常量投影，便于调用方循环） */
    static constexpr int capacity() { return kFloatPoolCapacity; }

    /** 池纪元复位（`shutdownRenderer` 调用——与 SceneStore::reset 同层语义） */
    void reset() {
        for (int i = 0; i < kFloatPoolCapacity; i++) slots_[i] = FloatTextInstance{};
        activeCount_ = 0;
        nextSeq_ = 0;
        overflowThisFrame_ = false;
        stats_ = FloatTextStats{};
        lastTime_ = 0.0f;
    }

    /** 遥测快照（接 R0.3 三计数器口径；供 JNI 导出） */
    const FloatTextStats& stats() const { return stats_; }

    /** 本帧是否发生池满覆盖（供遥测/日志，单帧标志） */
    bool overflowedThisFrame() const { return overflowThisFrame_; }

    /**
     * 时间卫生：非有限 / 负值 / 超大值一律归 0。
     *
     * 与 `updateCameraGlobals` 的消毒口径同族——渲染参数不得让动画进入
     * NaN/Inf 状态（会导致顶点流非有限、整批被后端丢弃）。
     */
    static float sanitizeTime(float t) {
        if (!std::isfinite(t) || t < 0.0f || t > kFloatMaxTimeSeconds) return 0.0f;
        return t;
    }

    /** 坐标卫生检查 */
    static bool finiteCoord(float v) {
        return std::isfinite(v) && std::fabs(v) <= kFloatMaxAbsCoord;
    }

private:
    /** 最大连续字形数（防单条浮字吃掉整段资产表；数字串最坏 ~12 位） */
    static constexpr int kFloatMaxCharCount = 24;

    /** 附加缩放上界（防超大 quad 撑爆批） */
    static constexpr float kFloatMaxScale = 8.0f;

    int findFreeSlot() const {
        for (int i = 0; i < kFloatPoolCapacity; i++) {
            if (!slots_[i].alive) return i;
        }
        return -1;
    }

    /** 最旧 = spawnSeq 最小（循环缓冲覆盖语义，守卫锁定） */
    int findOldestSlot() const {
        int best = -1;
        uint32_t bestSeq = 0;
        for (int i = 0; i < kFloatPoolCapacity; i++) {
            if (!slots_[i].alive) continue;
            if (best < 0 || slots_[i].spawnSeq < bestSeq) {
                best = i;
                bestSeq = slots_[i].spawnSeq;
            }
        }
        return best;
    }

    FloatTextInstance slots_[kFloatPoolCapacity]{};
    int activeCount_ = 0;
    uint32_t nextSeq_ = 0;
    bool overflowThisFrame_ = false;
    float lastTime_ = 0.0f;
    FloatTextStats stats_{};
};

/**
 * 单条浮字的动画采样结果（世界空间，**在调用方相机投影之前**）。
 *
 * `worldY` 已含上浮位移；`alpha` 已含淡出；`scale` 已含暴击弹跳。
 * 绘制核心据此生成 quad（多字形串按字宽水平排布）。
 */
struct FloatTextRenderSample {
    float worldX = 0.0f;
    float worldY = 0.0f;
    float scale = 1.0f;
    float alpha = 1.0f;
    int32_t assetIndex = 0;
    int32_t charCount = 1;
    int32_t styleIndex = 0;
};

/**
 * 动画采样：由实例 + 当前时基算出渲染参数（纯函数，可独立守卫）。
 *
 * 上浮：`worldY + risePerSecond × riseScale × age`
 * 淡出：进度 p = age / lifetime；p < fadeStart → 1，之后线性降到 0
 * 弹跳：age < bounceSeconds 时缩放从 bounceScale 线性收到 `scale`
 *
 * @returns 采样结果；`alpha <= 0` 表示本帧不可见（调用方跳过）
 */
inline FloatTextRenderSample sampleFloatText(const FloatTextInstance& inst, float nowSeconds) {
    FloatTextRenderSample out;
    out.assetIndex = inst.assetIndex;
    out.charCount = inst.charCount;
    out.styleIndex = inst.styleIndex;
    out.worldX = inst.worldX;
    out.worldY = inst.worldY;
    out.scale = inst.scale;

    const float t = FloatTextPool::sanitizeTime(nowSeconds);
    float age = t - inst.spawnTime;
    if (!std::isfinite(age) || age < 0.0f) age = 0.0f;

    // 上浮
    out.worldY = inst.worldY + kFloatRisePerSecond * inst.riseScale * age;

    // 淡出（进度归一化；寿命 > 0 由常量保证）
    const float progress = age / kFloatLifetimeSeconds;
    float alpha = 1.0f;
    if (progress > kFloatFadeStart) {
        const float fadeSpan = 1.0f - kFloatFadeStart;
        alpha = fadeSpan > 0.0f ? (1.0f - progress) / fadeSpan : 0.0f;
    }
    if (!std::isfinite(alpha)) alpha = 0.0f;
    out.alpha = alpha < 0.0f ? 0.0f : (alpha > 1.0f ? 1.0f : alpha);

    // 暴击弹跳（出生后回落）
    if (inst.bounce && age < kFloatCritBounceSeconds && kFloatCritBounceSeconds > 0.0f) {
        const float k = 1.0f - age / kFloatCritBounceSeconds;   // 1 → 0
        out.scale = inst.scale * (1.0f + (kFloatCritBounceScale - 1.0f) * k);
    }
    return out;
}

}  // namespace scene
