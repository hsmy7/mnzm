package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.domain.*
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 领域状态提供者 — 从独立 Room 表读取领域状态
 *
 * Phase B 完成：业务层通过此提供者读取领域状态，走细粒度 DAO 路径。
 * 每个领域状态从独立 DB 表读取，避免反序列化完整 GameData。
 *
 * 通过 flatMapLatest 在 gameData 变化时自动切换到对应 slot 的 DAO 订阅，
 * 实体表有数据时走 DAO 路径，无数据时 fallback 到 gameData 提取。
 */
@Singleton
class DomainStateProvider @Inject constructor(
    private val stateStore: GameStateStore,
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val database: GameDatabase
) {
    private val scope get() = applicationScopeProvider.scope

    val diplomacyState: StateFlow<DiplomacyDomainState> = stateStore.gameData
        .flatMapLatest { gd ->
            database.diplomacyStateDao().observeBySlot(gd.slotId)
                .map { entity -> entity?.toDomainState() ?: gd.extractDiplomacyState() }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), DiplomacyDomainState())

    val productionState: StateFlow<ProductionDomainState> = stateStore.gameData
        .flatMapLatest { gd ->
            database.productionStateDao().observeBySlot(gd.slotId)
                .map { entity -> entity?.toDomainState() ?: gd.extractProductionState() }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ProductionDomainState())

    val patrolState: StateFlow<PatrolDomainState> = stateStore.gameData
        .flatMapLatest { gd ->
            database.patrolStateDao().observeBySlot(gd.slotId)
                .map { entity -> entity?.toDomainState() ?: gd.extractPatrolState() }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), PatrolDomainState())

    val worldMapState: StateFlow<WorldMapDomainState> = stateStore.gameData
        .flatMapLatest { gd ->
            database.worldMapStateDao().observeBySlot(gd.slotId)
                .map { entity -> entity?.toDomainState() ?: gd.extractWorldMapState() }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), WorldMapDomainState())

    val sectPolicyState: StateFlow<SectPolicyDomainState> = stateStore.gameData
        .flatMapLatest { gd ->
            database.sectPolicyStateDao().observeBySlot(gd.slotId)
                .map { entity -> entity?.toDomainState() ?: gd.extractSectPolicyState() }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), SectPolicyDomainState())
}

// ── Entity → DomainState 转换扩展 ──

private fun DiplomacyState.toDomainState() = DiplomacyDomainState(
    sectRelations = sectRelations,
    alliances = alliances,
    playerAllianceSlots = playerAllianceSlots,
    playerProtectionEnabled = playerProtectionEnabled,
    playerProtectionStartYear = playerProtectionStartYear,
    playerHasAttackedAI = playerHasAttackedAI,
    sectDetails = sectDetails,
    exploredSects = exploredSects,
    scoutInfo = scoutInfo
)

private fun ProductionState.toDomainState() = ProductionDomainState(
    spiritFieldPlants = spiritFieldPlants,
    unlockedRecipes = unlockedRecipes,
    unlockedManuals = unlockedManuals,
    manualProficiencies = manualProficiencies
)

private fun PatrolStateEntity.toDomainState() = PatrolDomainState(
    patrolSlots = patrolSlots,
    patrolConfig = patrolConfig,
    patrolConfigs = patrolConfigs,
    patrolBattleResultPopup = patrolBattleResultPopup
)

private fun WorldMapStateEntity.toDomainState() = WorldMapDomainState(
    worldMapSects = worldMapSects,
    aiSectDisciples = aiSectDisciples,
    cultivatorCaves = cultivatorCaves,
    caveExplorationTeams = caveExplorationTeams,
    aiCaveTeams = aiCaveTeams,
    worldLevels = worldLevels
)

private fun SectPolicyState.toDomainState() = SectPolicyDomainState(
    sectPolicies = sectPolicies,
    autoRecruitSpiritRootFilter = autoRecruitSpiritRootFilter,
    daoCompanionBannedRootCounts = daoCompanionBannedRootCounts,
    daoCompanionConsentRequired = daoCompanionConsentRequired,
    breakthroughAutoPillFocused = breakthroughAutoPillFocused,
    breakthroughAutoPillRootCounts = breakthroughAutoPillRootCounts,
    autoEquipFromWarehouseFocused = autoEquipFromWarehouseFocused,
    autoEquipFromWarehouseRootCounts = autoEquipFromWarehouseRootCounts,
    autoLearnFromWarehouseFocused = autoLearnFromWarehouseFocused,
    autoLearnFromWarehouseRootCounts = autoLearnFromWarehouseRootCounts,
    yearlySalary = yearlySalary,
    yearlySalaryEnabled = yearlySalaryEnabled,
    autoSaveIntervalMonths = autoSaveIntervalMonths
)
