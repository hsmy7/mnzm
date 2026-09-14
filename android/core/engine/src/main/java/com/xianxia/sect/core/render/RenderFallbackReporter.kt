package com.xianxia.sect.core.render

import android.util.Log

/**
 * 渲染回退结构化上报器。
 *
 * 此前任何一次渲染降级只有一条 "Renderer initialization failed" 式日志——
 * 无法事后回答「从哪降到哪、卡在哪个阶段、什么 GPU」。本上报器把每次回退
 * 产出为结构化事件（from/to/stage/gpu/driver/api/elapsedMs），三通道落地：
 * 1. logcat ERROR 级 `FallbackReason:` 行（现场可 grep）；
 * 2. 持久化最近一次摘要（宿主经 [persistSink] 注入——下次启动
 *    `logDeviceDiagnostics` 一并打印，事故复盘不依赖现场 logcat）；
 * 3. 遥测自定义事件 `render_fallback`（宿主经 [telemetrySink] 注入，与
 *    TapDBManager.trackEvent("game_start", …) 同通道）。
 *
 * ## 模块边界
 * 本类位于 core:engine，不能依赖 app 模块的 CrashRecoveryEngine/TapDBManager——
 * 两个 sink 由宿主（GameActivity）在启动时注入；未注入时仅 logcat（行为 = 单测
 * 与纯 engine 环境降级）。
 *
 * stage 取值来源：[errorName]（C++ RenderInitError 错误码映射）或
 * `"init_timeout"`（10s 安全网超时，见 NativeSurfaceView.handleSurfaceInitTimeout）。
 */
object RenderFallbackReporter {

    private const val TAG = "RenderFallback"

    /** 结构化回退事件（from/to = VULKAN/GLES/SOFTWARE） */
    data class Event(
        val from: String,
        val to: String,
        val stage: String,
        val elapsedMs: Long,
        val gpu: String?,
        val driverVersion: Int,
        val apiVersion: Int,
        val extra: String? = null,
    )

    /**
     * 持久化端口：宿主注入（GameActivity → CrashRecoveryEngine.recordLastFallback）。
     * 参数 = 单行摘要 "from>to|stage|elapsed|gpu|extra"。
     */
    @Volatile
    var persistSink: ((String) -> Unit)? = null

    /**
     * 遥测端口：宿主注入（GameActivity → TapDBManager.trackEvent）。
     * 参数 = (事件名, 属性表)。
     */
    @Volatile
    var telemetrySink: ((String, Map<String, Any>) -> Unit)? = null

    /** 上报一次回退（logcat + 持久化 + 遥测；任一 sink 异常吞掉不阻断主流程） */
    fun report(event: Event) {
        Log.e(
            TAG,
            "FallbackReason: ${event.from}→${event.to} stage=${event.stage} " +
                "elapsed=${event.elapsedMs}ms gpu=${event.gpu} " +
                "driver=${event.driverVersion} api=${event.apiVersion} " +
                "${event.extra ?: ""}"
        )
        try {
            persistSink?.invoke(
                "${event.from}>${event.to}|${event.stage}|${event.elapsedMs}|" +
                    "${event.gpu}|${event.extra ?: ""}"
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // 防御兜底: 宿主 sink 异常源不可枚举, 降级继续+日志留痕, 非静默吞噬
            Log.w(TAG, "fallback persist failed: ${e.message}")
        }
        try {
            telemetrySink?.invoke(
                "render_fallback",
                mapOf(
                    "from" to event.from,
                    "to" to event.to,
                    "stage" to event.stage,
                    "elapsed_ms" to event.elapsedMs,
                    "gpu" to (event.gpu ?: "unknown"),
                    "driver" to event.driverVersion,
                    "api" to event.apiVersion,
                    "extra" to (event.extra ?: ""),
                )
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // 防御兜底: 遥测 SDK 异常源不可枚举, 降级继续+日志留痕, 非静默吞噬
            Log.w(TAG, "fallback telemetry failed: ${e.message}")
        }
    }

    // object 内不能再嵌 companion object——错误码表直接放在 object 属性区

    /** Vulkan 段错误码名（索引 = code - 10，与 Rhi.h RenderInitError 10..24 一一对应） */
    private val VK_ERROR_NAMES = listOf(
        "VK_INSTANCE", "VK_PHYSICAL_DEVICE", "VK_LOGICAL_DEVICE", "VK_QUEUE",
        "VK_SURFACE", "VK_SWAPCHAIN", "VK_RENDER_PASS", "VK_OFFSCREEN",
        "VK_DESCRIPTOR_POOL", "VK_PIPELINE_LAYOUT", "VK_PIPELINE",
        "VK_SHADERS", "VK_COMMAND_POOL", "VK_DEVICE_MEMORY", "VK_FENCE",
    )

    /** GLES 段错误码名（索引 = code - 30，与 Rhi.h RenderInitError 30..35 对应） */
    private val GLES_ERROR_NAMES = listOf(
        "GLES_DISPLAY", "GLES_CONFIG", "GLES_WINDOW_SURFACE", "GLES_CONTEXT",
        "GLES_SHADER_COMPILE", "GLES_PROGRAM_LINK",
    )

    /**
     * C++ `RenderInitError` 错误码 → 稳定字符串（遥测/日志可读；映射表覆盖测试
     * 锁定，防 enum 改名/编号漂移后 stage 语义悄悄变化）。
     */
    fun errorName(code: Int): String = when {
        code == 0 -> "NONE"
        code == 1 -> "NO_WINDOW"
        code in 10..24 -> VK_ERROR_NAMES[code - 10]
        code in 30..35 -> GLES_ERROR_NAMES[code - 30]
        else -> "UNKNOWN"
    }
}
