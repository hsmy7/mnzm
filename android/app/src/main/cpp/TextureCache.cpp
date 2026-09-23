// TextureCache 实现（契约见 TextureCache.h 头注释；MR3-P3.1/D2）

#include "TextureCache.h"

#include <cassert>
#include <vector>

TextureCache& TextureCache::get() {
    static TextureCache instance;
    return instance;
}

uint32_t TextureCache::acquire(TextureKey key, bool pinned, UploadFn upload) {
    // 键非法 = 编程错误（裸键/未登记 format）——debug 断言红 + 生产拒绝
    assert(key.isValid() && "TextureCache: 非法 TextureKey（assetId=0 或 format 未登记）");
    if (!key.isValid() || !upload) return 0;

    const uint64_t packed = key.pack();

    std::unique_lock<std::mutex> lock(m_mutex);
    {
        auto it = m_entries.find(packed);
        if (it != m_entries.end() && !it->second.pendingDestroy) {
            // 快路径：在用/空闲条目 refCount++，零重传（同 key 双 acquire 幂等）
            ++it->second.refCount;
            it->second.pinned = it->second.pinned || pinned;
            ++m_hits;
            return it->second.handle;
        }
    }

    // miss / pendingDestroy 替换：uploadFn 必须在缓存锁外执行（其内部走后端
    // m_gpuMutex 互斥域——锁嵌套即死锁）。解锁窗口内表可能被并发写（防御性
    // 重查；单线程上传编排契约下不发生），故不跨锁持迭代器。
    // 若未来改并发编排，须先加 in-flight 去重（线程契约表四口径不变）。
    lock.unlock();
    const uint32_t uploaded = upload();
    lock.lock();

    if (uploaded == 0) {
        // 上传失败不插表（ASTC 失败回退 RGBA 臂 = 不同键，无脏条目残留）。
        // 不计 uploads——textureAcquire 的 miss 探针（恒 0）不得污染上传计数
        //（trim 不重传风暴断言 / 附录 A 峰值观测面依赖本计数语义）。
        return 0;
    }
    ++m_uploads;
    auto it = m_entries.find(packed);
    if (it != m_entries.end()) {
        // pendingDestroy 替换：旧 handle 已在 release/trim 时 destroyFn 入队，
        // 由后端延迟队列继续排空——本处只覆写条目，禁对旧 handle 二次 destroy
        it->second.handle = uploaded;
        it->second.refCount = 1;
        it->second.pendingDestroy = false;
        it->second.pinned = pinned;
        ++m_reuploads;
        return it->second.handle;
    }
    TextureEntry entry;
    entry.handle = uploaded;
    entry.refCount = 1;
    entry.pinned = pinned;
    it = m_entries.emplace(packed, entry).first;
    return it->second.handle;
}

void TextureCache::release(TextureKey key, DestroyFn destroy) {
    assert(key.isValid() && "TextureCache: 非法 TextureKey（assetId=0 或 format 未登记）");
    if (!key.isValid()) return;

    const uint64_t packed = key.pack();
    uint32_t retiring = 0;

    {
        std::lock_guard<std::mutex> lock(m_mutex);
        auto it = m_entries.find(packed);
        if (it == m_entries.end()) return;                    // 防御：未知键
        TextureEntry& entry = it->second;
        if (entry.refCount == 0) return;                      // 防御：重复释放
        --entry.refCount;
        if (entry.refCount > 0 || entry.pinned) return;       // 在用/pinned 保留
        // 引用归零且 evictable：物理销毁即刻入队（后端延迟释放通道），
        // 条目滞留为 pendingDestroy（重传替换面，竞态二选一见类头）
        entry.pendingDestroy = true;
        retiring = entry.handle;
    }

    // destroyFn 锁外调用（其内部 Vulkan destroyTexture 持 m_gpuMutex 入退役队列）
    if (retiring != 0 && destroy) destroy(retiring);
}

void TextureCache::unpinAll() {
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& [packed, entry] : m_entries) {
        entry.pinned = false;
    }
}

size_t TextureCache::trim(int level, DestroyFn destroy) {
    // 档位语义：1=SOFT（Kotlin 驱逐域，GPU 面无动作）/ 2=AGGRESSIVE / 3=CRITICAL
    //（序数 = Renderer2D::RenderTrimLevel，JNI 线协议）
    if (level < 2) return 0;

    std::vector<uint32_t> retiring;
    size_t evicted = 0;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        for (auto it = m_entries.begin(); it != m_entries.end();) {
            TextureEntry& entry = it->second;
            // refCount>0 恒不动：Kotlin 场景仍引用的纹理无重传驱动面，
            // 驱逐即永久白纹（trim 不制造渲染降级，重传只由 acquire 驱动）
            const bool idle = entry.refCount == 0;
            const bool evictable = idle && (!entry.pinned || level >= 3);
            if (!evictable) {
                ++it;
                continue;
            }
            // pendingDestroy 条目已入过退役队列——只移除表项，禁二次 destroyFn
            if (!entry.pendingDestroy && entry.handle != 0 && destroy) {
                retiring.push_back(entry.handle);
            }
            it = m_entries.erase(it);
            ++m_trimEvictions;
            ++evicted;  // 返回值 = 驱逐条目数（含仅移表项的 pendingDestroy）
        }
    }

    // destroyFn 锁外批量调用（后端延迟释放——沿 MAX_FRAMES_IN_FLIGHT 帧边界；
    // 队列长度经 stats().pendingDestroy 观测）
    if (destroy) {
        for (const uint32_t handle : retiring) destroy(handle);
    }
    return evicted;
}

void TextureCache::clearEpoch() {
    std::lock_guard<std::mutex> lock(m_mutex);
    // 纯表失效：不调 destroyFn——物理销毁由纪元析构既有逐条销毁承担
    //（VulkanBackend m_textures 循环按轨销毁 / GLES shutdown 删除），二次
    // destroy 即 double-free。纪元后 acquire 必 miss（禁命中旧 handle）。
    m_entries.clear();
}

TextureCache::Stats TextureCache::stats() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    Stats snapshot;
    snapshot.uploads = m_uploads;
    snapshot.hits = m_hits;
    snapshot.reuploads = m_reuploads;
    snapshot.trimEvictions = m_trimEvictions;
    for (const auto& [packed, entry] : m_entries) {
        ++snapshot.entries;
        if (entry.pinned) ++snapshot.pinned;
        if (entry.pendingDestroy) ++snapshot.pendingDestroy;
    }
    return snapshot;
}
