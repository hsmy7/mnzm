package com.xianxia.sect.core.touch

import kotlin.math.ceil

/**
 * 触控命中外扩策略（Hit Slop）— 保证小建筑/小目标的命中区不小于最小触控目标。
 *
 * 背景：宗门地图 1×1 建筑（灵田等）命中区 = 单格（32 世界像素），默认缩放下仅约 15dp，
 * 远低于 Apple HIG 44pt / Material 48dp 最小触控目标，导致"点了却没选中"。
 * 本策略按屏幕像素下限把命中区向四周外扩（换算为世界坐标格数），跨设备一致（dp 计）。
 *
 * 纯 Kotlin 零平台依赖（density 由调用方注入），iOS 侧可复用。
 *
 * 参考来源：
 * - Apple HIG — Buttons（触控目标 ≥44×44pt）
 * - Material Design 3 — Accessibility（触控目标 ≥48dp）
 * - Google 无障碍帮助 — 触摸目标尺寸（48dp）
 */
class HitSlopPolicy(
    /** 最小命中目标（dp）— 接近 Material 48dp 但不过度抢占相邻建筑。 */
    val minHitTargetDp: Float = MIN_HIT_TARGET_DP,
    /** 屏幕密度（px/dp），由平台层注入。 */
    val density: Float
) {
    init {
        require(minHitTargetDp > 0f && minHitTargetDp.isFinite()) {
            "minHitTargetDp must be positive and finite"
        }
        require(density > 0f && density.isFinite()) { "density must be positive and finite" }
    }

    /** 最小命中目标（屏幕像素）。 */
    val minHitTargetPx: Float get() = minHitTargetDp * density

    /**
     * 计算在给定缩放下，命中区需要向四周外扩的格数。
     * @param tileSize 格尺寸（世界像素）
     * @param scale 相机缩放
     * @return 各方向外扩格数（≥0）；非法输入返回 0（不外扩，行为与旧版一致）
     */
    fun expandCells(tileSize: Int, scale: Float): Int {
        if (tileSize <= 0 || !scale.isFinite() || scale <= 0f) return 0
        return ceil(minHitTargetPx / (tileSize * scale)).toInt()
    }

    companion object {
        /** 最小命中目标（dp）— 接近 Material 48dp 但不过度抢占相邻建筑。 */
        const val MIN_HIT_TARGET_DP = 40f
    }
}
