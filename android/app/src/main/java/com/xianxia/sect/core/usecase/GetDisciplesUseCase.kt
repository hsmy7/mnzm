package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.model.DiscipleAggregate
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetDisciplesUseCase @Inject constructor(
    private val discipleFacade: DiscipleFacade
) {
    operator fun invoke(): StateFlow<List<DiscipleAggregate>> {
        return discipleFacade.discipleAggregates
    }
}
