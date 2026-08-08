package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.state.RunState



fun GameEngine.getStateSnapshotSync(): GameStateSnapshot = saveFacade.getStateSnapshotSync()
suspend fun GameEngine.getStateSnapshot(): GameStateSnapshot = saveFacade.getStateSnapshot()
suspend fun GameEngine.getStateSnapshotSuspend(): GameStateSnapshot = saveFacade.getStateSnapshot()
suspend fun GameEngine.loadFromSave(
    loadedGameData: GameData, disciples: List<Disciple>, equipmentStacks: List<EquipmentStack>,
    equipmentInstances: List<EquipmentInstance>, manualStacks: List<ManualStack>,
    manualInstances: List<ManualInstance>, pills: List<Pill>, materials: List<Material>,
    herbs: List<Herb>, seeds: List<Seed>, battleLogs: List<BattleLog>, teams: List<ExplorationTeam>
) = saveFacade.loadFromSave(loadedGameData, disciples, equipmentStacks, equipmentInstances, manualStacks, manualInstances, pills, materials, herbs, seeds, battleLogs, teams)
fun GameEngine.validateState(): List<String> = saveFacade.validateState()
fun GameEngine.getStateStatistics(): Map<String, Any> = saveFacade.getStateStatistics()
fun GameEngine.isGameStarted(): Boolean = stateStore.runState.value == RunState.PLAYING
fun GameEngine.getFormattedGameTime(): String = saveFacade.getFormattedGameTime()
