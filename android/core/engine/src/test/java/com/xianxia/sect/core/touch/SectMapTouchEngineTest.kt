package com.xianxia.sect.core.touch

import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class SectMapTouchEngineTest {

    private val callbacks = FakeTouchEngineCallbacks()
    private val defaultConfig = TouchEngineConfig(
        touchSlopPx = 16f,
        longPressTimeoutMs = 400L,
        minFlingVelocity = 200f
    )

    @Before
    fun setUp() {
        callbacks.reset()
    }

    @After
    fun tearDown() {
        callbacks.reset()
    }

    // ========================
    // 正常手势路径
    // ========================

    @Test
    fun `DOWN then UP without movement produces TAP`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchUp(100f, 200f))
        assertTrue("Expected Idle state after tap", engine.state is GestureState.Idle)
        assertTrue("Tap callback should be called", callbacks.tapCalled)
    }

    @Test
    fun `DOWN then MOVE over slop transitions to SCROLLING`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(150f, 200f, 300_000_000L)) // 50px > 16px slop over 300ms
        assertTrue("Expected Scrolling after move past slop", engine.state is GestureState.Scrolling)
        assertTrue("Pan callback should be called", callbacks.panCalled)
    }

    @Test
    fun `SCROLLING then UP goes to Idle`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        // Move just 5px over slop (21px > 16px) over 300ms → low velocity
        engine.onTouch(touchMove(121f, 200f, 300_000_000L))
        assertTrue(engine.state is GestureState.Scrolling)
        engine.onTouch(touchUp(121f, 200f, 600_000_000L))
        // State should be Idle (velocity ≈ 21/0.3 = 70px/s < 200 threshold)
        assertTrue(
            "Expected Idle after slow release, got ${engine.state::class.simpleName}",
            engine.state is GestureState.Idle
        )
    }

    // ========================
    // DragStart / DragEnd 回调
    // ========================

    @Test
    fun `Down then MOVE over slop triggers onDragStart`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        assertFalse("No dragStart before movement", callbacks.dragStartCalled)
        engine.onTouch(touchMove(150f, 200f, 300_000_000L)) // > slop
        assertTrue("onDragStart should fire when entering Scrolling",
            callbacks.dragStartCalled)
    }

    @Test
    fun `Scrolling then UP calls onDragEnd`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(150f, 200f, 300_000_000L)) // > slop
        assertTrue(callbacks.dragStartCalled)
        assertFalse("onDragEnd not yet", callbacks.dragEndCalled)
        engine.onTouch(touchUp(150f, 200f, 600_000_000L))
        assertTrue("onDragEnd should fire when Scrolling ends",
            callbacks.dragEndCalled)
    }

    @Test
    fun `CANCEL during Scrolling calls onDragEnd`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(150f, 200f, 300_000_000L))
        assertTrue(callbacks.dragStartCalled)
        engine.onTouch(touchCancel())
        assertTrue("CANCEL should call onDragEnd", callbacks.dragEndCalled)
    }

    @Test
    fun `BuildingDrag in edit mode calls onDragStart`() = runTest {
        callbacks.inEditMode = true
        callbacks.buildingTargetAtDown = true // 触摸在预览/建筑上
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        // 编辑模式下目标上任意移动进入 BuildingDrag
        engine.onTouch(touchMove(101f, 200f, 50_000_000L))
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
        assertTrue("BuildingDrag entry should call onDragStart",
            callbacks.dragStartCalled)
    }

    @Test
    fun `BuildingDrag then UP calls onDragEnd`() = runTest {
        callbacks.inEditMode = true
        callbacks.buildingTargetAtDown = true // 触摸在预览/建筑上
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(101f, 200f, 50_000_000L))
        assertTrue(callbacks.dragStartCalled)
        engine.onTouch(touchUp(101f, 200f, 100_000_000L))
        assertTrue("BuildingDrag UP should call onDragEnd",
            callbacks.dragEndCalled)
    }

    // ========================
    // BuildingDrag 路径（hasBuildingTarget = 直接拖拽，无长按）
    // ========================

    @Test
    fun `building target then ANY movement enters BuildingDrag when in edit mode`() = runTest {
        callbacks.buildingTargetAtDown = true
        callbacks.inEditMode = true // 已处于编辑模式
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(101f, 200f, 300_000_000L))
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
        assertTrue("Building drag update should be called on first move",
            callbacks.buildingDragUpdateCalled)
    }

    @Test
    fun `building target MOVE does not scroll when in edit mode`() = runTest {
        callbacks.buildingTargetAtDown = true
        callbacks.inEditMode = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(200f, 200f, 300_000_000L))
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
    }

    @Test
    fun `building target MOVE then UP calls onBuildingDragEnd when in edit mode`() = runTest {
        callbacks.buildingTargetAtDown = true
        callbacks.inEditMode = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(150f, 200f, 300_000_000L))
        assertTrue(engine.state is GestureState.BuildingDrag)
        engine.onTouch(touchUp(150f, 200f, 600_000_000L))
        assertEquals(GestureState.Idle::class, engine.state::class)
        assertTrue(callbacks.buildingDragEndCalled)
    }

    // ========================
    // GoldFingerDrag 路径
    // ========================

    @Test
    fun `LongPressResult GoldFingerDrag transitions to GoldFingerDrag`() = runTest {
        callbacks.longPressResult = LongPressResult.GoldFingerDrag
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.onTouch(touchDown(100f, 200f))
        advanceUntilIdle()
        assertEquals(GestureState.GoldFingerDrag::class, engine.state::class)
    }

    @Test
    fun `GoldFingerDrag MOVE calls onGoldFingerUpdate`() = runTest {
        callbacks.longPressResult = LongPressResult.GoldFingerDrag
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.onTouch(touchDown(100f, 200f))
        advanceUntilIdle()
        assertTrue(engine.state is GestureState.GoldFingerDrag)
        engine.onTouch(touchMove(300f, 400f, 300_000_000L))
        assertTrue(callbacks.goldFingerUpdateCalled)
    }

    @Test
    fun `GoldFingerDrag then UP returns to Idle`() = runTest {
        callbacks.longPressResult = LongPressResult.GoldFingerDrag
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.onTouch(touchDown(100f, 200f))
        advanceUntilIdle()
        engine.onTouch(touchUp(100f, 200f))
        assertEquals(GestureState.Idle::class, engine.state::class)
    }

    // ========================
    // NotHandled → Scrolling
    // ========================

    @Test
    fun `LongPressResult NotHandled then MOVE transitions to Scrolling`() = runTest {
        callbacks.longPressResult = LongPressResult.NotHandled
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.onTouch(touchDown(100f, 200f))
        advanceUntilIdle() // long press fires, returns NotHandled, state stays Down
        engine.onTouch(touchMove(200f, 200f, 300_000_000L))
        assertTrue(engine.state is GestureState.Scrolling)
    }

    // ========================
    // 取消/中断
    // ========================

    @Test
    fun `CANCEL resets to Idle`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchCancel())
        assertEquals(GestureState.Idle::class, engine.state::class)
    }

    @Test
    fun `reset clears state`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.onTouch(touchDown(100f, 200f))
        engine.reset()
        assertEquals(GestureState.Idle::class, engine.state::class)
    }

    // ========================
    // slop wins over long press
    // ========================

    @Test
    fun `move past slop before long press fires goes to Scrolling`() = runTest {
        callbacks.longPressResult = LongPressResult.BuildingDrag
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.onTouch(touchDown(100f, 200f))
        // Move past slop immediately
        engine.onTouch(touchMove(200f, 200f, 300_000_000L))
        assertTrue(
            "Slop before long press should trigger Scrolling",
            engine.state is GestureState.Scrolling
        )
    }

    // ========================
    // hasBuildingTarget 路径（建筑上按下，slop 判决统一由 touchSlop 决定）
    // ========================

    @Test
    fun `building target at DOWN does not suppress Slop to Scrolling`() = runTest {
        callbacks.longPressResult = LongPressResult.BuildingDrag
        callbacks.buildingTargetAtDown = true // 模拟在建筑上按下
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))

        // 移动远超 slop：即使建筑上起手也应切到 Scrolling（拖动视角不被建筑吞掉）
        engine.onTouch(touchMove(300f, 200f, 300_000_000L))
        assertTrue(
            "Should scroll when touch started on building",
            callbacks.panCalled
        )
        assertTrue("State should be Scrolling", engine.state is GestureState.Scrolling)

        // 长按 Job 已被取消，advanceUntilIdle 后不应进入 BuildingDrag
        advanceUntilIdle()
        assertTrue(
            "Long press must be cancelled after slop exceeded",
            engine.state is GestureState.Scrolling
        )
        assertFalse("Must NOT enter BuildingDrag", engine.state is GestureState.BuildingDrag)
        assertFalse("Building drag update must not be called", callbacks.buildingDragUpdateCalled)
    }

    @Test
    fun `building target at DOWN then quick UP triggers Tap not drag`() = runTest {
        callbacks.longPressResult = LongPressResult.BuildingDrag
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        // 抬起在长按触发之前
        engine.onTouch(touchUp(100f, 200f, 100_000_000L))
        assertEquals(GestureState.Idle::class, engine.state::class)
        assertTrue("Quick UP on building should trigger Tap", callbacks.tapCalled)
    }

    // ========================
    // 建筑上起手拖动 → pan（不误触 tap / 长按）回归
    // ========================

    @Test
    fun `building target then MOVE past slop then UP does not trigger Tap`() = runTest {
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(150f, 200f, 300_000_000L)) // 50px > 16px slop
        assertTrue(engine.state is GestureState.Scrolling)
        engine.onTouch(touchUp(150f, 200f, 600_000_000L))
        // 50px/0.3s ≈ 167px/s < 200 minFlingVelocity → Idle（避免进入 Flinging）
        assertEquals(GestureState.Idle::class, engine.state::class)
        assertFalse("Moved past slop must not tap", callbacks.tapCalled)
        assertTrue("Drag end should fire after scrolling", callbacks.dragEndCalled)
    }

    @Test
    fun `building target small movement then long press enters BuildingDrag`() = runTest {
        callbacks.longPressResult = LongPressResult.BuildingDrag
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(108f, 203f, 50_000_000L)) // 位移 (8,3) ≈ 8.5px ≤ 16px slop
        engine.onTouch(touchMove(108f, 203f, 250_000_000L)) // 250ms > 200ms，Job 未被取消
        advanceUntilIdle() // 200ms 长按触发
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
        assertTrue("Long press on building should enter BuildingDrag", callbacks.dragStartCalled)
        assertFalse("Small movement must not scroll", callbacks.panCalled)
    }

    @Test
    fun `building target small movement then quick UP triggers Tap`() = runTest {
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(108f, 203f, 50_000_000L)) // 位移 (8,3) ≤ 16px slop
        engine.onTouch(touchUp(108f, 203f, 100_000_000L)) // 100ms < 200ms 长按窗口
        assertEquals(GestureState.Idle::class, engine.state::class)
        assertTrue("Small movement quick UP on building should Tap", callbacks.tapCalled)
    }

    @Test
    fun `DOWN then UP past slop without MOVE events does not trigger Tap`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchUp(150f, 200f, 100_000_000L)) // 50px 位移但无 MOVE 事件
        assertEquals(GestureState.Idle::class, engine.state::class)
        assertFalse("UP past slop without MOVE must not tap", callbacks.tapCalled)
    }

    @Test
    fun `tap on building reports DOWN coordinates for hit test`() = runTest {
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(108f, 203f, 50_000_000L)) // ≤ slop 微移
        engine.onTouch(touchUp(108f, 203f, 100_000_000L))
        assertTrue(callbacks.tapCalled)
        assertEquals(100f, callbacks.lastTapX, 0.001f)
        assertEquals(200f, callbacks.lastTapY, 0.001f)
    }

    @Test
    fun `MOVE past slop with gold finger active enters Scrolling not GoldFingerDrag`() = runTest {
        // 金手指激活臂已删除：slop 拖动一律平移视角（编辑模式空地拖动不再重进框选）
        callbacks.goldFingerActive = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(150f, 200f, 300_000_000L)) // 50px > 16px slop
        assertTrue(
            "State should be Scrolling",
            engine.state is GestureState.Scrolling
        )
        assertTrue(
            "Scrolling entry must call onDragStart",
            callbacks.dragStartCalled
        )
        assertTrue("Scrolling must pan the camera", callbacks.panCalled)
        assertFalse(
            "GoldFingerDrag must not be entered via slop",
            engine.state is GestureState.GoldFingerDrag
        )
    }

    // ========================
    // 编辑模式触摸分类（Bug 4 重构回归）
    // ========================

    @Test
    fun `edit mode empty ground slop move pans camera not drags building`() = runTest {
        // 空地：slop 后一律 Scrolling（平移视角），不进入 BuildingDrag
        callbacks.inEditMode = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(150f, 200f, 300_000_000L)) // 50px > 16px slop
        assertTrue("空地上 slop 移动必须进入 Scrolling", engine.state is GestureState.Scrolling)
        assertTrue("空地拖动必须平移视角", callbacks.panCalled)
        assertFalse("空地拖动不得进入 BuildingDrag", engine.state is GestureState.BuildingDrag)
        assertFalse("空地拖动不得触发建筑拖拽更新", callbacks.buildingDragUpdateCalled)
    }

    @Test
    fun `edit mode empty ground quick UP triggers Tap`() = runTest {
        // 空地：短触保持 Tap（详情弹窗等用途）
        callbacks.inEditMode = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchUp(100f, 200f))
        assertEquals(GestureState.Idle::class, engine.state::class)
        assertTrue("空地上短触必须触发 onTap", callbacks.tapCalled)
    }

    @Test
    fun `edit mode target stationary hold enters BuildingDrag after timeout`() = runTest {
        // 目标上静止按住：200ms 超时自动进入 BuildingDrag（不动手指也可拖动建筑）
        callbacks.inEditMode = true
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        assertTrue("超时前保持 Down", engine.state is GestureState.Down)
        advanceUntilIdle() // buildingLongPressTimeoutMs (200ms) 到达
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
        assertTrue("超时进入 BuildingDrag 必须调用 onDragStart", callbacks.dragStartCalled)
    }

    @Test
    fun `edit mode DOWN on gold finger icon enters GoldFingerDrag immediately`() = runTest {
        // Bug 1 重入路径：编辑模式按下金手指图标立即重进框选（无需长按等待）
        callbacks.inEditMode = true
        callbacks.longPressResult = LongPressResult.GoldFingerDrag
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.onTouch(touchDown(100f, 200f))
        assertTrue("图标按下必须立即进入 GoldFingerDrag", engine.state is GestureState.GoldFingerDrag)
        assertTrue("GoldFingerDrag 进入必须调用 onDragStart", callbacks.dragStartCalled)
        engine.onTouch(touchMove(300f, 400f, 300_000_000L))
        assertTrue("重入框选后拖动必须更新选区", callbacks.goldFingerUpdateCalled)
    }

    @Test
    fun `edit mode target drag wins over gold finger re-entry`() = runTest {
        // 目标拖动优先于金手指重入：已激活时按住预览拖动，不得误入框选
        callbacks.inEditMode = true
        callbacks.goldFingerActive = true
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(101f, 200f, 300_000_000L)) // 1px 位移
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
        assertFalse("目标拖动不得误入金手指框选", engine.state is GestureState.GoldFingerDrag)
        assertTrue("目标拖动必须更新建筑位置", callbacks.buildingDragUpdateCalled)
    }

    @Test
    fun `building long press timeout respects config value`() = runTest {
        callbacks.longPressResult = LongPressResult.BuildingDrag
        callbacks.buildingTargetAtDown = true
        val config = TouchEngineConfig(buildingLongPressTimeoutMs = 1000L)
        val engine = SectMapTouchEngine(callbacks, this, config)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(108f, 203f, 50_000_000L)) // 位移 ≤ slop，长按 Job 保持
        assertTrue("Long press must not fire before configured timeout", engine.state is GestureState.Down)
        advanceUntilIdle() // 1000ms 到达，长按触发
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
    }

    companion object {
        private fun touchDown(x: Float, y: Float, t: Long = 0L) = TouchData(x, y, TouchAction.DOWN, t)
        private fun touchMove(x: Float, y: Float, t: Long = 1_000_000L) = TouchData(x, y, TouchAction.MOVE, t)
        private fun touchUp(x: Float, y: Float, t: Long = 300_000_000L) = TouchData(x, y, TouchAction.UP, t)
        private fun touchCancel() = TouchData(0f, 0f, TouchAction.CANCEL)
    }
}

