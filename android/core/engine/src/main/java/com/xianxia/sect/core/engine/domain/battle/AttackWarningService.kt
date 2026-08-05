package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.AttackWarning
import com.xianxia.sect.core.model.WarningStage
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.annotation.GameService
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI宗门进攻预警服务——管理谴责→战书二级预警生命周期。
 */
@Singleton
@GameService("AttackWarningService")
class AttackWarningService @Inject constructor(
    private val stateStore: GameStateStore
) {

    companion object {
        private const val TAG = "AttackWarningService"
    }

    /** 为指定攻击生成谴责预警（进攻前6个月） */
    fun createDenunciationWarning(
        attackerSectId: String,
        attackerSectName: String
    ): AttackWarning {
        val data = stateStore.gameData.value
        val nowMonth = data.gameYear * 12 + data.gameMonth
        val attackMonth = nowMonth +
            GameConfig.AIAttack.DENUNCIATION_BEFORE_ATTACK_MONTHS
        return AttackWarning(
            warningId = UUID.randomUUID().toString(),
            attackerSectId = attackerSectId,
            attackerSectName = attackerSectName,
            stage = WarningStage.DENUNCIATION,
            attackMonth = attackMonth,
            createdAtMonth = nowMonth
        )
    }

    /** 将谴责预警推进为战书预警 */
    fun advanceToWarDeclaration(warning: AttackWarning): AttackWarning {
        return warning.copy(stage = WarningStage.WAR_DECLARATION)
    }

    /** 取消指定攻击方的所有预警，返回被取消数量 */
    suspend fun cancelWarningsForAttacker(attackerSectId: String): Int {
        var cancelled = 0
        stateStore.update {
            val warnings = gameData.activeAttackWarnings
            val remaining = warnings.filter { it.attackerSectId != attackerSectId }
            cancelled = warnings.size - remaining.size
            if (cancelled > 0) {
                gameData = gameData.copy(activeAttackWarnings = remaining)
            }
        }
        return cancelled
    }

    /** 检查并处理到期的预警，返回需执行战斗的预警列表 */
    suspend fun checkExpiredWarnings(): List<AttackWarning> {
        var expired: List<AttackWarning> = emptyList()
        stateStore.update {
            val nowMonth = gameData.gameYear * 12 + gameData.gameMonth
            expired = gameData.activeAttackWarnings.filter {
                it.stage == WarningStage.WAR_DECLARATION &&
                    nowMonth >= it.attackMonth
            }

            if (expired.isNotEmpty()) {
                val expiredIds = expired.map { it.warningId }.toSet()
                gameData = gameData.copy(
                    activeAttackWarnings = gameData.activeAttackWarnings.filter {
                        it.warningId !in expiredIds
                    }
                )
            }
        }
        return expired
    }

    /** 推进需要升级阶段的预警（谴责→战书），返回刚升级的预警列表 */
    suspend fun advanceWarningsIfNeeded(): List<AttackWarning> {
        val newlyAdvanced = mutableListOf<AttackWarning>()
        stateStore.update {
            val nowMonth = gameData.gameYear * 12 + gameData.gameMonth

            val updatedWarnings = gameData.activeAttackWarnings.map { warning ->
                if (warning.stage == WarningStage.DENUNCIATION &&
                    nowMonth >= warning.attackMonth -
                        GameConfig.AIAttack.WAR_WARNING_BEFORE_ATTACK_MONTHS
                ) {
                    val advanced = warning.copy(stage = WarningStage.WAR_DECLARATION)
                    newlyAdvanced.add(advanced)
                    advanced
                } else {
                    warning
                }
            }

            if (newlyAdvanced.isNotEmpty()) {
                gameData = gameData.copy(activeAttackWarnings = updatedWarnings)
            }
        }
        return newlyAdvanced
    }

    /** 添加预警到活跃列表 */
    suspend fun addWarning(warning: AttackWarning) {
        stateStore.update {
            gameData = gameData.copy(
                activeAttackWarnings = gameData.activeAttackWarnings + warning
            )
        }
    }

    /** 获取所有活跃预警 */
    fun getActiveWarnings(): List<AttackWarning> {
        return stateStore.gameData.value.activeAttackWarnings
    }

    // ── 同步版本：直接操作 MutableGameState，供 BattleTickSystem 使用 ──

    /**
     * 同步推进预警阶段（谴责→战书），直接在 [state] 上修改。
     * @return 刚升级的预警列表
     */
    fun advanceWarningsIfNeededSync(state: MutableGameState): List<AttackWarning> {
        val data = state.gameData
        val nowMonth = data.gameYear * 12 + data.gameMonth
        val newlyAdvanced = mutableListOf<AttackWarning>()

        val updatedWarnings = data.activeAttackWarnings.map { warning ->
            if (warning.stage == WarningStage.DENUNCIATION &&
                nowMonth >= warning.attackMonth -
                    GameConfig.AIAttack.WAR_WARNING_BEFORE_ATTACK_MONTHS
            ) {
                val advanced = warning.copy(stage = WarningStage.WAR_DECLARATION)
                newlyAdvanced.add(advanced)
                advanced
            } else {
                warning
            }
        }

        if (newlyAdvanced.isNotEmpty()) {
            state.gameData = data.copy(activeAttackWarnings = updatedWarnings)
        }
        return newlyAdvanced
    }

    /**
     * 同步检查到期预警，直接在 [state] 上修改（移除已到期）。
     * @return 到期需执行战斗的预警列表
     */
    fun checkExpiredWarningsSync(state: MutableGameState): List<AttackWarning> {
        val data = state.gameData
        val nowMonth = data.gameYear * 12 + data.gameMonth

        val expired = data.activeAttackWarnings.filter {
            it.stage == WarningStage.WAR_DECLARATION &&
                nowMonth >= it.attackMonth
        }

        if (expired.isNotEmpty()) {
            val expiredIds = expired.map { it.warningId }.toSet()
            state.gameData = data.copy(
                activeAttackWarnings = data.activeAttackWarnings.filter {
                    it.warningId !in expiredIds
                }
            )
        }
        return expired
    }

    /**
     * 同步添加预警，直接在 [state] 上修改。
     */
    fun addWarningSync(state: MutableGameState, warning: AttackWarning) {
        state.gameData = state.gameData.copy(
            activeAttackWarnings = state.gameData.activeAttackWarnings + warning
        )
    }
}
