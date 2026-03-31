package com.xianxia.sect.core.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

interface DomainEventSubscriber {
    fun onEvent(event: DomainEvent)
    val subscribedTypes: Set<String>
}

@Singleton
class EventBus @Inject constructor() {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val eventChannel = Channel<DomainEvent>(capacity = Channel.UNLIMITED)
    val events: Flow<DomainEvent> = eventChannel.receiveAsFlow()
    
    private val _latestNotifications = MutableStateFlow<List<NotificationEvent>>(emptyList())
    val latestNotifications: StateFlow<List<NotificationEvent>> = _latestNotifications.asStateFlow()
    
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<DomainEventSubscriber>>()
    
    private val maxNotificationHistory = 50
    
    private var isProcessing = false
    
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
    
    suspend fun emit(event: DomainEvent) {
        eventChannel.send(event)
    }
    
    fun emitSync(event: DomainEvent): Boolean {
        return eventChannel.trySend(event).isSuccess
    }
    
    fun emitAll(events: List<DomainEvent>) {
        events.forEach { event ->
            scope.launch {
                eventChannel.send(event)
            }
        }
    }
    
    fun subscribe(subscriber: DomainEventSubscriber) {
        subscriber.subscribedTypes.forEach { type ->
            subscribers.computeIfAbsent(type) { CopyOnWriteArrayList() }.add(subscriber)
        }
    }
    
    fun unsubscribe(subscriber: DomainEventSubscriber) {
        subscriber.subscribedTypes.forEach { type ->
            subscribers[type]?.remove(subscriber)
        }
    }
    
    fun <T : DomainEvent> emitTyped(event: T) {
        scope.launch {
            eventChannel.send(event)
        }
    }
    
    fun clearNotifications() {
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
    
    fun dispose() {
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
