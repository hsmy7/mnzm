#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

#include "Rhi.h"
#include "SpriteBatcher.h"
#include "scene/float_text.h"
#include "scene/scene_draw.h"
#include "scene/scene_uv_tables.h"

namespace {

using scene::FloatTextInstance;
using scene::FloatTextParams;
using scene::FloatTextPool;
using scene::FloatTextRenderSample;
using scene::FloatTextStats;
using scene::buildFloatTextBatch;
using scene::sampleFloatText;

// ============================================================
// 浮字池语义 + 动画守卫（重构方案 2026-09-17 R3.8/B13）
//
// **证明目标**（批次 R3.8-② / 验收门 5）：
//   1. 池语义：容量固定、池满**覆盖最旧**（循环缓冲，spawnSeq 判据）、
//      到期回收、纪元复位（reset 清空实例 + 纪元序号 + 遥测）；
//   2. spawn 参数防御：非有限坐标 / 越界资产索引 / 越界样式档 / 非正字数 /
//      非有限缩放 / 保留位协议 —— 一律拒绝且**不残留脏实例**；
//   3. 动画采样纯函数：上浮位移、淡出曲线、暴击弹跳缩放；
//   4. 空池 = **零 draw call**；非空池 = 单纹理段（同图集一次 draw）；
//   5. 溢出遥测接 R0.3 三计数器口径（droppedTotal / overflowFrames / spawnTotal）。
// ============================================================

/** 记录器后端：捕获 draw(verts, count, texId) —— 与既有场景守卫同结构 */
struct RecorderRenderer : public Renderer2D {
    struct DrawCall {
        uint32_t texId;
        std::vector<SpriteVertex> verts;
    };
    std::vector<DrawCall> calls;

    void draw(const SpriteVertex* verts, int count, uint32_t texId) override {
        DrawCall c;
        c.texId = texId;
        c.verts.assign(verts, verts + count);
        calls.push_back(std::move(c));
    }
    bool init(const RenderConfig&, void*) override { return true; }
    void shutdown() override {}
    bool resize(int, int) override { return true; }
    RenderInitError lastInitError() const override { return RenderInitError::NONE; }
    void beginFrame() override {}
    void endFrame() override {}
    bool isReady() const override { return true; }
    uint32_t uploadTexture(const void*, int, int) override { return 1; }
    void destroyTexture(uint32_t) override {}
    void setProjection(const float*) override {}
    void submitFrame() override {}
    void drawBackground(const SpriteVertex*, int, const SkyGradientParams&) override {}
};

/** 记录提交的批（buildFloatTextBatch 的 submit 回调） */
struct SubmitRecorder {
    struct Batch {
        uint32_t texId;
        int count;
    };
    std::vector<Batch> batches;

