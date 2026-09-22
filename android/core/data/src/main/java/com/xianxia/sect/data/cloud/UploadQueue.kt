package com.xianxia.sect.data.cloud

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.data.model.SaveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 异步上传队列（方案 D3 双步保存的第二步，SR-2 落地）。
 *
 * 状态机对齐 SR-0 §4.3 B 清单（Q1-Q10 单测逐例锚定）：
 * - **单飞 worker + 惰性启动**：首次 enqueue 才拉起协程；LEGACY 模式全链零入队 ⇒
 *   零协程活动（硬红线的机制面保障）。单飞同时天然满足 TapTap"同一存档不允许并发更新"
 *   与 400007 串行化（Q4）；
 * - **窗口合并**（Q7/Q8）：worker 出队前先等 [Config.debounceMs]，窗口内同 slot 的
 *   多次入队只剩最新一条（最新 saveId/最新快照）；
 * - **限频适配**（Q2）：上传成功后强制 [Config.sharedUploadCooldownMs] 全局冷却
 *   （TapTap 创建/更新共享 1 次/分钟冷却，按用户计多档共用——SR-0 §2.3 保守口径，
 *   参数化可放宽）；400001 退避也按冷却间隔；
 * - **指数退避**（Q3/Q4）：TOKEN_EXPIRED/CONCURRENT/网络族按
 *   baseRetryDelayMs × 2^(n-1)，封顶 maxRetryDelayMs；
 * - **熔断**（Q9）：连续 [Config.maxConsecutiveFailures] 次失败 → CircuitOpened 事件
 *   （告警通道如实提示，不静默），静默 [Config.circuitOpenMs] 后半开重试；
 * - **冲突挂起**（Q10/S10）：上传前经 [SaveArbiter] 仲裁，CONFLICT ⇒ 不自动上传，
 *   移入挂起区并发出事件，由 [resolveConflict] 按玩家选择收口（SR-3 冲突弹窗接线）；
 * - **幂等重传**（Q6）：入队 saveId ≤ 已确认序号直接跳过；进程被杀后按"同 saveId
 *   重入队"配方恢复（账本待传指针持久化），覆盖上传收敛。
 *
 * IN2：上传前仲裁只看序号（L/C/W），全类零时钟比较（退避/冷却 delay 是节奏不是"谁新"判定）。
 * IN1：队列失败只降级（事件 + 账本保持脏标志），不回滚本地已提交的保存。
 */
