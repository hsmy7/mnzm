package com.xianxia.sect.ui.game.dialogs

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 侧卡中心偏移（卡片宽度的倍数，主卡遮挡侧卡约
 * 25% 宽度，即约四分之一卡宽）
 */
private const val SIDE_OFFSET_FRACTION = 0.75f
/** 补位卡最大偏移（卡片宽度的倍数，环绕补位落位） */
private const val WRAP_OFFSET_MULTIPLIER = 2f
/** 滚轮侧槽 y 下沉比例（中间高两侧低的弧线，相对内容区高度） */
private const val SIDE_Y_FRACTION = 0.05f

/**
 * 卡片渲染槽位（滚轮弧线 y 轨迹 + 显式 z 序）。
 * cardAlpha 为整卡透明度含背景，contentAlpha 为内容额外透明度。
 */
internal data class CardPlacement(
    val cardIndex: Int,
    val offsetX: Dp,
    val offsetY: Dp,
    val cardAlpha: Float,
    val contentAlpha: Float,
    val zIndex: Int
)

/** 滚轮侧槽高度（相对内容区高度） */
private fun sideY(containerHeight: Dp): Dp =
    containerHeight * SIDE_Y_FRACTION

/**
 * 静止态三槽位（滚轮：中间高两侧低的弧线，主卡 z 序最高）。
 */
internal fun buildStaticPlacements(
    carousel: LizhanCarouselState,
    cardWidth: Dp,
    containerHeight: Dp
): List<CardPlacement> {
    val sideOffset = cardWidth * SIDE_OFFSET_FRACTION
    return listOf(
        CardPlacement(
            carousel.slotIndex(-1), -sideOffset, sideY(containerHeight),
            1f, 0f, 0
        ),
        CardPlacement(carousel.slotIndex(0), 0.dp, 0.dp, 1f, 1f, 2),
        CardPlacement(
            carousel.slotIndex(1), sideOffset, sideY(containerHeight),
            1f, 0f, 0
        )
    )
}

/**
 * 动画态四槽位（滚轮旋转：原对侧卡原位淡出——无飞出位移，
 * 与原主卡移入交叉淡化保证翻页连贯；三张轮转卡平滑移动）。
 * [progress] ∈ [0,1]（已归一化），[dir] = ±1 为翻页方向。
 *
 * 右翻（dir=+1）＝卡片集体左移一格：右卡→中（内容渐显）、
 * 原主卡→左槽、原左卡原地淡出消失、新卡从屏外右侧进入右槽。
 */
internal fun buildFlipPlacements(
    carousel: LizhanCarouselState,
    dir: Float,
    progress: Float,
    cardWidth: Dp,
    containerHeight: Dp
): List<CardPlacement> {
    val sideOffset = cardWidth * SIDE_OFFSET_FRACTION
    val h = sideY(containerHeight)
    return listOf(
        // 原对侧卡原地淡出（x/y 固定对侧槽位，仅整卡渐隐；
        // 与原主卡移入交叉淡化，避免槽位瞬间跳变）
        CardPlacement(
            carousel.slotIndex(-dir.toInt()),
            -sideOffset * dir,
            h,
            1f - progress, 0f, 1
        ),
        // 原主卡沿 [dir] 方向移动到对侧槽位（内容渐隐为轮廓）
        CardPlacement(
            carousel.slotIndex(0),
            -sideOffset * dir * progress,
            h * progress,
            1f, 1f - progress, 2
        ),
        // 原相邻侧卡沿 [dir] 方向移入中槽（成为新主卡，内容渐显）
        CardPlacement(
            carousel.slotIndex(dir.toInt()),
            sideOffset * dir * (1f - progress),
            h * (1f - progress),
            1f, progress, 3
        ),
        // 新卡从屏外沿 [dir] 方向进入补位槽位
        CardPlacement(
            carousel.slotIndex(2 * dir.toInt()),
            sideOffset * dir * (WRAP_OFFSET_MULTIPLIER - progress),
            h, 1f, 0f, 0
        )
    )
}
