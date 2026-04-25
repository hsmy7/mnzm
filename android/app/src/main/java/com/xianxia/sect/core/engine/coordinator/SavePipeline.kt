package com.xianxia.sect.core.engine.coordinator

import android.util.Log
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.engine.coordinator.SaveLoadCoordinator
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 异步存档管道
 *
 * 核心设计：
 * - 使用 Channel<SaveRequest>(capacity = 2) 作为缓冲队列
 * - 后台消费者协程从 Channel 中消费并执行存档
 * - 不停止游戏循环，直接通过 StateFlow.value 获取快照（线程安全 O(1) 操作）
 * - 支持两种模式：AUTO（自动存档）和 MANUAL（手动存档）
 * - 自动存档使用超时较短(15s)，手动存档使用较长超时(30s)
 * - 存档完成后刷新 slot 列表
 */
@Singleton
class SavePipeline @Inject constructor(
    private val storageFacade: StorageFacade,
    private val saveLoadCoordinator: SaveLoadCoordinator,
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    companion object {
        private const val TAG = "SavePipeline"

        /** 自动存档超时：15秒 */
        private const val AUTO_SAVE_TIMEOUT_MS = 15_000L

        /** 手动存档超时：30秒 */
        private const val MANUAL_SAVE_TIMEOUT_MS = 30_000L
    }

    /**
     * 存档请求
     *
     * @param slot 存档槽位
     * @param snapshot 游戏状态快照（不可变引用）
     * @param source 存档来源（自动/手动/后台）
     * @param timestamp 请求时间戳
     */
    data class SaveRequest(
        val slot: Int,
        val snapshot: GameStateSnapshot,
        val source: SaveSource,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 存档来源枚举
     */
    enum class SaveSource { AUTO, MANUAL, EMERGENCY }

    /**
     * 存档执行结果
     *
     * @param success 是否成功
     * @param durationMs 执行耗时（毫秒）
     * @param slot 目标槽位
     * @param source 存档来源
     * @param errorMessage 错误信息（失败时非空）
     * @param timedOut 是否因超时失败
     */
    data class SavePipelineResult(
        val success: Boolean,
        val durationMs: Long,
        val slot: Int,
        val source: SaveSource,
        val errorMessage: String? = null,
        val timedOut: Boolean = false
    )

    /** 缓冲队列：容量 4，允许短时间积压存档请求 */
    private val saveChannel = Channel<SaveRequest>(capacity = 4)

    /** 存档完成事件流，用于通知外部（如 GameViewModel）刷新存档列表 */
    private val _saveResults = MutableSharedFlow<SavePipelineResult>(extraBufferCapacity = 8)
    val saveResults: SharedFlow<SavePipelineResult> = _saveResults.asSharedFlow()

    /** 后台消费者协程 Job */
    private var consumerJob: Job? = null

    /** 协程作用域 */
    private val scope get() = applicationScopeProvider.ioScope

    private val isProcessing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val processingLatch = kotlinx.coroutines.sync.Mutex()

    init {
        startConsumer()
    }

    /**
     * 启动后台消费者协程
     */
    private fun startConsumer() {
        consumerJob = scope.launch {
            consumeLoop()
        }
        Log.d(TAG, "SavePipeline consumer started")
    }

    /**
     * 将存档请求入队（非阻塞）
     *
     * 如果队列已满，返回失败结果（不会阻塞调用方）
     *
     * @param request 存档请求
     * @return 入队结果：true 表示成功入队，false 表示队列已满被丢弃
     */
    fun enqueue(request: SaveRequest): Boolean {
        val offered = try {
            saveChannel.trySend(request).isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue save request for slot=${request.slot}", e)
            false
        }

        if (!offered) {
            Log.w(TAG, "Save queue full, dropping save request for slot=${request.slot}, source=${request.source}")
        } else {
            Log.d(TAG, "Save request enqueued: slot=${request.slot}, source=${request.source}")
        }

        return offered
    }

    /**
     * 后台消费者协程主循环
     *
     * 从 Channel 中持续消费并执行存档请求
     */
    private suspend fun consumeLoop() {
        for (request in saveChannel) {
            isProcessing.set(true)
            processingLatch.lock()
            try {
                val result = executeSave(request)
                if (!result.success) {
                    Log.w(TAG, "Save failed: slot=${result.slot}, source=${result.source}, error=${result.errorMessage}, timedOut=${result.timedOut}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error processing save request for slot=${request.slot}", e)
            } finally {
                processingLatch.unlock()
                isProcessing.set(false)
            }
        }
    }

    /**
     * 执行实际存档逻辑
     *
     * 1. 通过 saveLoadCoordinator.executeSaveWithMonitoring 包装存档操作
     * 2. 调用 storageFacade.save(slot, saveData)（异步版本，避免 runBlocking 死锁）
     * 3. 根据 source 类型设置不同的超时（AUTO=15s, MANUAL/BG=30s）
     * 4. 返回 SavePipelineResult
     *
     * @param request 存档请求
     * @return 存档执行结果
     */
    private suspend fun executeSave(request: SaveRequest): SavePipelineResult {
        val startTime = System.currentTimeMillis()
        val timeoutMs = when (request.source) {
            SaveSource.AUTO -> AUTO_SAVE_TIMEOUT_MS
            SaveSource.EMERGENCY -> AUTO_SAVE_TIMEOUT_MS
            else -> MANUAL_SAVE_TIMEOUT_MS
        }

        // 从快照构建 SaveData
        val updatedGameData = request.snapshot.gameData.copy(currentSlot = request.slot)
        val saveData = SaveData(
            gameData = updatedGameData,
            disciples = request.snapshot.disciples,
            equipmentStacks = request.snapshot.equipmentStacks,
            equipmentInstances = request.snapshot.equipmentInstances,
            manualStacks = request.snapshot.manualStacks,
            manualInstances = request.snapshot.manualInstances,
            pills = request.snapshot.pills,
            materials = request.snapshot.materials,
            herbs = request.snapshot.herbs,
            seeds = request.snapshot.seeds,
            teams = request.snapshot.teams,
            events = request.snapshot.events,
            battleLogs = request.snapshot.battleLogs,
            alliances = request.snapshot.alliances,
            productionSlots = request.snapshot.productionSlots
        )

        val operationType = when (request.source) {
            SaveSource.AUTO -> SaveLoadCoordinator.OperationType.AUTO_SAVE
            SaveSource.EMERGENCY -> SaveLoadCoordinator.OperationType.AUTO_SAVE
            SaveSource.MANUAL -> SaveLoadCoordinator.OperationType.MANUAL_SAVE
        }

        val result = try {
            val monitoringResult = saveLoadCoordinator.executeSaveWithMonitoring(
                operationType = operationType,
                saveSlot = request.slot,
                saveOperation = {
                    // 关键修复：使用异步 save() 替代 saveSyncWithResult()
                    // saveSyncWithResult() 内部使用 runBlocking(Dispatchers.IO)，
                    // 在协程消费者上下文中嵌套 runBlocking 会导致线程池饥饿和死锁风险。
                    // 异步 save() 已在 executeSaveWithMonitoring 的 withContext(Dispatchers.IO) 中执行。
                    val innerResult = withTimeoutOrNull(timeoutMs) {
                        storageFacade.save(request.slot, saveData)
                    }
                    if (innerResult == null) {
                        throw TimeoutException("Save timed out after ${timeoutMs}ms")
                    }
                    innerResult.isSuccess
                }
            )

            val durationMs = System.currentTimeMillis() - startTime

            if (monitoringResult.success) {
                Log.d(TAG, "Save completed: slot=${request.slot}, source=${request.source}, duration=${durationMs}ms")
                SavePipelineResult(
                    success = true,
                    durationMs = durationMs,
                    slot = request.slot,
                    source = request.source
                )
            } else {
                SavePipelineResult(
                    success = false,
                    durationMs = durationMs,
                    slot = request.slot,
                    source = request.source,
                    errorMessage = monitoringResult.errorMessage ?: "Unknown save error",
                    timedOut = monitoringResult.timedOut
                )
            }
        } catch (e: CancellationException) {
            val durationMs = System.currentTimeMillis() - startTime
            Log.w(TAG, "Save cancelled for slot=${request.slot}: ${e.message}")
            SavePipelineResult(
                success = false,
                durationMs = durationMs,
                slot = request.slot,
                source = request.source,
                errorMessage = "Save cancelled",
                timedOut = true
            )
        } catch (e: TimeoutCancellationException) {
            val durationMs = System.currentTimeMillis() - startTime
            Log.w(TAG, "Save timed out for slot=${request.slot} after ${timeoutMs}ms")
            SavePipelineResult(
                success = false,
                durationMs = durationMs,
                slot = request.slot,
                source = request.source,
                errorMessage = "Save timed out after ${timeoutMs}ms",
                timedOut = true
            )
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            Log.e(TAG, "Save execution failed for slot=${request.slot}: ${e.message}", e)
            SavePipelineResult(
                success = false,
                durationMs = durationMs,
                slot = request.slot,
                source = request.source,
                errorMessage = e.message ?: "Unknown error"
            )
        }

        _saveResults.tryEmit(result)
        return result
    }

    fun isSaveInProgress(): Boolean = isProcessing.get()

    suspend fun waitForCurrentSave(timeoutMs: Long = 10_000L): Boolean {
        if (!isProcessing.get()) return true
        return try {
            withTimeoutOrNull(timeoutMs) {
                processingLatch.lock()
                processingLatch.unlock()
            } != null
        } catch (e: Exception) {
            Log.w(TAG, "waitForCurrentSave interrupted", e)
            false
        }
    }

    /**
     * 优雅关闭管道：先关闭Channel不再接受新请求，等待消费者处理完队列中剩余请求后停止
     * 
     * 注意：SavePipeline 为 @Singleton，生命周期绑定到应用进程。
     * 通常无需手动调用，进程终止时系统会自动清理所有资源。
     * 仅在需要主动释放资源（如应用级退出清理）时调用。
     */
    suspend fun shutdownGracefully() {
        saveChannel.close()
        try {
            consumerJob?.join()
        } catch (e: Exception) {
            Log.w(TAG, "Error waiting for consumer to finish during graceful shutdown: ${e.message}")
        }
        scope.cancel()
        Log.d(TAG, "SavePipeline graceful shutdown completed")
    }

    /**
     * 强制关闭管道（不等待队列排空）
     */
    fun shutdown() {
        saveChannel.close()
        consumerJob?.cancel()
        scope.cancel()
        Log.d(TAG, "SavePipeline force shutdown completed")
    }
}
