package com.xianxia.sect.data.integrity.corrupted

import android.util.Log
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.model.SaveData

/**
 * 损坏存档恢复结果处理器。
 *
 * 当 [com.xianxia.sect.data.engine.StorageEngine.load()] 遇到 [IntegrityResult.Corrupted]
 * 并从备份文件恢复后，恢复的数据应再次经过 [SaveValidator.validate()] 验证，
 * 确保业务语义完整。
 *
 * 当前备份恢复路径（[com.xianxia.sect.data.engine.StorageEngine.load()] ~line 268）
 * 在成功恢复后直接将数据返回，跳过了 [SaveValidator.validate()] 的二次校验。
 */
object CorruptedResultHandler {

    private const val TAG = "CorruptedResultHandler"

    /**
     * 对从备份恢复的数据执行二次验证。
     *
     * 确保恢复后的数据通过完整性校验，防止备份本身存在数据问题。
     *
     * @param slot 存档槽位
     * @param restoredData 从备份文件恢复的数据
     * @return 二次验证后的 IntegrityResult（[IntegrityResult.Passed] 或 [IntegrityResult.Repaired]）
     *         若二次修复仍然 [IntegrityResult.Corrupted]，记录致命日志并返回原数据
     */
    fun validateRestoredData(slot: Int, restoredData: SaveData): IntegrityResult {
        Log.i(TAG, "对备份恢复数据执行二次验证 (slot=$slot)")
        val result = SaveValidator.validate(restoredData)
        return when (result) {
            is IntegrityResult.Passed -> {
                Log.i(TAG, "二次验证通过 (slot=$slot)")
                result
            }
            is IntegrityResult.Repaired -> {
                Log.w(TAG, "二次验证修复 ${result.details.size} 项 (slot=$slot)")
                result.details.forEach { detail ->
                    Log.i(TAG, "  备份修复: $detail")
                }
                result
            }
            is IntegrityResult.Corrupted -> {
                Log.e(TAG, "备份恢复数据二次验证失败 (slot=$slot): ${result.details.size} 项")
                result.details.forEach { detail ->
                    Log.e(TAG, "  备份问题: $detail")
                }
                // 返回原数据（调用方决定是否丢弃）
                result
            }
        }
    }

    /**
     * 判断备份恢复的数据是否安全可用。
     *
     * @param result 二次验证结果
     * @return true 表示数据可用（无法修复的损坏返回 false）
     */
    fun isRestoredDataUsable(result: IntegrityResult): Boolean {
        return result !is IntegrityResult.Corrupted
    }
}
