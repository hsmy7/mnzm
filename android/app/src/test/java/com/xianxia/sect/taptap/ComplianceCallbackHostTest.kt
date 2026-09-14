package com.xianxia.sect.taptap

import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.data.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 防沉迷合规回调进程级宿主测试。
 *
 * 守卫契约：登录用户进入游戏后（MainActivity 已 finish），时长/时间/年龄限制
 * 回调必须转发到当前前台游戏窗口——若回调被 Activity 实例的
 * `isFinishing/isDestroyed` 检查静默丢弃，游戏内限制提示将永远无法弹出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComplianceCallbackHostTest {

    /** Fake 窗口端口——记录转发结果，postToUi 同步执行（模拟主线程即时投递） */
    private class FakeWindowPort : ComplianceCallbackHost.WindowPort {
        var alive = true
        var postCount = 0
        var restrictCalls = mutableListOf<Pair<String, String>>()
        var ageLimitCalls = 0
        var loginSuccessCalls = 0
        var exitedCalls = 0
        var networkErrorCalls = 0

        override fun postToUi(block: () -> Unit) {
            postCount++
            block()
        }

        override fun isAlive(): Boolean = alive

        override fun onLoginSuccess() {
            loginSuccessCalls++
        }

        override fun onExited() {
            exitedCalls++
        }

        override fun onNetworkError() {
            networkErrorCalls++
        }

        override fun onRestrict(title: String, message: String) {
            restrictCalls.add(title to message)
        }

        override fun onAgeLimit() {
            ageLimitCalls++
        }
    }

    private lateinit var host: ComplianceCallbackHost
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        sessionManager = SessionManager(ApplicationProvider.getApplicationContext())
        host = ComplianceCallbackHost(sessionManager)
    }

    @Test
    fun `durationLimit - 游戏窗口存活时转发到游戏窗口`() {
        val game = FakeWindowPort()
        val login = FakeWindowPort()
        host.registerLoginWindow(login)
        host.registerGameWindow(game)
        sessionManager.complianceVerified = true

        host.callback.onDurationLimit()

        assertEquals(1, game.restrictCalls.size)
        assertEquals("时长限制", game.restrictCalls[0].first)
        assertEquals("您今日的游戏时长已用尽，请合理安排游戏时间。", game.restrictCalls[0].second)
        assertEquals("游戏窗口优先，登录窗口不应收到", 0, login.restrictCalls.size)
        assertFalse("限制回调必须重置合规验证标记", sessionManager.complianceVerified)
    }

    @Test
    fun `periodRestrict - 无游戏窗口时回退登录窗口`() {
        val login = FakeWindowPort()
        host.registerLoginWindow(login)

        host.callback.onPeriodRestrict()

        assertEquals(1, login.restrictCalls.size)
        assertEquals("时间限制", login.restrictCalls[0].first)
    }

    @Test
    fun `durationLimit - 两窗口均不存在时静默丢弃不崩溃`() {
        host.callback.onDurationLimit()
        // 无窗口：仅重置合规标记，不抛异常
        assertFalse(sessionManager.complianceVerified)
    }

    @Test
    fun `ageLimit - 游戏窗口优先转发`() {
        val game = FakeWindowPort()
        val login = FakeWindowPort()
        host.registerLoginWindow(login)
        host.registerGameWindow(game)

        host.callback.onAgeLimit()

        assertEquals(1, game.ageLimitCalls)
        assertEquals(0, login.ageLimitCalls)
    }

    @Test
    fun `ageLimit - 游戏窗口已销毁时回退登录窗口`() {
        val game = FakeWindowPort().apply { alive = false }
        val login = FakeWindowPort()
        host.registerLoginWindow(login)
        host.registerGameWindow(game)

        host.callback.onAgeLimit()

        assertEquals("销毁窗口不得接收回调", 0, game.ageLimitCalls)
        assertEquals(1, login.ageLimitCalls)
    }

    @Test
    fun `loginSuccess - 转发登录窗口`() {
        val login = FakeWindowPort()
        host.registerLoginWindow(login)

        host.callback.onLoginSuccess()

        assertEquals(1, login.loginSuccessCalls)
    }

    @Test
    fun `loginSuccess - 登录窗口不存在时丢弃（游戏内不适用）`() {
        val game = FakeWindowPort()
        host.registerGameWindow(game)
        sessionManager.complianceVerified = true

        host.callback.onLoginSuccess()

        assertEquals("登录流程回调不应转发游戏窗口", 0, game.loginSuccessCalls)
        assertTrue("登录流程回调不重置合规标记", sessionManager.complianceVerified)
    }

    @Test
    fun `exited switchAccount realNameStop - 统一转发登录窗口退出响应`() {
        val login = FakeWindowPort()
        host.registerLoginWindow(login)

        host.callback.onExited()
        host.callback.onSwitchAccount()
        host.callback.onRealNameStop()

        assertEquals(3, login.exitedCalls)
    }

    @Test
    fun `clearGameWindow - 身份校验不误清新实例`() {
        val oldGame = FakeWindowPort()
        val newGame = FakeWindowPort()
        host.registerGameWindow(oldGame)
        host.registerGameWindow(newGame)

        // 旧窗口 onStop 迟到清除：不得清掉新窗口
        host.clearGameWindow(oldGame)

        host.callback.onDurationLimit()
        assertEquals("新窗口应保持注册", 1, newGame.restrictCalls.size)
        assertEquals(0, oldGame.restrictCalls.size)
    }

    @Test
    fun `networkError - 转发登录窗口`() {
        val login = FakeWindowPort()
        host.registerLoginWindow(login)

        host.callback.onNetworkError()

        assertEquals(1, login.networkErrorCalls)
    }
}
