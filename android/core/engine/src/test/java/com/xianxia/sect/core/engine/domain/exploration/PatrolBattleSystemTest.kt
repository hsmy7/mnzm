package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.exploration.PatrolBattleSystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any

class PatrolBattleSystemTest {

    private lateinit var system: PatrolBattleSystem

    @Before
    fun setUp() {
        val battleSystem = mock(BattleSystem::class.java)
        val rngManager = GameRngManager().also { it.initSystemSeed(42) }
        val inventorySystem = mock(InventorySystem::class.java)
        val buildingConfigService = mock(BuildingConfigService::class.java)
        `when`(buildingConfigService.getSlotCountByDisplayName("巡视楼")).thenReturn(2)
        runBlocking { `when`(inventorySystem.addMaterial(any())).thenReturn(DomainResult.Success(Material())) }
        `when`(battleSystem.createBattle(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Battle(team = emptyList(), beasts = emptyList()))
        `when`(battleSystem.executeBattle(any())).thenReturn(BattleSystemResult(
            battle = Battle(team = emptyList(), beasts = emptyList()),
            victory = true, rewards = mapOf("spiritStones" to 100), turnCount = 1))
        system = PatrolBattleSystem(battleSystem, rngManager, inventorySystem, buildingConfigService, DiscipleDeathHandler())
    }

    private fun emptyState() = MutableGameState(
        gameData = GameData(patrolConfigs = listOf(PatrolConfig()), gameYear = 5, gameMonth = 6),
        discipleTables = DiscipleTables(),
        equipmentStacks = EntityStore(), equipmentInstances = EntityStore(),
        manualStacks = EntityStore(), manualInstances = EntityStore(),
        pills = EntityStore(), materials = EntityStore(),
        herbs = EntityStore(), seeds = EntityStore(), storageBags = EntityStore(),
        teams = emptyList(), battleLogs = emptyList(),
        isPaused = false, isLoading = false, isSaving = false
    )

    @Test fun `empty state no crash`() = runBlocking { system.executePatrolRound(emptyState()) }

    @Test fun `empty slots no crash`() = runBlocking {
        val s = emptyState().apply { gameData = gameData.copy(patrolSlots = listOf(PatrolSlot(index = 0, discipleId = "1"))) }
        system.executePatrolRound(s)
    }

    @Test fun `no towers no crash`() = runBlocking {
        val s = emptyState().apply { gameData = gameData.copy(patrolSlots = listOf(PatrolSlot(index = 0, discipleId = "1", buildingInstanceId = "b1"))) }
        system.executePatrolRound(s)
    }

    @Test fun `no beasts means no battle`() = runBlocking {
        val s = emptyState().apply {
            gameData = gameData.copy(patrolSlots = listOf(PatrolSlot(index = 0, discipleId = "1", buildingInstanceId = "b1")),
                placedBuildings = listOf(GridBuildingData(instanceId = "b1", displayName = "巡视楼")))
        }
        system.executePatrolRound(s)
        assertTrue(s.battleLogs.isEmpty())
    }

    @Test fun `consumePendingPatrolResults empty initially`() { assertTrue(system.consumePendingPatrolResults().isEmpty()) }

    @Test fun `consumePendingPatrolResults idempotent`() { repeat(3) { assertTrue(system.consumePendingPatrolResults().isEmpty()) } }

    @Test fun `full pipeline no crash`() = runBlocking {
        val s = emptyState().apply {
            discipleTables.insert(Disciple(id = "1", name = "甲"))
            gameData = gameData.copy(patrolSlots = listOf(PatrolSlot(index = 0, discipleId = "1", buildingInstanceId = "b1")),
                placedBuildings = listOf(GridBuildingData(instanceId = "b1", displayName = "巡视楼")),
                worldLevels = listOf(WorldLevel(id = "b1", type = LevelType.BEAST,
                    defeated = false, realm = 9, count = 1, beastName = "虎妖",
                    expiryYear = 10, expiryMonth = 12, x = 100f, y = 100f)))
        }
        system.executePatrolRound(s)
    }
}
