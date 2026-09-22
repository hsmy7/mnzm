package com.xianxia.sect.ui.game.saveload

import android.util.Log
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.util.AnalyticsEvents
import com.xianxia.sect.core.util.AnalyticsTracker
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.cloud.CloudSaveEntry
import com.xianxia.sect.data.cloud.MigrationPlan
import com.xianxia.sect.data.cloud.SaveBackend
import com.xianxia.sect.data.cloud.SaveBackendMode
import com.xianxia.sect.data.cloud.SaveBackendModeProvider
import com.xianxia.sect.data.cloud.SaveBackendResult
import com.xianxia.sect.data.cloud.SaveMigrationLedger
import com.xianxia.sect.data.cloud.SaveMigrationPlanner
import com.xianxia.sect.data.cloud.SlotLedgerSnapshot
import com.xianxia.sect.data.cloud.SlotMigrationAction
import com.xianxia.sect.data.cloud.SlotMigrationInput
import com.xianxia.sect.data.cloud.UploadLedger
import com.xianxia.sect.data.cloud.UploadQueue
import com.xianxia.sect.data.cloud.UploadReason
import com.xianxia.sect.data.cloud.shouldEnqueueCloudUpload
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.unified.SaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 存量迁移引导协调器（SR-6，方案 §4 SR-6 + §2 模式开关收口）。
 *
 * ## 两阶段（LEGACY 红线的机制面，不是节流）
 * - **阶段 A [scan]**：纯本地（Room 槽位枚举 + MMKV 记账），**零云请求、零自动上传**。
 *   SR-3 主菜单云槽位卡在 LEGACY 下"短路零查询"是硬红线，本协调器同纪律：LEGACY 设备
 *   打开选档页不因本批多一次网络往返。
 * - **阶段 B [start]**：玩家显式点「开始迁移」之后才取云端列表、跑矩阵、投递队列——
 *   "引导"的本义就是玩家点头之后才动他的存档；矩阵的"云有档 / 冲突"两格也只有在此时点
 *   才有可判定的输入。
 *
 * ## 本批交付的实质工作
 * 1. **续传调用者**（勘察 F1）：[UploadLedger.pendingSaveId] 自 SR-2 落地后生产零消费者，
 *    其 KDoc 声称的"重启后以待传指针同 id 重入队"配方没有任何调用者 ⇒ [scan] 就是那个
 *    缺失的调用者，且只在 [shouldEnqueueCloudUpload] 为真（非 LEGACY）时自动执行——
 *    默认模式下零自动云动作，与 LEGACY 零行为变化红线不冲突；LEGACY 下这类槽由阶段 B
 *    的玩家显式动作收口；
 * 2. **矩阵驱动**：逐槽动作一律由 `core:data` 的纯函数 [SaveMigrationPlanner] 决定，
 *    本类不复制判据（四格逻辑单点，方案门用例在 core:data）；
 * 3. **邮件同源**（勘察 F2）：[loadForUpload] 与保存编排同源补 `getMailsForSlot`，
 *    取邮件失败即**中止该槽上传**——宁可失败也不上传缺邮件的档（云恢复是整对象替换回表）；
 * 4. **升档写入点**（[confirmEnableCloud]）：[SaveBackendModeProvider.set] 在 SR-2 就绪后的
 *    第一个生产调用者，且**由玩家确认触发**（施工卡 §7 S1）。`CLOUD_ONLY` 生产零写入，
 *    归 SR-7 且受 [SaveMigrationPlanner.canPromoteToCloudOnly] 门槛守卫。
 *
 * 跨槽迁移（存量单档 → 空槽）走 [CloudSaveCacheWriter]，与本类一样**不 boot 引擎**
 * （IN7 精神：引导不替玩家开局）。IN1：投递失败只降级为本行失败文案，不回滚已提交内容。
 * IN2：本类零时钟——读的全是序号与记账态。
 */
