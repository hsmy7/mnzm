package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import com.xianxia.sect.core.model.production.SlotType
import com.xianxia.sect.core.util.TimeProgressUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber
@Keep
@Serializable
@Entity(
    tableName = "exploration_teams",
    primaryKeys = ["id", "slot_id"]
)
data class ExplorationTeam(
    @ColumnInfo(name = "id")
    @ProtoNumber(1)
    val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @Transient
    var slotId: Int = 0,

    @ProtoNumber(2)
    val name: String = "",
    @Transient
    val caveId: String? = null,
    @ProtoNumber(13)
    val caveName: String = "",
    @ProtoNumber(14)
    val dungeon: String = "",
    @ProtoNumber(15)
    val dungeonName: String = "",
    @ProtoNumber(3)
    val memberIds: List<String> = emptyList(),
    @ProtoNumber(16)
    val memberNames: List<String> = emptyList(),
    @ProtoNumber(6)
    val startYear: Int = 1,
    @ProtoNumber(7)
    val startMonth: Int = 1,
    @ProtoNumber(17)
    val startDay: Int = 1,
    @ProtoNumber(8)
    val duration: Int = 1,
    @ProtoNumber(4)
    @Serializable(with = ExplorationStatusAsStringSerializer::class)
    val status: ExplorationStatus = ExplorationStatus.TRAVELING,
    @ProtoNumber(9)
    val progress: Int = 0,
    @Transient
    val scoutTargetSectId: String? = null,
    @ProtoNumber(32)
    val scoutTargetSectName: String = "",
    @ProtoNumber(18)
    val currentX: Float = 0f,
    @ProtoNumber(19)
    val currentY: Float = 0f,
    @ProtoNumber(20)
    val targetX: Float = 0f,
    @ProtoNumber(21)
    val targetY: Float = 0f,
    @ProtoNumber(22)
    val moveProgress: Float = 0f,
    @ProtoNumber(23)
    val arrivalYear: Int = 0,
    @ProtoNumber(24)
    val arrivalMonth: Int = 0,
    @ProtoNumber(25)
    val arrivalDay: Int = 0,
    @ProtoNumber(26)
    val route: List<String> = emptyList(),
    @ProtoNumber(27)
    val currentRouteIndex: Int = 0,
    @ProtoNumber(28)
    val currentSegmentProgress: Float = 0f,
    @ProtoNumber(29)
    val pityCounterEquipment: Int = 0,
    @ProtoNumber(30)
    val pityCounterPill: Int = 0,
    @ProtoNumber(31)
    val pityCounterManual: Int = 0
) {
    val isTraveling: Boolean get() = status == ExplorationStatus.TRAVELING
    val isExploring: Boolean get() = status == ExplorationStatus.EXPLORING
    val isComplete: Boolean get() = status == ExplorationStatus.COMPLETED
    val isScouting: Boolean get() = status == ExplorationStatus.SCOUTING
    val isMoving: Boolean get() = isScouting && moveProgress < 1f

    fun getRemainingMonths(currentYear: Int, currentMonth: Int): Int {
        return TimeProgressUtil.calculateRemainingMonths(startYear, startMonth, duration, currentYear, currentMonth)
    }

    fun getProgressPercent(currentYear: Int, currentMonth: Int): Int {
        if (duration <= 0) return 0
        return TimeProgressUtil.calculateProgressPercent(startYear, startMonth, duration, currentYear, currentMonth)
    }
}

@Keep
@Serializable
enum class ExplorationStatus {
    TRAVELING,
    EXPLORING,
    DANGER,
    COMPLETED,
    SCOUTING;

    val displayName: String get() = when (this) {
        TRAVELING -> "前往中"
        EXPLORING -> "探索中"
        DANGER -> "遇险中"
        COMPLETED -> "已完成"
        SCOUTING -> "侦查中"
    }
}

@Keep
@Serializable
@Entity(
    tableName = "building_slots",
    primaryKeys = ["id", "slot_id"]
)
data class BuildingSlot(
    @ColumnInfo(name = "id")
    @ProtoNumber(1)
    val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @Transient
    var slotId: Int = 0,

    @ProtoNumber(2)
    val buildingId: String = "",
    @ProtoNumber(3)
    val slotIndex: Int = 0,
    @ProtoNumber(4)
    val type: SlotType = SlotType.IDLE,
    @Transient
    val discipleId: String? = null,
    @ProtoNumber(6)
    val discipleName: String = "",
    @ProtoNumber(7)
    val startYear: Int = 0,
    @ProtoNumber(8)
    val startMonth: Int = 0,
    @ProtoNumber(9)
    val duration: Int = 0,
    @Transient
    val recipeId: String? = null,
    @ProtoNumber(11)
    val recipeName: String = "",
    @ProtoNumber(12)
    val status: SlotStatus = SlotStatus.IDLE
) {
    fun remainingTime(currentYear: Int, currentMonth: Int): Int {
        if (status != SlotStatus.WORKING) return 0
        return TimeProgressUtil.calculateRemainingMonths(startYear, startMonth, duration, currentYear, currentMonth)
    }

    fun isFinished(currentYear: Int, currentMonth: Int): Boolean {
        if (status != SlotStatus.WORKING) return false
        return TimeProgressUtil.isTimeElapsed(startYear, startMonth, duration, currentYear, currentMonth)
    }
}

