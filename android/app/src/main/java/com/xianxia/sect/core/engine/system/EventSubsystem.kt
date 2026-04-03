package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.GameEvent
import com.xianxia.sect.core.model.EventType
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventSubsystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "EventSubsystem"
        const val SYSTEM_NAME = "EventSubsystem"
        private const val MAX_EVENTS = 100
        private const val MAX_BATTLE_LOGS = 50
    }
    
    private val _events = MutableStateFlow<List<GameEvent>>(emptyList())
    val events: StateFlow<List<GameEvent>> = _events.asStateFlow()
    internal val mutableEvents: MutableStateFlow<List<GameEvent>> get() = _events
    
    private val _battleLogs = MutableStateFlow<List<BattleLog>>(emptyList())
    val battleLogs: StateFlow<List<BattleLog>> = _battleLogs.asStateFlow()
    internal val mutableBattleLogs: MutableStateFlow<List<BattleLog>> get() = _battleLogs
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "EventSubsystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "EventSubsystem released")
    }
    
    override suspend fun clear() {
        StateFlowListUtils.clearList(_events)
        StateFlowListUtils.clearList(_battleLogs)
    }
    
    fun loadEvents(events: List<GameEvent>) = StateFlowListUtils.setList(_events, events)
    
    fun loadBattleLogs(logs: List<BattleLog>) = StateFlowListUtils.setList(_battleLogs, logs)
    
    fun getEvents(): List<GameEvent> = _events.value
    
    fun getBattleLogs(): List<BattleLog> = _battleLogs.value
    
    fun addEvent(message: String, type: EventType = EventType.INFO) {
        val event = GameEvent(
            message = message,
            type = type,
            timestamp = System.currentTimeMillis()
        )
        _events.value = (_events.value + event).takeLast(MAX_EVENTS)
    }
    
    fun addEvent(event: GameEvent) {
        _events.value = (_events.value + event).takeLast(MAX_EVENTS)
    }
    
    fun clearEvents() = StateFlowListUtils.clearList(_events)
    
    fun addBattleLog(log: BattleLog) {
        _battleLogs.value = (_battleLogs.value + log).takeLast(MAX_BATTLE_LOGS)
    }
    
    fun clearBattleLogs() = StateFlowListUtils.clearList(_battleLogs)
    
    fun getRecentEvents(count: Int = 10): List<GameEvent> = 
        _events.value.takeLast(count)
    
    fun getRecentBattleLogs(count: Int = 10): List<BattleLog> = 
        _battleLogs.value.takeLast(count)
}
