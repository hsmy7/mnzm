package com.xianxia.sect.core.engine.subsystem

import android.util.Log
import com.xianxia.sect.core.event.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.UnifiedGameStateManager
import com.xianxia.sect.core.state.GameState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimeSubsystem @Inject constructor(
    private val eventBus: EventBus,
    private val stateManager: UnifiedGameStateManager
) : BaseGameSubsystem() {
    
    companion object {
        private const val TAG = "TimeSubsystem"
    }
    
    override val systemName: String = "Time"
    override val priority: Int = 0
    
    override suspend fun processTick(deltaTime: Float, state: GameState): GameState {
        if (!isEnabled()) return state
        
        return state
    }
}
