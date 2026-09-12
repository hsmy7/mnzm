package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacade
import com.xianxia.sect.core.engine.service.SecretRealmService
import javax.inject.Inject
import javax.inject.Singleton

/** 探索域服务归组实现（D1，GameEngine 构造 33→7）。 */
@Singleton
class ExplorationFacadeImpl @Inject constructor(
    override val explorationService: ExplorationService,
    override val secretRealmService: SecretRealmService,
    override val diplomacyService: com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService,
    override val diplomacyFacade: DiplomacyFacade
) : ExplorationFacade
