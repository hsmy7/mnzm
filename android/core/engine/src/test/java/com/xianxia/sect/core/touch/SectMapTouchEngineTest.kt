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
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        // 编辑模式下任意移动进入 BuildingDrag
        engine.onTouch(touchMove(101f, 200f, 50_000_000L))
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
        assertTrue("BuildingDrag entry should call onDragStart",
            callbacks.dragStartCalled)
    }

    @Test
    fun `BuildingDrag then UP calls onDragEnd`() = runTest {
        callbacks.inEditMode = true
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
    // hasBuildingTarget 路径（建筑上按下，抑制 slop）
    // ========================

    @Test
    fun `building target at DOWN suppresses Slop to Scrolling during long press window`() = runTest {
        callbacks.longPressResult = LongPressResult.BuildingDrag
        callbacks.buildingTargetAtDown = true // 模拟在建筑上按下
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))

        // 移动远超 slop，但因为有 buildingTarget，不会切到 Scrolling
        engine.onTouch(touchMove(300f, 200f, 300_000_000L))
        assertFalse(
            "Should NOT scroll when touch started on building",
            callbacks.panCalled
        )

        // 长按触发后，状态应为 BuildingDrag
        advanceUntilIdle()
        assertEquals(
            "Should transition to BuildingDrag after long press",
            GestureState.BuildingDrag::class, engine.state::class
        )
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

    var tapCalled = false
    var panCalled = false
    var buildingDragUpdateCalled = false
    var buildingDragEndCalled = false
    var goldFingerUpdateCalled = false
    var flingStartedCalled = false
    var flingEndCalled = false
    var dragStartCalled = false
    var dragEndCalled = false

    override fun onTap(screenX: Float, screenY: Float) {
        tapCalled = true
    }

    override fun onPanCamera(dx: Float, dy: Float) {
        panCalled = true
    }

    override fun onLongPress(screenX: Float, screenY: Float): LongPressResult = longPressResult

    override fun findBuildingAt(screenX: Float, screenY: Float): Any? =
        if (buildingTargetAtDown) Any() else null

    override fun isInEditMode(): Boolean = inEditMode

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
        panCalled = false
        buildingDragUpdateCalled = false
        buildingDragEndCalled = false
        goldFingerUpdateCalled = false
        flingStartedCalled = false
        flingEndCalled = false
        dragStartCalled = false
        dragEndCalled = false
        longPressResult = LongPressResult.NotHandled
        buildingTargetAtDown = false
        inEditMode = false
    }
}
