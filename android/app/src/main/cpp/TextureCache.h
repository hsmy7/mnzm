#pragma once

// ============================================================
// TextureCache — 键控 refCount 纹理缓存（memory-refactor D2/MR3）
//
// 结构根因（memory-audit M-P0-1/M-P1-7）：destroyTexture 零调用；无 key/
// refCount；同纪元重复上传累积；失败重试可双份。本类收口为渲染层纹理的
// 唯一键控面：
//   - TextureKey 位段 schema（禁裸 uint64 自造键）——同一资产的不同压缩臂
//     （ASTC/RGBA）必须不同键（format 位段区分）；
//   - acquire/release refCount 契约（同 key 幂等 refCount++，双 acquire = 2）；
//   - trim 档位驱逐（AGGRESSIVE 清空闲 evictable；CRITICAL 加码清空闲 pinned；
//     refCount>0 恒不动——Kotlin 场景仍引用的纹理无重传驱动面，驱逐即永久
//     白纹，见 trim 注释）；
//   - clearEpoch 纪元失效（surface 重建后 acquire 禁命中旧 handle）；
//   - 竞态二选一（方案 D2.2，线程契约表四预登记语义）：release/trim 后物理
//     销毁完成前同 key 再 acquire —— **按 miss 重传替换 entry**（pendingDestroy
//     条目不命中旧 handle，旧 handle 由后端延迟队列继续排空）。texture_cache_
//     test.cpp 锁定本决策。
//
// 【零图形 API 依赖】纹理创建/销毁经调用方注入的 UploadFn/DestroyFn 闭包
//（= Renderer2D::uploadTexture 族 / destroyTexture 虚接口——Vulkan 同步上传、
// GLES 入队异步上传，两者对缓存而言都是「传 handle」）——本类不 include 任何
// 图形头，Vulkan/GLES 双路径共用（全局约束 12：cache 只调 RHI 接口，不直碰
// GL），零 Android 依赖桌面可编译直测。
//
// 【线程契约】（docs/threading-contract.md 表四 textureAcquire/textureRelease
// 条目，2026-09-23 预登记）：Kotlin 上传编排线程（现状主线程）经 JNI 串行
// 调用 acquire/release/unpinAll；trim 由渲染线程帧边界（NativeBridge.beginFrame
// 消费点）调用；clearEpoch 由纪元析构路径（destroySurfaceGeneration /
// GlesBackend::shutdown）调用。内部互斥防御 stats 跨线程读与防御性串行化，
// 非并发入口承诺（上传编排单线程纪律不变）。
//
// 【单例】沿 GpuAllocator::get() 先例。clearEpoch 幂等——「先 cache 后
// allocator/资源销毁」顺序（方案 D2.2）由调用方保证；clearEpoch 仅解除键控
// 引用（整表失效），**不**调 DestroyFn——物理销毁由纪元析构的既有逐条销毁
// 承担（VulkanBackend m_textures 循环 / GLES 纹理删除），二次 destroy 即
// double-free。
// ============================================================

#include <cstdint>
#include <functional>
#include <mutex>
#include <unordered_map>

// ── TextureKey 位段 schema 常量（双端镜像：Kotlin 侧 RendererTextureKeys
//    同值同语义，TextureUploadPathGuardTest 锁定生产调用点只经此常量族）──
namespace texture_key {

/** 像素格式位段 [63:48]（合法值仅两个——非法 format 在 acquire 入口拒绝） */
constexpr uint16_t kFormatRgba8 = 1;
constexpr uint16_t kFormatAstc4x4 = 2;

/** variant 位段 [47:32]（位组合：bit0 = mip 链 / bit1 = REPEAT 寻址） */
constexpr uint16_t kVariantSingleLevel = 0;  // CLAMP + 单级
constexpr uint16_t kVariantMipChain = 1;     // CLAMP + 多级 mip
constexpr uint16_t kVariantRepeat = 2;       // REPEAT + 单级（无缝材质）
constexpr uint16_t kVariantRepeatMipChain = 3;

/** 资产位段 [31:0]（当前全部渲染纹理资产；新增资产必须在此登记） */
constexpr uint32_t kAssetAtlas = 1;        // 主精灵图集（ASTC/RGBA 两臂共用 assetId）
constexpr uint32_t kAssetGroundGrass = 2;  // 地皮草 REPEAT（map_grass_1）
constexpr uint32_t kAssetRockBase = 3;     // 底部岩石 REPEAT（map_rock_base）

}  // namespace texture_key

