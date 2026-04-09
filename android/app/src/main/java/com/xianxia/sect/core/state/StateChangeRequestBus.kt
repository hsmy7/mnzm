@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.state

import android.util.Log
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.event.ErrorEvent
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

class StateChangeException(message: String) : Exception(message)

@Singleton
class StateChangeRequestBus @Inject constructor(
    private val unifiedStateManager: UnifiedGameStateManager,
    private val eventBus: EventBus
) {
    companion object {
        private const val TAG = "StateChangeRequestBus"

        /** 请求通道缓冲区容量（防止内存无限增长） */
        private const val REQUEST_BUFFER_CAPACITY = 64

        /** 错误通道缓冲区容量 */
        private const val ERROR_BUFFER_CAPACITY = 32

        /** 重放窗口时间（毫秒），用于合并同类请求 */
        private const val REQUEST_DEDUP_WINDOW_MS = 100L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 有界 SharedFlow 替代无界 Channel
     *
     * 配置说明：
     * - extraBufferCapacity = 64：缓冲区最多保存 64 个未消费请求
     * - onBufferOverflow = DROP_OLDEST：缓冲区满时丢弃最旧的请求，防止内存溢出
     * - replay = 0：新订阅者只接收订阅后的新事件，不重放历史事件
     *
     * 相比 UNLIMITED Channel 的优势：
     * 1. 内存有界：高频事件时不会导致 OOM
     * 2. 支持多订阅者：多个组件可同时监听状态变更
     * 3. 自动背压：生产者速度快于消费者时自动丢弃旧数据
     */
    private val _requestFlow = MutableSharedFlow<StateChangeRequest>(
        replay = 0,
        extraBufferCapacity = REQUEST_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 对外暴露的只读 Flow */
    val requests: SharedFlow<StateChangeRequest> = _requestFlow.asSharedFlow()

    /**
     * 错误事件 SharedFlow（有界）
     *
     * 使用 DROP_OLDEST 策略：错误过多时保留最新的错误信息
     */
    private val _errorFlow = MutableSharedFlow<Throwable>(
        replay = 1, // 保留最近一个错误供新订阅者获取
        extraBufferCapacity = ERROR_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 对外暴露的错误流 */
    val processingErrors: SharedFlow<Throwable> = _errorFlow.asSharedFlow()

    /**
     * 弱引用订阅者列表（防止 Activity/Fragment 泄漏）
     *
     * 使用 WeakReference 包装订阅者的回调引用，
     * 当订阅者被 GC 回收时自动从列表中移除。
     */
    private val subscribers = CopyOnWriteArrayList<WeakReference<StateChangeSubscriber>>()

    /**
     * 请求去重缓存（防止短时间内重复提交相同请求）
     *
     * key: 请求的唯一标识（基于类型+关键参数生成）
     * value: 上次提交时间戳
     */
    private val requestDedupCache = ConcurrentHashMap<String, Long>()

    private var isProcessing = false

    init {
        startProcessing()
    }
    
    private fun startProcessing() {
        if (isProcessing) return
        isProcessing = true

        scope.launch {
            // 使用 SharedFlow 的 collect 替代 Channel 的 for 循环
            _requestFlow.collect { request ->
                try {
                    // 通知所有活跃的订阅者
                    notifySubscribers(request)

                    processRequestSuspend(request)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing request: $request", e)
                    _errorFlow.tryEmit(e)
                    eventBus.emitSync(ErrorEvent(
                        errorCode = "STATE_CHANGE_FAILED",
                        message = e.message ?: "Unknown error processing state change"
                    ))
                }
            }
        }
    }

    /**
     * 提交状态变更请求（挂起函数）
     *
     * 使用 emit 提交请求，如果缓冲区满会挂起直到有空间。
     * 适用于对可靠性要求高的场景。
     *
     * @param request 状态变更请求
     */
    suspend fun submit(request: StateChangeRequest) {
        // 检查是否为重复请求
        if (isDuplicateRequest(request)) {
            Log.d(TAG, "Dropping duplicate request: ${getRequestKey(request)}")
            return
        }

        _requestFlow.emit(request)
        recordRequest(request)
    }

    /**
     * 同步提交状态变更请求（非挂起）
     *
     * 使用 tryEmit 尝试提交，不会挂起。
     * 如果缓冲区满，会丢弃最旧的请求并返回 true（保证不阻塞调用方）。
     *
     * @param request 状态变更请求
     * @return true 表示请求已接受或缓冲区已自动腾出空间
     */
    fun submitSync(request: StateChangeRequest): Boolean {
        // 检查是否为重复请求
        if (isDuplicateRequest(request)) {
            Log.d(TAG, "Dropping duplicate request: ${getRequestKey(request)}")
            return true // 返回 true 避免上层报错
        }

        val result = _requestFlow.tryEmit(request)
        if (result) {
            recordRequest(request)
        }
        return result
    }

    /**
     * 提交请求或抛出异常
     *
     * 注意：由于 SharedFlow 配置了 DROP_OLDEST 策略，
     * 此方法实际上几乎不会抛出异常（除非 SharedFlow 已关闭）。
     * 保留此方法是为了 API 兼容性。
     *
     * @param request 状态变更请求
     * @throws StateChangeRequestBus.StateChangeException 仅在极少数情况下抛出
     */
    fun submitOrThrow(request: StateChangeRequest) {
        val result = _requestFlow.tryEmit(request)
        if (result) {
            recordRequest(request)
        } else {
            throw StateChangeException("Failed to submit request: shared flow closed or invalid state")
        }
    }

    /**
     * 同步处理请求（跳过消息队列）
     *
     * 直接在调用方协程中执行，不经过 SharedFlow 缓冲。
     * 适用于需要立即得到结果的场景。
     *
     * @param request 状态变更请求
     */
    suspend fun submitAndWait(request: StateChangeRequest) {
        processRequestSuspend(request)
    }
    
    private suspend fun processRequestSuspend(request: StateChangeRequest) {
        when (request) {
            is StateChangeRequest.UpdateGameDataField -> {
                unifiedStateManager.updateState { state ->
                    applyGameDataFieldUpdates(state, request.fieldUpdates)
                }
            }
            is StateChangeRequest.UpdateSectName -> {
                unifiedStateManager.updateGameData { gameData ->
                    gameData.copy(sectName = request.newName)
                }
            }
            is StateChangeRequest.UpdateSectLevel -> {
                // sectLevel field not present in GameData, skip
            }
            is StateChangeRequest.UpdateSpiritStones -> {
                unifiedStateManager.updateGameData { gameData ->
                    gameData.copy(spiritStones = gameData.spiritStones + request.delta)
                }
            }
            is StateChangeRequest.UpdateDiscipleField -> {
                unifiedStateManager.updateDisciple(request.discipleId) { disciple ->
                    applyDiscipleFieldUpdates(disciple, request.fieldUpdates)
                }
            }
            is StateChangeRequest.UpdateDiscipleCultivation -> {
                unifiedStateManager.updateDisciple(request.discipleId) { disciple ->
                    disciple.copy(cultivation = disciple.cultivation + request.cultivationDelta)
                }
            }
            is StateChangeRequest.UpdateDiscipleRealm -> {
                unifiedStateManager.updateDisciple(request.discipleId) { disciple ->
                    disciple.copy(realm = request.newRealm)
                }
            }
            is StateChangeRequest.UpdateDiscipleStatus -> {
                unifiedStateManager.updateDisciple(request.discipleId) { disciple ->
                    disciple.copy(status = request.newStatus)
                }
            }
            is StateChangeRequest.AddDisciple -> {
                unifiedStateManager.addDisciple(request.disciple)
            }
            is StateChangeRequest.RemoveDisciple -> {
                unifiedStateManager.removeDisciple(request.discipleId)
            }
            is StateChangeRequest.UpdateBuildingSlot -> {
                unifiedStateManager.updateState { state ->
                    val slots = state.gameData.forgeSlots.toMutableList()
                    if (request.slotIndex in slots.indices) {
                        slots[request.slotIndex] = request.slot
                        state.copy(gameData = state.gameData.copy(forgeSlots = slots))
                    } else {
                        state
                    }
                }
            }
            is StateChangeRequest.UpdateAlchemySlot -> {
                unifiedStateManager.updateState { state ->
                    val slots = state.gameData.alchemySlots.toMutableList()
                    if (request.slotIndex in slots.indices) {
                        slots[request.slotIndex] = request.slot
                        state.copy(gameData = state.gameData.copy(alchemySlots = slots))
                    } else {
                        state
                    }
                }
            }
            is StateChangeRequest.AddEquipment -> {
                unifiedStateManager.updateState { state ->
                    state.copy(equipment = state.equipment + request.equipment)
                }
            }
            is StateChangeRequest.AddPill -> {
                unifiedStateManager.updateState { state ->
                    state.copy(pills = state.pills + request.pill)
                }
            }
            is StateChangeRequest.RemoveItem -> {
                unifiedStateManager.updateState { state ->
                    removeItemFromState(state, request.itemId, request.itemType, request.quantity)
                }
            }
        }
    }
    
    private fun applyGameDataFieldUpdates(state: UnifiedGameState, updates: Map<String, Any?>): UnifiedGameState {
        var gameData = state.gameData
        updates.forEach { (field, value) ->
            gameData = when (field) {
                "sectName" -> gameData.copy(sectName = value as? String ?: gameData.sectName)
                "spiritStones" -> gameData.copy(spiritStones = value as? Long ?: gameData.spiritStones)
                "gameDay" -> gameData.copy(gameDay = value as? Int ?: gameData.gameDay)
                "gameMonth" -> gameData.copy(gameMonth = value as? Int ?: gameData.gameMonth)
                "gameYear" -> gameData.copy(gameYear = value as? Int ?: gameData.gameYear)
                else -> gameData
            }
        }
        return state.copy(gameData = gameData)
    }
    
    private fun applyDiscipleFieldUpdates(disciple: Disciple, updates: Map<String, Any?>): Disciple {
        var result = disciple
        updates.forEach { (field, value) ->
            result = when (field) {
                "cultivation" -> result.copy(cultivation = value as? Double ?: result.cultivation)
                "realm" -> result.copy(realm = value as? Int ?: result.realm)
                "status" -> result.copy(status = value as? DiscipleStatus ?: result.status)
                "name" -> result.copy(name = value as? String ?: result.name)
                else -> result
            }
        }
        return result
    }
    
    private fun removeItemFromState(state: UnifiedGameState, itemId: String, itemType: String, quantity: Int): UnifiedGameState {
        return when (itemType) {
            "equipment" -> state.copy(equipment = state.equipment.filterNot { it.id == itemId })
            "manual" -> state.copy(manuals = state.manuals.filterNot { it.id == itemId })
            "pill" -> {
                val pill = state.pills.find { it.id == itemId }
                if (pill != null && pill.quantity > quantity) {
                    state.copy(pills = state.pills.map { 
                        if (it.id == itemId) it.copy(quantity = it.quantity - quantity) else it 
                    })
                } else {
                    state.copy(pills = state.pills.filterNot { it.id == itemId })
                }
            }
            "material" -> {
                val material = state.materials.find { it.id == itemId }
                if (material != null && material.quantity > quantity) {
                    state.copy(materials = state.materials.map { 
                        if (it.id == itemId) it.copy(quantity = it.quantity - quantity) else it 
                    })
                } else {
                    state.copy(materials = state.materials.filterNot { it.id == itemId })
                }
            }
            "herb" -> {
                val herb = state.herbs.find { it.id == itemId }
                if (herb != null && herb.quantity > quantity) {
                    state.copy(herbs = state.herbs.map { 
                        if (it.id == itemId) it.copy(quantity = it.quantity - quantity) else it 
                    })
                } else {
                    state.copy(herbs = state.herbs.filterNot { it.id == itemId })
                }
            }
            "seed" -> {
                val seed = state.seeds.find { it.id == itemId }
                if (seed != null && seed.quantity > quantity) {
                    state.copy(seeds = state.seeds.map { 
                        if (it.id == itemId) it.copy(quantity = it.quantity - quantity) else it 
                    })
                } else {
                    state.copy(seeds = state.seeds.filterNot { it.id == itemId })
                }
            }
            else -> state
        }
    }
    
    fun dispose() {
        scope.cancel()
        isProcessing = false
        subscribers.clear()
        requestDedupCache.clear()
        Log.i(TAG, "StateChangeRequestBus disposed successfully")
    }

    // ══════════════════════════════════════════════
    // 订阅者管理（WeakReference 防泄漏）
    // ══════════════════════════════════════════════

    /**
     * 状态变更订阅者接口
     *
     * 实现此接口的组件可以监听所有状态变更请求，
     * 用于 UI 更新、日志记录、分析统计等场景。
     */
    interface StateChangeSubscriber {
        /**
         * 当状态变更请求被处理时回调
         *
         * @param request 正在处理的状态变更请求
         */
        fun onStateChangeRequest(request: StateChangeRequest)
    }

    /**
     * 注册状态变更订阅者（弱引用，不会阻止 GC）
     *
     * 使用 WeakReference 包装订阅者，当订阅者（如 Activity/Fragment）
     * 被销毁时自动从列表中移除，避免内存泄漏。
     *
     * @param subscriber 订阅者实例
     */
    fun subscribe(subscriber: StateChangeSubscriber) {
        cleanupExpiredSubscribers()
        subscribers.add(WeakReference(subscriber))
        Log.d(TAG, "Subscriber added: ${subscriber.javaClass.simpleName}, total: ${subscribers.size}")
    }

    /**
     * 取消订阅
     *
     * @param subscriber 要移除的订阅者实例
     */
    fun unsubscribe(subscriber: StateChangeSubscriber) {
        val iterator = subscribers.iterator()
        while (iterator.hasNext()) {
            val ref = iterator.next()
            if (ref.get() === subscriber || ref.get() == null) {
                iterator.remove()
            }
        }
        Log.d(TAG, "Subscriber removed, total: ${subscribers.size}")
    }

    /**
     * 通知所有活跃的订阅者
     *
     * 自动清理已被 GC 的弱引用。
     *
     * @param request 状态变更请求
     */
    private fun notifySubscribers(request: StateChangeRequest) {
        if (subscribers.isEmpty()) return

        val iterator = subscribers.iterator()
        while (iterator.hasNext()) {
            val ref = iterator.next()
            val subscriber = ref.get()
            if (subscriber != null) {
                try {
                    subscriber.onStateChangeRequest(request)
                } catch (e: Exception) {
                    Log.w(TAG, "Subscriber callback error: ${subscriber.javaClass.simpleName}", e)
                }
            } else {
                // 弱引用已被 GC，从列表中移除
                iterator.remove()
            }
        }
    }

    /**
     * 清理已过期的订阅者引用（定期调用以释放内存）
     */
    private fun cleanupExpiredSubscribers() {
        if (subscribers.isEmpty()) return

        val beforeSize = subscribers.size
        subscribers.removeIf { it.get() == null }
        val afterSize = subscribers.size

        if (beforeSize != afterSize) {
            Log.d(TAG, "Cleaned up ${beforeSize - afterSize} expired subscribers")
        }
    }

    // ══════════════════════════════════════════════
    // 请求去重逻辑（防止短时间内重复提交）
    // ══════════════════════════════════════════════

    /**
     * 生成请求的唯一标识键
     *
     * 基于请求类型和关键参数生成，用于去重判断。
     *
     * @param request 状态变更请求
     * @return 唯一标识字符串
     */
    private fun getRequestKey(request: StateChangeRequest): String {
        return when (request) {
            is StateChangeRequest.UpdateGameDataField ->
                "UpdateGameDataField:${request.fieldUpdates.keys.sorted().joinToString(",")}"
            is StateChangeRequest.UpdateSectName -> "UpdateSectName:${request.newName}"
            is StateChangeRequest.UpdateSectLevel -> "UpdateSectLevel"
            is StateChangeRequest.UpdateSpiritStones -> "UpdateSpiritStones:${request.delta}"
            is StateChangeRequest.UpdateDiscipleField ->
                "UpdateDiscipleField:${request.discipleId}:${request.fieldUpdates.keys.sorted().joinToString(",")}"
            is StateChangeRequest.UpdateDiscipleCultivation ->
                "UpdateDiscipleCultivation:${request.discipleId}:${request.cultivationDelta}"
            is StateChangeRequest.UpdateDiscipleRealm ->
                "UpdateDiscipleRealm:${request.discipleId}:${request.newRealm}"
            is StateChangeRequest.UpdateDiscipleStatus ->
                "UpdateDiscipleStatus:${request.discipleId}:${request.newStatus}"
            is StateChangeRequest.AddDisciple -> "AddDisciple:${request.disciple.id}"
            is StateChangeRequest.RemoveDisciple -> "RemoveDisciple:${request.discipleId}"
            is StateChangeRequest.UpdateBuildingSlot -> "UpdateBuildingSlot:${request.slotIndex}"
            is StateChangeRequest.UpdateAlchemySlot -> "UpdateAlchemySlot:${request.slotIndex}"
            is StateChangeRequest.AddEquipment -> "AddEquipment:${request.equipment.id}"
            is StateChangeRequest.AddPill -> "AddPill:${request.pill.id}"
            is StateChangeRequest.RemoveItem -> "RemoveItem:${request.itemId}:${request.itemType}"
        }
    }

    /**
     * 检查是否为重复请求
     *
     * 在 [REQUEST_DEDUP_WINDOW_MS] 时间窗口内的相同请求被视为重复。
     *
     * @param request 待检查的请求
     * @return true 表示是重复请求应被丢弃
     */
    private fun isDuplicateRequest(request: StateChangeRequest): Boolean {
        val key = getRequestKey(request)
        val now = System.currentTimeMillis()
        val lastTime = requestDedupCache[key]

        if (lastTime != null && (now - lastTime) < REQUEST_DEDUP_WINDOW_MS) {
            return true
        }

        return false
    }

    /**
     * 记录已提交的请求（用于去重）
     *
     * 同时清理过期的缓存条目，防止内存泄漏。
     *
     * @param request 已提交的请求
     */
    private fun recordRequest(request: StateChangeRequest) {
        val key = getRequestKey(request)
        val now = System.currentTimeMillis()

        // 记录当前请求
        requestDedupCache[key] = now

        // 清理过期条目（超过去重窗口 10 倍时间的条目）
        if (requestDedupCache.size > 100) { // 仅在缓存较大时清理
            val threshold = now - REQUEST_DEDUP_WINDOW_MS * 10
            val iterator = requestDedupCache.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value < threshold) {
                    iterator.remove()
                }
            }
        }
    }
}
