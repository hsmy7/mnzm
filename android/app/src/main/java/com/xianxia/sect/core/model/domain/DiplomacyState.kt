package com.xianxia.sect.core.model.domain

import com.xianxia.sect.core.model.*

/**
 * 外交领域状态 — 从 GameData 中提取的外交相关字段聚合。
 *
 * 纯领域模型，仅用于业务层传递。序列化由 GameData 负责，本类不标 @Serializable，
 * 以允许使用 Map 类型（与 GameData 字段类型一致），避免 ProtoBuf 兼容问题。
 */
data class DiplomacyDomainState(
    val sectRelations: List<SectRelation> = emptyList(),
    val alliances: List<Alliance> = emptyList(),
    val playerAllianceSlots: Int = 3,
    val playerProtectionEnabled: Boolean = true,
    val playerProtectionStartYear: Int = 1,
    val playerHasAttackedAI: Boolean = false,
    val sectDetails: Map<String, SectDetail> = emptyMap(),
    val exploredSects: Map<String, ExploredSectInfo> = emptyMap(),
    val scoutInfo: Map<String, SectScoutInfo> = emptyMap()
)

/** 从 GameData 提取外交领域状态 */
fun GameData.extractDiplomacyState(): DiplomacyDomainState = DiplomacyDomainState(
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

/** 将外交领域状态合并回 GameData */
fun GameData.mergeDiplomacyState(state: DiplomacyDomainState): GameData = copy(
    sectRelations = state.sectRelations,
    alliances = state.alliances,
    playerAllianceSlots = state.playerAllianceSlots,
    playerProtectionEnabled = state.playerProtectionEnabled,
    playerProtectionStartYear = state.playerProtectionStartYear,
    playerHasAttackedAI = state.playerHasAttackedAI,
    sectDetails = state.sectDetails,
    exploredSects = state.exploredSects,
    scoutInfo = state.scoutInfo
)
