package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doThrow
import org.mockito.kotlin.any
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * loadFromSnapshot 失败回滚回归测试。
 *
 * 守卫（rollbackLoad 回滚 + 聚合恢复）：
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
        // 注入失败：setActiveSlot 抛异常 → loadFromSnapshot 走回滚路径
        // （dirty 记账摘除 batch-05 后，失败注入点由 markAllDirty 迁至
        // finalizeLoadedState 同段位的仓库调用，语义等价——同款先例见
        // GameStateStoreRollbackTest 的 setActiveSlot 注入）
        val repo = testGameStateRepository()
        doThrow(RuntimeException("模拟存储失败")).`when`(repo).setActiveSlot(any())
        val store = GameStateStoreImpl(ApplicationScopeProvider(), repo)
        store.unsafeAllowMainThreadUpdateForTest = true

        // 建立旧状态（3 弟子 + 游戏数据）
        store.update {
            for (i in 1..3) discipleTables.insert(disciple(i))
            gameData = gameData.copy(sectName = "旧宗门", gameYear = 10)
        }
        TestPolling.awaitCondition("旧状态聚合就绪") { store.discipleAggregatesSnapshot.size == 3 }

        // 执行会失败的加载（新档 5 弟子）——setActiveSlot 失败 → rollbackLoad → rethrow
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
                    battleLogs = emptyList(),
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
        // 旧档（v4.0.83 前）事件 sequenceId 全 0 → 加载后按列表序回填 1..N
        val store = GameStateStoreImpl(
            ApplicationScopeProvider(), testGameStateRepository()
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
            battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )
        val records = store.gameDataSnapshot.gameEventRecords
        org.junit.Assert.assertEquals("3 条事件保留", 3, records.size)
        // 存在任一 0 序号时整体重编号 1..N（列表序）——
        // 仅重编号 0 条目会破坏单调递增（[0,0,5] → [6,7,5]，正确结果为 [1,2,3]）
        org.junit.Assert.assertEquals("事件A 重编号为 1", 1L, records[0].sequenceId)
        org.junit.Assert.assertEquals("事件B 重编号为 2", 2L, records[1].sequenceId)
        org.junit.Assert.assertEquals("事件C 重编号为 3", 3L, records[2].sequenceId)
    }

    @Test
    fun `事件 sequenceId 全非 0 时零成本不动`() = runBlocking {
        // T1 补充守卫：无 0 序号时不做任何重编号（返回原引用）
        val store = GameStateStoreImpl(
            ApplicationScopeProvider(), testGameStateRepository()
        )
        store.unsafeAllowMainThreadUpdateForTest = true
        val records = listOf(
            com.xianxia.sect.core.model.GameEventRecord(
                eventType = "desertion", summary = "事件A", sequenceId = 3L
            ),
            com.xianxia.sect.core.model.GameEventRecord(
                eventType = "death", summary = "事件B", sequenceId = 5L
            )
        )
        store.loadFromSnapshot(
            gameData = GameData(
                sectName = "新档",
                gameEventRecords = records
            ),
            disciples = emptyList(),
            equipmentStacks = emptyList(), equipmentInstances = emptyList(),
            manualStacks = emptyList(), manualInstances = emptyList(),
            pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(), storageBags = emptyList(),
            battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )
        val loaded = store.gameDataSnapshot.gameEventRecords
        org.junit.Assert.assertEquals("序号原样保留", listOf(3L, 5L), loaded.map { it.sequenceId })
    }

    @Test
    fun `正常加载后快照与代际一致`() = runBlocking {
        val store = GameStateStoreImpl(
            ApplicationScopeProvider(), testGameStateRepository()
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
            battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )
        TestPolling.awaitCondition("新档聚合就绪") { store.discipleAggregatesSnapshot.size == 4 }
        assertEquals("新档聚合覆盖 4 弟子", 4, store.discipleAggregatesSnapshot.size)
        assertEquals("新档 gameData 生效", "新宗门", store.gameDataSnapshot.sectName)
    }

    @Test
    fun `读档失败后状态与读档前逐位一致`() = runBlocking {
        // batch-05 回归守卫：回滚语义由 LoadBaseline + rollbackLoad 承载（dirty 记账
        // 摘除前也如此——dirty 位从无读者）。旧状态带非空实体 + 差异化状态三连，
        // 失败读档载荷全部不同——回滚若漏恢复任一流，逐位比较即失败。
        val repo = testGameStateRepository()
        val store = GameStateStoreImpl(ApplicationScopeProvider(), repo)
        store.unsafeAllowMainThreadUpdateForTest = true

        // 建立旧状态：首次成功读档（实体全非空，isPaused/isSaving 取非默认值）
        store.loadFromSnapshot(
            gameData = GameData(sectName = "旧宗门", gameYear = 10),
            disciples = (1..3).map { disciple(it) },
            equipmentStacks = listOf(EquipmentStack(name = "旧飞剑")),
            equipmentInstances = listOf(EquipmentInstance(name = "旧飞剑·器")),
            manualStacks = listOf(ManualStack(name = "旧功法")),
            manualInstances = listOf(ManualInstance(name = "旧功法·篇")),
            pills = listOf(Pill(name = "旧回气丹")),
            materials = listOf(Material(name = "旧铁精")),
            herbs = listOf(Herb(name = "旧灵草")),
            seeds = listOf(Seed(name = "旧灵种")),
            storageBags = listOf(StorageBag(name = "旧储物袋")),
            battleLogs = listOf(BattleLog(attackerName = "旧敌", defenderName = "旧我")),
            isPaused = true, isLoading = false, isSaving = true
        )
        val flowNames = listOf(
            "gameData", "disciples",
            "equipmentStacks", "equipmentInstances", "manualStacks", "manualInstances",
            "pills", "materials", "herbs", "seeds", "storageBags", "battleLogs",
            "isPaused", "isLoading", "isSaving"
        )
        val before = snapshotAllFlows(store)

        // 注入失败：setActiveSlot 抛异常 → 回滚（同首个测试的注入点迁移）
        doThrow(RuntimeException("模拟存储失败")).`when`(repo).setActiveSlot(any())
        val thrown = try {
            store.loadFromSnapshot(
                gameData = GameData(sectName = "新宗门", gameYear = 99),
                disciples = (4..8).map { disciple(it) },
                equipmentStacks = listOf(EquipmentStack(name = "新飞剑")),
                equipmentInstances = listOf(EquipmentInstance(name = "新飞剑·器")),
                manualStacks = listOf(ManualStack(name = "新功法")),
                manualInstances = listOf(ManualInstance(name = "新功法·篇")),
                pills = listOf(Pill(name = "新回气丹")),
                materials = listOf(Material(name = "新铁精")),
                herbs = listOf(Herb(name = "新灵草")),
                seeds = listOf(Seed(name = "新灵种")),
                storageBags = listOf(StorageBag(name = "新储物袋")),
                battleLogs = listOf(BattleLog(attackerName = "新敌", defenderName = "新我")),
                isPaused = false, isLoading = false, isSaving = false
            )
            null
        } catch (e: RuntimeException) {
            e
        }
        org.junit.Assert.assertNotNull("加载应抛异常", thrown)

        // 逐位一致：15 条流全部与读档前相等（值域 + 非空性）
        val after = snapshotAllFlows(store)
        before.zip(after).forEachIndexed { index, (was, now) ->
            assertEquals("回滚后 ${flowNames[index]} 与读档前逐位一致", was, now)
        }
        assertEquals("旧实体非空守卫（equipmentStacks）", 1, store.equipmentStacks.value.size)
        assertEquals("旧实体非空守卫（pills）", 1, store.pills.value.size)
    }

    private fun snapshotAllFlows(store: GameStateStoreImpl): List<Any?> = listOf(
        store.gameData.value,
        store.disciples.value,
        store.equipmentStacks.value,
        store.equipmentInstances.value,
        store.manualStacks.value,
        store.manualInstances.value,
        store.pills.value,
        store.materials.value,
        store.herbs.value,
        store.seeds.value,
        store.storageBags.value,
        store.battleLogs.value,
        store.isPaused.value,
        store.isLoading.value,
        store.isSaving.value
    )
}
