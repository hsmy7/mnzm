package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.domain.*
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 领域状态提供者 — 从 GameData 派生领域状态
 *
 * Phase B: 业务层通过此提供者读取领域状态，为后续拆表做准备。
 * 当前所有领域状态均从 GameData 字段提取（extractXxxState），
 * Phase C 将引入独立 DB 表后改为直接读取 Repository。
 */
@Singleton
class DomainStateProvider @Inject constructor(
    private val stateStore: GameStateStore,
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    val diplomacyState: StateFlow<DiplomacyDomainState> = stateStore.gameData
        .map { it.extractDiplomacyState() }
        .distinctUntilChanged()
        .stateIn(
            applicationScopeProvider.scope,
            SharingStarted.WhileSubscribed(5_000),
            DiplomacyDomainState()
        )

    val productionState: StateFlow<ProductionDomainState> = stateStore.gameData
        .map { it.extractProductionState() }
        .distinctUntilChanged()
        .stateIn(
            applicationScopeProvider.scope,
            SharingStarted.WhileSubscribed(5_000),
            ProductionDomainState()
        )

    val patrolState: StateFlow<PatrolDomainState> = stateStore.gameData
        .map { it.extractPatrolState() }
        .distinctUntilChanged()
        .stateIn(
            applicationScopeProvider.scope,
            SharingStarted.WhileSubscribed(5_000),
            PatrolDomainState()
        )

    val worldMapState: StateFlow<WorldMapDomainState> = stateStore.gameData
        .map { it.extractWorldMapState() }
        .distinctUntilChanged()
        .stateIn(
            applicationScopeProvider.scope,
            SharingStarted.WhileSubscribed(5_000),
            WorldMapDomainState()
        )

    val sectPolicyState: StateFlow<SectPolicyDomainState> = stateStore.gameData
        .map { it.extractSectPolicyState() }
        .distinctUntilChanged()
        .stateIn(
            applicationScopeProvider.scope,
            SharingStarted.WhileSubscribed(5_000),
            SectPolicyDomainState()
        )
}
