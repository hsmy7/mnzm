package com.xianxia.sect.data.cloud

import com.xianxia.sect.data.prefs.KeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 槽位迁移态（SR-6，方案 §4「存量迁移引导」完成度记账的原子事实）。
 *
 * 语义边界（与 [UploadLedger] 的序号记账正交——本类型只记"这个槽的存量档上云这件事
 * 走到哪一步"，进度新旧判定一律归 [SaveArbiter]，IN2）：
 * - [NONE]：本机这个槽从未参与过上云迁移；
 * - [QUEUED]：已入队、上云尚未确认（进程被杀后该态靠 [UploadLedger] 的待传指针续传收敛，
 *   本字段只是可观测态，不作判定输入）；
 * - [UPLOADED]：本机存量档**已确认上云**——迁移完成的唯一凭据，也是运营完成率的分子来源；
 * - [CLOUD_PREFERRED]：玩家裁决"这个槽以云端为准"（本机存量不上云，云档已落缓存）。
 *
 * [migrated] 只含后两者：`SKIPPED` 一类"暂时不做"的态**刻意不存在**——不标记即保持 [NONE]，
 * 下次冷启动矩阵照样列出，防止一个随手点掉的选项永久熄灭迁移引导（方案 D2 云唯一方向）。
 */
enum class MigrationSlotState {
    NONE,
    QUEUED,
    UPLOADED,
    CLOUD_PREFERRED;

    /** 该槽是否已无需再引导（UPLOADED / CLOUD_PREFERRED 才是收口，QUEUED 仍算未完成） */
    val settled: Boolean get() = this == UPLOADED || this == CLOUD_PREFERRED

    /** 该槽的进度是否已在云端存在一份（CLOUD_ONLY 升档门槛的判据，方案 §4 SR-6） */
    val migrated: Boolean get() = settled
}

/**
 * 迁移完成度记账（SR-6，MMKV 持久化）。
 *
 * 落点与纪律照 [UploadLedger] 先例：全部读写走 [KeyValueStore]（生产 MMKV / 测试内存 Fake），
 * 纯 JVM 可测；key 命名 `cloud_migration_slot{N}_state`，与 `cloud_upload_ledger_slotN_*`
 * 同一风格但**前缀独立**（两类记账互不越界）。
 *
 * 失败封闭：未写入 / 非法值一律回落 [MigrationSlotState.NONE] ⇒ 记账被手改或版本回退时，
 * 后果是"再问一次"而不是"误判已迁移而放行 CLOUD_ONLY"（与
 * [SaveBackendModeProvider.fromStored] 回落 LEGACY 同纪律）。
 */
@Singleton
class SaveMigrationLedger @Inject constructor(private val store: KeyValueStore) {

    /** 该槽迁移态；未写入/非法值 = [MigrationSlotState.NONE] */
    fun state(slot: Int): MigrationSlotState = fromStored(store.getString(stateKey(slot), null))

    /** 已入队待确认（观测态，判定输入请走 [UploadLedger.pendingSaveId]） */
    fun markQueued(slot: Int) = putState(slot, MigrationSlotState.QUEUED)

    /** 上云确认到账（收到 `UploadQueue.Event.UploadConfirmed` 后调用） */
    fun markUploaded(slot: Int) = putState(slot, MigrationSlotState.UPLOADED)

    /** 玩家裁决"以云端为准"：本机存量不上云，云档已落缓存 */
    fun markCloudPreferred(slot: Int) = putState(slot, MigrationSlotState.CLOUD_PREFERRED)

    /**
     * 槽位迁移态清除（删档面）。
     *
     * 与 [UploadLedger.resetSlot] 同处删档清单调用——残留的 `UPLOADED` 会让
     * [SaveMigrationPlanner.canPromoteToCloudOnly] 在"该槽其实无档"上说真话而事实错误，
     * 故删档必须一并清（`clearAllSlotTables` 清单纪律）。
     */
    fun clearSlot(slot: Int) {
        store.remove(stateKey(slot))
    }

    private fun putState(slot: Int, state: MigrationSlotState) {
        store.putString(stateKey(slot), state.name)
    }

    private fun stateKey(slot: Int) = "${KEY_PREFIX}slot${slot}_state"

    companion object {
        private const val KEY_PREFIX = "cloud_migration_"

        /** 解析存储值；null / 未知字符串一律 [MigrationSlotState.NONE]（失败封闭，见类 KDoc） */
        fun fromStored(raw: String?): MigrationSlotState =
            MigrationSlotState.values().firstOrNull { it.name == raw } ?: MigrationSlotState.NONE
    }
}