@Keep
@Serializable
enum class SlotStatus {
    IDLE,
    WORKING,
    COMPLETED;

    val displayName: String get() = when (this) {
        IDLE -> "空闲"
        WORKING -> "进行中"
        COMPLETED -> "已完成"
    }
}


@Keep
@Serializable
@Entity(
    tableName = "recipes",
    primaryKeys = ["id", "slot_id"]
)
data class Recipe(
    @ColumnInfo(name = "id")
    val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    var slotId: Int = 0,

    val name: String = "",
    val description: String = "",
    val type: RecipeType = RecipeType.PILL,
    val isUnlocked: Boolean = false,
    val unlockYear: Int = 0,
    val unlockMonth: Int = 0,
    val requiredMaterials: Map<String, Int> = emptyMap(),
    val outputItemId: String = "",
    val outputItemName: String = "",
    val outputQuantity: Int = 1,
    val duration: Int = 1
)

@Keep
@Serializable
enum class RecipeType {
    PILL,
    FORGE;

    val displayName: String get() = when (this) {
        PILL -> "丹方"
        FORGE -> "锻造"
    }
}

@Keep
@Serializable
@Entity(
    tableName = "battle_logs",
    primaryKeys = ["id", "slot_id"]
)
data class BattleLog(
    @ColumnInfo(name = "id")
    @ProtoNumber(1)
    val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "slot_id")
    @Transient
    var slotId: Int = 0,

    @ProtoNumber(2)
    val timestamp: Long = System.currentTimeMillis(),
    @ProtoNumber(3)
    val year: Int = 1,
    @ProtoNumber(4)
    val month: Int = 1,
    @ProtoNumber(14)
    @Serializable(with = BattleTypeAsStringSerializer::class)
    val type: BattleType = BattleType.PVE,
    @ProtoNumber(6)
    val attackerName: String = "",
    @ProtoNumber(8)
    val defenderName: String = "",
    @ProtoNumber(9)
    @Serializable(with = BattleResultAsStringSerializer::class)
    val result: BattleResult = BattleResult.DRAW,
    @ProtoNumber(15)
    val details: String = "",
    @ProtoNumber(16)
    val drops: List<String> = emptyList(),
    @ProtoNumber(17)
    val dungeonName: String = "",
    @Transient
    val teamId: String? = null,
    val teamMembers: List<BattleLogMember> = emptyList(),
    val enemies: List<BattleLogEnemy> = emptyList(),
    @ProtoNumber(10)
    val rounds: List<BattleLogRound> = emptyList(),
    @ProtoNumber(19)
    val turns: Int = 0,
    @ProtoNumber(20)
    val teamCasualties: Int = 0,
    @ProtoNumber(21)
    val beastsDefeated: Int = 0,
    @Transient
    val battleResult: BattleLogResult? = null
) {
    val displayTime: String get() = "第${year}年${month}月"
}

@Keep
@Serializable
enum class BattleType {
    PVE,
    PVP,
    SECT_WAR,
    CAVE_EXPLORATION,
    SCOUT,
    ENCOUNTER;

    val displayName: String get() = when (this) {
        PVE -> "PVE战斗"
        PVP -> "PVP战斗"
        SECT_WAR -> "宗门战"
        CAVE_EXPLORATION -> "洞府探索"
        SCOUT -> "探查"
        ENCOUNTER -> "遭遇战"
    }
}

@Keep
@Serializable
enum class BattleResult {
    WIN,
    LOSE,
    DRAW;

    val displayName: String get() = when (this) {
        WIN -> "胜利"
        LOSE -> "失败"
        DRAW -> "平局"
    }

    val winner: String get() = when (this) {
        WIN -> "team"
        LOSE -> "beasts"
        DRAW -> "draw"
    }
}

