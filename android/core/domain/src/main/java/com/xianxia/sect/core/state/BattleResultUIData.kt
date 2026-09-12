package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleRewardItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BattleResultUIData(
    @ProtoNumber(1) val battleLogId: String,
    @ProtoNumber(2) val victory: Boolean,
    @ProtoNumber(3) val teamMembers: List<BattleLogMember>,
    @ProtoNumber(4) val rewards: List<BattleRewardItem>,
    /** 防守失败时被妖兽掠夺的物品列表 */
    @ProtoNumber(5) val lootedItems: List<BattleRewardItem> = emptyList(),
    /** 是否为妖兽防守战斗 */
    @ProtoNumber(6) val isBeastDefense: Boolean = false
)
