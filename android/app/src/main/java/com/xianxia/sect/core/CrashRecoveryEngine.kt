package com.xianxia.sect.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

/**
 * 崩溃自愈引擎 — 渲染失败台账。
 *
 * ## 架构
 *
 * 失败持久化为**单一状态机**：
 *
 * ```
 * kill 计数        ← 启动时增量消费写前标记残留（读到残留 = 前次进程死在 native 初始化中）
 * soft-fail 计数   ← initDevice/initSurface 返回 false 的优雅失败（仅真实失败时 +1）
 * 窗口与衰减       ← 计数只在 3 天窗口内累积；14 天无失败自动清零（健康设备不被旧失败钉死）；
 *                    Vulkan 建链成功即清零（能建链已证明设备可用，崩溃循环由 kill 计数独立捕获）
 * 阈值             ← kill ≥ 3 或 soft-fail ≥ 3（窗口内）→ 下次启动 GLES_PREFERRED（仍是 GPU，
 *                    不是软件）；未达阈值 → VULKAN_PREFERRED 重试（保留「干净启动重试」意图）
 * ```
 *
 * ## 安全模式
 *
 * - **归因收窄**：只统计渲染链崩溃（NativeRenderer/VulkanInit 线程或堆栈含
 *   native-renderer/libhwui）——OOM/SDK 崩溃不再被定性为「GPU 渲染问题」；
 * - **滚动窗口**：24h 内渲染崩溃 ≥ [SAFE_MODE_RENDER_CRASH_THRESHOLD] 次才进入；
 * - **TTL**：任何持久降级态必有 TTL——安全模式 7 天自动解除重试 GPU，
 *   消灭「临时失败被永久缓存」。
 *
 * ## 设计原则
 *
 * - [SharedPreferences] 持久化，进程被杀后下次启动可读取
 * - 学习是单调状态机：增量消费 + 窗口 + 衰减 + 成功清零，
 *   无「读取前被清零」路径
 * - 附带产出：台账持久化最近一次成功探测的 GPU 设备信息（量化阈值的真实输入）
 *
 * @see VulkanPolicy 设备检测策略（消费台账的策略链）
 */
@Suppress("TooManyFunctions")
object CrashRecoveryEngine {

    private const val TAG = "CrashRecoveryEngine"

    private const val PREFS_NAME = "crash_recovery"
    private const val KEY_RENDER_SAFE_MODE = "render_safe_mode"
    private const val KEY_LAST_CRASH_TIMESTAMP = "last_crash_timestamp"
    private const val KEY_LAST_CRASH_STACK_HASH = "last_crash_stack_hash"

    // ── 写前标记（进程存活窗口内检测 SIGSEGV 级崩溃；启动时被增量消费为 kill 计数） ──

    /** 写前标记：Vulkan prewarm 开始前写入，返回后清除。残留 = 前次进程死在 prewarm 中 */
    private const val KEY_PREWARM_STARTED = "prewarm_started"
    /** 写前标记：initRenderer 开始前写入，成功后清除。残留 = 前次进程死在 surface init 中 */
    private const val KEY_SURFACE_INIT_STARTED = "surface_init_started"

    // ── Vulkan 失败台账（单向状态机） ──

    private const val KEY_VK_KILL_COUNT = "vk_kill_count"
    private const val KEY_VK_SOFT_FAIL_COUNT = "vk_soft_fail_count"
    private const val KEY_VK_LAST_FAILURE_AT = "vk_last_failure_at"
    private const val KEY_VK_LAST_FAILURE_STAGE = "vk_last_failure_stage"
    private const val KEY_GPU_VENDOR_ID = "gpu_vendor_id"
    private const val KEY_GPU_API_VERSION = "gpu_api_version"
    private const val KEY_GPU_DRIVER_VERSION = "gpu_driver_version"
    private const val KEY_GPU_DEVICE_NAME = "gpu_device_name"      // 量化阈值的真实输入

