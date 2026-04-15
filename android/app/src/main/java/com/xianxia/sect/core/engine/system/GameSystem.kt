package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.state.MutableGameState

interface GameSystem {
    val systemName: String

    fun initialize() {}
    fun release() {}
    suspend fun clear() {}

    suspend fun onSecondTick(state: MutableGameState) {}
    suspend fun onDayTick(state: MutableGameState) {}
    suspend fun onMonthTick(state: MutableGameState) {}
    suspend fun onYearTick(state: MutableGameState) {}
}