/**
 * 纹理缓存键（位段 schema，禁止裸 uint64 自造键——方案 D2.1）。
 *
 * [63:48] format | [47:32] variant | [31:0] assetId。
 * pack() 无损可逆（unpack）；isValid 校验 assetId 非 0 且 format 合法。
 */
struct TextureKey {
    uint32_t assetId = 0;
    uint16_t format = 0;
    uint16_t variant = 0;

    /** 位段打包（同段位不重叠，pack→unpack 恒等） */
    uint64_t pack() const {
        return (static_cast<uint64_t>(format) << 48) |
               (static_cast<uint64_t>(variant) << 32) |
               static_cast<uint64_t>(assetId);
    }

    /** pack 的逆变换（位段提取） */
    static TextureKey unpack(uint64_t raw) {
        TextureKey key;
        key.assetId = static_cast<uint32_t>(raw & 0xFFFF'FFFFULL);
        key.variant = static_cast<uint16_t>((raw >> 32) & 0xFFFFU);
        key.format = static_cast<uint16_t>((raw >> 48) & 0xFFFFU);
        return key;
    }

    /** 键合法性（acquire 入口断言面）：assetId 非 0 且 format 为已登记值 */
    bool isValid() const {
        return assetId != 0 &&
               (format == texture_key::kFormatRgba8 || format == texture_key::kFormatAstc4x4);
    }

    bool operator==(const TextureKey& other) const {
        return assetId == other.assetId && format == other.format && variant == other.variant;
    }
};

/** 缓存条目（方案 D2.1 契约字段） */
struct TextureEntry {
    uint32_t handle = 0;
    uint32_t refCount = 0;
    bool pinned = false;
    /** 已逻辑释放（destroyFn 已入队）、物理销毁未完成（后端延迟队列未排空）——
     *  置位期间同 key acquire 禁命中旧 handle（按 miss 重传替换，见类头注释） */
    bool pendingDestroy = false;
};

/**
 * 键控纹理缓存（Meyers 单例；调用方注入上传/销毁闭包，零图形 API 依赖）。
 */
class TextureCache {
public:
    /** miss/重传时的上传闭包：返回非 0 GPU 纹理 handle；0 = 上传失败（不插表） */
    using UploadFn = std::function<uint32_t()>;
    /** 退役闭包：把 handle 交给后端延迟释放通道（Renderer2D::destroyTexture——
     *  Vulkan 入 m_retiredTextures 帧边界释放 / GLES 入待删队列渲染线程删除） */
    using DestroyFn = std::function<void(uint32_t)>;

    static TextureCache& get();

    TextureCache(const TextureCache&) = delete;
    TextureCache& operator=(const TextureCache&) = delete;

    /**
     * 获取（或创建）键对应纹理，引用计数 +1。
     *
     * - 命中空闲/在用条目：refCount++ 快路径返回（不执行 upload——同纪元重复
     *   acquire 零重传，方案 R2 判据）；
     * - 命中 pendingDestroy 条目：**按 miss 重传替换**（竞态二选一决策，见类头）；
     * - miss：执行 uploadFn（持缓存锁外调用——uploadFn 内部走后端上传的
     *   m_gpuMutex 互斥域，与缓存锁不得嵌套）；成功插表返回 handle，失败
     *   （返回 0）不插表返回 0。
     *
     * @param pinned true = 当前 surface 必需资产（trim 不驱逐；当前生效的
     *        图集/地面/岩石臂）；false = evictable
     * @return GPU 纹理 handle；0 = 键非法 / 上传失败
     */
    uint32_t acquire(TextureKey key, bool pinned, UploadFn upload);

