package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*

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
fun GameEngine.isGameStarted(): Boolean = saveFacade.isGameStarted()
fun GameEngine.getFormattedGameTime(): String = saveFacade.getFormattedGameTime()
