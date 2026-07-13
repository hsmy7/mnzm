package com.xianxia.sect.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

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
    @Suppress("ReturnCount", "ComplexCondition", "CyclomaticComplexMethod")
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

    // ── TapTap 云游戏/沙箱环境检测 ──
    // TapTap TapSandbox 在 GPU 调用链上增加 Hook 层，vkCreateShaderModule 已知有驱动缺陷。
    // 参考：Unity Vulkan Device Filtering + Flutter Impeller 的虚拟环境处置策略。
    /**
     * 检测是否运行在 TapTap 云游戏/沙箱虚拟环境。
     *
     * 此类环境使用 GPU Hook 层拦截 Vulkan 调用，存在 vkCreateShaderModule
     * 内部 SIGSEGV 缺陷。使用 5 信号检测法，任一信号命中即认为云游戏环境。
     *
     * @see Unity Vulkan Device Filtering — Allow/Deny 列表
     * @see Flutter Impeller — API 版本门槛 + 已知问题 SoC 禁用
     * @see Chromium GPU Blocklist — Mali-G57 driver ≤ 40 blocklist
     */
    @Suppress("ReturnCount")
    private fun isTapTapCloudGaming(context: Context): Boolean {
        // 信号 1: /proc/self/maps 包含 taptap 沙箱库
        try {
            BufferedReader(FileReader("/proc/self/maps")).use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.contains("libtaptap_sandbox.so") ||
                        line.contains("libcloudgame.so")
                    ) return true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read /proc/self/maps")
        }

        // 信号 2: Build.HOST 包含 taptap/sandbox 标记
        // 注意：不使用 "cloud" 关键词，CI/CD 构建环境（如 cloudbuild）
        // 和云服务主机名可能包含 "cloud" 导致假阳性。
        val host = Build.HOST?.lowercase() ?: ""
        if (host.contains("taptap") || host.contains("tapsandbox")) return true

        // 信号 3: 包安装器来源（TapTap 分发的游戏）
        try {
            val installerPkg = context.packageManager
                .getInstallerPackageName(context.packageName)
                ?.lowercase() ?: ""
            if (installerPkg.contains("taptap")) return true
        } catch (e: Exception) { /* 忽略 */ }

        // 信号 4: SystemProperties 反射检测
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val sysPropClass = Class.forName("android.os.SystemProperties")
                val getMethod = sysPropClass.getMethod("get", String::class.java)
                for (key in listOf("persist.sys.taptap", "ro.taptap",
                        "sys.taptap")) {
                    val value = getMethod.invoke(null, key) as? String ?: continue
                    if (value.isNotBlank()) return true
                }
            } catch (e: Exception) { /* 忽略 */ }
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
    // 来源：Unreal Engine 论坛崩溃报告、Unity Issue Tracker、Flutter Issue、ANGLE 修复、
    //       ARM 驱动勘误表 (SDEN-3735689)、ARM Mali GPU compute hang 报告、Flash Slide 等行业数据
    // 参考：
    // https://forums.unrealengine.com/t/artifacts-and-crashes-on-some-android-gpus-and-versions-when-vulkan-is-enabled/2536208
    private val KNOWN_PROBLEM_GPU_PATTERNS = listOf(
        // Mali-G5x 系列（G52 MSAA 100% 崩溃 / G57 Ring Buffer 耗尽）
        Regex("mali-g(52|57|510|610)", RegexOption.IGNORE_CASE),
        // Mali-G6x/7x 系列（G68/G76 MSAA+延迟贴花 / G77 纹理数组 / G78 shared present mode）
        Regex("mali-g(68|69|72|76|77|78)", RegexOption.IGNORE_CASE),
        // Mali-G7xx 新系列（G715 compute hang / G925 PSO 编译崩溃）
        Regex("mali-g(510|610|615|710|715|720|925)", RegexOption.IGNORE_CASE),
        // Mali T8xx 系列（较旧但仍在使用）
        Regex("mali.*t(8[56]0|9[05]0)", RegexOption.IGNORE_CASE),
        // Adreno 600 系列（610/615/618/620/630/640/650/660/680 驱动异常）
        Regex("adreno.*6([1-9][05]|20|30|40|80)0", RegexOption.IGNORE_CASE),
        // Adreno 700 系列（730/740 计算着色器 bug / 750/758 写越界 / 830 内存泄漏）
        Regex("adreno.*73[0-9]", RegexOption.IGNORE_CASE),
        Regex("adreno.*75[0-9]", RegexOption.IGNORE_CASE),
        Regex("adreno.*83[0-9]", RegexOption.IGNORE_CASE),
        // PowerVR（GE8320/GM9446 计算着色器崩溃 / DXT Pixel 10 Tensor G5）
        Regex("powervr.*ge8320", RegexOption.IGNORE_CASE),
        Regex("powervr.*gm9446", RegexOption.IGNORE_CASE),
        Regex("powervr.*dxt", RegexOption.IGNORE_CASE),
        // Exynos Xclipse（940 swapchain bug / 2200 纹理闪烁）
        Regex("xclipse.*94[0-9]", RegexOption.IGNORE_CASE),
        Regex("exynos.*2200", RegexOption.IGNORE_CASE),
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
     * 3. Vulkan 崩溃专用标记 → SOFTWARE_ONLY（上次 SIGSEGV 直接标记）
     * 4. 持久化 Vulkan 初始化失败标记 → SOFTWARE_ONLY（前次运行 initDevice 返回 false）
     * 5. Phase 1 写前标记残留 → SOFTWARE_ONLY（前次 prewarm 被 SIGSEGV 杀死）
     * 6. Phase 2 写前标记残留 → SOFTWARE_ONLY（前次 initSurface 被 SIGSEGV 杀死）
     * 7. PROBLEMATIC 设备 → SOFTWARE_ONLY
     * 8. 其他 → VULKAN_PREFERRED
     */
    /**
     * 在 API < 31 设备上判断是否为已知 Vulkan 兼容性良好的设备。
     *
     * 对标 Flutter Impeller 的 API 版本门槛策略（API < 29 无条件回退 GLES）
     * 和 Unity Vulkan Device Filtering 的白名单做法。
     * 仅 Google Pixel/Nexus 和 Android One 设备在旧 API 上通过了严格的
     * CTS Vulkan 测试，驱动缺陷较少。
     */
    private fun isKnownGoodOldDevice(): Boolean {
        // Build.MANUFACTURER/Build.BRAND 是 Java 平台类型，定制 ROM 可能返回 null
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: return false
        val brand = Build.BRAND?.lowercase() ?: return false
        // Google 设备 — 原生 Android，驱动经过 CTS 认证
        if (manufacturer == "google") return true
        // Android One 设备 — Google 认证的低端设备
        if (brand == "android one") return true
        // Essential Phone — 已知 Vulkan 合规性好
        if (manufacturer == "essential") return true
        // Fairphone — 接近原生 Android
        if (manufacturer == "fairphone") return true
        // Sony Xperia — 部分型号的 Vulkan 合规性记录良好
        if (manufacturer == "sony") return true
        // Nokia (HMD Global) — Android One 项目成员
        if (manufacturer == "hmd global" || manufacturer == "nokia") return true
        return false
    }

    @Suppress("ReturnCount")
    fun getRenderStrategy(context: Context): RenderStrategy {
        // 1. 崩溃自愈安全模式
        if (CrashRecoveryEngine.isSafeMode()) {
            Log.w(TAG, "Safe mode → SOFTWARE_ONLY render strategy")
            return RenderStrategy.SOFTWARE_ONLY
        }

        // 1b. TapTap 云游戏环境检测
        // TapTap TapSandbox 在 Vulkan 调用链上增加 Hook 层，
        // vkCreateShaderModule 已知有 SIGSEGV 缺陷。
        // 参考 Flutter Impeller 模拟器禁用策略（PR #162454），
        // 云游戏等虚拟环境直接走软件渲染。
        if (isTapTapCloudGaming(context)) {
            Log.w(TAG, "TapTap cloud gaming → SOFTWARE_ONLY")
            return RenderStrategy.SOFTWARE_ONLY
        }

        // 2. Vulkan 崩溃专用标记（一次 SIGSEGV 即降级，无需累计到阈值）
        if (CrashRecoveryEngine.isVulkanCrashDetected()) {
            Log.w(TAG, "Vulkan crash detected → SOFTWARE_ONLY render strategy")
            return RenderStrategy.SOFTWARE_ONLY
        }

        // 3. 模拟器检测
        // 行业调研确认：模拟器 Vulkan 翻译层（Gfxstream/Virtio-gpu）走宿主机物理 GPU，
        // 非纯 CPU 渲染。蓝叠/MuMu/雷电均支持 Vulkan 原生命令透传（零拷贝渲染）。
        // 不应直接跳过硬件加速——仅在先前崩溃/初始化失败后才降级。
        // 参考：MuMu 12 WHPX+Vulkan 零拷贝渲染、LDPlayer 9 Vulkan 支持、Flutter Impeller 模拟器策略
        if (isEmulator()) {
            if (CrashRecoveryEngine.isVulkanCrashDetected() ||
                CrashRecoveryEngine.hasVulkanInitFailure() ||
                CrashRecoveryEngine.wasPrewarmKilled() ||
                CrashRecoveryEngine.wasSurfaceInitKilled()) {
                Log.w(TAG, "Emulator + prior Vulkan failure → SOFTWARE_ONLY")
                return RenderStrategy.SOFTWARE_ONLY
            }
            // API < 31 非白名单模拟器：即使无崩溃记录也应走软件渲染
            // Robolectric/test 环境（API 26/29/30）在非 Google 模拟器配置下
            // 使用 Vulkan passthrough 不可靠，应与非模拟器路径一致回退到软件渲染。
            if (Build.VERSION.SDK_INT < 31 && !isKnownGoodOldDevice()) {
                Log.w(TAG, "Emulator on API<31 non-whitelist → SOFTWARE_ONLY")
                return RenderStrategy.SOFTWARE_ONLY
            }
            Log.d(TAG, "Emulator → VULKAN_PREFERRED (GPU passthrough available)")
            return RenderStrategy.VULKAN_PREFERRED
        }

        // 4. 持久化 Vulkan 初始化失败标记（前次运行软失败）
        if (CrashRecoveryEngine.hasVulkanInitFailure()) {
            Log.w(TAG, "Persistent Vulkan failure → SOFTWARE_ONLY render strategy")
            return RenderStrategy.SOFTWARE_ONLY
        }

        // 5. Phase 1 写前标记残留 → 前次 prewarm 被 SIGSEGV 杀死
        if (CrashRecoveryEngine.wasPrewarmKilled()) {
            Log.w(TAG, "Previous prewarm was killed (SIGSEGV) → SOFTWARE_ONLY")
            CrashRecoveryEngine.recordVulkanInitFailure()
            return RenderStrategy.SOFTWARE_ONLY
        }

        // 6. Phase 2 写前标记残留 → 前次 initSurface/createSwapchain 被 SIGSEGV 杀死
        if (CrashRecoveryEngine.wasSurfaceInitKilled()) {
            Log.w(TAG, "Previous surface init was killed (SIGSEGV) → SOFTWARE_ONLY")
            CrashRecoveryEngine.recordVulkanInitFailure()
            return RenderStrategy.SOFTWARE_ONLY
        }

        // ── API < 31 保守策略（对标 Flutter API < 29 回退 + Unity Device Filtering） ──
        // 行业数据：Android 8-11 上 Mali/Adreno 6xx/PowerVR 等 GPU 的 Vulkan 驱动
        // 在非 Google 设备上存在广泛兼容性问题。Unity 6+ 对 Mali-G52/Mali T8xx 等
        // GPU 自动降级到 OpenGL ES。原神仅白名单设备启用 Vulkan。
        // 王者荣耀按机型分档，老旧设备直接使用 GLES 2.0。
        // 本检查在崩溃标记之后，确保：
        // - 安全模式优先（已有崩溃标记的设备）
        // - 首次启动（无标记）：非白名单设备走软件渲染
        // - Google Pixel 等已知兼容设备不受影响
        if (Build.VERSION.SDK_INT < 31) {
            if (isKnownGoodOldDevice()) {
                Log.d(TAG, "API ${Build.VERSION.SDK_INT} known-good device → VULKAN_PREFERRED")
            } else {
                Log.w(TAG, "API ${Build.VERSION.SDK_INT} non-whitelist device → SOFTWARE_ONLY")
                return RenderStrategy.SOFTWARE_ONLY
            }
        }

        // 7. 设备分级检测
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
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
    fun detectTier(context: Context): DeviceTier {
        // ★ 对抗性审查防御：Build.MODEL/Manufacturer/BOARD/HARDWARE 在定制 ROM、
        // Robolectric 测试中可能返回 null，.lowercase() 会抛出 NPE
        val model = (Build.MODEL ?: "").lowercase()
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val board = (Build.BOARD ?: "").lowercase()
        val hardware = (Build.HARDWARE ?: "").lowercase()
        val socManufacturer = if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MANUFACTURER?.lowercase() ?: ""
        } else {
            ""
        }

        // 0. Vulkan 崩溃专用标记 → PROBLEMATIC
        if (CrashRecoveryEngine.isVulkanCrashDetected()) {
            Log.w(TAG, "Vulkan crash detected — PROBLEMATIC")
            return DeviceTier.PROBLEMATIC
        }

        // 1. 持久化 Vulkan 初始化失败标记 → PROBLEMATIC
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
    @Suppress("ReturnCount")
    fun shouldDisableHardwareAcceleration(context: Context): Boolean {
        // 1. 崩溃自愈安全模式 → 强制降级
        if (CrashRecoveryEngine.isSafeMode()) {
            Log.w(TAG, "Safe mode active — disabling HW acceleration")
            return true
        }

        // 1b. TapTap 云游戏环境 → 禁用硬件加速
        if (isTapTapCloudGaming(context)) {
            Log.w(TAG, "TapTap cloud gaming — disabling HW acceleration")
            return true
        }

        // 2. Vulkan 崩溃专用标记 → 强制降级
        if (CrashRecoveryEngine.isVulkanCrashDetected()) {
            Log.w(TAG, "Vulkan crash detected — disabling HW acceleration")
            return true
        }

        // 3. API < 31 保守策略（与 getRenderStrategy 一致）
        // 行业数据：部分国产定制 ROM（如 Magic UI 5.0）可能在 Android 11 上
        // 回传 SkiaVK（Vulkan HWUI），而 Mali-G57/PowerVR 等 GPU 的 Vulkan 驱动
        // 存在广泛兼容性问题（Chromium 已全面禁止 Mali-G57 使用 Vulkan）。
        // android.graphics.renderer="skiagl" 仅在 API 31+ 被系统识别，对 API 30
        // 及以下设备无效。详见 docs/vulkan-crash-defense-design.md
        if (Build.VERSION.SDK_INT < 31) {
            if (!isKnownGoodOldDevice()) {
                Log.w(TAG,
                    "API ${Build.VERSION.SDK_INT} non-whitelist device —" +
                        " disabling HW acceleration (OEM may use SkiaVK)")
                return true
            }
            // 已知兼容设备（Google Pixel/Android One 等）使用 OpenGL ES，
            // Android 8-10 的 HWUI 固定使用 OpenGL ES，无需关闭 HW 加速
            return false
        }

        // 4. API 31-34（Android 12-14）：使用 android.graphics.renderer="skiagl"
        //    metadata 提示系统使用 OpenGL ES，硬件加速保持开启
        //    仅 API 35+（Android 15+）需要检查设备分级
        if (Build.VERSION.SDK_INT < 35) {
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
        // Build.SOC_MANUFACTURER/SOC_MODEL 是 API 31+ 字段，旧 API 需要反射兜底
        // 避免 Startup 阶段（如 Robolectric 测试/低端设备）触发 NoSuchFieldError
        if (Build.VERSION.SDK_INT >= 31) {
            sb.appendLine("SOC Manufacturer: ${Build.SOC_MANUFACTURER ?: "N/A"}")
            sb.appendLine("SOC Model: ${Build.SOC_MODEL ?: "N/A"}")
        } else {
            sb.appendLine("SOC Manufacturer: N/A (API < 31)")
            sb.appendLine("SOC Model: N/A (API < 31)")
        }
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
