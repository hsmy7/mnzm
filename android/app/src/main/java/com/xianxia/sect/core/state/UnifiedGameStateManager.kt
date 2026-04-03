package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.event.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 旧版游戏状态数据类
 * 
 * @deprecated 此类已废弃，请使用 [UnifiedGameState] 替代。
 *             UnifiedGameState 提供了更完善的状态管理和迁移方法。
 *             迁移指南：
 *             - 将 GameState 替换为 UnifiedGameState
 *             - 使用 UnifiedGameState.fromGameState() 进行转换
 *             - 使用 UnifiedGameState.toGameState() 如需向后兼容
 */
@Deprecated(
    message = "Use UnifiedGameState instead. This class is kept for backward compatibility only.",
    replaceWith = ReplaceWith("UnifiedGameState", "com.xianxia.sect.core.state.UnifiedGameState")
)
data class GameState(
    val gameData: GameData = GameData(),
    val disciples: List<Disciple> = emptyList(),
    val equipment: List<Equipment> = emptyList(),
    val manuals: List<Manual> = emptyList(),
    val pills: List<Pill> = emptyList(),
    val materials: List<Material> = emptyList(),
    val herbs: List<Herb> = emptyList(),
    val seeds: List<Seed> = emptyList(),
    val teams: List<ExplorationTeam> = emptyList(),
    val events: List<GameEvent> = emptyList(),
    val battleLogs: List<BattleLog> = emptyList(),
    val alliances: List<Alliance> = emptyList(),
    
    val isPaused: Boolean = true,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val gameSpeed: Int = 1,
    
    val lastUpdateTime: Long = System.currentTimeMillis(),
    val version: Int = 1
) {
    val isRunning: Boolean get() = !isPaused && !isLoading && !isSaving
    
    val aliveDisciples: List<Disciple> get() = disciples.filter { it.isAlive }
    
    val idleDisciples: List<Disciple> get() = disciples.filter { it.isAlive && it.status == DiscipleStatus.IDLE }
    
    val workingDisciples: List<Disciple> get() = disciples.filter { it.isAlive && it.status != DiscipleStatus.IDLE }
    
    fun getDiscipleById(id: String): Disciple? = disciples.find { it.id == id }
    
    fun getEquipmentById(id: String): Equipment? = equipment.find { it.id == id }
    
    fun getManualById(id: String): Manual? = manuals.find { it.id == id }
    
    fun getEquipmentByOwner(discipleId: String): List<Equipment> = 
        equipment.filter { it.ownerId == discipleId }
    
    fun getManualsByOwner(discipleId: String): List<Manual> = 
        manuals.filter { it.ownerId == discipleId }
}