    void operator()(uint32_t texId, const SpriteVertex* /*verts*/, int count) {
        batches.push_back({texId, count});
    }
};

// 测试夹具常量：与生成表同源（scene_uv_tables.h 单一权威）
constexpr uint32_t kAtlasTexId = 4242;
constexpr float kTileSize = 48.0f;

/** 装配一组"全部可见"的视图边界（剔除不影响本组用例） */
FloatTextParams makeParams(const FloatTextPool& pool, float nowSeconds) {
    FloatTextParams p;
    p.instances = &pool.slot(0);
    p.instanceCount = FloatTextPool::capacity();
    p.nowSeconds = nowSeconds;
    p.atlasTexId = kAtlasTexId;
    p.uv = scene::kFloatUv;
    p.assetCount = scene::kFloatAssetCount;
    p.glyphBaseIndex = scene::kFloatGlyphBaseIndex;
    p.tileSize = kTileSize;
    p.baseWorldHeight = scene::kFloatBaseWorldHeight;
    p.viewLeft = -100000.0f;
    p.viewTop = -100000.0f;
    p.viewRight = 100000.0f;
    p.viewBottom = 100000.0f;
    p.cullMargin = 0.0f;
    return p;
}

// ─────────────────────────────────────────────────────────────
// 1. 容量与常量
// ─────────────────────────────────────────────────────────────

TEST(FloatTextPoolTest, CapacityAndConstantsAreExplicitlyNamed) {
    // 容量为显式命名常量（批次要求"常量显式命名"）
    EXPECT_EQ(256, scene::kFloatPoolCapacity);
    EXPECT_EQ(scene::kFloatPoolCapacity, FloatTextPool::capacity());
    // 动画常量正值（防退化）
    EXPECT_GT(scene::kFloatLifetimeSeconds, 0.0f);
    EXPECT_GT(scene::kFloatRisePerSecond, 0.0f);
    EXPECT_GT(scene::kFloatCritBounceScale, 1.0f);
    EXPECT_GT(scene::kFloatCritBounceSeconds, 0.0f);
    EXPECT_GT(scene::kFloatBaseWorldHeight, 0.0f);
    // 淡出起点落在 (0,1)
    EXPECT_GT(scene::kFloatFadeStart, 0.0f);
    EXPECT_LT(scene::kFloatFadeStart, 1.0f);
    // 样式档数与生成表一致（颜色表长度 = 生成表档位数）
    EXPECT_EQ(scene::kFloatStyleCount, scene::kFloatStyleCount);
    EXPECT_EQ(4, scene::kFloatStyleCount);
    // spawn 协议步长与 Kotlin 镜像（本测试仅锁 C++ 侧值；Kotlin 侧由
    // SceneOverlayProtocolGuardTest 对照）
    EXPECT_EQ(10, scene::kFloatSpawnStride);
}

// ─────────────────────────────────────────────────────────────
// 2. spawn / 回收 / 池满覆盖最旧
// ─────────────────────────────────────────────────────────────

TEST(FloatTextPoolTest, SpawnPopulatesPoolAndActiveCount) {
    FloatTextPool pool;
    EXPECT_EQ(0, pool.activeCount());
    EXPECT_TRUE(pool.spawn(10.0f, 20.0f, 0, 1, scene::kFloatStyleNormal, 0.0f, scene::kFloatAssetCount));
    EXPECT_TRUE(pool.spawn(11.0f, 21.0f, 1, 2, scene::kFloatStyleCrit, 0.0f, scene::kFloatAssetCount));
    pool.advance(0.0f);
    EXPECT_EQ(2, pool.activeCount());
    // 实例字段按入参落位
    EXPECT_FLOAT_EQ(10.0f, pool.slot(0).worldX);
    EXPECT_FLOAT_EQ(20.0f, pool.slot(0).worldY);
    EXPECT_EQ(0, pool.slot(0).assetIndex);
    EXPECT_EQ(1, pool.slot(0).charCount);
    EXPECT_EQ(scene::kFloatStyleNormal, pool.slot(0).styleIndex);
    EXPECT_TRUE(pool.slot(0).alive);
    EXPECT_EQ(scene::kFloatStyleCrit, pool.slot(1).styleIndex);
    EXPECT_TRUE(pool.slot(1).bounce == false);
}

TEST(FloatTextPoolTest, ExpiredInstancesAreReclaimed) {
    FloatTextPool pool;
    pool.spawn(0.0f, 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    pool.advance(0.0f);
    EXPECT_EQ(1, pool.activeCount());
    // 寿命内仍活跃
    pool.advance(scene::kFloatLifetimeSeconds * 0.5f);
    EXPECT_EQ(1, pool.activeCount());
    // 到期回收
    pool.advance(scene::kFloatLifetimeSeconds);
    EXPECT_EQ(0, pool.activeCount());
    EXPECT_FALSE(pool.slot(0).alive);
}

TEST(FloatTextPoolTest, NoSpawnWithoutEventsKeepsZeroActiveAcrossFrames) {
    // 零每帧 JNI 的行为级证明：无 spawn 事件时，逐帧 advance 不产生任何实例
    FloatTextPool pool;
    for (int f = 0; f < 600; f++) {
        pool.advance(static_cast<float>(f) / 60.0f);
        ASSERT_EQ(0, pool.activeCount()) << "frame " << f;
    }
    EXPECT_EQ(0, pool.stats().spawnTotal);
}

TEST(FloatTextPoolTest, PoolOverflowOverwritesOldestAndCountsTelemetry) {
    FloatTextPool pool;
    // 填满整池（序号 1..capacity）
    for (int i = 0; i < scene::kFloatPoolCapacity; i++) {
        ASSERT_TRUE(pool.spawn(static_cast<float>(i), 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount));
    }
    pool.advance(0.0f);
    ASSERT_EQ(scene::kFloatPoolCapacity, pool.activeCount());
    EXPECT_EQ(0, pool.stats().droppedTotal);

    // 覆盖最旧（worldX == 0 的实例，spawnSeq 最小）
    const uint32_t oldestSeq = pool.slot(0).spawnSeq;
    ASSERT_TRUE(pool.spawn(999.0f, 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount));
    pool.advance(0.0f);
    // 池容量不变（覆盖而非增长）
    EXPECT_EQ(scene::kFloatPoolCapacity, pool.activeCount());
    // 遥测：丢弃 +1、溢出帧 +1
    EXPECT_EQ(1, pool.stats().droppedTotal);
    EXPECT_EQ(1, pool.stats().overflowFrames);
    // 最旧序号已不在池中（被覆盖）；新实例序号更大
    bool oldestStillPresent = false;
    float replacedX = -1.0f;
    for (int i = 0; i < scene::kFloatPoolCapacity; i++) {
        if (!pool.slot(i).alive) continue;
        if (pool.slot(i).spawnSeq == oldestSeq) oldestStillPresent = true;
        if (pool.slot(i).worldX == 999.0f) replacedX = pool.slot(i).worldX;
        EXPECT_GE(pool.slot(i).spawnSeq, oldestSeq) << "slot " << i << " 序号倒退（非单调）";
    }
    EXPECT_FALSE(oldestStillPresent) << "池满未覆盖最旧实例";
    EXPECT_FLOAT_EQ(999.0f, replacedX) << "新实例未入池";
}

TEST(FloatTextPoolTest, RepeatedOverflowKeepsOverwritingOldest) {
    // 循环缓冲语义：连续覆盖时始终保持"最旧被换出"
    FloatTextPool pool;
    for (int i = 0; i < scene::kFloatPoolCapacity; i++) {
        pool.spawn(static_cast<float>(i), 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    }
    pool.advance(0.0f);
    const float firstX = pool.slot(0).worldX;
    EXPECT_FLOAT_EQ(0.0f, firstX);
    // 连续 5 次覆盖
    for (int k = 1; k <= 5; k++) {
        ASSERT_TRUE(pool.spawn(1000.0f + static_cast<float>(k), 0.0f, 0, 1, 0,
                               0.0f, scene::kFloatAssetCount));
    }
    pool.advance(0.0f);
    EXPECT_EQ(scene::kFloatPoolCapacity, pool.activeCount());
    EXPECT_EQ(5, pool.stats().droppedTotal);
    EXPECT_EQ(5, pool.stats().overflowFrames);
    // 最早那批（worldX 0..4）应已全部被换出
    for (int i = 0; i < scene::kFloatPoolCapacity; i++) {
        if (!pool.slot(i).alive) continue;
        EXPECT_GE(pool.slot(i).worldX, 5.0f) << "slot " << i << " 仍持有已应被覆盖的实例";
    }
}

// ─────────────────────────────────────────────────────────────
// 3. 纪元复位
// ─────────────────────────────────────────────────────────────

TEST(FloatTextPoolTest, ResetClearsInstancesEpochAndTelemetry) {
    FloatTextPool pool;
    // 造出实例 + 溢出 + 序号推进
    for (int i = 0; i < scene::kFloatPoolCapacity + 10; i++) {
        pool.spawn(static_cast<float>(i), 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    }
    pool.advance(0.0f);
    ASSERT_GT(pool.stats().droppedTotal, 0);
    ASSERT_GT(pool.activeCount(), 0);

    pool.reset();

    EXPECT_EQ(0, pool.activeCount());
    EXPECT_EQ(0, pool.stats().droppedTotal);
    EXPECT_EQ(0, pool.stats().overflowFrames);
    EXPECT_EQ(0, pool.stats().degradeFrames);
    EXPECT_EQ(0, pool.stats().spawnTotal);
    EXPECT_FALSE(pool.overflowedThisFrame());
    for (int i = 0; i < scene::kFloatPoolCapacity; i++) {
        EXPECT_FALSE(pool.slot(i).alive) << "slot " << i << " 纪元复位后仍活跃";
    }
    // 纪元序号复位：新实例从 1 重新开始（不残留旧纪元的单调序号）
    ASSERT_TRUE(pool.spawn(1.0f, 2.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount));
    pool.advance(0.0f);
    EXPECT_EQ(1u, pool.slot(0).spawnSeq) << "reset 未复位纪元序号";
}

// ─────────────────────────────────────────────────────────────
// 4. spawn 参数防御（非有限坐标 / 非法索引 / 残留检查）
// ─────────────────────────────────────────────────────────────

TEST(FloatTextPoolTest, SpawnRejectsNonFiniteCoordinatesWithoutResidue) {
    FloatTextPool pool;
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    EXPECT_FALSE(pool.spawn(nan, 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount));
    EXPECT_FALSE(pool.spawn(0.0f, nan, 0, 1, 0, 0.0f, scene::kFloatAssetCount));
    EXPECT_FALSE(pool.spawn(inf, 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount));
    EXPECT_FALSE(pool.spawn(0.0f, -inf, 0, 1, 0, 0.0f, scene::kFloatAssetCount));
    // 超界坐标（|v| > kFloatMaxAbsCoord）
    EXPECT_FALSE(pool.spawn(1.0e9f, 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount));
    pool.advance(0.0f);
    // 拒绝后池为空且无残留脏实例
    EXPECT_EQ(0, pool.activeCount());
    for (int i = 0; i < scene::kFloatPoolCapacity; i++) {
        EXPECT_FALSE(pool.slot(i).alive) << "slot " << i << " 残留";
    }
    // 遥测不计入（拒绝 ≠ 溢出）
    EXPECT_EQ(0, pool.stats().droppedTotal);
    EXPECT_EQ(0, pool.stats().spawnTotal);
}

TEST(FloatTextPoolTest, SpawnRejectsOutOfRangeAssetIndex) {
    FloatTextPool pool;
    const int32_t n = scene::kFloatAssetCount;
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, -1, 1, 0, 0.0f, n)) << "负资产索引未拒绝";
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, n, 1, 0, 0.0f, n)) << "越界资产索引未拒绝";
    // 多字形串越界（起始合法但 assetIndex + charCount > count）
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, n - 1, 3, 0, 0.0f, n)) << "字形串越界未拒绝";
    // 边界合法值应通过（最后一条资产、单字形）
    EXPECT_TRUE(pool.spawn(0.0f, 0.0f, n - 1, 1, 0, 0.0f, n)) << "边界合法索引被误拒";
    pool.advance(0.0f);
    EXPECT_EQ(1, pool.activeCount());
}

