package com.xianxia.sect.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Vulkan 渲染策略 — 检测设备 GPU 兼容性并给出硬件加速建议。
 *
 * ## 背景
 *
 * Android 15 起 HWUI 默认使用 SkiaVK（Vulkan 后端）进行硬件加速渲染。
 * 中国厂商定制 ROM（联想 ZUXOS、vivo OriginOS、小米澎湃OS）的 Mali GPU
 * Vulkan 驱动存在广泛兼容性问题，易在 [android.view.RenderThread] 触发
 * SIGSEGV(SEGV_MAPERR) 崩溃。
 *
 * 参考行业做法（Flutter 禁用 MTK Vulkan、Unity Vulkan Device Filtering Asset、
 * 原神 GPU 白名单），此策略在已知问题设备上禁用 Vulkan 渲染路径。
 *
 * ## 设备分级
 *
 * ```
 * Level 0 (SAFE)       → 已知兼容设备 → 正常使用硬件加速
 * Level 1 (WARNING)    → 未知/可能有问题 → 日志警告，正常运行
 * Level 2 (PROBLEMATIC)→ 已知问题设备 → 建议降级或软件渲染
 * ```
 *
 * ## 参考
 *
 * - Flutter: 主动检测 MediaTek SoC 并回退到 GLES (#164126)
 * - Unity: Vulkan Device Filtering Asset (Allow/Deny 列表)
 * - Genshin Impact: GPU 厂商白名单
 * - Godot: Android 上 Vulkan→OpenGL 回退不可靠，建议直接输出 OpenGL
 *
 * @see CrashRecoveryEngine 崩溃自愈机制
 */
object VulkanPolicy {

    private const val TAG = "VulkanPolicy"

    /**
     * 硬件加速禁用决策缓存。
     * 在 XianxiaApplication.onCreate 中计算，GameActivity.onCreate 中读取。
     * 使用 @Volatile 保证多线程可见性。
     */
    @Volatile
    private var _disableAcceleration: Boolean = false

    /**
     * 初始化策略并缓存决策。
     * 必须在 Application.onCreate 中调用，在任何 Activity 启动之前。
     */
    fun initialize(context: Context) {
        _disableAcceleration = shouldDisableHardwareAcceleration(context)
        Log.i(TAG, "VulkanPolicy initialized: disable=$_disableAcceleration")
    }

    /**
     * 是否应禁用硬件加速（读取缓存的决策，无需 Context）。
     * 在 GameActivity.super.onCreate() 之前安全调用。
     */
    fun isAccelerationDisabled(): Boolean = _disableAcceleration

    // ── 已知问题机型列表（持续扩充） ──
    // 基于 Bugly 崩溃数据和行业报告维护
    private val KNOWN_PROBLEM_MODELS = setOf(
        // 联想
        "tb320fc",        // 联想平板 (ZUXOS)
        // vivo
        "v2232a",         // vivo 手机 (OriginOS+6)
        // 小米
        "23113rkc6c",     // 小米 (MIUI/澎湃OS)
        // 扩展预留
        "v2183a",         // vivo X Fold
        "v2157a",         // vivo X80
        "v2241a",         // vivo X90
        "2210132c",       // 小米 12T
        "2211133c",       // 小米 13
        "23046pnc5c",     // 小米 13T
    )

    // ── 已知问题厂商列表 ──
    // 覆盖国产主流定制 ROM 设备
    private val KNOWN_PROBLEM_MANUFACTURERS = setOf(
        "lenovo",       // 联想 ZUXOS
        "xiaomi",       // 小米 MIUI/澎湃OS
        "vivo",         // vivo OriginOS
        "oppo",         // OPPO ColorOS
        "oneplus",      // 一加 OxygenOS/ColorOS
        "realme",       // 真我 RealmeUI
        "huawei",       // 华为 HarmonyOS/EMUI
        "honor",        // 荣耀 MagicOS
        "meizu",        // 魅族 Flyme
        "smartisan",    // 锤子 SmartisanOS
        "zte",          // 中兴
        "nubia",        // 努比亚
        "samsung",      // 三星 OneUI（部分型号）
    )

    // ── 已知兼容的 SoC 前缀 ──
    // 高通 Adreno Vulkan 驱动相对成熟
    private val COMPATIBLE_SOC_PREFIXES = listOf(
        "qcom", "qualcomm", "sm8", "sm7", "sm6"
    )

    // ── 联发科 SoC 检测前缀 ──
    private val MEDIATEK_PREFIXES = listOf(
        "mt", "mediatek"
    )

    // ── 分级枚举 ──

    enum class DeviceTier(val description: String) {
        SAFE("设备兼容性良好，正常使用硬件加速"),
        WARNING("可能有兼容性问题，已记录日志"),
        PROBLEMATIC("已知问题设备，建议禁用硬件加速或降级渲染路径"),
    }

    // ── 公共 API ──

    /**
     * 检测当前设备的渲染安全等级。
     */
    fun detectTier(context: Context): DeviceTier {
        val model = Build.MODEL.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val board = Build.BOARD.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val socManufacturer = Build.SOC_MANUFACTURER?.lowercase() ?: ""

        // 1. 精确匹配已知问题机型 → PROBLEMATIC
        if (KNOWN_PROBLEM_MODELS.any { model.contains(it) }) {
            Log.w(TAG, "Device matches known problem model: $model")
            return DeviceTier.PROBLEMATIC
        }

        // 2. 检测联发科 SoC → PROBLEMATIC
        val isMediatek = MEDIATEK_PREFIXES.any { prefix ->
            board.startsWith(prefix) ||
            hardware.startsWith(prefix) ||
            socManufacturer.startsWith(prefix)
        }
        if (isMediatek) {
            Log.w(TAG, "MediaTek SoC: board=$board hw=$hardware")
            return DeviceTier.PROBLEMATIC
        }

        // 3. 检测国产厂商 + Android 15+ → WARNING
        val isAndroid15Plus = Build.VERSION.SDK_INT >= 35
        val isChineseManufacturer = KNOWN_PROBLEM_MANUFACTURERS.any {
            manufacturer.contains(it)
        }

        if (isChineseManufacturer && isAndroid15Plus) {
            Log.w(TAG, "Chinese OEM $manufacturer on Android 15+ — Vulkan risk")
            // 进一步检测是否非高通芯片（高通驱动相对较好）
            val isQualcomm = COMPATIBLE_SOC_PREFIXES.any { prefix ->
                board.startsWith(prefix) ||
                hardware.startsWith(prefix) ||
                socManufacturer.startsWith(prefix)
            }
            return if (isQualcomm) {
                DeviceTier.WARNING
            } else {
                DeviceTier.PROBLEMATIC
            }
        }

        // 4. 检查 Vulkan 功能级别
        try {
            val pm = context.packageManager
            if (pm.hasSystemFeature(
                    PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)) {
                Log.d(TAG, "Vulkan hardware feature detected")
            } else {
                Log.d(TAG, "No Vulkan feature — system uses OpenGL")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query Vulkan feature", e)
        }

        return DeviceTier.SAFE
    }

    /**
     * 是否应在该设备上禁用硬件加速。
     *
     * 合并 [VulkanPolicy] 的设备分级和 [CrashRecoveryEngine] 的安全模式。
     */
    fun shouldDisableHardwareAcceleration(context: Context): Boolean {
        // 1. 崩溃自愈安全模式 → 强制降级
        if (CrashRecoveryEngine.isSafeMode()) {
            Log.w(TAG, "Safe mode active — disabling HW acceleration")
            return true
        }

        // 2. 设备分级检测
        return when (detectTier(context)) {
            DeviceTier.PROBLEMATIC -> {
                Log.w(TAG, "Problematic device — disabling HW acceleration")
                true
            }
            DeviceTier.WARNING -> {
                Log.w(TAG, "Warning tier — keeping HW acceleration, monitoring")
                false
            }
            DeviceTier.SAFE -> false
        }
    }

    /**
     * 记录详细的设备诊断信息到日志（供 Bugly 分析）
     */
    fun logDeviceDiagnostics(context: Context) {
        val sb = StringBuilder()
        sb.appendLine("=== VulkanPolicy Device Diagnostics ===")
        sb.appendLine("Model: ${Build.MODEL}")
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("Brand: ${Build.BRAND}")
        sb.appendLine("Board: ${Build.BOARD}")
        sb.appendLine("Hardware: ${Build.HARDWARE}")
        sb.appendLine("Product: ${Build.PRODUCT}")
        sb.appendLine("Device: ${Build.DEVICE}")
        sb.appendLine("SOC Manufacturer: ${Build.SOC_MANUFACTURER ?: "N/A"}")
        sb.appendLine("SOC Model: ${Build.SOC_MODEL ?: "N/A"}")
        val sdk = Build.VERSION.SDK_INT
        val release = Build.VERSION.RELEASE
        sb.appendLine("Android: $release (SDK $sdk)")
        sb.appendLine("Tier: ${detectTier(context)}")
        sb.appendLine("Safe Mode: ${CrashRecoveryEngine.isSafeMode()}")
        val crashCount = CrashRecoveryEngine.getConsecutiveCrashCount()
        sb.appendLine("Consecutive Crashes: $crashCount")
        sb.appendLine("==========================================")
        Log.i(TAG, sb.toString())
    }
}
