package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * 师徒拜师功能测试。
 *
 * 使用 Robolectric 获得真实的 SparseArray/SparseIntArray 实现，
 * 使用 FakeStore 替代真实的 GameStateStore.update{}。
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleServiceApprenticeTest {

    private lateinit var tables: DiscipleTables
    private lateinit var mutableState: MutableGameState
    private lateinit var mockStore: GameStateStore
    private lateinit var service: DiscipleService

    @Before
    fun setUp() {
        tables = DiscipleTables()
        mutableState = createMutableState(tables)

        // 构造委托 mock：除 update/discipleTables
        // 外全部 stub 为默认值
        val delegate = mock(GameStateStore::class.java)
        Mockito.`when`(delegate.discipleTables).thenReturn(tables)

        mockStore = object : GameStateStore by delegate {
            override val discipleTables: DiscipleTables
                get() = tables

            override suspend fun update(
                block: suspend MutableGameState.() -> Unit
            ) {
                block.invoke(mutableState)
            }

            override suspend fun <R> updateAndReturn(
                block: suspend MutableGameState.() -> R
            ): R {
                return block.invoke(mutableState)
            }
        }

        service = DiscipleService(
            stateStore = mockStore,
            productionSlotRepository = mock(),
            scopeProvider = mock(),
            inventoryConfig = mock(),
            discipleFactory = mock()
        )
    }

    // ==================== 正常拜师 ====================

    @Test
    fun `apprenticeToMaster - insert验证`() = runTest {
        val d = Disciple(
            id = "1", name = "测试", realm = 9,
            realmLayer = 1, spiritRootType = "metal",
            discipleType = "inner"
        )
        tables.insert(d)
        // Disciple 的 isAlive 默认为 true
        assertTrue("ids应含1", tables.ids.contains(1))
        assertEquals("isAlive应为1", 1, tables.isAlive[1])
    }

    @Test
    fun `apprenticeToMaster - 正常拜师成功`() = runTest {
        insertAlive(1, realm = 9)  // 练气徒弟
        insertAlive(2, realm = 7)  // 金丹师父

        assertTrue("ids应含1", tables.ids.contains(1))
        assertTrue("ids应含2", tables.ids.contains(2))

        val result = service.apprenticeToMaster("1", "2")

        assertTrue(
            "拜师应成功, 实际: $result",
            result is DomainResult.Success
        )
        assertEquals("2", tables.masterIds.getOrNull(1))
    }

    // ==================== 校验失败 ====================

    @Test
    fun `apprenticeToMaster - 弟子不存在返回NotFound`() = runTest {
        insertAlive(2, realm = 7)

        val result = service.apprenticeToMaster("999", "2")

        assertTrue("应返回Failure", result is DomainResult.Failure)
    }

    @Test
    fun `apprenticeToMaster - 师父不存在返回NotFound`() = runTest {
        insertAlive(1, realm = 9)

        val result = service.apprenticeToMaster("1", "999")

        assertTrue("应返回Failure", result is DomainResult.Failure)
    }

    @Test
    fun `apprenticeToMaster - 不能拜自己为师`() = runTest {
        insertAlive(1, realm = 7)

        val result = service.apprenticeToMaster("1", "1")

        assertTrue("拜自己应失败", result is DomainResult.Failure)
    }

    @Test
    fun `apprenticeToMaster - 弟子已死亡拜师失败`() = runTest {
        insertAlive(1, realm = 9)
        insertAlive(2, realm = 7)
        tables.isAlive[1] = 0  // 弟子死亡

        val result = service.apprenticeToMaster("1", "2")

        assertTrue(
            "死亡弟子拜师应失败",
            result is DomainResult.Failure
        )
    }

    @Test
    fun `apprenticeToMaster - 师父已死亡拜师失败`() = runTest {
        insertAlive(1, realm = 9)
        insertAlive(2, realm = 7)
        tables.isAlive[2] = 0  // 师父死亡

        val result = service.apprenticeToMaster("1", "2")

        assertTrue("拜死人为师应失败", result is DomainResult.Failure)
    }

    @Test
    fun `apprenticeToMaster - 已有师父不能再拜师`() = runTest {
        insertAlive(1, realm = 9)
        insertAlive(2, realm = 7)
        insertAlive(3, realm = 6)
        tables.masterIds[1] = "2"  // 1已拜2为师

        val result = service.apprenticeToMaster("1", "3")

        assertTrue(
            "已有师父再拜应失败",
            result is DomainResult.Failure
        )
        assertEquals("2", tables.masterIds.getOrNull(1))  // 关系未变
    }

    @Test
    fun `apprenticeToMaster - 师父徒弟已满返回失败`() = runTest {
        insertAlive(1, realm = 9)  // 新徒弟
        insertAlive(2, realm = 7)  // 师父
        // 师父已有5个徒弟
        for (i in 3..7) {
            insertAlive(i, realm = 9)
            tables.masterIds[i] = "2"
        }

        val result = service.apprenticeToMaster("1", "2")

        assertTrue("师父满员应失败", result is DomainResult.Failure)
        assertNull("师徒关系不应建立", tables.masterIds.getOrNull(1))
    }

    @Test
    fun `apprenticeToMaster - 师父4徒弟时第5个可拜师`() = runTest {
        insertAlive(1, realm = 9)  // 新徒弟
        insertAlive(2, realm = 7)  // 师父
        for (i in 3..6) {
            insertAlive(i, realm = 9)
            tables.masterIds[i] = "2"
        }
        // 师父现有4个徒弟

        val result = service.apprenticeToMaster("1", "2")

        assertTrue("第5个徒弟应可拜师", result is DomainResult.Success)
        assertEquals("2", tables.masterIds.getOrNull(1))
    }

    @Test
    fun `apprenticeToMaster - 死亡徒弟不计入师父名额`() = runTest {
        insertAlive(1, realm = 9)  // 新徒弟
        insertAlive(2, realm = 7)  // 师父
        // 5个徒弟但2个已死
        for (i in 3..7) {
            insertAlive(i, realm = 9)
            tables.masterIds[i] = "2"
        }
        tables.isAlive[3] = 0  // 死亡
        tables.isAlive[7] = 0  // 死亡
        // 现有存活徒弟 = 3个

        val result = service.apprenticeToMaster("1", "2")

        assertTrue(
            "死亡徒弟不计入名额, 应可拜师",
            result is DomainResult.Success
        )
        assertEquals("2", tables.masterIds.getOrNull(1))
    }

    @Test
    fun `apprenticeToMaster - 无效ID格式返回NotFound`() = runTest {
        val result = service.apprenticeToMaster("not_a_number", "2")

        assertTrue("无效ID应失败", result is DomainResult.Failure)
    }

    // ==================== 辅助 ====================

    private fun insertAlive(id: Int, realm: Int) {
        val d = Disciple(
            id = id.toString(),
            name = "弟子$id",
            realm = realm,
            realmLayer = 1,
            spiritRootType = "metal",
            discipleType = "inner"
        )
        tables.insert(d)
        tables.isAlive[id] = 1
    }

    private fun createMutableState(tables: DiscipleTables) = MutableGameState(
        gameData = GameData(),
        discipleTables = tables,
        equipmentStacks = EntityStore(emptyList()),
        equipmentInstances = EntityStore(emptyList()),
        manualStacks = EntityStore(emptyList()),
        manualInstances = EntityStore(emptyList()),
        pills = EntityStore(emptyList()),
        materials = EntityStore(emptyList()),
        herbs = EntityStore(emptyList()),
        seeds = EntityStore(emptyList()),
        storageBags = EntityStore(emptyList()),
        teams = emptyList(),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )
}
