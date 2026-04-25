package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StateAccessor<T>(
    private val stateStore: GameStateStore,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher? = Dispatchers.IO,
    private val stateGetter: (MutableGameState?) -> T?,
    private val stateSetter: (MutableGameState, T) -> Unit,
    private val fallbackGetter: () -> T
) {
    var current: T
        get() {
            val ts = stateStore.currentTransactionMutableState()
            return stateGetter(ts) ?: fallbackGetter()
        }
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) {
                stateSetter(ts, value)
                return
            }
            if (dispatcher != null) {
                scope.launch(dispatcher) {
                    stateStore.update { stateSetter(this, value) }
                }
            } else {
                scope.launch {
                    stateStore.update { stateSetter(this, value) }
                }
            }
        }

    suspend fun setCurrentSync(value: T) {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            stateSetter(ts, value)
            return
        }
        stateStore.update { stateSetter(this, value) }
    }
}

class StateAccessorFactory(
    private val stateStore: GameStateStore,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher? = Dispatchers.IO
) {
    fun gameData(): StateAccessor<GameData> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.gameData },
        stateSetter = { s, v -> s.gameData = v },
        fallbackGetter = { stateStore.gameData.value }
    )

    fun gameDataFromUnified(): StateAccessor<GameData> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.gameData },
        stateSetter = { s, v -> s.gameData = v },
        fallbackGetter = { stateStore.unifiedState.value.gameData }
    )

    fun disciples(): StateAccessor<List<Disciple>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.disciples },
        stateSetter = { s, v -> s.disciples = v },
        fallbackGetter = { stateStore.disciples.value }
    )

    fun disciplesFromUnified(): StateAccessor<List<Disciple>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.disciples },
        stateSetter = { s, v -> s.disciples = v },
        fallbackGetter = { stateStore.unifiedState.value.disciples }
    )

    fun equipmentStacks(): StateAccessor<List<EquipmentStack>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.equipmentStacks },
        stateSetter = { s, v -> s.equipmentStacks = v },
        fallbackGetter = { stateStore.equipmentStacks.value }
    )

    fun equipmentInstances(): StateAccessor<List<EquipmentInstance>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.equipmentInstances },
        stateSetter = { s, v -> s.equipmentInstances = v },
        fallbackGetter = { stateStore.equipmentInstances.value }
    )

    fun manualStacks(): StateAccessor<List<ManualStack>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.manualStacks },
        stateSetter = { s, v -> s.manualStacks = v },
        fallbackGetter = { stateStore.manualStacks.value }
    )

    fun manualInstances(): StateAccessor<List<ManualInstance>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.manualInstances },
        stateSetter = { s, v -> s.manualInstances = v },
        fallbackGetter = { stateStore.manualInstances.value }
    )

    fun pills(): StateAccessor<List<Pill>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.pills },
        stateSetter = { s, v -> s.pills = v },
        fallbackGetter = { stateStore.pills.value }
    )

    fun materials(): StateAccessor<List<Material>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.materials },
        stateSetter = { s, v -> s.materials = v },
        fallbackGetter = { stateStore.getCurrentMaterials() }
    )

    fun materialsFromFlow(): StateAccessor<List<Material>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.materials },
        stateSetter = { s, v -> s.materials = v },
        fallbackGetter = { stateStore.materials.value }
    )

    fun herbs(): StateAccessor<List<Herb>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.herbs },
        stateSetter = { s, v -> s.herbs = v },
        fallbackGetter = { stateStore.getCurrentHerbs() }
    )

    fun herbsFromFlow(): StateAccessor<List<Herb>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.herbs },
        stateSetter = { s, v -> s.herbs = v },
        fallbackGetter = { stateStore.herbs.value }
    )

    fun seeds(): StateAccessor<List<Seed>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.seeds },
        stateSetter = { s, v -> s.seeds = v },
        fallbackGetter = { stateStore.getCurrentSeeds() }
    )

    fun seedsFromFlow(): StateAccessor<List<Seed>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.seeds },
        stateSetter = { s, v -> s.seeds = v },
        fallbackGetter = { stateStore.seeds.value }
    )

    fun teams(): StateAccessor<List<ExplorationTeam>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.teams },
        stateSetter = { s, v -> s.teams = v },
        fallbackGetter = { stateStore.teams.value }
    )

    fun teamsFromUnified(): StateAccessor<List<ExplorationTeam>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.teams },
        stateSetter = { s, v -> s.teams = v },
        fallbackGetter = { stateStore.unifiedState.value.teams }
    )

    fun events(): StateAccessor<List<GameEvent>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.events },
        stateSetter = { s, v -> s.events = v },
        fallbackGetter = { stateStore.events.value }
    )

    fun battleLogs(): StateAccessor<List<BattleLog>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.battleLogs },
        stateSetter = { s, v -> s.battleLogs = v },
        fallbackGetter = { stateStore.battleLogs.value }
    )
}
