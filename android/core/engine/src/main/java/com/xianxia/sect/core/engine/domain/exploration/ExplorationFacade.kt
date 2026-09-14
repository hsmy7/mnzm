package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacade
import com.xianxia.sect.core.engine.service.SecretRealmService

/**
 * 探索域服务归组门面。
 * 仅聚合服务引用供 GameEngine 访问器转发，方法体保留在 GameEngine/各 Service。
 */
interface ExplorationFacade {
    val explorationService: ExplorationService
    val secretRealmService: SecretRealmService
    val diplomacyService: com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService
    val diplomacyFacade: DiplomacyFacade
}
