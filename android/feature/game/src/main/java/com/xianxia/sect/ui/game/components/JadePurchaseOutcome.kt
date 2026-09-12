package com.xianxia.sect.ui.game.components

/**
 * 玉符购买通用结果：两入口（突破率/商人刷新）各自映射为统一三态，弹窗层只认此类型。
 */
internal sealed interface JadePurchaseOutcome {
    data object Success : JadePurchaseOutcome
    data object Insufficient : JadePurchaseOutcome
    data class Failed(val message: String) : JadePurchaseOutcome
}
