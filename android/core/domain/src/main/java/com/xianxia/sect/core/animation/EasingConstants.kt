package com.xianxia.sect.core.animation

/**
 * 缓动函数类型 — 归一化时间 t ∈ [0,1] → 缓动后的进度值。
 *
 * 曲线本身不做 clamp（纯数学映射），调用方（如 [com.xianxia.sect.core.animation.EngineTween]、
 * FadeTransition.alphaAt）负责保证入参 t ∈ [0,1]。
 */
typealias EasingFunction = (Float) -> Float

/**
 * 缓动曲线常量表 — 全游戏统一的缓动数学来源。
 *
 * ## 设计动机
 * - 动画库（EngineTween / Timeline）与 UI 侧（FadeTransition 等）引用同一组曲线常量，
 *   杜绝"同名缓动多份实现、数值漂移"（旧状态：CameraAnimator 自带 EaseOutCubic，
 *   FadeTransition 反向依赖它，UI 侧另用 Compose 内置曲线，曲线族不统一）
 * - 纯常量 + 纯函数，零依赖，放 :core:domain 供 :core:engine / :core:ui / :feature:game 跨模块引用
 *   （依赖方向约束：:core:ui 不依赖 :core:engine，因此曲线常量必须下沉到 domain）
 *
 * ## 参考
 * Robert Penner 缓动函数 (https://easings.net/)，游戏行业标准曲线族。
 */
object EasingConstants {

    /** 线性：进度与时间成正比（匀速）。 */
    val LINEAR: EasingFunction = { t -> t }

    /** 缓入立方：t³ — 慢启动、快结束，适合元素离场/下落。 */
    val EASE_IN_CUBIC: EasingFunction = { t -> t * t * t }

    /**
     * 缓出立方：1-(1-t)³ — 快速启动、柔和结束。
     *
     * 相机跟随/淡入等"尽快响应、优雅收尾"场景的游戏行业默认（原 CameraAnimator 默认曲线）。
     */
    val EASE_OUT_CUBIC: EasingFunction = { t -> 1f - (1f - t) * (1f - t) * (1f - t) }

    /**
     * 缓入缓出立方：t<0.5 → 4t³，否则 1-(2-2t)³/2 — 两端柔和、中间加速。
     *
     * 适合往返动画/切换过渡；t=0.5 处两分支均为 0.5，曲线 C0 连续。
     */
    val EASE_IN_OUT_CUBIC: EasingFunction = { t ->
        if (t < 0.5f) {
            4f * t * t * t
        } else {
            1f - 0.5f * (2f - 2f * t) * (2f - 2f * t) * (2f - 2f * t)
        }
    }
}