    /** 台账窗口：3 天内的失败才累积计数（窗口外重新从 1 计） */
    const val VK_FAILURE_WINDOW_MS = 3 * 24 * 3_600_000L
    /** 台账衰减：14 天无任何失败 → 清零（自动重试 Vulkan，健康设备不被旧失败钉死） */
    const val VK_FAILURE_DECAY_MS = 14 * 24 * 3_600_000L
    /** 台账阈值：kill 或 soft-fail 达到该值（窗口内）→ GLES_PREFERRED */
    const val VK_CRASH_LOOP_THRESHOLD = 3

    // ── 安全模式（归因收窄 + 滚动窗口 + TTL） ──

    private const val KEY_RENDER_CRASH_TIMESTAMPS = "render_crash_ts"
    private const val KEY_SAFE_MODE_ENTERED_AT = "safe_mode_entered_at"

    /** 触发安全模式的 24h 窗口内渲染链崩溃次数 */
    const val SAFE_MODE_RENDER_CRASH_THRESHOLD = 3
    /** 安全模式 TTL：7 天自动解除并重试 GPU 渲染 */
    const val SAFE_MODE_TTL_MS = 7 * 24 * 3_600_000L
    /** 渲染崩溃滚动窗口时长 */
    private const val RENDER_CRASH_WINDOW_MS = 24 * 3_600_000L
    /** 滚动窗口内保留的最大时间戳数（防 prefs 膨胀） */
    private const val MAX_RENDER_CRASH_STAMPS = 5

    /** 渲染链线程名——只统计这些线程的未捕获异常 */
    private val RENDER_THREAD_NAMES = setOf("NativeRenderer", "VulkanInit")

    // ── 渲染回退持久化（RenderFallbackReporter 的持久化端口） ──

    /** 最近一次渲染回退摘要（from>to|stage|elapsed|gpu|extra 单行） */
    private const val KEY_LAST_FALLBACK = "render_fallback_last"

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

    // ── 公共 API：崩溃 → 安全模式 ──

    /**
     * 记录一次崩溃（归因收窄 + 滚动窗口）。
     *
     * 应在 [Thread.UncaughtExceptionHandler.uncaughtException] 中调用
     * （CrashHandler 传 [thread.name]）。非渲染链崩溃只记时间戳、不进安全
     * 判定——OOM/SDK 崩溃不属于「GPU 渲染问题」。
     *
     * 注意：此方法可能在任意线程调用（包括 RenderThread），SharedPreferences 写自身线程安全。
     */
    fun recordCrash(stackTrace: String? = null, threadName: String? = null) {
        val p = requirePrefs()
        p.edit {
            putLong(KEY_LAST_CRASH_TIMESTAMP, System.currentTimeMillis())
            putString(KEY_LAST_CRASH_STACK_HASH, stackTrace?.hashCode().toString())
        }
        if (!isRenderAttributed(threadName, stackTrace)) {
            Log.w(TAG, "Crash recorded (non-render, not counted toward safe mode): thread=$threadName")
            return
        }
        // 滚动窗口：保留最近 24h 内的渲染崩溃时间戳（逗号分隔，最多 5 个）
        val now = System.currentTimeMillis()
        val stamps = readCrashTimestamps().filter { now - it < RENDER_CRASH_WINDOW_MS } + now
        p.edit { putString(KEY_RENDER_CRASH_TIMESTAMPS, stamps.takeLast(MAX_RENDER_CRASH_STAMPS).joinToString(",")) }
        Log.w(TAG, "Render-attributed crash recorded (${stamps.size} in 24h window)")
        if (stamps.size >= SAFE_MODE_RENDER_CRASH_THRESHOLD) {
            enterSafeMode(p)
        }
    }

    /** 渲染链归因：线程名命中或堆栈含渲染栈特征（native 桥/libhwui） */
    private fun isRenderAttributed(threadName: String?, stackTrace: String?): Boolean {
        if (threadName in RENDER_THREAD_NAMES) return true
        val s = stackTrace ?: return false
        return s.contains("native-renderer") || s.contains("libhwui") ||
            s.contains("com.xianxia.sect.core.nativebridge")
    }

