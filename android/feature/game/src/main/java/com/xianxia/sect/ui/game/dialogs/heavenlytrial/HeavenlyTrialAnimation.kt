package com.xianxia.sect.ui.game.dialogs.heavenlytrial

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay

/**
 * 单体攻击动画序列（飞向目标、命中抖动、伤害数字、返回原位）
 *
 * ## EngineTween 迁移评估结论（2026-08-13，批次 1b）：明确不迁移
 *
 * 评估结论（详见 HeavenlyTrialAnimationGuardTest 守卫测试证据）：
 * 1. **结构性结算耦合**：`applyResult`（HP 结算应用）内嵌于本动画序列**尾部**——
 *    结算应用时机由动画序列结构决定（守卫测试断言"applyResult 必须为序列最后一个
 *    结算回调"）。动画非纯表现层，而是"结算应用的载体"。
 * 2. **机制不匹配**：本序列是离散相位编排（MOVE→IMPACT→RETURN→APPLY），非连续值
 *    插值——EngineTween/Timeline 的核心能力（缓动曲线/时间归一化）在此无用武之地；
 *    位移/抖动/伤害数字的实际插值已由 Compose Animatable + tween(LinearEasing) 完成
 *    （见 HeavenlyTrialComponents.kt），本文件只负责相位定速。
 * 3. **Compose 生命周期适配**：delay() 跑在 LaunchedEffect 结构化并发内（dispose 自动
 *    取消动画与结算协程）；EngineTween 的 TimeSource 轮询模型需额外帧时钟接线，
 *    收益为零、风险非零。
 *
 * 守卫测试（HeavenlyTrialAnimationGuardTest）已建立三条不变量，任何改动破坏即失败：
 * - 动画路径零 RNG 消耗（本地战斗 PRNG 快照前后全等，1000 次）
 * - 回调顺序：结算回调（applyResult）必须位于视觉相位序列末尾
 * - 固定种子 1000 次战斗结算序列（含 RNG 消耗序列）全等
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
