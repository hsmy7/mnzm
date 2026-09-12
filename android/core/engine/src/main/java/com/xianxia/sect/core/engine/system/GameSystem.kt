package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.state.MutableGameState

/**
 * 游戏系统接口 — 与惰性结算引擎兼容的简化生命周期。
 *
 * 生命周期入口：initialize / onMonthlyEvent / onYearlyEvent / release。
 */
interface GameSystem {
    val systemName: String

    fun initialize() {}
    fun release() {}
    fun clear() {}
    fun clearForSlot(slotId: Int) { clear() }

    /**
     * 月变事件（定时事件型系统使用）。
     * 生产类系统由 UI 打开时惰性结算触发，不在此回调。
     */
    fun onMonthlyEvent(state: MutableGameState) {}

    /**
     * 年变事件（定时事件型系统使用）。
     * 老化/招募/盟约等。
     */
    fun onYearlyEvent(state: MutableGameState) {}
}
