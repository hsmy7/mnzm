package com.xianxia.sect.core.engine

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
import com.xianxia.sect.core.state.RunState



fun GameEngine.getStateSnapshotSync(): GameStateSnapshot {
    // L3a 快照前 flush 年变延迟队列：保证"快照 ⇒ 队列已空"不变量
    //（存档内容不含延迟组残余；加载时队列是进程内新实例，无需 load 路径处理）
    cultivationService.flushYearlyOpsQueue()
    return saveFacade.getStateSnapshotSync()
}
suspend fun GameEngine.getStateSnapshot(): GameStateSnapshot {
    // 同上：本地/云存档统一走门面快照，延迟组在快照前全量执行完毕
    cultivationService.flushYearlyOpsQueue()
    return saveFacade.getStateSnapshot()
}
suspend fun GameEngine.getStateSnapshotSuspend(): GameStateSnapshot {
    cultivationService.flushYearlyOpsQueue()
    return saveFacade.getStateSnapshot()
}
suspend fun GameEngine.loadFromSave(
    loadedGameData: GameData, disciples: List<Disciple>, equipmentStacks: List<EquipmentStack>,
    equipmentInstances: List<EquipmentInstance>, manualStacks: List<ManualStack>,
    manualInstances: List<ManualInstance>, pills: List<Pill>, materials: List<Material>,
    herbs: List<Herb>, seeds: List<Seed>, battleLogs: List<BattleLog>
) = engineContextDispatcher.withEngineContext {
    saveFacade.loadFromSave(
        loadedGameData, disciples, equipmentStacks, equipmentInstances, manualStacks,
        manualInstances, pills, materials, herbs, seeds, battleLogs
    )
    // 2026-09 根因修复（与 loadData 同模式）：读档状态替换后同步 C++ native 引擎基线——
    // 否则 AUTHORITATIVE tick 反向镜像会把 native 残留旧档状态覆盖回 Kotlin
    //（loadFromSave 与 loadSnapshot 同为状态替换入口，同步语义必须一致）
    syncNativeBaselineAfterLoad()
}
fun GameEngine.validateState(): List<String> = saveFacade.validateState()
fun GameEngine.getStateStatistics(): Map<String, Any> = saveFacade.getStateStatistics()
fun GameEngine.isGameStarted(): Boolean = stateStore.runState.value == RunState.PLAYING
fun GameEngine.getFormattedGameTime(): String = saveFacade.getFormattedGameTime()
