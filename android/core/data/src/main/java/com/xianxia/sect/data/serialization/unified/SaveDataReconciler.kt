package com.xianxia.sect.data.serialization.unified

import android.util.Log
import com.xianxia.sect.core.model.rebuildEquipmentStacks
import com.xianxia.sect.core.model.rebuildManualStacks
import com.xianxia.sect.data.model.SaveData

/**
 * 旧存档堆叠数据协调器（2026-08-01 堆叠序列化缺陷修复）。
 *
 * 历史缺陷：SaveData 的 equipmentStacks/manualStacks 曾被标记 @Transient，
 * 备份文件与云存档中不含堆叠，恢复路径会永久清空仓库堆叠。
 * 修复后新存档携带堆叠（stacksSerialized = true）；旧存档（false）经 [reconcileStacks]
 * 从实例重建堆叠兜底——仓库物品物理上从未被序列化过，仅能恢复未装备的游离实例，
 * 日志如实记录缺失范围。
 */
object SaveDataReconciler {
    private const val TAG = "SaveDataReconciler"

    /**
     * 协调堆叠数据：新格式（stacksSerialized = true）原样返回；
     * 旧格式从实例重建堆叠并置标记。
     */
    fun reconcileStacks(data: SaveData): SaveData {
        if (data.stacksSerialized) return data
        val rebuiltEquipment = rebuildEquipmentStacks(data.equipmentInstances)
        val rebuiltManual = rebuildManualStacks(data.manualInstances)
        // 2026-08-01 对抗性审查修复：无论重建是否为空都输出警告——
        // 旧格式的仓库堆叠从未被序列化（物理上无法恢复），空结果时也须如实提示
        Log.w(
            TAG,
            "旧存档无堆叠数据（stacksSerialized=false），从实例重建兜底：" +
                "equipment=${rebuiltEquipment.size} 组, manual=${rebuiltManual.size} 组。" +
                "仓库物品（仅以堆叠形式存在）从未被序列化，无法从备份恢复" +
                if (rebuiltEquipment.isEmpty() && rebuiltManual.isEmpty())
                    "——本次无可恢复的游离实例，仓库堆叠将为空（数据物理上不存在）。"
                else "——本次仅恢复未装备/未学习的游离实例。"
        )
        return data.copy(
            equipmentStacks = rebuiltEquipment,
            manualStacks = rebuiltManual,
            stacksSerialized = true
        )
    }
}
