package com.xianxia.sect.data.model

import androidx.annotation.Keep
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

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
        get() = if (timestamp > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(timestamp)) else "--"
    val displayName: String get() = if (customName.isNotBlank()) customName else name
}

@Keep
@Serializable
data class SaveData(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(1) val version: String = GameConfig.Game.VERSION,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(2) val timestamp: Long = System.currentTimeMillis(),
    @ProtoNumber(3) val gameData: GameData,
    @ProtoNumber(4) val disciples: List<Disciple>,
    @Transient val equipmentStacks: List<EquipmentStack> = emptyList(),
    @ProtoNumber(5) val equipmentInstances: List<EquipmentInstance> = emptyList(),
    @Transient val manualStacks: List<ManualStack> = emptyList(),
    @ProtoNumber(6) val manualInstances: List<ManualInstance> = emptyList(),
    @ProtoNumber(7) val pills: List<Pill>,
    @ProtoNumber(8) val materials: List<Material>,
    @ProtoNumber(9) val herbs: List<Herb>,
    @ProtoNumber(10) val seeds: List<Seed>,
    @ProtoNumber(15) val storageBags: List<StorageBag> = emptyList(),
    @ProtoNumber(11) val teams: List<ExplorationTeam>,
    @ProtoNumber(13) val battleLogs: List<BattleLog> = emptyList(),
    @ProtoNumber(14) val alliances: List<Alliance> = emptyList(),
    @ProtoNumber(52) val productionSlots: List<ProductionSlot> = emptyList()
)