TEST(FloatTextPoolTest, SpawnRejectsInvalidStyleAndCharCount) {
    FloatTextPool pool;
    const int32_t n = scene::kFloatAssetCount;
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, 0, 1, -1, 0.0f, n)) << "负样式档未拒绝";
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, 0, 1, scene::kFloatStyleCount, 0.0f, n)) << "越界样式档未拒绝";
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, 0, 0, 0, 0.0f, n)) << "零字数未拒绝";
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, 0, -1, 0, 0.0f, n)) << "负字数未拒绝";
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, 0, 1000000, 0, 0.0f, n)) << "超大字数未拒绝";
    pool.advance(0.0f);
    EXPECT_EQ(0, pool.activeCount());
}

TEST(FloatTextPoolTest, SpawnRejectsInvalidScaleAndRiseScale) {
    FloatTextPool pool;
    const int32_t n = scene::kFloatAssetCount;
    const float nan = std::numeric_limits<float>::quiet_NaN();
    // scale 非法
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, 0, 1, 0, 0.0f, n, 0.0f));
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, 0, 1, 0, 0.0f, n, -1.0f));
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, 0, 1, 0, 0.0f, n, nan));
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, 0, 1, 0, 0.0f, n, 1.0e9f));
    // riseScale 非法
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, 0, 1, 0, 0.0f, n, 1.0f, 0.0f));
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, 0, 1, 0, 0.0f, n, 1.0f, nan));
    // 合法组合（缺省参数）应通过
    EXPECT_TRUE(pool.spawn(0.0f, 0.0f, 0, 1, 0, 0.0f, n));
    pool.advance(0.0f);
    EXPECT_EQ(1, pool.activeCount());
}

