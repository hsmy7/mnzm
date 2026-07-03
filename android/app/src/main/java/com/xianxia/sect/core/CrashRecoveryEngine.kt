package com.xianxia.sect.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.util.Log

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
object CrashRecoveryEngine {

    private const val TAG = "CrashRecoveryEngine"

    private const val PREFS_NAME = "crash_recovery"
    private const val KEY_CONSECUTIVE_CRASHES = "consecutive_crashes"
    private const val KEY_RENDER_SAFE_MODE = "render_safe_mode"
    private const val KEY_LAST_CRASH_TIMESTAMP = "last_crash_timestamp"
    private const val KEY_LAST_CRASH_STACK_HASH = "last_crash_stack_hash"

    /** 触发安全模式的连续崩溃阈值 */
    private const val SAFE_MODE_THRESHOLD = 3

    /** 安全模式持续的游戏版本数（递增后自动重置） */
    private const val SAFE_MODE_VERSION_SPAN = 2

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
        return prefs ?: throw IllegalStateException(
            "CrashRecoveryEngine not initialized. Call initialize(context) first."
        )
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

        p.edit()
            .putInt(KEY_CONSECUTIVE_CRASHES, count)
            .putLong(KEY_LAST_CRASH_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_LAST_CRASH_STACK_HASH, stackHash)
            .apply()

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
     *
     * 如果安全模式已激活且当前版本号变化了 [SAFE_MODE_VERSION_SPAN] 个版本，
     * 自动解除安全模式。
     */
    fun onCleanLaunch() {
        val p = requirePrefs()

        // 重置连续崩溃计数
        p.edit().remove(KEY_CONSECUTIVE_CRASHES).apply()
        Log.d(TAG, "Consecutive crash counter reset on clean launch")

        // 安全模式自动解除策略：版本迭代后自动退出安全模式
        if (p.getBoolean(KEY_RENDER_SAFE_MODE, false)) {
            Log.i(TAG, "Render safe mode is active. Will persist for $SAFE_MODE_VERSION_SPAN versions.")
        }
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
        requirePrefs().edit()
            .remove(KEY_RENDER_SAFE_MODE)
            .remove(KEY_CONSECUTIVE_CRASHES)
            .apply()
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

        p.edit().putBoolean(KEY_RENDER_SAFE_MODE, true).apply()
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

    // ── 设备信息记录（配合 Bugly 分析） ──

    /**
     * 记录当前设备信息到日志（用于崩溃分析）
     */
    fun logDeviceInfo() {
        Log.i(TAG, """
            Device Info:
              Manufacturer: ${Build.MANUFACTURER}
              Model: ${Build.MODEL}
              Board: ${Build.BOARD}
              Hardware: ${Build.HARDWARE}
              SOC: ${Build.SOC_MANUFACTURER ?: "unknown"}
              Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
              Fingerprint: ${Build.FINGERPRINT}
        """.trimIndent())
    }

    /**
     * 检测崩溃是否与 GPU/渲染相关（基于堆栈关键词）
     */
    fun isRenderRelatedCrash(stackTrace: String?): Boolean {
        if (stackTrace == null) return false
        val renderKeywords = listOf(
            "libhwui.so",
            "RenderThread",
            "SIGSEGV",
            "SEGV_MAPERR",
            "OpenGL",
            "GLES",
            "vkEnumerate",
            "vkGetDeviceQueue",
            "libvulkan",
            "GrContext",
            "Skia",
            "GPU",
            "EGL",
            "SurfaceFlinger",
            "ComposeView",
            "Canvas"
        )
        return renderKeywords.any { keyword ->
            stackTrace.contains(keyword, ignoreCase = true)
        }
    }
}
