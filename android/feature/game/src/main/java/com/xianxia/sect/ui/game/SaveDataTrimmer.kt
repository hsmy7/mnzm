package com.xianxia.sect.ui.game

import android.util.Log
import com.xianxia.sect.core.engine.GameStateSnapshot
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
     */
    fun trimSaveData(snapshot: GameStateSnapshot): SaveData {
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
            teams = snapshot.teams,
            battleLogs = trimmedBattleLogs,
            alliances = snapshot.alliances,
            productionSlots = snapshot.productionSlots,
            storageBags = snapshot.storageBags
        )
    }
}
