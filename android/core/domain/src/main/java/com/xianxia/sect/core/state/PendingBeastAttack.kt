package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.WorldLevel

/**
 * 待处理的妖兽攻击预警（排期）。
 * 结算期间检测到妖兽接近宗门时暂存，UI 弹窗展示纯通知；
 * 下月结算由探索系统自动执行防守战（弹窗未关闭时自动消失）。
 */
data class PendingBeastAttack(
    val beastLevel: WorldLevel,
    val targetSectId: String,
    val targetSectName: String,
    val distance: Float
)
