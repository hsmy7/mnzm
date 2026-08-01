package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * load/reset 与锁外 assemble 协程竞态守卫测试（2026-08-01，D2 修复验证）。
 *
 * 修复前：loadFromSnapshot 锁外直接 `_disciplesFlow.value = assembleAll()`
 * 不经 assembleDispatcher，与排队中的增量组装任务并发交错——陈旧增量可能
 * 用过期事务的 changedIds 归并新列表，覆盖加载结果（丢弟子/陈尸）。
 * 修复后：状态版本号作废陈旧任务 + load 组装投递同一单线程调度器。
 *
 * 本测试模拟"update 提交后 → load 启动"窗口，断言最终 _disciplesFlow
 * 恒等于加载列表（无论交错顺序如何，正确性不变）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameStateStoreLoadRaceTest {

    private fun disciple(id: Int, name: String = "弟子$id"): Disciple =
        Disciple(id = id.toString(), name = name, realm = 5, realmLayer = 1)

    private fun store(): GameStateStoreImpl {
        val s = GameStateStoreImpl(ApplicationScopeProvider(), mock(GameStateRepository::class.java))
        // 测试模式：允许主线程调用 update（Robolectric 单元测试专用）
        s.unsafeAllowMainThreadUpdateForTest = true
        return s
    }

    @Test
    fun `load 完成后陈旧增量不覆盖新列表`() = runBlocking {
        val store = store()

        // 准备旧数据（3 弟子）+ 提交一次 update（排队增量组装）
        store.update {
            discipleTables.insert(disciple(1))
            discipleTables.insert(disciple(2))
            discipleTables.insert(disciple(3))
        }

        // 立即 load（新列表只含 2 个不同弟子）——与排队中的陈旧组装竞争
        store.loadFromSnapshot(
            gameData = GameData(),
            disciples = listOf(disciple(10, "新弟子10"), disciple(11, "新弟子11")),
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            storageBags = emptyList(),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = true,
            isLoading = false,
            isSaving = false
        )

        // 等待 assembleDispatcher 队列排空（陈旧任务作废 + load 组装完成）
        TestPolling.awaitCondition(
            "load 组装完成（陈旧增量作废）",
            condition = { store.disciples.value.map { it.id } == listOf("10", "11") },
            stateSnapshot = { store.disciples.value.map { it.id }.toString() }
        )

        val finalDisciples = store.disciples.value
        assertEquals("加载列表应完整（2 个新弟子）", listOf("10", "11"), finalDisciples.map { it.id })
        assertEquals("不应残留旧弟子（陈尸）", "新弟子10", finalDisciples.first().name)
    }

    @Test
    fun `load 之后的新 update 在加载列表基础上正确合并`() = runBlocking {
        val store = store()

        store.loadFromSnapshot(
            gameData = GameData(),
            disciples = listOf(disciple(1), disciple(2)),
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            storageBags = emptyList(),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = true,
            isLoading = false,
            isSaving = false
        )
        TestPolling.awaitCondition(
            "load 组装完成",
            condition = { store.disciples.value.map { it.id } == listOf("1", "2") },
            stateSnapshot = { store.disciples.value.map { it.id }.toString() }
        )

        // load 后的新 update：列级写入弟子 1 的修为
        store.update {
            discipleTables.cultivations[1] = 1234.5
        }
        TestPolling.awaitCondition(
            "列级写入聚合生效",
            condition = {
                store.disciples.value.size == 2 &&
                    store.disciples.value.firstOrNull { it.id == "1" }?.cultivation == 1234.5
            },
            stateSnapshot = { store.disciples.value.map { "${it.id}:${it.cultivation}" }.toString() }
        )

        val finalDisciples = store.disciples.value
        assertEquals("加载列表基础上合并（2 弟子）", listOf("1", "2"), finalDisciples.map { it.id })
        assertEquals("列级写入应生效", 1234.5, finalDisciples.first { it.id == "1" }.cultivation, 0.0)
    }

    @Test
    fun `reset 后陈旧任务不复活旧列表`() = runBlocking {
        val store = store()

        store.update {
            discipleTables.insert(disciple(1))
            discipleTables.insert(disciple(2))
        }
        store.reset()
        TestPolling.awaitCondition(
            "reset 生效列表清空",
            condition = { store.disciples.value.isEmpty() },
            stateSnapshot = { store.disciples.value.map { it.id }.toString() }
        )

        assertEquals("reset 后列表应为空", emptyList<String>(), store.disciples.value.map { it.id })
    }
}
