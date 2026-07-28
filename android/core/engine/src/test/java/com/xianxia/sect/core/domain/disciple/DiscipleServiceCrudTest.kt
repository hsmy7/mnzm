package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.di.IoDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * 验证 DiscipleService 的 CRUD 操作。
 *
 * 使用 delegate mock 模式（同 DiscipleServiceApprenticeTest）注入 GameStateStore。
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleServiceCrudTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var mutableState: MutableGameState
    private lateinit var mockStore: GameStateStore
    private lateinit var service: DiscipleService

    @Before
    fun setUp() {
        tables = DiscipleTables()
        mutableState = createMutableState(tables)

        val delegate = mock(GameStateStore::class.java)
        Mockito.`when`(delegate.discipleTables).thenReturn(tables)
        Mockito.`when`(delegate.gameData)
            .thenReturn(MutableStateFlow(GameData()))
        Mockito.`when`(delegate.equipmentStacks)
            .thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.equipmentInstances)
            .thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.manualStacks)
            .thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.manualInstances)
            .thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.pills)
            .thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.materials)
            .thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.herbs)
            .thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.seeds)
            .thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.teams)
            .thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.battleLogs)
            .thenReturn(MutableStateFlow(emptyList()))

        mockStore = object : GameStateStore by delegate {
            override val discipleTables: DiscipleTables
                get() = tables

            override fun update(
        block: MutableGameState.() -> Unit
            ) {
                block.invoke(mutableState)
            }

            override fun <R> updateAndReturn(
        block: MutableGameState.() -> R
            ): R {
                return block.invoke(mutableState)
            }
        }

        val slotManager = DiscipleSlotManager(
            stateStore = mockStore,
            productionSlotRepository = mock(),
            scopeProvider = mock(),
            discipleSlotCleanup = DiscipleSlotCleanup(
                DiscipleAssignmentGate(DiscipleAssignmentRegistry())
            ),
            discipleStatusServiceProvider = javax.inject.Provider { mock() },
            ioDispatcher = IoDispatcher()
        )
        val equipmentService = DiscipleEquipmentService(
            stateStore = mockStore,
            inventoryConfig = mock()
        )
        val masterService = DiscipleMasterApprenticeService(
            stateStore = mockStore
        )
        val lifecycleManager = DiscipleLifecycleManager(
            stateStore = mockStore,
            discipleFactory = mock(),
            rngManager = mock(),
            slotManager = slotManager,
            productionSlotRepository = mock(),
        )
        service = DiscipleService(
            stateStore = mockStore,
            discipleFactory = mock(),
            rngManager = mock(),
            discipleEquipmentService = equipmentService,
            discipleLifecycleManager = lifecycleManager,
            discipleMasterApprenticeService = masterService,
            discipleSlotManager = slotManager,
            discipleStatusService = mock()
        )
    }

    // ==================== 辅助方法 ====================

    private fun insertAliveDisciple(
        id: Int,
        name: String = "弟子$id",
        realm: Int = 9,
        realmLayer: Int = 3,
        age: Int = 20,
        lifespan: Int = 80
    ) {
        val disciple = Disciple(
            id = id.toString(),
            name = name,
            realm = realm,
            realmLayer = realmLayer,
            age = age,
            lifespan = lifespan
        )
        tables.insert(disciple)
        tables.isAlive[id] = 1
    }

    // ═══════════════════════════════════════════════════════════════
    // addDisciple — 成功插入
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `addDisciple - inserts disciple into tables`() {
        val disciple = Disciple(
            id = "1", name = "新增弟子", realm = 9, realmLayer = 1
        )
        service.addDisciple(disciple)

        assertTrue("tables should contain disciple 1", tables.ids.contains(1))
        assertEquals("inserted disciple name should match", "新增弟子", tables.names[1])
    }

    @Test
    fun `addDisciple - multiple disciples can be inserted`() {
        for (i in 1..5) {
            val d = Disciple(
                id = i.toString(), name = "弟子$i", realm = 9, realmLayer = 1
            )
            service.addDisciple(d)
        }

        assertEquals("5 disciples should be inserted", 5, tables.ids.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // removeDisciple — 移除
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `removeDisciple - removes existing disciple`() {
        insertAliveDisciple(1)

        val result = service.removeDisciple("1")

        assertTrue("remove should return Success", result is DomainResult.Success)
        assertFalse("tables should not contain removed disciple", tables.ids.contains(1))
    }

    @Test
    fun `removeDisciple - non-existent disciple returns NotFound`() {
        val result = service.removeDisciple("999")

        assertTrue("remove non-existent should return Failure", result is DomainResult.Failure)
    }

    @Test
    fun `removeDisciple - invalid id format returns NotFound`() {
        val result = service.removeDisciple("not_a_number")

        assertTrue("remove with invalid id should return Failure", result is DomainResult.Failure)
    }

    // ═══════════════════════════════════════════════════════════════
    // updateDisciple — 更新字段
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `updateDisciple - updates disciple fields`() {
        insertAliveDisciple(1, name = "原名", realm = 9)

        val updated = Disciple(
            id = "1", name = "新名", realm = 8, realmLayer = 1
        )
        service.updateDisciple(updated)

        val result = tables.assemble(1)
        assertEquals("name should be updated", "新名", result.name)
        assertEquals("realm should be updated", 8, result.realm)
    }

    @Test
    fun `updateDisciple - non-existent disciple does nothing`() {
        val original = Disciple(
            id = "1", name = "原始", realm = 9, realmLayer = 1
        )
        service.updateDisciple(original)

        // No exception should be thrown, and tables should be empty
        assertTrue("tables should still be empty", tables.ids.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // getDiscipleById — 获取
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `getDiscipleById - returns existing disciple`() {
        insertAliveDisciple(1, name = "查找弟子")

        val result = service.getDiscipleById("1")

        assertNotNull("should return disciple", result)
        assertEquals("name should match", "查找弟子", result!!.name)
    }

    @Test
    fun `getDiscipleById - returns null for non-existent disciple`() {
        val result = service.getDiscipleById("999")

        assertNull("should return null for non-existent disciple", result)
    }

    // ═══════════════════════════════════════════════════════════════
    // expelDisciple — 逐出
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `expelDisciple - removes alive disciple`() = runTest {
        insertAliveDisciple(1, name = "将被逐出")

        val result = service.expelDisciple("1")

        assertTrue("expel should return Success", result is DomainResult.Success)
        // Disciple should be removed from tables
        assertFalse("expelled disciple should be removed", tables.ids.contains(1))
    }

    @Test
    fun `expelDisciple - dead disciple returns NotAlive`() = runTest {
        insertAliveDisciple(1)
        tables.isAlive[1] = 0  // mark dead

        val result = service.expelDisciple("1")

        assertTrue("expel dead disciple should return Failure", result is DomainResult.Failure)
    }

    @Test
    fun `expelDisciple - non-existent disciple returns NotFound`() = runTest {
        val result = service.expelDisciple("999")

        assertTrue("expel non-existent should return Failure", result is DomainResult.Failure)
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

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
