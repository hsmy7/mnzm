package com.xianxia.sect.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录/防沉迷验证流程状态机全转移矩阵测试。
 *
 * 守护目标（docs/login-flow-state-machine.md）：
 * - 根因 B 回归守卫：退出认证/切换账号后再登录必须能重新触发防沉迷验证
 *   （历史缺陷：一次性标记永不复位导致验证被永久跳过，卡死登录界面）
 * - 根因 C 回归守卫：SDK 就绪契约（resumedReady 前置 + 重试立即启动）
 * - 超时/网络错误可恢复路径（不再永久卡死）
 * - 单飞幂等（重复事件 no-op，不重复启动 startup）
 * - 登出全出口统一四件套副作用
 */
class LoginFlowStateMachineTest {

    private val host = FakeLoginFlowHost()
    private val machine = LoginFlowStateMachine(host)

    // ── 正常路径 ──

    @Test
    fun `正常登录 - 登录成功到验证成功进入模式选择`() {
        machine.onEvent(LoginFlowEvent.LoginRequested)
        assertEquals(LoginFlowState.LoggingIn, machine.state)

        machine.onEvent(LoginFlowEvent.LoginSuccess("union-1"))
        assertEquals(LoginFlowState.VerifyPending, machine.state)

        machine.onEvent(LoginFlowEvent.ActivityResumed)
        assertEquals(LoginFlowState.Verifying, machine.state)

        machine.onEvent(LoginFlowEvent.VerificationSuccess)
        assertEquals(LoginFlowState.Verified, machine.state)

        assertEffects(
            "setLoginTimeout(true)",
            "setLoginTimeout(false)",
            "start(union-1)",
            "setVerifyTimeout(true)",
            "setVerifyTimeout(false)",
            "showModeSelect"
        )
    }

    @Test
    fun `登录成功复位resumedReady - 转场窗口期需等待登录后的RESUMED才启动`() {
        machine.onEvent(LoginFlowEvent.ActivityResumed) // 登录前已 RESUMED（旧会话残留）
        machine.onEvent(LoginFlowEvent.LoginRequested)
        machine.onEvent(LoginFlowEvent.LoginSuccess("union-1"))

        // 新会话必须重置 RESUMED 前置（转场窗口期防护）——不能立即启动
        assertEquals(LoginFlowState.VerifyPending, machine.state)

        // 登录成功后的稳定 RESUMED（onResume / 发送方补发）→ 启动验证
        machine.onEvent(LoginFlowEvent.ActivityResumed)
        assertEquals(LoginFlowState.Verifying, machine.state)
        assertEffects("setLoginTimeout(true)", "setLoginTimeout(false)", "start(union-1)", "setVerifyTimeout(true)")
    }

    @Test
    fun `登录失败 - 回 Idle 且取消登录超时`() {
        machine.onEvent(LoginFlowEvent.LoginRequested)
        machine.onEvent(LoginFlowEvent.LoginFailure("用户取消"))

        assertEquals(LoginFlowState.Idle, machine.state)
        assertEffects("setLoginTimeout(true)", "setLoginTimeout(false)")
    }

    @Test
    fun `登录超时 - 回 Idle 且取消登录超时`() {
        machine.onEvent(LoginFlowEvent.LoginRequested)
        machine.onEvent(LoginFlowEvent.LoginTimeout)

        assertEquals(LoginFlowState.Idle, machine.state)
        assertEffects("setLoginTimeout(true)", "setLoginTimeout(false)")
    }

    // ── 根因 B 回归守卫：退出/切换账号后再登录 ──

    @Test
    fun `根因B守卫 - 退出认证后再登录可重新触发验证`() {
        loginToVerifying()

        machine.onEvent(LoginFlowEvent.VerificationExited)
        assertEquals(LoginFlowState.Idle, machine.state)
        host.effects.clear()

        // 再次完整登录：必须能重新走到 Verifying（历史缺陷：被一次性标记跳过）。
        // 新会话重置 RESUMED 前置 → 需登录后的 ActivityResumed（发送方补发/onResume）
        machine.onEvent(LoginFlowEvent.LoginRequested)
        machine.onEvent(LoginFlowEvent.LoginSuccess("union-2"))
        assertEquals(LoginFlowState.VerifyPending, machine.state)
        machine.onEvent(LoginFlowEvent.ActivityResumed)

        assertEquals(LoginFlowState.Verifying, machine.state)
        assertTrueContains("setLoginTimeout(false)")
        assertTrueContains("start(union-2)")
        assertTrueContains("setVerifyTimeout(true)")
    }

