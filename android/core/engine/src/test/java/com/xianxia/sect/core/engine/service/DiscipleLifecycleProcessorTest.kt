package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.mockSmart
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config



@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
class DiscipleLifecycleProcessorTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var mockStore: GameStateStore
    private lateinit var statusService: DiscipleStatusService
    private lateinit var processor: DiscipleLifecycleProcessor

    @Before
    fun setUp() {
        val store = FakeAtomicStateStore()
        mockStore = store
        tables = store.discipleTables
        store.setGameData(GameData(gameYear = 10))

        // 对所有非 GameStateStore 的依赖使用 mockSmart（RETURNS_SMART_NULLS）。
        // 这些 mock 在测试方法中不会被 verify，只用作哑对象。
        statusService = mockSmart(DiscipleStatusService::class.java)
        processor = DiscipleLifecycleProcessor(
            stateStore = mockStore,
            scopeProvider = mockSmart(CoroutineScopeProvider::class.java),
            productionCoordinator = mockSmart(
                com.xianxia.sect.core.engine.domain.production.ProductionCoordinator::class.java
            ),
            eventBus = mockSmart(EventBusPort::class.java),
            discipleSlotCleanup = DiscipleSlotCleanup(
                DiscipleAssignmentGate(DiscipleAssignmentRegistry())
            ),
            discipleStatusService = statusService,
            ioDispatcher = IoDispatcher(),
            inventorySystem = com.xianxia.sect.core.engine.system.InventorySystem(
                stateStore = mockStore,
                // 必须用真实配置：mock 的 getMaxStackSize 返回 0 → StackableItemStore 拒绝
                // 任何入仓（maxStack<=0 守卫）→ 物化永远失败，测试失去意义
                inventoryConfig = InventoryConfig(),
            ),
            // 统一死亡入口：真实实例（markDead 写 isAlive=0 + status=DEAD + deathYear）
            deathHandler = DiscipleDeathHandler()
        )
    }

    // ==================== 辅助 ====================

    private fun insertDisciple(
        id: Int,
        name: String = "弟子$id",
        realm: Int = 9,
        realmLayer: Int = 3,
        status: DiscipleStatus = DiscipleStatus.IDLE,
        statusData: Map<String, String> = emptyMap(),
        skills: SkillStats = SkillStats(),
        skipTablesIsAlive: Boolean = false
    ) {
        val disciple = Disciple(
            id = id.toString(),
            name = name,
            realm = realm,
            realmLayer = realmLayer,
            status = status,
            statusData = statusData,
            skills = skills
        )
        tables.insert(disciple)
        if (!skipTablesIsAlive) {
            tables.isAlive[id] = 1
        }
    }

    // ══════════════════════════════════════
    // processDiscipleAging
    // ══════════════════════════════════════

    @Test
    fun `processDiscipleAging - delegates to disciple status sync`() = runTest {
        processor.processDiscipleAging(currentYear = 10)

        org.mockito.Mockito.verify(statusService).syncAllDiscipleStatuses()
    }

    // ══════════════════════════════════════
    // processReflectionRelease
    // ══════════════════════════════════════

    @Test
    fun `processReflectionRelease - reflection released when year equals end year`() = runTest {
        insertDisciple(
            1,
            status = DiscipleStatus.REFLECTING,
            statusData = mapOf("reflectionStartYear" to "8", "reflectionEndYear" to "10"),
            skills = SkillStats(morality = 50)
        )

        processor.processReflectionRelease(year = 10)

        val updated = tables.assemble(1)
        assertEquals(DiscipleStatus.IDLE, updated.status)
        assertEquals("morality should be 55 after reflection release",
            55, updated.skills.morality)
        assertFalse("reflectionEndYear should be removed",
            updated.statusData.containsKey("reflectionEndYear"))
    }

    @Test
    fun `processReflectionRelease - reflection not released before end year`() = runTest {
        insertDisciple(
            1,
            status = DiscipleStatus.REFLECTING,
            statusData = mapOf("reflectionStartYear" to "8", "reflectionEndYear" to "12")
        )

        processor.processReflectionRelease(year = 10)

        val updated = tables.assemble(1)
        assertEquals("status should remain REFLECTING",
            DiscipleStatus.REFLECTING, updated.status)
    }

    @Test
    fun `processReflectionRelease - non-reflecting disciples are not affected`() = runTest {
        insertDisciple(1, status = DiscipleStatus.IDLE)
        processor.processReflectionRelease(year = 10)
        val updated = tables.assemble(1)
        assertEquals(DiscipleStatus.IDLE, updated.status)
    }

    // ══════════════════════════════════════
    // processYearlyAging
    // ══════════════════════════════════════

    @Test
    fun `processYearlyAging - no dead disciples does nothing`() = runTest {
        insertDisciple(1)
        processor.processYearlyAging(currentYear = 10)
        assertTrue("disciple should remain when no one is dead",
            tables.ids.contains(1))
    }

    @Test
    fun `processYearlyAging - recent dead disciples are not culled`() = runTest {
        insertDisciple(1)
        tables.deathYears[1] = 10
        processor.processYearlyAging(currentYear = 10)
        assertTrue("recently dead disciple should not be culled",
            tables.ids.contains(1))
    }

    // ══════════════════════════════════════
    // handleDiscipleDeath
    // ══════════════════════════════════════

    @Test
    fun `handleDiscipleDeath - death year is written`() = runTest {
        insertDisciple(1)
        val deadDisciple = tables.assemble(1)

        processor.handleDiscipleDeath(deadDisciple, isOutsideSect = false)

        assertEquals("death year should be 10", 10, tables.deathYears[1])
    }

    @Test
    fun `handleDiscipleDeath - 统一入口写 isAlive=0 status=DEAD`() = runTest {
        // markDead 统一死亡标记（isAlive + status + deathYear 三字段）
        insertDisciple(1)
        val deadDisciple = tables.assemble(1)

        processor.handleDiscipleDeath(deadDisciple, isOutsideSect = false)

        assertEquals("isAlive 应为 0", 0, tables.isAlive[1])
        assertEquals("status 应为 DEAD", DiscipleStatus.DEAD, tables.statuses[1])
        assertEquals("deathYear 已写", 10, tables.deathYears[1])
    }

    @Test
    fun `handleDiscipleDeath - bag materialized and cleared - repeated death idempotent`() = runTest {
        // 死亡物化袋物品（玩家保留）+ 清空袋条目（幂等防复制）
        insertDisciple(1)
        // B3 实例轨：卸装/在册实例恒在实例表——袋条目 payload 与实例表同件（同 id）
        mockStore.update {
            equipmentInstances.replaceAll(listOf(
                EquipmentInstance(id = "i1", name = "精铁剑",
                part = EquipmentSlot.HANDS,
                growth = com.xianxia.sect.core.model.EquipGrowth(
                    affix = com.xianxia.sect.core.model.EquipAffixSet(
                        mainStat = com.xianxia.sect.core.model.EquipStatValue(
                            com.xianxia.sect.core.model.EquipStat.ATTACK, 10.0
                        )
                    )
                ),
                meta = com.xianxia.sect.core.model.EquipInstanceMeta(rarity = 1),
                    isEquipped = false
                )
            ))
        }
        tables.storageBagItems[1] = listOf(
            StorageBagItem(
                itemId = "i1", itemType = "equipment_instance", name = "精铁剑", rarity = 1,
                equipmentInstance = mockStore.equipmentInstances.value.first()
            )
        )
        val deadDisciple = tables.assemble(1)

        // 重复死亡处理（幂等性验证：第二次不重复物化）
        repeat(2) {
            processor.handleDiscipleDeath(deadDisciple, isOutsideSect = false)
        }

        // B3：实例保留实例表（下线态，玩家保留装备），袋条目清空防双持有
        assertEquals("实例应保留实例表（玩家保留）", 1, mockStore.equipmentInstances.value.size)
        assertEquals("实例仍为下线态", false, mockStore.equipmentInstances.value.first().isEquipped)
        // 袋清空（幂等）
        assertTrue("袋条目已清空", tables.storageBagItems[1].isNullOrEmpty())
    }
}