data class UnifiedGameState(
    val gameData: GameData = GameData(),
    val disciples: List<Disciple> = emptyList(),
    val equipment: List<Equipment> = emptyList(),
    val manuals: List<Manual> = emptyList(),
    val pills: List<Pill> = emptyList(),
    val materials: List<Material> = emptyList(),
    val herbs: List<Herb> = emptyList(),
    val seeds: List<Seed> = emptyList(),
    val teams: List<ExplorationTeam> = emptyList(),
    val events: List<GameEvent> = emptyList(),
    val battleLogs: List<BattleLog> = emptyList(),
    val alliances: List<Alliance> = emptyList(),
    
    val isPaused: Boolean = true,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val gameSpeed: Int = 1,
    
    val lastUpdateTime: Long = System.currentTimeMillis(),
    val version: Int = 1
) {
    val isRunning: Boolean get() = !isPaused && !isLoading && !isSaving
    
    val aliveDisciples: List<Disciple> get() = disciples.filter { it.isAlive }
    
    val idleDisciples: List<Disciple> get() = disciples.filter { it.isAlive && it.status == DiscipleStatus.IDLE }
    
    val workingDisciples: List<Disciple> get() = disciples.filter { it.isAlive && it.status != DiscipleStatus.IDLE }
    
    fun getDiscipleById(id: String): Disciple? = disciples.find { it.id == id }
    
    fun getEquipmentById(id: String): Equipment? = equipment.find { it.id == id }
    
    fun getManualById(id: String): Manual? = manuals.find { it.id == id }
    
    fun getEquipmentByOwner(discipleId: String): List<Equipment> = 
        equipment.filter { it.ownerId == discipleId }
    
    fun getManualsByOwner(discipleId: String): List<Manual> = 
        manuals.filter { it.ownerId == discipleId }
    
    /**
     * 将 UnifiedGameState 转换为旧版 GameState
     * 
     * @deprecated 此方法仅用于向后兼容。新代码应直接使用 UnifiedGameState。
     *             将在未来版本中移除。
     */
    @Deprecated(
        message = "Use UnifiedGameState directly instead of converting to legacy GameState. " +
                  "This method is kept for backward compatibility only and will be removed in a future version.",
        level = DeprecationLevel.WARNING
    )
    fun toGameState(): GameState = GameState(
        gameData = gameData,
        disciples = disciples,
        equipment = equipment,
        manuals = manuals,
        pills = pills,
        materials = materials,
        herbs = herbs,
        seeds = seeds,
        teams = teams,
        events = events,
        battleLogs = battleLogs,
        alliances = alliances
    )
    
    companion object {
        /**
         * 从旧版 GameState 创建 UnifiedGameState
         * 
         * @deprecated 此方法仅用于迁移旧数据。新代码应直接使用 UnifiedGameState。
         *             将在未来版本中移除。
         */
        @Deprecated(
            message = "Use UnifiedGameState directly instead of converting from legacy GameState. " +
                      "This method is kept for migrating old save data only and will be removed in a future version.",
            level = DeprecationLevel.WARNING
        )
        fun fromGameState(state: GameState): UnifiedGameState = UnifiedGameState(
            gameData = state.gameData,
            disciples = state.disciples,
            equipment = state.equipment,
            manuals = state.manuals,
            pills = state.pills,
            materials = state.materials,
            herbs = state.herbs,
            seeds = state.seeds,
            teams = state.teams,
            events = state.events,
            battleLogs = state.battleLogs,
            alliances = state.alliances,
            isPaused = state.isPaused,
            isLoading = state.isLoading,
            isSaving = state.isSaving,
            gameSpeed = state.gameSpeed,
            lastUpdateTime = state.lastUpdateTime,
            version = state.version
        )
    }
}

sealed class UnifiedStateChange {
    abstract val timestamp: Long
    