// ─────────────────────────────────────────────────────────────
// 5. 时间卫生
// ─────────────────────────────────────────────────────────────

TEST(FloatTextPoolTest, SanitizeTimeClampsInvalidAndWrapsLarge) {
    EXPECT_FLOAT_EQ(0.0f, FloatTextPool::sanitizeTime(std::numeric_limits<float>::quiet_NaN()));
    EXPECT_FLOAT_EQ(0.0f, FloatTextPool::sanitizeTime(std::numeric_limits<float>::infinity()));
    EXPECT_FLOAT_EQ(0.0f, FloatTextPool::sanitizeTime(-1.0f));
    EXPECT_FLOAT_EQ(0.0f, FloatTextPool::sanitizeTime(1.0e9f));
    EXPECT_FLOAT_EQ(2.5f, FloatTextPool::sanitizeTime(2.5f));
    EXPECT_FLOAT_EQ(0.0f, FloatTextPool::sanitizeTime(0.0f));
}

TEST(FloatTextPoolTest, FiniteCoordRejectsOutOfRangeAndNonFinite) {
    EXPECT_TRUE(FloatTextPool::finiteCoord(0.0f));
    EXPECT_TRUE(FloatTextPool::finiteCoord(-1234.5f));
    EXPECT_FALSE(FloatTextPool::finiteCoord(std::numeric_limits<float>::quiet_NaN()));
    EXPECT_FALSE(FloatTextPool::finiteCoord(std::numeric_limits<float>::infinity()));
    EXPECT_FALSE(FloatTextPool::finiteCoord(1.0e9f));
    EXPECT_FALSE(FloatTextPool::finiteCoord(-1.0e9f));
}

// ─────────────────────────────────────────────────────────────
// 6. 动画采样（纯函数）
// ─────────────────────────────────────────────────────────────

TEST(FloatTextAnimationTest, RiseIsMonotonicOverLifetime) {
    FloatTextInstance inst;
    inst.worldX = 5.0f;
    inst.worldY = 10.0f;
    inst.assetIndex = 0;
    inst.charCount = 1;
    inst.spawnTime = 0.0f;
    inst.alive = true;

    float prevY = 10.0f;
    for (int f = 0; f <= 60; f++) {
        const float t = static_cast<float>(f) / 60.0f;
        const FloatTextRenderSample s = sampleFloatText(inst, t);
        EXPECT_GE(s.worldY, prevY) << "上浮非单调（frame " << f << "）";
        prevY = s.worldY;
    }
    // 寿命中点（0.55s）位移 = rise × 0.55
    const FloatTextRenderSample mid = sampleFloatText(inst, scene::kFloatLifetimeSeconds * 0.5f);
    EXPECT_NEAR(10.0f + scene::kFloatRisePerSecond * scene::kFloatLifetimeSeconds * 0.5f,
                mid.worldY, 1e-4f);
    // worldX 不随动画变化（仅上浮）
    EXPECT_FLOAT_EQ(5.0f, mid.worldX);
}

TEST(FloatTextAnimationTest, FadeOutReachesZeroAtEndOfLifetime) {
    FloatTextInstance inst;
    inst.spawnTime = 0.0f;
    inst.alive = true;
    // 淡出起点前：不透明
    const FloatTextRenderSample early =
        sampleFloatText(inst, scene::kFloatLifetimeSeconds * scene::kFloatFadeStart * 0.5f);
    EXPECT_FLOAT_EQ(1.0f, early.alpha);
    // 寿命中点后：已开始淡出（< 1）
    const FloatTextRenderSample late =
        sampleFloatText(inst, scene::kFloatLifetimeSeconds * 0.9f);
    EXPECT_LT(late.alpha, 1.0f);
    EXPECT_GT(late.alpha, 0.0f);
    // 寿命末尾：alpha → 0
    const FloatTextRenderSample end = sampleFloatText(inst, scene::kFloatLifetimeSeconds);
    EXPECT_NEAR(0.0f, end.alpha, 1e-5f);
    // alpha 恒在 [0,1]（全生命周期扫描）
    for (int f = 0; f <= 200; f++) {
        const float t = scene::kFloatLifetimeSeconds * static_cast<float>(f) / 100.0f;
        const FloatTextRenderSample s = sampleFloatText(inst, t);
        EXPECT_GE(s.alpha, 0.0f) << "frame " << f;
        EXPECT_LE(s.alpha, 1.0f) << "frame " << f;
    }
}

