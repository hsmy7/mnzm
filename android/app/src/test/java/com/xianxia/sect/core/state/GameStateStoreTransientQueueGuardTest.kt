package com.xianxia.sect.core.state

import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * 瞬态队列清空守卫。
 *
 * 反射枚举 [GameStateStoreImpl] 全部 `_pending*` 前缀 StateFlow 字段，灌入
 * 非空标记值后走 [GameStateStoreImpl.reset]——**任何 `_pending*` 字段在
 * reset 后仍非空即失败**（错误消息带操作指引：新增瞬态 flow 必须登记
 * `GameStateStoreImpl.clearTransientQueues`）。reset 与 loadFromSnapshot
 * 两路径共用同一清空函数，故本守卫同时锁定两路径的清空语义。
 *
 * 锁定的不变量：换档后无跨档幽灵弹窗（§0 补充发现：婚配提议队列
 * `_pendingMarriageProposalsFlow` 原在 reset 路径同样漏清）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameStateStoreTransientQueueGuardTest {

    private lateinit var stateStore: GameStateStoreImpl

    @Before
    fun setUp() {
        stateStore = GameStateStoreImpl(
            ApplicationScopeProvider(),
            testGameStateRepository()
        )
    }

    private fun pendingProps(): List<KProperty1<GameStateStoreImpl, *>> =
        GameStateStoreImpl::class.declaredMemberProperties
            .filter { it.name.startsWith("_pending") }
            .onEach { it.isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun pendingMutableProps(): List<KMutableProperty1<GameStateStoreImpl, Any?>> =
        pendingProps().filterIsInstance<KMutableProperty1<GameStateStoreImpl, Any?>>()

    /** 反射对全部 `_pending*` MutableStateFlow 灌入类型擦除下的非空占位 */
    private fun fillAllPendingFlows() {
        for (prop in pendingMutableProps()) {
            val flow = prop.getter.call(stateStore) as? StateFlow<Any?> ?: continue
            when (flow.value) {
                is List<*> -> prop.setter.call(stateStore, listOf(GUARD_MARKER))
                is Map<*, *> -> prop.setter.call(stateStore, mapOf(GUARD_MARKER to GUARD_MARKER))
                else -> prop.setter.call(stateStore, GUARD_MARKER)
            }
        }
        // notificationQueue 同属瞬态（ConcurrentLinkedQueue），经公开入口灌入
        stateStore.enqueueNotification(GUARD_NOTIFICATION)
    }

    private fun violationsAfterReset(): List<String> {
        val violations = mutableListOf<String>()
        for (prop in pendingProps()) {
            val flow = prop.getter.call(stateStore) as? StateFlow<Any?> ?: continue
            val nonEmpty = when (val v = flow.value) {
                null -> false
                is Collection<*> -> v.isNotEmpty()
                is Map<*, *> -> v.isNotEmpty()
                else -> true
            }
            if (nonEmpty) violations += prop.name
        }
        val queueProp = GameStateStoreImpl::class.declaredMemberProperties
            .firstOrNull { it.name == "notificationQueue" }
        if (queueProp != null) {
            queueProp.isAccessible = true
            val queue = queueProp.getter.call(stateStore) as? ConcurrentLinkedQueue<*>
            if (queue?.isNotEmpty() == true) violations += "notificationQueue"
        }
        return violations
    }

    @Test
    fun `reset clears every pending transient field`() = runTest {
        // Act
        stateStore.reset()

        // 前置有效性：reset 后本应全空——若已有残留则守卫捕获到真实缺陷
        val preViolations = violationsAfterReset()
        assertTrue("reset 自身残留瞬态字段: $preViolations", preViolations.isEmpty())

        // Arrange: 灌入非空标记
        fillAllPendingFlows()

        // Act
        stateStore.reset()

        // Assert: 任何 _pending* / notificationQueue 残留即失败（漏登记 clearTransientQueues）
        val violations = violationsAfterReset()
        assertTrue(
            "reset 后仍残留瞬态字段: $violations —— 新增瞬态队列必须登记 " +
                "GameStateStoreImpl.clearTransientQueues()（reset 与 loadFromSnapshot " +
                "两路径共用，守卫）",
            violations.isEmpty()
        )
    }

    @Test
    fun `guard enumeration covers known transient fields`() {
        // 守卫自身有效性：反射口径必须覆盖已知关键瞬态字段（防字段改名后守卫空转）
        val names = pendingProps().map { it.name }
        assertTrue("_pendingBeastAttacksFlow 不在枚举内: $names", "_pendingBeastAttacksFlow" in names)
        assertTrue("_pendingBattleResultFlow 不在枚举内: $names", "_pendingBattleResultFlow" in names)
        assertTrue(
            "_pendingMarriageProposalsFlow 不在枚举内（§0 补充发现的漏清字段）: $names",
            "_pendingMarriageProposalsFlow" in names
        )
    }

    private companion object {
        const val GUARD_MARKER = "transient-queue-guard-marker"
        val GUARD_NOTIFICATION: GameNotification = GameNotification.RecruitFailed(GUARD_MARKER)
    }
}
