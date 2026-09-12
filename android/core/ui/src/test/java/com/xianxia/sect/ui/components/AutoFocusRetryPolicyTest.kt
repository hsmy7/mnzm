package com.xianxia.sect.ui.components

import com.xianxia.sect.ui.components.AutoFocusNextAction.EXHAUSTED
import com.xianxia.sect.ui.components.AutoFocusNextAction.FOCUS_ALREADY_SET
import com.xianxia.sect.ui.components.AutoFocusNextAction.IME_CONFIRMED
import com.xianxia.sect.ui.components.AutoFocusNextAction.REQUEST_FOCUS
import com.xianxia.sect.ui.components.AutoFocusNextAction.WAIT_ANIMATION
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * autoFocusNextAction 自动聚焦重试决策纯函数测试：
 * 键盘可见即确认终止；动画期等待不烧重试次数；焦点已持有放弃重试；
 * 上限耗尽放弃（绝不无限重试）；动画信号长期卡住时循环有界。
 */
class AutoFocusRetryPolicyTest {

    private val maxRetries = 2
    private val maxIterations = 6

    private fun inputs(
        reRequests: Int = 0,
        iterations: Int = 1,
        imeVisible: Boolean = false,
        animating: Boolean = false,
        hasTextFocus: Boolean = false
    ) = AutoFocusRetryInputs(
        reRequests = reRequests,
        maxRetries = maxRetries,
        iterations = iterations,
        maxIterations = maxIterations,
        imeVisible = imeVisible,
        animating = animating,
        hasTextFocus = hasTextFocus
    )

    @Test
    fun `键盘可见 - 确认终止`() {
        assertEquals(IME_CONFIRMED, autoFocusNextAction(inputs(imeVisible = true)))
    }

    @Test
    fun `动画进行中 - 等待不烧重试次数`() {
        assertEquals(WAIT_ANIMATION, autoFocusNextAction(inputs(animating = true)))
    }

    @Test
    fun `焦点已持有 - 放弃重试防智能输入法重弹`() {
        assertEquals(FOCUS_ALREADY_SET, autoFocusNextAction(inputs(hasTextFocus = true)))
    }

    @Test
    fun `重试上限耗尽 - 放弃`() {
        assertEquals(EXHAUSTED, autoFocusNextAction(inputs(reRequests = maxRetries)))
    }

    @Test
    fun `循环上限耗尽 - 放弃（动画信号卡住时仍有界）`() {
        assertEquals(EXHAUSTED, autoFocusNextAction(inputs(iterations = maxIterations)))
    }

    @Test
    fun `正常路径 - 请求聚焦`() {
        assertEquals(REQUEST_FOCUS, autoFocusNextAction(inputs(reRequests = 1, iterations = 2)))
    }
}
