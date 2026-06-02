package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.model.Disciple
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecruitDiscipleUseCase @Inject constructor(
    private val discipleFacade: DiscipleFacade
) {
    operator fun invoke(): Result<Disciple> = runCatching {
        discipleFacade.recruitDisciple()
    }
}
