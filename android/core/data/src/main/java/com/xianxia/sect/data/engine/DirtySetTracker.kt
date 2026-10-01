package com.xianxia.sect.data.engine

import com.xianxia.sect.core.state.SaveDirtyDeltaSource
import com.xianxia.sect.core.state.SaveDirtyRecorder
import com.xianxia.sect.core.state.SaveDirtySet
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 保存路径决策（增量默认 / 全量兜底）。
 *
 * @property incremental true = 走增量路径；false = 走既有全删全写
 * @property dirtySet 增量路径的输入脏集；全量路径为 null
 * @property fullSaveReason 全量路径的原因（增量路径为 null）——计数与诊断用
 */
data class SavePathDecision(
    val incremental: Boolean,
    val dirtySet: SaveDirtySet?,
    val fullSaveReason: FullSaveReason?,
) {
    companion object {
        fun incremental(dirtySet: SaveDirtySet) = SavePathDecision(true, dirtySet, null)
        fun full(reason: FullSaveReason) = SavePathDecision(false, null, reason)
    }
}

/** 全量路径触发原因（计数进 [StorageMetrics] 路径分布）。 */
enum class FullSaveReason {
    /** 无落盘基线：进程首保 / 读档、恢复、删档、重置后的首次保存。 */
    NO_BASELINE,

    /** 脏集缺失（SaveData 未携带脏集的保存链路，如云档落盘）。 */
    NO_DIRTY_SET,

    /** 脏集越界：upsert 脏 id 不在当前快照 id 集合内（陈旧/不可判定）。 */
    DIRTY_OUT_OF_SNAPSHOT,

    /** 脏集溢出：累积条目超过容量上限，保护内存并回退全量。 */
    DIRTY_OVERFLOW,
}

/**
 * 增量落盘的保存脏集跟踪器（SS5）。
 *
 * 职责：
 * - 接收 store 提交段的脏集馈送（[SaveDirtyRecorder]，镜像 applyDirty 写入与
 *   本地 Kotlin 写在同一提交段被捕获）；
 * - 在存档快照构建点以 move 语义交出累积脏集（[SaveDirtyDeltaSource]）；
 * - 保存结算：按代序号清除「捕获后未再变更」的条目，失败整组并回；
 * - 越界/溢出/无基线时判全量兜底（[resolveSavePath]）。
 *
 * **代序号与飞行期一致性**：保存飞行期间（快照已建、DB 事务未提交）同一实体
 * 可能再次变更并重新馈送——结算只清除「捕获后代序号未前进」的条目；序号已
 * 前进的条目保留在累积区，其新内容由下一次保存落盘。丢失更新的时间窗由此闭合。
 *
 * 线程契约：馈送与交出脏集都在引擎线程串行发生；结算（保存链路）在存储
 * IO 线程——全部状态由 [lock] 保护。脏集不持久化：进程重启后
 * [requiresFullWrite] 初始为 true，首保走全量基线建立。
 */
@Singleton
class DirtySetTracker @Inject constructor() : SaveDirtyRecorder, SaveDirtyDeltaSource {

    companion object {
        /**
         * 单表累积脏 id 容量上限——超过即溢出（[FullSaveReason.DIRTY_OVERFLOW]），
         * 保护累积区内存并回退全量；全量成功后复位。
         */
        internal const val MAX_DIRTY_IDS_PER_TABLE = 50_000
    }

    private val lock = Any()

    /** 表 → (id → 代序号)；序号在每次馈送时前进，结算据此判「捕获后未再变更」。 */
    private val pendingUpsertIds = HashMap<String, HashMap<String, Long>>()
    private val pendingHeavyKeys = HashMap<String, Long>()
    private val pendingRewriteTables = HashSet<String>()

    /** 保存飞行中的脏集代序号（capture 时冻结；settle 时比对清除）。 */
    private var inFlightUpsertStamps: Map<String, Map<String, Long>> = emptyMap()
    private var inFlightHeavyStamps: Map<String, Long> = emptyMap()

    private val stampCounter = AtomicLong(0)

