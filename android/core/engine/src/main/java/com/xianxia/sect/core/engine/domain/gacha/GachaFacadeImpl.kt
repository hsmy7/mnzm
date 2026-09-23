package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.model.GachaHistoryEntry
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [GachaFacade] 实现（G01 骨架）。
 * G09 接入 `GACHA_PULL_ONCE` / `GACHA_PULL_TEN` 后改为 native 事务；
 * 当前恒返回 [GachaPullResult.NotReady]。
 */
@Singleton
class GachaFacadeImpl @Inject constructor(
    private val gachaService: GachaService,
) : GachaFacade {

    override val pityCounters: StateFlow<Map<String, Int>> = gachaService.pityCounters
    override val fragmentCounts: StateFlow<Map<String, Int>> = gachaService.fragmentCounts
    override val starMap: StateFlow<Map<String, Int>> = gachaService.starMap
    override val history: StateFlow<List<GachaHistoryEntry>> = gachaService.history

    override suspend fun pullOnce(poolId: String): GachaPullResult =
        GachaPullResult.NotReady

    override suspend fun pullTen(poolId: String): GachaPullResult =
        GachaPullResult.NotReady
}
