package com.xianxia.sect.core.engine.domain.save

import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.repository.GameHeavyDataPort
import com.xianxia.sect.core.repository.HeavyDataDecoder



interface SaveFacade {
    val saveService: SaveService
    val heavyDataPort: GameHeavyDataPort
    val heavyDataDecoder: HeavyDataDecoder
    /**
     * 存档自愈发生标记（w3-13 通道关闭配套）：worldMapSects 缺失/缺玩家宗门的
     * 防御性自愈写入后置位——GameEngine 存档包装层读取后触发 native 基线重建
     * （rebaselineNativeMirror，§2.75④ "自愈后全量重建基线"）。每次校验开始时复位。
     */
    val worldMapSelfHealPending: Boolean

    fun getStateSnapshotSync(): GameStateSnapshot
    suspend fun getStateSnapshot(): GameStateSnapshot
    fun validateState(): List<String>
    fun getStateStatistics(): Map<String, Any>
    fun getFormattedGameTime(): String
}
