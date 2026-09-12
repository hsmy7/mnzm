package com.xianxia.sect.core.engine.domain.production

import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductionFacadeImpl @Inject constructor(
    private val productionCoordinator: ProductionCoordinator
) : ProductionFacade {
    override val productionSlots: StateFlow<List<ProductionSlot>> get() = productionCoordinator.slots
}
