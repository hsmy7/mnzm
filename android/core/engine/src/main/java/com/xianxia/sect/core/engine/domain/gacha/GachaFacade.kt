package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.model.GachaHistoryEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * 寻访（角色卡池）域门面——第 8 个领域 Facade。
 *
 * G01 骨架：状态流与抽卡入口占位；权威 roll/保底/碎片/升星在 C++
 * `gacha_tx`（G09），Kotlin 只展示，不反向写 Store。
 * 禁止塞进 DiscipleFacade。
 */
interface GachaFacade {
    /** 保底进度 map：poolId → 已抽次数（0..pityThreshold-1） */
    val pityCounters: StateFlow<Map<String, Int>>

    /** 碎片进度：templateId → x（相对下一星，0..fragmentsPerStar-1） */
    val fragmentCounts: StateFlow<Map<String, Int>>

    /** 星级：templateId → star（1..maxStar；未解锁无键） */
    val starMap: StateFlow<Map<String, Int>>

    /** 最近寻访历史（环缓冲，按抽记条，新在前） */
    val history: StateFlow<List<GachaHistoryEntry>>

    /**
     * 单抽占位。G09 前返回 [GachaPullResult.NotReady]；
     * G09 起经 ActionId `GACHA_PULL_ONCE` 走 C++ 事务。
     */
    suspend fun pullOnce(poolId: String = "standard"): GachaPullResult

    /** 十连占位（一笔事务内原子 10 次单抽语义）。 */
    suspend fun pullTen(poolId: String = "standard"): GachaPullResult
}

/** 寻访历史条目（按抽；保底格 [isPity]=true）。 */
sealed interface GachaPullResult {
    data object NotReady : GachaPullResult
    data class Failure(val reason: String) : GachaPullResult
}
