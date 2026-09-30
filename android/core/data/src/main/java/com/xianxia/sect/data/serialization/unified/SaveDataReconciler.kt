package com.xianxia.sect.data.serialization.unified

import android.util.Log
import com.xianxia.sect.core.model.rebuildManualStacks
import com.xianxia.sect.data.model.SaveData

/**
 * 旧存档堆叠数据协调器（B3 起：堆叠重建仅剩功法）。
 *
 * 新存档携带堆叠（stacksSerialized = true）；标记为 false 的存档（历史
 * 版本落库形态）仓库堆叠物理上从未被序列化，经 [reconcileStacks] 从实例
 * 重建兜底——装备堆叠已随 B3 退役，仅剩功法堆叠重建。
 */
object SaveDataReconciler {
    private const val TAG = "SaveDataReconciler"

    /**
     * 协调堆叠数据：新格式（stacksSerialized = true）原样返回；
     * 旧格式从功法实例重建堆叠并置标记。
     */
    fun reconcileStacks(data: SaveData): SaveData {
        if (data.stacksSerialized) return data
        val rebuiltManual = rebuildManualStacks(data.manualInstances)
        // 无论重建是否为空都输出警告——旧格式的仓库堆叠从未被序列化
        //（物理上无法恢复），空结果时也须如实提示
        Log.w(
            TAG,
            "旧存档无堆叠数据（stacksSerialized=false），从功法实例重建兜底：" +
                "manual=${rebuiltManual.size} 组。" +
                "仓库物品（仅以堆叠形式存在）从未被序列化，无法从备份恢复" +
                if (rebuiltManual.isEmpty())
                    "——本次无可恢复的游离实例，功法堆叠将为空（数据物理上不存在）。"
                else "——本次仅恢复未学习的游离功法实例。"
        )
        return data.copy(
            manualStacks = rebuiltManual,
            stacksSerialized = true
        )
    }
}
