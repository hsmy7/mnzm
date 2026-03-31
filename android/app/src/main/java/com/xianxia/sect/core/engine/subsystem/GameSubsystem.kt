package com.xianxia.sect.core.engine.subsystem

import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.state.GameState
import com.xianxia.sect.core.state.UnifiedGameState
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.flow.SharedFlow

interface GameSubsystem {
    val systemName: String
    val priority: Int get() = 0
    
    fun initialize()
    fun dispose()
    suspend fun processTick(deltaTime: Float, state: GameState): GameState
    
    fun isEnabled(): Boolean = true
    fun enable()
    fun disable()
}

abstract class BaseGameSubsystem : GameSubsystem {
    private var enabled = true
    
    override fun isEnabled(): Boolean = enabled
    
    override fun enable() {
        enabled = true
    }
    
    override fun disable() {
        enabled = false
    }
    
    override fun initialize() {}
    override fun dispose() {}
}

abstract class EventDrivenSubsystem(
    protected val eventBus: EventBus
) : BaseGameSubsystem(), com.xianxia.sect.core.event.DomainEventSubscriber {
    
    protected val subscribedEventTypes: MutableSet<String> = mutableSetOf()
    private var isSubscribedToBus = false
    
    override val subscribedTypes: Set<String> 
        get() = subscribedEventTypes.toSet()
    
    protected fun subscribeToEvent(eventType: String) {
        subscribedEventTypes.add(eventType)
        if (!isSubscribedToBus) {
            eventBus.subscribe(this)
            isSubscribedToBus = true
        }
    }
    
    protected fun unsubscribeFromEvent(eventType: String) {
        subscribedEventTypes.remove(eventType)
    }
    
    override fun onEvent(event: DomainEvent) {
        if (event.type in subscribedEventTypes) {
            handleEvent(event)
        }
    }
    
    protected open fun handleEvent(event: DomainEvent) {
    }
    
    override fun dispose() {
        eventBus.unsubscribe(this)
        isSubscribedToBus = false
        super.dispose()
    }
}

interface StateChangeHandler {
    fun onStateChangeRequested(change: StateChangeRequest)
}

sealed class StateChangeRequest {
    abstract val timestamp: Long
    abstract val source: String
    
    data class UpdateGameDataField(
        val fieldUpdates: Map<String, Any?>,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class UpdateSectName(
        val newName: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class UpdateSectLevel(
        val newLevel: Int,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class UpdateSpiritStones(
        val delta: Long,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class UpdateDiscipleField(
        val discipleId: String,
        val fieldUpdates: Map<String, Any?>,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class UpdateDiscipleCultivation(
        val discipleId: String,
        val cultivationDelta: Double,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class UpdateDiscipleRealm(
        val discipleId: String,
        val newRealm: Int,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class UpdateDiscipleStatus(
        val discipleId: String,
        val newStatus: DiscipleStatus,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class AddDisciple(
        val disciple: Disciple,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class RemoveDisciple(
        val discipleId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class UpdateBuildingSlot(
        val slotIndex: Int,
        val slot: BuildingSlot,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class UpdateAlchemySlot(
        val slotIndex: Int,
        val slot: AlchemySlot,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class AddEquipment(
        val equipment: Equipment,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class AddPill(
        val pill: Pill,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
    
    data class RemoveItem(
        val itemId: String,
        val itemType: String,
        val quantity: Int = 1,
        override val timestamp: Long = System.currentTimeMillis(),
        override val source: String
    ) : StateChangeRequest()
}
