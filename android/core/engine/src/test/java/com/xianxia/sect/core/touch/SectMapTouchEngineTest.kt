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
        engine.onTouch(touchMove(150f, 200f, 50_000_000L)) // 50px > slop → 实际拖拽
        assertTrue(callbacks.dragStartCalled)
        engine.onTouch(touchUp(150f, 200f, 100_000_000L))
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
        // 按下即拾起：DOWN 即进入 BuildingDrag；移动超 slop 后开始更新建筑位置
        engine.onTouch(touchMove(150f, 200f, 300_000_000L))
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
        assertTrue("Building drag update should be called on first move past slop",
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
    fun `building target at DOWN enters BuildingDrag pickup not Scrolling`() = runTest {
        // 按下即拾起（Clash of Clans 手感）：建筑上起手即拖拽建筑，不再吞成视角平移
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        assertTrue("建筑上按下应立即进入 BuildingDrag", engine.state is GestureState.BuildingDrag)

        // 移动远超 slop：拖拽建筑（补发累计位移），不切 Scrolling
        engine.onTouch(touchMove(300f, 200f, 300_000_000L))
        assertTrue("建筑上拖动必须更新建筑位置", callbacks.buildingDragUpdateCalled)
        assertFalse("建筑上拖动不得平移视角", callbacks.panCalled)
        assertTrue("状态保持 BuildingDrag", engine.state is GestureState.BuildingDrag)

        // 长按 Job（驻留拾起）已被移动取消，advanceUntilIdle 后不重复触发 onLongPress
        advanceUntilIdle()
        assertEquals("onLongPress 每轮拾起只触发一次", 1, callbacks.longPressCallCount)
        assertTrue(engine.state is GestureState.BuildingDrag)
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
        assertTrue(engine.state is GestureState.BuildingDrag)
        engine.onTouch(touchUp(150f, 200f, 600_000_000L))
        assertEquals(GestureState.Idle::class, engine.state::class)
        assertFalse("Moved past slop must not tap", callbacks.tapCalled)
        assertTrue("Drag end should fire after building drag", callbacks.dragEndCalled)
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
        // 目标上静止按住：pickUpBuildingOnDown=false 时 200ms 超时自动进入 BuildingDrag
        callbacks.inEditMode = true
        callbacks.buildingTargetAtDown = true
        val config = defaultConfig.copy(pickUpBuildingOnDown = false)
        val engine = SectMapTouchEngine(callbacks, this, config)
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
        engine.onTouch(touchMove(150f, 200f, 300_000_000L)) // 50px > slop → 实际拖拽
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
        assertFalse("目标拖动不得误入金手指框选", engine.state is GestureState.GoldFingerDrag)
        assertTrue("目标拖动必须更新建筑位置", callbacks.buildingDragUpdateCalled)
    }

    @Test
    fun `building long press timeout respects config value`() = runTest {
        callbacks.longPressResult = LongPressResult.BuildingDrag
        callbacks.buildingTargetAtDown = true
        val config = TouchEngineConfig(buildingLongPressTimeoutMs = 1000L, pickUpBuildingOnDown = false)
        val engine = SectMapTouchEngine(callbacks, this, config)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(108f, 203f, 50_000_000L)) // 位移 ≤ slop，长按 Job 保持
        assertTrue("Long press must not fire before configured timeout", engine.state is GestureState.Down)
        advanceUntilIdle() // 1000ms 到达，长按触发
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
    }

    // ========================
    // 按下即拾起（CoC 手感，pickUpBuildingOnDown=true）
    // ========================

    @Test
    fun `pickup on DOWN enters BuildingDrag immediately without 200ms wait`() = runTest {
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        // 未等长按超时即进入 BuildingDrag（旧行为需 200ms）
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
        assertTrue("按下即拾起必须立即 onDragStart", callbacks.dragStartCalled)
        assertFalse("按下时不得立即触发 onLongPress（延迟到移动/驻留）", callbacks.longPressCallCount > 0)
    }

    @Test
    fun `pickup then MOVE past slop triggers onLongPress once with down coords and accumulated delta`() = runTest {
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchMove(150f, 200f, 300_000_000L)) // 50px > slop
        assertEquals("onLongPress 必须在首次移动触发", 1, callbacks.longPressCallCount)
        assertEquals("onLongPress 必须以按下点定位建筑（手指已移开）", 100f, callbacks.lastLongPressX, 0.001f)
        assertEquals(200f, callbacks.lastLongPressY, 0.001f)
        assertTrue("首次移动必须补发累计位移（建筑跟上手指）", callbacks.buildingDragUpdateCalled)
    }

    @Test
    fun `pickup then UP without movement triggers Tap with dual points`() = runTest {
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        engine.onTouch(touchUp(120f, 210f, 100_000_000L)) // 位移 ≤ slop 快速抬起
        assertEquals(GestureState.Idle::class, engine.state::class)
        assertTrue("拾起后快速抬起必须仍视为 tap", callbacks.tapCalled)
        assertEquals("tap 按下点", 100f, callbacks.lastTapX, 0.001f)
        assertEquals(200f, callbacks.lastTapY, 0.001f)
        assertEquals("tap 抬起点（双点宽容判定）", 120f, callbacks.lastTapUpX, 0.001f)
        assertEquals(210f, callbacks.lastTapUpY, 0.001f)
        assertFalse("快速抬起不得进入移动确认态", callbacks.buildingDragEndCalled)
    }

    @Test
    fun `pickup stationary hold beyond timeout fires onLongPress for pickup visuals`() = runTest {
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        assertFalse(callbacks.longPressCallCount > 0)
        advanceUntilIdle() // buildingLongPressTimeoutMs 驻留超时
        assertEquals("按住不动驻留超时也要触发拾起", 1, callbacks.longPressCallCount)
        assertEquals(GestureState.BuildingDrag::class, engine.state::class)
    }

    @Test
    fun `pickup then UP after hold timeout is drag end not tap`() = runTest {
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        advanceUntilIdle() // 驻留超时触发拾起（longPressFired=true）
        engine.onTouch(touchUp(100f, 200f, 300_000_000L))
        assertFalse("驻留拾起后的抬起不是 tap", callbacks.tapCalled)
        assertTrue("驻留拾起后的抬起进入移动确认态", callbacks.buildingDragEndCalled)
    }

    // ========================
    // 边缘自动平移（驻留判定 + 手指跟随补偿）
    // ========================

    @Test
    fun `edge pan does not fire before dwell threshold`() = runTest {
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 300f))
        engine.onTouch(touchMove(10f, 300f, 50_000_000L)) // 进入左边缘区（10px < 100px）
        val dragUpdateCountBefore = callbacks.buildingDragUpdateCallCount
        advanceTimeBy(100L) // 100ms < edgePanDwellMs(150ms)
        assertFalse("驻留不足不得平移相机", callbacks.panCalled)
        assertEquals("驻留不足不得补偿建筑（无新增拖拽更新）",
            dragUpdateCountBefore, callbacks.buildingDragUpdateCallCount)
        engine.onTouch(touchUp(10f, 300f, 200_000_000L))
    }

    @Test
    fun `edge pan fires after dwell with camera pan and building compensation`() = runTest {
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 300f))
        engine.onTouch(touchMove(10f, 300f, 50_000_000L)) // 进入左边缘区并保持
        val dragUpdateCountBefore = callbacks.buildingDragUpdateCallCount
        advanceTimeBy(200L) // 200ms > 150ms 驻留 → 激活
        assertTrue("驻留超时后必须边缘平移相机", callbacks.panCalled)
        assertTrue(
            "边缘平移必须同步补偿建筑（保持在手指下）",
            callbacks.buildingDragUpdateCallCount > dragUpdateCountBefore
        )
        engine.onTouch(touchUp(10f, 300f, 400_000_000L))
    }

    @Test
    fun `edge pan stops when finger leaves edge zone`() = runTest {
        callbacks.buildingTargetAtDown = true
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 300f))
        engine.onTouch(touchMove(10f, 300f, 50_000_000L))
        advanceTimeBy(200L)
        assertTrue(callbacks.panCalled)
        engine.onTouch(touchMove(400f, 300f, 300_000_000L)) // 离开边缘区
        val panCountAfterExit = callbacks.panCallCount
        advanceTimeBy(300L)
        assertEquals("离开边缘区后不得继续平移", panCountAfterExit, callbacks.panCallCount)
        engine.onTouch(touchUp(400f, 300f, 700_000_000L))
    }

    // ========================
    // fling（60fps 节拍 + 真实 dt）
    // ========================

    @Test
    fun `fast fling starts inertia and ends after deceleration`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f))
        // 快速拖动：50px/50ms = 1000px/s > 200 minFlingVelocity
        engine.onTouch(touchMove(150f, 200f, 50_000_000L))
        assertTrue(engine.state is GestureState.Scrolling)
        engine.onTouch(touchUp(150f, 200f, 100_000_000L))
        assertTrue("快速松手应进入 Flinging", engine.state is GestureState.Flinging)
        assertTrue(callbacks.flingStartedCalled)
        advanceUntilIdle() // 惯性减速至停止
        assertTrue("惯性期间必须持续平移", callbacks.panCalled)
        assertTrue("惯性结束必须回调 onFlingEnd", callbacks.flingEndCalled)
        assertEquals(GestureState.Idle::class, engine.state::class)
    }

    // ========================
    // 双指缩放（pinch）
    // ========================

    @Test
    fun `双指张开触发 onPinchZoom 放大并锚定两指中点`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(300f, 300f))
        // 第二指按下：初始间距 100px（(400,300)-(500,300)）
        engine.onTouch(pinchDown(400f, 300f, 500f, 300f, 10_000_000L))
        assertTrue("第二指按下应进入 Pinching", engine.state is GestureState.Pinching)
        // 张开到 200px → ratio = 2.0，焦点为两指中点 (500,300)
        engine.onTouch(pinchMove(400f, 300f, 600f, 300f, 20_000_000L))
        assertTrue("双指移动应触发 onPinchZoom", callbacks.pinchZoomCalled)
        assertEquals(2.0f, callbacks.lastPinchScaleFactor, 0.001f)
        assertEquals(500f, callbacks.lastPinchFocusX, 0.001f)
        assertEquals(300f, callbacks.lastPinchFocusY, 0.001f)
    }

    @Test
    fun `双指合拢触发 onPinchZoom 缩小`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(300f, 300f))
        engine.onTouch(pinchDown(300f, 300f, 500f, 300f, 10_000_000L)) // 间距 200px
        engine.onTouch(pinchMove(300f, 300f, 400f, 300f, 20_000_000L)) // 间距 100px → ratio 0.5
        assertTrue("双指移动应触发 onPinchZoom", callbacks.pinchZoomCalled)
        assertEquals(0.5f, callbacks.lastPinchScaleFactor, 0.001f)
    }

    @Test
    fun `第二指按下取消长按并进入缩放`() = runTest {
        val config = defaultConfig.copy(longPressTimeoutMs = 50L)
        val engine = SectMapTouchEngine(callbacks, this, config)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(100f, 200f)) // 空地按下，启动长按
        engine.onTouch(pinchDown(200f, 200f, 300f, 200f, 10_000_000L))
        assertTrue(engine.state is GestureState.Pinching)
        advanceUntilIdle() // 若长按未被取消会触发 onLongPress
        assertEquals("第二指按下应取消长按", 0, callbacks.longPressCallCount)
    }

    @Test
    fun `双指缩放期间保持高帧率回调 dragStart and dragEnd`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(300f, 300f))
        engine.onTouch(pinchDown(400f, 300f, 500f, 300f, 10_000_000L))
        assertTrue("进入缩放应 onDragStart", callbacks.dragStartCalled)
        engine.onTouch(pinchUpRemaining(400f, 300f, 30_000_000L))
        assertTrue("缩放结束应 onDragEnd", callbacks.dragEndCalled)
    }

    @Test
    fun `双指缩放后剩一指抬起不触发 tap`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(300f, 300f))
        engine.onTouch(pinchDown(400f, 300f, 500f, 300f, 10_000_000L))
        engine.onTouch(pinchMove(400f, 300f, 600f, 300f, 20_000_000L))
        // 一根手指抬起，剩一指在 (400,300)
        engine.onTouch(pinchUpRemaining(400f, 300f, 30_000_000L))
        assertTrue("剩一指应回到 Down", engine.state is GestureState.Down)
        // 剩余手指无位移抬起 → 不应触发 tap（避免缩放后误点）
        engine.onTouch(touchUp(400f, 300f, 40_000_000L))
        assertEquals(GestureState.Idle::class, engine.state::class)
        assertFalse("缩放后不应触发 tap", callbacks.tapCalled)
    }

    @Test
    fun `双指缩放剩一指可继续平移`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(300f, 300f))
        engine.onTouch(pinchDown(400f, 300f, 500f, 300f, 10_000_000L))
        engine.onTouch(pinchUpRemaining(400f, 300f, 30_000_000L))
        // 剩余手指移动超过 slop → Scrolling → onPanCamera
        engine.onTouch(touchMove(450f, 300f, 40_000_000L))
        assertTrue(engine.state is GestureState.Scrolling)
        assertTrue("缩放后单指应可平移", callbacks.panCalled)
    }

    @Test
    fun `CANCEL during pinch returns to Idle without tap`() = runTest {
        val engine = SectMapTouchEngine(callbacks, this, defaultConfig)
        engine.updateViewport(800f, 600f)
        engine.onTouch(touchDown(300f, 300f))
        engine.onTouch(pinchDown(400f, 300f, 500f, 300f, 10_000_000L))
        assertTrue(engine.state is GestureState.Pinching)
        engine.onTouch(touchCancel())
        assertEquals(GestureState.Idle::class, engine.state::class)
        assertFalse("CANCEL 不应触发 tap", callbacks.tapCalled)
    }

    companion object {
        private fun touchDown(x: Float, y: Float, t: Long = 0L) = TouchData(x, y, TouchAction.DOWN, t)
        private fun touchMove(x: Float, y: Float, t: Long = 1_000_000L) = TouchData(x, y, TouchAction.MOVE, t)
        private fun touchUp(x: Float, y: Float, t: Long = 300_000_000L) = TouchData(x, y, TouchAction.UP, t)
        private fun touchCancel() = TouchData(0f, 0f, TouchAction.CANCEL)

        /** 第二根手指按下（双指缩放入口），x1/y1 为主指针，x2/y2 为新增指针 */
        private fun pinchDown(x1: Float, y1: Float, x2: Float, y2: Float, t: Long = 0L) =
            TouchData(x1, y1, TouchAction.DOWN, t, pointerCount = 2, pointer2X = x2, pointer2Y = y2)

        /** 双指移动（缩放更新），返回当前两指位置 */
        private fun pinchMove(x1: Float, y1: Float, x2: Float, y2: Float, t: Long = 1_000_000L) =
            TouchData(x1, y1, TouchAction.MOVE, t, pointerCount = 2, pointer2X = x2, pointer2Y = y2)

        /** 一根手指抬起，剩一指在 (x, y) */
        private fun pinchUpRemaining(x: Float, y: Float, t: Long = 300_000_000L) =
            TouchData(x, y, TouchAction.UP, t, pointerCount = 1)
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
    var lastTapUpX = -1f
    var lastTapUpY = -1f
    var panCalled = false
    var panCallCount = 0
    var buildingDragUpdateCalled = false
    var buildingDragUpdateCallCount = 0
    var buildingDragEndCalled = false
    var goldFingerUpdateCalled = false
    var flingStartedCalled = false
    var flingEndCalled = false
    var dragStartCalled = false
    var dragEndCalled = false
    var longPressCallCount = 0
    var lastLongPressX = -1f
    var lastLongPressY = -1f
    var pinchZoomCalled = false
    var pinchZoomCount = 0
    var lastPinchScaleFactor = 1f
    var lastPinchFocusX = -1f
    var lastPinchFocusY = -1f

    override fun onTap(screenX: Float, screenY: Float) {
        tapCalled = true
        lastTapX = screenX
        lastTapY = screenY
    }

    override fun onTap(downX: Float, downY: Float, upX: Float, upY: Float) {
        tapCalled = true
        lastTapX = downX
        lastTapY = downY
        lastTapUpX = upX
        lastTapUpY = upY
    }

    override fun onPanCamera(dx: Float, dy: Float) {
        panCalled = true
        panCallCount++
    }

    override fun onLongPress(screenX: Float, screenY: Float): LongPressResult {
        longPressCallCount++
        lastLongPressX = screenX
        lastLongPressY = screenY
        return longPressResult
    }

    override fun findBuildingAt(screenX: Float, screenY: Float): Any? =
        if (buildingTargetAtDown) Any() else null

    override fun isInEditMode(): Boolean = inEditMode

    override fun isGoldFingerActive(): Boolean = goldFingerActive

    override fun onBuildingDragUpdate(worldDx: Float, worldDy: Float) {
        buildingDragUpdateCalled = true
        buildingDragUpdateCallCount++
    }

    override fun onBuildingDragEnd() {
        buildingDragEndCalled = true
    }

    override fun onGoldFingerUpdate(screenX: Float, screenY: Float) {
        goldFingerUpdateCalled = true
    }

    override fun onPinchZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        pinchZoomCalled = true
        pinchZoomCount++
        lastPinchScaleFactor = scaleFactor
        lastPinchFocusX = focusX
        lastPinchFocusY = focusY
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
        lastTapUpX = -1f
        lastTapUpY = -1f
        panCalled = false
        panCallCount = 0
        buildingDragUpdateCalled = false
        buildingDragUpdateCallCount = 0
        buildingDragEndCalled = false
        goldFingerUpdateCalled = false
        flingStartedCalled = false
        flingEndCalled = false
        dragStartCalled = false
        dragEndCalled = false
        longPressCallCount = 0
        lastLongPressX = -1f
        lastLongPressY = -1f
        longPressResult = LongPressResult.NotHandled
        buildingTargetAtDown = false
        inEditMode = false
        goldFingerActive = false
        pinchZoomCalled = false
        pinchZoomCount = 0
        lastPinchScaleFactor = 1f
        lastPinchFocusX = -1f
        lastPinchFocusY = -1f
    }
}
