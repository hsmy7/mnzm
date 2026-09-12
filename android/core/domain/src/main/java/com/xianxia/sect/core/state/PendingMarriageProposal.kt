package com.xianxia.sect.core.state

/**
 * 待处理的弟子婚姻提议。
 *
 * 月度结算期间 [com.xianxia.sect.core.engine.system.PartnerSystem]
 * 检测到匹配但玩家开启了"结婚需同意"时，暂存到此列表。
 * 结算完成后由 UI 弹窗逐对展示，玩家选择同意或拒绝。
 *
 * 运行时状态，不持久化（与 [PendingBeastAttack] 一致）。
 */
data class PendingMarriageProposal(
    val maleId: String,
    val maleName: String,
    val femaleId: String,
    val femaleName: String
)
