package com.xianxia.sect.core.render

import com.xianxia.sect.core.perf.GpuRenderConfig
import com.xianxia.sect.core.perf.GpuTier
import kotlin.math.floor
import kotlin.math.min

/**
 * 屏幕像素面积档位 — 大屏降载分级的物理判据（按像素面积而非"是否平板"，
 * 2000×1200 的平板面积反而小于 1080p 手机，面积才是渲染负载的真实来源）。
 */
enum class ScreenPixelAreaTier {
    /** ≤2.6M 像素（≈1080×2400）— 主流手机 */
    COMPACT,

    /** ≤3.5M 像素（≈1440×2400，1.5K 高刷手机/小折叠屏内屏） */
    STANDARD,

    /** ≤5M 像素（≈2560×1600，主流平板） */
    LARGE,

    /** >5M 像素（大平板/桌面模式/折叠大屏） */
    XLARGE
}

/**
 * 渲染分辨率缩放策略 — 平板/高分辨率设备降载的唯一决策源（纯 Kotlin，零 Android 依赖）。
 *
 * 原理：渲染目标从屏幕物理分辨率下采样（Vulkan 离屏目标 + blit 上采样 / Canvas 降采样
 * 帧缓冲 + 拉伸提交），世界/相机/命中测试全部保持物理像素契约不变，缩放仅是后端内部
 * 像素密度参数。行业对标：Android 官方 Game Mode Interventions backbuffer 缩放
 * （-30% GPU 负载 / -10% 系统功耗，官方建议缩放 ≥70%）；米哈游 iPad 端同款降档做法。
 *
 * ## 档位规则
 * - COMPACT（手机）+ Vulkan：恒 1.0（零改动回归基线，热控/GPU 档均不触发缩放）
 * - COMPACT（手机）+ SOFTWARE：按 `GPU档cap × 软件路径factor` 降载——CPU 逐像素
 *   全屏合成成本远高于 GPU 路径，低端真机（联发科/麒麟等被 VulkanPolicy 判为
 *   SOFTWARE_ONLY 的国产机型）此前在手机上无任何分辨率降载手段，拖动视角卡顿；
 *   对照行业：Android Game Mode backbuffer 缩放、米哈游移动端降分辨率保帧率
 * - 非 COMPACT：`min(GPU档cap, 面积factor) × 路径factor × qualityFactor`，
 *   向下取到 0.05 离散档 + clamp [0.5, 1.0]
 * - SOFTWARE 路径额外 ×0.8（CPU 满分辨率逐像素绘制是黑名单平板耗电大头）
 * - qualityFactor 由引擎聚合热控×性能模式（[com.xianxia.sect.core.GameEngineCore]
 *   `renderingQualityFactor` StateFlow），非 COMPACT 面积与手机 SOFTWARE 路径生效
 *
 * ## 离散档与重算时机
 * 结果离散化（0.5/0.55/.../1.0），只在 surface 初始化/resize/热控/省电变化时重算，
 * 绝不做每帧动态分辨率（防画面忽清晰忽模糊的观感抖动）。
 *
 * ## 跨平台
 * iOS 直接复用本对象；Metal 对等实现 = MTKView `drawableSize = bounds × renderScale`。
 */
object RenderScalePolicy {

    /** 缩放下限（Android 官方建议 backbuffer 缩放不低于 70%，此处含质量因子叠加留 0.5 兜底） */
    const val MIN_RENDER_SCALE = 0.5f

    /** 缩放上限（1.0 = 原始分辨率） */
    const val MAX_RENDER_SCALE = 1.0f

    /** 软件渲染路径附加因子（CPU 逐像素绘制，降得更狠） */
    const val SOFTWARE_PATH_FACTOR = 0.8f

    /** qualityFactor 生效下限（热控 RED 档 0.4 与 0.5 下限叠加后不进一步模糊） */
    const val MIN_QUALITY_FACTOR = 0.4f

    /** 面积档位阈值（像素数） */
    const val PIXEL_AREA_STANDARD_MAX = 2_600_000
    const val PIXEL_AREA_LARGE_MAX = 3_500_000
    const val PIXEL_AREA_XLARGE_MAX = 5_000_000

