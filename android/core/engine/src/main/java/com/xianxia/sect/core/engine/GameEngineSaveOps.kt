package com.xianxia.sect.core.engine

import com.xianxia.sect.core.state.RunState



fun GameEngine.getStateSnapshotSync(): GameStateSnapshot {
    // L3a 快照前 flush 年变延迟队列：保证"快照 ⇒ 队列已空"不变量
    //（存档内容不含延迟组残余；加载时队列是进程内新实例，无需 load 路径处理）
    cultivationService.flushYearlyOpsQueue()
    // ⚠️ 线程契约：本函数为同步接口，无法自行切换
    // 引擎线程——saveFacade.getStateSnapshotSync() 内的 exportStates() 会读取
    // C++ PCG 分区状态，**必须在引擎线程调用**（当前生产代码无调用方；
    // 挂起版快照请用 GameEngine.buildSaveSnapshot()，自带引擎线程收敛）。
    return saveFacade.getStateSnapshotSync()
}
fun GameEngine.validateState(): List<String> = saveFacade.validateState()
fun GameEngine.getStateStatistics(): Map<String, Any> = saveFacade.getStateStatistics()
fun GameEngine.isGameStarted(): Boolean = stateStore.runState.value == RunState.PLAYING
fun GameEngine.getFormattedGameTime(): String = saveFacade.getFormattedGameTime()
