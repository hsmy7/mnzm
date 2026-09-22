package com.xianxia.sect.data.cloud

/**
 * 本端上传账本的只读快照（矩阵入参）。
 *
 * planner 不注入 [UploadLedger]：把"读了哪些序号"留在调用侧，矩阵本体保持纯函数
 * （零 IO、零时钟、JVM 直测），与 [SaveArbiter] 同纪律。
 */
data class SlotLedgerSnapshot(
    val lastLocalSaveId: Long,
    val lastConfirmedCloudId: Long,
    val pendingSaveId: Long
) {
    /** 存在"已入队但未确认上云"的保存（勘察 F1：续传判据，SR-2 待传指针的消费点） */
    val hasUnconfirmedPending: Boolean get() = pendingSaveId > lastConfirmedCloudId

    companion object {
        /** 全零 = 本机这个槽从没走过双步保存（LEGACY 存量档的典型态） */
        val EMPTY = SlotLedgerSnapshot(0L, 0L, 0L)
    }
}

/** 矩阵逐槽入参（"本地有没有 × 云上有没有 × 本机账本说了什么 × 之前裁决过没有"） */
data class SlotMigrationInput(
    val slot: Int,
    /** 本地该槽有可读存量档（slot 0 云会话入口卡不算——勘察 F3，调用侧必须排除） */
    val localHasSave: Boolean,
    /** 本地该槽**有数据但读取失败**：损坏档不得被当成空档覆盖（`SaveSlot.isLoadError` 三态纪律） */
    val localLoadError: Boolean,
    /** 云端 `slot_N` 档；null = 云无档（[SaveBackend.list] 的槽位过滤已把非槽位命名剔除） */
    val cloud: CloudSaveEntry?,
    /** 迁移记账现值 */
    val migrationState: MigrationSlotState,
    val ledger: SlotLedgerSnapshot
)

/** 上云动作的来由（UI 文案与指标归因用，不影响判定） */
enum class UploadReason {
    /** 存量档首次上云（方案 §4 SR-6「引导逐槽上传」） */
    MIGRATE,

    /** 上次入队后未获确认（勘察 F1 续传） */
    RESUME_PENDING
}

/** 冲突来由（两类的后果文案不同，合并成一句会误导玩家） */
enum class CloudConflictReason {
    /** 双端各有新进度：`SaveArbiter` 判 CONFLICT */
    BOTH_ADVANCED,

    /**
     * 云端有档但本机**无从判定**（云档无 `saveId`，或本机 `C==0` 从未确认过任何云档）——
     * 勘察 F12：[SaveArbiter] 的 U11 会把这种情况保守重算成 `UPLOAD_PENDING` 并放行上传，
     * 那等于静默覆盖另一台设备的历史档。迁移语境下必须升级为玩家裁决，不交给仲裁兜底。
     */
    UNVERIFIABLE_CLOUD_STATE
}

/** 单槽迁移动作（方案 §4 SR-6 首启检测矩阵的输出面） */
sealed class SlotMigrationAction {
    /** 无需动作：新游戏空槽，或两端本就一致，或已裁决过（幂等：不重复打扰） */
    data object NoAction : SlotMigrationAction()

    /** 引导把本机存量档上云 */
    data class UploadLocal(val reason: UploadReason) : SlotMigrationAction()

    /** 本机与云端各有内容，必须玩家二选一（禁止静默覆盖，方案 §2/§6） */
    data class ResolveConflict(val reason: CloudConflictReason) : SlotMigrationAction()

    /** 本机无档、云端有档 ⇒ 直接云档（下载并落缓存，不 boot 引擎） */
    data object UseCloud : SlotMigrationAction()

    /** 本机该槽损坏：如实阻断，既不上传也不当空档（IN7 精神：不改存档值、不覆盖） */
    data object BlockedByLoadError : SlotMigrationAction()
}

/** 逐槽计划 */
data class MigrationSlotPlan(val slot: Int, val action: SlotMigrationAction)

/**
 * 迁移计划（矩阵输出）。
 *
 * [legacyArchive] = slot 0 存量单档 `mnzm_cloud_save`（[TapTapSaveBackend.archiveNameFor]
 * 语义，勘察 F4）。它**不进** [slots] 的逐槽判定——它没有对应的本地槽，
 * 归宿是"下载到玩家选定的空槽"（SR-3 §4.1 移交本批），因此单独一列由 UI 显式引导。
 */
data class MigrationPlan(
    val slots: List<MigrationSlotPlan>,
    val legacyArchive: CloudSaveEntry?
) {
    val uploadSlots: List<Int> get() = filterSlots { it is SlotMigrationAction.UploadLocal }
    val conflictSlots: List<Int> get() = filterSlots { it is SlotMigrationAction.ResolveConflict }
    val cloudSlots: List<Int> get() = filterSlots { it === SlotMigrationAction.UseCloud }
    val blockedSlots: List<Int> get() = filterSlots { it === SlotMigrationAction.BlockedByLoadError }

    /** 还需要玩家动作的槽位数（迁移卡的可见性判据） */
    val actionableCount: Int
        get() = uploadSlots.size + conflictSlots.size + cloudSlots.size +
            blockedSlots.size + (if (legacyArchive != null) 1 else 0)

    val requiresPlayerDecision: Boolean get() = conflictSlots.isNotEmpty() || legacyArchive != null

    private fun filterSlots(match: (SlotMigrationAction) -> Boolean): List<Int> =
        slots.mapNotNull { entry -> entry.action.takeIf(match)?.let { entry.slot } }
}

