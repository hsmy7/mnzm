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

    suspend fun requestAllianceSimple(sectId: String): AllianceResult {
        val success = diplomacyFacade.requestAllianceSimple(sectId)
        return AllianceResult(success, if (success) "结盟成功" else "结盟失败")
    }

    suspend fun dissolveAllianceSimple(sectId: String): AllianceResult {
        val success = diplomacyFacade.dissolveAllianceSimple(sectId)
        return AllianceResult(success, if (success) "已解除结盟" else "解除结盟失败")
    }
}
