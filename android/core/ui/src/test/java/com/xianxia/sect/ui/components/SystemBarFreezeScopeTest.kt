package com.xianxia.sect.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SystemBarFreezeScope 冻结作用域状态机测试：
 * 计数语义、嵌套冻结、解冻回调、异常防御（荣耀 X70 键盘频闪根治组件）。
 */
class SystemBarFreezeScopeTest {

    @After
    fun tearDown() {
        SystemBarFreezeScope.resetForTest()
    }

    @Test
    fun `enterFreeze 后 isFrozen 为 true`() {
        SystemBarFreezeScope.enterFreeze()
        assertTrue(SystemBarFreezeScope.isFrozen)
    }

    @Test
    fun `enter 与 exit 配对后 isFrozen 恢复 false`() {
        SystemBarFreezeScope.enterFreeze()
        SystemBarFreezeScope.exitFreeze()
        assertFalse(SystemBarFreezeScope.isFrozen)
    }

    @Test
    fun `嵌套冻结 - 内层退出不解除冻结`() {
        SystemBarFreezeScope.enterFreeze()
        SystemBarFreezeScope.enterFreeze()
        SystemBarFreezeScope.exitFreeze()
        assertTrue("内层退出后外层仍冻结", SystemBarFreezeScope.isFrozen)
        SystemBarFreezeScope.exitFreeze()
        assertFalse(SystemBarFreezeScope.isFrozen)
    }

    @Test
    fun `解冻归零时触发监听器且仅触发一次`() {
        var callbackCount = 0
        SystemBarFreezeScope.addOnUnfreezeListener { callbackCount++ }
        SystemBarFreezeScope.enterFreeze()
        SystemBarFreezeScope.exitFreeze()
        assertEquals(1, callbackCount)
        // 再次 exit（未冻结状态）不应触发
        SystemBarFreezeScope.exitFreeze()
        assertEquals(1, callbackCount)
    }

    @Test
    fun `嵌套冻结归零时监听器只触发一次`() {
        var callbackCount = 0
        SystemBarFreezeScope.addOnUnfreezeListener { callbackCount++ }
        SystemBarFreezeScope.enterFreeze()
        SystemBarFreezeScope.enterFreeze()
        SystemBarFreezeScope.exitFreeze()
        assertEquals(0, callbackCount)
        SystemBarFreezeScope.exitFreeze()
        assertEquals(1, callbackCount)
    }

    @Test
    fun `未冻结状态 exit 是安全 no-op 且不触发监听器`() {
        var callbackCount = 0
        SystemBarFreezeScope.addOnUnfreezeListener { callbackCount++ }
        SystemBarFreezeScope.exitFreeze()
        SystemBarFreezeScope.exitFreeze()
        assertFalse(SystemBarFreezeScope.isFrozen)
        assertEquals(0, callbackCount)
    }

    @Test
    fun `移除监听器后解冻不再触发`() {
        var callbackCount = 0
        val listener: () -> Unit = { callbackCount++ }
        SystemBarFreezeScope.addOnUnfreezeListener(listener)
        SystemBarFreezeScope.removeOnUnfreezeListener(listener)
        SystemBarFreezeScope.enterFreeze()
        SystemBarFreezeScope.exitFreeze()
        assertEquals(0, callbackCount)
    }

    @Test
    fun `监听器抛异常不影响解冻语义与其他监听器`() {
        var normalCount = 0
        SystemBarFreezeScope.addOnUnfreezeListener { throw IllegalStateException("宿主已销毁") }
        SystemBarFreezeScope.addOnUnfreezeListener { normalCount++ }
        SystemBarFreezeScope.enterFreeze()
        SystemBarFreezeScope.exitFreeze()
        assertFalse("异常监听器不应阻断解冻", SystemBarFreezeScope.isFrozen)
        assertEquals("正常监听器仍应触发", 1, normalCount)
    }
}
