package com.xianxia.sect.data.model

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*

data class SaveSlot(
    val slot: Int,
    val name: String,
    val timestamp: Long,
    val gameYear: Int,
    val gameMonth: Int,
    val sectName: String,
    val discipleCount: Int,
    val spiritStones: Long,
    val isEmpty: Boolean = false,
    val customName: String = ""
) {
    val displayTime: String get() = "第${gameYear}年${gameMonth}月"
    val saveTime: String 
        get() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
    val displayName: String get() = if (customName.isNotBlank()) customName else name
}

data class SaveData(
    val version: String = GameConfig.Game.VERSION,
    val timestamp: Long = System.currentTimeMillis(),
    val gameData: GameData,
    val disciples: List<Disciple>,
    val equipment: List<Equipment>,
    val manuals: List<Manual>,
    val pills: List<Pill>,
    val materials: List<Material>,
    val herbs: List<Herb>,
    val seeds: List<Seed>,
    val teams: List<ExplorationTeam>,
    val events: List<GameEvent>,
    val battleLogs: List<BattleLog> = emptyList(),
    val alliances: List<Alliance> = emptyList()
)
