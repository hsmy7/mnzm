package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.engine.di.IoDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DiscipleLifecycleProcessorTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var mutableState: MutableGameState
    private lateinit var mockStore: GameStateStore
    private lateinit var processor: DiscipleLifecycleProcessor
    private lateinit var gameDataFlow: MutableStateFlow<GameData>
    private lateinit var disciplesFlow: MutableStateFlow<List<Disciple>>

    @Before
    fun setUp() {
        tables = DiscipleTables()
        mutableState = createMutableState(tables)
        gameDataFlow = MutableStateFlow(GameData(gameYear = 10))
        disciplesFlow = MutableStateFlow(emptyList())

        // 创建 delegate mock 但不做任何 property stub。
        // 所有属性在匿名类中直接 override 以避免 Mockito property 状态污染。
        val delegate = mock(GameStateStore::class.java)

        mockStore = object : GameStateStore by delegate {
            override val discipleTables: DiscipleTables get() = tables
            override val gameData: StateFlow<GameData> get() = gameDataFlow
            override val disciples: StateFlow<List<Disciple>> get() = disciplesFlow
            override val equipmentStacks: StateFlow<List<EquipmentStack>>
                get() = MutableStateFlow(emptyList())
            override val equipmentInstances: StateFlow<List<EquipmentInstance>>
                get() = MutableStateFlow(emptyList())
            override val manualStacks: StateFlow<List<ManualStack>>
                get() = MutableStateFlow(emptyList())
            override val manualInstances: StateFlow<List<ManualInstance>>
                get() = MutableStateFlow(emptyList())
            override val pills: StateFlow<List<Pill>>
                get() = MutableStateFlow(emptyList())
            override val materials: StateFlow<List<Material>>
                get() = MutableStateFlow(emptyList())
            override val herbs: StateFlow<List<Herb>>
                get() = MutableStateFlow(emptyList())
            override val seeds: StateFlow<List<Seed>>
                get() = MutableStateFlow(emptyList())
            override val storageBags: StateFlow<List<StorageBag>>
                get() = MutableStateFlow(emptyList())
            override val teams: StateFlow<List<ExplorationTeam>>
                get() = MutableStateFlow(emptyList())
            override val battleLogs: StateFlow<List<BattleLog>>
                get() = MutableStateFlow(emptyList())

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

        // 对所有非 GameStateStore 的依赖使用 Mockito.mock。
        // 这些 mock 在测试方法中不会被 verify，只用作哑对象。
        processor = DiscipleLifecycleProcessor(
            stateStore = mockStore,
            scopeProvider = mock(CoroutineScopeProvider::class.java),
            productionCoordinator = mock(
                com.xianxia.sect.core.engine.domain.production.ProductionCoordinator::class.java
            ),
            eventBus = mock(EventBusPort::class.java),
            discipleSlotCleanup = DiscipleSlotCleanup(
                DiscipleAssignmentGate(DiscipleAssignmentRegistry())
            ),
            lawEnforcementProcessor = object : javax.inject.Provider<LawEnforcementProcessor> {
                override fun get(): LawEnforcementProcessor = mock(LawEnforcementProcessor::class.java)
            },
            discipleStatusService = mock(DiscipleStatusService::class.java),
            ioDispatcher = IoDispatcher(),
            inventorySystem = com.xianxia.sect.core.engine.system.InventorySystem(
                stateStore = mockStore,
                inventoryConfig = mock(InventoryConfig::class.java),
                spiritStoneWallet = mock(com.xianxia.sect.core.wallet.SpiritStoneWallet::class.java),
                gameConfigProvider = mock(com.xianxia.sect.core.engine.config.GameConfigProvider::class.java)
            )
        )
    }

    // ==================== 辅助 ====================

    private fun insertDisciple(
        id: Int,
        name: String = "弟子$id",
        realm: Int = 9,
        realmLayer: Int = 3,
        age: Int = 20,
        lifespan: Int = 80,
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
            age = age,
            lifespan = lifespan,
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

    // ══════════════════════════════════════
    // processGriefExpiry
    // ══════════════════════════════════════

    @Test
    fun `processGriefExpiry - griefEndYear less than currentYear clears grief`() = runTest {
        insertDisciple(1, social = SocialData(griefEndYear = 8))
        disciplesFlow.value = tables.assembleAll()

        processor.processGriefExpiry(currentYear = 10)

        assertEquals("griefEndYear should be -1 (sentinel) after expiry",
            -1, tables.griefEndYears.getOrDefault(1, -1))
    }

    @Test
    fun `processGriefExpiry - griefEndYear equals currentYear clears grief`() = runTest {
        insertDisciple(1, social = SocialData(griefEndYear = 10))
        disciplesFlow.value = tables.assembleAll()

        processor.processGriefExpiry(currentYear = 10)

        assertEquals("griefEndYear should be -1 (sentinel) after expiry",
            -1, tables.griefEndYears.getOrDefault(1, -1))
    }

    @Test
    fun `processGriefExpiry - griefEndYear greater than currentYear keeps grief`() = runTest {
        insertDisciple(1, social = SocialData(griefEndYear = 15))
        disciplesFlow.value = tables.assembleAll()

        processor.processGriefExpiry(currentYear = 10)

        // IntComponentTable 使用 -1 哨兵表示"无哀悼期"，getOrNull 返回 null 仅当 key 缺失
        assertEquals("griefEndYear should persist when not yet expired",
            15, tables.griefEndYears.getOrDefault(1, -1))
    }

    // ══════════════════════════════════════
    // processDiscipleAging
    // ══════════════════════════════════════

    @Test
    fun `processDiscipleAging - age increases by 1 for living disciples`() = runTest {
        insertDisciple(1, age = 25)
        disciplesFlow.value = tables.assembleAll()

        processor.processDiscipleAging(currentYear = 10)

        assertEquals("age should be 26 after aging", 26, tables.ages[1])
    }

    @Test
    fun `processDiscipleAging - 5-year-old with realmLayer 0 gets fixed`() = runTest {
        insertDisciple(1, age = 4, realmLayer = 0)
        disciplesFlow.value = tables.assembleAll()

        processor.processDiscipleAging(currentYear = 10)

        val updated = tables.assemble(1)
        assertEquals("age should be 5", 5, updated.age)
        assertEquals("realmLayer should be 1 after fix", 1, updated.realmLayer)
        assertEquals("status should be IDLE after fix", DiscipleStatus.IDLE, updated.status)
    }

    @Test
    fun `processDiscipleAging - disciple with age beyond maxAge triggers death`() = runTest {
        insertDisciple(1, age = 79, lifespan = 80)
        disciplesFlow.value = tables.assembleAll()

        processor.processDiscipleAging(currentYear = 10)

        // dead disciple should be removed
        val idPresent = tables.ids.contains(1)
        val namePresent = tables.names.getOrNull(1) != null
        assertFalse("dead disciple should be removed from tables", idPresent || namePresent)
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
            skills = SkillStats(morality = 50, loyalty = 50)
        )

        processor.processReflectionRelease(year = 10)

        val updated = tables.assemble(1)
        assertEquals(DiscipleStatus.IDLE, updated.status)
        assertEquals("morality should be 55 after reflection release",
            55, updated.skills.morality)
        assertEquals("loyalty should be 55 after reflection release",
            55, updated.skills.loyalty)
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
        insertDisciple(1, age = 70)
        processor.processYearlyAging(currentYear = 10)
        assertTrue("disciple should remain when no one is dead",
            tables.ids.contains(1))
    }

    @Test
    fun `processYearlyAging - recent dead disciples are not culled`() = runTest {
        insertDisciple(1, age = 70)
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
        insertDisciple(1, age = 80)
        disciplesFlow.value = tables.assembleAll()
        val deadDisciple = tables.assemble(1)

        processor.handleDiscipleDeath(deadDisciple, isOutsideSect = false)

        assertEquals("death year should be 10", 10, tables.deathYears[1])
    }

    @Test
    fun `handleDiscipleDeath - partner relationship is unbound`() = runTest {
        insertDisciple(1, age = 80, social = SocialData(partnerId = "2"))
        insertDisciple(2, age = 75, social = SocialData(partnerId = "1"))
        disciplesFlow.value = tables.assembleAll()
        val deadDisciple = tables.assemble(1)

        processor.handleDiscipleDeath(deadDisciple, isOutsideSect = false)

        assertNull("partner's partnerId should be null",
            tables.partnerIds.getOrNull(2))
    }

    @Test
    fun `handleDiscipleDeath - master relationship unbound for apprentice`() = runTest {
        insertDisciple(1, age = 80)
        insertDisciple(2, age = 30)
        tables.masterIds[2] = "1"
        disciplesFlow.value = tables.assembleAll()
        val deadDisciple = tables.assemble(1)

        processor.handleDiscipleDeath(deadDisciple, isOutsideSect = false)

        assertNull("apprentice's masterId should be null",
            tables.masterIds.getOrNull(2))
    }

    // ══════════════════════════════════════
    // processDiscipleAging — dead skipped
    // ══════════════════════════════════════

    @Test
    fun `processDiscipleAging - dead disciples are not aged`() = runTest {
        insertDisciple(1, age = 50)
        tables.isAlive[1] = 0
        disciplesFlow.value = tables.assembleAll()

        processor.processDiscipleAging(currentYear = 10)

        assertTrue("dead disciple should still be in tables", tables.ids.contains(1))
        assertEquals("dead disciple age should not change", 50, tables.ages[1])
    }
}
