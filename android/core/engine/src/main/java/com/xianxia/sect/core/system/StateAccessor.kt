package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
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
        get() = stateGetter(null) ?: fallbackGetter()
        set(value) {
            scope.launch(dispatcher ?: Dispatchers.Main) {
                stateStore.modifyState { stateSetter(this, value) }
            }
        }

    suspend fun setCurrentSync(value: T) {
        stateStore.modifyState { stateSetter(this, value) }
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
        stateGetter = { it?.discipleTables?.assembleAll() },
        stateSetter = { s, v ->
            s.discipleTables.clear()
            v.forEach { s.discipleTables.insert(it) }
        },
        fallbackGetter = { stateStore.disciples.value }
    )

    fun disciplesFromUnified(): StateAccessor<List<Disciple>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.discipleTables?.assembleAll() },
        stateSetter = { s, v ->
            s.discipleTables.clear()
            v.forEach { s.discipleTables.insert(it) }
        },
        fallbackGetter = { stateStore.unifiedState.value.disciples }
    )

    fun equipmentStacks(): StateAccessor<List<EquipmentStack>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.equipmentStacks?.items },
        stateSetter = { s, v -> s.equipmentStacks = EntityStore(v) },
        fallbackGetter = { stateStore.equipmentStacks.value }
    )

    fun equipmentInstances(): StateAccessor<List<EquipmentInstance>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.equipmentInstances?.items },
        stateSetter = { s, v -> s.equipmentInstances = EntityStore(v) },
        fallbackGetter = { stateStore.equipmentInstances.value }
    )

    fun manualStacks(): StateAccessor<List<ManualStack>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.manualStacks?.items },
        stateSetter = { s, v -> s.manualStacks = EntityStore(v) },
        fallbackGetter = { stateStore.manualStacks.value }
    )

    fun manualInstances(): StateAccessor<List<ManualInstance>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.manualInstances?.items },
        stateSetter = { s, v -> s.manualInstances = EntityStore(v) },
        fallbackGetter = { stateStore.manualInstances.value }
    )

    fun pills(): StateAccessor<List<Pill>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.pills?.items },
        stateSetter = { s, v -> s.pills = EntityStore(v) },
        fallbackGetter = { stateStore.pills.value }
    )

    fun materials(): StateAccessor<List<Material>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.materials?.items },
        stateSetter = { s, v -> s.materials = EntityStore(v) },
        fallbackGetter = { stateStore.getCurrentMaterials() }
    )

    fun materialsFromFlow(): StateAccessor<List<Material>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.materials?.items },
        stateSetter = { s, v -> s.materials = EntityStore(v) },
        fallbackGetter = { stateStore.materials.value }
    )

    fun herbs(): StateAccessor<List<Herb>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.herbs?.items },
        stateSetter = { s, v -> s.herbs = EntityStore(v) },
        fallbackGetter = { stateStore.getCurrentHerbs() }
    )

    fun herbsFromFlow(): StateAccessor<List<Herb>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.herbs?.items },
        stateSetter = { s, v -> s.herbs = EntityStore(v) },
        fallbackGetter = { stateStore.herbs.value }
    )

    fun seeds(): StateAccessor<List<Seed>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.seeds?.items },
        stateSetter = { s, v -> s.seeds = EntityStore(v) },
        fallbackGetter = { stateStore.getCurrentSeeds() }
    )

    fun seedsFromFlow(): StateAccessor<List<Seed>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.seeds?.items },
        stateSetter = { s, v -> s.seeds = EntityStore(v) },
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

    fun battleLogs(): StateAccessor<List<BattleLog>> = StateAccessor(
        stateStore, scope, dispatcher,
        stateGetter = { it?.battleLogs },
        stateSetter = { s, v -> s.battleLogs = v },
        fallbackGetter = { stateStore.battleLogs.value }
    )
}
