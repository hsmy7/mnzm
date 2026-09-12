package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EquipItemUseCase @Inject constructor(
    private val discipleFacade: DiscipleFacade
) {
    suspend operator fun invoke(discipleId: String, equipmentId: String): Result<DomainResult<Unit>> = runCatching {
        discipleFacade.equipEquipment(discipleId, equipmentId)
    }
}
