package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.mockSmart
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.robolectric.RobolectricTestRunner



/**
 * 验证 DiscipleService 的 CRUD 操作。
 *
 * 使用 delegate mock 模式（同 DiscipleServiceApprenticeTest）注入 GameStateStore。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class DiscipleServiceCrudTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var mockStore: GameStateStore
    private lateinit var service: DiscipleService

    @Before
    fun setUp() {
        val store = FakeAtomicStateStore()
        mockStore = store
        tables = store.discipleTables

        // ProductionSlotRepository 是 final 类：mock 拦截依赖类加载时机（顺序敏感 flaky），
        // 用真实实例 + mockSmart 端口（getSlots() 真实返回空列表，语义与 mock 时代一致；
        // clearDiscipleFromAllSlots 遍历该列表）
        val productionRepo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        val slotManager = DiscipleSlotManager(
            stateStore = mockStore,
            productionSlotRepository = productionRepo,
            discipleSlotCleanup = DiscipleSlotCleanup(
                DiscipleAssignmentGate(DiscipleAssignmentRegistry())
            ),
            discipleStatusServiceProvider = javax.inject.Provider { mockSmart() },
            ioDispatcher = IoDispatcher()
        )
        val equipmentService = DiscipleEquipmentService(
            stateStore = mockStore
        )
        val masterService = DiscipleMasterApprenticeService(
            stateStore = mockStore
        )
        val lifecycleManager = DiscipleLifecycleManager(
            stateStore = mockStore,
            discipleFactory = mockSmart(),
            rngManager = mockSmart(),
            slotManager = slotManager,
            productionSlotRepository = mockSmart(),
        )
        service = DiscipleService(
            stateStore = mockStore,
            discipleFactory = mockSmart(),
            rngManager = mockSmart(),
            discipleEquipmentService = equipmentService,
            discipleLifecycleManager = lifecycleManager,
            discipleMasterApprenticeService = masterService,
            discipleSlotManager = slotManager,
            discipleStatusService = mockSmart(),
            inventorySystem = mockSmart(com.xianxia.sect.core.engine.system.InventorySystem::class.java)
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
        // 年报脱离弟子计数（2026-08-11 修复：玩家逐出漏计）
        assertEquals("逐出计入年报脱离", 1, (mockStore as FakeAtomicStateStore).gameData.value.annualDesertedDisciples)
    }

    @Test
    fun `expelDisciple - dead disciple returns NotAlive`() = runTest {
        insertAliveDisciple(1)
        tables.isAlive[1] = 0  // mark dead

        val result = service.expelDisciple("1")

        assertTrue("expel dead disciple should return Failure", result is DomainResult.Failure)
        assertEquals("失败路径不计数", 0, (mockStore as FakeAtomicStateStore).gameData.value.annualDesertedDisciples)
    }

    @Test
    fun `expelDisciple - non-existent disciple returns NotFound`() = runTest {
        val result = service.expelDisciple("999")

        assertTrue("expel non-existent should return Failure", result is DomainResult.Failure)
        assertEquals("失败路径不计数", 0, (mockStore as FakeAtomicStateStore).gameData.value.annualDesertedDisciples)
    }
}
