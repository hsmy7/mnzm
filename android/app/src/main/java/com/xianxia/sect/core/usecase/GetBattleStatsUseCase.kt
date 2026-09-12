package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetBattleStatsUseCase @Inject constructor(
    private val battleFacade: BattleFacade
) {
    data class BattleStats(
        val totalBattles: Int,
        val winRate: Double
    )

    operator fun invoke(lastNBattles: Int = 50): BattleStats {
        return BattleStats(
            totalBattles = battleFacade.getTotalBattlesCount(),
            winRate = battleFacade.getWinRate(lastNBattles)
        )
    }
}
