package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.model.DiscipleStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssignDiscipleWorkUseCase @Inject constructor(
    private val discipleFacade: DiscipleFacade
) {
    suspend operator fun invoke(discipleId: String, status: DiscipleStatus): Result<Unit> = runCatching {
        discipleFacade.updateDiscipleStatus(discipleId, status)
    }
}
