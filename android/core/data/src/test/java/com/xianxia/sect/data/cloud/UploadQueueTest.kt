package com.xianxia.sect.data.cloud

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.prefs.InMemoryKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 上传队列状态机单测——SR-0 §4.3 B 清单（Q1-Q10）逐例锚定。
 *
 * 全部用 TestScope 虚拟时间推进：退避/冷却/熔断 delay 均为节奏控制（IN2 合规，
 * 不涉"谁新"判定），虚拟时间下毫秒级跑完不真等。配置等比缩小窗宽/间隔，
 * 语义（先后序与比例）不变。云端单档语义下队列为单条目状态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadQueueTest {

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var ledger: UploadLedger
    private lateinit var backend: FakeBackend

    private val config = UploadQueue.Config(
        debounceMs = 100,
        sharedUploadCooldownMs = 1_000,
        baseRetryDelayMs = 200,
        maxRetryDelayMs = 3_200,
        maxConsecutiveFailures = 5,
        circuitOpenMs = 4_000
    )

    private val saveData = SaveData(
        gameData = GameData(),
        disciples = emptyList(),
        pills = emptyList(),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList()
    )

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        ledger = UploadLedger(store)
        backend = FakeBackend()
    }

    /** 本测试创建的调度器级作用域：测试体结束即取消（否则重试循环无限跑——OOM 根因） */
    private val cleanupScopes = mutableListOf<CoroutineScope>()

    /** runTest 包装器：测试体一结束（含断言失败路径）立即取消队列作用域，
     *  防止 runTest 收尾推进调度器时把耗尽脚本响应的 worker 无限重试循环跑成真实时间爆炸 */
    private fun queueTest(block: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            block()
        } finally {
            cleanupScopes.forEach { it.cancel() }
            cleanupScopes.clear()
        }
    }

    @After
    fun tearDown() {
        cleanupScopes.forEach { it.cancel() }
        cleanupScopes.clear()
    }

    private fun TestScope.newQueue() = UploadQueue(backend, ledger, workerScope(), config)

    /**
     * 队列 worker 作用域：与 TestScope 共享虚拟时钟调度器（delay 由 advanceTimeBy/
     * advanceUntilIdle 推进），但持有独立 Job。
     * 不用 backgroundScope——本版本 coroutines-test 的 backgroundScope 任务不被
     * advanceUntilIdle 推进（仅测试体挂起时推进），worker 会全程饿死；不直接用
     * TestScope 本体——worker 永不退出会成为 runTest 子协程导致 finish 挂起。
     */
    private fun TestScope.workerScope(): CoroutineScope =
        CoroutineScope(coroutineContext + Job()).also { cleanupScopes.add(it) }

    // ── Q1：空闲入队 → 待传，L 推进、C 不变（上传请求带正确 saveId）──

    @Test
    fun `Q1 - 空闲入队保存 id4 待传 L4 C 不变`() = queueTest {
        repeat(3) {
            val id = ledger.recordLocalSave()
            ledger.recordCloudConfirmed(id)
        }
        val queue = newQueue()
        val events = collectEvents(queue)
        backend.nextResponses.add(SaveBackendResult.Failure(SaveBackendError.NETWORK, "held"))
        queue.enqueue(saveData) // 派发 id=4
        advanceTimeBy(config.debounceMs + 50)

        assertEquals(listOf(4L), backend.uploadSaveIds)
        assertEquals(4L, ledger.lastLocalSaveId())
        assertEquals(3L, ledger.lastConfirmedCloudId()) // 失败只降级，C 不动（IN1）
        val failure = events.filterIsInstance<UploadQueue.Event.UploadFailed>().first()
        assertTrue(failure.willRetry)
    }

    // ── Q2：400001 冷却 → 退避重排，L/C 不变 ──

    @Test
    fun `Q2 - 限频400001 按共享冷却退避重试不改 L C`() = queueTest {
        val queue = newQueue()
        val id = ledger.recordLocalSave() // L=1, pending=1
        backend.nextResponses.add(SaveBackendResult.Failure(SaveBackendError.RATE_LIMITED, "[400001] cooldown"))
        backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(id)))
        queue.enqueue(saveData, id)
        advanceTimeBy(config.debounceMs)
        advanceUntilIdle()

        // 第 1 次失败后按共享冷却（非指数）重试成功
        assertEquals(2, backend.uploadCount)
        assertEquals(listOf(1L, 1L), backend.uploadSaveIds)
        assertEquals(id, ledger.lastConfirmedCloudId())
        assertEquals(0L, ledger.pendingSaveId())
    }

    // ── Q3：400006 令牌失效 → 重试成功，L/C 不变直至确认 ──

    @Test
    fun `Q3 - 令牌失效400006 指数退避重试成功`() = queueTest {
        val queue = newQueue()
        val id = ledger.recordLocalSave()
        backend.nextResponses.add(SaveBackendResult.Failure(SaveBackendError.TOKEN_EXPIRED, "[400006] token"))
        backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(id)))
        queue.enqueue(saveData, id)
        advanceTimeBy(config.debounceMs)
        advanceUntilIdle()

        assertEquals(2, backend.uploadCount)
        assertEquals(id, ledger.lastConfirmedCloudId())
    }

    // ── Q4：400007 并发 → 单飞串行重试（无并发上传）──

    @Test
    fun `Q4 - 并发400007 单飞串行重试且无并发上传`() = queueTest {
        val queue = newQueue()
        val id = ledger.recordLocalSave()
        backend.nextResponses.add(SaveBackendResult.Failure(SaveBackendError.CONCURRENT, "[400007] concurrent"))
        backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(id)))
        queue.enqueue(saveData, id)
        advanceUntilIdle()

        assertEquals(2, backend.uploadCount)
        // 单飞：全程至多 1 个在途上传
        assertEquals(1, backend.maxConcurrentUploads)
        assertEquals(id, ledger.lastConfirmedCloudId())
    }

    // ── Q5：上传成功 → C 推进出队，确认事件 ──

    @Test
    fun `Q5 - 上传成功确认 C 推进出队转净`() = queueTest {
        val queue = newQueue()
        val id = ledger.recordLocalSave()
        backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(id)))
        val events = collectEvents(queue)
        queue.enqueue(saveData, id)
        advanceUntilIdle()

        assertEquals(id, ledger.lastConfirmedCloudId())
        assertEquals(0L, ledger.pendingSaveId())
        assertFalse(ledger.isLocalDirty())
        val confirmed = events.filterIsInstance<UploadQueue.Event.UploadConfirmed>().first()
        assertEquals(id, confirmed.saveId)
    }

    // ── Q6：上传成功但确认未落地（进程被杀）→ 重启后同 id 幂等重传收敛 ──

    @Test
    fun `Q6 - 确认丢失重启后同id幂等重传收敛不误报冲突`() = queueTest {
        // "被杀前"：MMKV 已留 L=4 / pending=4 / C=3（上传成功但确认未写入）
        store.putLong("cloud_upload_ledger_last_local", 4L)
        store.putLong("cloud_upload_ledger_pending", 4L)
        store.putLong("cloud_upload_ledger_last_confirmed", 3L)

        // 重启后：新账本实例 + 新队列，按恢复配方以**同一 saveId** 重入队（W==L 幂等覆盖）
        val revivedLedger = UploadLedger(store)
        backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(4L)))
        val queue = UploadQueue(backend, revivedLedger, workerScope(), config)
        queue.enqueue(saveData, revivedLedger.pendingSaveId())
        advanceUntilIdle()

        assertEquals(listOf(4L), backend.uploadSaveIds)
        assertEquals(4L, revivedLedger.lastConfirmedCloudId())
        assertEquals(0L, revivedLedger.pendingSaveId())
    }

    // ── Q7：窗口内 3 次保存 → 合并为最新 id 一次上传 ──

    @Test
    fun `Q7 - 窗口内三次保存合并只传最新`() = queueTest {
        val queue = newQueue()
        repeat(3) { ledger.recordLocalSave() } // L=1/2/3，最终待传 3
        val id3 = ledger.lastLocalSaveId()
        backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(id3)))
        // 三次本地保存依次入队（最新覆盖 = 窗口合并数据面）
        queue.enqueue(saveData, id3 - 2)
        queue.enqueue(saveData, id3 - 1)
        queue.enqueue(saveData, id3)
        advanceTimeBy(config.debounceMs)
        advanceUntilIdle()

        assertEquals(1, backend.uploadCount)
        assertEquals(listOf(id3), backend.uploadSaveIds)
        assertEquals(id3, ledger.lastLocalSaveId())
        assertEquals(id3, ledger.lastConfirmedCloudId())
    }

    // ── Q8：待传中手动保存再次触发 → 合并为一次快照 ──

    @Test
    fun `Q8 - 待传中手动保存合并为一次上传`() = queueTest {
        val queue = newQueue()
        val id4 = ledger.recordLocalSave()
        queue.enqueue(saveData, id4)
        advanceTimeBy(config.debounceMs / 2) // 窗口中途"手动保存"
        val id5 = ledger.recordLocalSave()
        queue.enqueue(saveData, id5)
        backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(id5)))
        advanceTimeBy(config.debounceMs)
        advanceUntilIdle()

        assertEquals(1, backend.uploadCount)
        assertEquals(listOf(id5), backend.uploadSaveIds)
        assertEquals(id5, ledger.lastConfirmedCloudId())
    }

    // ── Q11/Q12（SR-4）：requestDrain 排空尝试 ──

    @Test
    fun `Q11 - 排空请求跳过合并窗立即试传`() = queueTest {
        val queue = newQueue()
        val id4 = ledger.recordLocalSave()
        backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(id4)))
        queue.enqueue(saveData, id4)
        queue.requestDrain()
        // 远小于 debounceMs 的时间片即应完成上传（不排空时此刻仍卡在窗内）
        advanceTimeBy(config.debounceMs / 10)
        advanceUntilIdle()

        assertEquals(1, backend.uploadCount)
        assertEquals(listOf(id4), backend.uploadSaveIds)
        assertEquals(id4, ledger.lastConfirmedCloudId())
    }

    @Test
    fun `Q11b - 不排空时同窗内尚未上传（对照组）`() = queueTest {
        val queue = newQueue()
        val id4 = ledger.recordLocalSave()
        backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(id4)))
        queue.enqueue(saveData, id4)

        advanceTimeBy(config.debounceMs / 10)

        assertEquals("窗未到期不得上传", 0, backend.uploadCount)
    }

    @Test
    fun `Q12 - 空队列排空尝试零上传零账本变化`() = queueTest {
        val queue = newQueue()
        queue.requestDrain()

        advanceTimeBy(config.debounceMs * 5)
        advanceUntilIdle()

        assertEquals(0, backend.uploadCount)
        assertEquals(0L, ledger.lastLocalSaveId())
        assertEquals(0L, ledger.lastConfirmedCloudId())
    }

    /**
     * Q13（SR-6）：稳态上传节奏 —— **打开生产上传的前提证据**。
     *
     * 单飞循环是 `窗 → 上传 → 成功后 delay(冷却)`，故两次成功上传的间隔恒
     * `≥ debounce + uploadTime + sharedUploadCooldown`，本用例配置下 ≈ 1.1s（生产 = 62s），
     * 已经贴住 TapTap 1 次/分钟。把窗再放宽到 60s 只会让快照更旧、每分钟上传次数更少，
     * 不减少任何请求 ⇒ 参数保持原值，本用例把这条节奏钉住，防后续批凭直觉再调。
     */
    @Test
    fun `Q13 - 入队节奏远高于限频时两次成功上传仍被冷却拉开`() = queueTest {
        val queue = newQueue()
        collectEvents(queue)
        repeat(4) { backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(it + 1L))) }

        repeat(6) { i ->
            queue.enqueue(saveData, saveId = (i + 1).toLong())
            advanceTimeBy(60) // 密集入队（等比缩小）
        }
        assertEquals("冷却未到期不得出现第二次上传", 1, backend.uploadCount)

        advanceTimeBy(config.sharedUploadCooldownMs + config.debounceMs + 100)
        advanceUntilIdle()
        assertTrue("冷却到期后应继续把最新快照推出去", backend.uploadCount >= 2)
    }

    // ── Q9：连续失败达上限 → 熔断（如实告警）+ 静默后半开 ──

    @Test
    fun `Q9 - 连续失败达上限熔断告警后半开重试`() = queueTest {
        val queue = newQueue()
        val id = ledger.recordLocalSave()
        repeat(config.maxConsecutiveFailures) {
            backend.nextResponses.add(SaveBackendResult.Failure(SaveBackendError.NETWORK, "down"))
        }
        backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(id)))
        val events = collectEvents(queue)
        queue.enqueue(saveData, id)
        advanceTimeBy(config.debounceMs + 50)
        // 窗过全部退避重试（4 次退避覆盖）直到第 5 次失败熔断
        repeat(config.maxConsecutiveFailures - 1) { advanceTimeBy(config.maxRetryDelayMs) }
        val opened = events.filterIsInstance<UploadQueue.Event.CircuitOpened>().firstOrNull()
        assertEquals(config.maxConsecutiveFailures, opened?.consecutiveFailures)
        // 半开静默结束后下一次尝试成功
        advanceTimeBy(config.circuitOpenMs)
        advanceUntilIdle()
        assertEquals(id, ledger.lastConfirmedCloudId())
        assertTrue(backend.uploadCount >= config.maxConsecutiveFailures + 1)
    }

    // ── Q10：冲突待决 → 不自动上传，冲突 UI 优先 ──

    @Test
    fun `Q10 - 冲突待决不自动上传挂起等待玩家选择`() = queueTest {
        val queue = newQueue()
        val id = ledger.recordLocalSave() // L=1, C=0
        // 云端有另一端新档 W=5 → arbitrate(1, 0, 5) = CONFLICT
        backend.nextCloudSaveIds.add(5L)
        val events = collectEvents(queue)
        queue.enqueue(saveData, id)
        advanceTimeBy(config.debounceMs)
        advanceUntilIdle()

        // 关键正确性（S10）：自动存不静默上传覆盖
        assertEquals(0, backend.uploadCount)
        assertTrue(queue.hasHeldConflict())
        val held = events.filterIsInstance<UploadQueue.Event.ConflictHeld>().first()
        assertEquals(5L, held.conflict.cloudSaveId)

        // 玩家选本地 → 恢复上传
        backend.nextCloudSaveIds.add(5L)
        backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(id)))
        queue.resolveConflict(keepLocal = true)
        advanceTimeBy(config.debounceMs)
        advanceUntilIdle()
        assertEquals(1, backend.uploadCount)
        assertEquals(id, ledger.lastConfirmedCloudId())
    }

    @Test
    fun `Q10b - 玩家选云档 本地待传丢弃账本基线收敛（IN2 序号语义）`() = queueTest {
        val queue = newQueue()
        val id = ledger.recordLocalSave()
        backend.nextCloudSaveIds.add(5L)
        collectEvents(queue)
        queue.enqueue(saveData, id)
        advanceTimeBy(config.debounceMs)
        advanceUntilIdle()
        assertTrue(queue.hasHeldConflict())

        queue.resolveConflict(keepLocal = false)
        advanceUntilIdle()
        assertEquals(0, backend.uploadCount)
        assertEquals(5L, ledger.lastLocalSaveId())
        assertEquals(5L, ledger.lastConfirmedCloudId())
        assertFalse(ledger.isLocalDirty())
    }

    // ── W>C 防覆盖护栏（D-5 硬门：绝不静默覆盖云端）──

    @Test
    fun `W大于C护栏 - 本地净且云端更新 不得自动上传（LOCAL_BEHIND 降级挂起）`() = queueTest {
        // L=C=1（本地净）+ 显式 saveId=2 入队（>C 通过幂等闸）+ 云端 W=5（另一端新进度）
        val id = ledger.recordLocalSave()
        ledger.recordCloudConfirmed(id)
        backend.nextCloudSaveIds.add(5L)
        val queue = newQueue()
        val events = collectEvents(queue)
        queue.enqueue(saveData, saveId = 2L)
        advanceTimeBy(config.debounceMs)
        advanceUntilIdle()

        // 降级路径零写调用：不上传（不覆盖云端）、账本基线不被推高
        assertEquals("W>C 时自动上传被禁止", 0, backend.uploadCount)
        assertTrue(queue.hasHeldConflict())
        val held = events.filterIsInstance<UploadQueue.Event.ConflictHeld>().first()
        assertEquals(5L, held.conflict.cloudSaveId)
        assertEquals("账本 L 不得被挂起路径改写", 1L, ledger.lastLocalSaveId())
        assertEquals("账本 C 不得被挂起路径改写", 1L, ledger.lastConfirmedCloudId())
    }

    @Test
    fun `W大于C护栏 - 降级后玩家选云 基线收敛且零上传`() = queueTest {
        val id = ledger.recordLocalSave()
        ledger.recordCloudConfirmed(id)
        backend.nextCloudSaveIds.add(5L)
        val queue = newQueue()
        collectEvents(queue)
        queue.enqueue(saveData, saveId = 2L)
        advanceTimeBy(config.debounceMs)
        advanceUntilIdle()
        assertTrue(queue.hasHeldConflict())

        queue.resolveConflict(keepLocal = false)
        advanceUntilIdle()
        assertEquals("玩家选云 = 本地待传丢弃，零上传", 0, backend.uploadCount)
        assertEquals(5L, ledger.lastLocalSaveId())
        assertEquals(5L, ledger.lastConfirmedCloudId())
        assertFalse(ledger.isLocalDirty())
    }

    @Test
    fun `W大于C护栏 - 降级后玩家选本地 显式授权越过闸上传`() = queueTest {
        val id = ledger.recordLocalSave()
        ledger.recordCloudConfirmed(id)
        backend.nextCloudSaveIds.add(5L)
        val queue = newQueue()
        collectEvents(queue)
        queue.enqueue(saveData, saveId = 2L)
        advanceTimeBy(config.debounceMs)
        advanceUntilIdle()
        assertEquals(0, backend.uploadCount)

        // 显式选择 ≠ 静默覆盖：授权后放行
        backend.nextCloudSaveIds.add(5L)
        backend.nextResponses.add(SaveBackendResult.Success(UploadReceipt(2L)))
        queue.resolveConflict(keepLocal = true)
        advanceTimeBy(config.debounceMs)
        advanceUntilIdle()
        assertEquals(1, backend.uploadCount)
        assertEquals(listOf(2L), backend.uploadSaveIds)
    }

    // ── 幂等闸：已确认 saveId 重放入队直接跳过（Q6 收敛面）──

    @Test
    fun `幂等 - 已确认序号重放入队跳过不产生上传`() = queueTest {
        val queue = newQueue()
        val id = ledger.recordLocalSave()
        ledger.recordCloudConfirmed(id)
        queue.enqueue(saveData, id)
        advanceUntilIdle()
        assertEquals(0, backend.uploadCount)
    }

    // ── 测试基建 ──

    private fun TestScope.collectEvents(queue: UploadQueue): MutableList<UploadQueue.Event> {
        val list = mutableListOf<UploadQueue.Event>()
        // 收集器须与 worker 同在调度器驱动的作用域——backgroundScope 不被 advance* 推进，
        // 挂在上面的收集器会让 SharedFlow 无订阅者而丢弃事件
        workerScope().launch { queue.events.collect { list.add(it) } }
        return list
    }

    /** 可编程假后端：按序消费响应；记录上传请求序号与并发面 */
    private class FakeBackend : SaveBackend {
        val nextResponses = ArrayDeque<SaveBackendResult<UploadReceipt>>()
        val nextCloudSaveIds = ArrayDeque<Long>()
        val uploadSaveIds = mutableListOf<Long>()
        var uploadCount = 0
            private set
        private var inFlight = 0
        var maxConcurrentUploads = 0
            private set

        override val conflicts = MutableSharedFlow<SaveConflictEvent>()

        override suspend fun upload(saveData: SaveData, saveId: Long): SaveBackendResult<UploadReceipt> {
            uploadSaveIds.add(saveId)
            uploadCount++
            inFlight++
            maxConcurrentUploads = maxOf(maxConcurrentUploads, inFlight)
            try {
                return if (nextResponses.isEmpty()) {
                    SaveBackendResult.Failure(SaveBackendError.UNKNOWN, "no scripted response")
                } else {
                    nextResponses.removeFirst()
                }
            } finally {
                inFlight--
            }
        }

        override suspend fun download(): SaveBackendResult<CloudSavePayload> =
            SaveBackendResult.Failure(SaveBackendError.ARCHIVE_MISSING, "not used in queue tests")

        override suspend fun currentCloudSaveId(): SaveBackendResult<Long?> =
            if (nextCloudSaveIds.isEmpty()) {
                SaveBackendResult.Success(null)
            } else {
                SaveBackendResult.Success(nextCloudSaveIds.removeFirst())
            }
    }
}