    private fun readCrashTimestamps(): List<Long> =
        requirePrefs().getString(KEY_RENDER_CRASH_TIMESTAMPS, null)
            ?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    /** 24h 窗口内的渲染链崩溃次数（诊断日志用） */
    fun getRenderCrashCountInWindow(): Int {
        val now = System.currentTimeMillis()
        return readCrashTimestamps().count { now - it < RENDER_CRASH_WINDOW_MS }
    }

    /**
     * 是否处于渲染安全模式（带 7 天 TTL 自动解除）。
     *
     * 安全模式下应禁用硬件加速，使用软件渲染。TTL 到期自动 [leaveSafeMode]
     * 重试 GPU——任何持久降级态必有 TTL（根治判据 R2）。
     */
    fun isSafeMode(): Boolean {
        val p = requirePrefs()
        if (!p.getBoolean(KEY_RENDER_SAFE_MODE, false)) return false
        val enteredAt = p.getLong(KEY_SAFE_MODE_ENTERED_AT, 0L)
        if (System.currentTimeMillis() - enteredAt > SAFE_MODE_TTL_MS) {
            Log.i(TAG, "Safe mode auto-expired after 7 days — retrying GPU rendering")
            leaveSafeMode()
            return false
        }
        return true
    }

    /**
     * 应用正常启动时调用：**增量消费**写前标记残留 + 时间衰减。
     *
     * 读到残留标记 = 前次进程死在 native 初始化中 → kill+1；
     * 无残留 → 仅清标记。距上次失败超过 [VK_FAILURE_DECAY_MS] → 台账清零（自动重试 Vulkan）。
     */
    fun onCleanLaunch() {
        val p = requirePrefs()
        consumeKillMarks()
        if (System.currentTimeMillis() - p.getLong(KEY_VK_LAST_FAILURE_AT, 0L) > VK_FAILURE_DECAY_MS) {
            p.edit {
                putInt(KEY_VK_KILL_COUNT, 0)
                putInt(KEY_VK_SOFT_FAIL_COUNT, 0)
            }
            Log.i(TAG, "Failure ledger decayed (14d clean) — Vulkan retry enabled")
        }
        Log.d(TAG, "Clean launch: kill marks consumed, ledger preserved")
    }

    /**
     * 手动解除安全模式（用户点击「尝试重新启用 GPU 渲染」）。
     * 一并清除崩溃时间戳窗口与进入时刻，保证解除后从零计数。
     */
    fun leaveSafeMode() {
        requirePrefs().edit {
            remove(KEY_RENDER_SAFE_MODE)
            remove(KEY_SAFE_MODE_ENTERED_AT)
            remove(KEY_RENDER_CRASH_TIMESTAMPS)
        }
        Log.i(TAG, "Render safe mode manually disabled — crash window cleared")
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

        p.edit {
            putBoolean(KEY_RENDER_SAFE_MODE, true)
            putLong(KEY_SAFE_MODE_ENTERED_AT, System.currentTimeMillis())
        }
        Log.e(TAG, """
            ╔════════════════════════════════════════════════════════════╗
            ║  RENDER SAFE MODE ACTIVATED                                ║
            ║                                                            ║
            ║  $SAFE_MODE_RENDER_CRASH_THRESHOLD+ render-chain crashes within 24h window.    ║
            ║  Hardware acceleration will be disabled.                   ║
            ║  Auto-expires after 7 days (TTL) — GPU retry then.         ║
            ╚════════════════════════════════════════════════════════════╝
        """.trimIndent())
    }

    // ── 写前标记（进程存活窗口；由台账增量消费） ──

    /**
     * 标记 Vulkan prewarm 已开始（在调 prewarmDevice 之前调用）。
     * 进程若被 SIGSEGV 杀死标记残留，下次启动 [consumeKillMarks] 计 kill。
     */
    fun markPrewarmStarted() {
        requirePrefs().edit { putBoolean(KEY_PREWARM_STARTED, true) }
        Log.d(TAG, "Prewarm started mark set")
    }

