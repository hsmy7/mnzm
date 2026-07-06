package com.xianxia.sect.core.touch

/**
 * 手势引擎 → UI 层的回调接口。
 *
 * 引擎负责手势识别（状态机、fling 物理、边缘检测），
 * UI 层（MainGameScreen）负责将引擎事件映射到 Compose 状态和业务逻辑。
 *
 * 所有回调默认空实现，UI 层按需覆写。
 */
interface TouchEngineCallbacks {

    /** 相机平移（SCROLLING / FLINGING）。dx/dy 为屏幕像素偏移。 */
    fun onPanCamera(dx: Float, dy: Float) = Unit

    /** 短触点击。screenX/screenY 为屏幕坐标。 */
    fun onTap(screenX: Float, screenY: Float) = Unit

    /**
     * 长按检测到。
     * @return true = 引擎应进入对应模式（BuildingDrag / GoldFingerDrag），
     *         false = 忽略（不作为长按处理）
     */
    fun onLongPress(screenX: Float, screenY: Float): Boolean = false

    /** 进入长按模式时告知引擎当前是什么模式 */
    suspend fun setLongPressMode(mode: LongPressMode): Unit = Unit

    /** 建筑拖拽位移更新。worldDx/worldDy 为世界坐标偏移。 */
    fun onBuildingDragUpdate(worldDx: Float, worldDy: Float) = Unit

    /** 建筑拖拽结束 */
    fun onBuildingDragEnd() = Unit

    /** 金手指框选更新（screenX/screenY 屏幕坐标） */
    fun onGoldFingerUpdate(screenX: Float, screenY: Float) = Unit

    /** 是否金手指激活 */
    fun isGoldFingerActive(): Boolean = false

    /** 获取相机缩放比 */
    fun getCameraScale(): Float = 1f

    /** Fling 开始/结束（用于帧率提升） */
    fun onFlingStart() = Unit
    fun onFlingEnd() = Unit
}

/** 长按模式 */
enum class LongPressMode {
    NONE,
    BUILDING_DRAG,
    GOLD_FINGER_DRAG
}
