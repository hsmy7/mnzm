package com.xianxia.sect.ui.game.perf

import android.opengl.GLES20
import com.xianxia.sect.core.perf.GpuTier
import com.xianxia.sect.core.util.DomainLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPU 分级检测器 — 通过 GameManager API + GLES20.glGetString(GL_RENDERER) 解析 GPU 型号并分级
 *
 * 来源: docs/huawei-performance-research.md §4.1 + docs/device-adaptation-plan.md §5 Step 5
 * 检测优先级: GameManager.getGamePerformanceClass() → GL_RENDERER 字符串匹配
 */
@Singleton
class GpuTierDetector @Inject constructor() {

    private var _detectedTier: GpuTier? = null

    /** 检测当前设备 GPU 等级（首次调用执行检测，后续返回缓存结果） */
    fun detect(): GpuTier {
        _detectedTier?.let { return it }
        val tier = detectGpuTier(null)
        _detectedTier = tier
        return tier
    }

    /** 带 Context 的检测方法，优先使用 GameManager API */
    fun detect(context: android.content.Context): GpuTier {
        _detectedTier?.let { return it }
        val tier = detectGpuTier(context)
        _detectedTier = tier
        return tier
    }

    internal fun detectGpuTier(context: android.content.Context? = null): GpuTier =
        // 优先 Android 12+ GameManager API（Elvis 短路：仅在其不可用时才走
        // EGL 上下文查询——queryGpuRenderer 创建 Pbuffer 上下文开销 ~几十 ms）
        context?.let { detectFromGameManager(it) }
            ?: EglGpuProbe.queryRenderer()?.let { classifyRenderer(it) }
            ?: GpuTier.MEDIUM // 无法检测时默认中档

