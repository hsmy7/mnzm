package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked

// SecretRealmModels.kt — 远古秘境玩法模型（地图实例/探索会话/事件/背包/AI 队伍）

/** 远古秘境地图实例（id 为空串表示当前不存在） */
@Keep
@Serializable
@Immutable
data class SecretRealmState(
    @ProtoNumber(1) val id: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(2) val name: String = "远古秘境",
    @ProtoNumber(3) val x: Float = 0f,
    @ProtoNumber(4) val y: Float = 0f,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(5) val spawnYear: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(6) val spawnMonth: Int = 1,
    @ProtoNumber(7) val spriteIndex: Int = 0
) {
    /** 秘境当前是否存在于世界地图（运行时推导，不序列化） */
    @Transient
    val exists: Boolean get() = id.isNotEmpty()
}

/** 远古秘境探索会话——完整持久化，支撑断线续玩 */
@Keep
@Serializable
@Immutable
data class SecretRealmExplorationSession(
    @ProtoNumber(1) val secretRealmId: String = "",
    /** 探索队伍成员动态状态（含濒死/死亡标记） */
    @ProtoNumber(2) val members: List<SecretRealmMemberState> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(3) val stamina: Int = 20,
    @ProtoNumber(4) val backpack: SecretRealmBackpack = SecretRealmBackpack(),
    /** 最近一个未完成事件（进行中或待选择） */
    @ProtoNumber(5) val currentEvent: SecretRealmEventRecord? = null,
    /** 已完成事件序列（每次选择后 append，读档展示历史） */
    @ProtoNumber(6) val eventHistory: List<SecretRealmEventRecord> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(7) val startYear: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(8) val startMonth: Int = 1,
    /** 上个事件的结果描述（衔接事件前缀） */
    @ProtoNumber(9) val resultMessage: String = ""
) {
    /** 会话是否有活跃探索（运行时推导，不序列化） */
    @Transient
    val isActive: Boolean get() = members.isNotEmpty()
}

/** 探索队伍成员动态状态（探索期间实时更新，读档恢复） */
@Keep
@Serializable
@Immutable
data class SecretRealmMemberState(
    @ProtoNumber(1) val discipleId: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val portraitRes: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(4) val realm: Int = 9,
    @ProtoNumber(5) val realmName: String = "",
    /** 当前血量，-1 = 满血（与 Disciple.combat.currentHp 语义一致） */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(6) val currentHp: Int = -1,
    /** 重伤濒死：首次战斗阵亡保命状态（红字代替血条；再参战血量 1） */
    @ProtoNumber(7) val isDying: Boolean = false,
    /** 已永久死亡（濒死后再阵亡，已 markDead） */
    @ProtoNumber(8) val isDead: Boolean = false,
    /** 战斗口径最大生命值（含装备/功法加成，由战斗写回维护）；0 = 未知（回退基础装配值） */
    @ProtoNumber(9) val maxHp: Int = 0
)

/** 探索事件记录——整个事件序列化（含参数），读档后可直接继续 */
@Keep
@Serializable
@Immutable
data class SecretRealmEventRecord(
    /** SecretRealmEventType.name：BEAST_ENCOUNTER / REST_AREA / RUIN_EXPLORE / RUIN_RESULT / BRIDGE */
    @ProtoNumber(1) val eventType: String = "",
    @ProtoNumber(2) val title: String = "",
    @ProtoNumber(3) val description: String = "",
    @ProtoNumber(4) val options: List<SecretRealmOption> = emptyList(),
    /** 已选择选项下标，-1 = 未选择（进行中） */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(5) val chosenOptionIndex: Int = -1,
    /** 选择后的反馈文本（成为衔接事件前缀） */
    @ProtoNumber(6) val resultText: String = "",
    @ProtoNumber(7) val params: SecretRealmEventParams = SecretRealmEventParams(),
    @ProtoNumber(8) val absoluteMonth: Int = 0
)

/** 事件选项（效果由 eventType + optionIndex + staminaCost 在引擎解析，不持久化） */
@Keep
@Serializable
@Immutable
data class SecretRealmOption(
    @ProtoNumber(1) val label: String = "",
    @ProtoNumber(2) val description: String = "",
    /** 选择该选项消耗的体力；默认 1（所有选项统一扣 1，个别选项差异化扣费如"仔细搜寻"扣 2） */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(3) val staminaCost: Int = 1
)