/** Fake callback implementation for testing the touch engine state machine. */
class FakeTouchEngineCallbacks : TouchEngineCallbacks {

    var longPressResult: LongPressResult = LongPressResult.NotHandled
    var buildingTargetAtDown = false // 模拟 findBuildingAt 返回非 null
    var inEditMode = false // 模拟 isInEditMode()
    var goldFingerActive = false // 模拟 isGoldFingerActive()

    var tapCalled = false
    var lastTapX = -1f
    var lastTapY = -1f
    var panCalled = false
    var buildingDragUpdateCalled = false
    var buildingDragEndCalled = false
    var goldFingerUpdateCalled = false
    var flingStartedCalled = false
    var flingEndCalled = false
    var dragStartCalled = false
    var dragEndCalled = false
    var longPressCallCount = 0

    override fun onTap(screenX: Float, screenY: Float) {
        tapCalled = true
        lastTapX = screenX
        lastTapY = screenY
    }

    override fun onPanCamera(dx: Float, dy: Float) {
        panCalled = true
    }

    override fun onLongPress(screenX: Float, screenY: Float): LongPressResult {
        longPressCallCount++
        return longPressResult
    }

    override fun findBuildingAt(screenX: Float, screenY: Float): Any? =
        if (buildingTargetAtDown) Any() else null

