package com.xianxia.sect.core.render

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * RenderMetrics — 渲染管线健康指标（线程安全，Atomic 计数器）。
 *
 * ## 用途
 * 将渲染管线从"无声黑箱"转变为"可观测系统"。
 * 每次"当前帧是否正常渲染"都有计数可查。
 *
 * ## 使用方式
 * - Vulkan 路径: [vulkanFrames] / [totalFrames]
 * - Canvas 路径: [softwareFrames] / [totalFrames] / [lockCanvasFailed]
 * - 异常: [renderFrameNull] / [atlasBuildFailed]
 * - 崩溃上报: [formatForCrashReport()] 由 CrashHandler 写入崩溃日志
 *   （本地落盘 + 远程上传携带；[snapshot()] 为其数据来源）
 *
 * ## 线程安全
 * 所有计数器使用 AtomicLong/AtomicInteger，支持多线程并发写入。
 * FPS 滑动窗口使用 AtomicInteger index + synchronized 数组读取。
 */
object RenderMetrics {

    // ── 帧计数 ──

    /** 总帧数（Vulkan + Canvas） */
    val totalFrames = AtomicLong(0)

    /** Vulkan 路径帧数 */
    val vulkanFrames = AtomicLong(0)

    /** Canvas 软件路径帧数 */
    val softwareFrames = AtomicLong(0)

    /** Vulkan 路径装饰层被热控跳过（decorationsDisabled || qualityFactor < 0.6）的帧数 */
    val vulkanDecorSkippedFrames = AtomicLong(0)

    // ── 丢帧与异常 ──

    /** renderFrame 返回 null 次数（软件路径无有效帧输出） */
    val renderFrameNull = AtomicLong(0)

    /** lockCanvas 重试次数 */
    val lockCanvasRetries = AtomicLong(0)

    /** lockCanvas 最终失败次数（3 次重试耗尽） */
    val lockCanvasFailed = AtomicLong(0)

    // ── 图集 ──

    /** 图集构建失败次数 */
    val atlasBuildFailed = AtomicLong(0)

    /** 图集内单个精灵加载失败次数 */
    val atlasLoadSpriteFailed = AtomicLong(0)

    // ── 精灵容量溢出（R0.3，C++ SpriteBatcher 累计遥测的低频折叠）──

    /** 累计丢弃精灵数（MAX_SPRITES_PER_FRAME 容量封顶后的丢弃） */
    val spriteOverflowDropped = AtomicLong(0)

    /** 累计溢出帧数（该帧任一批量构建器发生容量丢弃） */
    val spriteOverflowFrames = AtomicLong(0)

    /** 累计降级生效帧数（溢出后跳装饰层的帧——有序降级生效信号） */
    val spriteOverflowDegradeFrames = AtomicLong(0)

    // ── FPS 滑动窗口（2 秒窗口，120 槽 @60fps） ──

    private val frameTimestamps = LongArray(120)
    private val tsIndex = AtomicInteger(0)

    /** 记录一帧（由渲染线程在每帧输出后调用） */
    fun recordFrame() {
        val i = tsIndex.getAndIncrement()
        synchronized(frameTimestamps) {
            frameTimestamps[i % frameTimestamps.size] = System.nanoTime()
        }
    }

    /** 当前 FPS（基于最近 2 秒窗口） */
    fun fps(): Float {
        val now = System.nanoTime()
        val cutoff = now - 2_000_000_000L
        var count = 0
        synchronized(frameTimestamps) {
            for (ts in frameTimestamps) {
                if (ts >= cutoff) count++
            }
        }
        return count / 2f
    }

    // ── 快照（崩溃上报用） ──

    /**
     * 渲染健康快照（崩溃报告"Render Metrics"段的字段来源）。
     *
     * 字段为崩溃时点的累计值/时窗值，用于归因"崩溃前渲染是否已异常"
     * （GPU 驱动多样性场景下真机远程排查的主线索）。
     */
    data class Snapshot(
        val totalFrames: Long,
        /** 崩溃前 2 秒滑动窗口 FPS */
        val fps: Float,
        val vulkanFrames: Long,
        val softwareFrames: Long,
        /** 软件路径占比（[softwareFrames] / [totalFrames]；total=0 时为 0） */
        val softwareRatio: Float,
        /** renderFrame 返回 null 次数（软件路径无有效帧输出） */
        val renderFrameNullCount: Long,
        /** lockCanvas 最终失败次数（3 次重试耗尽） */
        val lockCanvasFailed: Long,
        /** Vulkan 路径装饰层被热控/LOD 跳过的帧数 */
        val vulkanDecorSkippedFrames: Long,
        /** 图集构建失败次数 */
        val atlasBuildFailed: Long,
        /** 图集内单个精灵加载失败次数 */
        val atlasLoadSpriteFailed: Long,
        /** 精灵容量溢出累计丢弃数（R0.3） */
        val spriteOverflowDropped: Long,
        /** 精灵溢出降级生效帧数（R0.3） */
        val spriteOverflowDegradeFrames: Long
    )

