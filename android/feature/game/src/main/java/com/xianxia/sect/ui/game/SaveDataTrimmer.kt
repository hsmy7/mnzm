package com.xianxia.sect.ui.game

import android.util.Log
import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.data.model.SaveData

/**
 * 存档数据裁剪工具
 * 从 SaveLoadViewModel.kt 提取，负责在保存前裁剪过大的数据（如战斗日志）
 */
object SaveDataTrimmer {

    private const val TAG = "SaveDataTrimmer"
    private const val MAX_BATTLE_LOGS = 1000

    /**
     * 裁剪 GameStateSnapshot 为可保存的 SaveData
     *
     * 主要裁剪逻辑：battleLogs 超过 1000 条时只保留最新的 1000 条
     *
     * [mails]（SR-1）：槽位全量邮件快照，调用方在保存编排时从 `mails` 表读当前
     * slot 传入——**必填无默认值**：邮件走"整对象替换回表"语义，构造点漏传 =
     * 快照空表抹掉槽位邮件，故用编译器强制每个 SaveData 构造点显式面对该参数
     * （本地保存/云上传共用本口，云恢复单表替换走 StorageFacade.replaceMailsForSlot）。
     */
    fun trimSaveData(snapshot: GameStateSnapshot, mails: List<MailEntity>): SaveData {
        val trimmedBattleLogs = if (snapshot.battleLogs.size > MAX_BATTLE_LOGS) {
            Log.w(TAG, "Trimming battleLogs: ${snapshot.battleLogs.size} -> $MAX_BATTLE_LOGS")
            snapshot.battleLogs.takeLast(MAX_BATTLE_LOGS)
        } else {
            snapshot.battleLogs
        }
        return SaveData(
            gameData = snapshot.gameData,
            disciples = snapshot.disciples,
            equipmentStacks = snapshot.equipmentStacks,
            equipmentInstances = snapshot.equipmentInstances,
            manualStacks = snapshot.manualStacks,
            manualInstances = snapshot.manualInstances,
            pills = snapshot.pills,
            materials = snapshot.materials,
            herbs = snapshot.herbs,
            seeds = snapshot.seeds,
            battleLogs = trimmedBattleLogs,
            alliances = snapshot.alliances,
            productionSlots = snapshot.productionSlots,
            storageBags = snapshot.storageBags,
            mails = mails,
            // 正常保存路径恒为新格式：堆叠已序列化
            stacksSerialized = true
        )
    }
}
