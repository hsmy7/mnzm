package com.xianxia.sect.core.touch

/**
 * 宗门地图手势状态机状态定义。
 *
 * 转换规则（行业验证）：
 * - Drag > LongPress：移动超 slop 立即取消长按
 * - Tap 仅短触无移动：抬起时间 < 400ms 且位移 < slop
 * - Fling 仅 Scrolling 后触发：抬起速度 > MIN_FLING_VELOCITY
 * - BuildingDrag 中边缘平移独立于手指移动运行
 *
 * 参考：Android GestureDetector、Flutter GestureArena、KorGE Input 架构
 */
sealed class GestureState {

    /** 空闲状态，等待触摸。 */
    data object Idle : GestureState()

    /** 手指按下，等待判决（超过 touchSlop 进入 Scrolling，超时进入 LongPress）。 */
    data object Down : GestureState()

    /** 手指移动超过 touchSlop，正在拖拽平移相机。 */
    data object Scrolling : GestureState()

    /** 手指抬起且速度 > minFlingVelocity，惯性滑行中。 */
    data object Flinging : GestureState()

    /**
     * 长按检测到建筑，正在拖拽移动建筑。
     * 引擎不持有建筑引用（由 UI 层通过 movingBuilding 变量维护），
     * 只负责将移动增量通过 onBuildingDragUpdate 回调传递给 UI 层。
     */
    data object BuildingDrag : GestureState()

    /** 长按检测到金手指激活区，正在框选批量建造区域。 */
    data object GoldFingerDrag : GestureState()
}
