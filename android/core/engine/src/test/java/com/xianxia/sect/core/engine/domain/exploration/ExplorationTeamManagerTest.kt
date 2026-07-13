package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.DomainEventSubscriber
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.exploration.ExplorationTeamManager
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class ExplorationTeamManagerTest {

    private lateinit var stateStore: FakeStore
    private lateinit var manager: ExplorationTeamManager

    @Before
    fun setUp() {
        stateStore = FakeStore()
        manager = ExplorationTeamManager(stateStore, mock())
    }

    @Test fun `recallDiscipleFromTeam false for empty teams`() = runTest {
        assertFalse(manager.recallDiscipleFromTeam("t1", "d1"))
    }

    @Test fun `recallDiscipleFromTeam false for missing team`() = runTest {
        stateStore.teamList = listOf(team("t1", listOf("d1")))
        assertFalse(manager.recallDiscipleFromTeam("no-such", "d1"))
    }

    @Test fun `recallDiscipleFromTeam false for non-member`() = runTest {
        stateStore.teamList = listOf(team("t1", listOf("d1")))
        assertFalse(manager.recallDiscipleFromTeam("t1", "d2"))
    }

    @Test fun `recallDiscipleFromTeam removes team when last member leaves`() = runTest {
        stateStore.teamList = listOf(team("t1", listOf("d1")))
        assertTrue(manager.recallDiscipleFromTeam("t1", "d1"))
        assertTrue(stateStore.teamList.none { it.id == "t1" })
    }

    @Test fun `recallDiscipleFromTeam updates member list`() = runTest {
        stateStore.teamList = listOf(team("t1", listOf("d1", "d2")))
        assertTrue(manager.recallDiscipleFromTeam("t1", "d1"))
        assertEquals(listOf("d2"), stateStore.teamList.find { it.id == "t1" }?.memberIds)
    }

    @Test fun `completeExploration noop for empty`() = runTest {
        manager.completeExploration("t1", true, emptyList())
    }

    @Test fun `completeExploration skips already completed`() = runTest {
        stateStore.teamList = listOf(team("t1", listOf("d1"), ExplorationStatus.COMPLETED))
        manager.completeExploration("t1", true, listOf("d1"))
        assertEquals(ExplorationStatus.COMPLETED, stateStore.teamList[0].status)
    }

    @Test fun `completeExploration marks team completed`() = runTest {
        stateStore.teamList = listOf(team("t1", listOf("d1"), ExplorationStatus.EXPLORING))
        manager.completeExploration("t1", true, listOf("d1"))
        assertEquals(ExplorationStatus.COMPLETED, stateStore.teamList[0].status)
    }

    private fun team(id: String, members: List<String>, status: ExplorationStatus = ExplorationStatus.EXPLORING) =
        ExplorationTeam(id = id, status = status, memberIds = members, memberNames = members)
}


private class FakeStore : GameStateStore {
    var teamList: List<ExplorationTeam> = emptyList()
    private var gd = GameData()

    override val teams = MutableStateFlow(teamList)
    override val disciples = MutableStateFlow<List<Disciple>>(emptyList())
    override val gameData = MutableStateFlow(gd)
    override val unifiedState = MutableStateFlow(UnifiedGameState())
    override val highFreqState = MutableStateFlow(GameStateStore.HighFreqState())
    override val entityState = MutableStateFlow(GameStateStore.EntityState())
    override val configState = MutableStateFlow(GameStateStore.ConfigState())
    override val battleLogs = MutableStateFlow<List<BattleLog>>(emptyList())
    override val equipmentStacks = MutableStateFlow<List<EquipmentStack>>(emptyList())
    override val equipmentInstances = MutableStateFlow<List<EquipmentInstance>>(emptyList())
    override val manualStacks = MutableStateFlow<List<ManualStack>>(emptyList())
    override val manualInstances = MutableStateFlow<List<ManualInstance>>(emptyList())
    override val pills = MutableStateFlow<List<Pill>>(emptyList())
    override val materials = MutableStateFlow<List<Material>>(emptyList())
    override val herbs = MutableStateFlow<List<Herb>>(emptyList())
    override val seeds = MutableStateFlow<List<Seed>>(emptyList())
    override val storageBags = MutableStateFlow<List<StorageBag>>(emptyList())
    override val isPaused = MutableStateFlow(false)
    override val isLoading = MutableStateFlow(false)
    override val isSaving = MutableStateFlow(false)
    override val pendingBattleResult = MutableStateFlow<BattleResultUIData?>(null)
    override val pendingNotification = MutableStateFlow<GameNotification?>(null)
    override val rewardCardQueue = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val pendingBeastAttacks = MutableStateFlow<List<PendingBeastAttack>>(emptyList())
    override val pendingBattleRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    override val sectCombatPower = MutableStateFlow(0L)
    override val aiSectCombatPowers = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val discipleAggregates = MutableStateFlow<List<DiscipleAggregate>>(emptyList())
    override val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = emptyList()
    override val gameDataSnapshot: GameData get() = gd
    override val disciplesSnapshot: List<Disciple> get() = disciples.value
    override val equipmentStacksSnapshot: List<EquipmentStack> get() = equipmentStacks.value
    override val equipmentInstancesSnapshot: List<EquipmentInstance> get() = equipmentInstances.value
    override val manualStacksSnapshot: List<ManualStack> get() = manualStacks.value
    override val manualInstancesSnapshot: List<ManualInstance> get() = manualInstances.value
    override val pillsSnapshot: List<Pill> get() = pills.value
    override val materialsSnapshot: List<Material> get() = materials.value
    override val herbsSnapshot: List<Herb> get() = herbs.value
    override val seedsSnapshot: List<Seed> get() = seeds.value
    override val storageBagsSnapshot: List<StorageBag> get() = storageBags.value
    override val teamsSnapshot: List<ExplorationTeam> get() = teamList
    override val battleLogsSnapshot: List<BattleLog> get() = battleLogs.value
    override val discipleTables: DiscipleTables = DiscipleTables()
    override val warehouseFullEvent = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val gameLifecycle = MutableStateFlow(GameLifecycle.UNINITIALIZED)
    override val bootPhase = MutableStateFlow(BootPhase.UNINITIALIZED)
    override val runState = MutableStateFlow(RunState.IDLE)
    override var activeTab: String = ""
    override var activeDialog: String? = ""
    override var activeSubDialogs: Set<String> = emptySet()