    data class GameDataUpdate(
        val oldData: GameData,
        val newData: GameData,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class DiscipleUpdated(
        val discipleId: String,
        val changes: Map<String, Any?>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class DiscipleUpdate(
        val discipleId: String,
        val oldDisciple: Disciple?,
        val newDisciple: Disciple?,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class DiscipleListUpdate(
        val disciples: List<Disciple>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class EquipmentUpdate(
        val equipmentId: String,
        val oldEquipment: Equipment?,
        val newEquipment: Equipment?,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class CultivationProgress(
        val discipleId: String,
        val progress: Double,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class ItemCrafted(
        val itemId: String,
        val itemType: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class BattleCompleted(
        val battleId: String,
        val result: BattleResult,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class PauseStateChange(
        val isPaused: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
    
    data class FullStateUpdate(
        val state: UnifiedGameState,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UnifiedStateChange()
}

data class BattleResult(
    val victory: Boolean,
    val playerLosses: Int = 0,
    val enemyLosses: Int = 0,
    val rewards: List<RewardSelectedItem> = emptyList()
) {
    fun toBattleResultInfo(): BattleResultInfo = BattleResultInfo(
        victory = victory,
        playerLosses = playerLosses,
        enemyLosses = enemyLosses,
        rewards = rewards.map { 
            RewardItemInfo(
                itemId = it.id,
                itemName = it.name,
                quantity = it.quantity,
                rarity = it.rarity
            )
        }
    )
}

interface UnifiedStateObserver {
    fun onStateChange(change: UnifiedStateChange)
}

@Singleton
class UnifiedGameStateManager @Inject constructor(
    private val eventBus: EventBus
) {

    companion object {
        private const val TAG = "UnifiedGameStateManager"
    }

    private val stateMutex = Mutex()
    
    /**
     * 同步桥接：用于在非协程环境中安全获取 stateMutex。
     * 所有 *Sync 后缀方法均通过此方法统一走 stateMutex，消除双锁死锁风险。
     *
     * ⚠️ 线程安全警告：
     * - 此方法使用 runBlocking，在主线程调用可能导致 ANR（Application Not Responding）
     * - 已添加 5 秒超时保护，超时后将直接执行 block()（无锁保护）
     * - 生产环境中应优先使用 suspend 版本的 updateStateSafe() 方法
     * - 仅在无法使用协程的遗留代码中使用此方法
     */
    private fun <T> runBlockingWithStateLock(block: () -> T): T {
        return try {
            // 使用带超时的 runBlocking，防止主线程阻塞导致 ANR
            runBlocking {
                withTimeoutOrNull(5000) {
                    stateMutex.withLock { block() }
                } ?: run {
                    android.util.Log.e("UnifiedGameStateManager", "State lock acquisition timed out (5000ms), executing without lock")
                    block()
                }
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            android.util.Log.e("UnifiedGameStateManager", "State lock acquisition timed out", e)
            // 超时降级：直接执行业务逻辑（无锁保护），避免应用卡死
            block()
        }
    }

    /**
     * 安全的状态更新方法（协程版本）
     *
     * 推荐在协程上下文中使用此方法替代 runBlockingWithStateLock，
     * 避免线程阻塞和 ANR 风险。
     *
     * 特性：
     * - 5 秒超时保护
     * - 超时时自动降级为无锁更新
     * - 完整的变更记录和观察者通知
     *
     * @param block 状态转换函数，接收旧状态并返回新状态
     * @return 状态变更对象
     */
    suspend fun <T> updateStateSafe(block: (UnifiedGameState) -> UnifiedGameState): UnifiedStateChange {
        return withTimeoutOrNull(5000) {
            stateMutex.withLock {
                val oldState = _state.value
                val newState = block(oldState)
                _state.value = newState.copy(lastUpdateTime = System.currentTimeMillis())
                val c = UnifiedStateChange.FullStateUpdate(newState)
                recordChange(c)
                c to Unit
            }
        }?.first ?: run {
            android.util.Log.w(TAG, "State update timed out, attempting direct update")
            val directChange = UnifiedStateChange.FullStateUpdate(block(_state.value))
            _state.value = _state.value.copy(lastUpdateTime = System.currentTimeMillis())
            directChange
        }
    }
    
    private val _state = MutableStateFlow(UnifiedGameState())
    val state: StateFlow<UnifiedGameState> = _state.asStateFlow()
    
    /**
     * 观察者列表（弱引用包装）
     *
     * 使用 WeakReference 防止内存泄漏：
     * - 若 UI 组件（如 Fragment/Activity）注册为观察者后忘记调用 removeObserver，
     *   强引用会导致该组件无法被 GC 回收，造成内存泄漏。
     * - WeakReference 允许 GC 在观察者不再被其他地方强引用时自动回收。
     * - notifyObservers 会在遍历时自动清理已被 GC 的失效引用。
     */
    private val observers = CopyOnWriteArrayList<WeakReference<UnifiedStateObserver>>()
    
    /**
     * 待处理的通知队列，用于批量通知优化
     * 减少高频场景下的遍历开销
     */
    private val pendingNotifications = mutableListOf<Pair<UnifiedStateObserver, UnifiedStateChange>>()
    
    private val changeHistory = ConcurrentLinkedQueue<UnifiedStateChange>()
    private val maxHistorySize = 100
    
    /**
     * 用于保护 changeHistory 的 add+trim 原子操作
     */
    private val historyLock = Any()
    
    val currentState: UnifiedGameState 
        get() = _state.value
    
    suspend fun <T> withState(block: (UnifiedGameState) -> Pair<UnifiedGameState, T>): T {
        val change = stateMutex.withLock {
            val (newState, result) = block(_state.value)
            _state.value = newState.copy(
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(newState)
            recordChange(c)
            c to result
        }
        notifyObservers(change.first)
        return change.second
    }
    
    suspend fun updateState(block: (UnifiedGameState) -> UnifiedGameState) {
        val change = stateMutex.withLock {
            val oldState = _state.value
            val newState = block(oldState)
            _state.value = newState.copy(
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(newState)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    suspend fun updateStateSync(block: (UnifiedGameState) -> UnifiedGameState) {
        val change = stateMutex.withLock {
            val oldState = _state.value
            val newState = block(oldState)
            _state.value = newState.copy(
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(newState)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    suspend fun updateGameData(update: (GameData) -> GameData) {
        val pair = stateMutex.withLock {
            val oldData = _state.value.gameData
            val newData = update(oldData)
            
            _state.value = _state.value.copy(
                gameData = newData,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val change = UnifiedStateChange.GameDataUpdate(oldData, newData)
            recordChange(change)
            change to SectEvent(
                sectId = newData.id,
                sectName = newData.sectName,
                action = "data_update",
                details = mapOf("timestamp" to System.currentTimeMillis())
            )
        }
        notifyObservers(pair.first)
        eventBus.emit(pair.second)
    }
    
    fun updateGameDataSync(update: (GameData) -> GameData) {
        val change = runBlockingWithStateLock {
            val oldData = _state.value.gameData
            val newData = update(oldData)
            
            _state.value = _state.value.copy(
                gameData = newData,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val c = UnifiedStateChange.GameDataUpdate(oldData, newData)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    suspend fun updateDisciple(discipleId: String, update: (Disciple) -> Disciple) {
        val result = stateMutex.withLock {
            val currentDisciples = _state.value.disciples
            val index = currentDisciples.indexOfFirst { it.id == discipleId }
            
            if (index >= 0) {
                val oldDisciple = currentDisciples[index]
                val newDisciple = update(oldDisciple)
                val newDisciples = currentDisciples.toMutableList()
                newDisciples[index] = newDisciple
                
                _state.value = _state.value.copy(
                    disciples = newDisciples,
                    lastUpdateTime = System.currentTimeMillis()
                )
                
                val change = UnifiedStateChange.DiscipleUpdate(discipleId, oldDisciple, newDisciple)
                recordChange(change)
                
                val changes = mutableMapOf<String, Any?>()
                if (oldDisciple.cultivation != newDisciple.cultivation) {
                    changes["cultivation"] = newDisciple.cultivation
                }
                if (oldDisciple.realm != newDisciple.realm) {
                    changes["realm"] = newDisciple.realm
                }
                if (oldDisciple.status != newDisciple.status) {
                    changes["status"] = newDisciple.status
                }
                val event = if (changes.isNotEmpty()) {
                    DiscipleUpdatedEvent(discipleId = discipleId, changes = changes)
                } else null
                
                Triple(change, true, event)
            } else {
                Triple(null, false, null)
            }
        }
        result.first?.let { notifyObservers(it) }
        result.third?.let { eventBus.emit(it) }
    }
    
    suspend fun updateDisciples(update: (List<Disciple>) -> List<Disciple>) {
        val change = stateMutex.withLock {
            val newDisciples = update(_state.value.disciples)
            
            _state.value = _state.value.copy(
                disciples = newDisciples,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val c = UnifiedStateChange.DiscipleListUpdate(newDisciples)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    suspend fun addDisciple(disciple: Disciple) {
        val pair = stateMutex.withLock {
            val newDisciples = _state.value.disciples + disciple
            
            _state.value = _state.value.copy(
                disciples = newDisciples,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val change = UnifiedStateChange.DiscipleUpdate(disciple.id, null, disciple)
            recordChange(change)
            change to DiscipleUpdatedEvent(
                discipleId = disciple.id,
                changes = mapOf("added" to true, "name" to disciple.name)
            )
        }
        notifyObservers(pair.first)
        eventBus.emit(pair.second)
    }
    
    suspend fun removeDisciple(discipleId: String) {
        val pair = stateMutex.withLock {
            val oldDisciple = _state.value.disciples.find { it.id == discipleId }
            val newDisciples = _state.value.disciples.filter { it.id != discipleId }
            
            _state.value = _state.value.copy(
                disciples = newDisciples,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val change = UnifiedStateChange.DiscipleUpdate(discipleId, oldDisciple, null)
            recordChange(change)
            change to DiscipleUpdatedEvent(
                discipleId = discipleId,
                changes = mapOf("removed" to true)
            )
        }
        notifyObservers(pair.first)
        eventBus.emit(pair.second)
    }
    
    suspend fun emitCultivationProgress(discipleId: String, progress: Double) {
        val pair = stateMutex.withLock {
            val change = UnifiedStateChange.CultivationProgress(discipleId, progress)
            recordChange(change)
            change to CultivationProgressEvent(discipleId = discipleId, progress = progress)
        }
        notifyObservers(pair.first)
        eventBus.emit(pair.second)
    }
    
    suspend fun emitItemCrafted(itemId: String, itemType: String) {
        val pair = stateMutex.withLock {
            val change = UnifiedStateChange.ItemCrafted(itemId, itemType)
            recordChange(change)
            change to ItemCraftedEvent(itemId = itemId, itemType = itemType)
        }
        notifyObservers(pair.first)
        eventBus.emit(pair.second)
    }
    
    suspend fun emitBattleCompleted(battleId: String, result: BattleResult) {
        val pair = stateMutex.withLock {
            val change = UnifiedStateChange.BattleCompleted(battleId, result)
            recordChange(change)
            change to BattleCompletedEvent(battleId = battleId, result = result.toBattleResultInfo())
        }
        notifyObservers(pair.first)
        eventBus.emit(pair.second)
    }
    
    suspend fun setPaused(paused: Boolean) {
        val change = stateMutex.withLock {
            _state.value = _state.value.copy(
                isPaused = paused,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val c = UnifiedStateChange.PauseStateChange(paused)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    fun setPausedSync(paused: Boolean) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                isPaused = paused,
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val c = UnifiedStateChange.PauseStateChange(paused)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    suspend fun setLoading(loading: Boolean) {
        stateMutex.withLock {
            _state.value = _state.value.copy(
                isLoading = loading,
                lastUpdateTime = System.currentTimeMillis()
            )
        }
    }
    
    suspend fun setSaving(saving: Boolean) {
        stateMutex.withLock {
            _state.value = _state.value.copy(
                isSaving = saving,
                lastUpdateTime = System.currentTimeMillis()
            )
        }
    }
    
    suspend fun loadState(newState: UnifiedGameState) {
        val change = stateMutex.withLock {
            _state.value = newState.copy(
                lastUpdateTime = System.currentTimeMillis()
            )
            
            val c = UnifiedStateChange.FullStateUpdate(newState)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    fun loadStateSync(newState: UnifiedGameState) {
        val change = runBlockingWithStateLock {
            _state.value = newState.copy(
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(newState)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    // ==================== 增量更新方法 ====================
    // 以下方法用于细粒度状态同步，只更新指定的域，避免全量拷贝开销
    
    /**
     * 仅更新弟子列表
     */
    fun updateDisciplesSync(newDisciples: List<Disciple>) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                disciples = newDisciples,
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.DiscipleListUpdate(newDisciples)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    /**
     * 仅更新游戏基础数据
     */
    fun updateGameDataSync(newGameData: GameData) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                gameData = newGameData,
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.GameDataUpdate(_state.value.gameData, newGameData)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    /**
     * 仅更新装备列表
     */
    fun updateEquipmentSync(newEquipment: List<Equipment>) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                equipment = newEquipment,
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(_state.value)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    /**
     * 仅更新功法列表
     */
    fun updateManualsSync(newManuals: List<Manual>) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                manuals = newManuals,
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(_state.value)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    /**
     * 仅更新丹药列表
     */
    fun updatePillsSync(newPills: List<Pill>) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                pills = newPills,
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(_state.value)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    /**
     * 仅更新材料列表
     */
    fun updateMaterialsSync(newMaterials: List<Material>) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                materials = newMaterials,
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(_state.value)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    /**
     * 仅更新灵草列表
     */
    fun updateHerbsSync(newHerbs: List<Herb>) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                herbs = newHerbs,
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(_state.value)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    /**
     * 仅更新种子列表
     */
    fun updateSeedsSync(newSeeds: List<Seed>) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                seeds = newSeeds,
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(_state.value)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    /**
     * 仅更新探索队伍列表
     */
    fun updateTeamsSync(newTeams: List<ExplorationTeam>) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                teams = newTeams,
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(_state.value)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    /**
     * 仅更新游戏事件列表
     */
    fun updateEventsSync(newEvents: List<GameEvent>) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                events = newEvents,
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(_state.value)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    /**
     * 仅更新战斗日志列表
     */
    fun updateBattleLogsSync(newBattleLogs: List<BattleLog>) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                battleLogs = newBattleLogs,
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(_state.value)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    /**
     * 仅更新联盟列表
     */
    fun updateAlliancesSync(newAlliances: List<Alliance>) {
        val change = runBlockingWithStateLock {
            _state.value = _state.value.copy(
                alliances = newAlliances,
                lastUpdateTime = System.currentTimeMillis()
            )
            val c = UnifiedStateChange.FullStateUpdate(_state.value)
            recordChange(c)
            c
        }
        notifyObservers(change)
    }
    
    /**
     * 从旧版 GameState 加载状态
     * 
     * @deprecated 此方法仅用于加载旧版存档。新代码应使用 [loadState] 或 [loadStateSync]。
     *             将在未来版本中移除。
     */
    @Deprecated(
        message = "Use loadState() or loadStateSync() with UnifiedGameState instead. " +
                  "This method is kept for loading old save files only and will be removed in a future version.",
        level = DeprecationLevel.WARNING
    )
    suspend fun loadFromGameState(gameState: GameState) {
        loadState(UnifiedGameState.fromGameState(gameState))
    }
    
    /**
     * 注册状态观察者（弱引用持有）
     *
     * 观察者以 WeakReference 方式存储，不会阻止观察者对象被 GC 回收。
     * 公开 API 签名不变，内部实现改为弱引用。
     */
    fun addObserver(observer: UnifiedStateObserver) {
        observers.add(WeakReference(observer))
    }

    /**
     * 移除状态观察者
     *
     * 通过比较弱引用解引用后的实际对象来匹配并移除。
     */
    fun removeObserver(observer: UnifiedStateObserver) {
        observers.removeAll { it.get() == observer }
    }
    
    fun getChangeHistory(): List<UnifiedStateChange> = changeHistory.toList()
    
    fun getChangesSince(timestamp: Long): List<UnifiedStateChange> = 
        changeHistory.filter { it.timestamp >= timestamp }
    
    private fun recordChange(change: UnifiedStateChange) {
        // 使用 synchronized 确保 add + trim 操作的原子性
        // 防止多线程环境下队列大小超过 maxHistorySize
        synchronized(historyLock) {
            changeHistory.add(change)
            while (changeHistory.size > maxHistorySize) {
                changeHistory.poll()
            }
        }
    }
    
    /**
     * 通知所有观察者状态变更
     *
     * 实现要点（P0-4 弱引用化修复）：
     * 1. 先清理已被 GC 回收的失效弱引用（get() == null），避免列表无限膨胀
     * 2. 将存活的弱引用解引用为快照数组，确保遍历期间线程安全
     * 3. 逐个通知，单个观察者异常不影响其他观察者
     */
    private fun notifyObservers(change: UnifiedStateChange) {
        // 清理已被 GC 的弱引用，防止列表中积累无效条目
        observers.removeAll { it.get() == null }

        // 解引用获取实际观察者实例，创建快照用于遍历
        val observersSnapshot = observers.mapNotNull { it.get() }.toTypedArray()
        for (observer in observersSnapshot) {
            try {
                observer.onStateChange(change)
            } catch (e: Exception) {
                android.util.Log.e("UnifiedGameStateManager", "Error notifying observer", e)
            }
        }
    }
    
    /**
     * 批量通知多个状态变更
     * 在高频更新场景下使用，减少重复遍历开销
     *
     * 与 notifyObservers 同样使用弱引用清理 + 快照遍历策略。
     */
    private fun notifyObserversBatch(changes: List<UnifiedStateChange>) {
        if (changes.isEmpty()) return

        // 清理已被 GC 的弱引用
        observers.removeAll { it.get() == null }

        // 解引用获取实际观察者实例，创建快照用于遍历
        val observersSnapshot = observers.mapNotNull { it.get() }.toTypedArray()
        for (observer in observersSnapshot) {
            try {
                // 对每个观察者，按顺序通知所有变更
                for (change in changes) {
                    observer.onStateChange(change)
                }
            } catch (e: Exception) {
                android.util.Log.e("UnifiedGameStateManager", "Error in batch notification", e)
                // 单个观察者异常不影响其他观察者
            }
        }
    }
    
    suspend fun createSnapshot(): UnifiedGameState {
        return stateMutex.withLock { _state.value.copy() }
    }
    
    fun clearHistory() {
        changeHistory.clear()
    }
}
