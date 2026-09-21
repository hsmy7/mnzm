package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.exploration.BeastAttackDetector
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.domain.disciple.battleWritebackMaxHpMp
import com.xianxia.sect.core.engine.domain.disciple.applyGriefToRelatives

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── 妖兽袭击域（自 ExplorationService 拆出，行为零变更） ─────────────────────

private val TAG = ExplorationService.TAG
/**
 * 执行上月排期的妖兽攻击（自动防守）并清空排期。
 *
 * 排期妖兽若已被击败/消失（巡视塔、AI 宗门、玩家手动进攻、过期清理），
 * 自动跳过不战斗；全部处理完后清空排期——玩家未关闭预警弹窗时，
 * 排期清空即弹窗自动关闭。单只失败仅记日志，不阻断月度结算。
 * 必须在本月结算事务（[GameStateStore.update]）内调用。
 *
 * @param state 事务内可变状态
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun ExplorationService.executeScheduledBeastAttacks(state: MutableGameState) {
    try {
        val scheduled = stateStore.pendingBeastAttacks.value
        if (scheduled.isEmpty()) return
        for (attack in scheduled) {
            try {
                executeScheduledBeastAttack(state, attack.beastLevel.id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                DomainLog.e(TAG, "executeScheduledBeastAttacks failed: " +
                    "beastId=${attack.beastLevel.id}", e)
            }
        }
        stateStore.setPendingBeastAttacks(emptyList())
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) {
        DomainLog.e(TAG, "executeScheduledBeastAttacks failed", e)
    }
}

/**
 * 执行已排期的妖兽攻击（月度结算内自动防守）。
 *
 * 排期于上月 [BeastAttackDetector.detectAttacks] 生成，本月结算时自动执行；
 * 必须在本月结算事务（[GameStateStore.update]）内调用。
 * 妖兽已不在（被击败/过期/清理）时直接视为已处理（返回 false，仅清理排期）。
 *
 * @param state 事务内可变状态
 * @param beastLevelId 妖兽关卡 ID
 * @return 是否真正执行了战斗（false = 妖兽已不存在/已击败，仅需清理排期）
 */

fun ExplorationService.executeScheduledBeastAttack(
    state: MutableGameState,
    beastLevelId: String
): Boolean {
    val level = state.gameData.worldLevels.find { it.id == beastLevelId }
    if (level == null || level.defeated) return false
    // B18-P4：遭遇战分支已删——`aiBeastEncounterTargets` 全仓零插入者 ⇒ 恒空 ⇒
    // 原 resolveEncounterPath 首行门判恒 false（死路径，B18 复核确证），直走妖兽战斗
    state.resolveBeastFightInternal(beastLevelId, level)
    return true
}

suspend fun ExplorationService.resolveBeastAttackFight(
    beastLevelId: String
): Boolean {
    val snapshot = stateStore.gameData.value
    val level = snapshot.worldLevels.find { it.id == beastLevelId } ?: return false
    if (level.defeated) return false
    var handled = false
    // 捕获豁免（updateMirror，§2.81）：迎战写面（worldLevels/战报 + 战利品经
    // 统一入口 addXxx 重入本事务写 9 类实体集合——已关闭回导，引用比较检测
    // 不适用值等值收敛）——写入经 GameEngine 尾部基线重建（"妖兽迎战"/
    // "遭遇战分支"）回导 C++
    stateStore.updateMirror {
        // 锁内二次检查 defeated（防 TOCTOU：锁外快照可能已过时）
        val currentLevel = gameData.worldLevels.find { it.id == beastLevelId }
        if (currentLevel == null || currentLevel.defeated) return@updateMirror

        // B18-P4：遭遇战分支已删（死路径，见 executeScheduledBeastAttack 同注）
        handled = true
        resolveBeastFightInternal(beastLevelId, level)
    }
    return handled
}

// ── 遭遇战路径（B18-P4 死臂清理，三函数已删）─────────────────────────────
// 原 `resolveEncounterPath` / `launchEncounterBattle` / `selectBeastDefenders`
// 已删除：门判源 `gameData.aiBeastEncounterTargets` 全仓（Kotlin + C++）**零插入者**
// ⇒ 恒空 ⇒ 门判恒 false（遭遇战分支自始不可执行；B18 复核 + 本批 grep 再证）。
//
// ⚠️ 字段本体**保留**（判归 = 值保留：镜像承载现值不被清空，
// `GameDataFieldPatchGuardTest` 锁定；C++ 侧 `exploration_tx.h` 字段零改动）。
// **复活须知**：接上"插入者"（AI 宗门盯上妖兽的写入点）**不会**自动恢复本特性
// ——消费链已随本批删除，须一并重建上述三函数（对照面见 git 历史本文件）。

// ── 内部战斗编排（≤60 行，委派各子阶段） ─────────────────────────────

internal fun MutableGameState.markBeastDefeated(
    beastLevelId: String
) {
    gameData = gameData.copy(
        worldLevels = gameData.worldLevels.map {
            if (it.id == beastLevelId) it.copy(defeated = true) else it
        }
    )
}

// ── 无守卫处理 ─────────────────────────────────────────────────────────

internal fun MutableGameState.processBeastCasualties(
    result: BattleSystemResult, targetSect: WorldSect,
    disciples: List<Disciple>
): Pair<List<Disciple>, Set<String>> {
    val garrisonIds = targetSect.garrisonSlots
        .filter { it.discipleId.isNotEmpty() }
        .map { it.discipleId }.toSet()

    val hpMap = result.battle.team.associate {
        it.id to (it.hp to it.mp)
    }
    val survivorIds = result.battle.team.filter { !it.isDead }
        .map { it.id }.toSet()
    val deadDefenders = disciples.filter {
        it.id in garrisonIds && it.id !in survivorIds
    }

    var processed = disciples.map { d ->
        val (hp, mp) = hpMap[d.id] ?: return@map d
        if (d.id !in survivorIds) {
            d.copy(
                isAlive = false, status = DiscipleStatus.DEAD
            )
        } else {
            val (finalMaxHp, finalMaxMp) = DiscipleStatCalculator.battleWritebackMaxHpMp(this, d)
            d.copy(combat = d.combat.copy(
                currentHp = hp.coerceIn(0, finalMaxHp),
                currentMp = mp.coerceIn(0, finalMaxMp)
            ))
        }
    }

    if (deadDefenders.isNotEmpty()) {
        processed = DiscipleStatCalculator.applyGriefToRelatives(
            processed, deadDefenders, gameData.gameYear
        )
    }

    /** 本场永久死亡弟子 ID（调用方事务外触发哀伤） */
    val deadIds = processed.filter { !it.isAlive }
        .map { it.id }.toSet()
    if (deadIds.isNotEmpty()) {
        gameData = gameData.copy(
            worldMapSects = gameData.worldMapSects.map { sect ->
                if (sect.id == targetSect.id) {
                    sect.copy(
                        garrisonSlots =
                            sect.garrisonSlots.map { slot ->
                                if (slot.discipleId in deadIds) {
                                    GarrisonSlot(index = slot.index)
                                } else slot
                            }
                    )
                } else sect
            }
        )
    }

    return processed to survivorIds
}

// ── 胜利奖励：神魂+随机属性 ──────────────────────────────────────────
