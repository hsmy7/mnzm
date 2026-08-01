package com.xianxia.sect.ui.components

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [canRenderDialogs] 生命周期门控测试（Bugly #3098）：
 * Activity 销毁窗口期（INITIALIZED/DESTROYED）禁止渲染 Dialog，
 * STARTED/RESUMED 允许。
 */
class DialogLifecycleTest {

    @Test
    fun `canRenderDialogs - INITIALIZED 与 DESTROYED 禁止`() {
        assertFalse(Lifecycle.State.INITIALIZED.canRenderDialogs())
        assertFalse(Lifecycle.State.DESTROYED.canRenderDialogs())
    }

    @Test
    fun `canRenderDialogs - STARTED 与 RESUMED 允许`() {
        assertTrue(Lifecycle.State.STARTED.canRenderDialogs())
        assertTrue(Lifecycle.State.RESUMED.canRenderDialogs())
    }

    @Test
    fun `canRenderDialogs - CREATED 禁止（销毁窗口期典型状态）`() {
        assertFalse(Lifecycle.State.CREATED.canRenderDialogs())
    }
}
