package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DiscipleDelegate 招募防抖状态机测试。
 *
 * 守卫目标：
 * 1. 防抖占位随协程实际执行注册——engineScope 取消窗口（launch 不执行 block）
 *    零残留。若在点击时占位：取消窗口 launch 不执行、finally 不执行 →
 *    占位永久残留 → 该弟子后续全部点击被静默拦截（手动招募失效）；
 * 2. 拦截分支（空 id）经 onRecruitBlocked 可见化，不得静默；
 * 3. 协程完成后占位清理，重复点击可再次发起。
 *
 * 观测句柄：[GameEngine.launchOnEngine] 为成员函数（可 stub/可 verify）——
 * 以派发次数作为"一次招募成功进入引擎"的观测（拦截分支在派发前 return）。
 * 招募业务经扩展函数执行（relaxed mock 链安全返回空结果），测试不校验
 * 业务结果（业务路径由 DiscipleFacadeImplRecruitTest / C++ 对拍覆盖）。
 */
class DiscipleDelegateRecruitGuardTest {

    private class Ui {
        val engine = mockk<GameEngine>(relaxed = true)
        var blockedReason: String? = null
        val delegate = DiscipleDelegate(
            gameEngine = engine,
            dispatcher = Dispatchers.Unconfined,
            onRecruitBlocked = { blockedReason = it }
        )
    }

    /** 模拟引擎协程执行体（Unconfined 语义；execute=false 模拟取消窗口：block 不执行） */
    private fun installLaunchExecutor(engine: GameEngine, execute: Boolean = true) {
        every { engine.launchOnEngine(any()) } answers {
            if (execute) {
                val suspendBlock = arg<suspend CoroutineScope.() -> Unit>(0)
                runBlocking { suspendBlock.invoke(CoroutineScope(coroutineContext)) }
            }
            mockk<Job>()
        }
    }

    @Test
    fun `取消窗口 - 占位不残留 恢复后再次点击可发起`() {
        val ui = Ui()
        // 取消窗口：launch 从不执行 block（点击时占位会在此永久残留）
        installLaunchExecutor(ui.engine, execute = false)

        ui.delegate.recruitDiscipleFromList("r1")

        assertNull("取消窗口内不应出现拦截提示", ui.blockedReason)
        verify(exactly = 1) { ui.engine.launchOnEngine(any()) }
        // 引擎恢复：再次点击必须正常发起（占位未残留——点击时占位的实现此处会被 duplicate 静默拦截）
        installLaunchExecutor(ui.engine, execute = true)
        ui.delegate.recruitDiscipleFromList("r1")

        assertNull("恢复后不应出现拦截提示", ui.blockedReason)
        verify(exactly = 2) { ui.engine.launchOnEngine(any()) }
    }

    @Test
    fun `正常流程 - 协程结束后占位清理 重复点击可再次发起`() {
        val ui = Ui()
        installLaunchExecutor(ui.engine)

        ui.delegate.recruitDiscipleFromList("r1")
        ui.delegate.recruitDiscipleFromList("r1")

        assertNull("不应出现拦截提示", ui.blockedReason)
        verify(exactly = 2) { ui.engine.launchOnEngine(any()) }
    }

    @Test
    fun `一键招募完成后 - isRecruitingAll 复位 手动招募不受阻`() {
        val ui = Ui()
        installLaunchExecutor(ui.engine)
        // mock 环境下 recruitAllFromList 扩展经 mock dispatcher（relaxed 泛型
        // 返回 null）抛出 → 一键协程 catch 后 finally 复位 isRecruitingAll——
        // 这正是"异常路径也必须复位"的守卫（异常时 isRecruitingAll 若不复位，
        // 手动招募会被永久拦截）
        ui.delegate.recruitAllDisciples()
        ui.delegate.recruitDiscipleFromList("r1")

        assertEquals("一键招募异常，请重试", ui.blockedReason)
        verify(exactly = 2) { ui.engine.launchOnEngine(any()) }
    }

    @Test
    fun `空 id - 拦截且可见化 不派发`() {
        val ui = Ui()

        ui.delegate.recruitDiscipleFromList("")

        assertEquals("招募操作无效，请重试", ui.blockedReason)
        verify(exactly = 0) { ui.engine.launchOnEngine(any()) }
    }
}
