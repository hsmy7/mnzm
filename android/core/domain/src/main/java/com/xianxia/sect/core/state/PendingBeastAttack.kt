package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.WorldLevel

/**
 * 待处理的妖兽攻击预警。
 * 结算期间检测到妖兽接近宗门时暂存，结算完成后由 UI 弹窗展示。
 */
data class PendingBeastAttack(
    val beastLevel: WorldLevel,
    val targetSectId: String,
    val targetSectName: String,
    val distance: Float
)

/**
 * 玩家对妖兽攻击预警的选择。
 */
sealed interface BeastAttackChoice {
    /** 上交灵石，妖兽取消进攻 */
    data object PayTribute : BeastAttackChoice
    /** 关闭预警，妖兽发动进攻 */
    data object Fight : BeastAttackChoice
}
