package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

// GameDataAlliance.kt — 联盟/战役/附庸/驻军/战斗队伍（P-2 从 GameData.kt 拆分，同包模型，序列化字段不变）

@Keep
@Serializable
data class Alliance(
    @ProtoNumber(1) val id: String = java.util.UUID.randomUUID().toString(),
    @ProtoNumber(2) val sectIds: List<String> = emptyList(),
    @ProtoNumber(3) val startYear: Int = 0,
    @ProtoNumber(4) val initiatorId: String = "",
    @ProtoNumber(5) val envoyDiscipleId: String = ""
)

/**
 * 宗门战结果类型
 */
@Keep
@Serializable
enum class SectBattleType {
    CONQUEST,      // 玩家攻占AI宗门（占领）
    LOST_SECT,     // 玩家被AI攻占宗门（丢失）
    BATTLE_WIN,    // 玩家战胜AI宗门（未占领）
    BATTLE_LOSS    // 玩家战败给AI宗门（未丢失）
}

/**
 * 玩家宗门战记录（仅记录宗门对宗门，不计妖兽和洞府）
 */
@Keep
@Serializable
data class SectBattleRecord(
    @ProtoNumber(1) val year: Int,
    @ProtoNumber(2) val type: SectBattleType
)

/**
 * 附属契约：玩家为宗主，AI为附属宗门
 */
@Keep
@Serializable
data class VassalContract(
    @ProtoNumber(1) val vassalSectId: String,
    @ProtoNumber(2) val establishedYear: Int,
    @ProtoNumber(3) val lastTributeYear: Int = 0
)

@Keep
@Serializable
data class GarrisonSlot(
    @ProtoNumber(1) val index: Int = 0,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String = "",
    @ProtoNumber(4) val discipleRealm: String = "",
    @ProtoNumber(5) val discipleSpiritRootColor: String = "#E0E0E0",
    @ProtoNumber(6) val portraitRes: String = ""
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

@Keep
@Serializable
data class BattleTeam(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "战斗队伍",
    val teamNumber: Int = 0,
    val slots: List<BattleTeamSlot> = buildList {
        repeat(2) { index ->
            add(BattleTeamSlot(index, slotType = BattleSlotType.ELDER))
        }
        repeat(8) { index ->
            add(BattleTeamSlot(index + 2, slotType = BattleSlotType.DISCIPLE))
        }
    },
    val isAtSect: Boolean = true,
    val currentX: Float = 0f,
    val currentY: Float = 0f,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val status: String = "idle",
    val targetSectId: String = "",
    val originSectId: String = "",
    val route: List<String> = emptyList(),
    val currentRouteIndex: Int = 0,
    val moveProgress: Float = 0f,
    val isOccupying: Boolean = false,
    val occupiedSectId: String = "",
    val isReturning: Boolean = false
)

@Keep
@Serializable
enum class BattleSlotType {
    ELDER,
    DISCIPLE
}

@Keep
@Serializable
data class BattleTeamSlot(
    val index: Int = 0,
    val discipleId: String = "",
    val discipleName: String = "",
    val discipleRealm: String = "",
    val slotType: BattleSlotType = BattleSlotType.DISCIPLE,
    val isAlive: Boolean = true
)

@Keep
@Serializable
data class AIBattleTeam(
    val id: String = java.util.UUID.randomUUID().toString(),
    val attackerSectId: String = "",
    val attackerSectName: String = "",
    val defenderSectId: String = "",
    val defenderSectName: String = "",
    val disciples: List<Disciple> = emptyList(),
    val currentX: Float = 0f,
    val currentY: Float = 0f,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val attackerStartX: Float = 0f,
    val attackerStartY: Float = 0f,
    val moveProgress: Float = 0f,
    val status: String = "moving",
    val route: List<String> = emptyList(),
    val currentRouteIndex: Int = 0,
    val startYear: Int = 0,
    val startMonth: Int = 0,
    val isPlayerDefender: Boolean = false,
    val isGarrison: Boolean = false,
    val garrisonSectId: String = "",
    val garrisonSectName: String = ""
)
