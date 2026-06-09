package com.xianxia.sect.core.perf

import android.opengl.GLES20
import androidx.compose.runtime.Immutable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPU 能力等级 — 用于分层渲染策略
 *
 * | 等级  | 典型 GPU                               |
 * |-------|----------------------------------------|
 * | LOW   | Mali G52/G57, 低端 Adreno 5xx          |
 * | MEDIUM| Mali G76/G77, Adreno 6xx               |
 * | HIGH  | Mali G78/G710, Adreno 7xx              |
 * | ULTRA | Adreno 8xx, Maleoon 910+               |
 */
enum class GpuTier {
    LOW, MEDIUM, HIGH, ULTRA
}

/**
 * GPU 分层渲染参数 — 每个等级对应一组渲染配置
 *
 * 来源: docs/huawei-performance-research.md §4.2
 */
@Immutable
data class GpuRenderConfig(
    /** 地图分辨率 (cells) — LOW=24, MEDIUM=32, HIGH/ULTRA=48 */
    val mapResolution: Int,
    /** 是否启用建筑烘焙 */
    val bakeBuildings: Boolean,
    /** Bitmap 格式: true=ARGB_8888, false=RGB_565 */
    val useArgb8888: Boolean,
    /** 基础渲染缩放 (1.0 = 原始分辨率) */
    val baseRenderScale: Float,
    /** 是否绘制树木装饰 */
    val showTrees: Boolean,
    /** 网格线模式: "full"=完整, "border"=仅边界 */
    val gridLineMode: String,
    /** 光环效果模式: "full"=完整, "simple"=简化(仅圆形轮廓), "off"=禁用 */
    val auraEffectMode: String,
    /** 粒子特效模式: "full"=完整, "simple"=简化, "off"=禁用 */
    val particleEffectMode: String,
    /** 纹理 LOD 偏移: 正值=更模糊(省显存), 0=默认, 负值=更清晰 */
    val textureLodOffset: Int
) {
    companion object {
        val LOW = GpuRenderConfig(
            mapResolution = 24,
            bakeBuildings = false,
            useArgb8888 = false,
            baseRenderScale = 0.6f,
            showTrees = false,
            gridLineMode = "border",
            auraEffectMode = "off",
            particleEffectMode = "off",
            textureLodOffset = 1
        )
        val MEDIUM = GpuRenderConfig(
            mapResolution = 32,
            bakeBuildings = true,
            useArgb8888 = false,
            baseRenderScale = 0.8f,
            showTrees = true,
            gridLineMode = "full",
            auraEffectMode = "simple",
            particleEffectMode = "simple",
            textureLodOffset = 0
        )
        val HIGH = GpuRenderConfig(
            mapResolution = 48,
            bakeBuildings = true,
            useArgb8888 = true,
            baseRenderScale = 1.0f,
            showTrees = true,
            gridLineMode = "full",
            auraEffectMode = "full",
            particleEffectMode = "full",
            textureLodOffset = 0
        )
        val ULTRA = GpuRenderConfig(
            mapResolution = 48,
            bakeBuildings = true,
            useArgb8888 = true,
            baseRenderScale = 1.0f,
            showTrees = true,
            gridLineMode = "full",
            auraEffectMode = "full",
            particleEffectMode = "full",
            textureLodOffset = -1
        )

        fun forTier(tier: GpuTier): GpuRenderConfig = when (tier) {
            GpuTier.LOW -> LOW
            GpuTier.MEDIUM -> MEDIUM
            GpuTier.HIGH -> HIGH
            GpuTier.ULTRA -> ULTRA
        }
    }
}

/**
 * 温控阈值分级 — 按 GpuTier 设置不同温控下的 renderScale
 *
 * 来源: docs/huawei-performance-research.md §4.5
 */
