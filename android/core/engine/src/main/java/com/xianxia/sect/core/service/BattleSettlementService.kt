package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 战斗前全量追赶结算服务。
 *
 * 职责：
 * - 战斗前 HP/MP 恢复、装备孕养追赶、功法熟练度追赶
 * - 单旬修炼结算
 */
@Singleton
@GameService("BattleSettlementService")
class BattleSettlementService @Inject constructor(
    private val hpMpRecoveryService: HpMpRecoveryService
) {

    /**
     * 战斗前对指定出战弟子执行 HP/MP 恢复。
     *
     * 功法熟练度和装备孕养已由每旬 [processManualProficiencyPerPhase] /
     * [processEquipmentNurturePerPhase] 统一处理，战斗前不再重复结算。
     *
     * @param state 可变游戏状态
     * @param discipleIds 出战弟子 ID 字符串列表
     */
    fun forceSettleDisciplesBeforeBattle(
        state: MutableGameState,
        discipleIds: List<String>
    ) {
        if (discipleIds.isEmpty()) return
        hpMpRecoveryService.recoverHpMpForBattleParticipants(state, discipleIds)
    }

    companion object {
        private const val TAG = "BattleSettlementService"
    }
}