    /**
     * 释放一次引用（--ref）。
     * refCount 减到 0 且非 pinned：destroyFn(handle) 入队 + entry 置
     * pendingDestroy（表项滞留至重传替换/trim/clearEpoch——物理销毁完成后
     * 由 acquire 重传替换，竞态二选一见类头）。pinned 条目减到 0 保留
     * （快速重取 + trim 防护；纪元切换时随 clearEpoch 整表失效）。
     * 键非法 / 条目不存在 / refCount 已为 0：无操作（防御调用方重复释放）。
     */
    void release(TextureKey key, DestroyFn destroy);

    /** 全部 pinned 条目降 evictable（pinned 迁移交 SceneUpdateChannel 切换
     *  路径——旧场景 pinned 降级，新场景上传成功后经 acquire(pinned=true)
     *  重提升；防漏 unpin 致 trim 永远腾不掉，方案 D2.4） */
    void unpinAll();

    /**
     * 档位驱逐（渲染线程帧边界经 NativeBridge.beginFrame 消费点调用）。
     * - level < AGGRESSIVE：无动作（SOFT 面为 Kotlin cache/驱逐域）；
     * - AGGRESSIVE：驱逐全部空闲 evictable（refCount==0 && !pinned）；
     * - CRITICAL：加码驱逐全部空闲 pinned（当前必需资产也让位——重取由
     *   上传轮 acquire 驱动）；refCount>0 恒不动（在用纹理无重传驱动面）。
     * 驱逐 = destroyFn 入队（后端延迟释放，沿用 MAX_FRAMES_IN_FLIGHT 帧边界）
     * + 表项移除。@return 驱逐条目数（0 = 无动作/无可驱逐）
     */
    size_t trim(int level, DestroyFn destroy);

    /**
     * 纪元失效（surface 纪元死亡——destroySurfaceGeneration / GLES shutdown
     * 挂点，先 cache 后资源销毁顺序由调用方保证）。整表清空，不调 destroyFn
     * （物理销毁由纪元析构既有逐条销毁承担，见类头注释）；此后任何 acquire
     * 必 miss（禁命中旧 handle——方案 D2.2）。幂等。
     */
    void clearEpoch();

    /** 缓存统计快照（退役队列长度可观测面 = pendingDestroy 计数） */
    struct Stats {
        size_t entries = 0;         // 表内存活条目（含 pendingDestroy 滞留项）
        size_t pinned = 0;          // pinned 条目数
        size_t pendingDestroy = 0;  // 逻辑已释放、物理销毁未完成（退役队列观测面）
        uint64_t uploads = 0;       // uploadFn 实际执行次数（miss + 重传）
        uint64_t hits = 0;          // 快路径命中次数（refCount++ 免重传）
        uint64_t reuploads = 0;     // pendingDestroy 替换重传次数
        uint64_t trimEvictions = 0; // 累计 trim 驱逐条目数
    };
    Stats stats() const;

private:
    TextureCache() = default;
    ~TextureCache() = default;

    /** 表（packed key → entry）。内部锁见类头线程契约。 */
    std::unordered_map<uint64_t, TextureEntry> m_entries;

    // 统计计数（GUARDED_BY(m_mutex)）
    uint64_t m_uploads = 0;
    uint64_t m_hits = 0;
    uint64_t m_reuploads = 0;
    uint64_t m_trimEvictions = 0;

    /** 内部互斥（防御 stats 跨线程读；acquire 的 uploadFn 在锁外执行，不嵌套） */
    mutable std::mutex m_mutex;
};
