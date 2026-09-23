// TextureCache 单测（MR3-P3.1/D2；桌面 ctest）
//
// 覆盖验收清单：refCount / pin / trim 档位 / 纪元失效（clearEpoch 后 acquire
// 禁命中旧 handle）/ 同 key 双 acquire 幂等 / 竞态二选一（pendingDestroy 期间
// 同 key 再 acquire 按 miss 重传替换——线程契约表四预登记决策，永不命中旧
// handle）/ trim 不重传风暴（upload 计数断言）。
//
// TextureCache 为 Meyers 单例且统计计数跨用例累计——每用例先 clearEpoch()
// 清表，统计断言一律用快照差值（delta）而非绝对值。
//
// CountingUploader 必须经 bindUpload 包装：std::function 按值拷贝 callable，
// 直接传 up 会把 calls/next 写到副本上（断言恒 0 的根因）。

#include "TextureCache.h"

#include <gtest/gtest.h>

#include <vector>

namespace {

using texture_key::kAssetAtlas;
using texture_key::kAssetGroundGrass;
using texture_key::kAssetRockBase;
using texture_key::kFormatAstc4x4;
using texture_key::kFormatRgba8;
using texture_key::kVariantMipChain;
using texture_key::kVariantRepeat;
using texture_key::kVariantRepeatMipChain;
using texture_key::kVariantSingleLevel;

/// 主图集 ASTC 臂键（与 Kotlin RendererTextureKeys 生产键同值）
TextureKey atlasAstcKey() { return {kAssetAtlas, kFormatAstc4x4, kVariantMipChain}; }
/// 主图集 RGBA mip 链臂键（同资产不同压缩臂——必须不同键）
TextureKey atlasRgbaKey() { return {kAssetAtlas, kFormatRgba8, kVariantMipChain}; }
/// 地皮草 REPEAT 键
TextureKey groundKey() { return {kAssetGroundGrass, kFormatRgba8, kVariantRepeat}; }
/// 岩石 REPEAT 键
TextureKey rockKey() { return {kAssetRockBase, kFormatRgba8, kVariantRepeat}; }

/// 统计快照差值（统计计数跨用例累计，断言用 delta）
struct StatsDelta {
    TextureCache::Stats before;
    explicit StatsDelta(TextureCache& cache) : before(cache.stats()) {}
    [[nodiscard]] uint64_t uploads(TextureCache& c) const { return c.stats().uploads - before.uploads; }
    [[nodiscard]] uint64_t hits(TextureCache& c) const { return c.stats().hits - before.hits; }
    [[nodiscard]] uint64_t reuploads(TextureCache& c) const { return c.stats().reuploads - before.reuploads; }
    [[nodiscard]] int64_t entries(TextureCache& c) const {
        return static_cast<int64_t>(c.stats().entries) - static_cast<int64_t>(before.entries);
    }
    [[nodiscard]] int64_t pendingDestroy(TextureCache& c) const {
        return static_cast<int64_t>(c.stats().pendingDestroy) - static_cast<int64_t>(before.pendingDestroy);
    }
};

/// 固定句柄上传闭包（每次调用句柄递增——断言「重传 = 新 handle ≠ 旧 handle」）
struct CountingUploader {
    uint32_t next = 100;
    uint32_t calls = 0;
    uint32_t operator()() { ++calls; return next++; }
};

/// std::function 按值拷贝 callable——直接传 up 会写到副本；lambda 捕引用回写原对象
TextureCache::UploadFn bindUpload(CountingUploader& up) {
    return [&up]() { return up(); };
}

class TextureCacheTest : public ::testing::Test {
protected:
    void SetUp() override { TextureCache::get().clearEpoch(); }

