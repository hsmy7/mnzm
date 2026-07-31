package com.xianxia.sect.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

/**
 * 崩溃自愈引擎 — 追踪 Native 层连续崩溃，自动进入安全模式。
 *
 * ## 工作原理
 *
 * ```
 * 正常启动 → 重置计数器
 * 崩溃 → 计数器 +1
 * 连续崩溃 N 次 → 进入安全模式
 * 安全模式下：
 *   1. 禁用硬件加速（回退软件渲染，绕过 libhwui.so Vulkan 路径）
 *   2. 显示提示对话框告知用户
 * 正常退出 → 重置计数器
 * ```
 *
 * ## 设计原则
 *
 * - 使用 [SharedPreferences] 持久化，崩溃后下次启动可读取
 * - 计数器只对**连续**崩溃敏感：一次正常启动就清零
 * - 安全模式是持久状态，直到用户主动解除或更新版本
 *
 * @see VulkanPolicy 设备检测策略
 */
@Suppress("TooManyFunctions")
object CrashRecoveryEngine {

    private const val TAG = "CrashRecoveryEngine"

    private const val PREFS_NAME = "crash_recovery"
    private const val KEY_CONSECUTIVE_CRASHES = "consecutive_crashes"
    private const val KEY_RENDER_SAFE_MODE = "render_safe_mode"
    private const val KEY_LAST_CRASH_TIMESTAMP = "last_crash_timestamp"
    private const val KEY_LAST_CRASH_STACK_HASH = "last_crash_stack_hash"
    private const val KEY_VULKAN_INIT_FAILED = "vulkan_init_failed"
    /** 写前标记：Vulkan prewarm 开始前写入，成功后清除。下次启动发现此标记 → SIGSEGV */
    private const val KEY_PREWARM_STARTED = "prewarm_started"
    /** 写前标记：Phase 2 (initSurface) 开始前写入，成功后清除。检测 initRenderer/createSwapchain SIGSEGV */
    private const val KEY_SURFACE_INIT_STARTED = "surface_init_started"
    /** Vulkan 崩溃专用标记：一旦检测到 Vulkan SIGSEGV，立即标记，不依赖计数器 */
    private const val KEY_VULKAN_CRASH_DETECTED = "vulkan_crash_detected"

    /** 触发安全模式的连续崩溃阈值（Vulkan 崩溃可预判，2 次即可触发） */
    private const val SAFE_MODE_THRESHOLD = 2

