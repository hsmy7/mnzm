package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag



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
    val battleLogsSnapshot: List<BattleLog>
}
