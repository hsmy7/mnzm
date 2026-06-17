package com.xianxia.sect.core.engine.domain.production

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.event.BuildingCompletedEvent
import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.DomainEventSubscriber
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@SystemPriority(order = 206)
@Singleton
class EconomySubsystem @Inject constructor(
    private val cultivationService: CultivationService,
    private val scopeProvider: CoroutineScopeProvider,
    private val eventBus: EventBusPort
) : GameSystem, DomainEventSubscriber {

    companion object {
        private const val TAG = "EconomySubsystem"
        const val SYSTEM_NAME = "EconomySubsystem"
    }

    private val scope get() = scopeProvider.scope

    override val systemName: String = SYSTEM_NAME
    override val focusDomain = FocusDomain.BUILDINGS

    override val subscribedTypes: Set<String> = setOf("building_completed")

    override fun onEvent(event: DomainEvent) {
        if (event !is BuildingCompletedEvent) return
        // 灵矿产出由月度结算 processSpiritMineProduction(shadow) 统一处理，
        // 不再通过事件异步触发以避免影子事务覆盖问题。
    }

    override fun initialize() {
        eventBus.subscribe(this)
        DomainLog.d(TAG, "EconomySubsystem initialized")
    }

    override fun release() {
        eventBus.unsubscribe(this)
        DomainLog.d(TAG, "EconomySubsystem released")
    }

    override suspend fun clearForSlot(slotId: Int) {}

    override suspend fun onMonthTick(state: MutableGameState) {
        cultivationService.processPolicyCosts(state)
        // 居住忠诚度加成由 SettlementCoordinator.calculateLoyaltyDelta() 统一处理，
        // 此处不再重复调用 processResidenceLoyalty() 以避免双重加成。
    }
}