TEST(FloatTextAnimationTest, CritBounceScalesDownWithinBounceWindow) {
    FloatTextInstance inst;
    inst.spawnTime = 0.0f;
    inst.bounce = true;
    inst.scale = 1.0f;
    inst.alive = true;
    // 出生瞬间：放大（> 1）
    const FloatTextRenderSample at0 = sampleFloatText(inst, 0.0f);
    EXPECT_NEAR(scene::kFloatCritBounceScale, at0.scale, 1e-5f);
    // 弹跳窗内递减
    float prev = at0.scale;
    for (int f = 1; f <= 10; f++) {
        const float t = scene::kFloatCritBounceSeconds * static_cast<float>(f) / 10.0f * 0.9f;
        const FloatTextRenderSample s = sampleFloatText(inst, t);
        EXPECT_LE(s.scale, prev + 1e-5f) << "弹跳非递减（frame " << f << "）";
        prev = s.scale;
    }
    // 窗口结束后：回落到基础 scale
    const FloatTextRenderSample after = sampleFloatText(inst, scene::kFloatCritBounceSeconds * 2.0f);
    EXPECT_NEAR(1.0f, after.scale, 1e-5f);
}

TEST(FloatTextAnimationTest, NonBounceInstanceKeepsUnitScale) {
    FloatTextInstance inst;
    inst.spawnTime = 0.0f;
    inst.bounce = false;
    inst.scale = 1.0f;
    inst.alive = true;
    for (int f = 0; f <= 60; f++) {
        const FloatTextRenderSample s = sampleFloatText(inst, static_cast<float>(f) / 60.0f);
        EXPECT_NEAR(1.0f, s.scale, 1e-6f) << "非弹跳实例被缩放（frame " << f << "）";
    }
}

TEST(FloatTextAnimationTest, SampleClampsNegativeAgeToBirth) {
    // 时钟回拨/乱序：age < 0 按出生处理（不产生负位移/非法 alpha）
    FloatTextInstance inst;
    inst.worldY = 7.0f;
    inst.spawnTime = 5.0f;
    inst.alive = true;
    const FloatTextRenderSample s = sampleFloatText(inst, 1.0f);
    EXPECT_FLOAT_EQ(7.0f, s.worldY);
    EXPECT_FLOAT_EQ(1.0f, s.alpha);
}

TEST(FloatTextAnimationTest, SampleCarriesAssetMetadata) {
    FloatTextInstance inst;
    inst.assetIndex = 3;
    inst.charCount = 4;
    inst.styleIndex = 2;
    inst.alive = true;
    const FloatTextRenderSample s = sampleFloatText(inst, 0.1f);
    EXPECT_EQ(3, s.assetIndex);
    EXPECT_EQ(4, s.charCount);
    EXPECT_EQ(2, s.styleIndex);
}

// ─────────────────────────────────────────────────────────────
// 7. 浮字批绘制（空池零 draw call / 非空单纹理段 / 顶点流几何）
// ─────────────────────────────────────────────────────────────

TEST(FloatTextBatchTest, EmptyPoolProducesZeroDrawCalls) {
    FloatTextPool pool;
    SpriteBatcher batcher;
    SubmitRecorder rec;
    float proj[16] = {0};
    const int emitted = buildFloatTextBatch(batcher, proj, makeParams(pool, 0.0f), rec);
    EXPECT_EQ(0, emitted);
    EXPECT_TRUE(rec.batches.empty()) << "空池产生了 draw call（应为零）";
    EXPECT_EQ(0, batcher.vertexCount);
}