    /** 清除 prewarm 标记（prewarmDevice 返回后调用，无论成败） */
    fun clearPrewarmStarted() {
        requirePrefs().edit { remove(KEY_PREWARM_STARTED) }
    }

    /** prewarm 写前标记是否残留 */
    fun wasPrewarmKilled(): Boolean {
        return requirePrefs().getBoolean(KEY_PREWARM_STARTED, false)
    }

    /** 标记 Vulkan surface 初始化已开始（在调 NativeBridge.initRenderer 之前调用） */
    fun markSurfaceInitStarted() {
        requirePrefs().edit { putBoolean(KEY_SURFACE_INIT_STARTED, true) }
        Log.d(TAG, "Surface init started mark set")
    }

    /** 清除 surface init 标记（initRenderer 成功后调用） */
    fun clearSurfaceInitStarted() {
        requirePrefs().edit { remove(KEY_SURFACE_INIT_STARTED) }
    }

    /** surface init 写前标记是否残留 */
    fun wasSurfaceInitKilled(): Boolean {
        return requirePrefs().getBoolean(KEY_SURFACE_INIT_STARTED, false)
    }

    // ── Vulkan 失败台账（单向状态机） ──

    /**
     * 启动时增量消费写前标记残留：读到残留 = 前次进程死在 native 初始化中 → kill+1。
     * 由 [onCleanLaunch] 调用（先于 VulkanPolicy.getRenderStrategy——GameActivity
     * 启动序列保证）。
     *
     * @return 消费到的阶段（"prewarm"/"initSurface"）；null = 无残留
     */
    fun consumeKillMarks(): String? {
        val prewarmKilled = wasPrewarmKilled()
        val surfaceKilled = wasSurfaceInitKilled()
        if (prewarmKilled) clearPrewarmStarted()
        if (surfaceKilled) clearSurfaceInitStarted()
        if (!prewarmKilled && !surfaceKilled) return null
        val stage = if (prewarmKilled) "prewarm" else "initSurface"
        bumpFailureCounter(KEY_VK_KILL_COUNT, stage)
        Log.e(TAG, "Vulkan kill mark consumed (stage=$stage) — killCount=${getVkKillCount()}")
        return stage
    }

    /**
     * 优雅失败（initDevice/initSurface 返回 false / prewarm 失败）——仅真实返回 false 时调用。
     * 与 [recordCrash] 不同：不触发安全模式，只进台账；达 [VK_CRASH_LOOP_THRESHOLD]
     * 后由 [shouldPreferGles] 在下次启动降级 GLES。
     */
    fun recordVulkanSoftFailure(stage: String) = bumpFailureCounter(KEY_VK_SOFT_FAIL_COUNT, stage)

    /**
     * Vulkan 建链成功：清零计数 + 持久化设备信息（量化阈值的真实输入）。
     * 能建链已证明设备可用——崩溃循环由 kill 计数独立捕获，此处无条件清零。
     */
    fun recordVulkanSuccess(vendorId: Int, apiVersion: Int, driverVersion: Int, deviceName: String) {
        requirePrefs().edit {
            putInt(KEY_VK_KILL_COUNT, 0)
            putInt(KEY_VK_SOFT_FAIL_COUNT, 0)
            putInt(KEY_GPU_VENDOR_ID, vendorId)
            putInt(KEY_GPU_API_VERSION, apiVersion)
            putInt(KEY_GPU_DRIVER_VERSION, driverVersion)
            putString(KEY_GPU_DEVICE_NAME, deviceName)
        }
        Log.i(TAG, "Vulkan chain succeeded — ledger cleared, gpu=$deviceName persisted")
    }

    /** kill 计数是否达到崩溃循环阈值（窗口内） */
    fun isVulkanCrashLoop(): Boolean =
        requirePrefs().getInt(KEY_VK_KILL_COUNT, 0) >= VK_CRASH_LOOP_THRESHOLD

