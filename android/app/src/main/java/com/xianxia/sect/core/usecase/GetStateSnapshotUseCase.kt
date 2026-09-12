package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.save.SaveFacade
import com.xianxia.sect.core.engine.GameStateSnapshot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetStateSnapshotUseCase @Inject constructor(
    private val saveFacade: SaveFacade
) {
    suspend operator fun invoke(): Result<GameStateSnapshot> = runCatching {
        saveFacade.getStateSnapshot()
    }
}
