package com.xianxia.sect.ui.components

import android.app.Activity
import android.view.Window
import com.xianxia.sect.ui.components.InputSessionStateMachine.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * InputSessionStateMachine 输入会话状态机测试：
 * 合法转换链、open/close 幂等（禁止重复动作）、CLOSING→OPENING 禁止直跳
 * （pendingReopen 重放）、**Window 隔离**（不同 Window 会话互不共享状态）、
 * 归属校验、反向验证序列（开→关→开→输入→确认→再开→取消→快速关→快速开）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InputSessionStateMachineTest {

    private companion object {
        const val KEY_SECT = "sect_rename"
        const val KEY_DISCIPLE = "disciple_rename"
    }

    private val windowA: Window by lazy {
        Robolectric.buildActivity(Activity::class.java).setup().get().window
    }

    private val windowB: Window by lazy {
        Robolectric.buildActivity(Activity::class.java).setup().get().window
    }

    @After
    fun tearDown() {
        InputSessionStateMachine.resetForTest()
    }

    private fun phaseOf(window: Window? = null, key: String = KEY_SECT): Phase =
        InputSessionStateMachine.phaseForTest(window, key)

    @Test
    fun `合法链路 CLOSED 至 OPENING 至 OPEN 至 CLOSING 至 CLOSED`() {
        InputSessionStateMachine.open(null, KEY_SECT)
        assertEquals(Phase.OPENING, phaseOf())
        InputSessionStateMachine.markOpen(null, KEY_SECT)
        assertEquals(Phase.OPEN, phaseOf())
        InputSessionStateMachine.close(null, KEY_SECT)
        assertEquals(Phase.CLOSING, phaseOf())
        InputSessionStateMachine.finishClose(null, KEY_SECT)
        assertEquals(Phase.CLOSED, phaseOf())
        assertFalse("会话落定后无活跃会话", InputSessionStateMachine.hasActiveSessions())
    }

    @Test
    fun `open 重复 - OPENING 与 OPEN 均幂等 no-op`() {
        InputSessionStateMachine.open(null, KEY_SECT)
        assertEquals(Phase.OPENING, phaseOf())
        InputSessionStateMachine.open(null, KEY_SECT)
        assertEquals("OPENING 重复 open 不得重开会话", Phase.OPENING, phaseOf())
        InputSessionStateMachine.markOpen(null, KEY_SECT)
        InputSessionStateMachine.open(null, KEY_SECT)
        assertEquals("OPEN 重复 open 不得重弹键盘", Phase.OPEN, phaseOf())
    }

    @Test
    fun `close 重复 - CLOSING 与 CLOSED 均幂等 no-op`() {
        InputSessionStateMachine.open(null, KEY_SECT)
        InputSessionStateMachine.close(null, KEY_SECT)
        assertEquals(Phase.CLOSING, phaseOf())
        InputSessionStateMachine.close(null, KEY_SECT)
        assertEquals("CLOSING 重复 close 不得重复执行关闭流程", Phase.CLOSING, phaseOf())
        InputSessionStateMachine.finishClose(null, KEY_SECT)
        InputSessionStateMachine.close(null, KEY_SECT)
        assertEquals("CLOSED 重复 close 不得产生副作用", Phase.CLOSED, phaseOf())
    }

    @Test
    fun `CLOSING 期间 open - 禁止直跳仅记 pendingReopen 且 CLOSED 后重放一次`() {
        InputSessionStateMachine.open(null, KEY_SECT)
        InputSessionStateMachine.close(null, KEY_SECT)
        assertEquals(Phase.CLOSING, phaseOf())
        InputSessionStateMachine.open(null, KEY_SECT)
        assertEquals("CLOSING 中的打开请求不得跳转 OPENING", Phase.CLOSING, phaseOf())
        InputSessionStateMachine.finishClose(null, KEY_SECT)
        assertEquals("CLOSED 后应重放一次打开请求", Phase.OPENING, phaseOf())
    }

    @Test
    fun `markOpen - 归属不符或非 OPENING 时 no-op`() {
        InputSessionStateMachine.open(null, KEY_SECT)
        // 其他会话名确认不得推进本会话
        InputSessionStateMachine.markOpen(null, KEY_DISCIPLE)
        assertEquals(Phase.OPENING, phaseOf())
        InputSessionStateMachine.markOpen(null, KEY_SECT)
        assertEquals(Phase.OPEN, phaseOf())
        // OPEN 状态重复 markOpen 不得产生副作用
        InputSessionStateMachine.markOpen(null, KEY_SECT)
        assertEquals(Phase.OPEN, phaseOf())
    }

    @Test
    fun `close 归属不符 - 不得关闭他人会话`() {
        InputSessionStateMachine.open(null, KEY_SECT)
        InputSessionStateMachine.close(null, KEY_DISCIPLE)
        assertEquals("非归属会话的 close 不得推进状态", Phase.OPENING, phaseOf())
    }

    @Test
    fun `finishClose 归属不符 - 不得落定他人会话`() {
        InputSessionStateMachine.open(null, KEY_SECT)
        InputSessionStateMachine.close(null, KEY_SECT)
        InputSessionStateMachine.finishClose(null, KEY_DISCIPLE)
        assertEquals("非归属会话的 finishClose 不得落定", Phase.CLOSING, phaseOf())
    }

    @Test
    fun `CLOSED 起始 - finishClose 与 close 均为安全 no-op`() {
        InputSessionStateMachine.finishClose(null, KEY_SECT)
        InputSessionStateMachine.close(null, KEY_SECT)
        assertEquals(Phase.CLOSED, phaseOf())
    }

    // ── Window 隔离（对齐窗口级要求）───────────────────

    @Test
    fun `Window 隔离 - 不同 Window 相同会话名互不共享状态`() {
        InputSessionStateMachine.open(windowA, KEY_SECT)
        assertEquals(Phase.OPENING, phaseOf(windowA))
        // 同 sessionKey 的另一 Window：独立会话（不误认成 A 的会话）
        InputSessionStateMachine.open(windowB, KEY_SECT)
        assertEquals(Phase.OPENING, phaseOf(windowB))
        assertEquals("两窗口会话应并存", 2, InputSessionStateMachine.sessionCountForTest())
        // A 推进 OPEN，B 必须不受影响
        InputSessionStateMachine.markOpen(windowA, KEY_SECT)
        assertEquals(Phase.OPEN, phaseOf(windowA))
        assertEquals(Phase.OPENING, phaseOf(windowB))
        // B 关闭落定，A 保持
        InputSessionStateMachine.close(windowB, KEY_SECT)
        InputSessionStateMachine.finishClose(windowB, KEY_SECT)
        assertEquals(Phase.CLOSED, phaseOf(windowB))
        assertEquals(Phase.OPEN, phaseOf(windowA))
    }

    @Test
    fun `Window 隔离 - 明细摘要可区分会话归属`() {
        InputSessionStateMachine.open(windowA, KEY_SECT)
        InputSessionStateMachine.open(windowB, KEY_DISCIPLE)
        val summary = InputSessionStateMachine.activeSessionsSummary()
        assertTrue("摘要应包含 sect 会话", summary.contains(KEY_SECT))
        assertTrue("摘要应包含 disciple 会话", summary.contains(KEY_DISCIPLE))
        assertTrue("摘要应区分相位", summary.contains("OPENING"))
    }

    // ── 反向验证序列（开→关→开→输入→确认→再开→取消→快速关→快速开）──

    @Test
    fun `反向验证序列 - 每步相位合法且经 CLOSED 后重开合法`() {
        val key = KEY_SECT
        val window = windowA
        // ① 打开
        InputSessionStateMachine.open(window, key)
        assertEquals(Phase.OPENING, phaseOf(window))
        // ② 关闭（未落定）
        InputSessionStateMachine.close(window, key)
        assertEquals(Phase.CLOSING, phaseOf(window))
        // ③ 打开（CLOSING 期间 → pendingReopen，禁止直跳）
        InputSessionStateMachine.open(window, key)
        assertEquals(Phase.CLOSING, phaseOf(window))
        // ④ 落定 → pendingReopen 重放 → OPENING
        InputSessionStateMachine.finishClose(window, key)
        assertEquals(Phase.OPENING, phaseOf(window))
        // ⑤ 输入确认（键盘可见）
        InputSessionStateMachine.markOpen(window, key)
        assertEquals(Phase.OPEN, phaseOf(window))
        // ⑥ 确认 → 关闭
        InputSessionStateMachine.close(window, key)
        assertEquals(Phase.CLOSING, phaseOf(window))
        InputSessionStateMachine.finishClose(window, key)
        assertEquals(Phase.CLOSED, phaseOf(window))
        // ⑦ 再打开（全新会话）
        InputSessionStateMachine.open(window, key)
        assertEquals(Phase.OPENING, phaseOf(window))
        // ⑧ 取消
        InputSessionStateMachine.close(window, key)
        InputSessionStateMachine.finishClose(window, key)
        assertEquals(Phase.CLOSED, phaseOf(window))
        // ⑨ 快速关闭 + 快速重新打开（同帧 dispose 序列）：末拍 finishClose 重放 pendingReopen → OPENING
        InputSessionStateMachine.open(window, key)
        InputSessionStateMachine.close(window, key)
        InputSessionStateMachine.open(window, key)
        InputSessionStateMachine.close(window, key)
        InputSessionStateMachine.finishClose(window, key)
        assertEquals("重开请求在 CLOSING 期间被记录，落定后应重放（禁止吞请求）", Phase.OPENING, phaseOf(window))
        // ⑩ 末态：完整关闭后会话落定，无活跃会话（freeze/日志依赖可安全归零）
        InputSessionStateMachine.close(window, key)
        InputSessionStateMachine.finishClose(window, key)
        assertEquals(Phase.CLOSED, phaseOf(window))
        assertFalse(InputSessionStateMachine.hasActiveSessions())
    }

    @Test
    fun `反向验证序列 - 全程无非法状态机转换`() {
        // 每步调用后断言相位 ∈ {OPENING, OPEN, CLOSING, CLOSED}，且无重复副作用：
        // 记录每步后的会话计数增量（重复 open/close 不得创建/销毁会话）
        InputSessionStateMachine.open(null, KEY_SECT)
        val countAfterFirstOpen = InputSessionStateMachine.sessionCountForTest()
        InputSessionStateMachine.open(null, KEY_SECT)
        assertEquals("重复 open 不得创建新会话", countAfterFirstOpen, InputSessionStateMachine.sessionCountForTest())
        InputSessionStateMachine.close(null, KEY_SECT)
        InputSessionStateMachine.close(null, KEY_SECT)
        assertEquals("重复 close 不得重复销毁流程", countAfterFirstOpen, InputSessionStateMachine.sessionCountForTest())
        InputSessionStateMachine.finishClose(null, KEY_SECT)
        assertEquals("落定后会话清理", countAfterFirstOpen - 1, InputSessionStateMachine.sessionCountForTest())
    }
}

