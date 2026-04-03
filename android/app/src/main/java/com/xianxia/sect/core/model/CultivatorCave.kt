package com.xianxia.sect.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xianxia.sect.core.model.production.SlotType
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "exploration_teams")
data class ExplorationTeam(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val caveId: String? = null,
    val caveName: String = "",
    val dungeon: String = "",
    val dungeonName: String = "",
    val memberIds: List<String> = emptyList(),
    val memberNames: List<String> = emptyList(),
    val startYear: Int = 1,
    val startMonth: Int = 1,
    val startDay: Int = 1,
    val duration: Int = 1,
    val status: ExplorationStatus = ExplorationStatus.TRAVELING,
    val progress: Int = 0,
    val scoutTargetSectId: String? = null,
    val scoutTargetSectName: String = "",
    val currentX: Float = 0f,
    val currentY: Float = 0f,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val moveProgress: Float = 0f,
    val arrivalYear: Int = 0,
    val arrivalMonth: Int = 0,
    val arrivalDay: Int = 0,
    val route: List<String> = emptyList(),
    val currentRouteIndex: Int = 0,
    val currentSegmentProgress: Float = 0f,
    val pityCounterEquipment: Int = 0,
    val pityCounterPill: Int = 0,
    val pityCounterManual: Int = 0
) {
    val isTraveling: Boolean get() = status == ExplorationStatus.TRAVELING
    val isExploring: Boolean get() = status == ExplorationStatus.EXPLORING
    val isComplete: Boolean get() = status == ExplorationStatus.COMPLETED
    val isScouting: Boolean get() = status == ExplorationStatus.SCOUTING
    val isMoving: Boolean get() = isScouting && moveProgress < 1f

    fun getRemainingMonths(currentYear: Int, currentMonth: Int): Int {
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val totalMonths = yearDiff * 12 + monthDiff
        return (duration - totalMonths.toInt()).coerceAtLeast(0)
    }

    fun getProgressPercent(currentYear: Int, currentMonth: Int): Int {
        if (duration <= 0) return 0
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsed = yearDiff * 12 + monthDiff
        return ((elapsed.toDouble() / duration) * 100).toInt().coerceIn(0, 100)
    }
}

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

@Serializable
@Entity(tableName = "building_slots")
data class BuildingSlot(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val buildingId: String = "",
    val slotIndex: Int = 0,
    val type: SlotType = SlotType.IDLE,
    val discipleId: String? = null,
    val discipleName: String = "",
    val startYear: Int = 0,
    val startMonth: Int = 0,
    val duration: Int = 0,
    val recipeId: String? = null,
    val recipeName: String = "",
    val status: SlotStatus = SlotStatus.IDLE
) {
    fun remainingTime(currentYear: Int, currentMonth: Int): Int {
        if (status != SlotStatus.WORKING) return 0
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsedMonths = yearDiff * 12 + monthDiff
        return (duration - elapsedMonths.toInt()).coerceAtLeast(0)
    }

    fun isFinished(currentYear: Int, currentMonth: Int): Boolean {
        if (status != SlotStatus.WORKING) return false
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsedMonths = yearDiff * 12 + monthDiff
        return elapsedMonths >= duration
    }
}

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

@Serializable
@Entity(tableName = "game_events")
data class GameEvent(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val message: String = "",
    val type: EventType = EventType.INFO,
    val timestamp: Long = System.currentTimeMillis(),
    val year: Int = 1,
    val month: Int = 1
) {
    val displayTime: String get() = "第${year}年${month}月"
}

@Serializable
enum class EventType {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    BATTLE,
    BREAKTHROUGH,
    DANGER;

    val displayName: String get() = when (this) {
        INFO -> "信息"
        SUCCESS -> "成功"
        WARNING -> "警告"
        ERROR -> "错误"
        BATTLE -> "战斗"
        BREAKTHROUGH -> "突破"
        DANGER -> "危险"
    }
}

@Serializable
@Entity(tableName = "dungeons")
data class Dungeon(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val realm: Int = 9,
    val realmName: String = "",
    val difficulty: Int = 1,
    val isUnlocked: Boolean = false,
    val unlockYear: Int = 0,
    val unlockMonth: Int = 0,
    val completedCount: Int = 0,
    val rewards: DungeonRewards = DungeonRewards()
)

@Serializable
data class DungeonRewards(
    val spiritStones: Int = 0,
    val equipmentRarity: Int = 1,
    val materialCount: Int = 0
)

