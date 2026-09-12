package com.xianxia.sect.core.engine.domain.production

import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.coroutines.flow.StateFlow

interface ProductionFacade {
    val productionSlots: StateFlow<List<ProductionSlot>>
}
