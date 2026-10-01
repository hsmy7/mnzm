package com.xianxia.sect.core.state

import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
 * 锁定的不变量：换档后无任何跨档幽灵弹窗/队列残留。
 *
 * 枚举面仅限 `_pending*` 前缀 StateFlow 字段；非该命名的瞬态容器不在
 * 反射口径内（新增瞬态容器按 `_pending*` 命名即自动纳入守卫）。
 *
 * 灌值口径：经 getter 取出的 flow 引用强转 `MutableStateFlow` 后直写
 * `.value`（类型擦除下的非空占位）——全部瞬态 flow 均为 `val` 声明，
 * 属性层面无 setter，禁止按 `KMutableProperty1` 过滤（会得到空集使守卫空转）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameStateStoreTransientQueueGuardTest {

    private lateinit var stateStore: GameStateStoreImpl

    @Before
    fun setUp() {
        stateStore = GameStateStoreImpl(
            ApplicationScopeProvider()
        )
    }

    private fun pendingProps(): List<KProperty1<GameStateStoreImpl, *>> =
        GameStateStoreImpl::class.declaredMemberProperties
            .filter { it.name.startsWith("_pending") }
            .onEach { it.isAccessible = true }

    /** 反射对全部 `_pending*` StateFlow 灌入类型擦除下的非空占位（经 flow.value 写入） */
    @Suppress("UNCHECKED_CAST")
    private fun fillAllPendingFlows() {
        for (prop in pendingProps()) {
            val flow = prop.getter.call(stateStore) as? MutableStateFlow<Any?> ?: continue
            when (flow.value) {
                is List<*> -> flow.value = listOf(GUARD_MARKER)
                is Map<*, *> -> flow.value = mapOf(GUARD_MARKER to GUARD_MARKER)
                else -> flow.value = GUARD_MARKER
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
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

        // Assert: 任何 _pending* 残留即失败（漏登记 clearTransientQueues）
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
            "_pendingBattleRewardCardsFlow 不在枚举内: $names",
            "_pendingBattleRewardCardsFlow" in names
        )
    }

    private companion object {
        const val GUARD_MARKER = "transient-queue-guard-marker"
    }
}
