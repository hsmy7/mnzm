package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProcessBattleCasualtiesUseCase @Inject constructor(
    private val battleFacade: BattleFacade
) {
    suspend operator fun invoke(
        deadMemberIds: Set<String>,
        survivorHpMap: Map<String, Int>,
        survivorMpMap: Map<String, Int> = emptyMap()
    ): Result<Unit> = runCatching {
        battleFacade.processBattleCasualties(deadMemberIds, survivorHpMap, survivorMpMap)
    }
}
