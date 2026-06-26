package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.state.MutableGameState

interface GameSystem {
    val systemName: String
    /** 所属关注域，默认 BACKGROUND（最慢频率） */
    val focusDomain: FocusDomain get() = FocusDomain.BACKGROUND
    /** 该系统在哪一旬结算（1=上旬, 2=中旬, 3=下旬, 0=每旬都结算） */
    val settlementPhase: Int get() = 0

    fun initialize() {}
    fun release() {}
    suspend fun clear() {}
    suspend fun clearForSlot(slotId: Int) { clear() }

    /**
     * 旬级 tick。焦点域每 100ms 执行，非焦点域每 30s 执行。
     * @param phasesToSettle 需结算的旬数：焦点域=1，非焦点域批量触发时=上次执行后跳过的旬数
     */
    suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int = 1) {}
    suspend fun onMonthTick(state: MutableGameState) {}
    suspend fun onYearTick(state: MutableGameState) {}
}
