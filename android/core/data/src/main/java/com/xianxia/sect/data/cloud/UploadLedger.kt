package com.xianxia.sect.data.cloud

import com.xianxia.sect.data.prefs.KeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上传落地记账（方案 D3/SR-2：MMKV 持久化，进程被杀后可恢复）。
 *
 * per-slot 三个序号（SR-0 §4.1 状态模型）：
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

    fun lastLocalSaveId(slot: Int): Long = store.getLong(lastLocalKey(slot), 0L)

    fun lastConfirmedCloudId(slot: Int): Long = store.getLong(lastConfirmedKey(slot), 0L)

    /** 待传指针；0 = 无待传 */
    fun pendingSaveId(slot: Int): Long = store.getLong(pendingKey(slot), 0L)

    /**
     * 记录一次本地保存：L 递增并落待传指针，返回新序号。
     * 调用时机 = 本地事务提交成功后（IN1：上传属后置步骤，失败只降级不回滚本地）。
     */
    fun recordLocalSave(slot: Int): Long {
        val next = lastLocalSaveId(slot) + 1
        store.putLong(lastLocalKey(slot), next)
        store.putLong(pendingKey(slot), next)
        return next
    }

    /**
     * 推进已确认序号（单调不回退，幂等——同 saveId 重复确认无副作用），
     * 并在确认对象即待传指针时清零待传。
     */
    fun recordCloudConfirmed(slot: Int, saveId: Long) {
        if (saveId > lastConfirmedCloudId(slot)) {
            store.putLong(lastConfirmedKey(slot), saveId)
        }
        if (pendingSaveId(slot) == saveId) {
            store.putLong(pendingKey(slot), 0L)
        }
    }

    /** 脏标志 = L > C（存在未确认上传的保存） */
    fun isLocalDirty(slot: Int): Boolean = lastLocalSaveId(slot) > lastConfirmedCloudId(slot)

    /**
     * U10 非法态自愈：确认写入先于本地序号写入的中断窗会留下 L < C，
     * 归一为 C = L 并返回 true（调用方遥测上报）。合法态返回 false，无副作用。
     */
    fun normalizeIfNeeded(slot: Int): Boolean {
        val l = lastLocalSaveId(slot)
        val c = lastConfirmedCloudId(slot)
        if (l >= c) return false
        store.putLong(lastConfirmedKey(slot), l)
        return true
    }

    /** 槽位记账全清（删档/测试复位用；与 clearAllSlotTables 清单纪律对齐） */
    fun resetSlot(slot: Int) {
        store.remove(lastLocalKey(slot))
        store.remove(lastConfirmedKey(slot))
        store.remove(pendingKey(slot))
    }

    private fun lastLocalKey(slot: Int) = "${KEY_PREFIX}slot${slot}_last_local"
    private fun lastConfirmedKey(slot: Int) = "${KEY_PREFIX}slot${slot}_last_confirmed"
    private fun pendingKey(slot: Int) = "${KEY_PREFIX}slot${slot}_pending"

    private companion object {
        const val KEY_PREFIX = "cloud_upload_ledger_"
    }
}
