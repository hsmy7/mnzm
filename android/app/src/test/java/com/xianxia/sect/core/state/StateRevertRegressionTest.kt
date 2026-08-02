package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.doThrow
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * loadFromSnapshot 失败回滚回归测试（Q-3 填充，2026-08-02）。
 *
 * 守卫（C-8 rollbackLoad 提取 + P-5 聚合恢复）：
 * 1. 加载失败后旧值全部恢复（gameData/disciples/聚合/战力）
 * 2. 回滚后快照缓存与恢复数据一致（aggregatesGen 对齐，getter 不误重算）
 * 3. 正常加载路径不受影响
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateRevertRegressionTest {

    private fun disciple(id: Int): Disciple =
        Disciple(id = id.toString(), name = "弟子$id", realm = 5, realmLayer = 1)

    @Test
    fun `loadFromSnapshot 失败后旧状态全部恢复`() = runBlocking {
        // 注入失败：markAllDirty 抛异常 → loadFromSnapshot 走回滚路径
        val repo = Mockito.mock(GameStateRepository::class.java)
        doThrow(RuntimeException("模拟存储失败")).`when`(repo).markAllDirty()
        val store = GameStateStoreImpl(ApplicationScopeProvider(), repo)
        store.unsafeAllowMainThreadUpdateForTest = true

        // 建立旧状态（3 弟子 + 游戏数据）
        store.update {
            for (i in 1..3) discipleTables.insert(disciple(i))
            gameData = gameData.copy(sectName = "旧宗门", gameYear = 10)
        }
        TestPolling.awaitCondition("旧状态聚合就绪") { store.discipleAggregatesSnapshot.size == 3 }

        // 执行会失败的加载（新档 5 弟子）——markAllDirty 失败 → rollbackLoad → rethrow
        val newDisciples = (1..5).map { disciple(it) }
        val thrown = runBlocking {
            try {
                store.loadFromSnapshot(
                    gameData = GameData(sectName = "新宗门", gameYear = 99),
                    disciples = newDisciples,
                    equipmentStacks = emptyList(), equipmentInstances = emptyList(),
                    manualStacks = emptyList(), manualInstances = emptyList(),
                    pills = emptyList(), materials = emptyList(),
                    herbs = emptyList(), seeds = emptyList(), storageBags = emptyList(),
                    teams = emptyList(), battleLogs = emptyList(),
                    isPaused = false, isLoading = false, isSaving = false
                )
                null
            } catch (e: RuntimeException) {
                e  // 预期异常：标记后断言 message 验证确实是存储失败触发
            }
        }
        org.junit.Assert.assertNotNull("加载应抛异常", thrown)
        org.junit.Assert.assertEquals("加载失败异常（模拟存储失败）", "模拟存储失败", thrown?.message)

        // 回滚验证：旧值全部恢复
        assertEquals("gameData 恢复旧宗门名", "旧宗门", store.gameDataSnapshot.sectName)
        assertEquals("disciples 恢复旧列表", 3, store.disciples.value.size)
        TestPolling.awaitCondition("回滚后聚合恢复") { store.discipleAggregatesSnapshot.size == 3 }
        val rollbackAggregates = store.discipleAggregatesSnapshot
        assertEquals("回滚聚合与旧状态一致", 3, rollbackAggregates.size)
        assertTrue(
            "回滚聚合含旧弟子",
            rollbackAggregates.map { it.id }.toSet() == setOf("1", "2", "3")
        )
        assertTrue("回滚后战力非零", store.sectCombatPower.value > 0)
    }

    @Test
    fun `旧档事件 sequenceId 加载后回填`() = runBlocking {
        // P-9 守卫：旧档（v4.0.83 前）事件 sequenceId 全 0 → 加载后按列表序回填 1..N
        val store = GameStateStoreImpl(
            ApplicationScopeProvider(), Mockito.mock(GameStateRepository::class.java)
        )
        store.unsafeAllowMainThreadUpdateForTest = true
        store.loadFromSnapshot(
            gameData = GameData(
                sectName = "旧档",
                gameEventRecords = listOf(
                    com.xianxia.sect.core.model.GameEventRecord(
                        eventType = "desertion", summary = "事件A", sequenceId = 0
                    ),
                    com.xianxia.sect.core.model.GameEventRecord(
                        eventType = "death", summary = "事件B", sequenceId = 0
                    ),
                    com.xianxia.sect.core.model.GameEventRecord(
                        eventType = "marriage", summary = "事件C", sequenceId = 5
                    )
                )
            ),
            disciples = emptyList(),
            equipmentStacks = emptyList(), equipmentInstances = emptyList(),
            manualStacks = emptyList(), manualInstances = emptyList(),
            pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(), storageBags = emptyList(),
            teams = emptyList(), battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )
        val records = store.gameDataSnapshot.gameEventRecords
        org.junit.Assert.assertEquals("3 条事件保留", 3, records.size)
        // 回填：0 序号从 max+1 继续（5 → 6/7），保证单调递增
        org.junit.Assert.assertEquals("事件A 回填为 6", 6L, records[0].sequenceId)
        org.junit.Assert.assertEquals("事件B 回填为 7", 7L, records[1].sequenceId)
        org.junit.Assert.assertEquals("事件C 保留 5", 5L, records[2].sequenceId)
    }

    @Test
    fun `正常加载后快照与代际一致`() = runBlocking {
        val store = GameStateStoreImpl(
            ApplicationScopeProvider(), Mockito.mock(GameStateRepository::class.java)
        )
        store.unsafeAllowMainThreadUpdateForTest = true
        store.update {
            for (i in 1..2) discipleTables.insert(disciple(i))
        }
        TestPolling.awaitCondition("聚合就绪") { store.discipleAggregatesSnapshot.size == 2 }

        // 正常加载新档（不失败）
        store.loadFromSnapshot(
            gameData = GameData(sectName = "新宗门"),
            disciples = (1..4).map { disciple(it) },
            equipmentStacks = emptyList(), equipmentInstances = emptyList(),
            manualStacks = emptyList(), manualInstances = emptyList(),
            pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(), storageBags = emptyList(),
            teams = emptyList(), battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )
        TestPolling.awaitCondition("新档聚合就绪") { store.discipleAggregatesSnapshot.size == 4 }
        assertEquals("新档聚合覆盖 4 弟子", 4, store.discipleAggregatesSnapshot.size)
        assertEquals("新档 gameData 生效", "新宗门", store.gameDataSnapshot.sectName)
    }
}
