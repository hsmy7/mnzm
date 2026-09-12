package com.xianxia.sect.ui.game.dialogs.heavenlytrial

import androidx.compose.ui.geometry.Offset

/**
 * 天劫试炼战斗阶段
 */
internal enum class BattlePhase { PLAYER_TURN, ENEMY_TURN, WON, LOST }

/**
 * 单体攻击动画事件
 */
internal data class AttackAnimationEvent(
    val attackerId: String,
    val targetId: String,
    val damage: Int,
    val isCrit: Boolean,
    val isPhysical: Boolean,
    val isHeal: Boolean = false,
    val skillName: String? = null,
    val isKill: Boolean = false
)

/**
 * AoE 全体命中事件：一次飞行 + 所有目标同时受击
 */
internal data class AoeAnimationEvent(
    val attackerId: String,
    val targetIds: List<String>,
    val damages: Map<String, Int>,
    val crits: Map<String, Boolean>,
    val isPhysical: Boolean,
    val isHeal: Boolean = false,
    val skillName: String? = null
)

/**
 * 动画事件统一分发接口
 */
internal sealed interface AnimEvent {
    data class Single(val event: AttackAnimationEvent) : AnimEvent
    data class Aoe(val event: AoeAnimationEvent) : AnimEvent
}

/**
 * 浮动伤害数字状态
 */
internal data class DamageNumberState(
    val id: String = java.util.UUID.randomUUID().toString(),
    val damage: Int,
    val isCrit: Boolean,
    val isPhysical: Boolean,
    val isHeal: Boolean = false,
    val targetId: String
)

/**
 * 动画阶段
 */
internal enum class AnimPhase {
    IDLE, MOVE_TO_TARGET, IMPACT, RETURN_TO_START
}

/**
 * 攻击动画状态
 */
internal data class AttackAnimState(
    val attackerId: String? = null,
    val targetId: String? = null,
    val phase: AnimPhase = AnimPhase.IDLE,
    val overrideEnd: Offset? = null
)

/**
 * 每个格子的飞行动画信息
 */
internal data class FlightAnimState(
    val isActive: Boolean = false,
    val phase: AnimPhase = AnimPhase.IDLE,
    val deltaX: Float = 0f,
    val deltaY: Float = 0f
)