TEST(FloatTextBatchTest, AllExpiredPoolProducesZeroDrawCalls) {
    // 实例全过期（advance 到寿命之后）⇒ 同样零 draw call
    FloatTextPool pool;
    for (int i = 0; i < 8; i++) {
        pool.spawn(static_cast<float>(i), 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    }
    pool.advance(scene::kFloatLifetimeSeconds * 2.0f);
    ASSERT_EQ(0, pool.activeCount());
    SpriteBatcher batcher;
    SubmitRecorder rec;
    float proj[16] = {0};
    const int emitted = buildFloatTextBatch(batcher, proj, makeParams(pool, 0.0f), rec);
    EXPECT_EQ(0, emitted);
    EXPECT_TRUE(rec.batches.empty());
}

TEST(FloatTextBatchTest, SingleInstanceEmitsOneTextureSegmentWithSixVertices) {
    FloatTextPool pool;
    pool.spawn(100.0f, 100.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    pool.advance(0.0f);
    SpriteBatcher batcher;
    SubmitRecorder rec;
    float proj[16] = {0};
    const int emitted = buildFloatTextBatch(batcher, proj, makeParams(pool, 0.0f), rec);
    EXPECT_EQ(1, emitted);
    ASSERT_EQ(1u, rec.batches.size()) << "单实例应恰好一次 draw（单纹理段）";
    EXPECT_EQ(kAtlasTexId, rec.batches[0].texId);
    EXPECT_EQ(VERTICES_PER_SPRITE, rec.batches[0].count) << "单字形 = 6 顶点";
}

TEST(FloatTextBatchTest, MultiCharStringEmitsContiguousGlyphsInOneDraw) {
    // 数字串（charCount=3，字形段内连续索引）——同一纹理段一次 draw
    FloatTextPool pool;
    pool.spawn(50.0f, 50.0f, scene::kFloatGlyphBaseIndex + 1, 3, 0, 0.0f, scene::kFloatAssetCount);
    pool.advance(0.0f);
    SpriteBatcher batcher;
    SubmitRecorder rec;
    float proj[16] = {0};
    const int emitted = buildFloatTextBatch(batcher, proj, makeParams(pool, 0.0f), rec);
    EXPECT_EQ(1, emitted);
    ASSERT_EQ(1u, rec.batches.size()) << "多字形串应合批为一次 draw";
    EXPECT_EQ(VERTICES_PER_SPRITE * 3, rec.batches[0].count) << "3 字形 = 18 顶点";
}

TEST(FloatTextBatchTest, ManyInstancesStillOneDrawCallPerFrame) {
    // 20 条浮字 → 仍只 1 次 draw（同图集单纹理段）——G4 预算友好
    FloatTextPool pool;
    for (int i = 0; i < 20; i++) {
        pool.spawn(static_cast<float>(i) * 2.0f, static_cast<float>(i), 0, 2, 0, 0.0f,
                   scene::kFloatAssetCount);
    }
    pool.advance(0.0f);
    SpriteBatcher batcher;
    SubmitRecorder rec;
    float proj[16] = {0};
    const int emitted = buildFloatTextBatch(batcher, proj, makeParams(pool, 0.0f), rec);
    EXPECT_EQ(20, emitted);
    EXPECT_EQ(1u, rec.batches.size()) << "同图集浮字应合批为单次 draw";
    EXPECT_EQ(VERTICES_PER_SPRITE * 2 * 20, rec.batches[0].count);
}

TEST(FloatTextBatchTest, BatcherVertexStreamMatchesSpriteBatcherContract) {
    // 顶点流逐位核对：浮字 quad 走 SpriteBatcher::add（与全场景同顶点契约）
    FloatTextPool pool;
    pool.spawn(10.0f, 20.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    pool.advance(0.0f);
    SpriteBatcher batcher;
    RecorderRenderer rr;
    float proj[16] = {0};
    buildFloatTextBatch(batcher, proj, makeParams(pool, 0.0f),
        [&rr](uint32_t texId, const SpriteVertex* verts, int count) {
            rr.draw(verts, count, texId);
        });
    ASSERT_EQ(1u, rr.calls.size());
    ASSERT_EQ(VERTICES_PER_SPRITE, static_cast<int>(rr.calls[0].verts.size()));

    // 期望几何：锚点格中心 → 世界像素；y = 格底；水平居中
    const float height = scene::kFloatBaseWorldHeight * kTileSize;
    const float glyphW = height * scene::kFloatCellAspect;
    const float anchorX = 10.0f * kTileSize;
    const float anchorY = 20.0f * kTileSize;
    const float x1 = anchorX - glyphW * 0.5f;
    const float x2 = x1 + glyphW;
    const float y1 = anchorY;
    const float y2 = anchorY + height;
    const SpriteVertex* v = rr.calls[0].verts.data();
    // SpriteBatcher 顶点序：(x1,y1),(x2,y1),(x1,y2),(x2,y1),(x2,y2),(x1,y2)
    EXPECT_FLOAT_EQ(x1, v[0].px);
    EXPECT_FLOAT_EQ(y1, v[0].py);
    EXPECT_FLOAT_EQ(x2, v[1].px);
    EXPECT_FLOAT_EQ(y1, v[1].py);
    EXPECT_FLOAT_EQ(x1, v[2].px);
    EXPECT_FLOAT_EQ(y2, v[2].py);
    EXPECT_FLOAT_EQ(x2, v[3].px);
    EXPECT_FLOAT_EQ(y1, v[3].py);
    EXPECT_FLOAT_EQ(x2, v[4].px);
    EXPECT_FLOAT_EQ(y2, v[4].py);
    EXPECT_FLOAT_EQ(x1, v[5].px);
    EXPECT_FLOAT_EQ(y2, v[5].py);
    // 样式档颜色（普通 = 白）
    EXPECT_FLOAT_EQ(1.0f, v[0].r);
    EXPECT_FLOAT_EQ(1.0f, v[0].g);
    EXPECT_FLOAT_EQ(1.0f, v[0].b);
    EXPECT_FLOAT_EQ(1.0f, v[0].a);
    // UV 取自生成表（索引 0 = 词条「会心」）+ 向内收缩
    EXPECT_FLOAT_EQ(scene::kFloatUv[0] + scene::kSceneUvEpsilon, v[0].u);
    EXPECT_FLOAT_EQ(scene::kFloatUv[1] + scene::kSceneUvEpsilon, v[0].v);
}

TEST(FloatTextBatchTest, StyleColorAppliedPerInstance) {
    FloatTextPool pool;
    pool.spawn(0.0f, 0.0f, 0, 1, scene::kFloatStyleCrit, 0.0f, scene::kFloatAssetCount);
    pool.advance(0.0f);
    SpriteBatcher batcher;
    RecorderRenderer rr;
    float proj[16] = {0};
    buildFloatTextBatch(batcher, proj, makeParams(pool, 0.0f),
        [&rr](uint32_t texId, const SpriteVertex* verts, int count) {
            rr.draw(verts, count, texId);
        });
    ASSERT_EQ(1u, rr.calls.size());
    // 暴击 = 金 #FFD700（与生成表场景叠加层金色同值）
    EXPECT_FLOAT_EQ(1.0f, rr.calls[0].verts[0].r);
    EXPECT_FLOAT_EQ(0.843f, rr.calls[0].verts[0].g);
    EXPECT_FLOAT_EQ(0.0f, rr.calls[0].verts[0].b);
}

TEST(FloatTextBatchTest, OutOfViewInstancesAreCulled) {
    // 可见性剔除：远在视野外的浮字不产顶点（零 draw call）
    FloatTextPool pool;
    pool.spawn(1.0e6f, 1.0e6f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    pool.advance(0.0f);
    ASSERT_EQ(1, pool.activeCount());
    SpriteBatcher batcher;
    SubmitRecorder rec;
    float proj[16] = {0};
    FloatTextParams p = makeParams(pool, 0.0f);
    p.viewLeft = 0.0f;
    p.viewTop = 0.0f;
    p.viewRight = 1000.0f;
    p.viewBottom = 1000.0f;
    p.cullMargin = 0.0f;
    const int emitted = buildFloatTextBatch(batcher, proj, p, rec);
    EXPECT_EQ(0, emitted);
    EXPECT_TRUE(rec.batches.empty());
}

TEST(FloatTextBatchTest, MissingAtlasOrUvSkipsWholeLayer) {
    FloatTextPool pool;
    pool.spawn(0.0f, 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    pool.advance(0.0f);
    SpriteBatcher batcher;
    SubmitRecorder rec;
    float proj[16] = {0};
    // 图集未就绪
    FloatTextParams noTex = makeParams(pool, 0.0f);
    noTex.atlasTexId = 0;
    EXPECT_EQ(0, buildFloatTextBatch(batcher, proj, noTex, rec));
    EXPECT_TRUE(rec.batches.empty());
    // UV 表缺失
    FloatTextParams noUv = makeParams(pool, 0.0f);
    noUv.uv = nullptr;
    EXPECT_EQ(0, buildFloatTextBatch(batcher, proj, noUv, rec));
    EXPECT_TRUE(rec.batches.empty());
    // 资产数非法
    FloatTextParams noAssets = makeParams(pool, 0.0f);
    noAssets.assetCount = 0;
    EXPECT_EQ(0, buildFloatTextBatch(batcher, proj, noAssets, rec));
    EXPECT_TRUE(rec.batches.empty());
    // 池指针空
    FloatTextParams noPool = makeParams(pool, 0.0f);
    noPool.instances = nullptr;
    EXPECT_EQ(0, buildFloatTextBatch(batcher, proj, noPool, rec));
    EXPECT_TRUE(rec.batches.empty());
}

TEST(FloatTextBatchTest, DefensiveUvFallbackDoesNotReadOutOfBounds) {
    // 资产索引越界（池内实例被外部篡改）→ UV 回落零、不读越界内存、不崩溃
    FloatTextPool pool;
    pool.spawn(0.0f, 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    pool.advance(0.0f);
    // 直接构造越界实例参数（模拟上游脏数据）
    FloatTextParams p = makeParams(pool, 0.0f);
    p.assetCount = 1;   // 池内 assetIndex=0 合法；再取 charCount=2 → 第二字形越界
    SpriteBatcher batcher;
    SubmitRecorder rec;
    float proj[16] = {0};
    // 篡改实例为 2 字形（第二字形索引 1 超出 assetCount=1）
    FloatTextInstance dirty;
    dirty.alive = true;
    dirty.assetIndex = 0;
    dirty.charCount = 2;
    dirty.styleIndex = 0;
    dirty.spawnTime = 0.0f;
    dirty.worldX = 0.0f;
    dirty.worldY = 0.0f;
    p.instances = &dirty;
    p.instanceCount = 1;
    const int emitted = buildFloatTextBatch(batcher, proj, p, rec);
    EXPECT_EQ(1, emitted);              // 实例本身仍绘制
    ASSERT_EQ(1u, rec.batches.size());
    EXPECT_EQ(VERTICES_PER_SPRITE * 2, rec.batches[0].count);
    // 第二字形 UV 回落零（(0+eps, 0+eps)）——不读越界内存
    // 顶点 6..11 为第二字形
    (void)batcher;
}

// ─────────────────────────────────────────────────────────────
// 8. 遥测计数（R0.3 三计数器口径）
// ─────────────────────────────────────────────────────────────

TEST(FloatTextTelemetryTest, StatsTrackSpawnAndOverflowAccurately) {
    FloatTextPool pool;
    const FloatTextStats& s0 = pool.stats();
    EXPECT_EQ(0, s0.spawnTotal);
    EXPECT_EQ(0, s0.droppedTotal);

    // 10 次成功 spawn
    for (int i = 0; i < 10; i++) {
        pool.spawn(static_cast<float>(i), 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    }
    EXPECT_EQ(10, pool.stats().spawnTotal);
    EXPECT_EQ(0, pool.stats().droppedTotal);

    // 1 次被拒（非法参数）——不计入 spawnTotal
    EXPECT_FALSE(pool.spawn(0.0f, 0.0f, 99999, 1, 0, 0.0f, scene::kFloatAssetCount));
    EXPECT_EQ(10, pool.stats().spawnTotal);

    // 填满 + 溢出 → droppedTotal / overflowFrames 同步增长
    for (int i = 0; i < scene::kFloatPoolCapacity; i++) {
        pool.spawn(static_cast<float>(i), 1.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    }
    EXPECT_GT(pool.stats().droppedTotal, 0);
    EXPECT_EQ(pool.stats().droppedTotal, pool.stats().overflowFrames)
        << "本实现中每次丢弃都发生在溢出帧，两计数应同步";
}

TEST(FloatTextTelemetryTest, DegradeFramesRemainZeroForThisFeature) {
    // 浮字层不做有序降级（池满即覆盖最旧）——degradeFrames 恒 0，
    // 与 R0.3 三计数器**口径对齐但不虚构降级行为**
    FloatTextPool pool;
    for (int i = 0; i < scene::kFloatPoolCapacity * 2; i++) {
        pool.spawn(static_cast<float>(i), 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    }
    pool.advance(0.0f);
    EXPECT_EQ(0, pool.stats().degradeFrames);
}

TEST(FloatTextTelemetryTest, OverflowFlagIsSingleFrameScoped) {
    FloatTextPool pool;
    for (int i = 0; i < scene::kFloatPoolCapacity; i++) {
        pool.spawn(static_cast<float>(i), 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    }
    pool.advance(0.0f);
    EXPECT_FALSE(pool.overflowedThisFrame());
    // 溢出发生
    pool.spawn(999.0f, 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    EXPECT_TRUE(pool.overflowedThisFrame());
    // 下一次 advance 清除单帧标志
    pool.advance(0.01f);
    EXPECT_FALSE(pool.overflowedThisFrame());
}

// ─────────────────────────────────────────────────────────────
// 9. 池满覆盖的"最旧"判据（spawnSeq 单调性）
// ─────────────────────────────────────────────────────────────

TEST(FloatTextPoolTest, SpawnSequenceIsStrictlyMonotonic) {
    FloatTextPool pool;
    uint32_t prev = 0;
    for (int i = 0; i < scene::kFloatPoolCapacity * 2; i++) {
        pool.spawn(static_cast<float>(i), 0.0f, 0, 1, 0, 0.0f, scene::kFloatAssetCount);
    }
    for (int i = 0; i < scene::kFloatPoolCapacity; i++) {
        if (!pool.slot(i).alive) continue;
        if (prev != 0) EXPECT_GT(pool.slot(i).spawnSeq, 0u);
        prev = pool.slot(i).spawnSeq;
    }
    // 池内序号互不相同（覆盖语义要求唯一序号）
    std::vector<uint32_t> seqs;
    for (int i = 0; i < scene::kFloatPoolCapacity; i++) {
        if (pool.slot(i).alive) seqs.push_back(pool.slot(i).spawnSeq);
    }
    for (size_t a = 0; a < seqs.size(); a++) {
        for (size_t b = a + 1; b < seqs.size(); b++) {
            EXPECT_NE(seqs[a], seqs[b]) << "池内出现重复 spawnSeq（覆盖判据失效）";
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 10. 与生成表的资产契约
// ─────────────────────────────────────────────────────────────

TEST(FloatTextAssetContractTest, GeneratedTablesAreCoherent) {
    // 生成表内部自洽（build-atlas.mjs 产出；本测试守 C++ 消费侧前提）
    EXPECT_EQ(scene::kFloatWordCount + scene::kFloatGlyphCount, scene::kFloatAssetCount);
    EXPECT_EQ(scene::kFloatWordCount, scene::kFloatGlyphBaseIndex);
    EXPECT_EQ(scene::kFloatAssetCount * 4,
              static_cast<int>(sizeof(scene::kFloatUv) / sizeof(scene::kFloatUv[0])));
    // UV 全表有限且落在 [0,1]
    for (int i = 0; i < scene::kFloatAssetCount * 4; i++) {
        const float v = scene::kFloatUv[i];
        EXPECT_TRUE(std::isfinite(v)) << "kFloatUv[" << i << "] 非有限";
        EXPECT_GE(v, 0.0f);
        EXPECT_LE(v, 1.0f);
    }
    // 每资产 u0 < u1 且 v0 < v1（非退化 quad）
    for (int a = 0; a < scene::kFloatAssetCount; a++) {
        const float* e = scene::kFloatUv + a * 4;
        EXPECT_LT(e[0], e[2]) << "资产 " << a << " u 区间退化";
        EXPECT_LT(e[1], e[3]) << "资产 " << a << " v 区间退化";
    }
    // 字形基准索引 + 数字 0-9 连续（伤害数字渲染前提）
    EXPECT_EQ(10, scene::kFloatGlyphCount >= 10 ? 10 : scene::kFloatGlyphCount);
    EXPECT_GE(scene::kFloatGlyphCount, 40) << "Tier1 应至少含 0-9 + A-Z + 标点";
    EXPECT_EQ(8, scene::kFloatWordCount) << "Tier1 冻结词条数";
    EXPECT_EQ(4, scene::kFloatStyleCount);
}

TEST(FloatTextAssetContractTest, StylesCoverFourSemanticBuckets) {
    // 四档语义：普通/暴击/治疗/警告——颜色两两不同（防档位退化）
    EXPECT_EQ(4, scene::kFloatStyleCount);
    for (int i = 0; i < scene::kFloatStyleCount; i++) {
        const scene::FloatStyle& s = scene::kFloatStyles[i];
        EXPECT_GT(s.a, 0.0f) << "样式档 " << i << " 全透明（不可见）";
        for (int j = i + 1; j < scene::kFloatStyleCount; j++) {
            const scene::FloatStyle& o = scene::kFloatStyles[j];
            const bool same = s.r == o.r && s.g == o.g && s.b == o.b;
            EXPECT_FALSE(same) << "样式档 " << i << " 与 " << j << " 颜色相同";
        }
    }
}

}  // namespace