fun thermalRenderScale(gpuTier: GpuTier, thermalState: ThermalState): Float {
    val map = when (gpuTier) {
        GpuTier.LOW -> mapOf(
            ThermalState.NORMAL to 0.7f,
            ThermalState.LIGHT to 0.5f,
            ThermalState.MODERATE to 0.4f,
            ThermalState.SEVERE to 0.3f,
            ThermalState.EMERGENCY to 0.25f
        )
        GpuTier.MEDIUM -> mapOf(
            ThermalState.NORMAL to 0.9f,
            ThermalState.LIGHT to 0.7f,
            ThermalState.MODERATE to 0.5f,
            ThermalState.SEVERE to 0.4f,
            ThermalState.EMERGENCY to 0.3f
        )
        GpuTier.HIGH -> mapOf(
            ThermalState.NORMAL to 1.0f,
            ThermalState.LIGHT to 0.85f,
            ThermalState.MODERATE to 0.65f,
            ThermalState.SEVERE to 0.5f,
            ThermalState.EMERGENCY to 0.4f
        )
        GpuTier.ULTRA -> mapOf(
            ThermalState.NORMAL to 1.0f,
            ThermalState.LIGHT to 0.9f,
            ThermalState.MODERATE to 0.75f,
            ThermalState.SEVERE to 0.6f,
            ThermalState.EMERGENCY to 0.5f
        )
    }
    return map[thermalState] ?: 1.0f
}

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

    internal fun detectGpuTier(context: android.content.Context? = null): GpuTier {
        // 优先使用 Android 12+ GameManager API
        context?.let { detectFromGameManager(it)?.let { tier -> return tier } }
        val renderer = queryGpuRenderer() ?: return GpuTier.MEDIUM // 无法检测时默认中档
        return classifyRenderer(renderer)
    }

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

    /** 在 GL 上下文中查询 GPU 渲染器名称 */
    private fun queryGpuRenderer(): String? {
        var display: android.opengl.EGLDisplay? = null
        var context: android.opengl.EGLContext? = null
        var surface: android.opengl.EGLSurface? = null
        return try {
            val dpy = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            if (dpy == android.opengl.EGL14.EGL_NO_DISPLAY) return null
            display = dpy

            val version = IntArray(2)
            if (!android.opengl.EGL14.eglInitialize(display, version, 0, version, 1)) return null

            val attribList = intArrayOf(
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                android.opengl.EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!android.opengl.EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)) {
                return null
            }

            val contextAttribs = intArrayOf(
                android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                android.opengl.EGL14.EGL_NONE
            )
            val ctx = android.opengl.EGL14.eglCreateContext(
                display, configs[0], android.opengl.EGL14.EGL_NO_CONTEXT, contextAttribs, 0
            )
            if (ctx == android.opengl.EGL14.EGL_NO_CONTEXT) return null
            context = ctx

            val surfaceAttribs = intArrayOf(
                android.opengl.EGL14.EGL_WIDTH, 1,
                android.opengl.EGL14.EGL_HEIGHT, 1,
                android.opengl.EGL14.EGL_NONE
            )
            val sfc = android.opengl.EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
            if (sfc == android.opengl.EGL14.EGL_NO_SURFACE) return null
            surface = sfc

            android.opengl.EGL14.eglMakeCurrent(display, surface, surface, context)
            GLES20.glGetString(GLES20.GL_RENDERER)
        } catch (_: Exception) {
            null
        } finally {
            try { android.opengl.EGL14.eglMakeCurrent(display ?: android.opengl.EGL14.EGL_NO_DISPLAY, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT) } catch (_: Exception) {}
            try { surface?.let { android.opengl.EGL14.eglDestroySurface(display, it) } } catch (_: Exception) {}
            try { context?.let { android.opengl.EGL14.eglDestroyContext(display, it) } } catch (_: Exception) {}
            try { display?.let { android.opengl.EGL14.eglTerminate(it) } } catch (_: Exception) {}
        }
    }

    /**
     * 根据 GPU 渲染器字符串分类等级
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

        // ===== ULTRA TIER (GPU ~= Adreno 740+) =====
        when {
            // Adreno 旗舰最新
            r.contains("adreno 8") && (r.contains("30") || r.contains("40")) -> return GpuTier.ULTRA
            r.contains("adreno 750") || r.contains("adreno 740") -> return GpuTier.ULTRA
            // Immortalis 旗舰
            r.contains("immortalis-g925") || r.contains("immortalis-g720") -> return GpuTier.ULTRA
            // Xclipse 旗舰
            r.contains("xclipse 950") || r.contains("xclipse 940") -> return GpuTier.ULTRA
        }

        // ===== HIGH TIER =====
        when {
            // Adreno 高端
            r.contains("adreno 735") || r.contains("adreno 730") -> return GpuTier.HIGH
            r.contains("adreno 7") && (r.contains("40") || r.contains("35")) -> return GpuTier.HIGH  // 740/735
            // Immortalis/Mali 高端
            r.contains("immortalis") -> return GpuTier.HIGH
            r.contains("mali-g715") && r.contains("mp11") -> return GpuTier.HIGH
            r.contains("mali-g710") && r.contains("mc10") -> return GpuTier.HIGH  // Dimensity 9000
            // Maleoon 高端（最低 6-core+）
            r.contains("maleoon 920") && r.contains("pro") -> return GpuTier.HIGH  // 9030 Pro
            // Xclipse 高端
            r.contains("xclipse") && r.contains("9") -> return GpuTier.HIGH  // 920/940 series
        }

        // ===== MEDIUM TIER =====
        when {
            r.contains("adreno 7") || r.contains("adreno 660") || r.contains("adreno 650") -> return GpuTier.MEDIUM
            r.contains("mali-g") && (r.contains("78") || r.contains("77") || r.contains("76")) -> return GpuTier.MEDIUM
            r.contains("mali-g710 mc") -> return GpuTier.MEDIUM  // Dimensity 9000: MC10
            r.contains("maleoon") -> return GpuTier.MEDIUM  // 9010, 9020 (non-Pro)
            r.contains("mali-g610 mc6") || r.contains("mali-g615 mc6") -> return GpuTier.MEDIUM  // Dimensity 8200/8300
            r.contains("mali-g610 mc3") || r.contains("mali-g610 mc4") -> return GpuTier.MEDIUM  // Dimensity 7200/1050
        }

        // ===== LOW TIER =====
        when {
            r.contains("adreno 6") || r.contains("adreno 5") || r.contains("adreno 4") || r.contains("adreno 3") -> return GpuTier.LOW
            r.contains("mali-g57") || r.contains("mali-g52") || r.contains("mali-g51") || r.contains("mali-g68") -> return GpuTier.LOW
            r.contains("mali-t") -> return GpuTier.LOW
            r.contains("powervr") -> return GpuTier.LOW
        }

        // Apple GPU
        when {
            r.contains("apple gpu") -> return GpuTier.ULTRA
        }

        // 未知 GPU 默认中档
        return GpuTier.MEDIUM
    }
}
