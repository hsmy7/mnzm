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
import com.xianxia.sect.core.model.SocialData
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
        social: SocialData = SocialData(),
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
            social = social,
            skills = skills
        )
        tables.insert(disciple)
        if (!skipTablesIsAlive) {
            tables.isAlive[id] = 1
        }
    }

    // ══════════════════════════════════════
    // processGriefExpiry
    // ══════════════════════════════════════

    @Test
    fun `processGriefExpiry - griefEndYear less than currentYear clears grief`() = runTest {
        insertDisciple(1, social = SocialData(griefEndYear = 8))

        processor.processGriefExpiry(currentYear = 10)

        assertEquals("griefEndYear should be -1 (sentinel) after expiry",
            -1, tables.griefEndYears.getOrDefault(1, -1))
    }

    @Test
    fun `processGriefExpiry - griefEndYear equals currentYear clears grief`() = runTest {
        insertDisciple(1, social = SocialData(griefEndYear = 10))

        processor.processGriefExpiry(currentYear = 10)

        assertEquals("griefEndYear should be -1 (sentinel) after expiry",
            -1, tables.griefEndYears.getOrDefault(1, -1))
    }

    @Test
    fun `processGriefExpiry - griefEndYear greater than currentYear keeps grief`() = runTest {
        insertDisciple(1, social = SocialData(griefEndYear = 15))

        processor.processGriefExpiry(currentYear = 10)

        // IntComponentTable 使用 -1 哨兵表示"无哀悼期"，getOrNull 返回 null 仅当 key 缺失
        assertEquals("griefEndYear should persist when not yet expired",
            15, tables.griefEndYears.getOrDefault(1, -1))
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
        tables.storageBagItems[1] = listOf(
            StorageBagItem(
                itemId = "i1", itemType = "equipment_instance", name = "精铁剑", rarity = 1,
                equipmentInstance = EquipmentInstance(id = "i1", name = "精铁剑", rarity = 1,
                    slot = EquipmentSlot.WEAPON)
            )
        )
        val deadDisciple = tables.assemble(1)

        // 重复死亡处理（幂等性验证：第二次不重复物化）
        repeat(2) {
            processor.handleDiscipleDeath(deadDisciple, isOutsideSect = false)
        }

        // 物化：仓库新增实例堆叠恰 1 条（重复处理不重复物化；toStack 随机堆叠 id）；
        // 实例表已移除防双持有
        assertEquals("实例堆叠入仓恰 1 条", 1, mockStore.equipmentStacks.value.size)
        // 幂等关键守卫：重复死亡处理不复制——数量仍为 1（若二次物化会 merge 成 2）
        assertEquals("重复死亡不复制（数量仍 1）", 1, mockStore.equipmentStacks.value.first().quantity)
        assertEquals("实例表已移除防双持有", 0, mockStore.equipmentInstances.value.count { it.id == "i1" })
        // 袋清空（幂等）
        assertTrue("袋条目已清空", tables.storageBagItems[1].isNullOrEmpty())
    }

    @Test
    fun `handleDiscipleDeath - partner relationship is unbound`() = runTest {
        insertDisciple(1, social = SocialData(partnerId = "2"))
        insertDisciple(2, social = SocialData(partnerId = "1"))
        val deadDisciple = tables.assemble(1)

        processor.handleDiscipleDeath(deadDisciple, isOutsideSect = false)

        assertNull("partner's partnerId should be null",
            tables.partnerIds.getOrNull(2))
    }

    @Test
    fun `handleDiscipleDeath - master relationship unbound for apprentice`() = runTest {
        insertDisciple(1)
        insertDisciple(2)
        tables.masterIds[2] = "1"
        val deadDisciple = tables.assemble(1)

        processor.handleDiscipleDeath(deadDisciple, isOutsideSect = false)

        assertNull("apprentice's masterId should be null",
            tables.masterIds.getOrNull(2))
    }
}
