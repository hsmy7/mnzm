package com.xianxia.sect.data.model

import androidx.annotation.Keep
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.MailEntity
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



/**
 * 存档槽位列表项（元数据投影，三态：有存档 / 空档 / 读取失败）。
 *
 * [isEmpty] 与 [isLoadError] 互斥语义：空档 = 槽位无数据（可创建新游戏）；
 * 读取失败 = 槽位有数据但查询异常（数据库不可达/schema 不匹配等），
 * **不得**当作空档提供"点击创建"入口——损坏存档被空档伪装覆盖是数据丢失事故。
 */
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
    val customName: String = "",
    val isLoadError: Boolean = false
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
     * false 表示存档来自堆叠未序列化的旧格式（旧备份/云档），堆叠数据缺失，
     * 由 SaveDataReconciler 从实例重建兜底。
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(55) val stacksSerialized: Boolean = false,
    @ProtoNumber(7) val pills: List<Pill>,
    @ProtoNumber(8) val materials: List<Material>,
    @ProtoNumber(9) val herbs: List<Herb>,
    @ProtoNumber(10) val seeds: List<Seed>,
    @ProtoNumber(15) val storageBags: List<StorageBag> = emptyList(),
    @ProtoNumber(13) val battleLogs: List<BattleLog> = emptyList(),
    @ProtoNumber(14) val alliances: List<Alliance> = emptyList(),
    @ProtoNumber(52) val productionSlots: List<ProductionSlot> = emptyList(),
    /**
     * 槽位邮件快照（SR-1：邮件并入 SaveData，云唯一存档下换设备不丢邮件）。
     *
     * 保存 = 从 `mails` 表读当前 slot 全量入快照；加载/云恢复 = 整对象替换回表；
     * 删档走 `clearAllSlotTables` 清单纪律（守卫自动覆盖）。
     * 兼容：旧档无此字段 ⇒ 反序列化默认空表（向后兼容单向，无需迁移器条目）；
     * proto 号 56 = 本类现有最大 55+1（908f24180 升序口径），受
     * `ProtoNumberUniquenessTest` IN4 守卫 + mails=56 方向锁约束。
     */
    @ProtoNumber(56) val mails: List<MailEntity> = emptyList()
)
