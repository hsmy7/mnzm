package com.xianxia.sect.core.perf

import androidx.compose.runtime.Immutable

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
 * GPU 分层渲染参数 — 每个等级对应一组渲染配置。
 *
 * 仅保留有消费者的字段：baseRenderScale 为 [RenderScalePolicy] 的
 * GPU 档位缩放上限；热控×模式画质因子由引擎
 * `renderingQualityFactor` StateFlow 聚合（见 [com.xianxia.sect.core.render.RenderScalePolicy]）。
 */
@Immutable
data class GpuRenderConfig(
    /** 基础渲染缩放上限 (1.0 = 原始分辨率) — 消费方：RenderScalePolicy.computeRenderScale */
    val baseRenderScale: Float
) {
    companion object {
        val LOW = GpuRenderConfig(baseRenderScale = 0.6f)
        val MEDIUM = GpuRenderConfig(baseRenderScale = 0.8f)
        val HIGH = GpuRenderConfig(baseRenderScale = 1.0f)
        val ULTRA = GpuRenderConfig(baseRenderScale = 1.0f)

        fun forTier(tier: GpuTier): GpuRenderConfig = when (tier) {
            GpuTier.LOW -> LOW
            GpuTier.MEDIUM -> MEDIUM
            GpuTier.HIGH -> HIGH
            GpuTier.ULTRA -> ULTRA
        }
    }
}
