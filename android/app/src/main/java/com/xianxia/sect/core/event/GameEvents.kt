package com.xianxia.sect.core.event

import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

interface DomainEvent {
    val timestamp: Long get() = System.currentTimeMillis()
    val type: String
}

data class CultivationEvent(
    val discipleId: String,
    val discipleName: String,
    val oldRealm: Int,
    val newRealm: Int,
    val cultivation: Long,
    override val type: String = "cultivation"
) : DomainEvent

data class BreakthroughEvent(
    val discipleId: String,
    val discipleName: String,
    val realm: Int,
    val success: Boolean,
    val newLayer: Int,
    override val type: String = "breakthrough"
) : DomainEvent

data class CombatEvent(
    val attackerId: String,
    val defenderId: String,
    val damage: Int,
    val isCritical: Boolean,
    val skillName: String?,
    override val type: String = "combat"
) : DomainEvent

data class DeathEvent(
    val entityId: String,
    val entityName: String,
    val cause: String,
    override val type: String = "death"
) : DomainEvent

data class ItemEvent(
    val itemId: String,
    val itemName: String,
    val action: String,
    val quantity: Int,
    val targetId: String?,
    override val type: String = "item"
) : DomainEvent

data class SectEvent(
    val sectId: String,
    val sectName: String,
    val action: String,
    val details: Map<String, Any> = emptyMap(),
    override val type: String = "sect"
) : DomainEvent

data class TimeEvent(
    val year: Int,
    val month: Int,
    val day: Int,
    val action: String,
    override val type: String = "time"
) : DomainEvent

data class SaveEvent(
    val slot: Int,
    val success: Boolean,
    val message: String,
    override val type: String = "save"
) : DomainEvent

data class ErrorEvent(
    val errorCode: String,
    val message: String,
    val details: Map<String, Any> = emptyMap(),
    override val type: String = "error"
) : DomainEvent

data class NotificationEvent(
    val title: String,
    val message: String,
    val severity: NotificationSeverity = NotificationSeverity.INFO,
    override val type: String = "notification"
) : DomainEvent

enum class NotificationSeverity {
    INFO, WARNING, ERROR, SUCCESS
}

data class DiscipleUpdatedEvent(
    val discipleId: String,
    val changes: Map<String, Any?>,
    override val type: String = "disciple_updated"
) : DomainEvent

data class CultivationProgressEvent(
    val discipleId: String,
    val progress: Double,
    override val type: String = "cultivation_progress"
) : DomainEvent

data class ItemCraftedEvent(
    val itemId: String,
    val itemType: String,
    override val type: String = "item_crafted"
) : DomainEvent

data class BattleCompletedEvent(
    val battleId: String,
    val result: BattleResultInfo,
    override val type: String = "battle_completed"
) : DomainEvent

data class BattleResultInfo(
    val victory: Boolean,
    val playerLosses: Int = 0,
    val enemyLosses: Int = 0,
    val rewards: List<RewardItemInfo> = emptyList()
)

data class RewardItemInfo(
    val itemId: String,
    val itemName: String,
    val quantity: Int,
    val rarity: Int
)

data class BattleStartedEvent(
    val attackerId: String,
    val defenderId: String,
    val attackerName: String = "",
    val defenderName: String = "",
    override val type: String = "battle_started"
) : DomainEvent

data class BuildingCompletedEvent(
    val buildingId: String,
    val buildingName: String = "",
    val gridX: Int = 0,
    val gridY: Int = 0,
    override val type: String = "building_completed"
) : DomainEvent

data class SpiritStonesChangedEvent(
    val delta: Long,
    val newTotal: Long,
    val reason: String = "",
    override val type: String = "spirit_stones_changed"
) : DomainEvent

data class SectRelationChangedEvent(
    val sectId: String,
    val sectName: String = "",
    val oldFavor: Int = 0,
    val newFavor: Int = 0,
    override val type: String = "sect_relation_changed"
) : DomainEvent

data class DiscipleRecruitedEvent(
    val discipleId: String,
    val discipleName: String = "",
    val realm: Int = 0,
    override val type: String = "disciple_recruited"
) : DomainEvent

data class DiscipleExpelledEvent(
    val discipleId: String,
    val discipleName: String = "",
    val reason: String = "",
    override val type: String = "disciple_expelled"
) : DomainEvent

data class ProductionCompletedEvent(
    val buildingType: String,
    val slotIndex: Int,
    val itemName: String = "",
    val quantity: Int = 0,
    override val type: String = "production_completed"
) : DomainEvent

data class AllianceFormedEvent(
    val sectId: String,
    val sectName: String = "",
    val startYear: Int = 0,
    override val type: String = "alliance_formed"
) : DomainEvent

data class AllianceDissolvedEvent(
    val sectId: String,
    val sectName: String = "",
    val reason: String = "",
    override val type: String = "alliance_dissolved"
) : DomainEvent

