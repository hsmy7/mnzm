package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable

/**
 * 宗门组织架构状态
 *
 * 包含长老槽位、结盟关系、战斗队伍（玩家/AI）、宗门政策、
 * 任务阁系统、兑换码使用记录等组织管理数据。
 * 从 GameData 上帝对象中拆分出来，降低 copy() 时的复制开销。
 */
@Serializable
data class SectOrganizationState(
    val elderSlots: ElderSlots = ElderSlots(),
    val alliances: List<Alliance> = emptyList(),
    val battleTeam: BattleTeam? = null,
    val aiBattleTeams: List<AIBattleTeam> = emptyList(),
    val sectPolicies: SectPolicies = SectPolicies(),
    val activeMissions: List<ActiveMission> = emptyList(),
    val availableMissions: List<Mission> = emptyList(),
    val usedRedeemCodes: List<String> = emptyList()
)
