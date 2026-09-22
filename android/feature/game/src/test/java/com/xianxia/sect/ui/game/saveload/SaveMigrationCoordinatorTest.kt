package com.xianxia.sect.ui.game.saveload

import android.util.Log
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.util.AnalyticsEvents
import com.xianxia.sect.data.cloud.ArbitrationVerdict
import com.xianxia.sect.data.cloud.CloudSaveEntry
import com.xianxia.sect.data.cloud.CloudSavePayload
import com.xianxia.sect.data.cloud.MigrationSlotState
import com.xianxia.sect.data.cloud.SaveBackend
import com.xianxia.sect.data.cloud.SaveBackendError
import com.xianxia.sect.data.cloud.SaveBackendMode
import com.xianxia.sect.data.cloud.SaveBackendModeProvider
import com.xianxia.sect.data.cloud.SaveBackendResult
import com.xianxia.sect.data.cloud.SaveConflictEvent
import com.xianxia.sect.data.cloud.SaveMigrationLedger
import com.xianxia.sect.data.cloud.UploadLedger
import com.xianxia.sect.data.cloud.UploadQueue
import com.xianxia.sect.data.crypto.SavePayloadIntegrity
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.unified.SaveResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 迁移引导协调器单测（SR-6 C5+C6）。
 *
 * 三组红线各自锚定：
 * - **LEGACY 零自动云动作**（用例 1/2）：默认模式扫描不发云列表请求、不入队、不升档；
 *   续传只在非 LEGACY 下自动跑（勘察 F1 的缺失调用者）；
 * - **邮件同源**（勘察 F2）：上传快照必须带 `getMailsForSlot` 的现值，读邮件失败即中止；
 * - **禁止静默覆盖 + 玩家确认式升档**（§7 S1）：矩阵判待裁决的槽本轮不投递；
 *   `confirmEnableCloud` 只在全员收口后写 `CLOUD_TRANSITION`。
 *
 * `CloudSaveCacheWriter` 用**真实实例**（吃同一批 mock）——跨槽"不抄序号"等语义由它自己
 * 的用例锚定，这里证的是协调器如何调它。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveMigrationCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()

    private val storageFacade: StorageFacade = mockk(relaxed = true)
    private val saveBackend: SaveBackend = mockk(relaxed = true)
    private val uploadQueue: UploadQueue = mockk(relaxed = true)
    private val uploadLedger: UploadLedger = mockk(relaxed = true)
    private val migrationLedger: SaveMigrationLedger = mockk(relaxed = true)
    private val modeProvider: SaveBackendModeProvider = mockk()
    private val analytics: com.xianxia.sect.core.util.AnalyticsTracker = mockk(relaxed = true)
    private val queueEvents = MutableSharedFlow<UploadQueue.Event>(extraBufferCapacity = 16)

    private var mode = SaveBackendMode.LEGACY
    private var localSlots: List<SaveSlot> = emptyList()
    private var slotStates: Map<Int, MigrationSlotState> = emptyMap()

    private lateinit var coordinator: SaveMigrationCoordinator

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0

        mode = SaveBackendMode.LEGACY
        localSlots = emptyList()
        slotStates = emptyMap()
        every { modeProvider.current() } answers { mode }
        every { modeProvider.set(any()) } just Runs
        // 阶段 B 的默认云态 = 云端空（各用例按需覆写；不覆写时不应把 Failure/异常当输入）
        coEvery { saveBackend.list() } returns SaveBackendResult.Success<List<CloudSaveEntry>>(emptyList())
        every { migrationLedger.state(any()) } answers { slotStates[firstArg()] ?: MigrationSlotState.NONE }
        // 记账 mock 带状态迁移：否则"确认到账后行转 MIGRATED"这类断言只能证到调用，证不到态
        every { migrationLedger.markUploaded(any()) } answers { mark(firstArg(), MigrationSlotState.UPLOADED) }
        every { migrationLedger.markCloudPreferred(any()) } answers {
            mark(firstArg(), MigrationSlotState.CLOUD_PREFERRED)
        }
        every { migrationLedger.markQueued(any()) } answers { mark(firstArg(), MigrationSlotState.QUEUED) }
        coEvery { storageFacade.getSaveSlotsSuspend() } answers { localSlots }
        every { uploadQueue.events } returns queueEvents
        coEvery { uploadQueue.heldConflictSlots() } returns emptySet()
        coEvery { storageFacade.getMailsForSlot(any()) } returns emptyList()
        coEvery { storageFacade.save(any(), any()) } returns SaveResult.success(Unit)

        coordinator = SaveMigrationCoordinator(
            storageFacade = storageFacade,
            saveBackend = saveBackend,
            uploadQueue = uploadQueue,
            uploadLedger = uploadLedger,
            migrationLedger = migrationLedger,
            modeProvider = modeProvider,
            cacheWriter = CloudSaveCacheWriter(saveBackend, storageFacade, uploadLedger),
            analytics = analytics,
            ioDispatcher = IoDispatcher(testDispatcher)
        )
    }

    @After
    fun tearDown() = unmockkAll()

    // ── 夹具 ──

    private fun slotView(slot: Int, empty: Boolean = false, loadError: Boolean = false) = SaveSlot(
        slot = slot,
        name = "槽$slot",
        timestamp = 0L,
        gameYear = 12,
        gameMonth = 3,
        sectName = "青云宗",
        discipleCount = 5,
        spiritStones = 100L,
        isEmpty = empty,
        isLoadError = loadError
    )

    private fun cloudEntry(slot: Int, saveId: Long?) = CloudSaveEntry(
        slot = slot,
        archiveName = "slot_$slot",
        saveId = saveId,
        sizeBytes = 1_024L,
        modifiedTimeMs = 0L,
        summary = null
    )

    private fun localSave() = SaveData(
        gameData = GameData(sectName = "青云宗", saveVersion = SAVE_VERSION),
        disciples = emptyList(),
        pills = emptyList(),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList()
    )

    private fun stubLocalSave(slot: Int) {
        coEvery { storageFacade.load(slot) } returns SaveResult.success(localSave())
    }

    private fun rowFor(slot: Int): MigrationSlotRow? = coordinator.state.value.rows.firstOrNull { it.slot == slot }

    private fun mark(slot: Int, state: MigrationSlotState) {
        slotStates = slotStates + (slot to state)
    }

    private fun payload(saveId: Long?, verdict: ArbitrationVerdict, integrity: SavePayloadIntegrity) =
        SaveBackendResult.Success(CloudSavePayload(localSave(), saveId, verdict, integrity))

    private fun capturedEnqueue(): Pair<Int, SaveData> {
        val slotArg = slot<Int>()
        val dataArg = slot<SaveData>()
        coVerify { uploadQueue.enqueue(capture(slotArg), capture(dataArg), any()) }
        return slotArg.captured to dataArg.captured
    }

    // ── LEGACY 红线 + 续传（勘察 F1）──

    @Test
    fun `LEGACY 扫描 - 零云请求零入队，只列清单`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(1))
        advanceUntilIdle()

        coordinator.scan()
        advanceUntilIdle()

        coVerify(exactly = 0) { saveBackend.list() }
        coVerify(exactly = 0) { uploadQueue.enqueue(any(), any(), any()) }
        assertEquals(SlotMigrationStatus.AWAITING_UPLOAD, rowFor(1)?.status)
        assertEquals(1, coordinator.state.value.pendingTotal)
        assertTrue(coordinator.state.value.visible)
        assertEquals(MigrationPhase.READY, coordinator.state.value.phase)
    }

    @Test
    fun `非 LEGACY 扫描 - 未确认待传按同 id 续传（F1 的缺失调用者）`() = runTest(testDispatcher) {
        mode = SaveBackendMode.CLOUD_TRANSITION
        localSlots = listOf(slotView(2))
        stubLocalSave(2)
        every { uploadLedger.pendingSaveId(2) } returns 4L
        every { uploadLedger.lastConfirmedCloudId(2) } returns 3L
        advanceUntilIdle()

        coordinator.scan()
        advanceUntilIdle()

        coVerify { uploadQueue.enqueue(2, any(), 4L) }
        verify { migrationLedger.markQueued(2) }
    }

    @Test
    fun `LEGACY 扫描 - 即使有待传指针也不自动入队`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(3))
        every { uploadLedger.pendingSaveId(3) } returns 9L
        every { uploadLedger.lastConfirmedCloudId(3) } returns 0L
        advanceUntilIdle()

        coordinator.scan()
        advanceUntilIdle()

        coVerify(exactly = 0) { uploadQueue.enqueue(any(), any(), any()) }
    }

    // ── 邮件同源（勘察 F2）──

    @Test
    fun `迁移上传自带邮件快照 - 与保存编排同源`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(1))
        stubLocalSave(1)
        val mails = listOf(MailEntity(id = "mail-1", slotId = 1))
        coEvery { storageFacade.getMailsForSlot(1) } returns mails
        advanceUntilIdle()

        coordinator.start()
        advanceUntilIdle()

        coVerify { storageFacade.getMailsForSlot(1) }
        assertEquals(mails, capturedEnqueue().second.mails)
    }

    @Test
    fun `邮件快照读失败 - 中止该槽上传并如实标失败，不上传缺邮件的档`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(1))
        stubLocalSave(1)
        coEvery { storageFacade.getMailsForSlot(1) } throws IllegalStateException("db down")
        advanceUntilIdle()

        coordinator.scan()
        coordinator.start()
        advanceUntilIdle()

        coVerify(exactly = 0) { uploadQueue.enqueue(any(), any(), any()) }
        assertEquals(SlotMigrationStatus.UPLOAD_FAILED, rowFor(1)?.status)
    }

    // ── 阶段 B：矩阵派发 ──

    @Test
    fun `云列表失败 - 如实 SERVICE_UNAVAILABLE 并带回原因`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(1))
        coEvery { saveBackend.list() } returns SaveBackendResult.Failure(
            SaveBackendError.NETWORK,
            "未连接"
        )
        advanceUntilIdle()

        coordinator.start()
        advanceUntilIdle()

        assertEquals(MigrationPhase.SERVICE_UNAVAILABLE, coordinator.state.value.phase)
        assertEquals("云存档服务不可达：未连接", coordinator.state.value.notice)
        coVerify(exactly = 0) { uploadQueue.enqueue(any(), any(), any()) }
    }

    @Test
    fun `矩阵四格并行 - 只有该上传的槽被投递`() = runTest(testDispatcher) {
        // 槽 1 本地有×云无 → 上传；槽 2 本地无×云有 → 用云档；
        // 槽 3 本地有×云有(无 saveId) → 待裁决（F12 前置拦截，不静默覆盖）；槽 4 损坏 → 阻断
        localSlots = listOf(slotView(1), slotView(2, empty = true), slotView(3), slotView(4, loadError = true))
        stubLocalSave(1)
        coEvery { saveBackend.list() } returns SaveBackendResult.Success(
            listOf(cloudEntry(2, saveId = 6L), cloudEntry(3, saveId = null))
        )
        advanceUntilIdle()

        coordinator.start()
        advanceUntilIdle()

        coVerify(exactly = 1) { uploadQueue.enqueue(any(), any(), any()) }
        coVerify { uploadQueue.enqueue(1, any(), any()) }
        assertEquals(SlotMigrationStatus.CLOUD_ONLY, rowFor(2)?.status)
        assertEquals(SlotMigrationStatus.NEEDS_DECISION, rowFor(3)?.status)
        assertEquals(SlotMigrationStatus.BLOCKED_CORRUPT, rowFor(4)?.status)
        assertTrue(coordinator.state.value.legacyArchivePresent.not())
    }

    @Test
    fun `存量单档在场时被单独识别，不混进逐槽判定`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(1))
        coEvery { saveBackend.list() } returns SaveBackendResult.Success(
            listOf(cloudEntry(0, saveId = null))
        )
        advanceUntilIdle()

        coordinator.start()
        advanceUntilIdle()

        assertTrue(coordinator.state.value.legacyArchivePresent)
        coVerify(exactly = 0) { saveBackend.download(any()) }
    }

    // ── 事件收敛与升档 ──

    @Test
    fun `上传确认到账 - 记 UPLOADED 且全员收口后才允许升档`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(1))
        stubLocalSave(1)
        advanceUntilIdle()

        coordinator.scan()
        coordinator.start()
        advanceUntilIdle()
        queueEvents.tryEmit(UploadQueue.Event.UploadConfirmed(slot = 1, saveId = 1L))
        advanceUntilIdle()

        verify { migrationLedger.markUploaded(1) }
        assertEquals(SlotMigrationStatus.MIGRATED, rowFor(1)?.status)
        assertTrue(coordinator.state.value.canEnableCloudSave)
        assertEquals(MigrationPhase.DONE, coordinator.state.value.phase)
    }

    @Test
    fun `收口时上报一次完成率指标，同阶段重复刷新不重复上报（SR-6 完成率定义）`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(1))
        stubLocalSave(1)
        advanceUntilIdle()

        coordinator.scan()
        coordinator.start()
        advanceUntilIdle()
        queueEvents.tryEmit(UploadQueue.Event.UploadConfirmed(slot = 1, saveId = 1L))
        advanceUntilIdle()
        coordinator.scan()
        advanceUntilIdle()

        val props = slot<Map<String, Any>>()
        verify(exactly = 1) {
            analytics.trackEvent(eq(AnalyticsEvents.SAVE_MIGRATION_RESULT), capture(props))
        }
        assertEquals(1, props.captured[AnalyticsEvents.PROP_MIGRATION_MIGRATED_TOTAL])
        assertEquals(0, props.captured[AnalyticsEvents.PROP_MIGRATION_PENDING_TOTAL])
        assertEquals("LEGACY", props.captured[AnalyticsEvents.PROP_MIGRATION_MODE_AFTER])
    }

    @Test
    fun `仍有未收口槽时 confirmEnableCloud 拒绝写模式`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(1))
        advanceUntilIdle()

        coordinator.scan()
        advanceUntilIdle()
        coordinator.confirmEnableCloud()

        assertFalse(coordinator.state.value.canEnableCloudSave)
        verify(exactly = 0) { modeProvider.set(any()) }
    }

    @Test
    fun `玩家确认后写入 CLOUD_TRANSITION - 本批唯一的模式写入点`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(1))
        slotStates = mapOf(1 to MigrationSlotState.UPLOADED)
        advanceUntilIdle()

        coordinator.scan()
        advanceUntilIdle()
        coordinator.confirmEnableCloud()

        verify { modeProvider.set(SaveBackendMode.CLOUD_TRANSITION) }
    }

    @Test
    fun `瞬时失败不打扰玩家 - 行内保持上云中，队列自行退避`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(1))
        stubLocalSave(1)
        advanceUntilIdle()

        coordinator.start()
        advanceUntilIdle()
        queueEvents.tryEmit(
            UploadQueue.Event.UploadFailed(1, 1L, SaveBackendError.RATE_LIMITED, "限频", willRetry = true)
        )
        advanceUntilIdle()

        assertEquals(SlotMigrationStatus.UPLOADING, rowFor(1)?.status)
    }

    @Test
    fun `队列挂起的冲突事件转为行内待裁决并带回序号文案`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(1))
        coordinator.scan()
        advanceUntilIdle()

        queueEvents.tryEmit(
            UploadQueue.Event.ConflictHeld(
                1,
                SaveConflictEvent(
                    slot = 1,
                    lastLocalSaveId = 5L,
                    lastConfirmedCloudId = 3L,
                    cloudSaveId = 7L,
                    source = "upload"
                )
            )
        )
        advanceUntilIdle()

        val row = rowFor(1)
        assertEquals(SlotMigrationStatus.NEEDS_DECISION, row?.status)
        assertTrue(row?.detail?.contains("云端已有第 7 次保存") == true)
    }

    // ── 二选一与存量单档落地 ──

    @Test
    fun `待裁决选本机 - 显式授权后投递`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(3))
        stubLocalSave(3)
        coEvery { saveBackend.list() } returns SaveBackendResult.Success(
            listOf(cloudEntry(3, saveId = null))
        )
        advanceUntilIdle()

        coordinator.start()
        advanceUntilIdle()
        coordinator.resolveConflict(3, keepLocal = true)
        advanceUntilIdle()

        coVerify { uploadQueue.enqueue(3, any(), any()) }
        coVerify(exactly = 0) { saveBackend.download(3) }
    }

    @Test
    fun `待裁决选云端 - 走落盘段覆盖本机缓存并记 CLOUD_PREFERRED`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(3))
        coEvery { saveBackend.download(3) } returns
            payload(saveId = 9L, verdict = ArbitrationVerdict.LOCAL_BEHIND, integrity = SavePayloadIntegrity.VERIFIED)
        advanceUntilIdle()

        coordinator.resolveConflict(3, keepLocal = false)
        advanceUntilIdle()

        coVerify { storageFacade.save(3, any()) }
        verify { uploadLedger.adoptCloudState(3, 9L) }
        verify { migrationLedger.markCloudPreferred(3) }
        coVerify(exactly = 0) { uploadQueue.enqueue(any(), any(), any()) }
    }

    @Test
    fun `存量单档迁到选定空槽 - 落目标槽且记 CLOUD_PREFERRED`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(5, empty = true))
        coEvery { saveBackend.download(0) } returns
            payload(saveId = null, verdict = ArbitrationVerdict.IN_SYNC, integrity = SavePayloadIntegrity.UNSIGNED)
        advanceUntilIdle()

        coordinator.migrateLegacyArchive(targetSlot = 5)
        advanceUntilIdle()

        coVerify { storageFacade.save(5, any()) }
        verify { migrationLedger.markCloudPreferred(5) }
        // 跨槽迁移不抄源槽序号（目标槽从自己的第一次保存起算）
        verify(exactly = 0) { uploadLedger.adoptCloudState(any(), any()) }
    }

    @Test
    fun `落盘失败时不记 CLOUD_PREFERRED - 引导不谎报成功`() = runTest(testDispatcher) {
        localSlots = listOf(slotView(5, empty = true))
        coEvery { saveBackend.download(0) } returns SaveBackendResult.Failure(
            SaveBackendError.ARCHIVE_MISSING,
            "云存档不存在"
        )
        advanceUntilIdle()

        coordinator.migrateLegacyArchive(targetSlot = 5)
        advanceUntilIdle()

        verify(exactly = 0) { migrationLedger.markCloudPreferred(any()) }
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
    }

    private companion object {
        const val SAVE_VERSION = 2
    }
}