    /// 退役收集（lambda 捕引用——DestroyFn 按值构造 std::function 仍写回本 vector）
    std::vector<uint32_t> retired;
    TextureCache::DestroyFn destroyFn() {
        return [this](uint32_t handle) { retired.push_back(handle); };
    }
};

// ── TextureKey 位段 schema ──

TEST_F(TextureCacheTest, PackUnpackRoundTrip) {
    const TextureKey key{kAssetRockBase, kFormatAstc4x4, kVariantRepeatMipChain};
    const TextureKey decoded = TextureKey::unpack(key.pack());
    EXPECT_EQ(key, decoded) << "pack→unpack 必须无损往返（位段 schema 契约）";
}

TEST_F(TextureCacheTest, PackBitFieldsDoNotBleed) {
    // 同一资产不同压缩臂必须不同键（方案 D2.1）——format 翻转改变 pack 值，
    // 且不串扰 assetId / variant
    const TextureKey astc{kAssetAtlas, kFormatAstc4x4, kVariantMipChain};
    const TextureKey rgba{kAssetAtlas, kFormatRgba8, kVariantMipChain};
    EXPECT_NE(astc.pack(), rgba.pack()) << "ASTC 与 RGBA 臂 pack 值必不等";
    EXPECT_EQ(astc.assetId, rgba.assetId) << "format 段不得串扰 assetId";
    EXPECT_EQ(astc.variant, rgba.variant) << "format 段不得串扰 variant";
    const TextureKey decodedAstc = TextureKey::unpack(astc.pack());
    EXPECT_EQ(kFormatAstc4x4, decodedAstc.format);
}

TEST_F(TextureCacheTest, IsValidRejectsIllegalKeys) {
    EXPECT_FALSE((TextureKey{}.isValid())) << "全零键非法";
    EXPECT_FALSE((TextureKey{0, kFormatRgba8, kVariantSingleLevel}.isValid())) << "assetId=0 非法";
    EXPECT_FALSE((TextureKey{9, 0, kVariantSingleLevel}.isValid())) << "format 未登记非法";
    EXPECT_FALSE((TextureKey{9, 3, kVariantSingleLevel}.isValid())) << "format 越界非法";
    EXPECT_TRUE((TextureKey{1, kFormatRgba8, kVariantSingleLevel}.isValid()));
    EXPECT_TRUE((TextureKey{1, kFormatAstc4x4, kVariantMipChain}.isValid()));
}

// ── acquire / refCount ──

TEST_F(TextureCacheTest, AcquireMissUploadsAndInserts) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    const uint32_t handle = cache.acquire(groundKey(), /*pinned=*/false, bindUpload(up));
    EXPECT_NE(0u, handle) << "miss 上传成功必须返回非 0 handle";
    EXPECT_EQ(1u, up.calls) << "miss 恰执行一次 uploadFn";
    EXPECT_EQ(1, cache.stats().entries) << "成功上传插表恰一条目";
}

TEST_F(TextureCacheTest, AcquireSameKeyTwiceRefCountIdempotentUpload) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    const uint32_t first = cache.acquire(groundKey(), true, bindUpload(up));
    const uint32_t second = cache.acquire(groundKey(), true, bindUpload(up));
    EXPECT_EQ(first, second) << "同 key 双 acquire 返回同一 handle";
    EXPECT_EQ(1u, up.calls) << "同 key 双 acquire 零重传（方案 R2 命中面）";

    // 首次 release 后仍有一份引用 → 不销毁；第二次 release 后 pinned 归零保留
    // （SetUp 已 clearEpoch——绝对计数即本用例净表态）
    cache.release(groundKey(), destroyFn());
    EXPECT_EQ(0u, cache.stats().pendingDestroy)
        << "仍有引用时物理销毁不入队";
    cache.release(groundKey(), destroyFn());
    EXPECT_TRUE(retired.empty()) << "pinned 条目归零后保留（快速重取 + trim 防护）";
    EXPECT_EQ(1u, cache.stats().entries) << "pinned 归零条目仍在表";
}

TEST_F(TextureCacheTest, AcquireUploadFailureLeavesNoEntry) {
    TextureCache& cache = TextureCache::get();
    const uint32_t failed = cache.acquire(atlasAstcKey(), true, [] { return 0u; });
    EXPECT_EQ(0u, failed) << "上传失败返回 0";
    EXPECT_EQ(0u, cache.stats().entries)
        << "失败路径不得残留脏条目（回退臂是不同键）";
    EXPECT_EQ(0u, cache.stats().pendingDestroy);
}

TEST_F(TextureCacheTest, AcquireRejectsIllegalKey) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    EXPECT_EQ(0u, cache.acquire((TextureKey{}), true, bindUpload(up))) << "全零键拒绝";
    EXPECT_EQ(0u, cache.acquire((TextureKey{0, kFormatRgba8, 0}), true, bindUpload(up)))
        << "assetId=0 拒绝";
    EXPECT_EQ(0u, up.calls) << "非法键不执行上传";
    EXPECT_EQ(0u, cache.stats().entries);
}

// ── release / pendingDestroy / 竞态二选一 ──

TEST_F(TextureCacheTest, ReleaseToZeroEvictableEnqueuesDestroyAndMarksPending) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    const uint32_t handle = cache.acquire(groundKey(), /*pinned=*/false, bindUpload(up));
    cache.release(groundKey(), destroyFn());
    ASSERT_EQ(1u, retired.size()) << "归零 evictable 必达 destroyFn 入队（方案 R2）";
    EXPECT_EQ(handle, retired[0]);
    EXPECT_EQ(1u, cache.stats().pendingDestroy)
        << "表项滞留为 pendingDestroy（物理销毁未完成观测面）";
}