    /** 下一次保存必须走全量（初始无基线 ⇒ 首保全量基线建立）。 */
    @Volatile
    private var requiresFullWrite = true

    /** 溢出闩：置位后保持全量，直到下一次全量保存成功复位。 */
    @Volatile
    private var overflowed = false

    // ── SaveDirtyRecorder（引擎线程，store 提交段馈送） ──

    override fun recordEntityUpserts(table: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            val bucket = pendingUpsertIds.getOrPut(table) { HashMap() }
            for (id in ids) {
                bucket[id] = stampCounter.incrementAndGet()
                if (bucket.size > MAX_DIRTY_IDS_PER_TABLE) {
                    overflowed = true
                    bucket.clear()
                    return
                }
            }
        }
    }

    override fun recordTableRewrite(table: String) {
        synchronized(lock) { pendingRewriteTables.add(table) }
    }

    override fun recordHeavyKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            for (key in keys) pendingHeavyKeys[key] = stampCounter.incrementAndGet()
        }
    }

    override fun recordRequiresFullWrite() {
        requiresFullWrite = true
    }

    override fun resetForStateReplacement() {
        synchronized(lock) {
            pendingUpsertIds.clear()
            pendingHeavyKeys.clear()
            pendingRewriteTables.clear()
            inFlightUpsertStamps = emptyMap()
            inFlightHeavyStamps = emptyMap()
        }
        overflowed = false
        requiresFullWrite = true
    }

    // ── SaveDirtyDeltaSource（引擎线程，存档快照构建点） ──

    override fun captureDeltaForSave(): SaveDirtySet? = synchronized(lock) {
        if (pendingUpsertIds.isEmpty() && pendingRewriteTables.isEmpty() && pendingHeavyKeys.isEmpty()) {
            return SaveDirtySet.EMPTY
        }
        val delta = SaveDirtySet(
            upsertIds = pendingUpsertIds.mapValues { it.value.keys.toSet() },
            rewriteTables = pendingRewriteTables.toSet(),
            heavyKeys = pendingHeavyKeys.keys.toSet()
        )
        // move 语义：累积区清空，代序号冻结为飞行中基准（结算比对用）
        inFlightUpsertStamps = pendingUpsertIds.mapValues { it.value.toMap() }
        inFlightHeavyStamps = pendingHeavyKeys.toMap()
        pendingUpsertIds.clear()
        pendingRewriteTables.clear()
        pendingHeavyKeys.clear()
        delta
    }

    // ── 保存结算（存储 IO 线程） ──

    /**
     * 保存结算。
     *
     * 失败：脏集整组并回累积区（DB 事务已回滚，变更仍未落盘）。
     * 成功：仅清除「捕获后代序号未前进」的条目（其内容确已落库）；飞行期间
     * 再次变更的条目保留，归下一次保存。全量成功额外复位溢出闩并建立基线。
     *
     * @param writtenViaFull 本次落盘实际走的是全量路径（全量写整快照 ⇒ 基线建立）
     */
    fun settleSaveResult(delta: SaveDirtySet?, success: Boolean, writtenViaFull: Boolean) {
        synchronized(lock) {
            if (!success) {
                if (delta != null) mergeBackLocked(delta)
            } else {
                clearSettledEntriesLocked(delta)
                if (writtenViaFull) overflowed = false
                requiresFullWrite = false
            }
            inFlightUpsertStamps = emptyMap()
            inFlightHeavyStamps = emptyMap()
        }
    }

    /** 当前是否要求全量（基线缺失或溢出闩置位）。 */
    fun isFullWriteRequired(): Boolean = requiresFullWrite || overflowed

    /** 累积区是否为空（诊断/测试用）。 */
    fun hasPendingChanges(): Boolean = synchronized(lock) {
        pendingUpsertIds.isNotEmpty() || pendingRewriteTables.isNotEmpty() || pendingHeavyKeys.isNotEmpty()
    }

    private fun clearSettledEntriesLocked(delta: SaveDirtySet?) {
        if (delta == null) return
        delta.upsertIds.forEach { (table, ids) -> clearSettledUpsertsLocked(table, ids) }
        clearSettledHeavyKeysLocked(delta.heavyKeys)
    }

    /** 清除「捕获后代序号未前进」的行级条目；飞行期再变更的条目保留。 */
    private fun clearSettledUpsertsLocked(table: String, ids: Set<String>) {
        val stamps = inFlightUpsertStamps[table] ?: return
        val bucket = pendingUpsertIds[table] ?: return
        for (id in ids) {
            val capturedStamp = stamps[id] ?: continue
            if (bucket[id] == capturedStamp) bucket.remove(id)
        }
        if (bucket.isEmpty()) pendingUpsertIds.remove(table)
    }

    /** 清除「捕获后代序号未前进」的 heavy key；飞行期再变更的 key 保留。 */
    private fun clearSettledHeavyKeysLocked(heavyKeys: Set<String>) {
        for (key in heavyKeys) {
            val capturedStamp = inFlightHeavyStamps[key] ?: continue
            if (pendingHeavyKeys[key] == capturedStamp) pendingHeavyKeys.remove(key)
        }
    }

    /** 把脏集并回累积区（保存失败路径；幂等）。 */
    private fun mergeBackLocked(delta: SaveDirtySet) {
        delta.upsertIds.forEach { (table, ids) ->
            val bucket = pendingUpsertIds.getOrPut(table) { HashMap() }
            for (id in ids) bucket[id] = stampCounter.incrementAndGet()
        }
        pendingRewriteTables.addAll(delta.rewriteTables)
        for (key in delta.heavyKeys) pendingHeavyKeys[key] = stampCounter.incrementAndGet()
    }
}