/** 妖兽事件参数（战斗/掉落/损失，读档一致性关键） */
@Keep
@Serializable
@Immutable
data class SecretRealmEventParams(
    @ProtoNumber(1) val beastTypeName: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(2) val beastRealm: Int = 9,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(3) val beastCount: Int = 1,
    /** 偷袭成功：妖兽初始血量 -10% */
    @ProtoNumber(4) val ambushSucceeded: Boolean = false,
    /** 妖兽层数（1..9，境界显示如"炼气三层"） */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(8) val beastLayer: Int = 1,
    /** 战斗失败丢失件数（随机选定后写入） */
    @ProtoNumber(5) val lostItemCount: Int = 0,
    @ProtoNumber(6) val spiritStones: Long = 0L,
    @ProtoNumber(7) val itemRewards: List<SecretRealmRewardItem> = emptyList()
)

/** 探索背包——暂存探索所得，结束统一入宗门仓库 */
@Keep
@Serializable
@Immutable
data class SecretRealmBackpack(
    @ProtoNumber(1) val spiritStones: Long = 0L,
    @ProtoNumber(2) val equipment: List<EquipmentStack> = emptyList(),
    @ProtoNumber(3) val manuals: List<ManualStack> = emptyList(),
    @ProtoNumber(4) val pills: List<Pill> = emptyList(),
    @ProtoNumber(5) val materials: List<Material> = emptyList(),
    @ProtoNumber(6) val herbs: List<Herb> = emptyList(),
    /** 种子（遗迹秘宝可产出；protobuf list 缺省即空，与其余六类字段一致） */
    @ProtoNumber(7) val seeds: List<Seed> = emptyList()
) {
    /** 背包物品总件数（运行时推导，不序列化） */
    @Transient
    val totalItemCount: Int
        get() = equipment.size + manuals.size + pills.size + materials.size + herbs.size + seeds.size
}

/** 奖励物品描述（结算时才实例化入仓） */
@Keep
@Serializable
@Immutable
data class SecretRealmRewardItem(
    /** equipment / manual / pill / material / herb / seed */
    @ProtoNumber(1) val type: String = "",
    @ProtoNumber(2) val itemId: String = "",
    @ProtoNumber(3) val name: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(4) val rarity: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(5) val quantity: Int = 1
)

/** AI 宗门探索队伍（仅派遣占位：AI 弟子进入秘境但无任何行为） */
@Keep
@Serializable
@Immutable
data class SecretRealmAITeam(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val sectId: String = "",
    @ProtoNumber(3) val sectName: String = "",
    @ProtoNumber(4) val members: List<SecretRealmAIMember> = emptyList()
)

/** AI 宗门队伍成员（真实弟子快照） */
@Keep
@Serializable
@Immutable
data class SecretRealmAIMember(
    @ProtoNumber(1) val discipleId: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val portraitRes: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @ProtoNumber(4) val realm: Int = 9
)

/**
 * 秘境探索中存活成员 ID（Int 集合）。
 *
 * 供修炼/恢复/突破/自动行为等系统的逐弟子循环跳过使用——循环内直接 `id in set`，
 * 避免每弟子每帧 `id.toString()` 字符串分配（与 P-4 优化方向一致）。
 */
fun GameData.secretRealmMemberIds(): Set<Int> =
    secretRealmSession.members.asSequence()
        .filter { !it.isDead }
        .mapNotNull { it.discipleId.toIntOrNull() }
        .toSet()

/** 秘境事件类型 */
enum class SecretRealmEventType {
    /** 遭遇妖兽（唯一战斗事件） */
    BEAST_ENCOUNTER,
    /** 空地事件（休整恢复 / 继续前进），不触发战斗 */
    REST_AREA,
    /** 发现遗迹事件（选项：直接离开 / 简单搜寻 / 仔细搜寻） */
    RUIN_EXPLORE,
    /** 遗迹搜寻结果子事件（空无一物 / 发现秘宝，title 区分） */
    RUIN_RESULT
}
