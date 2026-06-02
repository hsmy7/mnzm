package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacade
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManageAllianceUseCase @Inject constructor(
    private val diplomacyFacade: DiplomacyFacade
) {
    data class AllianceResult(
        val success: Boolean,
        val message: String
    )

    fun requestAlliance(sectId: String, envoyDiscipleId: String): AllianceResult {
        val (success, message) = diplomacyFacade.requestAlliance(sectId, envoyDiscipleId)
        return AllianceResult(success, message)
    }

    fun dissolveAlliance(sectId: String): AllianceResult {
        val (success, message) = diplomacyFacade.dissolveAlliance(sectId)
        return AllianceResult(success, message)
    }
}
