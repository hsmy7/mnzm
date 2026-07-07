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

    /**
     * 检测当前设备是否为模拟器。
     *
     * 参考信号：
     * - Google Android Emulator 的 HARDWARE/Brand/Model 特征值
     * - ABI 包含 x86（说明 ARM native 库需经翻译层运行）
     * - libhoudini 翻译层存在（ARM→x86）
     * - Build.TAGS / FINGERPRINT 模拟器特征（MuMu/Genymotion/华为模拟器）
     *
     * @see Flutter Impeller 2025.1 模拟器 Vulkan 禁用策略
     */
    @Suppress("ReturnCount")
    fun isEmulator(): Boolean {
        // 信号 1: Build 硬件属性（Google Android Emulator / Genymotion）
        val hardware = Build.HARDWARE.lowercase()
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val device = Build.DEVICE.lowercase()

        if (hardware.contains("ranchu") || hardware.contains("goldfish") ||
            hardware.contains("vbox") || hardware.contains("virtual")) {
            return true
        }
        if (brand.startsWith("google") && (model.contains("sdk_gphone") ||
            model.contains("android sdk built for") || model.contains("emu64"))) {
            return true
        }
        if (product.contains("sdk_") || product.contains("emulator") ||
            device.contains("generic")) {
            return true
        }

        // 信号 2: ABI 包含 x86/x86_64（ARM lib 需经翻译层）
        for (abi in Build.SUPPORTED_ABIS) {
            val abiLower = abi.lowercase()
            if (abiLower.contains("x86")) return true
        }

        // 信号 3: Build.TAGS test-keys（模拟器/开发版常见）
        // 注：部分自定义 ROM 也使用 test-keys，结合其他信号判断
        val tags = Build.TAGS?.lowercase()
        val fingerprint = Build.FINGERPRINT?.lowercase()
        val type = Build.TYPE?.lowercase()
        if (tags == "test-keys" || tags?.contains("test") == true) {
            if ((type == "eng" || type == "userdebug") ||
                fingerprint?.contains("test-keys") == true ||
                fingerprint?.contains("emulator") == true) {
                return true
            }
        }

        // 信号 4: 模拟器常见指纹特征
        if (fingerprint != null) {
            if (fingerprint.contains("emulator") ||
                fingerprint.contains("sdk_google_phone") ||
                fingerprint.contains("generic_")) {
                return true
            }
        }

        // 信号 5: RADIO / BOOTLOADER 未知（模拟器无基带/引导加载器）
        // 仅当两者都未知时才确认（避免误伤 WiFi 平板）
        val radio = Build.RADIO?.lowercase()
        val bootloader = Build.BOOTLOADER?.lowercase()
        if ((radio == "unknown" || radio.isNullOrBlank()) &&
            (bootloader == "unknown" || bootloader.isNullOrBlank())) {
            // 加上 SERIAL 确认（真机一般有有效序列号，模拟器为 unknown）
            if (Build.SERIAL?.lowercase() == "unknown") {
                return true
            }
        }

        return false
    }

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

    // ── 已知问题 GPU 型号正则列表（基于行业报告持续扩充） ──
    // 来源：Unreal Engine 论坛崩溃报告、Unity Issue Tracker、Flutter Issue
    // 参考：https://forums.unrealengine.com/t/artifacts-and-crashes-on-some-android-gpus-and-versions-when-vulkan-is-enabled/2536208
    private val KNOWN_PROBLEM_GPU_PATTERNS = listOf(
        Regex("mali-g(52|57|610)", RegexOption.IGNORE_CASE),  // MSAA 100% 崩溃 / 随机崩溃
        Regex("mali-g(72|76)", RegexOption.IGNORE_CASE),      // MSAA+延迟贴花崩溃
        Regex("mali-g(77|78)", RegexOption.IGNORE_CASE),      // 纹理数组渲染崩溃
        Regex("adreno.*6(1[05]|4)0", RegexOption.IGNORE_CASE), // Adreno 610/615/640 异常
        Regex("adreno.*73[0-9]", RegexOption.IGNORE_CASE),     // Adreno 730/740 计算着色器 bug
        Regex("adreno.*75[0-9]", RegexOption.IGNORE_CASE),     // Adreno 750/758 写越界
        Regex("adreno.*83[0-9]", RegexOption.IGNORE_CASE),     // Adreno 830 内存泄漏
        Regex("powervr.*ge8320", RegexOption.IGNORE_CASE),     // PowerVR GE8320 计算着色器崩溃
        Regex("powervr.*gm9446", RegexOption.IGNORE_CASE),     // PowerVR GM9446 计算着色器崩溃
        Regex("xclipse.*94[0-9]", RegexOption.IGNORE_CASE),    // Xclipse 940 swapchain bug
        Regex("mali.*t(8[56]0|9[05]0)", RegexOption.IGNORE_CASE), // Mali T8xx 系列
    )

    // ── 分级枚举 ──

    enum class DeviceTier(val description: String) {
        SAFE("设备兼容性良好，正常使用硬件加速"),
        WARNING("可能有兼容性问题，已记录日志"),
        PROBLEMATIC("已知问题设备，建议禁用硬件加速或降级渲染路径"),
    }

    /**
     * 渲染策略枚举 — 决定宗门地图使用哪种渲染后端。
     *
     * @see NativeSurfaceView.RenderMode
     */
    enum class RenderStrategy(val description: String) {
        /** 先尝试 Vulkan，失败后自动降级到 Canvas 软件渲染 */
        VULKAN_PREFERRED("首选 Vulkan，失败后软降级"),
        /** 直接使用 Canvas 软件渲染（模拟器/崩溃自愈模式） */
        SOFTWARE_ONLY("直接使用软件渲染"),
    }

    /**
     * 获取推荐渲染策略。
     *
     * 算法：
     * 1. 崩溃自愈安全模式 → SOFTWARE_ONLY
     * 2. 模拟器检测 → SOFTWARE_ONLY（模拟器 Vulkan 在 libhoudini 翻译层下不可靠）
     * 3. 持久化 Vulkan 初始化失败标记 → SOFTWARE_ONLY（前次运行 initDevice 返回 false）
     * 4. PROBLEMATIC 设备 → SOFTWARE_ONLY
     * 5. 其他 → VULKAN_PREFERRED
     */
    fun getRenderStrategy(context: Context): RenderStrategy {
        // 1. 崩溃自愈安全模式
        if (CrashRecoveryEngine.isSafeMode()) {
            Log.w(TAG, "Safe mode → SOFTWARE_ONLY render strategy")
            return RenderStrategy.SOFTWARE_ONLY
        }

        // 2. 模拟器检测
        if (isEmulator()) {
            Log.w(TAG, "Emulator detected → SOFTWARE_ONLY render strategy")
            return RenderStrategy.SOFTWARE_ONLY
        }

        // 3. 持久化 Vulkan 初始化失败标记（前次运行软失败）
        if (CrashRecoveryEngine.hasVulkanInitFailure()) {
            Log.w(TAG, "Persistent Vulkan failure → SOFTWARE_ONLY render strategy")
            return RenderStrategy.SOFTWARE_ONLY
        }

        // 4. 写前标记残留 → 前次 prewarm 被 SIGSEGV 杀死
        if (CrashRecoveryEngine.wasPrewarmKilled()) {
            Log.w(TAG, "Previous prewarm was killed (SIGSEGV) → SOFTWARE_ONLY")
            CrashRecoveryEngine.recordVulkanInitFailure()
            return RenderStrategy.SOFTWARE_ONLY
        }

        // 5. 设备分级检测
        return when (detectTier(context)) {
            DeviceTier.PROBLEMATIC -> {
                Log.w(TAG, "PROBLEMATIC device → SOFTWARE_ONLY render strategy")
                RenderStrategy.SOFTWARE_ONLY
            }
            DeviceTier.WARNING -> {
                Log.w(TAG, "WARNING device → VULKAN_PREFERRED (with fallback)")
                RenderStrategy.VULKAN_PREFERRED
            }
            DeviceTier.SAFE -> {
                Log.d(TAG, "SAFE device → VULKAN_PREFERRED")
                RenderStrategy.VULKAN_PREFERRED
            }
        }
    }

    // ── 公共 API ──

    /**
     * 检测当前设备的渲染安全等级。
     */
    @Suppress("ReturnCount")
    fun detectTier(context: Context): DeviceTier {
        val model = Build.MODEL.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val board = Build.BOARD.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val socManufacturer = Build.SOC_MANUFACTURER?.lowercase() ?: ""
        val gpuName = ""  // GPU 名无法从 Build 属性直接获取，需从硬件渲染器查询

        // 0. 持久化 Vulkan 初始化失败标记 → PROBLEMATIC
        if (CrashRecoveryEngine.hasVulkanInitFailure()) {
            Log.w(TAG, "Persistent Vulkan init failure — PROBLEMATIC")
            return DeviceTier.PROBLEMATIC
        }

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

        // 3. 检测国产厂商 → Vulkan 兼容性判定
        // 对大多数国产厂商，即使 Android < 15，其定制 GPU 驱动的 Vulkan 实现
        // 也存在广泛兼容性问题（华为 Kirin、荣耀、vivo、OPPO、小米澎湃OS 等均有报告）。
        // 只有高通 Adreno 的驱动相对成熟，非高通芯片一律降级。
        val isChineseManufacturer = KNOWN_PROBLEM_MANUFACTURERS.any {
            manufacturer.contains(it)
        }

        if (isChineseManufacturer) {
            val isQualcomm = COMPATIBLE_SOC_PREFIXES.any { prefix ->
                board.startsWith(prefix) ||
                hardware.startsWith(prefix) ||
                socManufacturer.startsWith(prefix)
            }
            if (isQualcomm) {
                // 高通 Adreno — 相对稳定，但 Android 15+ 仍需监控
                val isAndroid15Plus = Build.VERSION.SDK_INT >= 35
                if (isAndroid15Plus) {
                    Log.w(TAG, "Qualcomm + Chinese OEM $manufacturer on Android 15+ — monitoring")
                    return DeviceTier.WARNING
                }
                // Android < 15 的高通还好，返回 SAFE
            } else {
                // 非高通国产芯片（Kirin/Exynos/Unisoc/展讯等）Vulkan 驱动普遍不可靠
                Log.w(TAG, "Non-Qualcomm Chinese OEM $manufacturer — Vulkan unreliable")
                return DeviceTier.PROBLEMATIC
            }
        }

        // 4. 检查 SoC/GPU 型号匹配已知问题列表
        // 使用 Build.SOC_MODEL（API 31+）尝试匹配已知问题 GPU 型号，
        // 作为额外防线：即使厂商未被标记为问题设备也能捕获。
        if (Build.VERSION.SDK_INT >= 31) {
            val socModel = Build.SOC_MODEL?.lowercase() ?: ""
            if (socModel.isNotEmpty()) {
                for (pattern in KNOWN_PROBLEM_GPU_PATTERNS) {
                    if (pattern.containsMatchIn(socModel)) {
                        Log.w(TAG, "SOC model matches known problem GPU: " +
                            "$socModel (pattern=${pattern.pattern})")
                        return DeviceTier.PROBLEMATIC
                    }
                }
            }
        }
        // 辅助：board/hardware 中也可能包含 GPU 信息（如 "mt6893" 含 Mali 信息）
        val hwCombined = "$board $hardware $socManufacturer".lowercase()
        for (pattern in KNOWN_PROBLEM_GPU_PATTERNS) {
            if (pattern.containsMatchIn(hwCombined)) {
                Log.w(TAG, "Hardware matches known problem GPU pattern: " +
                    "$hwCombined (pattern=${pattern.pattern})")
                return DeviceTier.PROBLEMATIC
            }
        }

        // 5. 检查 Vulkan 功能级别
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
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Failed to query Vulkan feature", e)
        }

        return DeviceTier.SAFE
    }

    /**
     * 是否应在该设备上禁用硬件加速。
     *
     * 与 [getRenderStrategy] 不同，此方法控制系统级的 HW 加速（Activity 主题）。
     * Android 15+ 的系统渲染默认使用 SkiaVK（Vulkan 后端），问题设备上需关闭。
     * Android < 15 的系统渲染使用 OpenGL ES，与 Vulkan 驱动问题无关，可保持开启。
     */
    fun shouldDisableHardwareAcceleration(context: Context): Boolean {
        // 1. 崩溃自愈安全模式 → 强制降级
        if (CrashRecoveryEngine.isSafeMode()) {
            Log.w(TAG, "Safe mode active — disabling HW acceleration")
            return true
        }

        // 2. 设备分级检测（仅 Android 15+ 需要关 HW 加速）
        val isAndroid15Plus = Build.VERSION.SDK_INT >= 35
        if (!isAndroid15Plus) {
            // Android < 15 系统使用 OpenGL ES，不受 Vulkan 驱动问题影响
            return false
        }

        return when (detectTier(context)) {
            DeviceTier.PROBLEMATIC -> {
                Log.w(TAG, "Problematic device on Android 15+ — disabling HW acceleration")
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