data class ExplorationCompletedEvent(
    val teamId: String,
    val success: Boolean,
    val survivorCount: Int = 0,
    override val type: String = "exploration_completed"
) : DomainEvent

interface DomainEventSubscriber {
    fun onEvent(event: DomainEvent)
    val subscribedTypes: Set<String>
}

interface EventBusPort {
    val events: Flow<DomainEvent>
    val latestNotifications: StateFlow<List<NotificationEvent>>
    suspend fun emit(event: DomainEvent)
    fun emitSync(event: DomainEvent): Boolean
    fun emitAll(events: List<DomainEvent>)
    fun subscribe(subscriber: DomainEventSubscriber)
    fun unsubscribe(subscriber: DomainEventSubscriber)
    fun <T : DomainEvent> emitTyped(event: T)
    fun clearNotifications()
    fun dispose()
}

@Singleton
class EventBus @Inject constructor(
    private val applicationScopeProvider: ApplicationScopeProvider
) : EventBusPort {

    private val scope get() = applicationScopeProvider.scope

    private val eventChannel = Channel<DomainEvent>(capacity = 256)
    override val events: Flow<DomainEvent> = eventChannel.receiveAsFlow()
    
    private val _latestNotifications = MutableStateFlow<List<NotificationEvent>>(emptyList())
    override val latestNotifications: StateFlow<List<NotificationEvent>> = _latestNotifications.asStateFlow()
    
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<DomainEventSubscriber>>()
    
    private val maxNotificationHistory = 50
    
    private var isProcessing = false

    private var droppedEventCount = 0L
    private var lastDropLogTime = 0L
    
    init {
        startProcessing()
    }
    
    private fun startProcessing() {
        if (isProcessing) return
        isProcessing = true
        
        scope.launch {
            for (event in eventChannel) {
                if (event is NotificationEvent) {
                    addToNotificationHistory(event)
                }
                notifySubscribers(event)
            }
        }
    }
    
    override suspend fun emit(event: DomainEvent) {
        val result = eventChannel.trySend(event)
        if (!result.isSuccess) {
            droppedEventCount++
            val now = System.currentTimeMillis()
            if (now - lastDropLogTime > 5000) {
                android.util.Log.w("EventBus", "Event dropped (total: $droppedEventCount), type=${event.type}. Consider reducing event frequency.")
                lastDropLogTime = now
            }
        }
    }
    
    override fun emitSync(event: DomainEvent): Boolean {
        val result = eventChannel.trySend(event)
        if (!result.isSuccess) {
            droppedEventCount++
        }
        return result.isSuccess
    }
    
    override fun emitAll(events: List<DomainEvent>) {
        for (event in events) {
            eventChannel.trySend(event)
        }
    }
    
    override fun subscribe(subscriber: DomainEventSubscriber) {
        val types = subscriber.subscribedTypes
        if (types.isEmpty()) return
        types.forEach { type ->
            subscribers.computeIfAbsent(type) { CopyOnWriteArrayList() }.add(subscriber)
        }
    }
    
    override fun unsubscribe(subscriber: DomainEventSubscriber) {
        subscriber.subscribedTypes.forEach { type ->
            subscribers[type]?.remove(subscriber)
        }
    }
    
    override fun <T : DomainEvent> emitTyped(event: T) {
        scope.launch {
            eventChannel.send(event)
        }
    }
    
    override fun clearNotifications() {
        _latestNotifications.value = emptyList()
    }
    
    private fun addToNotificationHistory(event: NotificationEvent) {
        val current = _latestNotifications.value.toMutableList()
        current.add(0, event)
        if (current.size > maxNotificationHistory) {
            _latestNotifications.value = current.take(maxNotificationHistory)
        } else {
            _latestNotifications.value = current
        }
    }
    
    private fun notifySubscribers(event: DomainEvent) {
        subscribers[event.type]?.forEach { subscriber ->
            scope.launch {
                try {
                    subscriber.onEvent(event)
                } catch (e: Exception) {
                    android.util.Log.e("EventBus", "Error notifying subscriber for event ${event.type}", e)
                }
            }
        }
    }
    
    override fun dispose() {
        eventChannel.close()
        isProcessing = false
    }
}

class DomainEventHistory(private val maxSize: Int = 100) {
    private val history = CopyOnWriteArrayList<DomainEvent>()
    
    fun add(event: DomainEvent) {
        history.add(0, event)
        while (history.size > maxSize) {
            history.removeAt(history.size - 1)
        }
    }
    
    fun getAll(): List<DomainEvent> = history.toList()
    
    fun getByType(type: String): List<DomainEvent> = history.filter { it.type == type }
    
    fun getSince(timestamp: Long): List<DomainEvent> = history.filter { it.timestamp >= timestamp }
    
    fun clear() {
        history.clear()
    }
    
    fun size(): Int = history.size
}
