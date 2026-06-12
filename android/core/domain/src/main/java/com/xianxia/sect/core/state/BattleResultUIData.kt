package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleRewardItem
import kotlinx.serialization.Serializable

@Serializable
data class BattleResultUIData(
    val battleLogId: String,
    val victory: Boolean,
    val teamMembers: List<BattleLogMember>,
    val rewards: List<BattleRewardItem>
)