    /**
     * Android 13+ GameManager API 兜底检测
     *
     * 来源: docs/device-adaptation-plan.md §5 Step 5
     * - PERFORMANCE_CLASS_UNKNOWN (0): 回退到 GL_RENDERER 检测
     * - PERFORMANCE_CLASS_LEVEL_1+ : 等级越高 → 对应更高 GPU Tier
     */
    fun detectFromGameManager(context: android.content.Context): GpuTier? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            return try {
                @Suppress("WrongConstant")
                val gm = context.getSystemService(android.content.Context.GAME_SERVICE)
                // gamePerformanceClass 是 API 33+ 的方法，通过反射调用以兼容编译
                val method = gm?.javaClass?.getMethod("getGamePerformanceClass")
                val perfClass = (method?.invoke(gm) as? Int) ?: 0
                when {
                    perfClass >= 35 -> GpuTier.ULTRA
                    perfClass >= 32 -> GpuTier.HIGH
                    perfClass >= 30 -> GpuTier.MEDIUM
                    perfClass >= 20 -> GpuTier.LOW
                    else -> null  // fallback to GL_RENDERER
                }
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    /**
     * 根据 GPU 渲染器字符串分类等级（分档规则链：ULTRA -> HIGH -> MEDIUM -> LOW ->
     * 默认中档；各档独立函数保证规则可读，规则顺序与历史实现逐条一致）。
     *
     * 覆盖 80+ SoC 型号，40+ 品牌：
     * - Qualcomm Adreno 20+ 型号
     * - ARM Mali/Immortalis 15+ 型号
     * - Samsung Xclipse 4 型号
     * - HiSilicon Maleoon 4 型号
     * - Imagination PowerVR 3 型号
     *
     * 来源: docs/device-adaptation-plan.md §3 + §5 Step 1
     */
    internal fun classifyRenderer(renderer: String): GpuTier {
        val r = renderer.lowercase()
        return classifyUltra(r)
            ?: classifyHigh(r)
            ?: classifyMedium(r)
            ?: classifyLow(r)
            ?: GpuTier.MEDIUM
    }

    /** ULTRA 档规则（GPU ~= Adreno 740+ / Immortalis 旗舰 / Apple GPU） */
    private fun classifyUltra(r: String): GpuTier? = when {
        // Adreno 旗舰最新
        r.contains("adreno 8") && (r.contains("30") || r.contains("40")) -> GpuTier.ULTRA
        r.contains("adreno 750") || r.contains("adreno 740") -> GpuTier.ULTRA
        // Immortalis 旗舰
        r.contains("immortalis-g925") || r.contains("immortalis-g720") -> GpuTier.ULTRA
        // Xclipse 旗舰
        r.contains("xclipse 950") || r.contains("xclipse 940") -> GpuTier.ULTRA
        // Apple GPU（Metal 家族渲染器串）
        r.contains("apple gpu") -> GpuTier.ULTRA
        else -> null
    }

    /** HIGH 档规则链：Adreno/Immortalis 子组 → Mali/Maleoon/Xclipse 子组 */
    private fun classifyHigh(r: String): GpuTier? = classifyHighAdrenoImmortalis(r) ?: classifyHighMali(r)

    /** HIGH 档 Adreno / Immortalis 规则 */
    private fun classifyHighAdrenoImmortalis(r: String): GpuTier? = when {
        // Adreno 高端
        r.contains("adreno 735") || r.contains("adreno 730") -> GpuTier.HIGH
        r.contains("adreno 7") && (r.contains("40") || r.contains("35")) -> GpuTier.HIGH  // 740/735
        // Immortalis/Mali 高端
        r.contains("immortalis") -> GpuTier.HIGH
        else -> null
    }

    /** HIGH 档 Mali / Maleoon / Xclipse 规则 */
    private fun classifyHighMali(r: String): GpuTier? = when {
        r.contains("mali-g715") && r.contains("mp11") -> GpuTier.HIGH
        r.contains("mali-g710") && r.contains("mc10") -> GpuTier.HIGH  // Dimensity 9000
        // Maleoon 高端（最低 6-core+）
        r.contains("maleoon 920") && r.contains("pro") -> GpuTier.HIGH  // 9030 Pro
        // Xclipse 高端
        r.contains("xclipse") && r.contains("9") -> GpuTier.HIGH  // 920/940 series
        else -> null
    }

    /** MEDIUM 档规则链：Adreno/Mali 子组 → Dimensity/Maleoon 子组 */
    private fun classifyMedium(r: String): GpuTier? = classifyMediumAdrenoMali(r) ?: classifyMediumDimensity(r)

    /** MEDIUM 档 Adreno / Mali 规则 */
    private fun classifyMediumAdrenoMali(r: String): GpuTier? = when {
        r.contains("adreno 7") || r.contains("adreno 660") || r.contains("adreno 650") -> GpuTier.MEDIUM
        r.contains("mali-g") && (r.contains("78") || r.contains("77") || r.contains("76")) -> GpuTier.MEDIUM
        r.contains("mali-g710 mc") -> GpuTier.MEDIUM  // Dimensity 9000: MC10
        else -> null
    }

    /** MEDIUM 档 Dimensity / Maleoon 规则 */
    private fun classifyMediumDimensity(r: String): GpuTier? = when {
        r.contains("maleoon") -> GpuTier.MEDIUM  // 9010, 9020 (non-Pro)
        r.contains("mali-g610 mc6") || r.contains("mali-g615 mc6") -> GpuTier.MEDIUM  // Dimensity 8200/8300
        r.contains("mali-g610 mc3") || r.contains("mali-g610 mc4") -> GpuTier.MEDIUM  // Dimensity 7200/1050
        else -> null
    }

    /** LOW 档规则（Adreno 3-6xx 中低端 / Mali G5x-G68 / Mali-T / PowerVR） */
    private fun classifyLow(r: String): GpuTier? = when {
        r.contains("adreno 6") || r.contains("adreno 5") ||
            r.contains("adreno 4") || r.contains("adreno 3") -> GpuTier.LOW
        r.contains("mali-g57") || r.contains("mali-g52") ||
            r.contains("mali-g51") || r.contains("mali-g68") -> GpuTier.LOW
        r.contains("mali-t") -> GpuTier.LOW
        r.contains("powervr") -> GpuTier.LOW
        else -> null
    }
}

/**
 * EGL Pbuffer 探测管线（自 GpuTierDetector 拆出——EGL 资源创建/销毁与 GPU 分级解耦）。
 * 仅测试环境外调用一次（GpuTierDetector.detect 缓存结果）。
 */
private object EglGpuProbe {

    /** EGL Pbuffer 资源（探测用，finally 统一销毁） */
    private class EglResources(
        val display: android.opengl.EGLDisplay,
        val context: android.opengl.EGLContext,
        val surface: android.opengl.EGLSurface
    )

