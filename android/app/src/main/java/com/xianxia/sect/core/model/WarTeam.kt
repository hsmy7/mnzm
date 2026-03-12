package com.xianxia.sect.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "war_teams")
data class WarTeam(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val leaderId: String? = null,
    val leaderName: String = "",
    val memberIds: List<String> = emptyList(),
    val members: List<WarTeamMember> = emptyList(),
    val deadMemberIds: List<String> = emptyList(),
    val totalPower: Int = 0,
    val status: WarTeamStatus = WarTeamStatus.IDLE,
    val stationedSectId: String? = null,
    val stationedSectName: String = "",
    val targetSectId: String? = null,
    val targetSectName: String = "",
    val missionStartYear: Int = 0,
    val missionStartMonth: Int = 0,
    val missionDuration: Int = 0,
    val battleWins: Int = 0,
    val battleLosses: Int = 0,
    val reputation: Int = 0,
    val occupierTeamId: String? = null,
    val isOccupied: Boolean = false,
    val occupierTeamName: String = "",
    val pathSectIds: List<String> = emptyList(),
    val currentPathIndex: Int = 0,
    val travelStartTime: Long = 0,
    val travelEndTime: Long = 0,
    val monthlyCost: Int = 0
) {
    val canOperate: Boolean get() = status == WarTeamStatus.IDLE || status == WarTeamStatus.STATIONED
}

data class WarTeamMember(
    val discipleId: String = "",
    val name: String = "",
    val realm: Int = 9,
    val realmName: String = "",
    val power: Int = 0,
    val isLeader: Boolean = false
)

enum class WarTeamStatus {
    IDLE,
    STATIONED,
    ATTACKING,
    DEFENDING,
    RETURNING,
    SCOUTING,
    TRAVELING;

    val displayName: String get() = when (this) {
        IDLE -> "待命"
        STATIONED -> "驻守"
        ATTACKING -> "进攻中"
        DEFENDING -> "防守中"
        RETURNING -> "返回中"
        SCOUTING -> "侦查中"
        TRAVELING -> "行军中"
    }
}

// 防守弟子数据类
data class DefenseDisciple(
    val id: String = "",
    val name: String = "",
    val realm: Int = 9,
    val realmLayer: Int = 1,
    val realmName: String = "",
    val hp: Int = 1000,
    val maxHp: Int = 1000,
    val maxMp: Int = 500,
    val physicalAttack: Int = 100,
    val magicAttack: Int = 50,
    val physicalDefense: Int = 50,
    val magicDefense: Int = 40,
    val speed: Int = 100,
    val critRate: Double = 0.05
) {
    val isAlive: Boolean get() = hp > 0
    val hpPercent: Int get() = if (maxHp > 0) ((hp.toDouble() / maxHp) * 100).toInt() else 0
}

// 宗门战斗结果
data class WarBattleResult(
    val victory: Boolean = false,
    val attackerLosses: Int = 0,
    val defenderLosses: Int = 0,
    val loot: WarLoot = WarLoot(),
    val rounds: List<BattleRoundLog> = emptyList(),
    val playerCasualties: List<Casualty> = emptyList(),
    val aiCasualties: List<Casualty> = emptyList(),
    val log: List<String> = emptyList(),
    val timeout: Boolean = false,
    val mvp: String? = null
)

// 战斗回合日志
data class BattleRoundLog(
    val round: Int = 1,
    val actions: List<BattleAction> = emptyList()
)

// 战斗动作
data class BattleAction(
    val attacker: String = "",
    val attackerType: WarCombatantType = WarCombatantType.PLAYER,
    val target: String = "",
    val targetType: WarCombatantType = WarCombatantType.AI,
    val damage: Int = 0,
    val isCrit: Boolean = false,
    val isKill: Boolean = false,
    val message: String = ""
)

// 战斗者类型
enum class WarCombatantType {
    PLAYER,
    AI;

    val displayName: String get() = when (this) {
        PLAYER -> "玩家"
        AI -> "AI"
    }
}

// 战利品
data class WarLoot(
    val spiritStones: Long = 0,
    val materials: List<String> = emptyList()
)

// 伤亡统计
data class Casualty(
    val discipleId: String = "",
    val name: String = "",
    val realm: Int = 9,
    val injuryType: InjuryType = InjuryType.LIGHT,
    val recoveryMonths: Int = 0
)

// 伤势类型
enum class InjuryType {
    NONE,       // 无伤势
    LIGHT,      // 轻伤
    MODERATE,   // 中等伤势
    SEVERE,     // 重伤
    CRITICAL,   // 濒死
    DEAD;       // 死亡

    val displayName: String get() = when (this) {
        NONE -> "无伤势"
        LIGHT -> "轻伤"
        MODERATE -> "中等伤势"
        SEVERE -> "重伤"
        CRITICAL -> "濒死"
        DEAD -> "阵亡"
    }
}