    // ── 初始化状态 ──

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * 在 [android.app.Application.onCreate] 中调用。
     * 必须在首次访问任何 API 之前调用。
     */
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.i(TAG, "CrashRecoveryEngine initialized")
    }

    private fun requirePrefs(): SharedPreferences {
        return prefs ?: error(
            "CrashRecoveryEngine not initialized. Call initialize() first.")

    }

    // ── 公共 API ──

    /**
     * 记录一次崩溃。
     *
     * 应在 [Thread.UncaughtExceptionHandler.uncaughtException] 中调用。
     * 注意：此方法可能在任意线程调用（包括 RenderThread），需保证线程安全。
     */
    fun recordCrash(stackTrace: String? = null) {
        val p = requirePrefs()
        val count = p.getInt(KEY_CONSECUTIVE_CRASHES, 0) + 1
        val stackHash = stackTrace?.let { it.hashCode().toString() }

        p.edit {
            putInt(KEY_CONSECUTIVE_CRASHES, count)
            putLong(KEY_LAST_CRASH_TIMESTAMP, System.currentTimeMillis())
            putString(KEY_LAST_CRASH_STACK_HASH, stackHash)
        }

        Log.w(TAG, "Crash recorded (#$count consecutive)")

        // 触发安全模式判定
        if (count >= SAFE_MODE_THRESHOLD) {
            enterSafeMode(p)
        }
    }

    /**
     * 是否处于渲染安全模式。
     *
     * 安全模式下应禁用硬件加速，使用软件渲染。
     */
    fun isSafeMode(): Boolean {
        return requirePrefs().getBoolean(KEY_RENDER_SAFE_MODE, false)
    }

    /**
     * 应用正常启动时调用，重置连续崩溃计数器。
     */
    fun onCleanLaunch() {
        val p = requirePrefs()
        p.edit { remove(KEY_CONSECUTIVE_CRASHES) }
        Log.d(TAG, "Consecutive crash counter reset on clean launch")
    }

    /**
     * 获取连续崩溃次数（主要用于日志和调试）
     */
    fun getConsecutiveCrashCount(): Int {
        return requirePrefs().getInt(KEY_CONSECUTIVE_CRASHES, 0)
    }

    /**
     * 手动解除安全模式（用户点击"不提示"）
     */
    fun leaveSafeMode() {
        requirePrefs().edit {
            remove(KEY_RENDER_SAFE_MODE)
            remove(KEY_CONSECUTIVE_CRASHES)
        }
        Log.i(TAG, "Render safe mode manually disabled")
    }

    /**
     * 获取上次崩溃时间戳（毫秒）
     */
    fun getLastCrashTimestamp(): Long {
        return requirePrefs().getLong(KEY_LAST_CRASH_TIMESTAMP, 0)
    }

    // ── 内部方法 ──

    private fun enterSafeMode(p: SharedPreferences) {
        if (p.getBoolean(KEY_RENDER_SAFE_MODE, false)) return // 已在安全模式

        p.edit { putBoolean(KEY_RENDER_SAFE_MODE, true) }
        Log.e(TAG, """
            ╔══════════════════════════════════════════════════════════╗
            ║  RENDER SAFE MODE ACTIVATED                             ║
            ║                                                        ║
            ║  Device has crashed $SAFE_MODE_THRESHOLD+ times consecutively  ║
            ║  due to GPU rendering issues.                          ║
            ║  Hardware acceleration will be disabled.                ║
            ║  Game performance may be reduced.                      ║
            ╚══════════════════════════════════════════════════════════╝
        """.trimIndent())
    }

    // ── Vulkan 初始化失败持久化标记 ──

    /**
     * 记录一次 Vulkan 初始化失败。
     *
     * 与 [recordCrash] 不同，此标记记录的是 Vulkan initDevice 返回 false 的软失败，
     * 不是进程崩溃。标记持久化后，后续启动可直接跳过 Vulkan 尝试。
     */
    fun recordVulkanInitFailure() {
        requirePrefs().edit { putBoolean(KEY_VULKAN_INIT_FAILED, true) }
        Log.w(TAG, "Vulkan init failure recorded — will skip Vulkan on next launch")
    }

    /**
     * 清除 Vulkan 初始化失败标记（用户手动恢复或版本更新后调用）。
     */
    fun clearVulkanInitFailure() {
        requirePrefs().edit { remove(KEY_VULKAN_INIT_FAILED) }
        Log.d(TAG, "Vulkan init failure flag cleared")
    }

    /**
     * 是否有持久化的 Vulkan 初始化失败记录。
     *
     * 返回 true 时，VulkanPolicy 应直接使用 SOFTWARE_ONLY 渲染策略，
     * 不再尝试 Vulkan 后端。
     */
    fun hasVulkanInitFailure(): Boolean {
        return requirePrefs().getBoolean(KEY_VULKAN_INIT_FAILED, false)
    }

    // ── Vulkan prewarm 写前标记（检测 SIGSEGV 级崩溃） ──

    /**
     * 标记 Vulkan prewarm 已开始（在调 initDevice 之前调用）。
     *
     * 如果 Vulkan init 导致 SIGSEGV 进程被杀死，此标记不会被清除。
     * 下次启动时 [wasPrewarmKilled] 返回 true → 直接禁用 Vulkan。
     */
    fun markPrewarmStarted() {
        requirePrefs().edit { putBoolean(KEY_PREWARM_STARTED, true) }
        Log.d(TAG, "Prewarm started mark set")
    }

    /**
     * 清除 prewarm 标记（在 prewarmDevice 成功后调用）。
     */
    fun clearPrewarmStarted() {
        requirePrefs().edit { remove(KEY_PREWARM_STARTED) }
        Log.d(TAG, "Prewarm started mark cleared")
    }

    /**
     * 上次 Vulkan prewarm 是否被 SIGSEGV 杀死（标记遗留意味着进程未正常完成）。
     */
    fun wasPrewarmKilled(): Boolean {
        return requirePrefs().getBoolean(KEY_PREWARM_STARTED, false)
    }

    // ── Phase 2 (initSurface/createSwapchain) 写前标记（检测 initRenderer 阶段 SIGSEGV） ──

    /**
     * 标记 Vulkan Surface 初始化已开始（在调 NativeBridge.initRenderer 之前调用）。
     *
     * 如果 initRenderer → createSwapchain 导致 SIGSEGV 进程被杀死，此标记不会被清除。
     * 下次启动时 [wasSurfaceInitKilled] 返回 true → 直接禁用 Vulkan。
     */
    fun markSurfaceInitStarted() {
        requirePrefs().edit { putBoolean(KEY_SURFACE_INIT_STARTED, true) }
        Log.d(TAG, "Surface init started mark set")
    }

    /**
     * 清除 surface init 标记（在 initRenderer 成功后调用）。
     */
    fun clearSurfaceInitStarted() {
        requirePrefs().edit { remove(KEY_SURFACE_INIT_STARTED) }
        Log.d(TAG, "Surface init started mark cleared")
    }

    /**
     * 上次 Vulkan Surface 初始化是否被 SIGSEGV 杀死。
     */
    fun wasSurfaceInitKilled(): Boolean {
        return requirePrefs().getBoolean(KEY_SURFACE_INIT_STARTED, false)
    }

    // ── Vulkan 崩溃专用标记（无需等待计数器，1 次即标记） ──

    /**
     * 标记一次 Vulkan SIGSEGV 崩溃。
     * 与 [recordCrash] 不同，此标记专门跟踪 Vulkan 渲染器崩溃，
     * 一次崩溃即触发 SoftwareOnly 降级，无需积累到 SAFE_MODE_THRESHOLD。
     */
    fun markVulkanCrashDetected() {
        requirePrefs().edit { putBoolean(KEY_VULKAN_CRASH_DETECTED, true) }
        Log.e(TAG, "Vulkan crash detected — will force SOFTWARE_ONLY on next launch")
    }

    /**
     * 是否有 Vulkan 崩溃记录。
     */
    fun isVulkanCrashDetected(): Boolean {
        return requirePrefs().getBoolean(KEY_VULKAN_CRASH_DETECTED, false)
    }

    /**
     * 清除 Vulkan 崩溃标记（版本更新或用户手动恢复后调用）。
     */
    fun clearVulkanCrashDetected() {
        requirePrefs().edit { remove(KEY_VULKAN_CRASH_DETECTED) }
        Log.d(TAG, "Vulkan crash detected flag cleared")
    }

}
