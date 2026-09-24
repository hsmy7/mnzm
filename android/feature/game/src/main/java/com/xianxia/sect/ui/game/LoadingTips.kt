package com.xianxia.sect.ui.game

import com.xianxia.sect.core.util.PresentationRandom

/**
 * 加载界面游戏玩法提示数据源。
 *
 * 每2秒轮换显示一条随机提示。
 * 所有提示基于代码中实际存在的游戏机制。
 */
object LoadingTips {
    private val tips = listOf(
        // ── 长老系统 ──
        "任命合适的长老可提升对应建筑的产出效率",
        "副宗主的智力属性会影响所有政策的加成效果",

        // ── 战斗/生存 ──
        "弟子伴侣去世后会进入哀伤状态，哀伤期结束后恢复正常",
        "战斗受伤的弟子需要时间恢复生命值和法力值",
    )

    /**
     * 返回一条随机提示文本。
     *
     * 随机源**必传**（[PresentationRandom]，ADR R3 表现类流）：提示轮播是纯表现，
     * 不得污染决策分区；原先 `tips.random()` 走 `kotlin.random.Random.Default`
     *（进程启动随机、不入档）——属未受治理的第二类入口，R1/R5 违规。
     *
     * @param random 表现随机源（由调用方注入，测试可传固定种子实例）
     */
    fun randomTip(random: PresentationRandom): String = random.pick(tips)
}
