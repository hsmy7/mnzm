package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.domain.battle.EncounterAttacker
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.exploration.BeastAttackDetector
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.WorldLevel
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
/** 妖兽防守弟子排除状态（selectBeastDefenders 两路径共享） */
private val BEAST_DEFENDER_EXCLUDE_STATUSES =
    ExplorationService.BEAST_DEFENDER_EXCLUDE_STATUSES
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
    // 遭遇战检查：妖兽附近有 AI 宗门拦截（与弹窗"迎战"路径一致）
    val resolvedByEncounter = resolveEncounterPath(state, beastLevelId, level, null)
    if (!resolvedByEncounter) {
        state.resolveBeastFightInternal(beastLevelId, level)
    }
    return true
}

suspend fun ExplorationService.resolveBeastAttackFight(
    beastLevelId: String,
    manualDefenders: List<Disciple>? = null
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

        // 遭遇战检查：妖兽附近有 AI 宗门拦截
        if (resolveEncounterPath(this, beastLevelId, level, manualDefenders)) {
            handled = true
            return@updateMirror
        }
        // 无遭遇战，走正常妖兽战斗路径
        handled = true
        resolveBeastFightInternal(beastLevelId, level)
    }
    return handled
}

/** 遭遇战路径（resolveBeastAttackFight 提取）；返回是否已处理（命中遭遇战且 AI 应战） */

internal fun ExplorationService.resolveEncounterPath(
    state: MutableGameState,
    beastLevelId: String,
    level: WorldLevel,
    manualDefenders: List<Disciple>?
): Boolean {
    val aiSectId = state.gameData.aiBeastEncounterTargets[beastLevelId]
    val aiSect = state.gameData.worldMapSects.find { it.id == aiSectId }
    val targetSect = state.gameData.worldMapSects.find {
        it.isPlayerSect || it.isPlayerOccupied
    }
    if (aiSectId == null || aiSect == null || targetSect == null) return false

    val defenders = selectBeastDefenders(state, manualDefenders)
    state.prepareBeastDefenders(defenders.map { it.id }.toSet())
    val aiTeam = state.gameData.aiSectDisciples[aiSectId]
        ?.filter { it.isAlive }
        ?.take(GameConfig.AI.TEAM_SIZE) ?: emptyList()

    // 防守弟子与 AI 应战队伍均非空才执行遭遇战
    val ready = defenders.isNotEmpty() && aiTeam.isNotEmpty()
    if (ready && aiSect != null && targetSect != null) {
        launchEncounterBattle(state, aiSect, targetSect, defenders, aiTeam, level, beastLevelId)
    }
    return ready
}

/** 遭遇战执行（resolveEncounterPath 提取） */

internal fun ExplorationService.launchEncounterBattle(
    state: MutableGameState,
    aiSect: WorldSect,
    targetSect: WorldSect,
    defenders: List<Disciple>,
    aiTeam: List<Disciple>,
    level: WorldLevel,
    beastLevelId: String
) {
    encounterBattleService.encounter(
        state = state,
        attackerA = EncounterAttacker(
            sectId = targetSect.id,
            sectName = targetSect.name,
            isPlayer = true,
            teamDisciples = defenders
        ),
        attackerB = EncounterAttacker(
            sectId = aiSect.id,
            sectName = aiSect.name,
            isPlayer = false,
            teamDisciples = aiTeam
        ),
        beast = level,
        year = state.gameData.gameYear,
        month = state.gameData.gameMonth
    )
    state.gameData = state.gameData.copy(
        aiBeastEncounterTargets =
            state.gameData.aiBeastEncounterTargets - beastLevelId
    )
}

/** 防守弟子选择（resolveEncounterPath 提取）：手动路径锁内重查，自动路径巡视塔弟子 */

internal fun ExplorationService.selectBeastDefenders(
    state: MutableGameState,
    manualDefenders: List<Disciple>?
): List<Disciple> {
    // 使用手动选择的弟子（世界地图进攻）或自动选择（弹窗迎战）
    // 手动选择路径在锁内重新查询弟子状态，避免锁外快照的 isAlive 过期
    return if (manualDefenders != null) {
        val manualIds = manualDefenders.map { it.id }.toSet()
        state.discipleTables.assembleAll()
            .filter { it.id in manualIds && it.isAlive && it.status !in BEAST_DEFENDER_EXCLUDE_STATUSES }
    } else {
        val pids = state.gameData.patrolSlots
            .filter { it.discipleId.isNotEmpty() }
            .map { it.discipleId }.toSet()
        state.discipleTables.assembleAll()
            .filter { it.id in pids && it.isAlive && it.status !in BEAST_DEFENDER_EXCLUDE_STATUSES }
            .take(8)
    }
}

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