class UploadQueue(
    private val backend: SaveBackend,
    private val ledger: UploadLedger,
    private val scope: CoroutineScope,
    private val config: Config = Config()
) {

    data class Config(
        /** 窗口合并窗宽：worker 出队后先等此时长，窗内同 slot 多次入队合并为最新一条 */
        val debounceMs: Long = 2_000,
        /** 上传成功后的全局强制冷却（TapTap 创建/更新共享 1 次/分钟，多档共用） */
        val sharedUploadCooldownMs: Long = 60_000,
        /** 指数退避基值（TOKEN_EXPIRED/CONCURRENT/网络族） */
        val baseRetryDelayMs: Long = 30_000,
        /** 退避封顶 */
        val maxRetryDelayMs: Long = 10 * 60_000,
        /** 连续失败熔断阈值（Q9） */
        val maxConsecutiveFailures: Int = 5,
        /** 熔断静默时长（半开重试前） */
        val circuitOpenMs: Long = 5 * 60_000
    )

    /** 队列事件——UI 告警通道数据源（postSaveWarning 同纪律：失败必须如实，不静默） */
    sealed class Event {
        data class UploadConfirmed(val slot: Int, val saveId: Long) : Event()
        data class UploadFailed(
            val slot: Int,
            val saveId: Long,
            val error: SaveBackendError,
            val message: String,
            val willRetry: Boolean
        ) : Event()

        data class CircuitOpened(val slot: Int, val consecutiveFailures: Int) : Event()
        data class ConflictHeld(val slot: Int, val conflict: SaveConflictEvent) : Event()
    }

    private data class PendingEntry(
        val slot: Int,
        val saveData: SaveData,
        val saveId: Long
    )

    private data class HeldEntry(val entry: PendingEntry, val conflict: SaveConflictEvent)

    private val mutex = Mutex()
    private val pending = HashMap<Int, PendingEntry>()
    private val heldConflicts = HashMap<Int, HeldEntry>()
    /** 玩家已裁决"保留本地"的槽位：下一次上传授权越过冲突闸（显式选择 ≠ 静默覆盖） */
    private val conflictAuthorized = HashSet<Int>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var workerJob: Job? = null
    private var consecutiveFailures = 0
    /** 排空请求（SR-4 onStop）：置位后下一次处理跳过合并窗，消费即清 */
    private val drainRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Event> = _events

    /**
     * 入队一个待上传保存。`saveId` 缺省时由账本派发新序号（本地保存成功后调用，
     * IN1：本地事务已提交，本队列失败只降级）。
     * 幂等：saveId ≤ 已确认序号（Q6 已确认重放）直接跳过。
     */
    suspend fun enqueue(slot: Int, saveData: SaveData, saveId: Long? = null) {
        val id = saveId ?: ledger.recordLocalSave(slot)
        if (id <= ledger.lastConfirmedCloudId(slot)) {
            DomainLog.d(TAG, "enqueue skipped: saveId=$id already confirmed (slot=$slot)")
            return
        }
        mutex.withLock {
            pending[slot] = PendingEntry(slot, saveData, id) // 同 slot 最新覆盖 = 窗口合并数据面
        }
        ensureWorker()
        wake.trySend(Unit)
    }

    /**
     * 冲突收口（Q10/S10，SR-3 冲突弹窗接线）：
     * keepLocal=true ⇒ 恢复上传本地档；false ⇒ 玩家选云档，本地待传条目丢弃，
     * 账本基线收敛到云端序号（[UploadLedger.adoptCloudState]——保存序号语义，IN2 合规）。
     */
    suspend fun resolveConflict(slot: Int, keepLocal: Boolean) {
        val held = mutex.withLock { heldConflicts.remove(slot) } ?: return
        if (keepLocal) {
            mutex.withLock {
                pending[slot] = held.entry
                conflictAuthorized.add(slot)
            }
            wake.trySend(Unit)
        } else {
            mutex.withLock { pending.remove(slot) }
            ledger.adoptCloudState(slot, held.conflict.cloudSaveId ?: ledger.lastConfirmedCloudId(slot))
            DomainLog.i(TAG, "conflict resolved: slot=$slot 玩家选云档，本地待传丢弃，基线收敛")
        }
    }

    /** 测试/运维观测面：挂起中的冲突条目槽位 */
    suspend fun heldConflictSlots(): Set<Int> = mutex.withLock { heldConflicts.keys.toSet() }

    /**
     * 排空尝试（SR-4 `onStop`：本地事务已提交，进程可能随时被杀 ⇒ 尽力把待传推出去）。
     *
     * 只做两件事：置"下一次处理跳过合并窗"标记 + 唤醒空闲 worker。**不**绕过任何安全闸
     * （冲突仲裁/限频共享冷却/退避/熔断照旧生效——排空是"早点试"，不是"强行传"），
     * 也**不**拉起 worker（LEGACY 零活动红线：worker 惰性启动语义不变，调用侧模式门控）。
     *
     * 非挂起、任意线程可调。
     */
    fun requestDrain() {
        drainRequested.set(true)
        wake.trySend(Unit)
    }

    private fun consumeDrainRequest(): Boolean = drainRequested.getAndSet(false)

    private fun ensureWorker() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch { runLoop() }
    }

    // 防御兜底: 上传链异常源跨 IO/SDK 不可枚举, 记录后继续排空队列, 非静默吞噬
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runLoop() {
        while (true) {
            val entry = mutex.withLock { pending.values.minByOrNull { it.saveId } }
            if (entry == null) {
                wake.receive() // 空闲挂起（惰性启动语义：不入队即零活动）
                continue
            }
            try {
                process(entry)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 取消穿透：scope 取消时 worker 退出，不吞取消
            } catch (e: Exception) {
                DomainLog.e(TAG, "upload pipeline unexpected error, entry kept for retry: ${e.message}", e)
                _events.tryEmit(
                    Event.UploadFailed(entry.slot, entry.saveId, SaveBackendError.UNKNOWN, e.message ?: "", true)
                )
                delay(config.baseRetryDelayMs)
            }
        }
    }

    /** 单条处理：合并窗 → 冲突仲裁 → 上传 → 结果分类（确认/退避/熔断/挂起） */
    private suspend fun process(entry: PendingEntry) {
        // Q7/Q8：窗口内合并——多次入队只剩本 slot 最新条目；SR-4 排空请求跳窗立即试传
        if (!consumeDrainRequest()) delay(config.debounceMs)
        val current = mutex.withLock { pending[entry.slot] } ?: return
        if (conflictGate(current)) return
        executeUpload(current)
    }

    /**
     * 冲突仲裁闸（Q10）：上传前查云端 W，CONFLICT ⇒ 移入挂起区 + 发事件 + 中止本次处理。
     * W 查询失败 = null ⇒ U11 保守退化，不阻断 UPLOAD_PENDING 的重试。
     *
     * @return true = 已挂起（调用方结束本条处理）
     */
    private suspend fun conflictGate(entry: PendingEntry): Boolean {
        // 玩家已显式选择保留本地 ⇒ 本次上传已获授权，越过冲突闸（否则闸会再次
        // 判 CONFLICT 形成死锁——"谁新"已由玩家裁决，不再由仲裁器否决）
        val authorized = mutex.withLock { conflictAuthorized.remove(entry.slot) }
        if (authorized) return false
        val cloudId = when (val r = backend.currentCloudSaveId(entry.slot)) {
            is SaveBackendResult.Success -> r.data
            is SaveBackendResult.Failure -> null
        }
        ledger.normalizeIfNeeded(entry.slot) // U10 自愈（中断窗防御）
        val verdict = SaveArbiter.arbitrate(
            lastLocalSaveId = ledger.lastLocalSaveId(entry.slot),
            lastConfirmedCloudId = ledger.lastConfirmedCloudId(entry.slot),
            cloudSaveId = cloudId
        )
        if (verdict != ArbitrationVerdict.CONFLICT) return false

        val conflict = SaveConflictEvent(
            slot = entry.slot,
            lastLocalSaveId = ledger.lastLocalSaveId(entry.slot),
            lastConfirmedCloudId = ledger.lastConfirmedCloudId(entry.slot),
            cloudSaveId = cloudId,
            source = "upload"
        )
        mutex.withLock {
            pending.remove(entry.slot)
            heldConflicts[entry.slot] = HeldEntry(entry, conflict)
        }
        DomainLog.w(TAG, "conflict held: slot=${entry.slot} L=${conflict.lastLocalSaveId} " +
            "C=${conflict.lastConfirmedCloudId} W=$cloudId — 禁止自动上传覆盖，等待玩家选择")
        _events.tryEmit(Event.ConflictHeld(entry.slot, conflict))
        return true
    }

    private suspend fun executeUpload(entry: PendingEntry) {
        val result = backend.upload(entry.slot, entry.saveData, entry.saveId)
        when (result) {
            is SaveBackendResult.Success -> onUploadSuccess(entry)
            is SaveBackendResult.Failure -> onUploadFailure(entry, result)
        }
    }

    private suspend fun onUploadSuccess(entry: PendingEntry) {
        val failuresBefore = consecutiveFailures
        consecutiveFailures = 0
        ledger.recordCloudConfirmed(entry.slot, entry.saveId)
        mutex.withLock { pending.remove(entry.slot) }
        DomainLog.i(TAG, "upload confirmed: slot=${entry.slot} saveId=${entry.saveId}")
        _events.tryEmit(Event.UploadConfirmed(entry.slot, entry.saveId))
        if (failuresBefore > 0) DomainLog.i(TAG, "upload recovered after $failuresBefore consecutive failures")
        delay(config.sharedUploadCooldownMs) // 共享冷却（多档共用，Q2 保守口径）
    }

    private suspend fun onUploadFailure(entry: PendingEntry, failure: SaveBackendResult.Failure) {
        consecutiveFailures++
        val retryable = failure.error in RETRYABLE_ERRORS
        if (retryable && consecutiveFailures < config.maxConsecutiveFailures) {
            val backoff = retryBackoffMs(consecutiveFailures, failure.error)
            DomainLog.w(TAG, "upload failed (will retry in ${backoff}ms): slot=${entry.slot} " +
                "error=${failure.error} message=${failure.message}")
            _events.tryEmit(
                Event.UploadFailed(entry.slot, entry.saveId, failure.error, failure.message, willRetry = true)
            )
            delay(backoff)
            return
        }
        if (retryable) {
            // Q9：连续失败达上限 → 熔断（如实告警不静默），静默后半开重试
            DomainLog.e(TAG, "upload circuit OPEN: slot=${entry.slot} consecutiveFailures=$consecutiveFailures")
            _events.tryEmit(Event.CircuitOpened(entry.slot, consecutiveFailures))
            delay(config.circuitOpenMs)
            consecutiveFailures = 0 // 半开：给下一次尝试重置计数
            return
        }
        // 非重试族（尺寸/配额/鉴权/序列化/SDK 缺失）：如实告警，条目移除；
        // 账本 L>C 保持脏标志为真，下次本地保存重新入队
        DomainLog.e(TAG, "upload failed permanent: slot=${entry.slot} error=${failure.error} " +
            "message=${failure.message}")
        _events.tryEmit(
            Event.UploadFailed(entry.slot, entry.saveId, failure.error, failure.message, willRetry = false)
        )
        mutex.withLock { pending.remove(entry.slot) }
    }

    /** 退避节奏：限频族按共享冷却（服务器真实约束），其余指数 ×2 封顶 */
    private fun retryBackoffMs(failures: Int, error: SaveBackendError): Long {
        if (error == SaveBackendError.RATE_LIMITED) return config.sharedUploadCooldownMs
        var step = config.baseRetryDelayMs
        repeat((failures - 1).coerceAtMost(20)) {
            step = (step * 2).coerceAtMost(config.maxRetryDelayMs)
        }
        return step
    }

    private companion object {
        const val TAG = "UploadQueue"

        /** 可重试族（Q2/Q3/Q4 + 网络/超时/未知/档缺失——400002 清 UUID 后走重建路径可收敛） */
        val RETRYABLE_ERRORS = setOf(
            SaveBackendError.RATE_LIMITED,
            SaveBackendError.TOKEN_EXPIRED,
            SaveBackendError.CONCURRENT,
            SaveBackendError.NETWORK,
            SaveBackendError.TIMEOUT,
            SaveBackendError.ARCHIVE_MISSING,
            SaveBackendError.UNKNOWN
        )
    }
}
