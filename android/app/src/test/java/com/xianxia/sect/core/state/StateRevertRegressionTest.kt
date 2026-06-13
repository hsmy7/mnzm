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
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * 状态回退回归测试
 *
 * 注意：此测试需要 MMKV native 库，仅在 androidTest（设备测试）中可运行。
 * 本地单元测试中因缺少 native 库而跳过。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Ignore("需要 MMKV native 库，仅在 androidTest 中运行")
class StateRevertRegressionTest {

    private fun createStore(): GameStateStore {
        val repository = mock(GameStateRepository::class.java)
        val appScopeProvider = ApplicationScopeProvider()
        return GameStateStoreImpl(appScopeProvider, repository)
    }

    @Test
    fun `discipleTables update preserves player fields`() = runTest {
        val store = createStore()
        store.loadFromSnapshot(
            gameData = GameData(),
            disciples = listOf(Disciple(
                id = "1", name = "测试",
                discipleType = "outer", status = DiscipleStatus.IDLE,
                statusData = emptyMap(), cultivation = 100.0,
                realm = 9, realmLayer = 1, lifespan = 80,
                isAlive = true
            )),
            pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(),
            teams = emptyList(), battleLogs = emptyList()
        )

        // Player: 切内门 + 分配任务（assemble → modify → insert 模式）
        store.update {
            val d = discipleTables.assemble(1)
            discipleTables.remove(1)
            discipleTables.insert(d.copy(
                discipleType = "inner",
                status = DiscipleStatus.PREACHING,
                statusData = mapOf("role" to "讲经")
            ))
        }

        // Settlement: 修炼变化（通过 shadow 组件表操作）
        val shadow = store.createShadow()
        val sd = shadow.discipleTables.assemble(1)
        shadow.discipleTables.remove(1)
        shadow.discipleTables.insert(sd.copy(
            cultivation = 1000.0, realm = 8, lifespan = 79
        ))
        store.swapFromShadow(shadow)

        // Verify
        val result = store.disciples.value.find { it.id == "1" }!!
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
                id = "1", name = "测试",
                discipleType = "outer", cultivation = 100.0,
                isAlive = true
            )),
            pills = emptyList(), materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(),
            teams = emptyList(), battleLogs = emptyList()
        )

        store.update {
            val d = discipleTables.assemble(1)
            discipleTables.remove(1)
            discipleTables.insert(d.copy(discipleType = "inner"))
        }

        val shadow = store.createShadow()
        val sd = shadow.discipleTables.assemble(1)
        shadow.discipleTables.remove(1)
        shadow.discipleTables.insert(sd.copy(cultivation = 500.0))
        store.swapFromShadow(shadow)

        val r = store.disciples.value.find { it.id == "1" }!!
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