@Singleton
class SaveMigrationCoordinator @Inject constructor(
    private val storageFacade: StorageFacade,
    private val saveBackend: SaveBackend,
    private val uploadQueue: UploadQueue,
    private val uploadLedger: UploadLedger,
    private val migrationLedger: SaveMigrationLedger,
    private val modeProvider: SaveBackendModeProvider,
    private val cacheWriter: CloudSaveCacheWriter,
    private val analytics: AnalyticsTracker,
    private val ioDispatcher: IoDispatcher
) {

    private val _state = MutableStateFlow(MigrationUiState())

    /** 迁移卡数据源 */
    val state: StateFlow<MigrationUiState> = _state.asStateFlow()

    /**
     * 事件收集作用域（与 [UploadQueue] 同族：SupervisorJob + 注入 IO）。
     *
     * 收集器在构造期建立而非 `start()` 内 launch——队列事件是 replay=0 的 SharedFlow，
     * "先 enqueue 后订阅"会丢掉最早的确认。被动收集在 LEGACY 下零发射 ⇒ 零唤醒、零网络、
     * 零账本写（LEGACY 红线的机制面不变）。
     */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher.dispatcher)

    /** 本次引导的运行期即时态（不入 MMKV：入队中/失败/待裁决只对这一轮有语义） */
    private val overlays = ConcurrentHashMap<Int, MigrationOverlay>()

    private var slots: List<SaveSlot> = emptyList()
    private var cloudBySlot: Map<Int, CloudSaveEntry> = emptyMap()
    private var started = false
    private var notice: String? = null

    /** 指标边沿触发用：同一阶段只报一次，[start] 复位（一轮引导一条记录） */
    private var reportedPhase: MigrationPhase? = null

    init {
        scope.launch { uploadQueue.events.collect { handleEvent(it) } }
    }

    /**
     * 阶段 A：本地扫描（零云请求）+ 非 LEGACY 下的待传续传（F1）。
     *
     * 幂等，可反复调用（删槽、迁移收口、回到选档页都重建行表）。云端态在此**清空**——
     * 阶段 A 不查云，行表里出现"云端有档"只能是上一次阶段 B 的结果被误当成现值。
     */
    suspend fun scan() {
        val mode = modeProvider.current()
        slots = migratableSlots(storageFacade.getSaveSlotsSuspend())
        cloudBySlot = emptyMap()
        if (shouldEnqueueCloudUpload(mode)) {
            slots.filter { ledgerOf(it.slot).hasUnconfirmedPending }.forEach { slotView ->
                val pending = uploadLedger.pendingSaveId(slotView.slot)
                Log.i(TAG, "resume pending upload: slot=${slotView.slot} saveId=$pending（F1 续传调用者）")
                enqueueSlot(slotView.slot, saveId = pending)
            }
        }
        refresh()
        Log.i(TAG, "migration scan: mode=$mode rows=${_state.value.rows.size} pending=${_state.value.pendingTotal}")
    }

    /**
     * 阶段 B：玩家显式开始迁移。
     *
     * 云端列表拿不到 ⇒ 如实 [MigrationPhase.SERVICE_UNAVAILABLE] 并带回原因，**不**降级成
     * "没有要迁的档"——静默假装无档会让玩家以为进度已经安全（IN6/诚实红线）。
     */
    suspend fun start() {
        if (slots.isEmpty()) slots = migratableSlots(storageFacade.getSaveSlotsSuspend())
        val listed = saveBackend.list()
        if (listed is SaveBackendResult.Failure) {
            notice = "云存档服务不可达：${listed.message}"
            refresh(MigrationPhase.SERVICE_UNAVAILABLE)
            Log.w(TAG, "migration start aborted: list failed — ${listed.message}")
            return
        }
        cloudBySlot = (listed as SaveBackendResult.Success).data.associateBy { it.slot }
        started = true
        notice = null
        reportedPhase = null
        refresh(MigrationPhase.RUNNING)
        val inputs = slots.map { inputOf(it, cloudBySlot[it.slot]) }
        dispatch(SaveMigrationPlanner.plan(inputs, cloudBySlot[StorageConstants.CLOUD_SAVE_SLOT]))
        refresh()
    }

    /**
     * 冲突二选一收口（矩阵格"本地有 × 云有"，禁止静默覆盖）。
     *
     * 两条来源分流：队列已挂起该槽（上传前仲裁判 CONFLICT）⇒ 先交还队列授权/丢弃；
     * 尚未投递（[SlotMigrationAction.ResolveConflict] 的 F12 前置拦截）⇒ 本机为准走授权上传。
     * **两条"改用云端"的路径后果必须一致**：都把云端内容落回本机缓存
     * （[migrateCloudOverLocal]），否则行内文案与玩家实际拿到的进度不符。
     */
    suspend fun resolveConflict(slot: Int, keepLocal: Boolean) {
        if (slot in uploadQueue.heldConflictSlots()) {
            uploadQueue.resolveConflict(slot, keepLocal)
            if (!keepLocal) {
                // 队列侧的"选云"只做了丢弃待传 + 基线收敛（SR-2 Q10），本机 Room 缓存仍是
                // 分歧的那一份 ⇒ 必须把云端内容落回本机，否则行内文案与实际后果不符
                migrateCloudOverLocal(slot)
            }
        } else if (keepLocal) {
            overlays.remove(slot)
            enqueueSlot(slot, saveId = null)
        } else {
            migrateCloudOverLocal(slot)
        }
        refresh()
    }

    /** 以云端为准：下载覆盖本机缓存（不 boot），成功才记 `CLOUD_PREFERRED`，失败留在待裁决 */
    private suspend fun migrateCloudOverLocal(slot: Int) {
        when (val outcome = cacheWriter.downloadIntoCache(sourceSlot = slot, targetSlot = slot)) {
            is CloudSaveCacheWriter.Outcome.Written -> {
                migrationLedger.markCloudPreferred(slot)
                overlays.remove(slot)
            }
            is CloudSaveCacheWriter.Outcome.Rejected ->
                overlays[slot] = MigrationOverlay(SlotMigrationStatus.NEEDS_DECISION, outcome.message)
            CloudSaveCacheWriter.Outcome.ConflictPending ->
                overlays[slot] = MigrationOverlay(SlotMigrationStatus.NEEDS_DECISION, "云端仍在等待裁决")
        }
    }

    /**
     * 存量单档 `mnzm_cloud_save` → 玩家选定的空槽（SR-3 报告 §4.1 移交本批）。
     *
     * 成功后重跑 [scan]：该槽刚从"无档"变成"有档"，不重取本地快照迁移卡会继续显示它是空的。
     */
    suspend fun migrateLegacyArchive(targetSlot: Int) {
        when (cacheWriter.downloadIntoCache(StorageConstants.CLOUD_SAVE_SLOT, targetSlot)) {
            is CloudSaveCacheWriter.Outcome.Written -> {
                migrationLedger.markCloudPreferred(targetSlot)
                scan()
                return
            }
            is CloudSaveCacheWriter.Outcome.Rejected ->
                overlays[targetSlot] = MigrationOverlay(SlotMigrationStatus.UPLOAD_FAILED, "旧版云存档未能落到槽 $targetSlot")
            CloudSaveCacheWriter.Outcome.ConflictPending ->
                overlays[targetSlot] = MigrationOverlay(SlotMigrationStatus.NEEDS_DECISION, "旧版云存档待你裁决")
        }
        refresh()
    }

    /**
     * 「启用云存档」——本批唯一的模式写入点（施工卡 §7 S1：玩家确认式）。
     *
     * 判据取 [_state] 现值：状态只由本类 refresh，UI 读的就是同一份，不存在陈旧副本。
     * `CLOUD_ONLY` 不经这里（SR-7 批 + 门槛守卫）。
     */
    fun confirmEnableCloud() {
        if (!_state.value.canEnableCloudSave) {
            refresh(MigrationPhase.READY)
            Log.w(TAG, "cloud save enable refused: 仍有槽位未收口，不升档")
            return
        }
        modeProvider.set(SaveBackendMode.CLOUD_TRANSITION)
        Log.i(TAG, "cloud save mode = CLOUD_TRANSITION（玩家确认，SR-6 §2.4）")
        refresh()
    }

    /** 逐槽派发（矩阵输出 → 队列 / 行内待裁决） */
    private suspend fun dispatch(plan: MigrationPlan) {
        plan.slots.forEach { entry ->
            when (val action = entry.action) {
                is SlotMigrationAction.UploadLocal ->
                    enqueueSlot(entry.slot, saveId = action.reason.takeIf { it == UploadReason.RESUME_PENDING }
                        ?.let { uploadLedger.pendingSaveId(entry.slot) })
                is SlotMigrationAction.ResolveConflict ->
                    overlays[entry.slot] = MigrationOverlay(SlotMigrationStatus.NEEDS_DECISION)
                SlotMigrationAction.UseCloud ->
                    overlays[entry.slot] = MigrationOverlay(SlotMigrationStatus.CLOUD_ONLY)
                SlotMigrationAction.BlockedByLoadError ->
                    overlays[entry.slot] = MigrationOverlay(SlotMigrationStatus.BLOCKED_CORRUPT)
                SlotMigrationAction.NoAction -> Unit
            }
        }
    }

    /** 投递一个槽：读取（含邮件，F2）→ 入队 → 记账 QUEUED */
    // TooGenericExceptionCaught：防御兜底——异常源跨账本存储/队列/协程作用域不可枚举，
    // 降级为该行的失败文案并继续其他槽（IN1：不回滚已提交内容），非静默吞噬
    @Suppress("TooGenericExceptionCaught")
    private suspend fun enqueueSlot(slot: Int, saveId: Long?) {
        val data = loadForUpload(slot) ?: return
        overlays[slot] = MigrationOverlay(SlotMigrationStatus.UPLOADING)
        try {
            uploadQueue.enqueue(slot, data, saveId)
            migrationLedger.markQueued(slot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "migration enqueue failed: slot=$slot", e)
            overlays[slot] = MigrationOverlay(SlotMigrationStatus.UPLOAD_FAILED, "入队失败：${e.message}")
        }
    }

    /**
     * 读出可直接上云的本机快照（含邮件，F2）。
     *
     * TooGenericExceptionCaught：邮件快照跨 Room/DAO，异常源不可枚举；这里的目标是
     * "读不全就别上传"，一律降级为该槽失败并如实留痕，非静默吞噬。
     *
     * @return null = 该槽本轮不投递（读档失败或邮件快照失败），原因已写进行内
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadForUpload(slot: Int): SaveData? {
        val loaded = storageFacade.load(slot)
        val data = (loaded as? SaveResult.Success)?.data
        if (data == null) {
            val message = "读取本机存档失败：${(loaded as? SaveResult.Failure)?.message ?: "未知原因"}"
            Log.e(TAG, "migration load failed: slot=$slot — $message")
            overlays[slot] = MigrationOverlay(SlotMigrationStatus.UPLOAD_FAILED, message)
            return null
        }
        return try {
            data.copy(mails = storageFacade.getMailsForSlot(slot))
        } catch (e: CancellationException) {
            throw e // 取消穿透：不以"邮件缺失"冒充业务失败
        } catch (e: Exception) {
            // 邮件读不全就别上传——空表会在云恢复时把玩家邮件整表替换掉（勘察 F2）
            Log.e(TAG, "migration mails snapshot failed: slot=$slot", e)
            overlays[slot] = MigrationOverlay(
                SlotMigrationStatus.UPLOAD_FAILED,
                "邮件快照读取失败，已中止上云（避免上传缺邮件的档）"
            )
            null
        }
    }

    private fun handleEvent(event: UploadQueue.Event) {
        when (event) {
            is UploadQueue.Event.UploadConfirmed -> {
                migrationLedger.markUploaded(event.slot)
                overlays.remove(event.slot)
            }
            // 瞬时失败（willRetry=true）不打扰玩家：队列自己的退避在跑，行内保持"上云中"
            is UploadQueue.Event.UploadFailed -> if (!event.willRetry) {
                overlays[event.slot] = MigrationOverlay(
                    SlotMigrationStatus.UPLOAD_FAILED,
                    "上云失败：${event.message}"
                )
            }
            is UploadQueue.Event.ConflictHeld ->
                overlays[event.slot] = MigrationOverlay(
                    SlotMigrationStatus.NEEDS_DECISION,
                    "本机第 ${event.conflict.lastLocalSaveId} 次保存未上云，云端已有第 " +
                        "${event.conflict.cloudSaveId ?: 0L} 次保存"
                )
            is UploadQueue.Event.CircuitOpened ->
                Log.w(TAG, "upload circuit opened during migration: slot=${event.slot}")
        }
        refresh()
    }

    /** 重建界面态（行表 + 计数 + 阶段 + 升档可用性） */
    private fun refresh(forcedPhase: MigrationPhase? = null) {
        val mode = modeProvider.current()
        val rows = slots.mapNotNull { rowOf(it) }
        val pending = rows.count { it.status in PENDING_STATUSES }
        val phase = forcedPhase ?: phaseOf(rows, pending)
        _state.value = MigrationUiState(
            phase = phase,
            rows = rows,
            pendingTotal = pending,
            migratedTotal = rows.count { it.status == SlotMigrationStatus.MIGRATED },
            notice = notice,
            legacyArchivePresent = cloudBySlot.containsKey(StorageConstants.CLOUD_SAVE_SLOT),
            cloudOnlyTotal = rows.count { it.status == SlotMigrationStatus.CLOUD_ONLY },
            canEnableCloudSave = mode == SaveBackendMode.LEGACY &&
                rows.isNotEmpty() && rows.all { it.status == SlotMigrationStatus.MIGRATED }
        )
        reportMetricsIfNeeded(phase, rows, mode)
    }

    /**
     * 完成率指标（方案 §4 SR-6「完成率指标定义（运营侧可查）」）。
     *
     * **边沿触发**：只在进入 DONE / PARTIAL_FAILED 的那一次上报，且同一阶段不重复报；
     * `start()` 复位 ⇒ 玩家重试一轮就是一条新记录（运营要看的是"这轮跑到哪"）。
     * 走 [scope] 而非调用线程：`rules/data-analytics.md` 1.3 禁止埋点占主线程/热路径，
     * TapDB 未初始化或抛异常时其内部一律兜底降级（不影响引导本身）。
     */
    private fun reportMetricsIfNeeded(
        phase: MigrationPhase,
        rows: List<MigrationSlotRow>,
        mode: SaveBackendMode
    ) {
        if (phase != MigrationPhase.DONE && phase != MigrationPhase.PARTIAL_FAILED) return
        if (phase == reportedPhase) return
        reportedPhase = phase
        val pendingTotal = rows.count { it.status in PENDING_STATUSES }
        scope.launch {
            analytics.trackEvent(
                AnalyticsEvents.SAVE_MIGRATION_RESULT,
                mapOf(
                    AnalyticsEvents.PROP_MIGRATION_PENDING_TOTAL to pendingTotal,
                    AnalyticsEvents.PROP_MIGRATION_MIGRATED_TOTAL to
                        rows.count { it.status == SlotMigrationStatus.MIGRATED },
                    AnalyticsEvents.PROP_MIGRATION_CONFLICT_TOTAL to
                        rows.count { it.status == SlotMigrationStatus.NEEDS_DECISION },
                    AnalyticsEvents.PROP_MIGRATION_BLOCKED_TOTAL to
                        rows.count { it.status == SlotMigrationStatus.BLOCKED_CORRUPT },
                    AnalyticsEvents.PROP_MIGRATION_MODE_AFTER to mode.name
                )
            )
        }
    }

    private fun rowOf(slotView: SaveSlot): MigrationSlotRow? {
        overlays[slotView.slot]?.let {
            return MigrationSlotRow(slotView.slot, slotView.migrationLabel(), it.status, it.detail)
        }
        val status = when {
            slotView.isLoadError -> SlotMigrationStatus.BLOCKED_CORRUPT
            migrationLedger.state(slotView.slot).migrated -> SlotMigrationStatus.MIGRATED
            !slotView.isEmpty -> SlotMigrationStatus.AWAITING_UPLOAD
            cloudBySlot[slotView.slot] != null -> SlotMigrationStatus.CLOUD_ONLY
            else -> return null
        }
        return MigrationSlotRow(slotView.slot, slotView.migrationLabel(), status)
    }

    private fun phaseOf(rows: List<MigrationSlotRow>, pending: Int): MigrationPhase = when {
        rows.isEmpty() -> MigrationPhase.IDLE
        pending > 0 && started -> MigrationPhase.RUNNING
        pending > 0 -> MigrationPhase.READY
        rows.any { it.status == SlotMigrationStatus.UPLOAD_FAILED } -> MigrationPhase.PARTIAL_FAILED
        else -> MigrationPhase.DONE
    }

    private fun inputOf(slotView: SaveSlot, cloud: CloudSaveEntry?): SlotMigrationInput = SlotMigrationInput(
        slot = slotView.slot,
        localHasSave = !slotView.isEmpty && !slotView.isLoadError,
        localLoadError = slotView.isLoadError,
        cloud = cloud,
        migrationState = migrationLedger.state(slotView.slot),
        ledger = ledgerOf(slotView.slot)
    )

    private fun ledgerOf(slot: Int): SlotLedgerSnapshot = SlotLedgerSnapshot(
        lastLocalSaveId = uploadLedger.lastLocalSaveId(slot),
        lastConfirmedCloudId = uploadLedger.lastConfirmedCloudId(slot),
        pendingSaveId = uploadLedger.pendingSaveId(slot)
    )

    private companion object {
        const val TAG = "SaveMigrationCoordinator"

        /** 仍需动作的状态集（DONE 判据的分子侧） */
        val PENDING_STATUSES = setOf(
            SlotMigrationStatus.AWAITING_UPLOAD,
            SlotMigrationStatus.UPLOADING,
            SlotMigrationStatus.UPLOAD_FAILED,
            SlotMigrationStatus.NEEDS_DECISION
        )
    }
}