@Serializable
@Entity(tableName = "recipes")
data class Recipe(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
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

@Serializable
enum class RecipeType {
    PILL,
    FORGE;

    val displayName: String get() = when (this) {
        PILL -> "丹方"
        FORGE -> "锻造"
    }
}

@Serializable
@Entity(tableName = "battle_logs")
data class BattleLog(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val year: Int = 1,
    val month: Int = 1,
    val type: BattleType = BattleType.PVE,
    val attackerName: String = "",
    val defenderName: String = "",
    val result: BattleResult = BattleResult.DRAW,
    val details: String = "",
    val drops: List<String> = emptyList(),
    val dungeonName: String = "",
    val teamId: String? = null,
    val teamMembers: List<BattleLogMember> = emptyList(),
    val enemies: List<BattleLogEnemy> = emptyList(),
    val rounds: List<BattleLogRound> = emptyList(),
    val turns: Int = 0,
    val teamCasualties: Int = 0,
    val beastsDefeated: Int = 0,
    val battleResult: BattleLogResult? = null
) {
    val displayTime: String get() = "第${year}年${month}月"
}

@Serializable
enum class BattleType {
    PVE,
    PVP,
    SECT_WAR,
    TOURNAMENT,
    CAVE_EXPLORATION;

    val displayName: String get() = when (this) {
        PVE -> "PVE战斗"
        PVP -> "PVP战斗"
        SECT_WAR -> "宗门战"
        TOURNAMENT -> "大比"
        CAVE_EXPLORATION -> "洞府探索"
    }
}

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

@Serializable
data class CultivatorCave(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val ownerRealm: Int = 5,
    val ownerRealmName: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val spawnYear: Int = 1,
    val spawnMonth: Int = 1,
    val expiryYear: Int = 1,
    val expiryMonth: Int = 1,
    val isExplored: Boolean = false,
    val exploredByTeamId: String? = null,
    val status: CaveStatus = CaveStatus.AVAILABLE,
    val canOperate: Boolean = true,
    val isOwned: Boolean = false,
    val connectedSects: List<String> = emptyList(),
    val mineSlots: List<MineSlot> = emptyList(),
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

@Serializable
enum class AITeamStatus {
    EXPLORING,
    DEFEATED;
    
    val displayName: String get() = when (this) {
        EXPLORING -> "探索中"
        DEFEATED -> "已击败"
    }
}

@Serializable
data class AICaveDisciple(
    val id: String = "",
    val name: String = "",
    val realm: Int = 5,
    val realmName: String = "",
    val hp: Int = 1000,
    val maxHp: Int = 1000,
    val mp: Int = 500,
    val maxMp: Int = 500,
    val physicalAttack: Int = 100,
    val magicAttack: Int = 50,
    val physicalDefense: Int = 50,
    val magicDefense: Int = 40,
    val speed: Int = 100,
    val critRate: Double = 0.05,
    val equipments: List<AIRandomEquipment> = emptyList(),
    val manuals: List<AIRandomManual> = emptyList()
) {
    val isAlive: Boolean get() = hp > 0
    val hpPercent: Int get() = if (maxHp > 0) ((hp.toDouble() / maxHp) * 100).toInt() else 0
}

@Serializable
data class AIRandomEquipment(
    val slot: EquipmentSlot,
    val name: String,
    val rarity: Int,
    val nurtureLevel: Int,
    val physicalAttack: Int = 0,
    val magicAttack: Int = 0,
    val physicalDefense: Int = 0,
    val magicDefense: Int = 0,
    val speed: Int = 0,
    val hp: Int = 0,
    val mp: Int = 0
)

@Serializable
data class AIRandomManual(
    val name: String,
    val rarity: Int,
    val mastery: Int,
    val stats: Map<String, Int> = emptyMap()
)

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
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val totalMonths = yearDiff * 12 + monthDiff
        return (duration - totalMonths.toInt()).coerceAtLeast(0)
    }

    fun getProgressPercent(currentYear: Int, currentMonth: Int): Int {
        if (duration <= 0) return 0
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsed = yearDiff * 12 + monthDiff
        return ((elapsed.toDouble() / duration) * 100).toInt().coerceIn(0, 100)
    }
}

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
@Serializable
data class BattleLogMember(
    val id: String = "",
    val name: String = "",
    val realm: Int = 9,
    val realmName: String = "",
    val realmLayer: Int = 0,
    val hp: Int = 0,
    val maxHp: Int = 0,
    val isAlive: Boolean = true
)

@Serializable
data class BattleLogEnemy(
    val id: String = "",
    val name: String = "",
    val realm: Int = 9,
    val realmName: String = "",
    val realmLayer: Int = 0,
    val hp: Int = 0,
    val maxHp: Int = 0,
    val isAlive: Boolean = true
)

// 战斗日志回合
@Serializable
data class BattleLogRound(
    val roundNumber: Int = 1,
    val actions: List<BattleLogAction> = emptyList()
)

// 战斗日志动作
@Serializable
data class BattleLogAction(
    val type: String = "",
    val attacker: String = "",
    val attackerType: String = "",
    val target: String = "",
    val damage: Int = 0,
    val damageType: String = "",
    val isCrit: Boolean = false,
    val isKill: Boolean = false,
    val message: String = "",
    val skillName: String? = null
)

// 战斗日志结果
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

// AI洞府探索队伍（用于GameData）
@Serializable
data class AICaveTeam(
    val id: String = java.util.UUID.randomUUID().toString(),
    val caveId: String = "",
    val sectId: String = "",
    val sectName: String = "",
    val memberCount: Int = 5,
    val avgRealm: Int = 5,
    val avgRealmName: String = "",
    val disciples: List<AICaveDisciple> = emptyList(),
    val status: AITeamStatus = AITeamStatus.EXPLORING
) {
    val isExploring: Boolean get() = status == AITeamStatus.EXPLORING
    val isDefeated: Boolean get() = status == AITeamStatus.DEFEATED
}
