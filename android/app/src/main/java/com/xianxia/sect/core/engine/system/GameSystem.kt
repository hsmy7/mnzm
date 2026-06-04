package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.state.MutableGameState

interface GameSystem {
    val systemName: String
    /** 所属关注域，默认 BACKGROUND（最慢频率） */
    val focusDomain: FocusDomain get() = FocusDomain.BACKGROUND

    fun initialize() {}
    fun release() {}
    suspend fun clear() {}
    suspend fun clearForSlot(slotId: Int) { clear() }

    suspend fun onPhaseTick(state: MutableGameState) {}
    suspend fun onMonthTick(state: MutableGameState) {}
    suspend fun onYearTick(state: MutableGameState) {}
}