@Keep
@Serializable
data class CultivatorCave(
    @ProtoNumber(1)
    val id: String = java.util.UUID.randomUUID().toString(),
    @ProtoNumber(2)
    val name: String = "",
    @ProtoNumber(3)
    val ownerRealm: Int = 5,
    @ProtoNumber(7)
    val ownerRealmName: String = "",
    @ProtoNumber(4)
    val x: Float = 0f,
    @ProtoNumber(5)
    val y: Float = 0f,
    @ProtoNumber(11)
    val spawnYear: Int = 1,
    @ProtoNumber(12)
    val spawnMonth: Int = 1,
    @ProtoNumber(13)
    val expiryYear: Int = 1,
    @ProtoNumber(14)
    val expiryMonth: Int = 1,
    @ProtoNumber(10)
    val isExplored: Boolean = false,
    @Transient
    val exploredByTeamId: String? = null,
    @ProtoNumber(16)
    @Serializable(with = CaveStatusAsStringSerializer::class)
    val status: CaveStatus = CaveStatus.AVAILABLE,
    @ProtoNumber(17)
    val canOperate: Boolean = true,
    @ProtoNumber(18)
    val isOwned: Boolean = false,
    @ProtoNumber(19)
    val connectedSects: List<String> = emptyList(),
    @ProtoNumber(20)
    val mineSlots: List<MineSlot> = emptyList(),
    @ProtoNumber(21)
    val occupationTime: Long = 0
) {
    val isAvailable: Boolean get() = status == CaveStatus.AVAILABLE
    val isExpired: Boolean get() = status == CaveStatus.EXPIRED

    fun isExpired(currentYear: Int, currentMonth: Int): Boolean {
        if (currentYear > expiryYear) return true
        if (currentYear == expiryYear && currentMonth >= expiryMonth) return true
        return false
    }

    fun getRemainingMonths(currentYear: Int, currentMonth: Int): Int {
        val yearDiff = (expiryYear - currentYear).toLong()
        val monthDiff = (expiryMonth - currentMonth).toLong()
        val totalMonths = yearDiff * 12 + monthDiff
        return totalMonths.toInt().coerceAtLeast(0)
    }
}

@Keep
@Serializable
enum class CaveStatus {
    AVAILABLE,
    EXPLORING,
    EXPLORED,
    EXPIRED;
    
    val displayName: String get() = when (this) {
        AVAILABLE -> "可探索"
        EXPLORING -> "探索中"
        EXPLORED -> "已探索"
        EXPIRED -> "已消失"
    }
}

@Keep
@Serializable
enum class AITeamStatus {
    EXPLORING,
    DEFEATED;
    
    val displayName: String get() = when (this) {
        EXPLORING -> "探索中"
        DEFEATED -> "已击败"
    }
}

@Keep
@Serializable
data class AICaveDisciple(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val realm: Int = 5,
    val realmName: String = "",
    @ProtoNumber(5) val hp: Int = 1000,
    @ProtoNumber(6) val maxHp: Int = 1000,
    @ProtoNumber(7) val mp: Int = 500,
    @ProtoNumber(8) val maxMp: Int = 500,
    @ProtoNumber(9) val physicalAttack: Int = 100,
    @ProtoNumber(10) val magicAttack: Int = 50,
    @ProtoNumber(11) val physicalDefense: Int = 50,
    @ProtoNumber(12) val magicDefense: Int = 40,
    @ProtoNumber(13) val speed: Int = 100,
    @ProtoNumber(14) val critRate: Double = 0.05,
    @ProtoNumber(15) val equipments: List<AIRandomEquipment> = emptyList(),
    @ProtoNumber(16) val manuals: List<AIRandomManual> = emptyList()
) {
    val isAlive: Boolean get() = hp > 0
    val hpPercent: Int get() = if (maxHp > 0) ((hp.toDouble() / maxHp) * 100).toInt() else 0
}

@Keep
@Serializable
data class AIRandomEquipment(
    @ProtoNumber(1) val slot: EquipmentSlot,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val rarity: Int,
    @ProtoNumber(4) val nurtureLevel: Int,
    @ProtoNumber(5) val physicalAttack: Int = 0,
    @ProtoNumber(6) val magicAttack: Int = 0,
    @ProtoNumber(7) val physicalDefense: Int = 0,
    @ProtoNumber(8) val magicDefense: Int = 0,
    @ProtoNumber(9) val speed: Int = 0,
    @ProtoNumber(10) val hp: Int = 0,
    @ProtoNumber(11) val mp: Int = 0
)

@Keep
@Serializable
data class AIRandomManual(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val rarity: Int,
    @ProtoNumber(3) val mastery: Int,
    @ProtoNumber(4) val stats: Map<String, Int> = emptyMap()
)

