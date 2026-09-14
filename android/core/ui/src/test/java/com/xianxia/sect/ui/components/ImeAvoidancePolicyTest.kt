package com.xianxia.sect.ui.components

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ImeAvoidancePolicy 键盘避让位移纯函数测试：
 * API<30 恒零位移（经典 resize/PAN 兜底自身避让，防双重避让）；
 * API30+ 仅本窗口可见时按本窗口真值取大值 × 系数。
 */
class ImeAvoidancePolicyTest {

    @Test
    fun `API 29 - 恒零位移`() {
        assertEquals(0, ImeAvoidancePolicy.computeOffsetPx(
            windowImeBottomPx = 400, trackedImeBottomPx = 400,
            imeVisibleForWindow = true, sdkInt = Build.VERSION_CODES.Q
        ))
    }

    @Test
    fun `API 30 - 本窗口可见按真值位移`() {
        assertEquals("400×0.6=240", 240, ImeAvoidancePolicy.computeOffsetPx(
            windowImeBottomPx = 400, trackedImeBottomPx = 300,
            imeVisibleForWindow = true, sdkInt = Build.VERSION_CODES.R
        ))
    }

    @Test
    fun `API 30 - 本窗口不可见恒零（防跨窗口污染）`() {
        assertEquals(0, ImeAvoidancePolicy.computeOffsetPx(
            windowImeBottomPx = 0, trackedImeBottomPx = 400,
            imeVisibleForWindow = false, sdkInt = Build.VERSION_CODES.R
        ))
    }

    @Test
    fun `API 30 - 高度取本窗口两信号较大值（compose insets 不可用时走跟踪器）`() {
        assertEquals("跟踪器真值兜底 compose insets 历史缺陷", 300, ImeAvoidancePolicy.computeOffsetPx(
            windowImeBottomPx = 0, trackedImeBottomPx = 500,
            imeVisibleForWindow = true, sdkInt = Build.VERSION_CODES.R
        ))
    }
}
