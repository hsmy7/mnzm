package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.domain.production.EconomySubsystem
import com.xianxia.sect.core.engine.domain.production.ProductionSubsystem
import com.xianxia.sect.core.engine.service.CultivationService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 领域服务门面：聚合修炼、生产、经济、探索四个领域服务。
 *
 * 用于缩减 [SettlementCoordinator] 的构造参数数量。
 */
@Singleton
class SettlementDomainFacade @Inject constructor(
    val cultivationService: CultivationService,
    val productionSubsystem: ProductionSubsystem,
    val economySubsystem: EconomySubsystem,
    val explorationService: ExplorationService
)
