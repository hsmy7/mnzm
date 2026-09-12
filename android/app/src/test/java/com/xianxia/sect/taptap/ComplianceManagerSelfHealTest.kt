package com.xianxia.sect.taptap

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 合规回调注册自愈机制测试。
 *
 * 守护目标（根因 A）：冷启动路径合规回调注册早于 SDK 就绪导致注册失败后，
 * [ComplianceManager.ensureCallbackRegistered] 必须支持下次调用自愈重试——
 * 注册失败不得造成"永久失去回调"（验证结果丢失 → 卡死登录界面）。
 *
 * SDK 注册入口经 [ComplianceManager.sdkCallbackRegistrar] 注入 Fake（遵循
 * "9.4 优先 Fake 而非 Mock"），避免 Robolectric 依赖 TapTap SDK。
 */
class ComplianceManagerSelfHealTest {

    private var registerCalls = 0

    @Before
    fun setUp() {
        ComplianceManager.sdkCallbackRegistrar = { registerCalls++ }
        ComplianceManager.resetForTest()
    }

    @After
    fun tearDown() {
        ComplianceManager.resetForTest()
    }

    @Test
    fun `首次注册 - 注册SDK监听器并绑定回调`() {
        val callback = FakeComplianceCallback()

        ComplianceManager.ensureCallbackRegistered(callback)

        assertEquals("SDK 监听器应注册一次", 1, registerCalls)
    }

    @Test
    fun `重复注册 - 幂等不重复注册SDK监听器但更新回调`() {
        val first = FakeComplianceCallback()
        val second = FakeComplianceCallback()

        ComplianceManager.ensureCallbackRegistered(first)
        ComplianceManager.ensureCallbackRegistered(second)

        assertEquals("SDK 监听器只应注册一次", 1, registerCalls)
    }

    @Test
    fun `注册失败 - 下次调用自愈重试成功`() {
        // 第一次注册抛异常（模拟冷启动路径 SDK 未就绪）
        ComplianceManager.sdkCallbackRegistrar = { throw IllegalStateException("SDK 未就绪") }
        ComplianceManager.ensureCallbackRegistered(FakeComplianceCallback())
        assertEquals("失败注册不应计数成功", 0, registerCalls)

        // SDK 就绪后再次调用 → 自愈重试成功
        ComplianceManager.sdkCallbackRegistrar = { registerCalls++ }
        ComplianceManager.ensureCallbackRegistered(FakeComplianceCallback())

        assertEquals("SDK 就绪后应自愈注册成功", 1, registerCalls)
    }

    @Test
    fun `注册成功后 - 验证结果回调转发到最新绑定的callback`() {
        val first = FakeComplianceCallback()
        val second = FakeComplianceCallback()
        ComplianceManager.ensureCallbackRegistered(first)
        ComplianceManager.ensureCallbackRegistered(second)

        ComplianceManager.handleResult(ComplianceManager.CODE_LOGIN_SUCCESS, null)

        assertTrue("最新 callback 应收到登录成功回调", second.loginSuccess)
        assertFalse("旧 callback 不应收到回调", first.loginSuccess)
    }

    @Test
    fun `registerCallback 兼容入口 - 语义与 ensureCallbackRegistered 一致`() {
        ComplianceManager.registerCallback(FakeComplianceCallback())
        ComplianceManager.registerCallback(FakeComplianceCallback())

        assertEquals("兼容入口同样幂等", 1, registerCalls)
    }

    private class FakeComplianceCallback : ComplianceManager.ComplianceCallback {
        var loginSuccess = false

        override fun onLoginSuccess() {
            loginSuccess = true
        }

        override fun onExited() = Unit
        override fun onSwitchAccount() = Unit
        override fun onPeriodRestrict() = Unit
        override fun onDurationLimit() = Unit
        override fun onAgeLimit() = Unit
        override fun onNetworkError() = Unit
        override fun onRealNameStop() = Unit
    }
}