    override fun isInEditMode(): Boolean = inEditMode

    override fun isGoldFingerActive(): Boolean = goldFingerActive

    override fun onBuildingDragUpdate(worldDx: Float, worldDy: Float) {
        buildingDragUpdateCalled = true
    }

    override fun onBuildingDragEnd() {
        buildingDragEndCalled = true
    }

    override fun onGoldFingerUpdate(screenX: Float, screenY: Float) {
        goldFingerUpdateCalled = true
    }

    override fun onDragStart() {
        dragStartCalled = true
    }

    override fun onDragEnd() {
        dragEndCalled = true
    }

    override fun onFlingStart() {
        flingStartedCalled = true
    }

    override fun onFlingEnd() {
        flingEndCalled = true
    }

    override fun getCameraScale(): Float = 1f

    fun reset() {
        tapCalled = false
        lastTapX = -1f
        lastTapY = -1f
        panCalled = false
        buildingDragUpdateCalled = false
        buildingDragEndCalled = false
        goldFingerUpdateCalled = false
        flingStartedCalled = false
        flingEndCalled = false
        dragStartCalled = false
        dragEndCalled = false
        longPressCallCount = 0
        longPressResult = LongPressResult.NotHandled
        buildingTargetAtDown = false
        inEditMode = false
        goldFingerActive = false
    }
}