/**
 * 存量迁移矩阵纯函数（SR-6，方案 §4「首启检测矩阵」）。
 *
 * 判定顺序（自上而下短路，全部是**序号与记账态**的比较，零时钟——IN2）：
 * 1. 本地读取失败 ⇒ [SlotMigrationAction.BlockedByLoadError]（损坏档不得被当成空档）；
 * 2. 有未确认的待传指针 ⇒ [SlotMigrationAction.UploadLocal]（[UploadReason.RESUME_PENDING]）——
 *    排在"已裁决"之前，因为续传是**未走完的动作**，幂等态不该把它抹掉（勘察 F1）；
 * 3. 该槽已收口（[MigrationSlotState.UPLOADED] / [MigrationSlotState.CLOUD_PREFERRED]）⇒
 *    无动作（幂等，冷启动不重复骚扰）；
 * 4. 本地无档 ×（云无档 ⇒ 无动作 = 新游戏槽｜云有档 ⇒ [SlotMigrationAction.UseCloud]）；
 * 5. 本地有档 × 云无档 ⇒ [SlotMigrationAction.UploadLocal]（[UploadReason.MIGRATE]）；
 * 6. 本地有档 × 云有档 ⇒ 先过 F12 守卫（云档无 `saveId` 或本机 `C==0` ⇒ 玩家裁决），
 *    其余交 [SaveArbiter]：CONFLICT ⇒ 玩家裁决｜LOCAL_BEHIND ⇒ 用云档｜
 *    UPLOAD_PENDING ⇒ 上本机档｜IN_SYNC ⇒ 无动作。
 *
 * 方案的"双无 ⇒ 新游戏"格即规则 4 的 `cloud == null` 分支；"本地无 × 云有 ⇒ 直接云档"
 * 即规则 4 的 `UseCloud`；"本地有 × 云有 ⇒ 脏标志仲裁 + 冲突 UI"即规则 6。
 */
object SaveMigrationPlanner {

    fun plan(inputs: List<SlotMigrationInput>, legacyArchive: CloudSaveEntry? = null): MigrationPlan =
        MigrationPlan(inputs.map { MigrationSlotPlan(it.slot, decide(it)) }, legacyArchive)

    /** 逐槽判定（类 KDoc 的六步顺序） */
    fun decide(input: SlotMigrationInput): SlotMigrationAction {
        val cloud = input.cloud
        return when {
            input.localLoadError -> SlotMigrationAction.BlockedByLoadError
            input.ledger.hasUnconfirmedPending ->
                SlotMigrationAction.UploadLocal(UploadReason.RESUME_PENDING)
            input.migrationState.settled -> SlotMigrationAction.NoAction
            !input.localHasSave ->
                if (cloud == null) SlotMigrationAction.NoAction else SlotMigrationAction.UseCloud
            cloud == null -> SlotMigrationAction.UploadLocal(UploadReason.MIGRATE)
            else -> decideBothSides(cloud, input.ledger)
        }
    }

    /**
     * CLOUD_ONLY 升档门槛（方案 §4 SR-6 硬红线：「未完成迁移的设备不推 CLOUD_ONLY」）。
     *
     * 三条缺一即 false：① 无损坏槽（损坏槽进度既不在云上也无法读出，推上去就是丢档）；
     * ② 所有本地有档槽均已 [MigrationSlotState.migrated]；③ 无未确认的待传保存。
     * 本地一槽无档时三条**真空成立** ⇒ true：没有存量要保护的机器升档不需要迁移前置
     * （迁移卡的可见性另有 [MigrationPlan.actionableCount] 判据，不靠本函数）。
     */
    fun canPromoteToCloudOnly(inputs: List<SlotMigrationInput>): Boolean {
        val nothingBlocked = inputs.none { it.localLoadError || it.ledger.hasUnconfirmedPending }
        val allLocalSavesMigrated = inputs.filter { it.localHasSave }.all { it.migrationState.migrated }
        return nothingBlocked && allLocalSavesMigrated
    }

    private fun decideBothSides(
        cloud: CloudSaveEntry,
        ledger: SlotLedgerSnapshot
    ): SlotMigrationAction {
        val saveId = cloud.saveId
        if (saveId == null || ledger.lastConfirmedCloudId == 0L) {
            return SlotMigrationAction.ResolveConflict(CloudConflictReason.UNVERIFIABLE_CLOUD_STATE)
        }
        return when (SaveArbiter.arbitrate(ledger.lastLocalSaveId, ledger.lastConfirmedCloudId, saveId)) {
            ArbitrationVerdict.CONFLICT ->
                SlotMigrationAction.ResolveConflict(CloudConflictReason.BOTH_ADVANCED)
            ArbitrationVerdict.LOCAL_BEHIND -> SlotMigrationAction.UseCloud
            ArbitrationVerdict.UPLOAD_PENDING -> SlotMigrationAction.UploadLocal(UploadReason.MIGRATE)
            ArbitrationVerdict.IN_SYNC -> SlotMigrationAction.NoAction
        }
    }
}