    /** 在 GL 上下文中查询 GPU 渲染器名称（EGL 资源创建/销毁拆分，见 [createEglResources]） */
    fun queryRenderer(): String? {
        val egl = createEglResources() ?: return null
        return try {
            android.opengl.EGL14.eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context)
            GLES20.glGetString(GLES20.GL_RENDERER)
        } catch (_: Exception) {
            null
        } finally {
            destroyEglResources(egl)
        }
    }

    /** 逐步创建 EGL 资源（display+config → context+surface，任一步失败返回 null） */
    private fun createEglResources(): EglResources? =
        initDisplayAndConfig()?.let { (display, config) ->
            createContextAndSurface(display, config)?.let { (context, surface) ->
                EglResources(display, context, surface)
            }
        }

    /** display 初始化 + GLES2 Pbuffer 配置选择 */
    private fun initDisplayAndConfig(): Pair<android.opengl.EGLDisplay, android.opengl.EGLConfig>? =
        initEglDisplay()?.let { display ->
            chooseEglConfig(display)?.let { config -> display to config }
        }

    /** GLES2 上下文 + 1x1 Pbuffer 离屏表面创建 */
    private fun createContextAndSurface(
        display: android.opengl.EGLDisplay,
        config: android.opengl.EGLConfig
    ): Pair<android.opengl.EGLContext, android.opengl.EGLSurface>? =
        createEglContext(display, config)?.let { context ->
            createEglSurface(display, config)?.let { surface -> context to surface }
        }

    /** EGL 默认 display 初始化（无效 display / 初始化失败均返回 null） */
    private fun initEglDisplay(): android.opengl.EGLDisplay? =
        android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            .takeIf { it != android.opengl.EGL14.EGL_NO_DISPLAY }
            ?.takeIf { dpy ->
                android.opengl.EGL14.eglInitialize(dpy, IntArray(2), 0, IntArray(2), 1)
            }

    /** 选择 GLES2 Pbuffer 配置 */
    private fun chooseEglConfig(display: android.opengl.EGLDisplay): android.opengl.EGLConfig? {
        val attribList = intArrayOf(
            android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
            android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
            android.opengl.EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        val ok = android.opengl.EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)
        return if (ok) configs[0] else null
    }

    /** 创建 GLES2 上下文 */
    private fun createEglContext(
        display: android.opengl.EGLDisplay,
        config: android.opengl.EGLConfig
    ): android.opengl.EGLContext? {
        val contextAttribs = intArrayOf(
            android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            android.opengl.EGL14.EGL_NONE
        )
        val ctx = android.opengl.EGL14.eglCreateContext(
            display, config, android.opengl.EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        return if (ctx == android.opengl.EGL14.EGL_NO_CONTEXT) null else ctx
    }

    /** 创建 1x1 Pbuffer 离屏表面 */
    private fun createEglSurface(
        display: android.opengl.EGLDisplay,
        config: android.opengl.EGLConfig
    ): android.opengl.EGLSurface? {
        val surfaceAttribs = intArrayOf(
            android.opengl.EGL14.EGL_WIDTH, 1,
            android.opengl.EGL14.EGL_HEIGHT, 1,
            android.opengl.EGL14.EGL_NONE
        )
        val sfc = android.opengl.EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
        return if (sfc == android.opengl.EGL14.EGL_NO_SURFACE) null else sfc
    }

    /** EGL 资源统一销毁（makeCurrent 复位 -> surface -> context -> display；各步失败非致命） */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun destroyEglResources(egl: EglResources) {
        try {
            android.opengl.EGL14.eglMakeCurrent(
                egl.display,
                android.opengl.EGL14.EGL_NO_SURFACE,
                android.opengl.EGL14.EGL_NO_SURFACE,
                android.opengl.EGL14.EGL_NO_CONTEXT
            )
        } catch (e: Exception) {
            DomainLog.w("GpuTierDetector", "EGL eglMakeCurrent cleanup failed (non-fatal)")
        }
        runCatching { android.opengl.EGL14.eglDestroySurface(egl.display, egl.surface) }
        runCatching { android.opengl.EGL14.eglDestroyContext(egl.display, egl.context) }
        runCatching { android.opengl.EGL14.eglTerminate(egl.display) }
    }
}
