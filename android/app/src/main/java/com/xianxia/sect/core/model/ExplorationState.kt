package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable

/**
 * 探索与弟子管理状态
 *
 * 包含弟子招募列表、修士洞府、洞府探索队伍（玩家/AI）、
 * 解锁内容（副本/配方/功法）、功法熟练度等探索相关数据。
 * 从 GameData 上帝对象中拆分出来，降低 copy() 时的复制开销。
 */
@Serializable
data class ExplorationState(
    val recruitList: List<Disciple> = emptyList(),
    val lastRecruitYear: Int = 0,
    val cultivatorCaves: List<CultivatorCave> = emptyList(),
    val caveExplorationTeams: List<CaveExplorationTeam> = emptyList(),
    val aiCaveTeams: List<AICaveTeam> = emptyList(),
    val unlockedDungeons: List<String> = emptyList(),
    val unlockedRecipes: List<String> = emptyList(),
    val unlockedManuals: List<String> = emptyList(),
    val manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    val pendingCompetitionResults: List<CompetitionRankResult> = emptyList(),
    val lastCompetitionYear: Int = 0
)