/**
 * 保存路径判定：增量默认，无基线 / 脏集缺失 / 脏集越界 / 溢出回退全量。
 *
 * 越界判据（验收⑤）：脏集 upsert id 必须 ⊆ 当前快照对应表的 id 集合——
 * 陈旧脏集不可判定哪些行真实变化，回退全量并计数，不得静默跳过。
 *
 * @param hasBaseline 上次成功落盘是否已建立基线（首保/读档后首保为 false）
 * @param snapshotIds 表名 → 当前快照 id 集合
 */
internal fun resolveSavePath(
    dirtySet: SaveDirtySet?,
    hasBaseline: Boolean,
    snapshotIds: Map<String, Set<String>>
): SavePathDecision = when {
    !hasBaseline -> SavePathDecision.full(FullSaveReason.NO_BASELINE)
    dirtySet == null -> SavePathDecision.full(FullSaveReason.NO_DIRTY_SET)
    hasOutOfSnapshotIds(dirtySet, snapshotIds) ->
        SavePathDecision.full(FullSaveReason.DIRTY_OUT_OF_SNAPSHOT)
    else -> SavePathDecision.incremental(dirtySet)
}

/** 脏集 upsert id 是否存在不在快照 id 集合内的越界项（纯函数，便于直测）。 */
internal fun hasOutOfSnapshotIds(
    dirtySet: SaveDirtySet,
    snapshotIds: Map<String, Set<String>>
): Boolean = dirtySet.upsertIds.any { (table, ids) ->
    val valid = snapshotIds[table] ?: return@any true
    ids.any { it !in valid }
}

/**
 * 路径判定 + 计数（StorageEngine.resolveSaveDecision 委托至此，便于直测）：
 * 路径分布（增量/全量）逐次计数；越界/溢出属异常回退，独立于首保类全量计数。
 */
internal fun resolveSaveDecisionWithCount(
    dirtySet: SaveDirtySet?,
    hasBaseline: Boolean,
    snapshotIds: Map<String, Set<String>>,
    metrics: StorageMetrics
): SavePathDecision {
    val decision = resolveSavePath(dirtySet, hasBaseline, snapshotIds)
    if (decision.incremental) {
        metrics.recordIncrementalSave()
    } else {
        metrics.recordFullSave()
        val reason = decision.fullSaveReason
        if (reason == FullSaveReason.DIRTY_OUT_OF_SNAPSHOT || reason == FullSaveReason.DIRTY_OVERFLOW) {
            metrics.recordDirtyFallback()
        }
        metrics.setLastFullSaveReason(reason?.name)
    }
    return decision
}
