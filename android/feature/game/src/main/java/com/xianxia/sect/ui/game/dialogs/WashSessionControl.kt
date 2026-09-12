package com.xianxia.sect.ui.game.dialogs

/**
 * 洗炼会话控制参数组（Composable 参数上限 6 个，聚合防连点与保底回传通道）。
 *
 * [washing] 由弹窗主函数 remember 持有：一方面供弹窗 onDismissRequest 在引擎操作进行中
 * 拦截关闭（防"玉符已扣、结果丢失"），另一方面下传内容区用于按钮 enabled/防连点。
 *
 * 洗炼灵根与洗炼天赋/体质/词条共用（洗炼类玩法统一会话控制）。
 */
internal data class WashSessionControl(
    val initialPityCount: Int,
    val onPityCountChanged: (Int) -> Unit,
    val washing: Boolean,
    val onWashingChange: (Boolean) -> Unit
)
