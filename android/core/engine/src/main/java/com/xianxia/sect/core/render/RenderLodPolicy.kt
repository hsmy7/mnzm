package com.xianxia.sect.core.render

/**
 * 渲染 LOD（细节层次）策略 — 纯函数：装饰层是否应绘制。
 *
 * ## 设计
 * 装饰层（草/树）在以下任一条件下降级跳过：
 * - [decorationsDisabled]（热控/省电显式关闭）
 * - qualityFactor < [DECOR_QUALITY_THRESHOLD]（热控质量因子下降）
 * - scale < [DECOR_ZOOM_THRESHOLD]（缩得太小——装饰像素过密无意义，缩小时
 *   纹理采样降级更省 CPU/GPU，视觉差异几乎不可见）
 *
 * 三个条件收敛于同一判定（渲染层"一处比较"），chunk 失效路径以该布尔值
 * 变化为界：档位内变化（如 0.5→0.7 跨档）才重建，浮点微动不触发重建防抖动。
 *
 * ## 双端对齐
 * C++ drawAllTiles skipDecor 判定与本函数同阈值同语义（0.6 与 Canvas
 * 帧缓冲 RGB_565 降级阈值同常量）——修改任一侧必须同步另一侧。
 */
object RenderLodPolicy {

    /** 装饰层质量因子阈值：qualityFactor < 0.6 时降级（与帧缓冲 RGB_565 阈值同常量） */
    const val DECOR_QUALITY_THRESHOLD = 0.6f

    /** 装饰层缩放阈值：scale < 0.6 时降级（双端对齐常量） */
    const val DECOR_ZOOM_THRESHOLD = 0.6f

    /**
     * 判定装饰层是否应绘制。
     *
     * @param scale 相机缩放（NaN/Inf 视为小于阈值 → 降级，防御）
     * @param decorationsDisabled 装饰层显式关闭标志
     * @param qualityFactor 热控质量因子（0-1）
     * @return true = 绘制装饰层；false = 跳过
     */
    fun decorationsEnabled(
        scale: Float,
        decorationsDisabled: Boolean,
        qualityFactor: Float
    ): Boolean {
        // NaN/Inf 视为小于阈值 → 降级（防御）
        val validScale = !scale.isNaN() && !scale.isInfinite() && scale >= DECOR_ZOOM_THRESHOLD
        return !decorationsDisabled && qualityFactor >= DECOR_QUALITY_THRESHOLD && validScale
    }
}
