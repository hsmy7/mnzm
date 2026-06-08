package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*

// 直接读取快照（绕过 stateIn 的 Dispatchers.Default 调度延迟）
val GameStateStore.gameDataSnapshot: GameData get() = _gameDataFlow.value
val GameStateStore.discipleAggregatesSnapshot: List<DiscipleAggregate> get() = _disciplesFlow.value.map { it.toAggregate() }
val GameStateStore.disciplesSnapshot: List<Disciple> get() = _disciplesFlow.value
val GameStateStore.equipmentStacksSnapshot: List<EquipmentStack> get() = _equipmentStacksFlow.value
val GameStateStore.equipmentInstancesSnapshot: List<EquipmentInstance> get() = _equipmentInstancesFlow.value
val GameStateStore.manualStacksSnapshot: List<ManualStack> get() = _manualStacksFlow.value
val GameStateStore.manualInstancesSnapshot: List<ManualInstance> get() = _manualInstancesFlow.value
val GameStateStore.pillsSnapshot: List<Pill> get() = _pillsFlow.value
val GameStateStore.materialsSnapshot: List<Material> get() = _materialsFlow.value
val GameStateStore.herbsSnapshot: List<Herb> get() = _herbsFlow.value
val GameStateStore.seedsSnapshot: List<Seed> get() = _seedsFlow.value
val GameStateStore.storageBagsSnapshot: List<StorageBag> get() = _storageBagsFlow.value
val GameStateStore.teamsSnapshot: List<ExplorationTeam> get() = _teamsFlow.value
val GameStateStore.battleLogsSnapshot: List<BattleLog> get() = _battleLogsFlow.value

fun GameStateStore.getCurrentSeeds(): List<Seed> = _seedsFlow.value
fun GameStateStore.getCurrentHerbs(): List<Herb> = _herbsFlow.value
fun GameStateStore.getCurrentMaterials(): List<Material> = _materialsFlow.value
