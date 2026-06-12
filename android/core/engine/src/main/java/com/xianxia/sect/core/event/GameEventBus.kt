package com.xianxia.sect.core.engine.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 游戏内部事件总线 — 替代 System 间直接方法调用
 *
 * 用法：
 *   // 发射事件
 *   gameEventBus.emit(BattleCompletedEvent(sectorId))
 *
 *   // 订阅事件
 *   gameEventBus.events.filterIsInstance<BattleCompletedEvent>()
 *       .collect { event -> handleBattleResult(event) }
 */
@Singleton
class GameEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    suspend fun emit(event: GameEvent) {
        _events.emit(event)
    }
}

/** 所有游戏事件的基类 */
interface GameEvent

// === 具体事件定义 ===

/** 战斗结束事件 */
data class BattleCompletedEvent(
    val sectorId: String,
    val winnerSectId: String,
    val loserSectId: String
) : GameEvent

/** 建筑放置事件 */
data class BuildingPlacedEvent(
    val buildingId: String,
    val instanceId: String
) : GameEvent

/** 建筑拆除事件 */
data class BuildingRemovedEvent(
    val instanceId: String
) : GameEvent

/** 弟子死亡事件 */
data class DiscipleDeathEvent(
    val discipleId: String,
    val cause: String
) : GameEvent

/** 月度结算完成事件 */
object SettlementCompletedEvent : GameEvent

/** 存档保存完成事件 */
data class SaveCompletedEvent(
    val slotId: Int,
    val timeMs: Long
) : GameEvent
