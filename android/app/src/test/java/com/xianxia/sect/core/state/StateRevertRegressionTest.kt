package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * 状态回退回归测试
 *
 * 使用 UnconfinedTestDispatcher 创建真实的 ApplicationScopeProvider，
 * 避免 mock scope 带来的异步问题。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateRevertRegressionTest {

    private fun createStore(): GameStateStore {
        val repository = mock(GameStateRepository::class.java)
        // 使用 UnconfinedTestDispatcher — 所有协程操作立即执行，无需手动 advance
        val dispatcher = UnconfinedTestDispatcher()
        val appScopeProvider = ApplicationScopeProvider().apply {
            // 注意：ApplicationScopeProvider 的 scope 是 val，无法替换
            // 这里依赖 GameStateStore 的行为 — stateIn 需要一个 scope，
            // 但 loadFromSnapshot / update / swapFromShadow 不依赖 scope
        }
        return GameStateStoreImpl(appScopeProvider, repository)
    }

    @Test
    fun `mergeDisciple preserves player fields while applying settlement changes`() = runTest {
        val store = createStore()
        store.loadFromSnapshot(
            gameData = GameData(),
            disciples = listOf(Disciple(
                id = "d1", name = "测试",
                discipleType = "outer", status = DiscipleStatus.IDLE,
                statusData = emptyMap(), cultivation = 100.0,
                realm = 9, realmLayer = 1, lifespan = 80,
                isAlive = true
            )),
            pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(),
            teams = emptyList(), battleLogs = emptyList()
        )

        // Player: 切内门 + 分配任务
        store.update {
            disciples = disciples.map { d ->
                if (d.id == "d1") d.copy(
                    discipleType = "inner",
                    status = DiscipleStatus.PREACHING,
                    statusData = mapOf("role" to "讲经")
                ) else d
            }
        }

        // Settlement: 修炼变化
        val shadow = store.createShadow()
        shadow.disciples = shadow.disciples.map {
            it.copy(cultivation = 1000.0, realm = 8, lifespan = 79)
        }
        store.swapFromShadow(shadow)

        // Verify
        val result = store.disciples.value.find { it.id == "d1" }!!
        assertEquals("discipleType", "inner", result.discipleType)
        assertEquals("status", DiscipleStatus.PREACHING, result.status)
        assertEquals("statusData", "讲经", result.statusData["role"])
        assertEquals("cultivation", 1000.0, result.cultivation, 0.01)
        assertEquals("realm", 8, result.realm)
        assertEquals("lifespan", 79, result.lifespan)
    }

    @Test
    fun `discipleType survives settlement merge`() = runTest {
        val store = createStore()
        store.loadFromSnapshot(
            gameData = GameData(),
            disciples = listOf(Disciple(
                id = "d1", name = "测试",
                discipleType = "outer", cultivation = 100.0,
                isAlive = true
            )),
            pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(),
            teams = emptyList(), battleLogs = emptyList()
        )

        store.update {
            disciples = disciples.map { d ->
                if (d.id == "d1") d.copy(discipleType = "inner") else d
            }
        }

        val shadow = store.createShadow()
        shadow.disciples = shadow.disciples.map { it.copy(cultivation = 500.0) }
        store.swapFromShadow(shadow)

        val r = store.disciples.value.find { it.id == "d1" }!!
        assertEquals("inner", r.discipleType)
        assertEquals(500.0, r.cultivation, 0.01)
    }

    @Test
    fun `spiritFieldPlants survive settlement merge`() = runTest {
        val store = createStore()
        val plant = SpiritFieldPlant(buildingInstanceId = "field_1", seedId = "seed_1")
        store.loadFromSnapshot(
            gameData = GameData(spiritFieldPlants = listOf(plant)),
            disciples = emptyList(),
            pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(),
            teams = emptyList(), battleLogs = emptyList()
        )

        store.update {
            gameData = gameData.copy(
                spiritFieldPlants = gameData.spiritFieldPlants +
                    SpiritFieldPlant(buildingInstanceId = "field_2", seedId = "seed_2")
            )
        }

        val shadow = store.createShadow()
        store.swapFromShadow(shadow)

        assertTrue("玩家新种的灵草不应丢失",
            store.gameData.value.spiritFieldPlants.any { it.buildingInstanceId == "field_2" })
    }
}
