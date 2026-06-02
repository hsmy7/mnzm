package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.model.BattleLog
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetBattleLogsUseCase @Inject constructor(
    private val battleFacade: BattleFacade
) {
    operator fun invoke(): StateFlow<List<BattleLog>> {
        return battleFacade.battleLogs
    }
}
