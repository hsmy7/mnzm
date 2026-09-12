package com.xianxia.sect.core.engine.domain.cultivation

import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.domain.road.RoadFacade
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.service.FormulaService
import com.xianxia.sect.core.engine.service.LawEnforcementProcessor
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator

/**
 * 修炼/生产域服务归组门面。
 * 仅聚合服务引用供 GameEngine 访问器转发，方法体保留在 GameEngine/各 Service。
 */
interface CultivationFacade {
    val cultivationService: CultivationService
    val discipleService: DiscipleService
    val productionCoordinator: ProductionCoordinator
    val formulaService: FormulaService
    val lawEnforcementProcessor: LawEnforcementProcessor
    val discipleFacade: DiscipleFacade
    val productionFacade: ProductionFacade
    val buildingFacade: BuildingFacade
    val roadFacade: RoadFacade
}
