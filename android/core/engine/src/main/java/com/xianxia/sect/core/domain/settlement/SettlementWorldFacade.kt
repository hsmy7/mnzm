package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.engine.system.ChildBirthSystem
import com.xianxia.sect.core.engine.system.MailSystem
import com.xianxia.sect.core.engine.system.PartnerSystem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界事件服务门面：聚合邮件、子嗣、伙伴三个系统。
 *
 * 用于缩减 [SettlementCoordinator] 的构造参数数量。
 */
@Singleton
class SettlementWorldFacade @Inject constructor(
    val mailSystem: MailSystem,
    val childBirthSystem: ChildBirthSystem,
    val partnerSystem: PartnerSystem
)
