package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacade
import com.xianxia.sect.core.model.MerchantItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetSectTradeItemsUseCase @Inject constructor(
    private val diplomacyFacade: DiplomacyFacade
) {
    operator fun invoke(sectId: String): Result<List<MerchantItem>> = runCatching {
        diplomacyFacade.getOrRefreshSectTradeItems(sectId)
    }
}
