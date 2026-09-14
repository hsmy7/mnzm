package com.xianxia.sect.core.engine

import com.xianxia.sect.core.state.RunState



/**
 * 地图冻结（WS-5b）"生成即数据"回填：把 boot/读档路径生成的地形段
 * 落为 GameData 权威数据（幂等——已有段零写入）。
 *
 * 判定口径："存的地形恒优先"（terrainTiles 非空即采用，跨版本冻结不重算）；
 * 本函数只负责**首次**落段。写入即存档字段（terrainTiles/mapGenVersion 经
 * 存档链路持久化）；反向通道按"在册保留字段"照常传输本次一次性变更
 * （C++ 侧由 importStateInternal ensureTerrainGenerated 同源生成，内容逐位
 * 相同，回导为幂等覆盖）。engineContext 派发到引擎线程。
 *
 * @param flatTiles 行主序 flat 瓦片段（index = row*worldWidthCells+col）
 * @return true = 本次执行了回填；false = 已有段（幂等跳过）
 */
suspend fun GameEngine.ensureSectTerrainBackfilled(flatTiles: IntArray): Boolean {
    return engineContextDispatcher.withEngineContext {
        val gd = stateStore.gameDataSnapshot
        if (gd.terrainTiles.isNotEmpty()) return@withEngineContext false
        stateStore.update {
            gameData = gameData.copy(
                terrainTiles = flatTiles.toList(),
                mapGenVersion = com.xianxia.sect.core.GameConfig.SectMap.MAP_GEN_VERSION
            )
        }
        true
    }
}

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
