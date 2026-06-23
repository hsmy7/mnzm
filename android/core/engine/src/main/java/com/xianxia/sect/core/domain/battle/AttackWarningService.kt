package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.AttackWarning
import com.xianxia.sect.core.model.WarningStage
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI宗门进攻预警服务——管理谴责→战书二级预警生命周期。
 */
@Singleton
@GameService("AttackWarningService")
class AttackWarningService @Inject constructor(
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider
) {
    private val scope get() = scopeProvider.scope

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
    fun cancelWarningsForAttacker(attackerSectId: String): Int {
        val data = stateStore.gameData.value
        val warnings = data.activeAttackWarnings
        val remaining = warnings.filter {
            it.attackerSectId != attackerSectId
        }
        val cancelled = warnings.size - remaining.size
        if (cancelled > 0) {
            scope.launch {
                stateStore.update {
                    gameData = gameData.copy(activeAttackWarnings = remaining)
                }
            }
        }
        return cancelled
    }

    /** 检查并处理到期的预警，返回需执行战斗的预警列表 */
    fun checkExpiredWarnings(): List<AttackWarning> {
        val data = stateStore.gameData.value
        val nowMonth = data.gameYear * 12 + data.gameMonth
        val warnings = data.activeAttackWarnings

        val expired = warnings.filter {
            it.stage == WarningStage.WAR_DECLARATION &&
                nowMonth >= it.attackMonth
        }

        if (expired.isNotEmpty()) {
            val expiredIds = expired.map { it.warningId }.toSet()
            scope.launch {
                stateStore.update {
                    gameData = gameData.copy(
                        activeAttackWarnings = gameData.activeAttackWarnings.filter {
                            it.warningId !in expiredIds
                        }
                    )
                }
            }
        }
        return expired
    }

    /** 推进需要升级阶段的预警（谴责→战书），返回刚升级的预警列表 */
    fun advanceWarningsIfNeeded(): List<AttackWarning> {
        val data = stateStore.gameData.value
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
            scope.launch {
                stateStore.update {
                    gameData = gameData.copy(activeAttackWarnings = updatedWarnings)
                }
            }
        }
        return newlyAdvanced
    }

    /** 添加预警到活跃列表 */
    fun addWarning(warning: AttackWarning) {
        scope.launch {
            stateStore.update {
                gameData = gameData.copy(
                    activeAttackWarnings = gameData.activeAttackWarnings + warning
                )
            }
        }
    }

    /** 获取所有活跃预警 */
    fun getActiveWarnings(): List<AttackWarning> {
        return stateStore.gameData.value.activeAttackWarnings
    }
}