TEST_F(TextureCacheTest, ReleaseToZeroPinnedKeepsEntry) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    cache.acquire(groundKey(), /*pinned=*/true, bindUpload(up));
    cache.release(groundKey(), destroyFn());
    EXPECT_TRUE(retired.empty()) << "pinned 条目归零保留，不触发销毁";
    EXPECT_EQ(1u, cache.stats().entries);
    EXPECT_EQ(0u, cache.stats().pendingDestroy);
}

TEST_F(TextureCacheTest, ReleaseDoubleFreeDefense) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    cache.acquire(groundKey(), false, bindUpload(up));
    cache.release(groundKey(), destroyFn());     // 正常归零 → destroy 一次
    cache.release(groundKey(), destroyFn());     // 重复释放：refCount 已 0 → no-op
    cache.release(atlasAstcKey(), destroyFn());  // 未知键：no-op
    EXPECT_EQ(1u, retired.size())
        << "同一 handle 恰销毁一次（二次 destroyFn = double-free 根因防线）";
}

TEST_F(TextureCacheTest, PendingDestroyThenAcquireReuploadsReplacingEntry) {
    // 竞态二选一锁定（方案 D2.2 + 线程契约表四预登记）：release 物理销毁完成
    // 前（pendingDestroy 滞留期）同 key 再 acquire —— 按 miss 重传并替换
    // entry，旧 handle 不复用、继续由后端延迟队列排空。
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    const uint32_t oldHandle = cache.acquire(groundKey(), false, bindUpload(up));
    cache.release(groundKey(), destroyFn());
    ASSERT_EQ(1u, retired.size());

    const uint32_t newHandle = cache.acquire(groundKey(), false, bindUpload(up));
    EXPECT_NE(oldHandle, newHandle) << "重传必须产出新 handle（禁命中旧 handle）";
    EXPECT_EQ(1u, cache.stats().entries) << "替换后表内仍恰一条目";
    EXPECT_EQ(0u, cache.stats().pendingDestroy) << "替换后 pendingDestroy 清除";
    EXPECT_EQ(2u, up.calls) << "首传 + 重传 = 恰两次 uploadFn";
    EXPECT_EQ(1u, cache.stats().reuploads) << "重传计数可观测";
}

// ── trim 档位 ──

TEST_F(TextureCacheTest, TrimSoftIsNoop) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    cache.acquire(groundKey(), false, bindUpload(up));
    cache.release(groundKey(), destroyFn());  // 空闲 evictable（表项滞留 pendingDestroy）
    EXPECT_EQ(0u, cache.trim(1, destroyFn())) << "SOFT 档 GPU 面无动作";
    EXPECT_EQ(1u, cache.stats().entries) << "SOFT 不驱逐";
}

TEST_F(TextureCacheTest, TrimAggressiveEvictsIdleEvictableOnly) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    const uint32_t idleEvictable = cache.acquire(groundKey(), false, bindUpload(up));
    const uint32_t idlePinned = cache.acquire(atlasAstcKey(), true, bindUpload(up));
    const uint32_t inUse = cache.acquire(atlasRgbaKey(), false, bindUpload(up));
    cache.release(groundKey(), destroyFn());  // idleEvictable → 空闲 pendingDestroy

    retired.clear();
    const size_t evicted = cache.trim(2, destroyFn());
    // pendingDestroy 条目：trim 只移除表项不再次 destroyFn（禁 double-free）
    EXPECT_EQ(1u, evicted) << "AGGRESSIVE 只驱逐空闲 evictable";
    EXPECT_TRUE(retired.empty())
        << "pendingDestroy 条目 trim 仅移表项（已在 release 时入过退役队列）";
    EXPECT_EQ(idlePinned, cache.acquire(atlasAstcKey(), true, bindUpload(up)))
        << "pinned 空闲条目 AGGRESSIVE 不动（acquire 幂等返回原 handle）";
    EXPECT_EQ(inUse, cache.acquire(atlasRgbaKey(), false, bindUpload(up)))
        << "在用条目（refCount>0）不动";
    EXPECT_EQ(2u, cache.stats().entries) << "驱逐后余 pinned + 在用两条";
}

