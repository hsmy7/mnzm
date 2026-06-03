package com.xianxia.sect.core.model.domain

import com.xianxia.sect.core.model.*

/**
 * 巡视领域状态 — 从 GameData 中提取的巡视相关字段聚合。
 *
 * 纯领域模型，仅用于业务层传递。序列化由 GameData 负责，本类不标 @Serializable。
 */
data class PatrolDomainState(
    val patrolSlots: List<PatrolSlot> = emptyList(),
    val patrolConfig: PatrolConfig = PatrolConfig(),
    val patrolConfigs: List<PatrolConfig> = emptyList(),
    val patrolBattleResultPopup: Boolean = false
)

/** 从 GameData 提取巡视领域状态 */
fun GameData.extractPatrolState(): PatrolDomainState = PatrolDomainState(
    patrolSlots = patrolSlots,
    patrolConfig = patrolConfig,
    patrolConfigs = patrolConfigs,
    patrolBattleResultPopup = patrolBattleResultPopup
)

/** 将巡视领域状态合并回 GameData */
fun GameData.mergePatrolState(state: PatrolDomainState): GameData = copy(
    patrolSlots = state.patrolSlots,
    patrolConfig = state.patrolConfig,
    patrolConfigs = state.patrolConfigs,
    patrolBattleResultPopup = state.patrolBattleResultPopup
)
