package com.xianxia.sect.data.model

import androidx.annotation.Keep
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
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
    @ProtoNumber(53) val equipmentStacks: List<EquipmentStack> = emptyList(),
    @ProtoNumber(5) val equipmentInstances: List<EquipmentInstance> = emptyList(),
    @ProtoNumber(54) val manualStacks: List<ManualStack> = emptyList(),
    @ProtoNumber(6) val manualInstances: List<ManualInstance> = emptyList(),
    /**
     * 堆叠数据是否已序列化。
     *
     * 历史缺陷（2026-08-01 修复前）：equipmentStacks/manualStacks 曾被标记 @Transient，
     * 备份文件与云存档中不含堆叠，恢复路径会永久清空仓库堆叠。
     * 新存档恒为 true；旧存档（false）由 SaveDataReconciler 从实例重建堆叠兜底。
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(55) val stacksSerialized: Boolean = false,
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