TEST_F(TextureCacheTest, TrimCriticalAlsoEvictsIdlePinnedNeverInUse) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    const uint32_t idlePinned = cache.acquire(groundKey(), true, bindUpload(up));
    const uint32_t inUseEvictable = cache.acquire(atlasAstcKey(), false, bindUpload(up));
    cache.release(groundKey(), destroyFn());  // 空闲 pinned（pinned 归零保留，非 pendingDestroy）

    retired.clear();
    const size_t evicted = cache.trim(3, destroyFn());
    EXPECT_EQ(1u, evicted) << "CRITICAL 加码清空闲 pinned（重取由上传轮驱动）";
    ASSERT_EQ(1u, retired.size());
    EXPECT_EQ(idlePinned, retired[0]);
    EXPECT_EQ(inUseEvictable, cache.acquire(atlasAstcKey(), false, bindUpload(up)))
        << "在用 evictable（refCount>0）恒不动——驱逐即永久白纹（无重传驱动面）";
}

TEST_F(TextureCacheTest, TrimNeverTriggersReuploadStorm) {
    // 方案红线：trim 路径禁止重传风暴——trim 只销毁不重传
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    cache.acquire(groundKey(), false, bindUpload(up));
    cache.release(groundKey(), destroyFn());
    const StatsDelta delta(cache);  // 基线：trim 前
    cache.trim(3, destroyFn());
    cache.trim(3, destroyFn());
    EXPECT_EQ(0u, delta.uploads(cache)) << "trim 全程零 uploadFn 执行";
    EXPECT_EQ(-1, delta.entries(cache)) << "trim 后空闲条目被驱逐（基线 1 → 0）";
}

TEST_F(TextureCacheTest, TrimEvictedKeyReacquireGetsNewHandle) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    const uint32_t first = cache.acquire(groundKey(), false, bindUpload(up));
    cache.release(groundKey(), destroyFn());
    cache.trim(2, destroyFn());
    const uint32_t reacquired = cache.acquire(groundKey(), false, bindUpload(up));
    EXPECT_NE(first, reacquired) << "trim 驱逐后重取 = miss 重传新 handle";
}

// ── clearEpoch 纪元失效 ──

TEST_F(TextureCacheTest, ClearEpochInvalidatesWholeTableNoStaleHit) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    const uint32_t oldHandle = cache.acquire(groundKey(), true, bindUpload(up));
    cache.clearEpoch();  // surface 纪元死亡
    EXPECT_EQ(0u, cache.stats().entries) << "整表失效";
    const uint32_t newHandle = cache.acquire(groundKey(), true, bindUpload(up));
    EXPECT_NE(oldHandle, newHandle)
        << "纪元后 acquire 必 miss 重传（禁命中旧 handle——方案 D2.2）";
    EXPECT_TRUE(retired.empty())
        << "clearEpoch 不调 destroyFn（物理销毁由纪元析构既有逐条销毁承担）";
}

TEST_F(TextureCacheTest, ClearEpochIsIdempotent) {
    TextureCache& cache = TextureCache::get();
    cache.acquire(groundKey(), true, [] { return 7u; });
    cache.clearEpoch();
    cache.clearEpoch();
    EXPECT_EQ(0u, cache.stats().entries);
}

// ── unpinAll（pinned 迁移）──

TEST_F(TextureCacheTest, UnpinAllDowngradesAndTrimCanReclaim) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    cache.acquire(groundKey(), true, bindUpload(up));
    cache.acquire(atlasAstcKey(), true, bindUpload(up));
    EXPECT_EQ(2u, cache.stats().pinned);
    cache.unpinAll();  // SceneUpdateChannel 切换路径挂点语义
    EXPECT_EQ(0u, cache.stats().pinned) << "全部 pinned 降 evictable";
    cache.release(groundKey(), destroyFn());    // ref→0 且 !pinned → pendingDestroy 入队
    cache.release(atlasAstcKey(), destroyFn()); // 同上
    EXPECT_EQ(2u, cache.trim(2, destroyFn()))
        << "降级后空闲条目可被 trim 腾掉（防漏 unpin 致 trim 永远腾不掉）";
}

// ── 观测面 ──

TEST_F(TextureCacheTest, StatsSnapshotObservability) {
    TextureCache& cache = TextureCache::get();
    CountingUploader up;
    cache.acquire(groundKey(), false, bindUpload(up));  // ref=1
    const StatsDelta delta(cache);  // 基线：命中 + 归零前
    cache.acquire(groundKey(), false, bindUpload(up));  // 快路径命中 → ref=2
    cache.release(groundKey(), destroyFn());            // ref=1
    cache.release(groundKey(), destroyFn());            // ref=0 → pendingDestroy
    EXPECT_EQ(1u, delta.hits(cache)) << "命中计数可观测";
    EXPECT_EQ(0u, delta.uploads(cache)) << "命中零上传";
    EXPECT_EQ(1, delta.pendingDestroy(cache)) << "退役队列长度可观测";
}

}  // namespace
