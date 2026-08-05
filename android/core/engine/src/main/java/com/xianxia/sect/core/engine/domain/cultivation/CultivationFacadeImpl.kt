package com.xianxia.sect.core.engine.domain.cultivation

import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.service.FormulaService
import com.xianxia.sect.core.engine.service.LawEnforcementProcessor
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/** 修炼/生产域服务归组实现（D1，GameEngine 构造 33→7）。 */
@Singleton
class CultivationFacadeImpl @Inject constructor(
    override val cultivationService: CultivationService,
    override val discipleService: DiscipleService,
    override val productionCoordinator: ProductionCoordinator,
    override val formulaService: FormulaService,
    override val lawEnforcementProcessor: LawEnforcementProcessor,
    override val discipleFacade: DiscipleFacade,
    override val productionFacade: ProductionFacade,
    override val buildingFacade: BuildingFacade
) : CultivationFacade
