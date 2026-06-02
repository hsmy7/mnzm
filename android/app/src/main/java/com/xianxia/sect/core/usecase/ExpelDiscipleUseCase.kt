package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExpelDiscipleUseCase @Inject constructor(
    private val discipleFacade: DiscipleFacade
) {
    suspend operator fun invoke(discipleId: String): Result<Boolean> = runCatching {
        discipleFacade.expelDisciple(discipleId)
    }
}
