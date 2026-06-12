package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*

/**
 * 游戏状态快照提供者接口
 *
 * 在 :core:domain 中定义，由 :app 中的 GameStateStore 实现。
 * 解除 data 模块对 app 模块 GameStateStore 类的直接依赖。
 *
 * 提供 game tick 各字段的即时快照读取（绕过 stateIn 的 Dispatchers.Default 调度延迟）。
 */
interface GameStateSnapshotProvider {
    val gameDataSnapshot: GameData
    val disciplesSnapshot: List<Disciple>
    val equipmentStacksSnapshot: List<EquipmentStack>
    val equipmentInstancesSnapshot: List<EquipmentInstance>
    val manualStacksSnapshot: List<ManualStack>
    val manualInstancesSnapshot: List<ManualInstance>
    val pillsSnapshot: List<Pill>
    val materialsSnapshot: List<Material>
    val herbsSnapshot: List<Herb>
    val seedsSnapshot: List<Seed>
    val storageBagsSnapshot: List<StorageBag>
    val teamsSnapshot: List<ExplorationTeam>
    val battleLogsSnapshot: List<BattleLog>
}