    /**
     * 按视口物理像素分类面积档位。
     *
     * @param width 视口物理宽度（像素），≤0 时按 COMPACT 处理
     * @param height 视口物理高度（像素），≤0 时按 COMPACT 处理
     * @return 面积档位（COMPACT/STANDARD/LARGE/XLARGE）
     */
    fun classifyScreenArea(width: Int, height: Int): ScreenPixelAreaTier {
        if (width <= 0 || height <= 0) return ScreenPixelAreaTier.COMPACT
        val area = width.toLong() * height.toLong()
        return when {
            area <= PIXEL_AREA_STANDARD_MAX -> ScreenPixelAreaTier.COMPACT
            area <= PIXEL_AREA_LARGE_MAX -> ScreenPixelAreaTier.STANDARD
            area <= PIXEL_AREA_XLARGE_MAX -> ScreenPixelAreaTier.LARGE
            else -> ScreenPixelAreaTier.XLARGE
        }
    }

    /**
     * 面积档位 → 面积因子（1.0/0.9/0.8/0.7）。
     */
    fun screenFactor(tier: ScreenPixelAreaTier): Float = when (tier) {
        ScreenPixelAreaTier.COMPACT -> 1.0f
        ScreenPixelAreaTier.STANDARD -> 0.9f
        ScreenPixelAreaTier.LARGE -> 0.8f
        ScreenPixelAreaTier.XLARGE -> 0.7f
    }

    /**
     * 计算渲染缩放（唯一决策入口，双后端同一函数同一常量）。
     *
     * 档位示例：
     * - 手机 1080×2400（COMPACT）+ Vulkan 任意 GPU/路径/qualityFactor → 1.0（回归基线）
     * - 手机 + SOFTWARE：LOW → 0.5、MEDIUM → 0.6、HIGH/ULTRA → 0.8（CPU 逐像素路径降载）
     * - 平板 2560×1600 HIGH+Vulkan → 0.8；MEDIUM+Vulkan → 0.8；MEDIUM+SOFTWARE → 0.6
     * - 平板 2880×1800 ULTRA+Vulkan → 0.7；8K → 0.7（XLARGE 封顶）
     * - 平板 LARGE + qualityFactor 0.6（ORANGE 热控）→ 0.5（下限 clamp）
     *
     * @param gpuTier GPU 能力档（[GpuRenderConfig.forTier] 消费 baseRenderScale）
     * @param softwarePath true=软件渲染路径（额外 pathFactor）
     * @param screenWidth 视口物理宽度（像素）
     * @param screenHeight 视口物理高度（像素）
     * @param qualityFactor 引擎聚合画质因子（热控×性能模式，0.4–1.0；NaN/Inf 消毒为 1.0）
     * @param clarityRenderScale 玩家自选清晰度目标缩放上限（[ClarityMode.renderScaleCap]，
     *   0.5–1.0；默认 1.0 = 不改变行为）。最终值 = min(自动档, 本上限)——含 COMPACT+Vulkan
     *   早退路径也要被钳（否则玩家选低档无法压低手机默认 1.0）
     * @return 渲染缩放值，离散 0.05 档，范围 [0.5, 1.0]
     */
    fun computeRenderScale(
        gpuTier: GpuTier,
        softwarePath: Boolean,
        screenWidth: Int,
        screenHeight: Int,
        qualityFactor: Float,
        clarityRenderScale: Float = 1.0f
    ): Float {
        val screenFactor = screenFactor(classifyScreenArea(screenWidth, screenHeight))
        // COMPACT（手机）+ Vulkan 恒 1.0（回归基线）；COMPACT + SOFTWARE 仍降载——
        // CPU 逐像素全屏合成成本高，手机 SOFTWARE 此前被短路而无任何降载手段
        //（低端真机拖动视角卡顿根因，2026 修复）
        val computed = if (screenFactor >= 1.0f && !softwarePath) {
            1.0f
        } else {
            val baseCap = GpuRenderConfig.forTier(gpuTier).baseRenderScale
            val pathFactor = if (softwarePath) SOFTWARE_PATH_FACTOR else 1.0f
            val thermalFactor = if (qualityFactor.isFinite()) {
                qualityFactor.coerceIn(MIN_QUALITY_FACTOR, 1.0f)
            } else {
                1.0f
            }
            val raw = min(baseCap, screenFactor) * pathFactor * thermalFactor
            floorTo05(raw).coerceIn(MIN_RENDER_SCALE, MAX_RENDER_SCALE)
        }
        // 玩家清晰度上限（最终生效；含早退 1.0 也须被钳）
        return min(computed, clarityRenderScale.coerceIn(MIN_RENDER_SCALE, MAX_RENDER_SCALE))
    }

    /** 向下取到 0.05 离散档（防热控/省电变化引起的连续抖动） */
    internal fun floorTo05(value: Float): Float {
        if (!value.isFinite()) return MAX_RENDER_SCALE
        return floor(value * 20.0f) / 20.0f
    }
}
