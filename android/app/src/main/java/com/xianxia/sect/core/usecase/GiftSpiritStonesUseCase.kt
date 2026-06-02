package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacade
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GiftSpiritStonesUseCase @Inject constructor(
    private val diplomacyFacade: DiplomacyFacade
) {
    operator fun invoke(sectId: String, tier: Int): Result<DiplomacyService.GiftResult> = runCatching {
        diplomacyFacade.giftSpiritStones(sectId, tier)
    }
}
