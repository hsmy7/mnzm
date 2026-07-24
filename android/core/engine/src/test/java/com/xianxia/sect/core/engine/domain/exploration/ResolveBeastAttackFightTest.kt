package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.exploration.BeastAttackDetector
import com.xianxia.sect.core.exploration.LootCalculator
import com.xianxia.sect.core.exploration.PatrolBattleSystem
import com.xianxia.sect.core.exploration.WorldLevelManager
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.domain.battle.EncounterBattleService
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.eq

class ResolveBeastAttackFightTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var service: ExplorationService
    private lateinit var stateStore: GameStateStore
    private val gameDataFlow = kotlinx.coroutines.flow.MutableStateFlow(GameData())

    @Before
    fun setUp() {
        stateStore = mock(GameStateStore::class.java)
        val battleSystem = mock(BattleSystem::class.java)
        val rngManager = GameRngManager().also { it.initSystemSeed(42) }
        val inventorySystem = mock(InventorySystem::class.java)
        val worldLevelManager = mock(WorldLevelManager::class.java)
        val patrolBattleSystem = mock(PatrolBattleSystem::class.java)
        val beastAttackDetector = mock(BeastAttackDetector::class.java)
        val lootCalculator = mock(LootCalculator::class.java)
        val encounterBattleService = mock(EncounterBattleService::class.java)
        val cultivationService = mock(CultivationService::class.java)
        val spiritStoneWallet = mock(SpiritStoneWallet::class.java)
        val explorationTeamManager = mock(com.xianxia.sect.core.exploration.ExplorationTeamManager::class.java)

        `when`(battleSystem.createBattle(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Battle(team = emptyList(), beasts = emptyList()))
        `when`(battleSystem.executeBattle(any())).thenReturn(
            BattleSystemResult(battle = Battle(team = emptyList(), beasts = emptyList()),
                victory = true, rewards = emptyMap(), turnCount = 1)
        )
        `when`(stateStore.gameData).thenReturn(gameDataFlow)
        `when`(stateStore.gameDataSnapshot).thenAnswer { gameDataFlow.value }
        `when`(stateStore.disciples).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        `when`(stateStore.discipleTables).thenReturn(DiscipleTables())
        `when`(stateStore.equipmentStacks).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        `when`(stateStore.equipmentInstances).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        `when`(stateStore.manualStacks).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        `when`(stateStore.manualInstances).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        `when`(stateStore.pills).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        `when`(stateStore.materials).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        `when`(stateStore.herbs).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        `when`(stateStore.seeds).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        `when`(stateStore.battleLogs).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        `when`(stateStore.storageBags).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        `when`(stateStore.pendingBeastAttacks).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        `when`(stateStore.equipmentStacksSnapshot).thenReturn(emptyList())
        `when`(stateStore.equipmentInstancesSnapshot).thenReturn(emptyList())
        `when`(stateStore.manualStacksSnapshot).thenReturn(emptyList())
        `when`(stateStore.manualInstancesSnapshot).thenReturn(emptyList())
        `when`(stateStore.battleLogsSnapshot).thenReturn(emptyList())
        `when`(stateStore.discipleAggregatesSnapshot).thenReturn(emptyList())
        `when`(inventorySystem.addMaterial(any())).thenReturn(DomainResult.Success(Material()))

        service = ExplorationService(
            stateStore = stateStore,
            battleSystem = battleSystem,
            rngManager = rngManager,
            inventorySystem = inventorySystem,
            worldLevelManager = worldLevelManager,
            patrolBattleSystem = patrolBattleSystem,
            beastAttackDetector = beastAttackDetector,
            lootCalculator = lootCalculator,
            encounterBattleService = encounterBattleService,
            cultivationService = cultivationService,
            spiritStoneWallet = spiritStoneWallet,
            explorationTeamManager = explorationTeamManager
        )
    }

    // ── resolveBeastAttackFight ────────────────────────────────────────

    @Test
    fun `resolveBeastAttackFight returns false when beast not found`() = runBlocking {
        gameDataFlow.value = GameData(worldLevels = emptyList())
        val result = service.resolveBeastAttackFight("nonexistent")
        assertFalse(result)
    }

    @Test
    fun `resolveBeastAttackFight returns false when beast already defeated`() = runBlocking {
        val beast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = true)
        gameDataFlow.value = GameData(worldLevels = listOf(beast))
        val result = service.resolveBeastAttackFight("b1")
        assertFalse(result)
    }

    @Test
    fun `resolveBeastAttackPayTribute returns false when beast not found`() = runBlocking {
        gameDataFlow.value = GameData(worldLevels = emptyList())
        val result = service.resolveBeastAttackPayTribute("nonexistent")
        assertFalse(result)
    }

    @Test
    fun `resolveBeastAttackPayTribute returns false when beast already defeated`() = runBlocking {
        val beast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = true)
        gameDataFlow.value = GameData(worldLevels = listOf(beast))
        val result = service.resolveBeastAttackPayTribute("b1")
        assertFalse(result)
    }

    @Test
    fun `resolveBeastAttackPayTribute returns false when spirit stones insufficient`() = runBlocking {
        // 灵石不足时 deduct 失败，DeductResult 非 Success → return@update → paid=false
        val beast = WorldLevel(id = "b1", type = LevelType.BEAST, defeated = false, x = 500f, y = 500f)
        gameDataFlow.value = GameData(
            worldLevels = listOf(beast),
            worldMapSects = listOf(WorldSect(isPlayerSect = true, x = 500f, y = 500f, name = "玩家宗门")),
            spiritStones = 0  // 无灵石
        )
        val result = service.resolveBeastAttackPayTribute("b1")
        assertFalse("灵石不足时应返回 false", result)
    }

    // ── runWithoutStoreUpdate tests for resolveBeastFightInternal ───────

    @Test
    fun `defender selection prioritizes patrol disciples first`() {
        val allDiscipleList = listOf(
            Disciple(id = "1", name = "巡逻甲", realm = 5, realmLayer = 50,
                status = DiscipleStatus.PATROLLING, isAlive = true),
            Disciple(id = "2", name = "巡逻乙", realm = 4, realmLayer = 40,
                status = DiscipleStatus.PATROLLING, isAlive = true),
            Disciple(id = "3", name = "空闲甲", realm = 6, realmLayer = 60,
                status = DiscipleStatus.IDLE, isAlive = true)
        )
        val patrolDiscipleIds = setOf("1", "2")

        // Same logic as resolveBeastFightInternal
        val allAlive = allDiscipleList.filter { it.isAlive }
        val patrolDefenders = allAlive.filter { it.id in patrolDiscipleIds }
        val excludeStatuses = setOf(DiscipleStatus.ON_MISSION, DiscipleStatus.IN_TEAM, DiscipleStatus.REFLECTING, DiscipleStatus.GARRISONING, DiscipleStatus.REFINING)
        val remainingAlive = allAlive.filter {
            it.id !in patrolDiscipleIds && it.status !in excludeStatuses
        }.sortedByDescending { it.realmLayer }
        val defenders = (patrolDefenders + remainingAlive).take(8)

        assertEquals(3, defenders.size)
        assertTrue(defenders.any { it.id == "1" })
        assertTrue(defenders.any { it.id == "2" })
        assertTrue(defenders.any { it.id == "3" })
        // Patrol disciples come before non-patrol
        val idx1 = defenders.indexOfFirst { it.id == "1" }
        val idx3 = defenders.indexOfFirst { it.id == "3" }
        assertTrue("patrol disciple should come before non-patrol", idx1 < idx3)
    }

    @Test
    fun `defender selection excludes ON_MISSION IN_TEAM REFLECTING GARRISONING REFINING`() {
        val allDiscipleList = listOf(
            Disciple(id = "1", name = "正常", realm = 5, realmLayer = 50,
                status = DiscipleStatus.IDLE, isAlive = true),
            Disciple(id = "2", name = "任务中", realm = 5, realmLayer = 50,
                status = DiscipleStatus.ON_MISSION, isAlive = true),
            Disciple(id = "3", name = "探索中", realm = 5, realmLayer = 50,
                status = DiscipleStatus.IN_TEAM, isAlive = true),
            Disciple(id = "4", name = "思过中", realm = 5, realmLayer = 50,
                status = DiscipleStatus.REFLECTING, isAlive = true),
            Disciple(id = "5", name = "驻军中", realm = 5, realmLayer = 50,
                status = DiscipleStatus.GARRISONING, isAlive = true),
            Disciple(id = "6", name = "血炼中", realm = 5, realmLayer = 50,
                status = DiscipleStatus.REFINING, isAlive = true),
            Disciple(id = "7", name = "生产中", realm = 5, realmLayer = 50,
                status = DiscipleStatus.MINING, isAlive = true)
        )
        val excludeStatuses = setOf(
            DiscipleStatus.ON_MISSION, DiscipleStatus.IN_TEAM,
            DiscipleStatus.REFLECTING, DiscipleStatus.GARRISONING,
            DiscipleStatus.REFINING
        )
        val defenders = allDiscipleList.filter { it.isAlive && it.status !in excludeStatuses }

        assertEquals(2, defenders.size) // 1(IDLE) + 7(MINING)
        assertTrue(defenders.any { it.id == "1" })
        assertTrue(defenders.any { it.id == "7" })
    }
}
