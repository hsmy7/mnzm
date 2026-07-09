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
     * @return LongPressResult 指示引擎应进入的模式。
     *   BuildingDrag / GoldFingerDrag — 引擎自动切换状态，
     *   NotHandled — 忽略（不处理为长按）
     */
    fun onLongPress(screenX: Float, screenY: Float): LongPressResult = LongPressResult.NotHandled

    /**
     * 查找屏幕坐标处的建筑（用于 DOWN 时刻快速判断）。
     * 引擎在 handleDown 中调用，如果返回非 null，则抑制 Down→Scrolling 转换。
     */
    fun findBuildingAt(screenX: Float, screenY: Float): Any? = null

    /**
     * 是否已在编辑模式（正在移动建筑 / 放置模式中）。
     * true  → 直接拖拽（无需长按），适用于放置模式或确认按钮显示时再次拖动。
     * false → 首次触摸建筑，需 200ms 长按才进入 BuildingDrag。
     */
    fun isInEditMode(): Boolean = false

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

    /** 拖拽（手指按下滑动）开始/结束（用于帧率提升） */
    fun onDragStart() = Unit
    fun onDragEnd() = Unit

    /** Fling 开始/结束（用于帧率提升） */
    fun onFlingStart() = Unit
    fun onFlingEnd() = Unit
}

/**
 * 长按结果 —— 通知引擎长按检测到了什么。
 * 引擎根据结果自动切换到对应手势状态。
 */
sealed class LongPressResult {
    /** 未检测到任何长按目标，引擎保持 Down 状态等待后续判决 */
    data object NotHandled : LongPressResult()

    /** 检测到建筑，进入建筑拖拽模式 */
    data object BuildingDrag : LongPressResult()

    /** 检测到金手指区域，进入金手指框选模式 */
    data object GoldFingerDrag : LongPressResult()
}
