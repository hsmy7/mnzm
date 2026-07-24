package com.xianxia.sect.data.integrity

import android.util.Log
import com.xianxia.sect.data.model.SaveData

/**
 * SaveValidator 补充修复工具。
 *
 * 提供 [StorageEngine.load()] 路径中修复后数据的持久化支持。
 * 当前 [SaveValidator] 在 load() 路径中对损坏数据的修复只缓存到内存，
 * 不写回数据库。本工具提供 write-back 接口供调度层使用。
 */
object SaveValidatorFixes {

    private const val TAG = "SaveValidatorFixes"

    /**
     * 检查修复后的数据是否需要持久化回数据库。
     *
     * 在 [StorageEngine.load()] 路径中，[SaveValidator.validate()] 返回 [IntegrityResult.Repaired]
     * 时，修复后的数据仅被缓存（[updateCacheAfterSave]），未写回数据库。
     * 若在下一次保存前发生崩溃，修复丢失。
     *
     * 调用方应当在持有写锁时调用此方法，将修复数据持久化。
     *
     * @param slot 存档槽位
     * @param original 原始的存档数据（用作对比）
     * @param repaired 修复后的存档数据
     * @return true 表示需要持久化修复
     */
    fun shouldPersistRepair(original: SaveData, repaired: SaveData): Boolean {
        return original != repaired
    }

    /**
     * 在 load() 路径中使用修复后的数据替换缓存后，
     * 记录一条日志说明修复数据的持久化状态。
     *
     * @param slot 存档槽位
     * @param repairCount 修复项数量
     * @param persisted 是否已持久化到数据库
     */
    fun logRepairStatus(slot: Int, repairCount: Int, persisted: Boolean) {
        if (persisted) {
            Log.i(TAG, "修复后数据已持久化 (slot=$slot, ${repairCount}项)")
        } else {
            Log.w(TAG, "修复后数据仅缓存，未持久化 (slot=$slot, ${repairCount}项) — " +
                "将在下次保存时持久化")
        }
    }
}
