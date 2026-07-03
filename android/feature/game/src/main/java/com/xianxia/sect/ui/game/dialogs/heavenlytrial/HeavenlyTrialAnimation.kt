package com.xianxia.sect.ui.game.dialogs.heavenlytrial

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay

/**
 * 单体攻击动画序列（飞向目标、命中抖动、伤害数字、返回原位）
 */
internal suspend fun playAttackSequence(
    event: AttackAnimationEvent,
    cellPositions: Map<String, Offset>,
    currentAnimState: () -> AttackAnimState,
    setAnimState: (AttackAnimState) -> Unit,
    setShaking: (Set<String>) -> Unit,
    addDamageNumber: (DamageNumberState) -> Unit,
    applyResult: (AttackAnimationEvent) -> Unit
) {
    val aPos = cellPositions[event.attackerId]
    val tPos = cellPositions[event.targetId]

    if (aPos != null && tPos != null && !event.isHeal) {
        // 阶段 1: 攻击者移动至目标
        setAnimState(AttackAnimState(
            attackerId = event.attackerId,
            targetId = event.targetId,
            phase = AnimPhase.MOVE_TO_TARGET
        ))
        delay(300)

        // 阶段 2: 命中 + 抖动 + 伤害数字
        setAnimState(currentAnimState().copy(phase = AnimPhase.IMPACT))
        setShaking(setOf(event.targetId))

        addDamageNumber(DamageNumberState(
            damage = event.damage,
            isCrit = event.isCrit,
            isPhysical = event.isPhysical,
            isHeal = event.isHeal,
            targetId = event.targetId
        ))

        delay(250)
        setShaking(emptySet())

        // 阶段 3: 返回原位
        setAnimState(currentAnimState().copy(phase = AnimPhase.RETURN_TO_START))
        delay(300)

        // 阶段 4: 应用伤害
        applyResult(event)
        setAnimState(AttackAnimState())
    } else if (event.isHeal) {
        // Buff/治疗: 无位移，仅绿色数字
        addDamageNumber(DamageNumberState(
            damage = event.damage, isCrit = false,
            isPhysical = false, isHeal = true, targetId = event.targetId
        ))
        delay(300)
        applyResult(event)
        setAnimState(AttackAnimState())
    } else {
        applyResult(event)
        delay(100)
    }
}

/**
 * AoE 全体命中：飞向敌群中心一次 → 全体目标同时抖动+伤害数字 → 飞回 → 一次性结算
 */
internal suspend fun playAoeAttackSequence(
    event: AoeAnimationEvent,
    cellPositions: Map<String, Offset>,
    currentAnimState: () -> AttackAnimState,
    setAnimState: (AttackAnimState) -> Unit,
    setShaking: (Set<String>) -> Unit,
    addDamageNumber: (DamageNumberState) -> Unit,
    applyAoeResult: (AoeAnimationEvent) -> Unit
) {
    val aPos = cellPositions[event.attackerId]
    val targetPositions = event.targetIds.mapNotNull { cellPositions[it] }
    if (aPos == null || targetPositions.isEmpty()) {
        applyAoeResult(event)
        delay(100)
        return
    }
    val centerX = targetPositions.map { it.x }.average().toFloat()
    val centerY = targetPositions.map { it.y }.average().toFloat()
    val centerOffset = Offset(centerX, centerY)

    // 阶段 1: 攻击者飞向敌群中心
    setAnimState(AttackAnimState(
        attackerId = event.attackerId, targetId = event.targetIds.first(),
        phase = AnimPhase.MOVE_TO_TARGET, overrideEnd = centerOffset
    ))
    delay(300)

    // 阶段 2: 全体目标同时抖动 + 同时弹出伤害数字
    setAnimState(currentAnimState().copy(phase = AnimPhase.IMPACT, overrideEnd = centerOffset))
    setShaking(event.targetIds.toSet())

    event.targetIds.forEach { tid ->
        val dmg = event.damages[tid] ?: 0
        val crit = event.crits[tid] ?: false
        addDamageNumber(DamageNumberState(
            damage = dmg, isCrit = crit, isPhysical = event.isPhysical,
            isHeal = event.isHeal, targetId = tid
        ))
    }

    delay(300)
    setShaking(emptySet())

    // 阶段 3: 返回原位
    setAnimState(currentAnimState().copy(phase = AnimPhase.RETURN_TO_START, overrideEnd = centerOffset))
    delay(300)

    // 阶段 4: 一次性结算全体伤害
    applyAoeResult(event)
    setAnimState(AttackAnimState())
}
