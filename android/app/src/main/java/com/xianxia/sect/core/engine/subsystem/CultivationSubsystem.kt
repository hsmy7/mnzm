package com.xianxia.sect.core.engine.subsystem

import com.xianxia.sect.core.event.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.UnifiedGameStateManager
import com.xianxia.sect.core.state.GameState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CultivationSubsystem @Inject constructor(
    private val eventBus: EventBus,
    private val stateManager: UnifiedGameStateManager
) : BaseGameSubsystem() {
    
    override val systemName: String = "Cultivation"
    override val priority: Int = 10
    
    override suspend fun processTick(deltaTime: Float, state: GameState): GameState {
        if (!isEnabled()) return state
        
        return state
    }
}