    @Test
    fun `根因B守卫 - 实名认证界面切换账号后再登录可重新触发验证`() {
        loginToVerifying()
        machine.onEvent(LoginFlowEvent.VerificationTimeout)
        assertEquals(LoginFlowState.VerificationFailed, machine.state)
        host.effects.clear()

        machine.onEvent(LoginFlowEvent.LogoutRequested)
        assertEquals(LoginFlowState.Idle, machine.state)
        host.effects.clear()

        machine.onEvent(LoginFlowEvent.LoginRequested)
        machine.onEvent(LoginFlowEvent.LoginSuccess("union-3"))
        machine.onEvent(LoginFlowEvent.ActivityResumed)

        assertEquals(LoginFlowState.Verifying, machine.state)
        assertTrueContains("start(union-3)")
    }

    // ── 冷启动路径 ──

    @Test
    fun `冷启动已认证 - 直接进模式选择`() {
        machine.onEvent(LoginFlowEvent.ColdStart(complianceVerified = true, unionId = "union-1"))

        assertEquals(LoginFlowState.Verified, machine.state)
        assertEffects("showModeSelect")
    }

    @Test
    fun `冷启动未认证 - 显示实名认证界面`() {
        machine.onEvent(LoginFlowEvent.ColdStart(complianceVerified = false, unionId = "union-1"))

        assertEquals(LoginFlowState.VerificationFailed, machine.state)
        assertEffects("showVerify")
    }

    @Test
    fun `冷启动缺 unionId - 清会话回登录界面`() {
        machine.onEvent(LoginFlowEvent.ColdStart(complianceVerified = false, unionId = null))

        assertEquals(LoginFlowState.Idle, machine.state)
        assertEffects("clearLogout", "showLogin")
    }

    // ── 超时恢复与重试 ──

    @Test
    fun `验证超时 - 恢复SDK状态并显示实名认证界面可重试`() {
        loginToVerifying()
        machine.onEvent(LoginFlowEvent.VerificationTimeout)

        assertEquals(LoginFlowState.VerificationFailed, machine.state)
        assertEffects(
            "setLoginTimeout(true)",
            "setLoginTimeout(false)",
            "start(union-1)",
            "setVerifyTimeout(true)",
            "setVerifyTimeout(false)",
            "recoverSdk",
            "toast(实名认证无响应，请点击重新认证)",
            "showVerify"
        )
    }

    @Test
    fun `重试验证 - 实名认证界面点开始认证立即启动（resumedReady 已置位）`() {
        loginToVerifying()
        machine.onEvent(LoginFlowEvent.VerificationTimeout)
        host.effects.clear()

        machine.onEvent(LoginFlowEvent.RetryVerification)

        assertEquals(LoginFlowState.Verifying, machine.state)
        assertEffects("start(union-1)", "setVerifyTimeout(true)")
    }

    @Test
    fun `网络错误 - 保留会话进入实名认证界面可重试`() {
        loginToVerifying()
        machine.onEvent(LoginFlowEvent.VerificationNetworkError)

        assertEquals(LoginFlowState.VerificationFailed, machine.state)
        assertTrueContains("toast(网络连接异常，请检查网络后重试)")
        assertTrueContains("showVerify")
        // 不得清会话（可重试语义）
        assertFalseContains("clearLogout")
    }

    // ── 单飞幂等 ──

    @Test
    fun `单飞 - 验证中重复成功回调只转移一次`() {
        loginToVerifying()
        machine.onEvent(LoginFlowEvent.VerificationSuccess)
        val verifiedEffects = host.effects.toList()
        host.effects.clear()

        // 重复 VerificationSuccess（SDK 回调重复/回调转发竞态）→ no-op
        machine.onEvent(LoginFlowEvent.VerificationSuccess)
        assertEquals(LoginFlowState.Verified, machine.state)
        assertEquals(emptyList<String>(), host.effects)
        assertTrue("首次转移应含 showModeSelect", verifiedEffects.contains("showModeSelect"))
    }

