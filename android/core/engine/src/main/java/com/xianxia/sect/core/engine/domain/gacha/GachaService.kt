package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 寻访域编排骨架（G01）。权威逻辑在 C++ `gacha_tx`（G09）；
 * 本类只聚合镜像状态供 UI 订阅，**不写 Store**。
 */
@Singleton
class GachaService @Inject constructor(
    private val stateStore: GameStateStore,
    scopeProvider: CoroutineScopeProvider,
) {
    private val scope = scopeProvider.scope

    val pityCounters: StateFlow<Map<String, Int>> = stateStore.gameData
        .map { it.gachaPityCounters }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val fragmentCounts: StateFlow<Map<String, Int>> = stateStore.gameData
        .map { it.gachaFragmentCounts }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val starMap: StateFlow<Map<String, Int>> = stateStore.gameData
        .map { it.gachaStarMap }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val history: StateFlow<List<GachaHistoryEntry>> = stateStore.gameData
        .map { it.gachaHistory }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
}
