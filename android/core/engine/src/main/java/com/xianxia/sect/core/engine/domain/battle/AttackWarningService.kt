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
 * AI宗门进攻预警服务——管理单级"即将进攻"预警生命周期。
 *
 * 流程简化：AI 决定进攻时生成预警（下个月进攻），弹窗纯通知（仅"知道了"），
 * 到期后由 [PlayerDefenseProcessor] 自动执行防守战。
 */
@Singleton
@GameService("AttackWarningService")
class AttackWarningService @Inject constructor(
    private val stateStore: GameStateStore
) {

    /** 生成"即将进攻"预警（单级）：下个月直接进攻 */
    fun createImminentAttackWarning(
        attackerSectId: String,
        attackerSectName: String
    ): AttackWarning {
        val data = stateStore.gameData.value
        val nowMonth = data.gameYear * 12 + data.gameMonth
        return AttackWarning(
            warningId = UUID.randomUUID().toString(),
            attackerSectId = attackerSectId,
            attackerSectName = attackerSectName,
            stage = WarningStage.WAR_DECLARATION,
            attackMonth = nowMonth + GameConfig.AIAttack.WARNING_BEFORE_ATTACK_MONTHS,
            createdAtMonth = nowMonth
        )
    }

    /**
     * 旧档/历史预警收敛：统一为战书阶段、attackMonth = 下月（幂等）。
     *
     * 两级预警（谴责→战书）移除后，旧档残留的 DENUNCIATION 或更晚 attackMonth
     * 的预警按新语义收敛；**已到期的预警保持原值**，使其在本批结算中立即执行
     * （避免把"本月就该打的仗"推迟一个月）。未变化的预警不产生新实例，
     * `updated != old` 守卫避免每月无谓写入 GameData。
     */
    fun normalizeImminentWarningsSync(state: MutableGameState) {
        val data = state.gameData
        val nowMonth = data.gameYear * 12 + data.gameMonth
        val target = nowMonth + GameConfig.AIAttack.WARNING_BEFORE_ATTACK_MONTHS
        val updated = data.activeAttackWarnings.map { warning ->
            if (warning.stage != WarningStage.WAR_DECLARATION || warning.attackMonth > target) {
                warning.copy(stage = WarningStage.WAR_DECLARATION, attackMonth = target)
            } else {
                warning
            }
        }
        if (updated != data.activeAttackWarnings) {
            state.gameData = data.copy(activeAttackWarnings = updated)
        }
    }

    /** 添加预警到活跃列表（同步版本，月度结算用） */
    fun addWarningSync(state: MutableGameState, warning: AttackWarning) {
        state.gameData = state.gameData.copy(
            activeAttackWarnings = state.gameData.activeAttackWarnings + warning
        )
    }
}