    /**
     * 台账判定：kill ≥ 3 或 soft-fail ≥ 3（3 天窗口内）→ 下次启动 GLES_PREFERRED
     * （仍是 GPU，不是软件）。未达阈值返回 false → VULKAN_PREFERRED 重试。
     */
    fun shouldPreferGles(): Boolean {
        val p = requirePrefs()
        val kill = p.getInt(KEY_VK_KILL_COUNT, 0)
        val soft = p.getInt(KEY_VK_SOFT_FAIL_COUNT, 0)
        if (kill < VK_CRASH_LOOP_THRESHOLD && soft < VK_CRASH_LOOP_THRESHOLD) return false
        val lastAt = p.getLong(KEY_VK_LAST_FAILURE_AT, 0L)
        return System.currentTimeMillis() - lastAt < VK_FAILURE_WINDOW_MS
    }

    /** kill 计数（诊断/测试用） */
    fun getVkKillCount(): Int = requirePrefs().getInt(KEY_VK_KILL_COUNT, 0)

    /** soft-fail 计数（诊断/测试用） */
    fun getVkSoftFailCount(): Int = requirePrefs().getInt(KEY_VK_SOFT_FAIL_COUNT, 0)

    /**
     * 读取最近一次成功探测的 GPU 设备信息（无记录返回 null → 默认 Allow）。
     * VulkanPolicy.initialize 时消费——量化阈值在策略期即可生效（首装首启无历史 →
     * 本次启动 prewarm 后落台账，下次生效）。internal：消费方仅限本模块 VulkanPolicy。
     */
    internal fun readPersistedGpuInfo(): VulkanDeviceInfo? {
        val p = requirePrefs()
        val name = p.getString(KEY_GPU_DEVICE_NAME, null) ?: return null
        val vendorId = p.getInt(KEY_GPU_VENDOR_ID, -2)
        return VulkanDeviceInfo(
            vendor = GpuVendor.fromVendorId(vendorId),
            apiVersion = p.getInt(KEY_GPU_API_VERSION, 0),
            driverVersion = p.getInt(KEY_GPU_DRIVER_VERSION, 0),
            deviceName = name,
        )
    }

    /** 计数递增（窗口语义：距上次失败超过窗口 → 重新从 1 计，无需时间戳列表） */
    private fun bumpFailureCounter(key: String, stage: String) {
        val p = requirePrefs()
        val now = System.currentTimeMillis()
        val lastAt = p.getLong(KEY_VK_LAST_FAILURE_AT, 0L)
        val next = if (now - lastAt > VK_FAILURE_WINDOW_MS) 1 else p.getInt(key, 0) + 1
        p.edit {
            putInt(key, next)
            putLong(KEY_VK_LAST_FAILURE_AT, now)
            putString(KEY_VK_LAST_FAILURE_STAGE, stage)
        }
        Log.w(TAG, "Failure ledger bumped: $key=$next (stage=$stage)")
    }

    // ── 渲染回退持久化（RenderFallbackReporter 的持久化端口） ──

    /**
     * 持久化最近一次渲染回退摘要（单键覆盖写）。
     * 由 GameActivity 注入 [com.xianxia.sect.core.render.RenderFallbackReporter.persistSink]；
     * [VulkanPolicy.logDeviceDiagnostics] 启动时一并打印——事故复盘不依赖现场 logcat。
     *
     * @param summary 单行摘要 "from>to|stage|elapsedMs|gpu|extra"
     */
    fun recordLastFallback(summary: String) {
        requirePrefs().edit { putString(KEY_LAST_FALLBACK, summary) }
        Log.w(TAG, "Render fallback recorded: $summary")
    }

    /** 最近一次渲染回退摘要（无记录返回 null） */
    fun getLastFallback(): String? =
        requirePrefs().getString(KEY_LAST_FALLBACK, null)

}
