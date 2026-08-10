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
 * - 崩溃上报: [snapshot()] 由 CrashHandler 携带
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

    data class Snapshot(
        val totalFrames: Long,
        val fps: Float,
        val softwareRatio: Float,
        val droppedFrames: Long,
        val atlasFailed: Boolean
    )

    /** 获取当前指标快照，供 CrashHandler 在崩溃时携带上报 */
    fun snapshot(): Snapshot {
        val total = totalFrames.get()
        return Snapshot(
            totalFrames = total,
            fps = fps(),
            softwareRatio = if (total > 0) softwareFrames.get().toFloat() / total else 0f,
            droppedFrames = renderFrameNull.get(),
            atlasFailed = atlasBuildFailed.get() > 0
        )
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
    }
}