    /**
     * 折叠 C++ 侧溢出累计计数（[NativeBridge.nativeGetSpriteOverflowStats] 低频轮询）。
     *
     * C++ 计数单调递增，Kotlin 侧取 max 单调折叠（渲染器重建不会使累计回退；
     * C++ 侧累计计数跨 surface 代际保留，仅有 resetForTest 显式清零）。
     */
    fun foldSpriteOverflowStats(droppedTotal: Long, overflowFrames: Long, degradeFrames: Long) {
        spriteOverflowDropped.updateAndGet { maxOf(it, droppedTotal) }
        spriteOverflowFrames.updateAndGet { maxOf(it, overflowFrames) }
        spriteOverflowDegradeFrames.updateAndGet { maxOf(it, degradeFrames) }
    }

    /** 获取当前指标快照（[formatForCrashReport] 的数据来源） */
    fun snapshot(): Snapshot {
        val total = totalFrames.get()
        return Snapshot(
            totalFrames = total,
            fps = fps(),
            vulkanFrames = vulkanFrames.get(),
            softwareFrames = softwareFrames.get(),
            softwareRatio = if (total > 0) softwareFrames.get().toFloat() / total else 0f,
            renderFrameNullCount = renderFrameNull.get(),
            lockCanvasFailed = lockCanvasFailed.get(),
            vulkanDecorSkippedFrames = vulkanDecorSkippedFrames.get(),
            atlasBuildFailed = atlasBuildFailed.get(),
            atlasLoadSpriteFailed = atlasLoadSpriteFailed.get(),
            spriteOverflowDropped = spriteOverflowDropped.get(),
            spriteOverflowDegradeFrames = spriteOverflowDegradeFrames.get()
        )
    }

    /**
     * 渲染健康快照的崩溃报告文本段（一行一指标，键值格式）。
     *
     * 由 CrashHandler 写入崩溃日志（本地落盘与远程上传共用同一内容）；
     * 纯内存 Atomic 读取，崩溃线程调用安全。浮点用 [java.util.Locale.US]
     * 固定小数点格式，规避区域设置差异。
     */
    fun formatForCrashReport(): String {
        val s = snapshot()
        return buildString {
            append("TotalFrames: ").append(s.totalFrames).append('\n')
            append("FPS(2s): ").append("%.1f".format(java.util.Locale.US, s.fps)).append('\n')
            append("VulkanFrames: ").append(s.vulkanFrames).append('\n')
            append("SoftwareFrames: ").append(s.softwareFrames).append('\n')
            append("SoftwareRatio: ").append("%.2f".format(java.util.Locale.US, s.softwareRatio)).append('\n')
            append("RenderFrameNull: ").append(s.renderFrameNullCount).append('\n')
            append("LockCanvasFailed: ").append(s.lockCanvasFailed).append('\n')
            append("DecorSkippedFrames: ").append(s.vulkanDecorSkippedFrames).append('\n')
            append("AtlasBuildFailed: ").append(s.atlasBuildFailed).append('\n')
            append("AtlasLoadSpriteFailed: ").append(s.atlasLoadSpriteFailed).append('\n')
            append("SpriteOverflowDropped: ").append(s.spriteOverflowDropped).append('\n')
            append("SpriteOverflowDegradeFrames: ").append(s.spriteOverflowDegradeFrames)
        }
    }

    /** 重置所有计数器（仅测试用） */
    fun resetForTest() {
        totalFrames.set(0)
        vulkanFrames.set(0)
        softwareFrames.set(0)
        vulkanDecorSkippedFrames.set(0)
        renderFrameNull.set(0)
        lockCanvasRetries.set(0)
        lockCanvasFailed.set(0)
        atlasBuildFailed.set(0)
        atlasLoadSpriteFailed.set(0)
        spriteOverflowDropped.set(0)
        spriteOverflowFrames.set(0)
        spriteOverflowDegradeFrames.set(0)
    }
}
