package com.xianxia.sect.data.cloud

import com.xianxia.sect.data.prefs.KeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上传落地记账（方案 D3/SR-2：MMKV 持久化，进程被杀后可恢复）。
 *
 * 云端单档语义下记账键坍缩为单键，三个序号（SR-0 §4.1 状态模型）：
 * - `lastLocalSaveId`（L）：本地最新保存序号，每次本地保存成功 +1；
 * - `lastConfirmedCloudId`（C）：已确认上云的序号（上传成功回执后推进）；
 * - `pendingSaveId`：待传指针（入队即落，确认后清零）——进程在上传成功与确认写入
 *   之间被杀时，重启后仍可见"有未确认上传"（SR-0 S6/Q6 幂等重传的依据）。
 *
 * **脏标志 = L > C**（存在未确认上传的保存）——IN2 仲裁无时钟的唯一判定输入。
 * 全部读写走 [KeyValueStore]（生产 MMKV / 测试内存 Fake），纯 JVM 可测。
 */
@Singleton
class UploadLedger @Inject constructor(private val store: KeyValueStore) {

    fun lastLocalSaveId(): Long = store.getLong(KEY_LAST_LOCAL, 0L)

    fun lastConfirmedCloudId(): Long = store.getLong(KEY_LAST_CONFIRMED, 0L)

    /** 待传指针；0 = 无待传 */
    fun pendingSaveId(): Long = store.getLong(KEY_PENDING, 0L)

    /**
     * 记录一次本地保存：L 递增并落待传指针，返回新序号。
     * 调用时机 = 本地事务提交成功后（IN1：上传属后置步骤，失败只降级不回滚本地）。
     */
    fun recordLocalSave(): Long {
        val next = lastLocalSaveId() + 1
        store.putLong(KEY_LAST_LOCAL, next)
        store.putLong(KEY_PENDING, next)
        return next
    }

    /**
     * 推进已确认序号（单调不回退，幂等——同 saveId 重复确认无副作用），
     * 并在确认对象即待传指针时清零待传。
     */
    fun recordCloudConfirmed(saveId: Long) {
        if (saveId > lastConfirmedCloudId()) {
            store.putLong(KEY_LAST_CONFIRMED, saveId)
        }
        if (pendingSaveId() == saveId) {
            store.putLong(KEY_PENDING, 0L)
        }
    }

    /** 脏标志 = L > C（存在未确认上传的保存） */
    fun isLocalDirty(): Boolean = lastLocalSaveId() > lastConfirmedCloudId()

    /**
     * U10 非法态自愈：确认写入先于本地序号写入的中断窗会留下 L < C，
     * 归一为 C = L 并返回 true（调用方遥测上报）。合法态返回 false，无副作用。
     */
    fun normalizeIfNeeded(): Boolean {
        val l = lastLocalSaveId()
        val c = lastConfirmedCloudId()
        if (l >= c) return false
        store.putLong(KEY_LAST_CONFIRMED, l)
        return true
    }

    /**
     * 冲突收口"玩家选云档"的基线收敛（Q10）：本地与云端序号统一收敛到云端实际保存序号
     * [saveId]（保存序号语义，无时钟输入，IN2 合规）——丢弃的本地待传不再保持脏标志。
     */
    fun adoptCloudState(saveId: Long) {
        store.putLong(KEY_LAST_LOCAL, saveId)
        store.putLong(KEY_LAST_CONFIRMED, saveId)
        store.putLong(KEY_PENDING, 0L)
    }

    /** 记账全清（删档/测试复位用；与 clearAllSlotTables 清单纪律对齐） */
    fun reset() {
        store.remove(KEY_LAST_LOCAL)
        store.remove(KEY_LAST_CONFIRMED)
        store.remove(KEY_PENDING)
    }

    private companion object {
        const val KEY_PREFIX = "cloud_upload_ledger_"
        const val KEY_LAST_LOCAL = "${KEY_PREFIX}last_local"
        const val KEY_LAST_CONFIRMED = "${KEY_PREFIX}last_confirmed"
        const val KEY_PENDING = "${KEY_PREFIX}pending"
    }
}