    override fun getCurrentSeeds(): List<Seed> = seeds.value
    override fun getCurrentHerbs(): List<Herb> = herbs.value
    override fun getCurrentMaterials(): List<Material> = materials.value
    override fun setPendingNotification(notification: GameNotification) { pendingNotification.value = notification }
    override fun clearPendingNotification() { pendingNotification.value = null }
    override fun setPendingBattleResult(result: BattleResultUIData) { pendingBattleResult.value = result }
    override fun clearPendingBattleResult() { pendingBattleResult.value = null }
    override fun setPendingBeastAttacks(attacks: List<PendingBeastAttack>) { pendingBeastAttacks.value = attacks }
    override fun clearPendingBeastAttacks() { pendingBeastAttacks.value = emptyList() }
    override fun setPendingBattleRewardCards(cards: List<RewardCardItem>) { pendingBattleRewardCards.value = cards }
    override fun clearPendingBattleRewardCards() { pendingBattleRewardCards.value = emptyList() }
    override fun enqueueRewardCards(items: List<RewardCardItem>) {}
    override fun clearRewardCardQueue(count: Int) {}
    override fun transitionTo(state: GameLifecycle) {}
    override fun forceLifecycle(state: GameLifecycle) {}
    override fun advanceBootPhase() {}
    override fun resetBootPhase() {}
    override fun setPlaying() {}
    override fun setReloading() {}
    override fun setPausedDirect(paused: Boolean) { isPaused.value = paused }
    override fun setLoadingDirect(loading: Boolean) { isLoading.value = loading }
    override fun setSavingDirect(saving: Boolean) { isSaving.value = saving }
    override fun createSettlementShadow(productionSlots: List<com.xianxia.sect.core.model.production.ProductionSlot>): MutableGameState {
        return MutableGameState(gameData = GameData(), discipleTables = DiscipleTables(),
            equipmentStacks = EntityStore(), equipmentInstances = EntityStore(),
            manualStacks = EntityStore(), manualInstances = EntityStore(),
            pills = EntityStore(), materials = EntityStore(),
            herbs = EntityStore(), seeds = EntityStore(), storageBags = EntityStore(),
            teams = emptyList(), battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false)
    }
    override suspend fun swapFromShadow(shadow: MutableGameState) {}
    override suspend fun loadFromSnapshot(gameData: GameData, disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>, equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>, manualInstances: List<ManualInstance>,
        pills: List<Pill>, materials: List<Material>, herbs: List<Herb>, seeds: List<Seed>,
        storageBags: List<StorageBag>, teams: List<ExplorationTeam>, battleLogs: List<BattleLog>,
        isPaused: Boolean, isLoading: Boolean, isSaving: Boolean) {}
    override suspend fun reset() {}

    override fun update(block: MutableGameState.() -> Unit) {
        val m = newMutable()
        block(m)
        teamList = m.teams
        gd = m.gameData
        teams.value = teamList
        gameData.value = gd
    }

    override fun <R> updateAndReturn(block: MutableGameState.() -> R): R {
        val m = newMutable()
        val result = block(m)
        teamList = m.teams
        gd = m.gameData
        teams.value = teamList
        gameData.value = gd
        return result
    }

    override fun modifyState(block: MutableGameState.() -> Unit) { update(block) }
    override fun enterBatchEmissionMode() {}
    override fun exitBatchEmissionMode() {}

    private fun newMutable() = MutableGameState(
        gameData = gd, discipleTables = DiscipleTables(),
        equipmentStacks = EntityStore(), equipmentInstances = EntityStore(),
        manualStacks = EntityStore(), manualInstances = EntityStore(),
        pills = EntityStore(), materials = EntityStore(),
        herbs = EntityStore(), seeds = EntityStore(), storageBags = EntityStore(),
        teams = teamList, battleLogs = emptyList(),
        isPaused = false, isLoading = false, isSaving = false
    )
}