    @Test
    fun `单飞 - VerifyPending 中重复 ActivityResumed 不重复启动`() {
        machine.onEvent(LoginFlowEvent.LoginRequested)
        machine.onEvent(LoginFlowEvent.LoginSuccess("union-1"))
        machine.onEvent(LoginFlowEvent.ActivityResumed)
        assertEquals(LoginFlowState.Verifying, machine.state)
        val startCount = host.effects.count { it.startsWith("start(") }
        host.effects.clear()

        // 后台→前台切换再触发 → 状态已离开 VerifyPending，no-op
        machine.onEvent(LoginFlowEvent.ActivityResumed)
        assertEquals(LoginFlowState.Verifying, machine.state)
        assertEquals(0, host.effects.count { it.startsWith("start(") })
        assertEquals("首次应仅启动一次验证", 1, startCount)
    }

    @Test
    fun `单飞 - 已进模式选择后登录事件 no-op`() {
        machine.onEvent(LoginFlowEvent.LoginRequested)
        machine.onEvent(LoginFlowEvent.LoginSuccess("union-1"))
        machine.onEvent(LoginFlowEvent.ActivityResumed)
        machine.onEvent(LoginFlowEvent.VerificationSuccess)
        host.effects.clear()

        machine.onEvent(LoginFlowEvent.LoginRequested) // Verified 中登录请求非法 → no-op
        assertEquals(LoginFlowState.Verified, machine.state)
        assertEquals(emptyList<String>(), host.effects)
    }

    // ── 登出全出口 ──

    @Test
    fun `登出 - 各状态 LogoutRequested 统一清会话回登录界面`() {
        // Verifying 中登出
        loginToVerifying()
        machine.onEvent(LoginFlowEvent.LogoutRequested)
        assertEquals(LoginFlowState.Idle, machine.state)
        assertTrueContains("clearLogout")
        assertTrueContains("showLogin")

        // VerificationFailed 中登出
        host.effects.clear()
        loginToVerifying()
        machine.onEvent(LoginFlowEvent.VerificationTimeout)
        machine.onEvent(LoginFlowEvent.LogoutRequested)
        assertEquals(LoginFlowState.Idle, machine.state)
        assertTrueContains("clearLogout")

        // Verified 中登出（模式选择界面退出登录）
        host.effects.clear()
        loginToVerifying()
        machine.onEvent(LoginFlowEvent.VerificationSuccess)
        machine.onEvent(LoginFlowEvent.LogoutRequested)
        assertEquals(LoginFlowState.Idle, machine.state)
        assertTrueContains("clearLogout")
    }

    // ── 工具 ──

    private fun loginToVerifying() {
        machine.onEvent(LoginFlowEvent.LoginRequested)
        machine.onEvent(LoginFlowEvent.LoginSuccess("union-1"))
        machine.onEvent(LoginFlowEvent.ActivityResumed)
        assertEquals(LoginFlowState.Verifying, machine.state)
    }

    private fun assertEffects(vararg expected: String) {
        assertEquals(expected.toList(), host.effects)
    }

    private fun assertTrueContains(effect: String) {
        assertTrue("副作用列表应包含 $effect，实际: ${host.effects}", host.effects.contains(effect))
    }

    private fun assertFalseContains(effect: String) {
        assertFalse("副作用列表不应包含 $effect，实际: ${host.effects}", host.effects.contains(effect))
    }

    private class FakeLoginFlowHost : LoginFlowHost {
        val effects = mutableListOf<String>()

        override fun onStartComplianceVerification(unionId: String) {
            effects += "start($unionId)"
        }

        override fun onShowComplianceVerificationScreen() {
            effects += "showVerify"
        }

        override fun onShowModeSelection() {
            effects += "showModeSelect"
        }

        override fun onShowLoginScreen() {
            effects += "showLogin"
        }

        override fun onClearSessionAndLogout() {
            effects += "clearLogout"
        }

        override fun onShowToast(message: String) {
            effects += "toast($message)"
        }

        override fun onRecoverSdkRunningState() {
            effects += "recoverSdk"
        }

        override fun onSetVerificationTimeout(active: Boolean) {
            effects += if (active) "setVerifyTimeout(true)" else "setVerifyTimeout(false)"
        }

        override fun onSetLoginTimeout(active: Boolean) {
            effects += if (active) "setLoginTimeout(true)" else "setLoginTimeout(false)"
        }

        override fun onLog(message: String) {
            // 转移日志不参与副作用断言
        }
    }
}