@Keep
@Serializable
data class CaveExplorationTeam(
    val id: String = java.util.UUID.randomUUID().toString(),
    val caveId: String = "",
    val caveName: String = "",
    val memberIds: List<String> = emptyList(),
    val memberNames: List<String> = emptyList(),
    val startYear: Int = 1,
    val startMonth: Int = 1,
    val duration: Int = 1,
    val status: CaveExplorationStatus = CaveExplorationStatus.TRAVELING,
    val startX: Float = 2000f,
    val startY: Float = 1750f,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val currentX: Float = 2000f,
    val currentY: Float = 1750f,
    val moveProgress: Float = 0f
) {
    val isTraveling: Boolean get() = status == CaveExplorationStatus.TRAVELING
    val isExploring: Boolean get() = status == CaveExplorationStatus.EXPLORING
    val isComplete: Boolean get() = status == CaveExplorationStatus.COMPLETED
    val isMoving: Boolean get() = isTraveling && moveProgress < 1f
    
    fun getRemainingMonths(currentYear: Int, currentMonth: Int): Int {
        return TimeProgressUtil.calculateRemainingMonths(startYear, startMonth, duration, currentYear, currentMonth)
    }

    fun getProgressPercent(currentYear: Int, currentMonth: Int): Int {
        if (duration <= 0) return 0
        return TimeProgressUtil.calculateProgressPercent(startYear, startMonth, duration, currentYear, currentMonth)
    }
}

@Keep
@Serializable
enum class CaveExplorationStatus {
    TRAVELING,
    EXPLORING,
    COMPLETED;

    val displayName: String get() = when (this) {
        TRAVELING -> "前往中"
        EXPLORING -> "探索中"
        COMPLETED -> "已完成"
    }
}

// 战斗日志成员
@Keep
@Serializable
data class BattleLogMember(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val realm: Int = 9,
    @ProtoNumber(10) val realmName: String = "",
    @ProtoNumber(11) val realmLayer: Int = 0,
    @ProtoNumber(5) val hp: Int = 0,
    @ProtoNumber(6) val maxHp: Int = 0,
    @ProtoNumber(7) val mp: Int = 0,
    @ProtoNumber(8) val maxMp: Int = 0,
    @ProtoNumber(4) val isAlive: Boolean = true,
    @ProtoNumber(9) val portraitRes: String = ""
)

@Keep
@Serializable
data class BattleLogEnemy(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val realm: Int = 9,
    @ProtoNumber(10) val realmName: String = "",
    @ProtoNumber(11) val realmLayer: Int = 0,
    @ProtoNumber(5) val hp: Int = 0,
    @ProtoNumber(6) val maxHp: Int = 0,
    @ProtoNumber(4) val isAlive: Boolean = true,
    @ProtoNumber(9) val portraitRes: String = ""
)

// 战斗日志回合
@Keep
@Serializable
data class BattleLogRound(
    @ProtoNumber(1) val roundNumber: Int = 1,
    @ProtoNumber(2) val actions: List<BattleLogAction> = emptyList()
)

// 战斗日志动作
@Keep
@Serializable
data class BattleLogAction(
    @ProtoNumber(1) val type: String = "",
    @ProtoNumber(2) val attacker: String = "",
    @ProtoNumber(3) val attackerType: String = "",
    @ProtoNumber(4) val target: String = "",
    @ProtoNumber(7) val damage: Int = 0,
    @ProtoNumber(11) val damageType: String = "",
    @ProtoNumber(8) val isCrit: Boolean = false,
    @ProtoNumber(12) val isKill: Boolean = false,
    @ProtoNumber(10) val message: String = "",
    @Transient val skillName: String? = null
)

// 战斗日志结果
@Keep
@Serializable
data class BattleLogResult(
    val winner: String = "",
    val isPlayerWin: Boolean = false,
    val turns: Int = 0,
    val rounds: Int = 0,
    val teamCasualties: Int = 0,
    val beastsDefeated: Int = 0,
    val drops: List<String> = emptyList()
)

// 战斗奖励物品（用于战斗结算界面展示战利品）
@Keep
@Serializable
data class BattleRewardItem(
    @ProtoNumber(1) val itemId: String = "",
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val quantity: Int = 1,
    @ProtoNumber(4) val rarity: Int = 1,
    @ProtoNumber(5) val type: String = "material"  // "spiritStones", "equipment", "manual", "pill", "material"
)

// AI洞府探索队伍（用于GameData）
@Keep
@Serializable
data class AICaveTeam(
    @ProtoNumber(1) val id: String = java.util.UUID.randomUUID().toString(),
    @ProtoNumber(4) val caveId: String = "",
    @ProtoNumber(2) val sectId: String = "",
    @ProtoNumber(3) val sectName: String = "",
    @ProtoNumber(9) val memberCount: Int = 5,
    @ProtoNumber(10) val avgRealm: Int = 5,
    @ProtoNumber(11) val avgRealmName: String = "",
    @ProtoNumber(5) val disciples: List<AICaveDisciple> = emptyList(),
    @ProtoNumber(6)
    @Serializable(with = AITeamStatusAsStringSerializer::class)
    val status: AITeamStatus = AITeamStatus.EXPLORING
) {
    val isExploring: Boolean get() = status == AITeamStatus.EXPLORING
    val isDefeated: Boolean get() = status == AITeamStatus.DEFEATED
}
